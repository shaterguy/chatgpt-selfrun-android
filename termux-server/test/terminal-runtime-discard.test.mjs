import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';
import { keyFor } from '../src/lifecycle.mjs';

function assertDiscarded(f, identity=f.body) {
  const key=keyFor(identity), repo=f.controller.repository;
  assert.equal(repo.get(key),null,'terminal identity must not remain in runtime retired records');
  for(const source of [repo.data,f.store.snapshot().lifecycle])for(const field of ['requests','retired','outbox','completionOutbox'])
    assert.equal(Object.hasOwn(source[field]||{},key),false,field);
  assert.equal(f.controller.operations.has(key),false,'terminal execution lane must be detached');
  assert.equal(f.controller.resultRetirements,undefined,'no terminal Result retry lane exists');
  assert.equal(f.controller.sessions.has(key),false,'terminal monitor must be detached');
}

for(const state of ['STOPPED','DONE'])test(`${state} discards runtime immediately while browser preparation is unresolved`,async t=>{
  const f=fixture(),entered=deferred(),release=deferred(),closed=[];let args;
  f.browser.chromium={isOwnedTarget:id=>id==='owned-late',closeOwnedTarget:async(id,{guard})=>{
    if(!guard({id,url:f.body.project_url}))return {closed:false};closed.push(id);return {closed:true};
  },releaseIfUnused:async()=>({released:false})};
  f.browser.prepare=async options=>{args=options;f.prepares++;entered.resolve();await release.promise;
    await options.onTarget({id:'owned-late'});
    return {target:{id:'owned-late'},session:{close(){}},baseline:f.probe};};
  f.controller=f.recreate();t.after(()=>f.controller.quiesceForStandby());
  const preparing=f.ingest();await entered.promise;
  f.control={...f.control,state,control_epoch:2};await f.controller.controlDurable(null,f.control,f.transport);
  assert.equal(args.signal.aborted,true);assertDiscarded(f);
  release.resolve();await preparing;await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
  assertDiscarded(f);assert.deepEqual(closed,['owned-late']);assert.equal(f.sends,0);
  f.controller=f.recreate();await f.controller.tick(f.transport);
  assertDiscarded(f);assert.equal(f.prepares,1);assert.equal(f.sends,0);
});

for(const state of ['STOPPED','DONE'])test(`${state} discards a pending receipt and permits a fresh next turn without waiting for it`,async t=>{
  const f=fixture(),entered=deferred(),release=deferred();let oldRequest;
  f.browser.submitIntent=async options=>{oldRequest=options;entered.resolve();await release.promise;
    await options.onRequest({message_id:'late-message',receipt:{intent_id:options.intent.id,generation:options.intent.receipt_generation}});
    return f.probe;};
  const preparing=f.ingest();await entered.promise;const old={...f.body};
  f.control={...f.control,state,control_epoch:2};await f.controller.controlDurable(null,f.control,f.transport);
  assertDiscarded(f,old);assert.equal(oldRequest.signal.aborted,true);
  f.body={...f.body,turn_id:'SAFE-TEST:turn:2',request_id:'next-request',client_status:'CREATE_REQUESTED'};
  f.path='__SELFRUN_DISPATCH__next.json';f.control={...f.control,turn_id:f.body.turn_id,request_id:f.body.request_id,state:'RUNNING',control_epoch:3};
  await f.ingest();assert.equal(f.record().state,'PREPARED');assert.equal(f.prepares,2);
  release.resolve();await preparing;assertDiscarded(f,old);
  assert.equal(f.record().state,'PREPARED');assert.equal(f.record().intent,null);
  await f.controller.quiesceForStandby();
});

test('DONE drops execution data while owned-target cleanup is blocked',async t=>{
  const f=fixture(),entered=deferred(),release=deferred();
  f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:async(id,{guard})=>{entered.resolve();await release.promise;return {closed:guard({id,url:f.probe.url})};},releaseIfUnused:async()=>({released:false})};
  f.controller=f.recreate();await f.existing();f.control={...f.control,state:'DONE',control_epoch:2};await f.controller.controlDurable(null,f.control,f.transport);await entered.promise;
  assertDiscarded(f);assert.equal(f.controller.repository.resourceCleanups.size,1);
  assert.equal(JSON.stringify([...f.controller.repository.resourceCleanups.values()]).includes('test input'),false);
  release.resolve();await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
  assertDiscarded(f);assert.equal(f.controller.repository.resourceCleanups.size,0);
});

test('late queued observer cannot occupy or mutate a new same-identity execution',async()=>{
  const f=fixture(),entered=deferred(),release=deferred();await f.existing();const old=f.observations.at(-1);
  const read=f.transport.readGoogleDocText;f.transport.readGoogleDocText=async id=>{entered.resolve();await release.promise;return read(id);};
  await f.observe({});await f.controller.repository.move(keyFor(f.body),'OBSERVING','test recovery deadline',{last_progress_at:0});
  const observing=old.onActivity({...f.probe,status:'STALLED'});await entered.promise;
  const queued=old.onActivity({...f.probe,status:'STALLED'});
  await f.stop();assertDiscarded(f);
  f.transport.readGoogleDocText=read;await f.resume();
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.resumes,2);
  release.resolve();await Promise.all([observing,queued]);await f.settle();
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.resumes,2);assert.equal(f.sends,0);
  await f.controller.quiesceForStandby();
});

test('failed terminal disposal is released and never scheduled again',async()=>{
  const f=fixture();let attempts=0;
  f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:async()=>{attempts++;return {closed:false,reason:'fixture_failure'};},releaseIfUnused:async()=>({released:false})};
  f.controller=f.recreate();await f.existing();await f.stop();await f.controller.cleanupTerminalResources();
  assertDiscarded(f);assert.equal(attempts,1);assert.equal(f.controller.repository.cleanupEntries().length,0);
  for(let i=0;i<3;i++){await f.controller.tick(f.transport);await f.controller.cleanupTerminalResources();}
  assert.equal(attempts,1);assert.equal(f.sends,0);
});

test('a delayed old CONTROL read cannot restore an ended watch',async()=>{
  const f=fixture();await f.existing();f.control={...f.control,conversation_url:f.probe.url};
  const old=structuredClone(f.control),entered=deferred(),release=deferred(),read=f.transport.read;
  f.transport.read=async path=>{if(path.startsWith('__SELFRUN_CONTROL__')){entered.resolve();await release.promise;return old;}return read(path);};
  const advancing=f.controller.tick(f.transport);await entered.promise;
  f.control={...f.control,state:'STOPPED',control_epoch:2};await f.controller.controlDurable(null,f.control,f.transport);
  assertDiscarded(f);release.resolve();await advancing;
  assertDiscarded(f);assert.equal(f.resumes,1);assert.equal(f.sends,0);
});

import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { StateStore } from '../src/state-store.mjs';
test('terminal discard and restart preserve original raw logs and user Drive documents',async t=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-discard-log-proof-'));
  t.after(()=>fs.rm(dir,{recursive:true,force:true}));
  const config={dataDir:dir,stateFile:path.join(dir,'state.json'),eventsFile:path.join(dir,'events.jsonl')};
  const store=new StateStore(config);await store.init();const f=fixture({store});await f.existing();
  await store.recordEvent('RAW_LOG',{task_id:f.body.task_id,turn_id:f.body.turn_id,request_id:f.body.request_id,raw:'preserve original bytes'});
  const before=await fs.readFile(config.eventsFile,'utf8'),documents=structuredClone([...f.docs]);
  assert.ok(before.includes(f.body.request_id));await f.stop();
  assert.equal(await fs.readFile(config.eventsFile,'utf8'),before);assert.deepEqual([...f.docs],documents);
  const reloaded=new StateStore(config);await reloaded.init();f.store=reloaded;f.controller=f.recreate();await f.controller.initialize();await f.controller.tick(f.transport);
  assert.equal(await fs.readFile(config.eventsFile,'utf8'),before);assertDiscarded(f);assert.equal(f.resumes,1);
});

for(const rejects of [false,true])test(`old CONTROL ${rejects?'failure':'success'} cannot detach a newer same-identity run`,async()=>{
  const f=fixture();await f.existing();const entered=deferred(),release=deferred(),read=f.transport.read;let delayed=false;
  f.transport.read=async p=>{if(!delayed&&p.startsWith('__SELFRUN_CONTROL__')){delayed=true;const old=await read(p);entered.resolve();await release.promise;if(rejects)throw Error('old read failed');return old;}return read(p);};
  const oldTick=f.controller.tick(f.transport);await entered.promise;await f.stop();await f.resume();
  const current=f.observations.at(-1);release.resolve();await oldTick;
  assert.equal(current.signal.aborted,false);assert.equal(f.controller.sessions.size,1);assert.equal(f.resumes,2);
  await f.controller.quiesceForStandby();
});

test('STOP during the final restore CONTROL read cancels admission even with no current record',async()=>{
  const f=fixture();await f.existing();await f.stop();const entered=deferred(),release=deferred(),read=f.transport.read;
  f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
  f.transport.read=async p=>{const value=await read(p);if(p.startsWith('__SELFRUN_CONTROL__')){entered.resolve();await release.promise;}return value;};
  const restoring=f.controller.controlDurable(null,f.control,f.transport);await entered.promise;
  f.control={...f.control,state:'STOPPED',control_epoch:4};await f.controller.controlDurable(null,f.control,f.transport);
  release.resolve();await restoring;assertDiscarded(f);assert.equal(f.controller.admissions.size,0);assert.equal(f.resumes,1);
});

import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';
for(const state of ['STOPPED','DONE'])test(`${state} immediately clears watcher input and retry bookkeeping`,async()=>{
  const f=fixture(),watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
  await watcher.scanOnce();await f.settle();
  assert.equal(JSON.stringify([...watcher.inputs.values()]).includes(f.body.prompt),false,'watcher fingerprints must never retain raw prompts');
  watcher.failures.set(f.path,{retryAt:Date.now()+60000});watcher.deferred.set(f.path,{taskId:f.body.task_id});
  if(state==='COMMITTED'){f.result(true);await f.observe({});await f.controller.flush(f.transport);}
  else {f.control={...f.control,state,control_epoch:2};await f.controller.controlDurable(null,f.control,f.transport);}
  for(const name of ['inputs','deferred','failures','readFailures','jobs','reads','priority'])assert.equal(watcher[name].has(f.path),false,name);
  assert.equal(watcher.runningControls.has(f.body.task_id),false);assertDiscarded(f);watcher.stop();
});

test('unavailable terminal disposal is discarded rather than scheduled when availability returns',async()=>{
  const f=fixture();let attempts=0;f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:async()=>{attempts++;return {closed:true};}};
  f.controller=f.recreate();await f.existing();f.controller.canDispatch=()=>false;await f.stop();await f.controller.cleanupTerminalResources();
  assert.equal(f.controller.repository.cleanupEntries().length,0);f.controller.canDispatch=()=>true;await f.controller.cleanupTerminalResources();
  assert.equal(attempts,0);assertDiscarded(f);
});

import { discardTerminalHistorySnapshot } from '../src/maintenance-quarantine.mjs';
test('terminal discard cannot delete a different new HTTP identity which reused its path',()=>{
  const old={task_id:'OLD',turn_id:'OLD:1',request_id:'same-request',dispatch_attempt:1,control_epoch:9,state:'STOPPED',path:'shared.json'};old.key=keyFor(old);
  const next={schema:'selfrun-server-dispatch-v1',task_id:'NEW',turn_id:'NEW:1',request_id:'same-request',dispatch_attempt:1,server_control_epoch:1,prompt:'new input'};
  const snapshot={lifecycle:{requests:{},retired:{[old.key]:old},controls:{},events:[],outbox:{},completionOutbox:{},browserCleanup:{}},httpIngress:{files:{'shared.json':next},signals:{new:{path:'shared.json',task_id:'NEW',request_id:'same-request'}}}};
  const clean=discardTerminalHistorySnapshot(snapshot);assert.deepEqual(clean.httpIngress,snapshot.httpIngress);
});

test('STOP serializes behind an already-persisting admission and discards it',async()=>{
  const f=fixture();await f.existing();await f.stop();const entered=deferred(),release=deferred(),patch=f.store.patch.bind(f.store);let blocked=false;
  f.store.patch=async value=>{if(!blocked&&Object.keys(value.lifecycle?.requests||{}).length){blocked=true;entered.resolve();await release.promise;}return patch(value);};
  f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
  const restoring=f.controller.controlDurable(null,f.control,f.transport);await entered.promise;
  f.control={...f.control,state:'STOPPED',control_epoch:4};const stopping=f.controller.controlDurable(null,f.control,f.transport);
  await new Promise(resolve=>setImmediate(resolve));release.resolve();await Promise.all([restoring,stopping]);
  assertDiscarded(f);assert.equal(f.resumes,1);assert.equal(f.controller.admissions.size,0);
});

test('explicit STOP followed by restart and Result outage cannot recreate an old execution',async()=>{
  const f=fixture();await f.existing();await f.stop();assertDiscarded(f);
  f.controller=f.recreate();f.transport.readGoogleDocText=async()=>{throw Error('Result must not be read');};
  await f.controller.ingest(f.path,f.rows.get(f.path),f.transport);await f.controller.tick(f.transport);
  assertDiscarded(f);assert.equal(f.resumes,1);assert.equal(f.sends,0);
});

test('DONE has no final status publication to hold a new turn behind tick',async()=>{
  const f=fixture();await f.existing();let terminalWrites=0;const write=f.transport.write;
  f.transport.write=async(p,b,o)=>{if(b.server_status==='COMPLETED')terminalWrites++;return write(p,b,o);};
  f.control={...f.control,state:'DONE',control_epoch:2};await f.controller.control(null,f.control,f.transport);assertDiscarded(f);
  f.body={...f.body,turn_id:'SAFE-TEST:turn:2',request_id:'next-request',client_status:'CREATE_REQUESTED',result_document_id:'RESULT-2',conversation_url:'',server_status:'PENDING',server_control_epoch:3};
  f.path='next.json';f.control={...f.control,turn_id:f.body.turn_id,request_id:f.body.request_id,state:'RUNNING',control_epoch:3};
  await f.ingest();assert.equal(f.record().state,'PREPARED');assert.equal(f.prepares,1);assert.equal(terminalWrites,0);
  await f.controller.quiesceForStandby();
});

for(const state of ['STOPPED','DONE'])test(`${state} fences watcher body reads before lifecycle admission`,async()=>{
  const f=fixture(),entered=deferred(),release=deferred(),read=f.transport.read;let delayed=false;
  f.transport.read=async p=>{const body=await read(p);if(p===f.path&&!delayed){delayed=true;entered.resolve();await release.promise;}return body;};
  const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
  const scanning=watcher.scanOnce();await entered.promise;
  f.control={...f.control,state,control_epoch:2};await f.controller.controlDurable(null,f.control,f.transport);
  assert.equal(watcher.jobs.has(f.path),false);assert.equal(watcher.reads.has(f.path),false);assert.equal(watcher.inputs.has(f.path),false);
  release.resolve();await scanning;await f.settle();assertDiscarded(f);assert.equal(f.prepares,0);assert.equal(f.sends,0);watcher.stop();
});

for(const state of ['STOPPED','DONE'])test(`late watcher CONTROL rejection after ${state} cannot recreate retry data`,async()=>{
  const f=fixture();await f.existing();f.controller.tick=async()=>{};
  const entered=deferred(),release=deferred(),read=f.transport.read;let held=false;
  f.transport.read=async p=>{if(!held&&p.startsWith('__SELFRUN_CONTROL__')){held=true;entered.resolve();await release.promise;throw Error('discarded control read failed');}return read(p);};
  const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}}),scanning=watcher.scanOnce();await entered.promise;
  f.control={...f.control,state,control_epoch:2};await f.controller.controlDurable(null,f.control,f.transport);
  release.resolve();await scanning.catch(()=>{});
  assert.equal(watcher.readFailures.size,0);assert.equal(watcher.failures.size,0);assert.equal(watcher.controls.size,0);watcher.stop();
});

test('a discarded watcher job cannot erase replacement failure pacing on the same path',async()=>{
  const f=fixture();f.controller.tick=async()=>{};const entered=deferred(),release=deferred(),read=f.transport.read;let held=false,bad=false;
  f.transport.read=async p=>{if(p===f.path){if(bad)throw Error('current body failure');if(!held){held=true;const old=await read(p);entered.resolve();await release.promise;return old;}}return read(p);};
  const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}}),oldScan=watcher.scanOnce();await entered.promise;
  f.control={...f.control,state:'STOPPED',control_epoch:2};await f.controller.controlDurable(null,f.control,f.transport);
  f.control={...f.control,state:'RUNNING',control_epoch:3};bad=true;await watcher.scanOnce().catch(()=>{});
  const failure=watcher.failures.get(f.path);assert.ok(failure);release.resolve();await oldScan.catch(()=>{});
  assert.deepEqual(watcher.failures.get(f.path),failure);watcher.stop();
});

test('cold active discovery starts monitoring without any Result admission read',async()=>{
  const f=fixture();f.body={...f.body,conversation_url:f.probe.url,server_status:'STARTED'};f.rows.set(f.path,f.body);let reads=0;
  f.transport.readGoogleDocText=async()=>{reads++;throw Error('transient Result outage');};
  const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
  await watcher.scanOnce();await f.settle();assert.equal(reads,0);assert.equal(f.resumes,1);assert.equal(f.record().state,'OBSERVING');
  watcher.stop();await f.controller.quiesceForStandby();
});
