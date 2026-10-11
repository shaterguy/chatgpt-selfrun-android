import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';
import { StateStore } from '../src/state-store.mjs';

function doneFixture(t,options={}) {
  const f=fixture(options);f.resultReads=0;f.closed=[];
  const read=f.transport.readGoogleDocText;
  f.transport.readGoogleDocText=async(...args)=>{f.resultReads++;return read(...args);};
  f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:async(id,{guard})=>{
    if(!guard())return {closed:false};
    if(!f.closed.includes(id))f.closed.push(id);
    return {closed:true};
  },releaseIfUnused:async()=>({released:false})};
  f.controller=f.recreate();
  f.done=async()=>{
    f.control={...f.control,state:'DONE',control_epoch:f.control.control_epoch+1};
    await f.controller.control(null,f.control,f.transport);
  };
  t.after(()=>f.controller.quiesceForStandby());
  const existing=f.existing;f.existing=async()=>{await existing();f.resultReads=0;};
  return f;
}

function assertDiscarded(f, before) {
  assert.deepEqual(f.rows.get(f.path),before,'DONE sends no separate business state to the app');
  assert.equal(f.record(),null);
  assert.equal(f.controller.sessions.size,0);
  const lifecycle=f.store.snapshot().lifecycle;
  for(const field of ['requests','controls','outbox','completionOutbox','retired','browserCleanup'])
    assert.deepEqual(lifecycle[field]||{},{});
}

test('app DONE discards runtime and observer without Result reads or terminal publication',async t=>{
  const f=doneFixture(t);await f.existing();f.result(true);
  const observer=f.observations.at(-1),before=structuredClone(f.rows.get(f.path));
  await f.done();await f.controller.cleanupTerminalResources();assertDiscarded(f,before);
  assert.equal(f.resultReads,0);assert.equal(observer.signal.aborted,true);assert.deepEqual(f.closed,['target-owned']);
  for(let n=0;n<3;n++){await observer.onActivity({...f.probe,status:'STALLED'});await f.controller.ingest(f.path,before,f.transport);await f.controller.tick(f.transport);}
  assertDiscarded(f,before);assert.equal(f.resultReads,0);assert.equal(f.sends,0);assert.equal(f.resumes,1);
});

test('app DONE while the ten-minute Result read is pending cannot be revived by its callback',async t=>{
  const f=doneFixture(t);await f.existing();await f.observe({});f.result(true);
  await f.controller.repository.move(f.record().key,'OBSERVING','test deadline',{last_progress_at:Date.now()-600001});
  const entered=deferred(),release=deferred(),read=f.transport.readGoogleDocText;
  f.transport.readGoogleDocText=async id=>{entered.resolve();await release.promise;return read(id);};
  const observing=f.observe({});await entered.promise;
  const before=structuredClone(f.rows.get(f.path));await f.done();release.resolve();await observing;
  await f.controller.cleanupTerminalResources();assertDiscarded(f,before);assert.equal(f.sends,0);
});

for(const failure of ['read','write'])test('app DONE ignores '+failure+' publication failure and keeps no retry after restart',async t=>{
  const f=doneFixture(t);await f.existing();const before=structuredClone(f.rows.get(f.path)),read=f.transport.read;
  if(failure==='read')f.transport.read=async name=>{if(name===f.path)throw Error('Drive unavailable');return read(name);};
  else f.writeFail=true;
  await f.done();await f.controller.cleanupTerminalResources();assertDiscarded(f,before);
  f.controller=f.recreate();f.writeFail=false;f.transport.read=read;await f.controller.tick(f.transport);
  assertDiscarded(f,before);assert.equal(f.resultReads,0);assert.equal(f.resumes,1);assert.equal(f.sends,0);
});

for(const replacement of ['request','attempt'])test('DONE has no pending projection that can overwrite newer '+replacement,async t=>{
  const f=doneFixture(t);await f.existing();await f.done();
  const newer={...f.rows.get(f.path),...(replacement==='request'?{request_id:'successor-request'}:{dispatch_attempt:2})};
  f.rows.set(f.path,newer);await f.controller.tick(f.transport);
  assert.deepEqual(f.rows.get(f.path),newer);assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
  assert.equal(f.resultReads,0);assert.equal(f.sends,0);
});

test('STOP and DONE discard identically without an app-facing completion signal',async t=>{
  for(const state of ['STOPPED','DONE']){
    const f=doneFixture(t);await f.existing();const before=structuredClone(f.rows.get(f.path));
    f.control={...f.control,state,control_epoch:2};await f.controller.control(null,f.control,f.transport);
    await f.controller.cleanupTerminalResources();assertDiscarded(f,before);assert.equal(f.resultReads,0);
  }
});

test('task-wide DONE discards active execution even when the selected identity differs',async t=>{
  const f=doneFixture(t);await f.existing();const before=structuredClone(f.rows.get(f.path));
  f.control={...f.control,turn_id:'SAFE-TEST:turn:2',request_id:'next-request',state:'DONE',control_epoch:2};
  await f.controller.control(null,f.control,f.transport);await f.controller.cleanupTerminalResources();assertDiscarded(f,before);
});

test('a newer explicit watch command can be admitted from current CONTROL after DONE without retained terminal state',async t=>{
  const f=doneFixture(t);await f.existing();await f.done();await f.controller.cleanupTerminalResources();
  await f.resume();assert.equal(f.record().control_state,'RUNNING');assert.equal(f.resumes,2);assert.equal(f.sends,0);
  assert.deepEqual(f.store.snapshot().lifecycle.retired||{},{});
});

test('DONE leaves no executable records or queued projection after actual disk reload',async t=>{
  const dataDir=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-done-projection-'));
  t.after(()=>fs.rm(dataDir,{recursive:true,force:true}));
  const config={dataDir,stateFile:path.join(dataDir,'state.json'),eventsFile:path.join(dataDir,'events.jsonl')};
  const store=new StateStore(config);await store.init();
  const f=doneFixture(t,{store});await f.existing();f.writeFail=true;await f.done();
  assert.equal(Object.keys(store.snapshot().lifecycle.completionOutbox).length,0);
  const reloaded=new StateStore(config);await reloaded.init();
  assert.deepEqual(reloaded.snapshot().lifecycle.requests,{});
  assert.deepEqual(reloaded.snapshot().lifecycle.completionOutbox,store.snapshot().lifecycle.completionOutbox);
  f.store=reloaded;f.controller=f.recreate();f.writeFail=false;await f.controller.tick(f.transport);
  assert.equal(f.record(),null);assert.equal(f.resultReads,0);assert.equal(f.resumes,1);assert.equal(f.sends,0);
});

test('cold DONE control and dispatch leave no local work or browser activity',async()=>{
 const f=fixture();f.control={...f.control,state:'DONE',control_epoch:2};
 await f.controller.control(null,f.control,f.transport);await f.ingest();await f.controller.tick(f.transport);
 assert.equal(f.record(),null);assert.equal(f.resumes,0);assert.equal(f.prepares,0);assert.equal(f.sends,0);
 assert.equal(f.quiesces,0);assert.equal(f.controller.sessions.size,0);assert.deepEqual(f.store.snapshot().lifecycle.requests,{});
 assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
});
