import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';
import { monitorRuntimeFixture } from './helpers/monitor-runtime-fixture.mjs';

// This tests the real controller scheduling, not just the browser close helper.
test('next turn does not prepare a new tab before the prior owned tab has closed', async t=>{
  const f=fixture(),closeStarted=deferred(),allowClose=deferred(),owned=new Set(['old-owned']);
  let closes=0,prepares=0,sessionClosures=0;
  t.after(()=>{allowClose.resolve();return f.controller.quiesceForStandby();});
  f.browser.chromium={
    transport:'extension',isOwnedTarget:id=>owned.has(id),
    closeOwnedTarget:async(id,{guard})=>{
      closes++;assert.equal(id,'old-owned');assert.equal(guard({id,url:f.probe.url}),true);
      closeStarted.resolve();await allowClose.promise;owned.delete(id);return {closed:true};
    },releaseIfUnused:async()=>({released:false}),
  };
  f.browser.resume=async args=>{
    f.resumes++;const target={id:'old-owned'};await args.onTarget(target);
    return {target,session:{close(){sessionClosures++;}},baseline:{...f.probe}};
  };
  f.browser.prepare=async args=>{
    prepares++;assert.equal(owned.has('old-owned'),false,'old owned tab cannot overlap the next fresh prepare');
    assert.equal(args.previousTargetId,null);
    const target={id:'new-owned'};owned.add(target.id);await args.onTarget(target);
    return {target,session:{close(){}},baseline:{...f.probe}};
  };
  f.controller=f.recreate();await f.existing();
  assert.equal(f.resumes,1);
  f.control={...f.control,turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',control_epoch:2};
  await f.controller.control(null,f.control,f.transport);
  const body={...f.body,turn_id:f.control.turn_id,request_id:f.control.request_id,
    result_document_id:'RESULT-2',conversation_url:'',client_status:'CREATE_REQUESTED',dispatch_attempt:1,server_control_epoch:2};
  f.result(false,'RESULT-2',2);
  const path='__SELFRUN_DISPATCH__'+body.request_id+'__A1.json';f.rows.set(path,structuredClone(body));
  const next=f.controller.ingest(path,body,f.transport);
  await closeStarted.promise;await new Promise(resolve=>setImmediate(resolve));
  assert.equal(prepares,0,'new target must not be created while old CDP/tab close is unresolved');
  allowClose.resolve();await next;await f.settle();
  assert.equal(prepares,1);assert.equal(closes,1);assert.equal(sessionClosures>0,true);
});

test('first turn without a predecessor follows the same fresh prepare path',async t=>{
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());
  await f.ingest();assert.equal(f.prepares,1);
  assert.equal(f.controller.repository.cleanupEntries().length,0);
});

test('owned extension tab closes if CDP Target.closeTarget times out',async t=>{
  const wire=await monitorRuntimeFixture(t);
  const target=await wire.manager.createTarget('https://chatgpt.com/c/prior-owned');
  const session=await wire.manager.connectTarget(target);
  await session.run('initialize',{clientProfile:'native'});await session.run('snapshot');session.close();
  const previous=wire.manager.deps.browserAction;
  wire.manager.deps.browserAction=async args=>{
    if(args.method==='Target.closeTarget')throw Object.assign(Error('CDP command timeout'),{code:'CDP_COMMAND_TIMEOUT'});
    return previous(args);
  };
  const closed=await wire.manager.closeOwnedTarget(target.id,{caller:true,guard:()=>true});
  assert.equal(closed.closed,true);assert.equal(closed.reason,'extension_close');
  assert.equal(wire.manager.isOwnedTarget(target.id),false);
  assert.equal([...wire.tabs.values()].some(t=>t.url==='https://chatgpt.com/c/prior-owned'),false);
  assert.equal(wire.tabs.has(99),true,'controller and unrelated tabs survive');
});

test('extension fallback refuses to close a repurposed or unrelated browser tab',async t=>{
  const wire=await monitorRuntimeFixture(t);
  const target=await wire.manager.createTarget('https://chatgpt.com/c/original-owned');
  const session=await wire.manager.connectTarget(target);
  await session.run('initialize',{clientProfile:'native'});await session.run('snapshot');session.close();
  const previous=wire.manager.deps.browserAction;
  wire.manager.deps.browserAction=async args=>{
    if(args.method==='Target.closeTarget')throw Error('CDP unavailable');
    return previous(args);
  };
  for(const tab of wire.tabs.values())if(tab.url==='https://chatgpt.com/c/original-owned')tab.url='https://chatgpt.com/c/repurposed';
  const closed=await wire.manager.closeOwnedTarget(target.id,{caller:true,guard:()=>true});
  assert.equal(closed.closed,false);
  assert.equal([...wire.tabs.values()].some(t=>t.url==='https://chatgpt.com/c/repurposed'),true);
});

test('successor closure releases an Android CDP profile lease before another fresh tab',async t=>{
  const wire=await monitorRuntimeFixture(t);let releases=0;
  wire.config.browserClientProfile='android-webview';
  wire.config.extensionProfileBootstrap='cdp-emulation';
  wire.manager.deps.applyProfile=async()=>({cdpCommands:3,fingerprint:'test-profile',isAlive:()=>true,release(){releases++;}});
  const old=await wire.manager.createTarget('https://chatgpt.com/c/old-profile');
  const session=await wire.manager.connectTarget(old);
  await session.run('initialize',{clientProfile:'android-webview'});
  await session.run('snapshot');session.close();
  assert.equal(wire.manager.status().profileConnections,1);
  const action=wire.manager.deps.browserAction;
  wire.manager.deps.browserAction=async args=>args.method==='Target.closeTarget'
    ?Promise.reject(Error('CDP command timeout')):action(args);
  const closed=await wire.manager.closeOwnedTarget(old.id,{caller:true,guard:()=>true});
  assert.equal(closed.closed,true);assert.equal(releases,1);
  assert.equal(wire.manager.status().profileConnections,0);
  const next=await wire.manager.createTarget('about:blank');
  assert.notEqual(next.id,old.id);
});
