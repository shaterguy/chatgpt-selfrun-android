import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fixture as lifecycleFixture} from './helpers/lifecycle-fixture.mjs';
import {extensionRuntimeFixture,deferred,wait} from './helpers/extension-runtime-fixture.mjs';
import {keyFor} from '../src/lifecycle.mjs';

const tabNumber=target=>Number(target.id.split(':').at(-1));
const successFor=(f,id)=>f.requests.filter(r=>r.path==='/result'&&r.body.id===id&&r.body.ok);
test('asynchronous content hello releases concurrent progress and receipt readiness for the same target',async t=>{
 const f=await extensionRuntimeFixture(t),target=await f.target();f.gates.hello=true;
 const progress=f.bridge.request('progress',{targetId:target.id}),receipt=f.bridge.request('receiptSnapshot',{targetId:target.id});
 await wait(()=>f.calls.filter(c=>c[0]==='selfrun.content.helloRequest').length===2,'both readiness requests');f.hello(tabNumber(target));
 await wait(()=>f.bridge.pendingCount===0,'both reads finishing');assert.equal((await progress).composer,true);assert.equal(await receipt,true);
});
test('cancelling one readiness waiter preserves another and rejects foreign target hello',async t=>{
 const f=await extensionRuntimeFixture(t),a=await f.target('a'),b=await f.target('b');f.gates.hello=true;const abort=new AbortController();
 const old=f.bridge.request('progress',{targetId:a.id},{signal:abort.signal}),oldRejected=assert.rejects(old,e=>e.name==='AbortError');
 const current=f.bridge.request('receiptSnapshot',{targetId:a.id});await wait(()=>f.calls.filter(c=>c[0]==='selfrun.content.helloRequest').length===2);
 abort.abort(new Error('app STOP'));await oldRejected;f.hello(tabNumber(b));await new Promise(r=>setTimeout(r,15));assert.equal(f.bridge.pendingCount,1);
 f.hello(tabNumber(a));assert.equal(await current,true);assert.equal(f.calls.filter(c=>c[0]==='selfrun.content.snapshot').length,0);
});
test('owned target removal settles every pending readiness waiter without touching another target',async t=>{
 const f=await extensionRuntimeFixture(t),a=await f.target('a'),b=await f.target('b');f.gates.hello=true;
 const p=f.bridge.request('progress',{targetId:a.id}),q=f.bridge.request('receiptSnapshot',{targetId:a.id});
 const rejected=[assert.rejects(p),assert.rejects(q)];await wait(()=>f.calls.filter(c=>c[0]==='selfrun.content.helloRequest').length===2);
 assert.equal((await f.manager.closeOwnedTarget(a.id,{guard:()=>true})).closed,true);
 await wait(()=>f.bridge.pendingCount===0,'all removed-target waiters');await Promise.all(rejected);assert.ok(f.tabs.has(tabNumber(b)));
});
test('controller clamps page observation and mutation deadlines to its own sixty-second limit',async t=>{
 const f=await extensionRuntimeFixture(t),target=await f.target(),before=Date.now();
 await f.bridge.request('progress',{targetId:target.id},{timeoutMs:75000});await f.bridge.request('receiptArm',{targetId:target.id,key:{intent_id:'i',generation:'g'}},{timeoutMs:75000});
 const observation=f.calls.find(c=>c[0]==='selfrun.content.snapshot')[2].deadlineAt;
 const mutation=f.calls.find(c=>c[0]==='script'&&c[1]==='receiptArm')[3][3];
 assert.ok(observation<=before+61000&&observation>before+59000);assert.ok(mutation<=before+61000&&mutation>before+59000);
});
test('queued cancellation is acknowledged while an unrelated active mutation remains pending',async t=>{
 const f=await extensionRuntimeFixture(t),a=await f.target('a'),b=await f.target('b'),gate=deferred();f.gates.scripts.set(tabNumber(b),gate.promise);
 await f.bridge.request('receiptArm',{targetId:b.id,key:{intent_id:'b',generation:'g'}});
 const running=f.bridge.request('submit',{targetId:b.id});await wait(()=>f.calls.some(c=>c[0]==='script'&&c[1]==='submit'));
 const abort=new AbortController(),queued=f.bridge.request('input',{targetId:a.id,prompt:'cancelled'},{signal:abort.signal});const id=[...f.bridge.pending.keys()].at(-1),rejected=assert.rejects(queued,e=>e.name==='AbortError');
 abort.abort(new Error('app STOP'));await rejected;await wait(()=>f.requests.some(r=>r.path==='/result'&&r.body.id===id),'queued cancellation acknowledgement before B completes');
 assert.equal(f.bridge.pendingCount,1);assert.ok(!f.calls.some(c=>c[0]==='script'&&c[1]==='input'));assert.equal((await f.bridge.request('progress',{targetId:b.id})).composer,true);
 gate.resolve();assert.equal((await running).status,'SUBMITTED');
});
test('STOP reaches real session cancellation and owned close despite an unrelated blocked manager create',async t=>{
 const f=await extensionRuntimeFixture(t),a=await f.target('a'),b=await f.target('b'),life=lifecycleFixture();
 const sessionA=await f.manager.connectTarget(a),sessionB=await f.manager.connectTarget(b);life.probe.url=a.url;
 life.browser.chromium=f.manager;life.browser.resume=async({conversationUrl})=>({target:conversationUrl===a.url?a:b,session:conversationUrl===a.url?sessionA:sessionB,baseline:{...life.probe,url:conversationUrl}});life.controller=life.recreate();
 await life.existing();const attachment=life.controller.sessions.get(life.record().key),executionGate=deferred();
 const otherBody={...life.body,task_id:'ACTIVE-B',turn_id:'ACTIVE-B:turn:1',request_id:'ACTIVE-B:request:1',conversation_url:b.url,result_document_id:'RESULT-B'},otherPath='__SELFRUN_DISPATCH__ACTIVE-B:request:1__A1.json';
 const otherControl={...life.control,task_id:otherBody.task_id,turn_id:otherBody.turn_id,request_id:otherBody.request_id};
 life.rows.set(otherPath,otherBody);life.docs.set('RESULT-B',JSON.stringify({schema:'selfrun-turn-result-v3',task_id:otherBody.task_id,turn_id:otherBody.turn_id,turn:1,document_id:'RESULT-B',event_id:'ACTIVE-B:turn:1:result',committed:false}));
 const read=life.transport.read;life.transport.read=async name=>name==='__SELFRUN_CONTROL__ACTIVE-B.json'?structuredClone(otherControl):read(name);
 await life.controller.ingest(otherPath,otherBody,life.transport);await life.settle();const otherKey=keyFor(otherBody),otherAttachment=life.controller.sessions.get(otherKey);
 assert.equal(otherAttachment.session,sessionB);assert.equal(life.controller.activeDispatches().length,2);
 await sessionA.run('receiptArm',{key:{intent_id:'a',generation:'g'}});f.gates.scripts.set(tabNumber(a),executionGate.promise);
 const running=sessionA.run('submit',{}, {signal:attachment.abort.signal}),runningId=[...f.bridge.pending.keys()].at(-1);const runningRejected=assert.rejects(running,e=>e.name==='AbortError');
 await wait(()=>f.calls.some(c=>c[0]==='script'&&c[1]==='submit'));
 const createGate=deferred();f.gates.create=createGate.promise;const unrelatedCreate=f.manager.createTarget('https://chatgpt.com/c/b-next');
 const queued=sessionA.run('input',{prompt:'never execute'}),queuedId=[...f.bridge.pending.keys()].at(-1);const queuedRejected=assert.rejects(queued,e=>e.name==='AbortError');
 const stopping=life.stop();await Promise.all([runningRejected,queuedRejected]);await stopping;
 assert.equal(f.bridge.pending.has(runningId),false);assert.equal(f.bridge.pending.has(queuedId),false);
 await wait(()=>f.calls.filter(c=>c[0]==='createRequested').length===3,'unrelated create remains active');
 await wait(()=>!f.tabs.has(tabNumber(a)),'STOP owned tab cleanup outside manager queue');
 assert.ok(f.tabs.has(tabNumber(b)));assert.equal((await sessionB.run('progress')).composer,true);
 assert.equal(life.controller.sessions.size,1);assert.equal(life.controller.sessions.get(otherKey),otherAttachment);assert.equal(otherAttachment.abort.signal.aborted,false);
 assert.equal(life.controller.repository.get(otherKey).control_state,'RUNNING');assert.equal(life.controller.activeDispatches()[0].body.task_id,'ACTIVE-B');
 await wait(()=>f.requests.some(r=>r.path==='/result'&&r.body.id===queuedId),'STOP queued command retired');
 assert.ok(!f.calls.some(c=>c[0]==='script'&&c[1]==='input'&&c[2]===tabNumber(a)));
 executionGate.resolve();await new Promise(r=>setTimeout(r,15));assert.equal(successFor(f,runningId).length,0);assert.equal(successFor(f,queuedId).length,0);
 f.gates.create=null;createGate.resolve();assert.ok((await unrelatedCreate).id);assert.ok(f.manager.isOwnedTarget(b.id));assert.equal(f.launches,1);
 await life.controller.cleanupTerminalResources();assert.equal(life.record(),null);otherAttachment.abort.abort();sessionB.close();
});
test('authenticated current controller reports its actual manifest version while legacy absence stays unknown',async t=>{
 for(const version of ['1.0.2',undefined]){const f=await extensionRuntimeFixture(t,{manifestVersion:version===undefined?null:version});await f.target();assert.equal(f.bridge.status().extensionVersion,version??null);}
});
test('current authenticated manifest version keeps the durable storage namespace and rejects stale identity updates',async t=>{
 const f=await extensionRuntimeFixture(t);await f.target();assert.deepEqual(Object.keys(f.storage),['selfrun-extension-controller-v1:'+f.bridge.browserSessionId]);
 const hello=f.requests.find(r=>r.path==='/event'&&r.body.event==='hello').body;
 for(const override of [{connectionId:'old-connection'},{browserSessionId:'other-browser'},{extensionId:'foreign-extension'}]){
  const r=await fetch(f.bridge.url+'/event',{method:'POST',headers:{Origin:'chrome-extension://test',Authorization:'Bearer fixture-only-capability-123456','X-SelfRun-Owner':'fixture-owner','Content-Type':'application/json'},body:JSON.stringify({...hello,extensionVersion:'9.9.9',...override})});
  assert.ok([403,409].includes(r.status));assert.equal(f.bridge.status().extensionVersion,'1.0.2');
 }
});
test('target close deduplicates by target while a new owned target and shared journal remain intact',async t=>{
 const f=await extensionRuntimeFixture(t),a=await f.target('a'),b=await f.target('b'),gate=deferred(),inventory=f.manager.listExistingTargets.bind(f.manager);let inventories=0;
 f.manager.listExistingTargets=async(...args)=>{inventories++;await gate.promise;return inventory(...args);};
 const first=f.manager.closeOwnedTarget(a.id,{guard:()=>true}),second=f.manager.closeOwnedTarget(a.id,{guard:()=>true});await wait(()=>inventories===1);
 const c=await f.target('c');gate.resolve();assert.equal((await first).closed,true);assert.equal((await second).reason,'unowned_target');assert.equal(inventories,1);
 const saved=JSON.parse(await fs.readFile(path.join(f.config.dataDir,'extension-ownership.json'),'utf8'));
 assert.deepEqual(new Set(saved.targets),new Set([b.id,c.id]));assert.ok(saved.targetMetadata[b.id]);assert.ok(saved.targetMetadata[c.id]);assert.equal(saved.targetMetadata[a.id],undefined);
 assert.equal(f.calls.filter(row=>row[0]==='removed'&&row[1]===tabNumber(a)).length,1);
});
for(const replacement of ['metadata','journal'])test('close never adopts replacement '+replacement+' after its owned inventory wait',async t=>{
 const f=await extensionRuntimeFixture(t),target=await f.target(),inventory=f.manager.listExistingTargets.bind(f.manager);
 f.manager.listExistingTargets=async(...args)=>{const rows=await inventory(...args);if(replacement==='metadata')f.manager.journal.targetMetadata[target.id]={...f.manager.journal.targetMetadata[target.id]};else f.manager.journal={...f.manager.journal};return rows;};
 const first=f.manager.closeOwnedTarget(target.id,{guard:()=>true}),queued=f.manager.closeOwnedTarget(target.id,{guard:()=>true});
 assert.equal((await first).reason,'guard_rejected');assert.equal((await queued).reason,'guard_rejected');assert.ok(f.tabs.has(tabNumber(target)));assert.equal(f.manager.targetClosing(target.id),false);
});
test('a late inventory cannot restore a target removed by concurrent owned cleanup',async t=>{
 const f=await extensionRuntimeFixture(t),a=await f.target('a'),b=await f.target('b'),held=deferred(),release=deferred(),request=f.bridge.request.bind(f.bridge);let delay=true;
 f.bridge.request=async(...args)=>{const result=await request(...args);if(args[0]==='listTargets'&&delay){delay=false;held.resolve();await release.promise;}return result;};
 const inventory=f.manager.listExistingTargets();await held.promise;assert.equal((await f.manager.closeOwnedTarget(a.id,{guard:()=>true})).closed,true);
 release.resolve();assert.equal(await inventory,null);assert.equal(f.manager.isOwnedTarget(a.id),false);assert.equal(f.manager.isOwnedTarget(b.id),true);
 const saved=JSON.parse(await fs.readFile(path.join(f.config.dataDir,'extension-ownership.json'),'utf8'));assert.deepEqual(saved.targets,[b.id]);
});
test('a late close acknowledgement cannot clear replacement ownership for the same target id',async t=>{
 const f=await extensionRuntimeFixture(t),target=await f.target(),held=deferred(),release=deferred(),request=f.bridge.request.bind(f.bridge);
 f.bridge.request=async(...args)=>{const result=await request(...args);if(args[0]==='closeTarget'){held.resolve();await release.promise;}return result;};
 const closing=f.manager.closeOwnedTarget(target.id,{guard:()=>true});await held.promise;await wait(()=>!f.manager.isOwnedTarget(target.id));
 const replacement={replacement:true};f.manager.journal.targetMetadata[target.id]=replacement;f.manager.owned.add(target.id);f.manager.closing.delete(target.id);
 release.resolve();assert.equal((await closing).reason,'guard_rejected');assert.equal(f.manager.isOwnedTarget(target.id),true);assert.equal(f.manager.journal.targetMetadata[target.id],replacement);
});
test('owned removal during close inventory completes absence without restoring the target',async t=>{
 const f=await extensionRuntimeFixture(t),a=await f.target('a'),b=await f.target('b'),held=deferred(),release=deferred(),request=f.bridge.request.bind(f.bridge);let delay=true;
 f.bridge.request=async(...args)=>{const result=await request(...args);if(args[0]==='listTargets'&&delay){delay=false;held.resolve();await release.promise;}return result;};
 const closing=f.manager.closeOwnedTarget(a.id,{guard:()=>true});await held.promise;
 assert.equal((await request('closeTarget',{targetId:a.id,expectedUrl:a.url})).closed,true);await wait(()=>!f.manager.isOwnedTarget(a.id));
 release.resolve();assert.equal((await closing).absent,true);assert.equal(f.manager.isOwnedTarget(a.id),false);assert.equal(f.manager.isOwnedTarget(b.id),true);
});
test('whole-controller shutdown remains behind a pending acquisition and preserves its new target',async t=>{
 const f=await extensionRuntimeFixture(t),first=await f.target();await f.manager.closeOwnedTarget(first.id,{guard:()=>true});
 const gate=deferred();f.gates.create=gate.promise;const acquire=f.manager.createTarget('https://chatgpt.com/c/new');await wait(()=>f.calls.filter(c=>c[0]==='createRequested').length===2);
 const stopping=f.bridge.request('controllerStop'),id=[...f.bridge.pending.keys()].at(-1);await new Promise(r=>setTimeout(r,15));
 assert.ok(!f.requests.some(r=>r.path==='/result'&&r.body.id===id));gate.resolve();const target=await acquire;
 assert.equal((await stopping).stopped,false);assert.ok(f.tabs.has(tabNumber(target)));assert.ok(f.tabs.has(99));
});
