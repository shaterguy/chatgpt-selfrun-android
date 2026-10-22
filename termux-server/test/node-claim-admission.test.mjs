import test from 'node:test';
import assert from 'node:assert/strict';
import { SelfRunClusterCoordinator } from '../src/cluster-coordinator.mjs';
import { fixture } from './helpers/lifecycle-fixture.mjs';
import { wireExecutionClaims } from '../src/execution-claims.mjs';
import { keyFor } from '../src/lifecycle.mjs';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';
import fs from 'node:fs/promises';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';

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

test('two ACTIVE nodes race on a URL-less request: exactly one record, target preparation and submission',async()=>{
  const shared=new ClusterMemory(),a=node(shared,'Tablet'),b=node(shared,'Phone');
  try {
    await Promise.all([a.ingest(),b.ingest()]);
    assert.equal(Number(!!a.record())+Number(!!b.record()),1);
    assert.equal(a.prepares+b.prepares,1);assert.equal(a.sends+b.sends,1);
  } finally {await a.controller.quiesceForStandby();await b.controller.quiesceForStandby();}
});

test('a different node cannot import an existing owner even after global leadership changes',async()=>{
  const shared=new ClusterMemory(),a=node(shared,'Tablet'),b=node(shared,'Phone');
  try {
    await a.existing();b.control={...a.control,conversation_url:a.probe.url};b.body={...a.body};
    await b.ingest();
    assert.equal(b.record(),null);assert.equal(b.resumes,0);assert.equal(b.sends,0);
    a.coordinator.role='STANDBY';await a.controller.quiesceForStandby();
    assert.equal(a.controller.sessions.size,1);
    await a.observe({responseTextLength:1351});assert.equal(a.record().cursor.responseTextLength,1351);
  } finally {for(const f of [a,b]){f.controller.executionClaims=null;await f.controller.quiesceForStandby();}}
});

test('demotion before a new claim returns creates no local record or browser effect',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Phone');
  shared.afterWrite=async()=>{f.coordinator.role='STANDBY';await f.controller.quiesceForStandby();};
  try {await f.ingest();assert.equal(f.record(),null);assert.equal(f.prepares,0);assert.equal(f.sends,0);}
  finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

test('same-node process restart reattaches the existing claim without resubmission; other node stays excluded',async()=>{
  const shared=new ClusterMemory(),a=node(shared,'Tablet');await a.existing();
  const key=keyFor(a.body),token=shared.body.execution_claims[key].token;
  a.controller.executionClaims=null;await a.controller.quiesceForStandby();
  a.controller=a.recreate();a.coordinator.role='STANDBY';a.controller.canDispatch=()=>a.coordinator.canDispatch();
  a.canProcess=wireExecutionClaims({cluster:a.coordinator,controller:a.controller,transport:a.transport});
  // A fresh process does not inherit the in-memory token cache.
  a.coordinator.executionClaims.local.clear();
  try {
    assert.equal(a.canProcess(),true);await a.controller.tick(a.transport);await a.settle();
    assert.equal(a.resumes+a.attaches,2);assert.equal(a.sends,0);assert.equal(a.controller.sessions.size,1);
    assert.equal(shared.body.execution_claims[key].token,token);
  } finally {a.controller.executionClaims=null;await a.controller.quiesceForStandby();}
});

test('real startup ordering with absent local state preserves a RUNNING claim before the watcher starts',async()=>{
  const shared=new ClusterMemory(),original=node(shared,'Tablet');await original.existing();
  const before=structuredClone(shared.body.execution_claims);
  original.controller.executionClaims=null;await original.controller.quiesceForStandby();
  const restarted=node(shared,'Tablet'),other=node(shared,'Phone');
  // Match server.mjs: lifecycle initialization precedes first cluster refresh,
  // which precedes the first watcher scan. The local state is deliberately absent.
  await restarted.controller.initialize();await restarted.coordinator.refresh();
  assert.deepEqual(shared.body.execution_claims,before);
  try {await other.ingest();assert.equal(other.record(),null);assert.equal(other.prepares,0);assert.equal(other.sends,0);}
  finally {for(const f of [restarted,other]){f.controller.executionClaims=null;await f.controller.quiesceForStandby();}}
});

test('owner global standby keeps ten-minute recovery and observation, with unrelated new work on the leader',async()=>{
  const shared=new ClusterMemory(),a=node(shared,'Tablet'),b=node(shared,'Phone');
  try {
    await a.existing();a.coordinator.role='STANDBY';await a.controller.quiesceForStandby();
    await a.stall();assert.equal(a.sends,1);assert.equal(a.sent[0].kind,'recovery');assert.equal(a.controller.sessions.size,1);
    b.body.task_id='NEXT-TASK';b.body.turn_id='NEXT-TASK:turn:1';b.body.request_id='NEXT-TASK:turn:1-request';
    b.control={...b.control,task_id:b.body.task_id,turn_id:b.body.turn_id,request_id:b.body.request_id};
    b.path='__SELFRUN_DISPATCH__'+b.body.request_id+'__A1.json';
    await b.ingest();assert.equal(b.prepares,1);assert.equal(b.sends,1);
    assert.equal(Object.keys(shared.body.execution_claims).length,2);
  } finally {for(const f of [a,b]){f.controller.executionClaims=null;await f.controller.quiesceForStandby();}}
});

test('CAS conflict and transient failure recover through the real next admission without a permanent blocker',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');
  try {
    shared.conflicts=1;shared.failWrites=true;
    await assert.rejects(f.ingest(),/unavailable/);assert.equal(f.record(),null);assert.equal(f.sends,0);
    shared.failWrites=false;await f.ingest();assert.equal(f.sends,1);assert.equal(f.prepares,1);
    assert.equal(Object.keys(shared.body.execution_claims).length,1);
  } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

test('STOP while the claim write response is delayed cannot resurrect a record or browser effect',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');let arrive,release;
  const arrived=new Promise(r=>arrive=r),pending=new Promise(r=>release=r);
  shared.afterWrite=async()=>{shared.afterWrite=null;arrive();await pending;};
  const running=f.ingest();await arrived;
  await f.stop();assert.equal(f.record(),null);release();await running;
  assert.equal(f.record(),null);assert.equal(f.prepares,0);assert.equal(f.sends,0);
  assert.equal(shared.body.execution_claims,undefined);assert.equal(f.coordinator.executionClaims.local.size,0);
});

test('STOP clears an acquired in-flight local claim immediately before its control read returns',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');let arrive,release,reads=0;
  const arrived=new Promise(r=>arrive=r),pending=new Promise(r=>release=r),original=f.transport.read;
  f.transport.read=async path=>{const body=await original(path);if(path.startsWith('__SELFRUN_CONTROL__')&&++reads===2){arrive();await pending;}return body;};
  const running=f.ingest();await arrived;assert.equal(f.coordinator.executionClaims.local.size,1);
  await f.stop();assert.equal(f.coordinator.executionClaims.local.size,0);
  release();await running;assert.equal(f.record(),null);assert.equal(f.sends,0);
});

test('a delayed old token release preserves a newer same-request epoch claim',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet'),claims=f.coordinator.executionClaims;
  const old=await claims.acquire(f.control,{createAllowed:true});
  const latest=await claims.acquire({...f.control,control_epoch:2},{createAllowed:true});
  assert.notEqual(old.token,latest.token);
  await claims.release(keyFor(f.control),old.token);
  assert.equal(shared.body.execution_claims[keyFor(f.control)].token,latest.token);
  assert.equal(claims.localOwns(keyFor(f.control),latest.token),true);
});

test('a newer current turn retires the old key claim atomically and rejects its delayed release',async()=>{
  const shared=new ClusterMemory(),a=node(shared,'Tablet'),b=node(shared,'Phone');
  const old=await a.coordinator.executionClaims.acquire(a.control,{createAllowed:true});
  const next={...b.control,turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',control_epoch:2};
  const current=await b.coordinator.executionClaims.acquire(next,{createAllowed:true});
  assert.deepEqual(Object.keys(shared.body.execution_claims),[keyFor(next)]);
  await a.coordinator.executionClaims.release(keyFor(a.control),old.token);
  assert.equal(shared.body.execution_claims[keyFor(next)].token,current.token);
});

test('a stale legacy predecessor without a claim still reads current control and is discarded',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');
  f.controller.executionClaims=null;await f.existing();await f.controller.quiesceForStandby();
  f.control={...f.control,turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',control_epoch:2};
  const next=await f.coordinator.executionClaims.acquire(f.control,{createAllowed:true});
  wireExecutionClaims({cluster:f.coordinator,controller:f.controller,transport:f.transport});
  await f.controller.tick(f.transport);await f.settle();
  assert.equal(f.record(),null);
  assert.equal(shared.body.execution_claims[keyFor(f.control)].token,next.token);
});

test('failed terminal release leaves no local queue and reconciliation removes only the stale current claim',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');await f.existing();
  shared.failWrites=true;await f.stop();await f.settle();
  assert.equal(f.record(),null);assert.equal(f.coordinator.executionClaims.local.size,0);
  assert.equal(Object.keys(shared.body.execution_claims).length,1);
  shared.failWrites=false;await f.coordinator.onRefresh();
  assert.equal(shared.body.execution_claims,undefined);
  assert.deepEqual(Object.keys(f.coordinator.executionClaims).sort(),['local','node','transport']);
});

test('cluster heartbeat CAS conflict preserves the live claim entry',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');await f.existing();
  try {
    const before=structuredClone(shared.body.execution_claims);shared.conflicts=1;
    await f.coordinator.refresh();assert.deepEqual(shared.body.execution_claims,before);
    assert.equal(shared.body.active_server,'Tablet');
  } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

test('production wiring keeps a standby owner watcher live and rejects a transport without CAS',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');await f.existing();
  try {
    f.coordinator.role='STANDBY';await f.controller.quiesceForStandby();
    const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{},canDispatch:f.canProcess});
    assert.equal(watcher.status().dispatchEnabled,true);await watcher.scanOnce();await f.settle();
    assert.equal(f.controller.sessions.size,1);watcher.stop();
    assert.throws(()=>wireExecutionClaims({cluster:{transport:{},executionClaims:{}},controller:f.controller,transport:f.transport}),/requires cluster CAS/);
    const server=await fs.readFile(new URL('../src/server.mjs',import.meta.url),'utf8');
    assert.match(server,/wireExecutionClaims\(\{cluster,controller:driveController,transport:driveTransport\}\)/);
    assert.match(server,/canDispatch: canProcessDrive/);
  } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

test('owned exact target URL still publishes before release or DOM operations with production claim wiring',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');let domCalls=0;
  const project=f.body.project_url,conversation='https://chatgpt.com/g/other/c/claimed-owned-url';
  const page={transport:'extension',close(){},async run(){domCalls++;throw new Error('synthetic dead DOM');}};
  const browser=new ChatGptBrowser({listExistingTargets:async()=>[{id:'claimed-target',type:'page',url:conversation}]},{});
  f.browser.prepare=async()=>({target:{id:'claimed-target'},session:page,baseline:{url:project}});
  f.browser.submitIntent=args=>browser.submitIntent(args);
  try {
    await f.ingest();assert.equal(f.rows.get(f.path).conversation_url,'https://chatgpt.com/c/claimed-owned-url');
    assert.equal(f.rows.get(f.path).server_status,'PENDING');assert.equal(domCalls,0);
    assert.notEqual(f.record().intent?.released,true);assert.notEqual(f.record().intent?.outcome,'CONFIRMED');
    assert.equal(f.observations.length,1);
  } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

test('unknown CONTROL or claim read preserves owned observation and URL handoff while suppressing new input',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');await f.existing();await f.observe({});
  const originalRead=f.transport.read,clusterRead=shared.readCluster.bind(shared);
  try {
    f.coordinator.role='STANDBY';await f.controller.quiesceForStandby();
    f.transport.read=async path=>{if(path.startsWith('__SELFRUN_CONTROL__'))throw new Error('unknown CONTROL');return originalRead(path);};
    await assert.doesNotReject(f.controller.tick(f.transport));
    await f.observe({url:'https://chatgpt.com/g/changed/c/observed-new-url',responseTextLength:2000});
    assert.equal(f.rows.get(f.path).conversation_url,'https://chatgpt.com/c/observed-new-url');
    assert.equal(f.controller.sessions.size,1);assert.equal(f.sends,0);
    f.transport.read=originalRead;shared.readCluster=async()=>{throw new Error('unknown claim');};
    await assert.doesNotReject(f.controller.tick(f.transport));
    await f.observe({responseTextLength:2100});assert.equal(f.controller.sessions.size,1);assert.equal(f.sends,0);
  } finally {shared.readCluster=clusterRead;f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

test('legacy non-owner record cleanup without a token cannot release the real owner claim',async()=>{
  const shared=new ClusterMemory(),owner=node(shared,'Tablet'),legacy=node(shared,'Phone');
  try {
    await owner.existing();const key=keyFor(owner.body),claim=structuredClone(shared.body.execution_claims[key]);
    legacy.controller.executionClaims=null;legacy.attachError=new Error('synthetic disconnected Phone');await legacy.existing();
    assert.equal(legacy.record().state,'BLOCKED');
    wireExecutionClaims({cluster:legacy.coordinator,controller:legacy.controller,transport:legacy.transport});
    assert.equal(legacy.coordinator.executionClaims.current(key),null);
    let writes=0;legacy.rows.get(legacy.path).conversation_url=null;
    const write=legacy.transport.write;legacy.transport.write=async(...args)=>{writes++;return write(...args);};
    await legacy.controller.tick(legacy.transport);await legacy.settle();
    assert.equal(writes,0);assert.equal(legacy.controller.activeDispatches().length,0);
    assert.equal(legacy.store.snapshot().activeCount,0);
    assert.equal(legacy.record(),null);assert.deepEqual(shared.body.execution_claims[key],claim);
    assert.equal(owner.controller.sessions.size,1);
  } finally {for(const f of [owner,legacy]){f.controller.executionClaims=null;await f.controller.quiesceForStandby();}}
});

test('long owner absence does not expire authority into another node',async t=>{
  let now=1800000000000;t.mock.method(Date,'now',()=>now);
  const shared=new ClusterMemory(),a=node(shared,'Tablet'),b=node(shared,'Phone');
  try {
    await a.existing();const before=structuredClone(shared.body.execution_claims);
    now+=30*86400000;b.control={...a.control,conversation_url:a.probe.url};b.body={...a.body};await b.ingest();
    assert.equal(b.record(),null);assert.equal(b.resumes,0);assert.equal(b.sends,0);
    assert.deepEqual(shared.body.execution_claims,before);
  } finally {for(const f of [a,b]){f.controller.executionClaims=null;await f.controller.quiesceForStandby();}}
});

test('stale STOP cannot release a same-request owner whose current RUNNING epoch advanced',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');
  try {
    await f.existing();const key=keyFor(f.body),before=structuredClone(shared.body.execution_claims[key]);
    f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
    await f.controller.control(null,f.control,f.transport);await f.settle();
    assert.equal(f.record().control_epoch,3);
    await f.controller.control(null,{...f.control,state:'STOPPED',control_epoch:2},f.transport);await f.settle();
    console.log(JSON.stringify({case:'stale_stop',recordEpoch:f.record()?.control_epoch,recordState:f.record()?.state,claims:shared.body.execution_claims??null,localClaims:f.coordinator.executionClaims.local.size}));
    assert.ok(shared.body.execution_claims?.[key],'stale STOP must not delete current owner claim');
    assert.equal(shared.body.execution_claims[key].node,before.node);
  } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});
test('own live remote claim restores observation after local state loss while globally standby',async()=>{
  const shared=new ClusterMemory(),first=node(shared,'Phone');await first.existing();
  first.controller.executionClaims=null;await first.controller.quiesceForStandby();
  const next=node(shared,'Phone',{active:false});next.body={...first.body};next.control={...first.control,conversation_url:first.probe.url};next.rows.set(next.path,structuredClone(next.body));
  await next.controller.initialize();await next.coordinator.onRefresh();
  const watcher=new DriveDispatchWatcher({transport:next.transport,controller:next.controller,config:{},canDispatch:next.canProcess});
  try {
    await watcher.scanOnce();await next.settle();
    console.log(JSON.stringify({case:'state_loss_standby',canProcess:next.canProcess(),record:!!next.record(),sessions:next.controller.sessions.size,claims:shared.body.execution_claims}));
    assert.equal(next.controller.sessions.size,1,'current remote ownership should allow restoration');
  } finally {watcher.stop();next.controller.executionClaims=null;await next.controller.quiesceForStandby();}
});

test('uncertain CONTROL while restoring an existing own claim must preserve that live authority',async()=>{
  const shared=new ClusterMemory(),first=node(shared,'Tablet');await first.existing();
  first.controller.executionClaims=null;await first.controller.quiesceForStandby();
  const next=node(shared,'Tablet');next.body={...first.body};next.control={...first.control,conversation_url:first.probe.url};
  const key=keyFor(next.body),before=structuredClone(shared.body.execution_claims[key]);
  const originalRead=next.transport.read;
  next.transport.read=async path=>{
    if(path.startsWith('__SELFRUN_CONTROL__')&&next.coordinator.executionClaims.localOwns(key))throw new Error('synthetic CONTROL read failure after reacquire');
    return originalRead(path);
  };
  try {
    await assert.rejects(next.ingest(),/CONTROL read failure/);
    console.log(JSON.stringify({case:'restore_uncertain_control',claims:shared.body.execution_claims??null,record:!!next.record()}));
    assert.deepEqual(shared.body.execution_claims?.[key],before,'a failed restoration must not release an existing live claim');
  } finally {next.controller.executionClaims=null;await next.controller.quiesceForStandby();}
});

export { ClusterMemory, node };

test('state loss without a current URL preserves its existing claim and never creates a second input',async()=>{
  const shared=new ClusterMemory(),first=node(shared,'Phone');await first.ingest();
  first.controller.executionClaims=null;await first.controller.quiesceForStandby();
  const next=node(shared,'Phone');const before=structuredClone(shared.body.execution_claims);
  try {await next.ingest();assert.equal(next.record(),null);assert.equal(next.prepares,0);assert.equal(next.sends,0);assert.deepEqual(shared.body.execution_claims,before);}
  finally {next.controller.executionClaims=null;await next.controller.quiesceForStandby();}
});

test('missing shared claim preserves existing owned observation and local record but suppresses new input',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');await f.existing();
  try {delete shared.body.execution_claims;await f.controller.tick(f.transport);await f.settle();
    assert.ok(f.record());assert.equal(f.controller.sessions.size,1);assert.equal(f.sends,0);
    await f.observe({responseTextLength:2200});assert.equal(f.record().cursor.responseTextLength,2200);
  } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

for(const mode of ['committed','intervention'])test('standby owned '+mode+' keeps ten-minute observation while skipping only input',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');await f.existing();
  try {f.coordinator.role='STANDBY';await f.controller.quiesceForStandby();
    if(mode==='committed')f.result(true);
    else {f.docs.set('RESULT-1',JSON.stringify({...JSON.parse(f.docs.get('RESULT-1')),status:'USER_ACTION_REQUIRED'}));f.control={...f.control,state:'WAITING_USER_INTERVENTION',control_epoch:2};await f.controller.control(null,f.control,f.transport);}
    await f.stall();assert.equal(f.sends,mode==='committed'?1:0);assert.equal(f.controller.sessions.size,1);assert.ok(f.record());
    await f.observe({responseTextLength:2400});assert.equal(f.record().cursor.responseTextLength,2400);
    f.control={...f.control,state:'DONE',control_epoch:3};await f.controller.control(null,f.control,f.transport);await f.settle();
    assert.equal(f.record(),null);assert.equal(shared.body.execution_claims,undefined);
  } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

test('same-node restart hands off exact owned target URL even if shared claim read is unavailable',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');let url=f.body.project_url,metadataReads=0;
  const realBrowser=new ChatGptBrowser({listExistingTargets:async()=>{metadataReads++;return[{id:'target-owned',type:'page',url}];}},{});
  f.probe={url,composer:true};
  f.browser.prepare=async()=>({target:{id:'target-owned'},session:{transport:'extension',close(){},async run(){throw Error('synthetic dead DOM');}},baseline:{url:f.body.project_url}});
  f.browser.submitIntent=args=>realBrowser.submitIntent(args);
  f.browser.ownedConversationUrl=args=>realBrowser.ownedConversationUrl(args);
  try {
    await f.ingest();assert.equal(f.record().reason,'INPUT_NOT_SENT');
    f.controller.executionClaims=null;await f.controller.quiesceForStandby();f.controller=f.recreate();
    f.controller.canDispatch=()=>f.coordinator.canDispatch();f.coordinator.executionClaims.local.clear();
    wireExecutionClaims({cluster:f.coordinator,controller:f.controller,transport:f.transport});
    await f.controller.initialize();url='https://chatgpt.com/c/restart-owned-url';const before=metadataReads;
    shared.readCluster=async()=>{throw Error('synthetic shared claim unavailable');};
    await assert.doesNotReject(f.controller.tick(f.transport));await f.settle();
    console.log(JSON.stringify({case:'restart_url_unknown_claim',newMetadataReads:metadataReads-before,url:f.rows.get(f.path).conversation_url??null,localClaimCount:f.coordinator.executionClaims.local.size}));
    assert.equal(f.rows.get(f.path).conversation_url,url,'exact owned target URL must not depend on remote claim read');
  } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

test('delayed reconciliation STOP snapshot cannot release a now newer RUNNING epoch',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');
  try {
    await f.existing();const key=keyFor(f.body);
    let arrived,release;const entered=new Promise(r=>arrived=r),pending=new Promise(r=>release=r);
    const stale={...f.control,state:'STOPPED',control_epoch:2};
    const reconciling=f.coordinator.executionClaims.reconcile({readControl:async()=>{arrived();await pending;return stale;}});
    await entered;
    f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
    await f.controller.control(null,f.control,f.transport);await f.settle();
    release();await reconciling;
    console.log(JSON.stringify({case:'delayed_reconcile_stop',recordEpoch:f.record()?.control_epoch,claims:shared.body.execution_claims??null}));
    assert.ok(shared.body.execution_claims?.[key],'old reconciler result must not release higher current epoch');
  } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

test('STOP while same-request epoch refresh CAS response is delayed cannot resurrect claim cache',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet');
  try {
    await f.existing();let arrived,release;const entered=new Promise(r=>arrived=r),pending=new Promise(r=>release=r);
    shared.afterWrite=async()=>{shared.afterWrite=null;arrived();await pending;};
    f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
    const refresh=f.controller.control(null,f.control,f.transport);await entered;
    await f.stop();assert.equal(f.record(),null);release();await refresh;await f.settle();
    console.log(JSON.stringify({case:'stop_during_epoch_refresh',record:!!f.record(),localClaims:f.coordinator.executionClaims.local.size,claims:shared.body.execution_claims??null}));
    assert.equal(f.coordinator.executionClaims.local.size,0,'late epoch refresh must not restore local current authority after STOP');
    assert.equal(shared.body.execution_claims,undefined);
  } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

test('STOP while cold claim verification response is delayed cannot recreate authority',async()=>{
 const shared=new ClusterMemory(),f=node(shared,'Tablet');await f.existing();
 f.coordinator.executionClaims.local.clear();let entered,release;
 const arrived=new Promise(r=>entered=r),pending=new Promise(r=>release=r),read=shared.readCluster.bind(shared);
 shared.readCluster=async()=>{const body=await read();shared.readCluster=read;entered();await pending;return body;};
 const tick=f.controller.tick(f.transport);await arrived;await f.stop();release();await tick;await f.settle();
 assert.equal(f.record(),null);assert.equal(f.coordinator.executionClaims.local.size,0);assert.equal(shared.body.execution_claims,undefined);
});

test('conflicting same-epoch STOP cannot release the current running claim',async()=>{
 const shared=new ClusterMemory(),f=node(shared,'Tablet');await f.existing();const before=structuredClone(shared.body.execution_claims);
 try {await assert.rejects(f.controller.control(null,{...f.control,state:'STOPPED'},f.transport),/epoch conflict/);assert.deepEqual(shared.body.execution_claims,before);assert.ok(f.record());}
 finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

test('cold terminal retirement preserves foreign claims and a newer epoch across a CAS conflict',async()=>{
 const shared=new ClusterMemory(),a=node(shared,'Tablet'),b=node(shared,'Phone'),claims=a.coordinator.executionClaims;
 const first=await claims.acquire(a.control,{createAllowed:true});
 await b.coordinator.executionClaims.releaseControl({...b.control,state:'STOPPED',control_epoch:4});
 assert.equal(shared.body.execution_claims[keyFor(a.control)].token,first.token);
 const snapshot=structuredClone(shared.body.execution_claims[keyFor(a.control)]);shared.beforeWrite=async()=>{
  shared.beforeWrite=null;shared.body.execution_claims[keyFor(a.control)]={...snapshot,token:'new-current-token',control_epoch:5};shared.revision++;
 };
 await claims.releaseControl({...a.control,state:'STOPPED',control_epoch:4});
 assert.equal(shared.body.execution_claims[keyFor(a.control)].token,'new-current-token');
 assert.equal(shared.body.execution_claims[keyFor(a.control)].control_epoch,5);
});

test('delayed cold STOP cleanup CAS retry must preserve a replacement token for reused identity',async()=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet'),claims=f.coordinator.executionClaims,key=keyFor(f.control);
  const old=await claims.acquire(f.control,{createAllowed:true});
  let arrived,release;const entered=new Promise(r=>arrived=r),pending=new Promise(r=>release=r);
  shared.beforeWrite=async()=>{shared.beforeWrite=null;arrived();await pending;};
  const stopping=claims.releaseControl({...f.control,state:'STOPPED',control_epoch:2});await entered;
  await claims.release(key,old.token);
  const next=await claims.acquire(f.control,{createAllowed:true});assert.notEqual(next.token,old.token);
  release();await stopping;
  console.log(JSON.stringify({case:'stop_retry_reused_identity',expectedNewToken:next.token,actualClaim:shared.body.execution_claims?.[key]??null}));
  assert.equal(shared.body.execution_claims?.[key]?.token,next.token,'retry may remove only the claim observed for the ended execution');
});

import { StateStore } from '../src/state-store.mjs';
import os from 'node:os';
import path from 'node:path';
test('corrupt local state fails startup before cluster ownership mutation and is not replaced with empty state',async t=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-corrupt-state-review-'));
  t.after(()=>fs.rm(dir,{recursive:true,force:true}));
  const config={dataDir:dir,stateFile:path.join(dir,'state.json'),eventsFile:path.join(dir,'events.jsonl')};
  const invalid='{ "lifecycle": ';await fs.writeFile(config.stateFile,invalid);
  const store=new StateStore(config);await assert.rejects(store.init(),SyntaxError);
  assert.equal(await fs.readFile(config.stateFile,'utf8'),invalid);
  const server=await fs.readFile(new URL('../src/server.mjs',import.meta.url),'utf8');
  assert.ok(server.indexOf('await stateStore.init()')<server.indexOf('const cluster = new SelfRunClusterCoordinator'));
  assert.ok(server.indexOf('await controller.initialize()')<server.indexOf('cluster.start()'));
});

for(const state of ['STOPPED','DONE','RUNNING'])test('same-epoch conflicting '+state+' cannot retire a current execution through reconciliation',async()=>{
 const shared=new ClusterMemory(),f=node(shared,'Tablet'),claims=f.coordinator.executionClaims;
 await claims.acquire(f.control,{createAllowed:true});const before=structuredClone(shared.body.execution_claims);
 const control={...f.control,state,...(state==='RUNNING'?{turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request'}:{})};
 await claims.reconcile({readControl:async()=>control});assert.deepEqual(shared.body.execution_claims,before);
 if(state!=='RUNNING'){await claims.releaseControl(control);assert.deepEqual(shared.body.execution_claims,before);}
});

test('a claim ahead of stale local CONTROL preserves observation and suppresses all input until authority catches up',async()=>{
 const shared=new ClusterMemory(),f=node(shared,'Tablet');await f.existing();
 await f.coordinator.executionClaims.acquire({...f.control,state:'WAITING_USER_INTERVENTION',control_epoch:2},{createAllowed:false});
 f.controller.executionClaims=null;await f.controller.quiesceForStandby();f.controller=f.recreate();await f.controller.initialize();
 f.coordinator.executionClaims.local.clear();f.controller.canDispatch=()=>f.coordinator.canDispatch();wireExecutionClaims({cluster:f.coordinator,controller:f.controller,transport:f.transport});
 try {await f.controller.tick(f.transport);await f.settle();await f.stall();assert.equal(f.sends,0);assert.equal(f.controller.sessions.size,1);
  await f.observe({responseTextLength:2500});assert.equal(f.record().cursor.responseTextLength,2500);
  f.control={...f.control,state:'RUNNING',control_epoch:3};await f.controller.control(null,f.control,f.transport);await f.stall();assert.equal(f.sends,1);
 } finally {f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});

test('same-epoch STOP cannot cancel an in-flight RUNNING admission before its claim response',async()=>{
 const shared=new ClusterMemory(),f=node(shared,'Tablet');let entered,release;
 const arrived=new Promise(r=>entered=r),pending=new Promise(r=>release=r);
 shared.afterWrite=async()=>{shared.afterWrite=null;entered();await pending;};
 const ingest=f.ingest();await arrived;
 await assert.rejects(f.controller.control(null,{...f.control,state:'STOPPED'},f.transport),/epoch conflict/);
 release();await ingest;assert.equal(f.sends,1);assert.ok(f.record());assert.ok(shared.body.execution_claims);
 f.controller.executionClaims=null;await f.controller.quiesceForStandby();
});
