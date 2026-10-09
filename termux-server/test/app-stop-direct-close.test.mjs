import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';

function owned() {
  const f=fixture();f.closed=[];f.stopUiCalls=0;
  f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:async(id,{guard})=>{
    if(!guard())return {closed:false};if(!f.closed.includes(id))f.closed.push(id);return {closed:true};
  },releaseIfUnused:async()=>({released:false})};
  f.browser.quiesce=async()=>{f.stopUiCalls++;throw Error('APP STOP must not click generation controls');};
  f.controller=f.recreate();return f;
}
test('APP STOP directly closes owned tab without UI stop, receipt readback or remote-stop claim',async()=>{
  const f=owned();await f.existing();const observer=f.observations.at(-1),reads=f.readbacks;
  await f.stop();await f.controller.cleanupTerminalResources();
  assert.deepEqual(f.closed,['target-owned']);assert.equal(f.stopUiCalls,0);assert.equal(f.readbacks,reads);
  assert.equal(f.record(),null);assert.equal(f.probe.streaming,true);
  assert.equal(observer.signal.aborted,true);assert.equal(f.controller.sessions.size,0);
  const marker=structuredClone(f.record());await observer.onActivity({...f.probe,status:'STALLED'});
  assert.deepEqual(f.record(),marker);assert.equal(f.sends,0);
});
test('APP STOP never reconciles an uncertain initial POST before closing its owned target',async()=>{
  const f=owned();f.mode='unknown';await f.ingest();const reads=f.readbacks;
  await f.stop();await f.controller.cleanupTerminalResources();
  assert.deepEqual(f.closed,['target-new']);assert.equal(f.readbacks,reads);assert.equal(f.stopUiCalls,0);
  assert.equal(f.record(),null);assert.equal(f.sends,1);
});
test('late attached owned tab is disposed by STOP without generation control or task resurrection',async()=>{
  const f=owned(),entered=deferred(),gate=deferred();let disposed=0;
  f.browser.resume=async args=>{entered.resolve();await gate.promise;await args.onTarget?.({id:'late-owned'});
    return {target:{id:'late-owned'},session:{close(){disposed++;}},baseline:f.probe};};
  const opening=f.existing();await entered.promise;await f.stop();gate.resolve();await opening;
  await f.controller.cleanupTerminalResources();assert.deepEqual(f.closed,['late-owned']);
  assert.equal(disposed,1);assert.equal(f.stopUiCalls,0);assert.equal(f.readbacks,0);
  assert.equal(f.record(),null);assert.equal(f.sends,0);
});
