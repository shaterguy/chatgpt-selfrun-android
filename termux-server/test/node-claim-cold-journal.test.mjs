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



import { ExtensionManager } from '../src/browser/extension-manager.mjs';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter } from 'node:events';

async function coldOwnedManager(t,targetId){
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-independent-cold-journal-'));
  const ownerId='synthetic-owner',capability='synthetic-fixture-capability-123456',sessionId=targetId.split(':')[1],cdpId=targetId.split(':')[2];
  const config={dataDir:root,extensionPairingFile:path.join(root,'pair.json'),extensionBridgePort:12345,
    extensionChromiumCommand:'/synthetic/chromium',browserProfile:path.join(root,'profile'),browserClientProfile:'native',browserPort:19227};
  const extensionPath=path.join(root,'browser-extension');
  const proof={pid:4242,startTime:'123',bootId:'synthetic-boot',profile:config.browserProfile,extensionPath,ownerId,cdpPort:19227,
    browserKey:'ws://127.0.0.1:19227/devtools/browser/synthetic-browser'};
  const processRow={pid:proof.pid,startTime:proof.startTime,state:'S',args:['/synthetic/chromium',
    '--user-data-dir='+config.browserProfile,'--load-extension='+extensionPath,'--selfrun-extension-owner='+ownerId,
    '--remote-debugging-port=19227','--remote-debugging-address=127.0.0.1']};
  await fs.writeFile(config.extensionPairingFile,JSON.stringify({schema:'selfrun-extension-pair-v1',ownerId,capability}),{mode:0o600});
  await fs.writeFile(path.join(root,'extension-ownership.json'),JSON.stringify({schema:'selfrun-extension-ownership-v1',ownerId,
    profile:config.browserProfile,browserSessionId:sessionId,process:proof,targets:[targetId],controllerStopPending:false,retiringProcess:null,
    targetMetadata:{[targetId]:{cdpTargetId:cdpId,bootstrapUrl:'http://127.0.0.1:12345/bootstrap/00000000-0000-4000-8000-000000000000',entryUrl:'https://chatgpt.com/g/test/project'}}}),{mode:0o600});
  const state={url:'https://chatgpt.com/c/restart-journal-owned',metadataReads:0,starts:0,launches:0};
  class Bridge extends EventEmitter {constructor(){super();this.url='http://127.0.0.1:12345';}async start(){state.starts++;await new Promise(r=>setImmediate(r));}async close(){}status(){return{connected:true};}}
  const manager=new ExtensionManager(config,{processIdentity:()=>processRow,processExists:()=>true,bootId:()=>proof.bootId,
    bridgeFactory:()=>new Bridge(),findOwnedBrowserRoots:()=>[proof],readCdpMetadata:async(_port,resource)=>{
      state.metadataReads++;return resource==='version'?{webSocketDebuggerUrl:proof.browserKey}:[{id:cdpId,type:'page',url:state.url}];
    },launch:async()=>{state.launches++;throw Error('must never launch');},signalProcess:()=>assert.fail('must never signal a process'),
    browserAction:async()=>assert.fail('must never perform browser action'),purgeInactiveAction:async()=>{},});
  t.after(async()=>{await manager.close();await fs.rm(root,{recursive:true,force:true});});
  return {manager,state};
}

test('cold ExtensionManager journal restores exact-target URL before CONTROL or shared-claim availability',async t=>{
  const shared=new ClusterMemory(),f=node(shared,'Tablet'),targetId='ext:synthetic-session:owned-tab';
  const initialSubmit=f.browser.submitIntent,initialPrepare=f.browser.prepare;
  f.probe={...f.probe,url:f.body.project_url};
  const initialManager={isOwnedTarget:id=>id===targetId,listExistingTargets:async()=>[{id:targetId,type:'page',url:f.body.project_url}]};
  const initialBrowser=new ChatGptBrowser(initialManager,{});
  f.browser.chromium=initialManager;
  f.browser.prepare=async()=>({target:{id:targetId},session:{transport:'extension',close(){},async run(){throw Error('synthetic dead DOM');}},baseline:{url:f.body.project_url}});
  f.browser.submitIntent=args=>initialBrowser.submitIntent(args);f.browser.ownedConversationUrl=args=>initialBrowser.ownedConversationUrl(args);
  await f.ingest();assert.equal(f.record().reason,'PRE_SUBMIT_RETRY');assert.equal(f.record().target_id,targetId);
  assert.equal(f.record().target_owned,true);const oldKey=keyFor(f.body);
  f.controller.executionClaims=null;await f.controller.quiesceForStandby();
  const {manager,state}=await coldOwnedManager(t,targetId),browser=new ChatGptBrowser(manager,{});
  f.browser.chromium=manager;f.browser.ownedConversationUrl=args=>browser.ownedConversationUrl(args);
  f.browser.attachTarget=async args=>{
    assert.equal(args.targetId,targetId);assert.equal(args.observationOnly,true);
    assert.equal(f.rows.get(f.path).conversation_url,state.url,'publish before readonly attachment');
    return {target:{id:targetId},session:{close(){}},baseline:{...f.probe,url:state.url}};
  };
  f.controller=f.recreate();f.controller.canDispatch=()=>f.coordinator.canDispatch();f.coordinator.executionClaims.local.clear();
  wireExecutionClaims({cluster:f.coordinator,controller:f.controller,transport:f.transport});await f.controller.initialize();
  assert.equal(manager.isOwnedTarget(targetId),false,'cold async journal has not yet loaded');
  const read=f.transport.read,claimRead=shared.readCluster.bind(shared);let controlUnavailable=true,claimUnavailable=true;
  f.transport.read=async p=>{if(controlUnavailable&&p.startsWith('__SELFRUN_CONTROL__'))throw Error('synthetic unknown CONTROL');return read(p);};
  shared.readCluster=async()=>{if(claimUnavailable)throw Error('synthetic unknown claim');return claimRead();};
  try {
    await f.controller.tick(f.transport).catch(()=>{});await f.settle();
    assert.equal(f.rows.get(f.path).conversation_url,state.url,'cold exact owner URL must publish before remote read gates');
    assert.equal(manager.isOwnedTarget(targetId),true);assert.equal(state.launches,0);assert.ok(state.metadataReads>0);
    assert.equal(f.controller.sessions.size,1);assert.equal(f.sends,0);
    await f.observe({url:state.url,responseTextLength:1300});assert.equal(f.record().cursor.responseTextLength,1300);assert.equal(f.sends,0);
    controlUnavailable=false;await f.controller.tick(f.transport).catch(()=>{});await f.settle();
    await f.observe({url:state.url,responseTextLength:1500});assert.equal(f.record().cursor.responseTextLength,1500);assert.equal(f.sends,0);
    claimUnavailable=false;await f.controller.tick(f.transport);await f.settle();
    f.browser.submitIntent=initialSubmit;f.probe.url=state.url;await f.stall();assert.equal(f.sends,1);
    const staleObserver=f.observations.at(-1);f.control={...f.control,turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',control_epoch:2};
    await f.controller.control(null,f.control,f.transport);await f.settle();
    assert.equal(f.controller.repository.get(oldKey),null);assert.equal(f.coordinator.executionClaims.localOwns(oldKey),false);
    await staleObserver.onActivity({...f.probe,url:'https://chatgpt.com/c/stale-url',responseTextLength:9999});await f.settle();
    assert.equal(f.controller.repository.get(oldKey),null);assert.equal(f.sends,1);
    f.browser.prepare=initialPrepare;f.body={...f.body,turn_id:f.control.turn_id,request_id:f.control.request_id};f.path='__SELFRUN_DISPATCH__'+f.body.request_id+'__A1.json';
    f.rows.set(f.path,structuredClone(f.body));await f.controller.ingest(f.path,f.body,f.transport);await f.settle();
    assert.equal(f.sends,2);assert.equal(f.controller.repository.records().length,1);
    assert.equal(f.controller.repository.records()[0].request_id,f.control.request_id);
  } finally {shared.readCluster=claimRead;f.controller.executionClaims=null;await f.controller.quiesceForStandby();}
});
