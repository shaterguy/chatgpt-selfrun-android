import test from 'node:test';
import assert from 'node:assert/strict';
import {monitorRuntimeFixture,wait} from './helpers/monitor-runtime-fixture.mjs';

test('actual controller runtime exposes immutable v2 build matching accepted bridge hello',async t=>{
  const f=await monitorRuntimeFixture(t);
  await f.manager.createTarget();
  await wait(()=>f.bridge.status().connected);
  const descriptor=Object.getOwnPropertyDescriptor(f.runtimeContext,'__selfrunMonitorBuild');
  assert.equal(descriptor?.value,'selfrun-monitor-only-v2');
  assert.equal(descriptor?.writable,false);
  assert.equal(descriptor?.configurable,false);
  assert.equal(f.bridge.status().controllerBuild,descriptor.value);
  const current=f.bridge.connection;
  const response=await fetch(f.bridge.url+'/events',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer fixture-only-capability-123456','X-SelfRun-Owner':'fixture-owner',Origin:'chrome-extension://test'},body:JSON.stringify({browserSessionId:f.bridge.browserSessionId,controllerBuild:'selfrun-monitor-only-v1'})});
  assert.equal(response.status,409);
  await response.text();
  assert.equal(f.bridge.connection,current);
});
