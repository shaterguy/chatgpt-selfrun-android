import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';
import { keyFor } from '../src/lifecycle.mjs';
import { humanizeSelfRunPrompt, appendTurnStartDirective } from '../src/prompt-directives.mjs';

async function send(f) { f.body.client_status='SEND_REQUESTED'; await f.ingest(); }

test('execution: formal app preparation waits for SEND_REQUESTED',async()=>{
  const f=fixture(); f.body.client_status='CREATE_REQUESTED'; await f.ingest();
  assert.equal(f.prepares,1); assert.equal(f.sends,0);
  assert.equal(f.rows.get(f.path).server_status,'READY_TO_SUBMIT');
  await send(f); assert.equal(f.sends,1); assert.equal(f.rows.get(f.path).server_status,'STARTED');
});
test('execution: Result documents are never read to authorize initial submission',async()=>{
  const f=fixture(); let reads=0;
  f.transport.readGoogleDocText=async()=>{reads++;throw new Error('must remain app-owned');};
  await send(f); assert.equal(f.sends,1);
  assert.equal(reads,0);
});
test('execution: predecessor metadata and opaque turn IDs do not gate app SEND_REQUESTED',async()=>{
  const f=fixture();
  Object.assign(f.body,{turn_id:'opaque-next',request_id:'request-next',previous_result_document_id:'missing-result'});
  Object.assign(f.control,{turn_id:f.body.turn_id,request_id:f.body.request_id});
  let reads=0; f.transport.readGoogleDocText=async()=>{reads++;throw new Error('no Result access');};
  await send(f); assert.equal(f.sends,1); assert.equal(reads,0);
});
test('execution: missing or UNKNOWN control does not veto an app dispatch',async()=>{
  for(const state of [null,'UNKNOWN']) {
    const f=fixture();
    f.control=state?{...f.control,state}:null;
    await send(f); assert.equal(f.sends,1);
  }
});
test('execution: server prompt rewriting remains the submitted immutable payload',async()=>{
  const f=fixture();
  f.body.prompt='TASK_ID=T\nTURN_ID=T:turn:1\nREQUEST_ID=R\nRESULT_DOCUMENT_ID=D\n[사용자 추가 지시 원문]\nkeep this instruction';
  f.controller.config.turnStartDirective='configured start';
  await send(f);
  assert.equal(f.sent[0].prompt,appendTurnStartDirective(humanizeSelfRunPrompt(f.body.prompt),'configured start'));
  assert.equal(f.record().intent.prompt,f.sent[0].prompt);
});
test('execution: cancelled attempt is not revived by unchanged RUNNING control',async()=>{
  const f=fixture(); f.body.client_status='CREATE_REQUESTED'; await f.ingest();
  f.body.client_status='CANCELLED'; await f.ingest();
  await f.controller.tick(f.transport);
  assert.equal(f.sends,0); assert.equal(f.prepares,1); assert.equal(f.record().state,'CANCELLED');
  f.body.dispatch_attempt=2; f.path='__SELFRUN_DISPATCH__retry.json';
  f.body.client_status='CREATE_REQUESTED'; await f.ingest();
  assert.equal(f.sends,0); assert.equal(f.record().state,'PREPARED');
  await send(f); assert.equal(f.sends,1);
});
test('execution: early canonical URL is not a verified STARTED receipt',async()=>{
  const f=fixture(); f.publishConversationBeforeOutcome=true; f.mode='unknown';
  await send(f);
  assert.equal(f.rows.get(f.path).conversation_url,f.probe.url);
  assert.equal(f.record().intent.outcome,'UNKNOWN');
  assert.equal(f.rows.get(f.path).server_status,'PENDING');
  await f.controller.tick(f.transport); assert.equal(f.sends,1);
});
test('execution: response idle and Result commit leave the observer alive',async()=>{
  const f=fixture(); await f.existing(); f.result(true);
  const observer=f.observations.at(-1);
  await f.observe({streaming:false,stopButtonVisible:false,status:'COMPLETED'});
  assert.equal(observer.signal.aborted,false); assert.equal(f.controller.sessions.size,1);
  assert.notEqual(f.record().state,'COMPLETED');
  assert.equal(f.rows.get(f.path).response_status,'COMPLETED');
});
test('execution: ten-minute nudge retains Result suppression while observer stays alive',async()=>{
  for(const result of ['open','committed','unreadable']) {
    const f=fixture(); await f.existing(); f.controller.config.stallAfterMs=600000;
    if(result==='committed')f.result(true);
    if(result==='unreadable')f.docs.set('RESULT-1','unreadable');
    await f.observe({});
    await f.controller.repository.move(keyFor(f.body),'OBSERVING','elapsed',{last_progress_at:Date.now()-600001});
    await f.observe({status:'STALLED'});
    assert.equal(f.sends,result==='open'?1:0);
    if(result==='open')assert.equal(f.sent[0].prompt,'continue test');
    assert.equal(f.observations.at(-1).signal.aborted,false);
  }
});
test('execution: STOP quiesces the owned conversation and fences late callbacks',async()=>{
  const f=fixture(); await f.existing(); const observer=f.observations.at(-1);
  await f.stop();
  assert.equal(f.quiesces,1); assert.equal(observer.signal.aborted,true);
  const before=f.record().revision;
  await observer.onActivity({...f.probe,responseTextLength:99999});
  assert.equal(f.record().revision,before); assert.equal(f.sends,0);
});
test('execution: next app request ends the preceding observer without interpreting predecessor',async()=>{
  const f=fixture(); await f.existing(); const observer=f.observations.at(-1);
  const next={...f.body,turn_id:'another-turn',request_id:'another-request',conversation_url:'',
    client_status:'CREATE_REQUESTED',dispatch_attempt:1};
  f.control={...f.control,turn_id:next.turn_id,request_id:next.request_id,control_epoch:2};
  await f.controller.control(null,f.control,f.transport);
  const nextPath='__SELFRUN_DISPATCH__next.json';f.rows.set(nextPath,next);
  await f.controller.ingest(nextPath,next,f.transport);
  assert.equal(observer.signal.aborted,true); assert.equal(f.sends,0);
  assert.equal(f.rows.get(nextPath).server_status,'READY_TO_SUBMIT');
});

test('execution: STOP fences abort-aware pending attach without reopening for cleanup',async()=>{
  const f=fixture(),entered=deferred();const resume=f.browser.resume;
  f.browser.resume=async({signal})=>{
    entered.resolve();
    await new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(new Error('attach aborted')),{once:true}));
  };
  const opening=f.existing();await entered.promise;await f.stop();await opening;
  f.browser.resume=resume;await f.controller.tick(f.transport);
  assert.equal(f.record().stop_status,'UNCONFIRMED');assert.equal(f.quiesces,0);assert.equal(f.sends,0);
  assert.deepEqual(f.controller.repository.records(),[]);
});
test('execution: persisted STOP remains inert after the control transaction crash gap',async()=>{
  const f=fixture();await f.existing();
  f.control={...f.control,state:'STOPPED',control_epoch:2};
  await f.controller.repository.applyControl(f.control);
  await f.controller.quiesceForStandby();f.controller=f.recreate();
  await f.controller.tick(f.transport);
  assert.equal(f.record().stop_status,'UNCONFIRMED');assert.equal(f.quiesces,0);assert.equal(f.sends,0);
  assert.deepEqual(f.controller.repository.records(),[]);
});
test('execution: an unconfirmed STOP stays inert without resubmitting',async()=>{
  const f=fixture();await f.existing();const quiesce=f.browser.quiesce;
  f.browser.quiesce=async()=>{throw new Error('temporary stop transport error');};
  await f.stop();assert.equal(f.record().stop_status,'UNCONFIRMED');
  f.browser.quiesce=quiesce;
  await f.controller.repository.move(keyFor(f.body),'STOPPED','retry elapsed',{stop_retry_at:0});
  await f.controller.tick(f.transport);
  assert.equal(f.record().stop_status,'UNCONFIRMED');assert.equal(f.quiesces,0);assert.equal(f.sends,0);
  assert.deepEqual(f.controller.repository.records(),[]);
});
test('execution: migration does not reactivate an observer after authoritative DONE',async()=>{
  const f=fixture();await f.existing();await f.controller.quiesceForStandby();
  const snapshot=f.store.snapshot(),r=snapshot.lifecycle.requests[keyFor(f.body)];
  r.state='COMPLETED';r.control_state='RUNNING';r.result_state='COMMITTED';
  f.control={...f.control,state:'DONE',control_epoch:2};snapshot.lifecycle.controls[f.body.task_id]=f.control;
  await f.store.patch(snapshot);f.controller=f.recreate();await f.controller.tick(f.transport);
  assert.equal(f.resumes,1);assert.equal(f.controller.sessions.size,0);
});
test('execution: imported legacy terminal state stays finished under cached RUNNING',async()=>{
  const f=fixture();await f.existing();await f.controller.quiesceForStandby();
  const body=structuredClone(f.rows.get(f.path));body.lifecycle_record.state='COMPLETED';
  body.lifecycle_record.result_state='COMMITTED';body.lifecycle_state='COMPLETED';
  const {MemoryStore}=await import('./helpers/lifecycle-fixture.mjs');
  f.store=new MemoryStore();f.controller=f.recreate();f.rows.set(f.path,body);
  await f.controller.ingest(f.path,body,f.transport);
  assert.equal(f.resumes,1);assert.equal(f.sends,0);assert.equal(f.controller.sessions.size,0);
  assert.equal(f.record().state,'COMPLETED');assert.equal(f.store.snapshot().activeCount,0);
});

test('execution: durable cancellation fences a POST waiting on publication',async()=>{
  const f=fixture(),entered=deferred(),gate=deferred();
  const flush=f.controller.repository.flushCurrent.bind(f.controller.repository);let held=false;
  f.controller.repository.flushCurrent=async(...args)=>{
    if(!held&&f.record()?.state==='POST_PENDING'){held=true;entered.resolve();await gate.promise;}
    return flush(...args);
  };
  const posting=f.ingest();await entered.promise;
  f.body.client_status='CANCELLED';f.rows.set(f.path,structuredClone(f.body));
  await f.controller.ingestDurable(f.path,f.body,f.transport);
  gate.resolve();await posting;
  assert.equal(f.sends,0);assert.equal(f.record().state,'CANCELLED');
});
test('execution: unreadable TaskControl fails closed instead of assuming absence',async()=>{
  const f=fixture(),read=f.transport.read;
  f.transport.read=async name=>{
    if(name.startsWith('__SELFRUN_CONTROL__'))throw new Error('control transport temporarily unavailable');
    return read(name);
  };
  await assert.rejects(f.ingest(),/control transport temporarily unavailable/);
  assert.equal(f.sends,0);
});

test('execution: app cancellation followed by STOP never reopens a released observer',async()=>{
  const f=fixture();await f.existing();const quiesce=f.browser.quiesce;
  f.body.client_status='CANCELLED';await f.ingest();
  f.browser.quiesce=async()=>{throw new Error('temporary stop failure');};
  await f.stop();assert.equal(f.record().stop_status,'UNCONFIRMED');
  f.browser.quiesce=quiesce;
  await f.controller.repository.move(keyFor(f.body),'STOPPED','retry elapsed',{stop_retry_at:0});
  await f.controller.tick(f.transport);
  assert.equal(f.record().stop_status,'UNCONFIRMED');assert.equal(f.quiesces,0);assert.equal(f.sends,0);
  assert.deepEqual(f.controller.repository.records(),[]);
});

test('execution: STOP with unknown URL fences a released input without historical retry',async()=>{
  const f=fixture(),entered=deferred(),gate=deferred();
  f.onSubmit=async()=>{entered.resolve();await gate.promise;};
  const posting=f.ingest();await entered.promise;
  assert.equal(f.record().conversation_url,'');
  await f.stop();gate.resolve();await posting;
  await f.controller.repository.move(keyFor(f.body),'STOPPED','retry elapsed',{stop_retry_at:0});
  await f.controller.tick(f.transport);
  assert.equal(f.record().conversation_url,undefined);
  assert.equal(f.record().stop_status,'UNCONFIRMED');assert.equal(f.quiesces,0);
  assert.deepEqual(f.controller.repository.records(),[]);
  assert.equal(f.sends,1);assert.equal(f.prepares,1);
});
test('execution: STOP without provable submitted URL is explicitly unconfirmed',async()=>{
  const f=fixture();f.mode='unknown';await f.ingest();await f.stop();
  assert.equal(f.record().stop_status,'UNCONFIRMED');
  assert.equal(f.quiesces,0);assert.equal(f.sends,1);assert.equal(f.prepares,1);
});
