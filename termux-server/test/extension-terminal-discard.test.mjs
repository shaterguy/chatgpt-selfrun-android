import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {monitorRuntimeFixture as extensionRuntimeFixture,deferred,wait} from './helpers/monitor-runtime-fixture.mjs';

const tabNumber=target=>Number(target.id.split(':').at(-1).replace(/^CDP/,''));
async function prepared(f,name='owned'){const target=await f.target(name),session=await f.manager.connectTarget(target);await session.run('initialize',{clientProfile:'native'});await session.run('snapshot');session.close();return{...target,url:f.tabs.get(tabNumber(target)).url};}
const stored=f=>f.storage['selfrun-extension-controller-v1:'+f.manager.journal.browserSessionId]||{};

test('failed close leaves runtime owned until terminal disposal erases both actor and monitor state',async t=>{
  const f=await extensionRuntimeFixture(t),target=await prepared(f);
  const session=await f.manager.connectTarget(target),key={intent_id:'ended-intent',generation:'ended-generation',prompt:'private terminal prompt'};
  await session.run('receiptArm',{key});await session.run('submit');session.close();
  let released=0;f.manager.profileLeases.set(target.id,{release(){released++;},isAlive:()=>true});
  f.gates.removeError=Error('synthetic close failure');
  await f.manager.closeOwnedTarget(target.id,{guard:()=>true});
  assert.equal(f.manager.owned.has(target.id),true);assert.equal(f.manager.closing.has(target.id),true);
  const action=f.manager.actionSessions.get(target.id);assert.equal(action.armed.intentId,key.intent_id);assert.equal(action.released.intentId,key.intent_id);assert.equal(Object.hasOwn(stored(f)[target.id],'armed'),false);assert.equal(Object.hasOwn(stored(f)[target.id],'released'),false);
  // End-to-end terminal disposal is independent of the already-aborted session.
  const result=await f.manager.discardOwnedTarget(target.id,{guard:()=>true});
  assert.equal(result.localDiscarded,true);assert.equal(result.remoteCachePurged,true);
  assert.equal(f.manager.owned.has(target.id),false);assert.equal(f.manager.closing.has(target.id),false);
  assert.equal(f.manager.journal.targetMetadata[target.id],undefined);assert.equal(f.manager.profileLeases.has(target.id),false);assert.equal(released,1);
  assert.equal(stored(f)[target.id],undefined);assert.equal(f.tabs.has(tabNumber(target)),true);
  assert.equal(action.closed,true);assert.equal(action.armed,null);assert.equal(action.released,null);assert.equal(f.manager.actionSessions.has(target.id),false);
  assert.ok(f.calls.some(row=>row[0]==='selfrun.content.dispose'));
  assert.equal((await f.bridge.request('listTargets')).some(x=>x.id===target.id),false);
  assert.equal((await f.manager.listExistingTargets()).some(x=>x.id===target.id),false);
  assert.equal(f.manager.owned.has(target.id),false,'remote inventory cannot re-adopt ended ownership');
  const journal=JSON.parse(await fs.readFile(f.manager.journalFile,'utf8'));assert.equal(journal.targets.includes(target.id),false);assert.equal(journal.targetMetadata[target.id],undefined);
});

test('terminal disposal fences session work and late commands while preserving sibling owned and user tabs',async t=>{
  const f=await extensionRuntimeFixture(t),old=await prepared(f,'ended'),active=await prepared(f,'active');
  f.tabs.set(555,{id:555,url:'https://example.test/manual'});
  const session=await f.manager.connectTarget(old);await session.run('receiptArm',{key:{intent_id:'i',generation:'g'}});
  const gate=deferred();f.gates.scripts.set(tabNumber(old),gate.promise);
  const submitting=session.run('submit').catch(e=>e);await wait(()=>f.calls.some(x=>x[0]==='serverOperation'&&x[1]==='submit'),'pending submit');
  const queued=session.run('input',{prompt:'queued ended prompt'}).catch(e=>e);session.close();
  const result=await f.manager.discardOwnedTarget(old.id,{guard:()=>true});assert.equal(result.localDiscarded,true);
  gate.resolve();await Promise.all([submitting,queued]);await new Promise(r=>setImmediate(r));
  assert.equal(stored(f)[old.id],undefined);assert.ok(stored(f)[active.id]);assert.equal(f.manager.owned.has(active.id),true);
  assert.equal(f.calls.some(x=>x[0]==='serverOperation'&&x[1]==='input'),false);
  assert.equal(f.tabs.has(555),true);assert.equal(f.calls.some(x=>x[0]==='removed'),false);
});

test('unreachable controller is reported unconfirmed but local disposal is permanent',async t=>{
  const f=await extensionRuntimeFixture(t),target=await prepared(f);
  const request=f.bridge.request.bind(f.bridge);f.bridge.request=async(op,...rest)=>{if(op==='discardTarget')throw Error('synthetic disconnected');return request(op,...rest);};
  const result=await f.manager.discardOwnedTarget(target.id,{guard:()=>true});
  assert.equal(result.localDiscarded,true);assert.equal(result.remoteCachePurged,false);assert.ok(stored(f)[target.id]);
  assert.equal(f.manager.owned.has(target.id),false);assert.equal(f.manager.journal.targetMetadata[target.id],undefined);
  await f.manager.listExistingTargets();assert.equal(f.manager.owned.has(target.id),false);
});

test('reconnect reconciles stale extension storage from current server ownership without closing any tab',async t=>{
  const f=await extensionRuntimeFixture(t),old=await prepared(f,'ended-offline'),active=await prepared(f,'active-live');
  const oldSession=await f.manager.connectTarget(old);await oldSession.run('receiptArm',{key:{intent_id:'old-i',generation:'old-g'}});
  const request=f.bridge.request.bind(f.bridge);f.bridge.request=async(op,...rest)=>{if(op==='discardTarget')throw Error('synthetic disconnected');return request(op,...rest);};
  const result=await f.manager.discardOwnedTarget(old.id,{guard:()=>true});assert.equal(result.remoteCachePurged,false);assert.ok(stored(f)[old.id]);
  f.bridge.request=request;const prior=f.bridge.connection.id;f.bridge.connection.res.end();
  await wait(()=>f.bridge.connection?.ready&&f.bridge.connection.id!==prior,'reconciled reconnect');
  assert.equal(stored(f)[old.id],undefined);assert.ok(stored(f)[active.id]);assert.equal(f.manager.owned.has(old.id),false);
  assert.equal(f.tabs.has(tabNumber(old)),true);assert.equal(f.tabs.has(tabNumber(active)),true);
  assert.equal(f.calls.some(row=>row[0]==='removed'),false);assert.ok(f.calls.some(row=>row[0]==='selfrun.content.dispose'));
});

test('active registry preserves acquisition between createTarget return and lifecycle onTarget recording',async t=>{
  const f=await extensionRuntimeFixture(t),active=[];f.manager.setLifecycleGuards({activeTargetIds:()=>active});
  const target=await prepared(f);assert.equal(f.manager.pendingAcquisitions.has(target.id),true);
  const prior=f.bridge.connection.id;f.bridge.connection.res.end();
  await wait(()=>f.bridge.connection?.ready&&f.bridge.connection.id!==prior,'new acquisition reconnect');
  assert.ok(stored(f)[target.id]);assert.equal(f.calls.some(row=>row[0]==='selfrun.content.dispose'),false);
  active.push(target.id);assert.deepEqual(f.bridge.activeOwnedTargets(),[target.id]);assert.equal(f.manager.pendingAcquisitions.has(target.id),false);
  assert.equal(f.calls.some(row=>row[0]==='removed'),false);
});

import {controllerRuntime} from '../src/browser/extension-controller-source.mjs';
const inspectingRuntime=Function('return '+controllerRuntime.toString().replace('  void start().catch',`  globalThis.inspectRuntime=()=>({targets:[...targets.keys()],contexts:[...contexts.values()].map(c=>({targetId:c.targetId,operation:c.operation})),inflight:[...inflight.keys()],completed:[...completedTargets.values()],completedBodies:[...completed.values()],mutationLanes:[...mutationLanes.keys()],queued:commandQueue.map(e=>e.context.targetId)});\n  void start().catch`))();
test('exact controller caches lose all target command state and late completion cannot repersist it',async t=>{
  const f=await extensionRuntimeFixture(t,{runtime:inspectingRuntime}),target=await prepared(f);
  const session=await f.manager.connectTarget(target);await session.run('receiptArm',{key:{intent_id:'erase-i',generation:'erase-g'}});await f.bridge.request('listTargets');
  const gate=deferred();f.gates.snapshots.set(tabNumber(target),gate.promise);
  const before=f.calls.filter(row=>row[0]==='selfrun.content.snapshot').length;
  const work=session.run('snapshot').catch(e=>e);await wait(()=>f.calls.filter(row=>row[0]==='selfrun.content.snapshot').length>before);
  const queued=session.run('progress').catch(e=>e);
  await f.manager.discardOwnedTarget(target.id,{guard:()=>true});
  const clean=()=>{const state=f.runtimeContext.inspectRuntime();for(const name of ['targets','completed','mutationLanes','queued'])assert.equal(state[name].includes(target.id),false,name);assert.equal(state.contexts.some(c=>c.targetId===target.id),false);assert.equal(JSON.stringify(state.completedBodies).includes(target.id),false,'inventory completion cache');};
  clean();gate.resolve();await Promise.all([work,queued]);await new Promise(r=>setImmediate(r));clean();assert.equal(stored(f)[target.id],undefined);
});

test('one terminal close remains addressable after lifecycle removes the active target',async t=>{
 const f=await extensionRuntimeFixture(t),target=await prepared(f),active=[target.id];
 f.manager.setLifecycleGuards({activeTargetIds:()=>active});f.bridge.activeOwnedTargets();active.length=0;
 const result=await f.manager.closeOwnedTarget(target.id,{guard:()=>true});
 assert.equal(result.closed,true);assert.equal(f.tabs.has(tabNumber(target)),false);
 assert.equal(f.manager.pendingAcquisitions.has(target.id),false);assert.equal(f.manager.sessions.has(target.id),false);
 assert.equal(JSON.stringify(f.manager.journal).includes(target.id),false);
});

test('cancelled in-flight creation forgets its new target even if its one close fails',async t=>{
 const f=await extensionRuntimeFixture(t);await f.manager.ensureStarted();const gate=deferred(),abort=new AbortController();
 f.gates.create=gate.promise;f.gates.removeError=Error('synthetic close failure');
 const creating=f.manager.createTarget('https://chatgpt.com/c/cancelled',{signal:abort.signal}).catch(e=>e);
 await wait(()=>f.calls.some(row=>row[0]==='createRequested'));abort.abort();gate.resolve();assert.equal((await creating).code,'ABORT_ERR');
 await wait(()=>f.calls.some(row=>row[0]==='removed'));await new Promise(r=>setTimeout(r,30));
 assert.deepEqual(Object.keys(stored(f)),[]);assert.equal(f.manager.owned.size,0);assert.equal(f.calls.filter(row=>row[0]==='removed').length,1);
});


test('ordinary receipt retirement keeps nonterminal duplicate-submission protection until terminal disposal',async t=>{
 const f=await extensionRuntimeFixture(t),target=await prepared(f),session=await f.manager.connectTarget(target),key={intent_id:'i',generation:'g'};
 await session.run('receiptArm',{key});await session.run('submit');await session.run('receiptRetire',{key});
 await session.run('receiptArm',{key});await assert.rejects(session.run('submit'),e=>e.code==='SUBMISSION_UNCERTAIN'&&e.released===true);
 assert.equal(f.calls.filter(row=>row[0]==='serverOperation'&&row[1]==='submit').length,1);
 await f.manager.discardOwnedTarget(target.id,{guard:()=>true});assert.equal(stored(f)[target.id],undefined);
});

import {fixture as lifecycleFixture} from './helpers/lifecycle-fixture.mjs';
for(const state of ['STOPPED','DONE'])test(state+' flows through lifecycle into exact extension disposal after failed tab close',async t=>{
 const f=await extensionRuntimeFixture(t,{runtime:inspectingRuntime}),target=await prepared(f,'ended'),life=lifecycleFixture();
 const session=await f.manager.connectTarget(target);life.probe.url=target.url;life.browser.chromium=f.manager;
 // Keep this regression scoped to per-target disposal, never whole-browser idle retirement.
 life.browser.resume=async()=>({target,session,baseline:{...life.probe}});life.controller=life.recreate();life.controller.config.browserTargetIdleMs=3600000;
 await life.existing();await session.run('receiptArm',{key:{intent_id:'ended',generation:'g'}});await session.run('submit');
 f.tabs.set(555,{id:555,url:'https://example.test/manual'});f.gates.removeError=Error('synthetic tab close failure');
 life.control={...life.control,state,control_epoch:2};await life.controller.control(null,life.control,life.transport);await life.controller.cleanupTerminalResources();
 assert.equal(life.record(),null);assert.equal(life.controller.sessions.size,0);assert.equal(life.controller.repository.resourceCleanups.size,0);
 assert.equal(f.manager.isOwnedTarget(target.id),false);assert.equal(f.manager.sessions.has(target.id),false);assert.equal(f.manager.targetClosing(target.id),false);
 assert.equal(stored(f)[target.id],undefined);assert.equal(JSON.stringify(f.runtimeContext.inspectRuntime()).includes(target.id),false);
 assert.equal(JSON.stringify(f.manager.journal).includes(target.id),false);assert.equal(f.tabs.has(555),true);assert.equal(f.tabs.has(tabNumber(target)),true);
 const attempts=f.calls.filter(row=>row[0]==='removed').length;await life.controller.cleanupTerminalResources();assert.equal(f.calls.filter(row=>row[0]==='removed').length,attempts);
 life.controller=life.recreate();await life.controller.initialize();await life.controller.tick(life.transport);assert.equal(life.record(),null);assert.equal(life.sends,0);
});

test('one slow stale target disposal cannot prevent reconciliation of another stale target',async t=>{
 const f=await extensionRuntimeFixture(t),a=await prepared(f,'offline-a'),b=await prepared(f,'offline-b');
 const sa=await f.manager.connectTarget(a),sb=await f.manager.connectTarget(b);await sa.run('receiptArm',{key:{intent_id:'a',generation:'g'}});await sb.run('receiptArm',{key:{intent_id:'b',generation:'g'}});
 await f.manager.discardOwnedTarget(a.id,{guard:()=>true,remote:false});await f.manager.discardOwnedTarget(b.id,{guard:()=>true,remote:false});
 const gate=deferred();t.after(()=>gate.resolve());f.gates.disposals.set(tabNumber(a),gate.promise);
 f.bridge.connection.res.end();await wait(()=>f.calls.some(row=>row[0]==='selfrun.content.dispose'&&row[1]===tabNumber(a)));
 await wait(()=>f.calls.some(row=>row[0]==='selfrun.content.dispose'&&row[1]===tabNumber(b)),'second target disposal while first is blocked');
 assert.deepEqual(Object.keys(stored(f)),[]);gate.resolve();await wait(()=>f.bridge.connection?.ready);
 assert.equal(f.calls.some(row=>row[0]==='removed'),false);
});
