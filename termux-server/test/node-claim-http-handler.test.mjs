import test from 'node:test';
import assert from 'node:assert/strict';
import { SelfRunClusterCoordinator } from '../src/cluster-coordinator.mjs';
import { fixture } from './helpers/lifecycle-fixture.mjs';
import { wireExecutionClaims } from '../src/execution-claims.mjs';
import { keyFor } from '../src/lifecycle.mjs';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';
class ClusterMemory {
  constructor() { this.body={schema:'selfrun-cluster-state-v1'}; this.revision=0; this.versions=new WeakMap(); this.conflicts=0; }
  async listMemberStates() { return []; }
  async upsertJson() {}
  async readCluster() { const body=structuredClone(this.body);this.versions.set(body,this.revision);return body; }
  async writeCluster(body,{expected}) {
    await this.beforeWrite?.(body);
    if(this.failWrites)throw new Error('synthetic cluster unavailable');
    if(this.conflicts-->0||this.versions.get(expected)!==this.revision)
      throw Object.assign(new Error('CAS conflict'),{http_status:412});
    this.body=structuredClone(body);this.revision++;
    await this.afterWrite?.(body);
  }
}
function node(shared,name,{active=true}={}) {
  const f=fixture();
  const coordinator=new SelfRunClusterCoordinator({stateStore:f.store,config:{version:'test'},transport:shared,
    serverName:name,serverPriority:name==='Tablet'?1:2,settleMs:0});
  coordinator.role=active?'ACTIVE':'STANDBY';coordinator.claimReadyAt=0;
  f.controller.canDispatch=()=>coordinator.canDispatch();
  f.canProcess=wireExecutionClaims({cluster:coordinator,controller:f.controller,transport:f.transport});
  f.coordinator=coordinator;f.shared=shared;
  return f;
}


import { SelfRunController } from '../src/controller.mjs';
import { SignalDispatchTransport } from '../src/signal-transport.mjs';
import fs from 'node:fs/promises';
import vm from 'node:vm';
const httpStart=(f,turn=1)=>({signalId:'http-start-'+turn,type:turn===1?'START':'NEXT',projectUrl:f.body.project_url,
 envelope:{TASK_ID:f.body.task_id,TURN_ID:'SAFE-TEST:turn:'+turn,REQUEST_ID:'SAFE-TEST:turn:'+turn+'-request',
 SELF_RUN_SKILL_DOCUMENT_ID:'skill',RESULT_DOCUMENT_ID:turn===1?'RESULT-1':'RESULT-2',REQUIREMENT_DOCUMENT_ID:'requirement'}});
async function exactHandler(f,http){
  const source=await fs.readFile(new URL('../src/server.mjs',import.meta.url),'utf8');
  const match=source.match(/const server = http\.createServer\(([\s\S]+?)\n\}\);/);assert.ok(match);
  return vm.runInNewContext('('+match[1]+'\n})',{
    URL,authorized:()=>true,readJson:async req=>req.body,keyFor,cluster:f.coordinator,controller:http,driveController:f.controller,
    json:(_res,status,body)=>({status,body}),
  });
}
test('production-wired HTTP owner accepts STOP while STANDBY and excludes unrelated START',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet'),transport=new SignalDispatchTransport(f.transport,f.store);
  const http=new SelfRunController({lifecycle:f.controller,transport,stateStore:f.store,config:{}});
  wireExecutionClaims({cluster:f.coordinator,controller:f.controller,transport});
  const handler=await exactHandler(f,http),call=body=>handler({method:'POST',url:'/v1/signals',body},{});
  try {
    const first=await call(httpStart(f));assert.equal(first.status,202);assert.equal(f.sends,1);
    f.coordinator.role='STANDBY';await f.controller.quiesceForStandby();
    const unrelated=httpStart(f,2);unrelated.envelope.TASK_ID='DIFFERENT';
    assert.equal((await call(unrelated)).status,409);
    const stopped=await call({signalId:'http-stop',type:'STOP',task_id:f.body.task_id,turn_id:f.body.turn_id,request_id:f.body.request_id,control_epoch:2});
    assert.equal(stopped.status,202);assert.equal(f.record(),null);await f.settle();assert.equal(shared.body.execution_claims,undefined);
  } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});
test('legacy HTTP current work without first claim preserves observation but does not infer input authority',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet'),transport=new SignalDispatchTransport(f.transport,f.store);
  f.rows.clear();f.controller.executionClaims=null;
  const http=new SelfRunController({lifecycle:f.controller,transport,stateStore:f.store,config:{}});
  await http.accept(httpStart(f));await f.settle();assert.equal(f.sends,1);
  await f.controller.quiesceForStandby();f.controller=f.recreate();f.controller.canDispatch=()=>f.coordinator.canDispatch();
  await f.controller.initialize();f.canProcess=wireExecutionClaims({cluster:f.coordinator,controller:f.controller,transport});
  const watcher=new DriveDispatchWatcher({transport,controller:f.controller,config:{},canDispatch:f.canProcess});
  try {
    await watcher.scanOnce();await f.settle();await f.controller.tick(transport);await f.settle();
    console.log(JSON.stringify({case:'http_upgrade',record:!!f.record(),sessions:f.controller.sessions.size,claims:shared.body.execution_claims??null,sends:f.sends}));
    assert.ok(f.record());assert.equal(f.controller.sessions.size,1,'legacy current HTTP observation should remain');assert.equal(shared.body.execution_claims,undefined);assert.equal(f.sends,1);
  } finally {watcher.stop();f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});


test('cold standby HTTP owner accepts only cancellation controls before first successful claim read',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Phone'),transport=new SignalDispatchTransport(f.transport,f.store);
  let http=new SelfRunController({lifecycle:f.controller,transport,stateStore:f.store,config:{}});
  wireExecutionClaims({cluster:f.coordinator,controller:f.controller,transport});await http.accept(httpStart(f));await f.settle();
  f.controller.executionClaims=null;await f.controller.quiesceForStandby();f.controller=f.recreate();await f.controller.initialize();
  f.coordinator.role='STANDBY';f.coordinator.executionClaims.local.clear();f.controller.canDispatch=()=>f.coordinator.canDispatch();
  wireExecutionClaims({cluster:f.coordinator,controller:f.controller,transport});http=new SelfRunController({lifecycle:f.controller,transport,stateStore:f.store,config:{}});
  const handler=await exactHandler(f,http);
  try {
    const base={task_id:f.body.task_id,turn_id:f.body.turn_id,request_id:f.body.request_id};
    const call=body=>handler({method:'POST',url:'/v1/signals',body},{});
    assert.equal((await call({...base,signalId:'cold-resume',type:'RESUME',control_epoch:2,conversation_url:f.probe.url})).status,409);
    assert.equal((await call({...base,request_id:'DIFFERENT',signalId:'wrong-stop',type:'STOP',control_epoch:2})).status,409);
    assert.notEqual((await call({...base,signalId:'old-stop',type:'STOP',control_epoch:1})).status,202);assert.ok(f.record());
    const response=await handler({method:'POST',url:'/v1/signals',body:{signalId:'cold-stop',type:'STOP',task_id:f.body.task_id,turn_id:f.body.turn_id,request_id:f.body.request_id,control_epoch:2}},{});
    console.log(JSON.stringify({case:'cold_standby_http_stop',httpStatus:response.status,stillCurrent:!!f.record(),remoteOwner:shared.body.execution_claims?.[keyFor(f.body)]?.node}));
    assert.equal(response.status,202,'current cancellation must not await shared ownership refresh');assert.equal(f.record(),null);await f.settle();assert.equal(shared.body.execution_claims,undefined,'healthy provider must retire own cold claim after STOP');
  } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});


test('cold HTTP STOP during provider outage discards local state without a task cleanup queue',async()=>{
 const shared=new ClusterMemory(),f=node(shared,'Phone'),transport=new SignalDispatchTransport(f.transport,f.store);
 let http=new SelfRunController({lifecycle:f.controller,transport,stateStore:f.store,config:{}});
 wireExecutionClaims({cluster:f.coordinator,controller:f.controller,transport});await http.accept(httpStart(f));await f.settle();
 f.controller.executionClaims=null;await f.controller.quiesceForStandby();f.controller=f.recreate();await f.controller.initialize();
 f.coordinator.role='STANDBY';f.coordinator.executionClaims.local.clear();f.controller.canDispatch=()=>f.coordinator.canDispatch();
 wireExecutionClaims({cluster:f.coordinator,controller:f.controller,transport});http=new SelfRunController({lifecycle:f.controller,transport,stateStore:f.store,config:{}});
 shared.failWrites=true;const handler=await exactHandler(f,http),base={task_id:f.body.task_id,turn_id:f.body.turn_id,request_id:f.body.request_id};
 const response=await handler({method:'POST',url:'/v1/signals',body:{...base,signalId:'offline-stop',type:'STOP',control_epoch:2}},{});await f.settle();
 assert.equal(response.status,202);assert.equal(f.record(),null);assert.equal(f.coordinator.executionClaims.local.size,0);
 assert.deepEqual(Object.keys(f.coordinator.executionClaims).sort(),['local','node','transport']);
 assert.equal(shared.body.execution_claims[keyFor(f.body)].node,'Phone','remote removal is not asserted during outage');
});
