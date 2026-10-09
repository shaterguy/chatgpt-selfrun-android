import test from 'node:test';
import assert from 'node:assert/strict';
import {ChatGptBrowser} from '../src/browser/chatgpt.mjs';
import {fixture as controllerFixture} from './helpers/lifecycle-fixture.mjs';

const uuid='92dd19de-1242-4d48-8f34-413e8ed42aa4';
// Sanitized 59PXHA observation: empty initial baseline; current response-region
// text grows while no assistant bubble/ID exists and the POST has no SSE ACK.
const empty={userCount:0,assistantCount:0,responseTurnId:null,responseTextLength:0,responseFingerprint:''};
const response={url:'https://chatgpt.com/c/owned',userCount:0,assistantCount:0,assistantMessageId:null,
  responseTurnId:'new-response',responseTextLength:221,responseFingerprint:'current-response',streaming:true};
function fixture() {
  const f={probe:{...response},receipt:{version:1,intent_id:'i',generation:'g',request_id:'g:1',
    message_id:uuid,conversation_id:'owned',released:true,http_status:200,ack:null,invalid:null},calls:[],saves:[]};
  f.intent={id:'i',receipt_generation:'g',kind:'initial',prompt:'exact input',sends:1,released:true,baseline:{...empty}};
  f.session={async call(method,p){f.calls.push(method);assert.equal(method,'Runtime.evaluate');
    return {result:{value:p.expression.includes('__selfrunReceiptObserver')?f.receipt:f.probe}};}};
  f.browser=new ChatGptBrowser(null,{navigationTimeoutMs:1});
  f.read=extra=>f.browser.readSubmission({session:f.session,intent:f.intent,prompt:f.intent.prompt,
    conversationUrl:'https://chatgpt.com/c/owned',onRequest:async evidence=>{f.saves.push(evidence);Object.assign(f.intent,evidence);},...extra});
  return f;
}
test('new current response confirms without SSE ACK or an assistant bubble',async()=>{
  const f=fixture();const result=await f.read();assert.equal(result.state,'CONFIRMED');
  assert.equal(result.evidence,'NEW_RESPONSE');assert.equal(result.messageId,uuid);assert.equal(f.intent.receipt.ack,null);
});
test('new response is an independent alternative when the passive observer is absent',async()=>{
  const f=fixture();f.receipt=null;assert.equal((await f.read()).state,'CONFIRMED');
});
test('new completed response also confirms after streaming has ended',async()=>{
  const f=fixture();f.probe.streaming=false;assert.equal((await f.read()).state,'CONFIRMED');
});
for(const invalid of ['response_bound','event_bound','malformed_event','response_read_error','clone_unavailable','response_body_unavailable'])
  test('response evidence remains independent of passive read failure '+invalid,async()=>{
    const f=fixture();f.receipt.invalid=invalid;assert.equal((await f.read()).state,'CONFIRMED');
  });
for(const [name,change] of [
  ['other conversation',f=>{f.probe.url='https://chatgpt.com/c/other';}],
  ['project route',f=>{f.probe.url='https://chatgpt.com/g/project';}],
  ['login',f=>{f.probe.loginPage=true;}],
  ['streaming without response content',f=>{f.probe.responseTextLength=0;f.probe.responseFingerprint='';}],
  ['user bubble alone',f=>{f.probe={...f.probe,userCount:1,userMessageId:uuid,responseTextLength:0,responseFingerprint:''};}],
  ['missing baseline',f=>{delete f.intent.baseline;}],
  ['unreleased attempt',f=>{f.intent.released=false;f.receipt=null;}],
  ['stale observer',f=>{f.receipt.generation='old';}],
  ['other receipt conversation',f=>{f.receipt.conversation_id='other';}],
  ['duplicate request',f=>{f.receipt.invalid='duplicate_request';}],
  ['HTTP failure',f=>{f.receipt.invalid='http_error';f.receipt.http_status=500;}],
  ['unchanged old response',f=>{f.intent.kind='recovery';f.intent.baseline={...f.probe,userCount:1,userMessageId:'old-user'};f.probe.userCount=1;f.probe.userMessageId='old-user';}],
  ['growth in old response',f=>{f.intent.kind='recovery';f.intent.baseline={...f.probe,userCount:1,userMessageId:'old-user',responseTextLength:100,responseFingerprint:'old-text'};f.probe.userCount=1;f.probe.userMessageId='old-user';}],
  ['remounted old response',f=>{f.intent.kind='recovery';f.intent.baseline={...f.probe,userCount:1,userMessageId:'old-user',responseTurnId:'old-response'};f.probe.userCount=1;f.probe.userMessageId='old-user';}],
]) test('does not accept '+name,async()=>{const f=fixture();change(f);assert.equal((await f.read()).state,'UNKNOWN');});
test('recovery confirms its new response after the current input in the same conversation',async()=>{
  const f=fixture();f.intent.kind='recovery';f.intent.message_id=uuid;
  f.intent.baseline={...response,userCount:1,userMessageId:'data-message-id:old',responseTurnId:'old-response',responseFingerprint:'old'};
  Object.assign(f.probe,{userCount:2,userMessageId:'data-message-id:'+uuid});
  assert.equal((await f.read()).state,'CONFIRMED');
  f.probe.userMessageId='data-message-id:unrelated';assert.equal((await f.read()).state,'UNKNOWN');
});
test('STOP while saving evidence prevents late confirmation',async()=>{
  const f=fixture();let allowed=true;
  await assert.rejects(f.read({guard:()=>allowed,onRequest:async()=>{allowed=false;}}),/stale/);
});
test('late current response clears unknown outcome and warning projections without resending',async t=>{
  const f=controllerFixture();t.after(()=>f.controller.quiesceForStandby());
  f.mode='unknown';f.publishConversationBeforeOutcome=true;f.probe={...response,...empty};
  f.browser.readSubmission=async()=>({state:'UNKNOWN',probe:{...f.probe}});
  await f.ingest();assert.equal(f.record().intent.outcome,'UNKNOWN');assert.equal(f.record().error.code,'POST_UNCERTAIN');
  f.probe={...response};
  const browser=new ChatGptBrowser(null,{navigationTimeoutMs:1});
  f.browser.readSubmission=args=>browser.readSubmission({...args,session:{async call(method,p){
    assert.equal(method,'Runtime.evaluate');return {result:{value:p.expression.includes('__selfrunReceiptObserver')?null:f.probe}};
  }}});
  await f.controller.tick(f.transport);await f.settle();
  assert.equal(f.record().intent.outcome,'CONFIRMED');assert.equal(f.record().submission_confirmed,true);assert.equal(f.record().error,null);
  const projected=f.rows.get(f.path);assert.equal(projected.post_outcome,'CONFIRMED');assert.equal(projected.server_error,'');assert.equal(projected.server_error_class,null);
  assert.notEqual(f.record().reason,'POST_READBACK_UNKNOWN');assert.equal(f.sends,1);
});

import {cursor} from '../src/lifecycle.mjs';
for(const change of ['growth','remount'])test('real recovery cursor cannot confirm an old response after '+change+' when request identity is absent',async()=>{
  const f=fixture();f.intent.kind='recovery';f.intent.message_id=null;f.receipt=null;
  const old={...response,userCount:1,userMessageId:'old-user',responseTurnId:'old-turn',responseFingerprint:'old-text'};
  f.intent.baseline=cursor(old);f.probe={...old,...(change==='growth'?{responseFingerprint:'changed-text',responseTextLength:500}:{responseTurnId:'remounted-turn'})};
  assert.equal(Object.hasOwn(f.intent.baseline,'userMessageId'),false);
  assert.equal((await f.read()).state,'UNKNOWN');
});
for(const mode of ['old-growth','old-remount','matching-new-input'])test('controller recovery intent and browser evidence integrate for '+mode,async t=>{
  let now=1700000000000;t.mock.method(Date,'now',()=>now);
  const f=controllerFixture();t.after(()=>f.controller.quiesceForStandby());
  f.probe.streaming=false;f.probe.stopButtonVisible=false;await f.existing();await f.observe({});
  const browser=new ChatGptBrowser(null,{navigationTimeoutMs:1});
  f.browser.readSubmission=args=>browser.readSubmission({...args,session:{async call(method,p){
    assert.equal(method,'Runtime.evaluate');return {result:{value:p.expression.includes('__selfrunReceiptObserver')?null:{...f.probe}}};
  }}});
  f.browser.submitIntent=async args=>{
    f.sends++;assert.equal(args.kind,'recovery');
    assert.equal(Object.hasOwn(args.intent.baseline,'userMessageId'),false);
    assert.equal(args.intent.message_id,null);
    await args.onRequest({released:true,...(mode==='matching-new-input'?{message_id:uuid}:{})});
    if(mode==='old-growth')f.probe.responseFingerprint='old-response-kept-growing';
    else if(mode==='old-remount')f.probe.responseTurnId='old-response-remounted';
    else Object.assign(f.probe,{userCount:2,userMessageId:'data-message-id:'+uuid,responseTurnId:'new-after-this-input',responseFingerprint:'new-response'});
    return {...f.probe};
  };
  now+=600000;await f.controller.tick(f.transport);await f.settle();
  assert.equal(f.sends,1);assert.equal(f.record().intent.kind,'recovery');
  assert.equal(f.record().intent.outcome,mode==='matching-new-input'?'CONFIRMED':'UNKNOWN');
  if(mode!=='matching-new-input')assert.equal(f.record().error.code,'POST_UNCERTAIN');
});
