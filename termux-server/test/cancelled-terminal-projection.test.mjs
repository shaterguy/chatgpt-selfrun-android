import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';
test('cancelled unsubmitted request publishes terminal proof after cleanup without reopening browser',async t=>{
 const f=fixture();t.after(()=>f.controller.quiesceForStandby());
 f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:async()=>({closed:true}),releaseIfUnused:async()=>({released:false})};
 f.controller=f.recreate();
 f.body.client_status='CREATE_REQUESTED';await f.ingest();
 const prepared=f.rows.get(f.path);f.body={...prepared,client_status:'CANCELLED',cancelled_at_ms:Date.now()};
 await f.ingest();await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
 const row=f.rows.get(f.path);assert.equal(row.server_status,'CANCELLED');
 assert.equal(row.server_cancel_proof.never_submitted,true);assert.equal(row.server_cancel_proof.cleanup_confirmed,true);
 assert.equal(row.server_cancel_proof.dispatch_attempt,1);assert.equal(f.sends,0);assert.equal(f.record().retired,true);
});
function cancellationFixture(t,close=async()=>({closed:true})) {
 const f=fixture();f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:close,releaseIfUnused:async()=>({released:false})};f.controller=f.recreate();t.after(()=>f.controller.quiesceForStandby());return f;
}
async function cancelPrepared(f) {
 f.body.client_status='CREATE_REQUESTED';await f.ingest();f.body={...f.rows.get(f.path),client_status:'CANCELLED',cancelled_at_ms:Date.now()};await f.ingest();
}
test('pending cleanup cannot publish an automatic retry proof',async t=>{
 const f=cancellationFixture(t,async()=>({closed:false,reason:'guard_rejected'}));await cancelPrepared(f);await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
 assert.equal(f.rows.get(f.path).server_cancel_proof,undefined);assert.equal(Object.keys(f.store.snapshot().lifecycle.completionOutbox).length,1);assert.equal(f.sends,0);
});
test('cancel projection write failure is durable across restart and retries without browser work',async t=>{
 const f=cancellationFixture(t);await cancelPrepared(f);await f.controller.cleanupTerminalResources();f.writeFail=true;await f.controller.flush(f.transport);
 assert.equal(Object.keys(f.store.snapshot().lifecycle.completionOutbox).length,1);await f.controller.quiesceForStandby();f.controller=f.recreate();f.writeFail=false;await f.controller.repository.initialize('');await f.controller.flush(f.transport);
 assert.equal(f.rows.get(f.path).server_cancel_proof.never_submitted,true);assert.equal(f.sends,0);assert.equal(f.prepares,1);assert.equal(Object.keys(f.store.snapshot().lifecycle.completionOutbox).length,0);
});
test('terminal telemetry cannot overwrite a newer dispatch attempt on the same path',async t=>{
 const f=cancellationFixture(t);await cancelPrepared(f);await f.controller.cleanupTerminalResources();const newer={...f.rows.get(f.path),client_status:'CREATE_REQUESTED',dispatch_attempt:2};f.rows.set(f.path,newer);await f.controller.flush(f.transport);
 assert.deepEqual(f.rows.get(f.path),newer);assert.equal(f.sends,0);assert.equal(Object.keys(f.store.snapshot().lifecycle.completionOutbox).length,0);
});
test('higher preparation attempt can execute only after positive retired no-send proof',async t=>{
 const f=cancellationFixture(t);await cancelPrepared(f);await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
 f.body={...f.body,client_status:'CREATE_REQUESTED',dispatch_attempt:2};await f.ingest();assert.equal(f.record().retired,undefined);assert.equal(f.record().dispatch_attempt,2);assert.equal(f.record().state,'PREPARED');assert.equal(f.sends,0);
});

test('legacy cancelled marker migrates terminal proof on cold initialize without browser work',async t=>{
 const f=cancellationFixture(t);await cancelPrepared(f);await f.controller.cleanupTerminalResources();await f.controller.quiesceForStandby();
 const snapshot=f.store.snapshot();snapshot.lifecycle.completionOutbox={};
 for(const marker of Object.values(snapshot.lifecycle.retired)){delete marker.cancel_projection_schema;delete marker.browser_open_pending;}
 await f.store.patch({lifecycle:snapshot.lifecycle});f.controller=f.recreate();await f.controller.initialize();
 await f.controller.ingest(f.path,f.body,f.transport);await f.controller.flush(f.transport);
 const row=f.rows.get(f.path);assert.equal(row.server_status,'CANCELLED');assert.equal(row.server_cancel_proof.never_submitted,true);assert.equal(row.server_cancel_proof.cleanup_confirmed,true);
 assert.equal(f.prepares,1);assert.equal(f.sends,0);
});
test('cancel proof and retry wait until a pending target acquisition settles and closes',async t=>{
 const f=cancellationFixture(t);const arrival=deferred(),started=deferred();const closed=[];
 f.browser.chromium.closeOwnedTarget=async id=>{closed.push(id);return {closed:true};};
 f.browser.prepare=async args=>{f.prepares++;started.resolve();await arrival.promise;await args.onTarget({id:'target-late'});return {target:{id:'target-late'},session:{close(){}},baseline:{...f.probe}};};
 f.body.client_status='CREATE_REQUESTED';f.rows.set(f.path,structuredClone(f.body));
 await f.controller.ingestDurable(f.path,f.body,f.transport);await started.promise;
 f.body={...f.rows.get(f.path),client_status:'CANCELLED',cancelled_at_ms:Date.now()};await f.controller.ingest(f.path,f.body,f.transport);await f.controller.flush(f.transport);
 const earlyProof=f.rows.get(f.path).server_cancel_proof?.cleanup_confirmed;
 arrival.resolve();await f.settle();await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
 assert.notEqual(earlyProof,true);assert.ok(closed.includes('target-late'));assert.equal(f.rows.get(f.path).server_cancel_proof.cleanup_confirmed,true);assert.equal(f.sends,0);
});

test('higher attempt cannot remove a retired marker while old target acquisition is pending',async t=>{
 const f=cancellationFixture(t);const arrival=deferred(),started=deferred();const closed=[];
 f.browser.chromium.closeOwnedTarget=async id=>{closed.push(id);return {closed:true};};
 f.browser.prepare=async args=>{f.prepares++;started.resolve();await arrival.promise;await args.onTarget({id:'target-old-pending'});return {target:{id:'target-old-pending'},session:{close(){}},baseline:{...f.probe}};};
 f.body.client_status='CREATE_REQUESTED';f.rows.set(f.path,structuredClone(f.body));await f.controller.ingestDurable(f.path,f.body,f.transport);await started.promise;
 f.body={...f.rows.get(f.path),client_status:'CANCELLED',cancelled_at_ms:Date.now()};await f.controller.ingest(f.path,f.body,f.transport);
 const next={...f.body,client_status:'CREATE_REQUESTED',dispatch_attempt:2};await f.controller.repository.ingest(f.path,next);const accepted=f.record().dispatch_attempt;
 if(accepted===2)f.rows.set(f.path,next);
 arrival.resolve();await f.settle();await f.controller.cleanupTerminalResources();
 assert.equal(accepted,1);assert.ok(closed.includes('target-old-pending'));assert.equal(f.sends,0);
});

test('same-request RUNNING epoch refresh does not permanently fence proven unsent cancellation cleanup',async t=>{
 let canClose=false,closed=0;const f=cancellationFixture(t,async()=>{if(!canClose)return {closed:false,reason:'temporary_unavailable'};closed++;return {closed:true};});
 await cancelPrepared(f);await f.controller.cleanupTerminalResources();assert.equal(closed,0);
 f.control={...f.control,control_epoch:2};await f.controller.control(null,f.control,f.transport);canClose=true;
 await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
 assert.equal(closed,1);assert.equal(f.rows.get(f.path).server_cancel_proof.cleanup_confirmed,true);assert.equal(f.sends,0);
});
test('other-request RUNNING epoch still fences old cancellation cleanup',async t=>{
 let canClose=false,closed=0;const f=cancellationFixture(t,async()=>{if(!canClose)return {closed:false};closed++;return {closed:true};});
 await cancelPrepared(f);await f.controller.cleanupTerminalResources();f.control={...f.control,request_id:'other-request',control_epoch:2};
 await f.controller.control(null,f.control,f.transport);canClose=true;await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
 assert.equal(closed,0);assert.equal(f.rows.get(f.path).server_cancel_proof,undefined);assert.equal(f.sends,0);
});

test('persisted pending browser acquisition cannot become cleanup proof after restart',async t=>{
 const f=cancellationFixture(t);await cancelPrepared(f);await f.controller.cleanupTerminalResources();
 const snapshot=f.store.snapshot(),marker=Object.values(snapshot.lifecycle.retired)[0];marker.browser_open_pending={run:1,browser:1};
 await f.store.patch({lifecycle:snapshot.lifecycle});f.controller=f.recreate();await f.controller.initialize();await f.controller.flush(f.transport);
 assert.equal(f.rows.get(f.path).server_cancel_proof,undefined);assert.equal(f.record().browser_open_pending.browser,1);
});
test('completed cancellation projection is not requeued on a second restart',async t=>{
 const f=cancellationFixture(t);await cancelPrepared(f);await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
 await f.controller.quiesceForStandby();f.controller=f.recreate();await f.controller.initialize();let io=0;
 const transport={read:async()=>{io++;throw Error('unexpected historical read');}};await f.controller.flush(transport);
 assert.equal(io,0);assert.equal(Object.keys(f.controller.repository.data.completionOutbox).length,0);
});
for(const patch of [{conversation_url:'https://chatgpt.com/c/manual'},{state:'PAUSED'}])
test('cancellation cleanup refresh exception preserves URL and paused control fences '+JSON.stringify(patch),async t=>{
 let ready=false,closed=0;const f=cancellationFixture(t,async()=>{if(!ready)return {closed:false};closed++;return {closed:true};});
 await cancelPrepared(f);await f.controller.cleanupTerminalResources();f.control={...f.control,control_epoch:2,...patch};await f.controller.control(null,f.control,f.transport);ready=true;
 await f.controller.cleanupTerminalResources();assert.equal(closed,0);assert.equal(f.sends,0);
});
