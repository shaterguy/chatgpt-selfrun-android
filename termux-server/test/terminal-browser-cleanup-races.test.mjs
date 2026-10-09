import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';

test('CANCELLED commit racing target acquisition retains cleanup for an existing owned tab', async () => {
  const f=fixture(),targetGate=deferred(),opening=deferred();
  const cancelSave=deferred(),cancelSaveGate=deferred(),closed=[];
  f.browser.chromium={
    isOwnedTarget:()=>true,
    closeOwnedTarget:async(id,{guard})=>{
      if(!guard())return {closed:false};
      closed.push(id);return {closed:true};
    },
    releaseIfUnused:async()=>({released:false}),
  };
  f.browser.resume=async args=>{
    opening.resolve();await targetGate.promise;
    await args.onTarget({id:'target-owned'});
    // ChatGptBrowser.resume preserves an existing target when attachment aborts.
    if(args.signal.aborted)throw Error('aborted existing attachment');
    return {target:{id:'target-owned'},session:{close(){}},baseline:f.probe};
  };
  f.controller=f.recreate();
  const attaching=f.existing();await opening.promise;
  const patch=f.store.patch.bind(f.store);let held=false;
  f.store.patch=async value=>{
    if(!held&&value.status==='IDLE'&&Object.keys(f.store.snapshot().lifecycle?.requests||{}).length&&!Object.keys(value.lifecycle?.requests||{}).length){
      held=true;cancelSave.resolve();await cancelSaveGate.promise;
    }
    return patch(value);
  };
  f.body.client_status='CANCELLED';
  const cancelling=f.ingest();await cancelSave.promise;
  targetGate.resolve();await new Promise(resolve=>setImmediate(resolve));
  cancelSaveGate.resolve();await cancelling;await attaching;
  await f.controller.cleanupTerminalResources();
  assert.deepEqual(closed,['target-owned']);
  assert.equal(f.record(),null);
  assert.equal(f.controller.sessions.size,0);
  assert.deepEqual(f.store.snapshot().lifecycle.browserCleanup,{});
  assert.equal(f.sends,0);
});
