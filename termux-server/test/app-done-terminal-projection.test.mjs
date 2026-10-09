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

function assertAppDone(f,resultState='UNKNOWN') {
  const row=f.rows.get(f.path);
  assert.equal(row.server_status,'COMPLETED');
  assert.equal(row.lifecycle_state,'STOPPED');
  assert.equal(row.server_control_state,'DONE');
  assert.equal(row.server_control_epoch,f.control.control_epoch);
  assert.equal(row.server_completion_reason,'APP_DONE');
  assert.equal(row.result_state,resultState,'app DONE must not fabricate Result proof');
  assert.equal(row.lifecycle_record,undefined,'terminal projection must contain no executable snapshot');
  assert.equal(f.record(),null);
  assert.deepEqual(f.controller.repository.records(),[]);
  assert.equal(f.store.snapshot().activeCount,0);
  assert.deepEqual(f.store.snapshot().lifecycle.outbox,{});
  assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
}

test('app DONE before Result read publishes terminal telemetry after retiring its observer',async t=>{
  const f=doneFixture(t);await f.existing();f.result(true);
  const observer=f.observations.at(-1),stale=structuredClone(f.rows.get(f.path));
  await f.done();await f.controller.cleanupTerminalResources();
  assertAppDone(f);assert.equal(f.resultReads,0);assert.equal(observer.signal.aborted,true);
  assert.deepEqual(f.closed,['target-owned']);assert.equal(f.controller.sessions.size,0);
  for(let n=0;n<3;n++) {
    await observer.onActivity({...f.probe,status:'STALLED'});
    await f.controller.ingest(f.path,stale,f.transport);
    await f.controller.tick(f.transport);
  }
  assert.equal(f.record(),null);assert.equal(f.resultReads,0);assert.equal(f.resumes,1);assert.equal(f.sends,0);
});

test('Result read before app DONE preserves true COMMITTED completion',async t=>{
  const f=doneFixture(t);await f.existing();f.result(true);
  await f.observe({});await f.done();await f.controller.flush(f.transport);
  const row=f.rows.get(f.path);
  assert.equal(row.server_status,'COMPLETED');assert.equal(row.lifecycle_state,'COMMITTED');
  assert.equal(row.result_state,'COMMITTED');assert.equal(f.record(),null);
  assert.equal(f.resultReads,2);assert.equal(f.sends,0);assert.equal(f.resumes,1);
});

test('late Result callback after app DONE cannot change terminal evidence or restore execution',async t=>{
  const f=doneFixture(t);await f.existing();f.result(true);
  const entered=deferred(),release=deferred(),read=f.transport.readGoogleDocText;
  f.transport.readGoogleDocText=async id=>{entered.resolve();await release.promise;return read(id);};
  const observing=f.observe({});await entered.promise;
  await f.done();release.resolve();await observing;await f.controller.flush(f.transport);
  assertAppDone(f);assert.equal(f.record(),null);assert.equal(f.sends,0);assert.equal(f.resumes,1);
});

for(const failure of ['read','write'])test('app DONE '+failure+' failure remains inert after restart without Result reads or browser reopening',async t=>{
  const f=doneFixture(t);await f.existing();
  await f.observe({}); // Preserve observed Result state rather than inventing proof.
  assert.equal(f.record().result_state,'NOT_COMMITTED');
  const resultReads=f.resultReads,read=f.transport.read;
  if(failure==='read')f.transport.read=async name=>{if(name===f.path)throw Error('Drive unavailable');return read(name);};
  else f.writeFail=true;
  await f.done();await f.controller.cleanupTerminalResources();
  assert.equal(f.record(),null);assert.equal(f.store.snapshot().activeCount,0);
  const pending=Object.values(f.store.snapshot().lifecycle.completionOutbox||{});
  assert.equal(pending.length,0);
  assert.deepEqual(f.closed,['target-owned']);assert.equal(f.controller.sessions.size,0);
  f.controller=f.recreate();f.writeFail=false;f.transport.read=read;
  await f.controller.tick(f.transport);assert.equal(f.record(),null);assert.equal(f.controller.sessions.size,0);
  assert.equal(f.resultReads,resultReads);assert.equal(f.resumes,1);assert.equal(f.sends,0);
});

for(const replacement of ['request','attempt'])test('pending app DONE cannot overwrite a reused '+replacement+' identity',async t=>{
  const f=doneFixture(t);await f.existing();f.writeFail=true;await f.done();
  assert.equal(Object.keys(f.store.snapshot().lifecycle.completionOutbox).length,0);
  const newer={...f.rows.get(f.path),...(replacement==='request'?{request_id:'successor-request'}:{dispatch_attempt:2})};
  f.rows.set(f.path,newer);f.writeFail=false;await f.controller.tick(f.transport);
  assert.deepEqual(f.rows.get(f.path),newer);assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
  assert.equal(f.sends,0);assert.equal(f.resultReads,0);
});

test('app DONE arriving during an older Drive write leaves the terminal projection last',async t=>{
  const f=doneFixture(t);await f.existing();
  await f.controller.repository.move(f.record().key,'OBSERVING','old pending telemetry');
  const entered=deferred(),release=deferred(),write=f.transport.write;let writes=0;
  f.transport.write=async(...args)=>{if(++writes===1){entered.resolve();await release.promise;}return write(...args);};
  const publishing=f.controller.flush(f.transport);await entered.promise;
  const done=f.done();await new Promise(resolve=>setImmediate(resolve));
  release.resolve();await Promise.all([publishing,done]);assertAppDone(f);assert.equal(writes,2);
});

test('STOP remains local stop and does not acquire DONE completion publication',async t=>{
  const f=doneFixture(t);await f.existing();const row=structuredClone(f.rows.get(f.path));
  await f.stop();await f.controller.flush(f.transport);
  assert.equal(f.record(),null);assert.equal(f.record(),null);
  assert.deepEqual(f.rows.get(f.path),row);assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
  assert.equal(f.resultReads,0);assert.equal(f.sends,0);
  await f.resume();assert.equal(f.record().retired,undefined);assert.equal(f.resumes,2);
});

test('task-wide DONE only marks the exact app-selected identity as completed',async t=>{
  const f=doneFixture(t);await f.existing();
  f.control={...f.control,turn_id:'SAFE-TEST:turn:2',request_id:'next-request',state:'DONE',control_epoch:2};
  await f.controller.control(null,f.control,f.transport);
  assert.equal(f.record(),null);assert.notEqual(f.rows.get(f.path).server_status,'COMPLETED');
  assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});assert.equal(f.resultReads,0);
});

test('pending DONE telemetry is discarded after a newer explicit same-identity resume',async t=>{
  const f=doneFixture(t);await f.existing();f.writeFail=true;await f.done();
  assert.equal(Object.keys(f.store.snapshot().lifecycle.completionOutbox).length,0);
  f.writeFail=false;await f.resume();await f.controller.tick(f.transport);
  assert.equal(f.record().retired,undefined);assert.equal(f.record().control_state,'RUNNING');
  assert.notEqual(f.rows.get(f.path).server_status,'COMPLETED');
  assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});assert.equal(f.sends,0);
});

test('explicit resume after published DONE clears the old completion reason',async t=>{
  const f=doneFixture(t);await f.existing();await f.done();assertAppDone(f);
  await f.resume();await f.controller.flush(f.transport);
  assert.equal(f.record().control_state,'RUNNING');assert.notEqual(f.rows.get(f.path).server_status,'COMPLETED');
  assert.equal(f.rows.get(f.path).server_completion_reason,undefined);assert.equal(f.sends,0);
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
