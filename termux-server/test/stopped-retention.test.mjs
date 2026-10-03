import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred, MemoryStore } from './helpers/lifecycle-fixture.mjs';
import { keyFor } from '../src/lifecycle.mjs';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';

const idle=()=>new Promise(resolve=>setImmediate(resolve));
const active=f=>Object.keys(f.store.snapshot().lifecycle.requests);
function countIo(f) {
 const calls={reads:0,writes:0}; const read=f.transport.read,write=f.transport.write;
 f.transport.read=async(...args)=>{calls.reads++;return read(...args);};
 f.transport.write=async(...args)=>{calls.writes++;return write(...args);};return calls;
}
test('retired STOP performs one owned stop then leaves no execution or publication work',async()=>{
 const f=fixture();await f.existing();const observer=f.observations.at(-1);
 await f.stop();const io=countIo(f);
 for(let i=0;i<3;i++)await f.controller.tick(f.transport);
 assert.deepEqual(active(f),[]);assert.deepEqual(f.store.snapshot().lifecycle.outbox,{});
 assert.equal(f.quiesces,1);assert.equal(f.resumes,1);assert.deepEqual(io,{reads:0,writes:0});
 assert.equal(observer.signal.aborted,true);assert.equal(f.controller.sessions.size,0);
 const snapshot=f.store.snapshot();await observer.onActivity({...f.probe,responseTextLength:99999});
 assert.deepEqual(f.store.snapshot(),snapshot);
 const marker=f.store.snapshot().lifecycle.retired[keyFor(f.body)];
 assert.equal(marker.state,'STOPPED');assert.equal(marker.stop_status,'CONFIRMED');
 for(const field of ['body','prompt','conversation_url','intent','cursor','target_id'])assert.equal(marker[field],undefined);
});
test('failed STOP is inert across ticks and process restart without reopening a conversation',async()=>{
 const f=fixture();await f.existing();let attempts=0;f.browser.quiesce=async()=>{attempts++;throw Error('closed target');};
 await f.stop();f.controller=f.recreate();const io=countIo(f);
 await f.controller.tick(f.transport);await f.controller.tick(f.transport);
 assert.equal(attempts,1);assert.equal(f.resumes,1);assert.deepEqual(active(f),[]);
 assert.deepEqual(io,{reads:0,writes:0});assert.equal(f.record().stop_status,'UNCONFIRMED');
});
test('cold historical STOP import has no readback, browser, outbox or active request',async()=>{
 const f=fixture();await f.existing();await f.stop();
 const body=structuredClone(f.rows.get(f.path));body.lifecycle_state='STOPPED';body.server_control_state='STOPPED';
 body.server_control_epoch=2;body.lifecycle_record={...body.lifecycle_record,state:'STOPPED',control_state:'STOPPED',control_epoch:2};
 f.store=new MemoryStore();f.controller=f.recreate();const io=countIo(f);
 await f.controller.ingest(f.path,body,f.transport);await f.controller.tick(f.transport);
 assert.deepEqual(active(f),[]);assert.deepEqual(f.store.snapshot().lifecycle.outbox,{});
 assert.equal(f.resumes,1);assert.deepEqual(io,{reads:0,writes:0});
});
test('CANCELLED and DONE retire without browser reopen or repeated publication',async()=>{
 for(const state of ['CANCELLED','DONE']){
  const f=fixture();await f.existing();
  if(state==='CANCELLED'){f.body.client_status='CANCELLED';await f.ingest();}
  else {f.control={...f.control,state:'DONE',control_epoch:2};await f.controller.control(null,f.control,f.transport);}
  const io=countIo(f);await f.controller.tick(f.transport);
  assert.deepEqual(active(f),[]);assert.deepEqual(f.store.snapshot().lifecycle.outbox,{});
  assert.deepEqual(io,{reads:0,writes:0});assert.equal(f.resumes,1);assert.equal(f.controller.sessions.size,0);
 }
});
test('identical app input and server-only projection echoes enqueue no writes',async()=>{
 const f=fixture();f.body.client_status='CREATE_REQUESTED';await f.ingest();
 const io=countIo(f),before=f.record();
 for(let i=0;i<3;i++){
  const body={...f.rows.get(f.path),updated_at_ms:Date.now()+i,server_error:'publication only '+i};
  await f.controller.repository.ingest(f.path,body);await f.controller.flush(f.transport);
 }
 assert.equal(io.writes,0);assert.equal(f.record().revision,before.revision);
 assert.deepEqual(f.store.snapshot().lifecycle.outbox,{});
});
test('higher epoch same-request app URL resumes a retired observer without replaying input',async()=>{
 const f=fixture();await f.existing();await f.stop();const io=countIo(f);
 f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
 await f.controller.control(null,f.control,f.transport);await f.controller.tick(f.transport);
 assert.equal(f.resumes,2);assert.equal(f.sends,0);assert.equal(f.prepares,0);
 assert.equal(f.record().conversation_url,f.control.conversation_url);assert.equal(f.record().state,'OBSERVING');
 assert.equal(f.record().body.result_document_id,'RESULT-1');assert.ok(io.reads>0);
 await f.controller.quiesceForStandby();
});
test('retired work does not resume from unchanged epoch or missing/invalid app URL',async()=>{
 for(const url of ['',undefined,'https://example.com/c/wrong']){
  const f=fixture();await f.existing();await f.stop();
  f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:url};
  await f.controller.control(null,f.control,f.transport);await f.controller.tick(f.transport);
  assert.equal(f.resumes,1);assert.deepEqual(active(f),[]);assert.equal(f.sends,0);
 }
});
test('retired dispatch projection cannot reopen after fresh store cleanup and restart',async()=>{
 const f=fixture();await f.existing();const oldBody=structuredClone(f.rows.get(f.path));await f.stop();
 f.controller=f.recreate();const io=countIo(f);
 await f.controller.ingest(f.path,oldBody,f.transport);await f.controller.tick(f.transport);
 assert.deepEqual(active(f),[]);assert.equal(f.resumes,1);assert.deepEqual(io,{reads:0,writes:0});
});
test('publication waiting on a remote read is fenced when STOP retires its request',async()=>{
 const f=fixture();await f.existing();
 await f.controller.repository.move(keyFor(f.body),'OBSERVING','pending publication');
 const gate=deferred(),entered=deferred(),read=f.transport.read;let writes=0;
 f.transport.read=async name=>{const result=await read(name);if(name===f.path){entered.resolve();await gate.promise;}return result;};
 f.transport.write=async()=>{writes++;};
 const flushing=f.controller.flush(f.transport);await entered.promise;
 await f.controller.controlDurable(null,{...f.control,state:'STOPPED',control_epoch:2});
 gate.resolve();await flushing;
 assert.equal(writes,0);assert.deepEqual(active(f),[]);
});
test('PAUSED retains resumable execution identity and is not retired',async()=>{
 const f=fixture();await f.existing();f.control={...f.control,state:'PAUSED',control_epoch:2};
 await f.controller.control(null,f.control,f.transport);
 assert.equal(active(f).length,1);assert.equal(f.record().state,'PAUSED');assert.equal(f.quiesces,0);
});
test('cold watcher skips retired exact paths without reading their dispatch bodies',async()=>{
 const f=fixture();await f.existing();await f.stop();f.controller=f.recreate();await f.controller.initialize();
 let bodies=0,writes=0;const read=f.transport.read;
 f.transport.read=async name=>{if(name===f.path)bodies++;return read(name);};f.transport.write=async()=>{writes++;};
 const w=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
 await w.scanOnce();await idle();await w.scanOnce();await idle();
 assert.equal(bodies,0);assert.equal(writes,0);assert.equal(f.resumes,1);
});
test('resume rejects a valid app URL for a different conversation',async()=>{
 const f=fixture();await f.existing();await f.stop();
 f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:'https://chatgpt.com/c/different-owned'};
 await f.controller.control(null,f.control,f.transport);
 assert.equal(f.record().resume_error,'RESUME_CONVERSATION_MISMATCH');
 assert.deepEqual(active(f),[]);assert.equal(f.resumes,1);
});
test('new explicit cancelled-attempt dispatch can prepare without old intent replay',async()=>{
 const f=fixture();f.body.client_status='CREATE_REQUESTED';await f.ingest();
 f.body.client_status='CANCELLED';await f.ingest();
 f.body={...f.body,client_status:'CREATE_REQUESTED',dispatch_attempt:2};await f.ingest();
 assert.equal(f.prepares,2);assert.equal(f.sends,0);assert.equal(f.record().state,'PREPARED');
});
test('higher dispatch attempt alone cannot replay a retired accepted or uncertain input',async()=>{
 for(const mode of ['accepted','unknown']){
  const f=fixture();if(mode==='unknown')f.mode=mode;await f.ingest();
  f.body.client_status='CANCELLED';await f.ingest();
  f.body={...f.body,dispatch_attempt:2,client_status:'SEND_REQUESTED'};await f.ingest();
  assert.equal(f.sends,1);assert.equal(f.prepares,1);assert.deepEqual(active(f),[]);
 }
});
test('watcher explicit resume ignores the old cancelled dispatch while preserving app URL',async()=>{
 const f=fixture();await f.existing();f.body.client_status='CANCELLED';await f.ingest();await f.stop();
 f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
 const w=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
 await w.scanOnce();await idle();await idle();await f.controller.tick(f.transport);
 assert.equal(f.resumes,2);assert.equal(f.sends,0);assert.equal(f.record().state,'OBSERVING');
 await f.controller.quiesceForStandby();
});
test('explicit resume retries the same accepted epoch after metadata read failure or restart',async()=>{
 const f=fixture();await f.existing();await f.stop();const read=f.transport.read;let failed=false;
 f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
 f.transport.read=async name=>{if(name===f.path&&!failed){failed=true;throw Error('temporary metadata unavailable');}return read(name);};
 await assert.rejects(f.controller.control(null,f.control,f.transport),/metadata unavailable/);
 f.controller=f.recreate();await f.controller.control(null,f.control,f.transport);await f.controller.tick(f.transport);
 assert.equal(f.resumes,2);assert.equal(f.sends,0);assert.equal(f.record().state,'OBSERVING');
 await f.controller.quiesceForStandby();
});
test('HTTP cannot claim a retired Drive request by sending a higher epoch resume',async()=>{
 const {SelfRunController}=await import('../src/controller.mjs');
 const {SignalDispatchTransport}=await import('../src/signal-transport.mjs');
 const f=fixture();await f.existing();await f.stop();
 const http=new SelfRunController({lifecycle:f.controller,transport:new SignalDispatchTransport(f.transport,f.store),stateStore:f.store,config:{}});
 await assert.rejects(http.accept({signalId:'http-resume',type:'RESUME',task_id:f.body.task_id,
  turn_id:f.body.turn_id,request_id:f.body.request_id,control_epoch:3,conversation_url:f.probe.url}),/owned by Drive/);
 assert.equal(f.resumes,1);assert.equal(f.store.snapshot().httpIngress,undefined);
});
test('late owned attachment is disposed when it arrives during the STOP outcome transaction',async()=>{
 const f=fixture(),opening=deferred(),attachGate=deferred(),outcomeEntered=deferred(),outcomeGate=deferred();let closed=0;
 f.browser.resume=async()=>{opening.resolve();await attachGate.promise;return {target:{id:'late'},session:{close(){closed++;}},baseline:f.probe};};
 const recordOutcome=f.controller.repository.recordStopOutcome.bind(f.controller.repository);let held=false;
 f.controller.repository.recordStopOutcome=async(...args)=>{if(!held){held=true;outcomeEntered.resolve();await outcomeGate.promise;}return recordOutcome(...args);};
 const attaching=f.existing();await opening.promise;const stopping=f.stop();await outcomeEntered.promise;
 attachGate.resolve();await idle();outcomeGate.resolve();await stopping;await attaching;
 assert.equal(closed,1);assert.equal(f.quiesces,1);assert.equal(f.controller.sessions.size,0);
 assert.deepEqual(active(f),[]);assert.equal(f.sends,0);
});
test('cold cancelled input cannot infer never-submitted from missing fields or old legacy uncertainty',async()=>{
 const f=fixture();await f.controller.initialize();
 f.controller.repository.history.legacy.set(keyFor(f.body),{unknown_post:true,recovery_count:0,error:'old POST timeout'});
 f.body.client_status='CANCELLED';await f.ingest();f.body={...f.body,dispatch_attempt:2,client_status:'SEND_REQUESTED'};
 await f.ingest();assert.equal(f.prepares,0);assert.equal(f.sends,0);assert.deepEqual(active(f),[]);
});
test('cold import consumes a newer explicit app resume without raising the old retirement epoch',async()=>{
 for(const state of ['STOPPED','CANCELLED','COMPLETED']){
  const f=fixture();await f.existing();const body=structuredClone(f.rows.get(f.path));await f.controller.quiesceForStandby();
  body.lifecycle_record.state=state;body.lifecycle_record.control_epoch=2;body.lifecycle_record.control_state='STOPPED';
  body.lifecycle_state=state;body.server_control_epoch=2;body.server_control_state='STOPPED';body.client_status='CANCELLED';
  f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};f.rows.set(f.path,body);
  f.store=new MemoryStore();f.controller=f.recreate();
  const w=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
  await w.scanOnce();await idle();await idle();await f.controller.tick(f.transport);
  assert.equal(f.resumes,2,state);assert.equal(f.sends,0);assert.equal(f.record().state,'OBSERVING');
  await f.controller.quiesceForStandby();
 }
});
test('deterministically invalid resume metadata is rejected once per app epoch without a polling loop',async()=>{
 const f=fixture();await f.existing();await f.stop();let reads=0;const read=f.transport.read;
 f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
 f.transport.read=async name=>{if(name===f.path){reads++;return {...await read(name),request_id:'wrong-request'};}return read(name);};
 for(let i=0;i<3;i++)await f.controller.control(null,f.control,f.transport).catch(()=>{});
 assert.equal(reads,1);assert.equal(f.resumes,1);assert.deepEqual(active(f),[]);
 assert.equal(f.record().resume_rejected_epoch,3);
});
test('STOP between durable browser transition and token acquisition returns without browser work or throw',async()=>{
 const f=fixture(),entered=deferred(),gate=deferred();f.body.client_status='CREATE_REQUESTED';
 const move=f.controller.repository.move.bind(f.controller.repository);
 f.controller.repository.move=async(...args)=>{const result=await move(...args);if(args[2]==='BROWSER_PREPARE'){entered.resolve();await gate.promise;}return result;};
 const preparing=f.ingest();await entered.promise;await f.stop();gate.resolve();await assert.doesNotReject(preparing);
 assert.equal(f.prepares,0);assert.equal(f.sends,0);assert.deepEqual(active(f),[]);
});
test('invalid resume dispatch attempt is an inert per-epoch rejection',async()=>{
 const f=fixture();await f.existing();await f.stop();let reads=0;const read=f.transport.read;
 f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
 f.transport.read=async name=>{if(name===f.path){reads++;return {...await read(name),dispatch_attempt:0};}return read(name);};
 for(let i=0;i<2;i++)await f.controller.control(null,f.control,f.transport).catch(()=>{});
 assert.equal(reads,1);assert.equal(f.record().resume_rejected_epoch,3);assert.equal(f.resumes,1);
});
