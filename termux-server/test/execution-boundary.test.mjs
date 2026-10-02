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
  const f=fixture(); f.body.client_status='CANCELLED'; await f.ingest();
  await f.controller.tick(f.transport);
  assert.equal(f.sends,0); assert.equal(f.prepares,0); assert.equal(f.record().state,'CANCELLED');
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
