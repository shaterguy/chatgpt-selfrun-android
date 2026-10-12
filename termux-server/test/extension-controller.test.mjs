import test from 'node:test';import assert from 'node:assert/strict';
import {monitorRuntimeFixture,wait,deferred} from './helpers/monitor-runtime-fixture.mjs';
const target=async f=>{const t=await f.manager.createTarget(),s=await f.manager.connectTarget(t);await s.run('initialize',{clientProfile:'native'});await s.run('navigate',{url:'https://chatgpt.com/c/owned'});return{t,s};};
test('controller registers the exact server-owned tab without any create or send command',async t=>{
 const f=await monitorRuntimeFixture(t),{t:page,s}=await target(f);await s.run('progress');
 assert.equal(f.manager.journal.targetMetadata[page.id].observerTabId,1);assert.equal(f.requests.some(r=>r.body?.operation==='createTarget'||r.body?.operation==='submit'),false);
});
for(const op of ['createTarget','navigate','input','submit','receiptArm','evaluate'])test('controller transport excludes action '+op,async t=>{
 const f=await monitorRuntimeFixture(t),{t:page,s}=await target(f);await s.run('progress');await assert.rejects(f.bridge.request(op,{targetId:page.id}),e=>e.code==='EXTENSION_UNSUPPORTED');assert.equal(f.tabs.has(1),true);
});
test('monitor registration rejects a foreign session and wrong HMAC without adopting a user tab',async t=>{
 const f=await monitorRuntimeFixture(t),{s}=await target(f);await s.run('progress');f.tabs.set(777,{id:777,url:'https://chatgpt.com/c/owned'});
 for(const id of ['ext:foreign:CDP777','ext:'+f.bridge.browserSessionId+':CDP777'])await assert.rejects(f.bridge.request('registerTarget',{targetId:id,expectedUrl:'https://chatgpt.com/c/owned',challenge:'bad',mac:'wrong'}));
 assert.equal(f.tabs.has(777),true);assert.equal(f.manager.owned.size,1);
});
test('a registered tab remains observable while repurposed-conversation input is refused',async t=>{
 const f=await monitorRuntimeFixture(t),{s}=await target(f);await s.run('progress');f.tabs.get(1).url='https://chatgpt.com/c/user-other';
 const before=f.calls.filter(c=>c[0]==='selfrun.content.snapshot').length;assert.equal((await s.run('progress')).url,'https://chatgpt.com/c/user-other');await assert.rejects(s.run('input',{prompt:'never type'}),/repurposed/);
 assert.equal(f.calls.filter(c=>c[0]==='selfrun.content.snapshot').length,before+1);assert.equal(f.calls.some(c=>c[0]==='serverOperation'&&c[1]==='input'),false);assert.equal(f.tabs.has(1),true);
});
test('discard during registration attestation cancels its late callback without a tombstone',async t=>{
 const f=await monitorRuntimeFixture(t),{t:page,s}=await target(f);const gate=deferred();f.gates.attest=gate.promise;
 const progress=s.run('progress');progress.catch(()=>{});await wait(()=>f.calls.some(c=>c[0]==='script'));
 const disposed=await f.manager.discardOwnedTarget(page.id,{guard:()=>true});assert.equal(disposed.localDiscarded,true);gate.resolve();await assert.rejects(progress);await new Promise(r=>setImmediate(r));
 const stored=Object.values(f.storage).flatMap(x=>Object.keys(x));assert.equal(stored.includes(page.id),false);assert.equal(f.manager.actionSessions.size,0);assert.equal(f.tabs.has(1),true);
});
test('observer disposal preserves its controller lifetime and other user tabs',async t=>{
 const f=await monitorRuntimeFixture(t),{t:page,s}=await target(f);await s.run('progress');f.tabs.set(777,{id:777,url:'https://user.invalid/'});const connection=f.bridge.connection;
 await f.manager.discardOwnedTarget(page.id,{guard:()=>true});assert.equal(f.bridge.connection,connection);assert.equal(f.tabs.has(99),true);assert.equal(f.tabs.has(777),true);assert.equal((await f.bridge.request('controllerStop')).stopped,false);
});

test('a lost Android profile lease cannot gate observation or authorize profile effects on another conversation',async t=>{
 const f=await monitorRuntimeFixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});
 let profiles=0,alive=true;f.manager.deps.applyProfile=async()=>{profiles++;return{cdpCommands:3,cdpTargetId:'CDP1',isAlive:()=>alive,release(){}};};
 const page=await f.manager.createTarget('https://chatgpt.com/c/owned'),session=await f.manager.connectTarget(page);
 await session.run('initialize',{clientProfile:'android-webview'});await session.run('progress');
 alive=false;f.tabs.get(1).url='https://chatgpt.com/g/other/c/other';
 assert.equal((await session.run('progress')).url,'https://chatgpt.com/g/other/c/other');assert.equal(profiles,1);
 await assert.rejects(session.run('initialize',{clientProfile:'android-webview'}),/repurposed/);assert.equal(profiles,1);
});

test('controller closeTarget requires the exact registered URL before closing a tab',async t=>{
 const f=await monitorRuntimeFixture(t),{t:page,s}=await target(f);await s.run('progress');
 await assert.rejects(f.bridge.request('closeTarget',{targetId:page.id}),e=>e.code==='TARGET_SCOPE_CHANGED');
 await assert.rejects(f.bridge.request('closeTarget',{targetId:page.id,expectedUrl:'https://chatgpt.com/c/wrong'}),e=>e.code==='TARGET_SCOPE_CHANGED');
 assert.equal(f.tabs.has(1),true);
});
