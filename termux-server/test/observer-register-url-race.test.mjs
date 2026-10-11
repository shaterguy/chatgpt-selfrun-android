import test from 'node:test';
import assert from 'node:assert/strict';
import {monitorRuntimeFixture} from './helpers/monitor-runtime-fixture.mjs';
import {connectedPageRuntime} from './helpers/connected-page-runtime-fixture.mjs';
test('exact target URL changes between metadata read and observer registration without losing observation',async t=>{
 const f=await monitorRuntimeFixture(t,{pageRuntimeFactory:args=>connectedPageRuntime(args)});
 const target=await f.manager.createTarget('https://chatgpt.com/g/test/project'),session=await f.manager.connectTarget(target);
 t.after(()=>session.close());await session.run('initialize',{clientProfile:'native'});
 const page=f.pageRuntimes.get(1),request=f.bridge.request.bind(f.bridge);let raced=false;
 f.bridge.request=async(operation,payload,options)=>{
  if(operation==='registerTarget'&&!raced){raced=true;page.context.window.history.pushState({},'', '/g/test/c/owned-1');console.log(JSON.stringify({event:'forced_same_target_url_race',expected:payload.expectedUrl,actual:page.context.location.href,target:payload.targetId}));}
  return request(operation,payload,options);
 };
 const observed=await session.run('progress');assert.equal(raced,true);assert.equal(observed.url,'https://chatgpt.com/g/test/c/owned-1');
 assert.equal(page.effects.filter(e=>e.kind==='input'||e.kind==='click').length,0);
});

test('same URL attestation failure never retries or acquires another target',async t=>{
 const f=await monitorRuntimeFixture(t,{pageRuntimeFactory:args=>connectedPageRuntime(args)});
 const target=await f.manager.createTarget('https://chatgpt.com/g/test/project'),session=await f.manager.connectTarget(target);
 t.after(()=>session.close());await session.run('initialize',{clientProfile:'native'});
 const request=f.bridge.request.bind(f.bridge);let registrations=0;
 f.bridge.request=async(operation,payload,options)=>{
  if(operation==='registerTarget'){registrations++;throw Object.assign(Error('Exact observation target unavailable'),{code:'TARGET_NOT_OWNED'});}
  return request(operation,payload,options);
 };
 await assert.rejects(session.run('progress'),error=>error.code==='TARGET_NOT_OWNED');assert.equal(registrations,1);
 assert.equal(f.calls.filter(c=>c[0]==='server'&&c[1]==='Target.createTarget').length,1);
});

test('STOP during changed-URL registration failure cannot retry the discarded target',async t=>{
 const f=await monitorRuntimeFixture(t,{pageRuntimeFactory:args=>connectedPageRuntime(args)});
 const target=await f.manager.createTarget('https://chatgpt.com/g/test/project'),session=await f.manager.connectTarget(target);
 t.after(()=>session.close());await session.run('initialize',{clientProfile:'native'});
 const request=f.bridge.request.bind(f.bridge);let registrations=0;
 f.bridge.request=async(operation,payload,options)=>{
  if(operation==='registerTarget'){registrations++;f.pageRuntimes.get(1).context.window.history.pushState({},'', '/g/test/c/owned-1');session.close();throw Object.assign(Error('Exact observation target unavailable'),{code:'TARGET_NOT_OWNED'});}
  return request(operation,payload,options);
 };
 await assert.rejects(session.run('progress'));assert.equal(registrations,1);
});
