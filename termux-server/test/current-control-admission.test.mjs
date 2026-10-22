import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture} from './helpers/lifecycle-fixture.mjs';
import {DriveDispatchWatcher} from '../src/drive-watcher.mjs';
import {discardTerminalHistorySnapshot} from '../src/maintenance-quarantine.mjs';

const emptyControls=f=>{
  assert.deepEqual(f.controller.repository.data.controls,{});
  assert.deepEqual(f.store.snapshot().lifecycle.controls,{});
  assert.equal(f.controller.repository.records().length,0);
};

test('polling current RUNNING dispatch ignores historical completion labels without repeating its send',async()=>{
  const f=fixture();f.body={...f.body,server_status:'COMPLETED',lifecycle_state:'COMMITTED'};
  f.rows.set(f.path,f.body);f.result(true);
  const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
  await watcher.scanOnce();await f.settle();await f.controller.tick(f.transport);
  assert.equal(f.sends,1);assert.equal(f.prepares,1);assert.equal(f.record().state,'OBSERVING');
  assert.equal(f.record().control_epoch,1);assert.equal(f.controller.sessions.size,1);
  // A restart restores this current execution, never a second initial submission.
  await f.controller.quiesceForStandby();
  f.controller=f.recreate();
  await new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}}).scanOnce();
  await f.settle();assert.equal(f.sends,1);assert.equal(f.prepares,1);
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.controller.sessions.size,1);
  await f.controller.quiesceForStandby();
});

test('current RUNNING control URL starts observation despite historical completion and committed Result',async()=>{
  const f=fixture();f.body={...f.body,server_status:'COMPLETED',lifecycle_state:'COMMITTED'};
  f.rows.set(f.path,f.body);f.result(true);f.control={...f.control,conversation_url:f.probe.url};
  await f.controller.initialize();await f.controller.controlDurable(null,f.control,f.transport);
  await f.controller.tick(f.transport);
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.record().conversation_url,f.probe.url);
  assert.equal(f.resumes,1);assert.equal(f.sends,0);assert.equal(f.prepares,0);
  const observer=f.observations.at(-1);await f.stall();
  assert.equal(f.sends,1);assert.equal(observer.signal.aborted,false);assert.equal(f.controller.sessions.size,1);
  await f.controller.quiesceForStandby();
});

test('a control arriving before a fresh dispatch is not retained and is reread when the dispatch arrives',async()=>{
  const f=fixture();f.rows.clear();
  const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
  await watcher.scanOnce();await f.settle();emptyControls(f);
  f.rows.set(f.path,f.body);await watcher.scanOnce();await f.settle();
  assert.equal(f.sends,1);assert.equal(f.record().state,'OBSERVING');
  assert.equal(f.controller.controlForTask(f.body.task_id).request_id,f.body.request_id);
  await f.controller.quiesceForStandby();
});

test('startup discards orphan RUNNING controls while preserving current ongoing authority',async()=>{
  const f=fixture();await f.ingest();await f.controller.quiesceForStandby();
  const state=f.store.snapshot();state.lifecycle.controls.OLD={...f.control,task_id:'OLD',request_id:'OLD-REQUEST'};
  const clean=discardTerminalHistorySnapshot(state);
  assert.deepEqual(Object.keys(clean.lifecycle.controls),[f.body.task_id]);
  await f.store.patch(state);f.controller=f.recreate();await f.controller.initialize();
  assert.deepEqual(Object.keys(f.controller.repository.data.controls),[f.body.task_id]);
  await f.controller.tick(f.transport);assert.equal(f.sends,1);
  assert.equal(f.record().state,'OBSERVING');await f.controller.quiesceForStandby();
});

test('poll status proves a completed discovery pass without returning task identities',async()=>{
 const f=fixture();f.rows.clear();const w=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
 assert.equal(w.status().completedScans,0);await w.scanOnce();
 const status=w.status();assert.equal(status.completedScans,1);assert.equal(status.pendingFiles,0);assert.equal(status.pendingReads,0);assert.equal(status.failedFiles,0);assert.equal(status.deferredFiles,0);
 assert.equal(status.dispatchEnabled,true);assert.equal(JSON.stringify(status).includes('SAFE-TEST'),false);
 w.stop();assert.equal(w.status().dispatchEnabled,false);
});
