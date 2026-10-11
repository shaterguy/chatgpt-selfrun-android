import test from 'node:test';
import assert from 'node:assert/strict';
import {controllerRuntime as legacyControllerRuntime} from './fixtures/extension-controller-dev25.mjs';
import {extensionRuntimeFixture,wait} from './helpers/extension-runtime-fixture.mjs';

test('source rewrite with retained legacy controller fails readiness without killing or replacing browser tabs',async t=>{
  // Exact pinned dev25 controller code, current real manager/HTTP bridge; only
  // Chrome process/tabs and the 20-second readiness budget are simulated.
  const f=await extensionRuntimeFixture(t,{runtime:legacyControllerRuntime,readinessBudgetMs:40});
  f.tabs.set(777,{id:777,url:'https://unrelated.invalid/'});
  await assert.rejects(f.manager.ensureStarted(),{code:'EXTENSION_UNAVAILABLE'});
  await wait(()=>f.requests.some(x=>x.path==='/events'&&x.status===409),'old events rejected');
  await assert.rejects(f.manager.ensureStarted(),{code:'EXTENSION_UNAVAILABLE',message:'Owned controller unavailable and browser cannot be safely retired'});
  assert.equal(f.launches,1);assert.equal(f.bridge.status().connected,false);assert.equal(f.bridge.status().controllerBuild,null);
  assert.equal(f.tabs.has(99),true);assert.equal(f.tabs.has(777),true);assert.equal(f.calls.some(x=>x[0]==='removed'),false);
});
