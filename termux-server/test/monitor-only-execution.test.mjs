import test from 'node:test';import assert from 'node:assert/strict';
import {monitorRuntimeFixture,wait,deferred} from './helpers/monitor-runtime-fixture.mjs';
import {buildExtensionFiles} from '../src/browser/extension-package.mjs';

const prepare=async f=>{const target=await f.manager.createTarget();const session=await f.manager.connectTarget(target);await session.run('initialize',{clientProfile:'native'});await session.run('navigate',{url:'https://chatgpt.com/g/test/project'});return{target,session};};
const input=async s=>{await s.run('installProfile');await s.run('profileConfigure',{operations:[]});await s.run('installReceipt');await s.run('input',{prompt:'synthetic prompt'});await s.run('receiptArm',{key:{intent_id:'i',generation:'g'}});};
test('packaged extension has only isolated observation script and no page action runtime',()=>{const files=buildExtensionFiles({bridgeUrl:'http://127.0.0.1:9223',ownerId:'fixture',capability:'fixture-capability-12345'});const manifest=JSON.parse(files['manifest.json']);assert.equal(files['page-runtime.js'],undefined);assert.equal(manifest.content_scripts.length,1);assert.deepEqual(manifest.content_scripts[0].js,['content.js']);assert.equal(files['controller.js'].includes("chrome.tabs.create"),false);assert.equal(files['controller.js'].includes("chrome.tabs.update"),false);assert.equal(files['controller.js'].includes("helper.run("),false);});
test('server creates navigates types and clicks; extension only registers and reads progress',async t=>{const f=await monitorRuntimeFixture(t),{target,session}=await prepare(f);await input(session);await session.run('submit');await session.run('progress');assert.deepEqual(f.calls.filter(c=>c[0]==='serverOperation').map(c=>c[1]),['installProfile','profileConfigure','installReceipt','input','receiptArm','submit']);assert.equal(f.calls.filter(c=>c[0]==='server'&&c[1]==='Target.createTarget').length,1);assert.ok(f.calls.some(c=>c[0]==='selfrun.content.snapshot'));assert.equal(f.requests.some(r=>r.body?.operation==='input'||r.body?.operation==='submit'),false);assert.equal(f.manager.isOwnedTarget(target.id),true);});
for(const boundary of ['after-create','before-submit','after-submit'])test('observer reconnect at '+boundary+' never replays or cancels server input',async t=>{const f=await monitorRuntimeFixture(t),{target,session}=await prepare(f);if(boundary!=='after-create')await input(session);if(boundary==='after-submit')await session.run('submit');await wait(()=>f.bridge.status().connected,'initial observer connection');f.bridge.connection.res.end();await wait(()=>!f.bridge.status().connected,'observer disconnected');if(boundary==='after-create')await input(session);if(boundary!=='after-submit')await session.run('submit');await wait(()=>f.bridge.status().connected,'observer reconnected');await session.run('progress');assert.equal(f.calls.filter(c=>c[0]==='serverOperation'&&c[1]==='submit').length,1);assert.equal(f.calls.filter(c=>c[0]==='serverOperation'&&c[1]==='input').length,1);assert.equal(f.manager.isOwnedTarget(target.id),true);});
test('observer refuses any input or click operation',async t=>{const f=await monitorRuntimeFixture(t),{target,session}=await prepare(f);await session.run('progress');for(const op of ['input','submit','navigate','installProfile','receiptArm'])await assert.rejects(f.bridge.request(op,{targetId:target.id,prompt:'not allowed'}),e=>e.code==='EXTENSION_UNSUPPORTED');assert.equal(f.calls.some(c=>c[0]==='serverOperation'&&c[1]==='submit'),false);});
for(const state of ['STOPPED','DONE'])test(state+' clears action and observer caches when owned tab close fails',async t=>{
 const f=await monitorRuntimeFixture(t),{target,session}=await prepare(f);await input(session);await session.run('progress');f.gates.removeError=Error('simulated close failure');
 const result=await f.manager.closeOwnedTarget(target.id,{guard:()=>true});assert.equal(result.closed,false);
 const disposed=await f.manager.discardOwnedTarget(target.id,{guard:()=>true});assert.equal(disposed.localDiscarded,true);
 assert.equal(f.manager.actionSessions.size,0);assert.equal(f.manager.sessions.size,0);assert.equal(f.manager.owned.size,0);assert.equal(f.manager.journal.targetMetadata[target.id],undefined);
 assert.equal(f.calls.some(c=>c[0]==='actionClosed'),true);assert.equal(f.calls.some(c=>c[0]==='selfrun.content.dispose'),true);
 await assert.rejects(session.run('submit'));assert.equal(f.calls.some(c=>c[0]==='serverOperation'&&c[1]==='submit'),false);
});
test('late server click completion cannot restore discarded action or observer ownership',async t=>{
 const f=await monitorRuntimeFixture(t),{target,session}=await prepare(f);await input(session);await session.run('progress');const gate=deferred();f.gates.scripts.set(1,gate.promise);
 const submit=session.run('submit');submit.catch(()=>{});await wait(()=>f.calls.some(c=>c[0]==='serverOperation'&&c[1]==='submit'));
 await f.manager.discardOwnedTarget(target.id,{guard:()=>true});gate.resolve();await assert.rejects(submit);await new Promise(r=>setImmediate(r));
 assert.equal(f.manager.actionSessions.size,0);assert.equal(f.manager.owned.size,0);assert.equal(f.manager.journal.targetMetadata[target.id],undefined);
 assert.equal(f.calls.filter(c=>c[0]==='serverOperation'&&c[1]==='submit').length,1);
});
test('controller health identifies monitor-only build and refuses old build before stream replacement',async t=>{
 const f=await monitorRuntimeFixture(t);await prepare(f);await wait(()=>f.bridge.status().connected);
 assert.equal(f.bridge.status().controllerBuild,'selfrun-monitor-only-v3');const current=f.bridge.connection;
 const response=await fetch(f.bridge.url+'/events',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer fixture-only-capability-123456','X-SelfRun-Owner':'fixture-owner',Origin:'chrome-extension://test'},body:JSON.stringify({browserSessionId:f.bridge.browserSessionId,controllerBuild:'selfrun-controller-lock-session-v1'})});
 assert.equal(response.status,409);await response.text();assert.equal(f.bridge.connection,current);
});
test('STOP during create response settles exact created bootstrap without retaining its identity',async t=>{
 const f=await monitorRuntimeFixture(t),original=f.manager.deps.browserAction;let active=true;
 f.manager.deps.browserAction=async args=>{const value=await original(args);if(args.method==='Target.createTarget')active=false;return value;};
 await assert.rejects(f.manager.createTarget('about:blank',{guard:()=>active}),/owner ended/);
 assert.equal(f.manager.owned.size,0);assert.equal(f.manager.pendingAcquisitions.size,0);assert.deepEqual(f.manager.journal.targetMetadata,{});assert.equal(f.tabs.size,1);
});
test('lost create reply reconciles its exact random bootstrap once without another creation',async t=>{
 const f=await monitorRuntimeFixture(t),original=f.manager.deps.browserAction;
 f.manager.deps.browserAction=async args=>{const value=await original(args);if(args.method==='Target.createTarget')throw Error('lost create reply');return value;};
 const target=await f.manager.createTarget();assert.equal(f.manager.owned.has(target.id),true);assert.equal(f.calls.filter(c=>c[0]==='server'&&c[1]==='Target.createTarget').length,1);assert.equal(f.tabs.size,2);
});
test('a cold CDP listener error retries the same create action before any tab effect',async t=>{
 const f=await monitorRuntimeFixture(t),read=f.manager.deps.readCdpMetadata;let versions=0;
 f.manager.deps.readCdpMetadata=async(...args)=>{if(args[1]==='version'&&++versions===1)throw Error('ECONNREFUSED');return read(...args);};
 assert.ok(await f.manager.createTarget());assert.ok(versions>=3);assert.equal(f.calls.filter(c=>c[0]==='server'&&c[1]==='Target.createTarget').length,1);
});
test('browser endpoint replacement during controller proof never creates a tab',async t=>{
 const f=await monitorRuntimeFixture(t),read=f.manager.deps.readCdpMetadata;let versions=0;
 f.manager.deps.readCdpMetadata=async(...args)=>args[1]==='version'&&++versions===2?{webSocketDebuggerUrl:'ws://127.0.0.1:19227/devtools/browser/other'}:read(...args);
 await assert.rejects(f.manager.createTarget(),/ownership is unproven/);assert.equal(f.calls.some(c=>c[0]==='server'&&c[1]==='Target.createTarget'),false);
});
for(const delayAt of ['inventory','attestation'])test('cold controller '+delayAt+' readiness retries before one tab creation',async t=>{
 const f=await monitorRuntimeFixture(t);let count=0;
 if(delayAt==='inventory'){const read=f.manager.deps.readCdpMetadata;f.manager.deps.readCdpMetadata=async(...args)=>args[1]==='list'&&++count<3?[]:read(...args);}
 else{const proof=f.manager.deps.attestController;f.manager.deps.attestController=async args=>++count<3?null:proof(args);}
 assert.ok(await f.manager.createTarget());assert.ok(count>=3);assert.equal(f.calls.filter(c=>c[0]==='server'&&c[1]==='Target.createTarget').length,1);
});
test('late create inventory after STOP is cleaned once and never enters any registry',async t=>{
 const f=await monitorRuntimeFixture(t),read=f.manager.deps.readCdpMetadata,create=f.manager.deps.browserAction;let active=true,created=false,reads=0;
 f.manager.deps.browserAction=async args=>{const value=await create(args);if(args.method==='Target.createTarget'){active=false;created=true;throw Error('response lost');}return value;};
 f.manager.deps.readCdpMetadata=async(...args)=>args[1]==='list'&&created&&++reads<3?[]:read(...args);
 await assert.rejects(f.manager.createTarget('about:blank',{guard:()=>active}),/owner ended/);assert.equal(f.tabs.size,1);assert.equal(f.manager.owned.size,0);assert.deepEqual(f.manager.journal.targetMetadata,{});assert.equal(f.calls.filter(c=>c[0]==='server'&&c[1]==='Target.createTarget').length,1);
});
test('ambiguous lost-create inventory is not adopted or closed as an owned target',async t=>{
 const f=await monitorRuntimeFixture(t),create=f.manager.deps.browserAction;
 f.manager.deps.browserAction=async args=>{const value=await create(args);if(args.method==='Target.createTarget'){f.tabs.set(777,{id:777,url:args.params.url});throw Error('response lost');}return value;};
 await assert.rejects(f.manager.createTarget(),/response lost/);assert.equal(f.manager.owned.size,0);assert.equal(f.calls.some(c=>c[0]==='server'&&c[1]==='Target.closeTarget'),false);assert.equal(f.calls.filter(c=>c[0]==='server'&&c[1]==='Target.createTarget').length,1);
});
test('generic helper purge excludes authoritative active targets even when local owned metadata is missing',async t=>{
 const f=await monitorRuntimeFixture(t),{target,session}=await prepare(f);await session.run('progress');
 f.manager.setLifecycleGuards({activeTargetIds:()=>[target.id]});f.manager.owned.delete(target.id);f.manager.pendingAcquisitions.delete(target.id);
 const purges=[];f.manager.deps.purgeInactiveAction=async args=>{purges.push(args.row.id);return true;};
 await f.manager.reconcileInactiveActions();assert.deepEqual(purges,[]);
 f.manager.setLifecycleGuards({activeTargetIds:()=>[]});await f.manager.reconcileInactiveActions();assert.deepEqual(purges,['CDP1']);assert.equal(f.tabs.has(1),true);assert.equal(f.manager.owned.size,0);
});
test('reconnected observer sweeps inactive helper without an ended-target cleanup list',async t=>{
 const f=await monitorRuntimeFixture(t),{target,session}=await prepare(f);await session.run('progress');const action=f.manager.actionSessions.get(target.id);action.session.call=async()=>{throw Error('socket unavailable');};
 const result=await f.manager.discardOwnedTarget(target.id,{guard:()=>true});assert.equal(result.actionCachePurged,false);assert.equal(result.remoteCachePurged,false);assert.equal(JSON.stringify(f.manager.journal).includes(target.id),false);
 const prior=f.bridge.connection.id;f.bridge.connection.res.end();await wait(()=>f.bridge.connection?.ready&&f.bridge.connection.id!==prior);
 await wait(()=>f.calls.some(c=>c[0]==='inactivePurge'&&c[1]==='CDP1'));assert.equal(f.manager.owned.size,0);assert.equal(f.tabs.has(1),true);assert.equal(f.manager.actionSessions.size,0);
});
test('existing sibling action reconnect sweeps inactive helper while observer connection remains unchanged',async t=>{
 const f=await monitorRuntimeFixture(t),old=await prepare(f),sibling=await prepare(f);await old.session.run('progress');await sibling.session.run('progress');
 const dead=f.manager.actionSessions.get(old.target.id);dead.session.call=async()=>{throw Error('lost old action socket');};await f.manager.discardOwnedTarget(old.target.id,{guard:()=>true});
 await new Promise(r=>setImmediate(r));const currentConnection=f.bridge.connection.id;const purges=[];f.manager.deps.purgeInactiveAction=async args=>{purges.push(args.row.id);return args.guard();};
 const live=f.manager.actionSessions.get(sibling.target.id),call=live.session.call.bind(live.session);live.session.socket={readyState:3};live.reconnect=async()=>({socket:{readyState:1},close(){},call});
 await sibling.session.run('recoveryInput');await wait(()=>purges.includes('CDP1'));
 assert.equal(f.bridge.connection.id,currentConnection);assert.equal(purges.includes('CDP2'),false);assert.equal(f.manager.owned.has(old.target.id),false);assert.equal(f.manager.owned.has(sibling.target.id),true);
});
