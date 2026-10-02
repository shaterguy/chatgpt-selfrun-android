import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { ChatGptBrowser, requestProfileDocumentStartSource } from '../src/browser/chatgpt.mjs';

const operations=[
  {op:'SET',path:'model',value:'gpt-5-6-thinking'},
  {op:'SET',path:'thinking_effort',value:'max'},
  {op:'REMOVE',path:'conversation_origin'},
  {op:'REMOVE',path:'service_tier'},
];

class MockRequest {
  constructor(input,init={}) {
    if(input instanceof MockRequest){this.url=input.url;this.method=input.method;this.body=input.body;}
    else{this.url=String(input);this.method='GET';this.body='';}
    if(init.method!==undefined)this.method=String(init.method);
    if(init.body!==undefined)this.body=String(init.body);
  }
  clone(){return new MockRequest(this);}
  async text(){return this.body;}
}
class MockXHR {
  open(method,url){this.method=method;this.url=url;}
  send(body){this.sent=body;}
}
function engineFixture() {
  const seen=[];
  const window={fetch:async(input,init)=>{
    const request=input instanceof MockRequest?input:new MockRequest(input,init);
    seen.push({url:request.url,method:request.method,body:request.body});
    return {ok:true};
  }};
  const context={window,location:{href:'https://chatgpt.com/g/project',origin:'https://chatgpt.com'},
    Request:MockRequest,XMLHttpRequest:MockXHR,URL,WeakMap,JSON,Set,String,Error};
  vm.runInNewContext(requestProfileDocumentStartSource(),context);
  context.window.__selfrunRequestProfileEngine.configure([
    ['set','model','gpt-5-6-thinking'],
    ['set','thinking_effort','max'],
    ['remove','conversation_origin'],
    ['remove','service_tier'],
  ]);
  return {window,seen};
}
test('scheduler-style profile engine changes only the four control fields',async()=>{
  const f=engineFixture();
  const original={model:'instant-default',requested_default_model:'instant-default',thinking_effort:'standard',
    conversation_origin:'chat',service_tier:'default',conversation_id:'existing-profile',
    messages:[{id:'user-2',author:{role:'user'},content:{parts:['hello']}}],metadata:{keep:true}};
  await f.window.fetch('https://chatgpt.com/backend-api/f/conversation',{method:'POST',body:JSON.stringify(original)});
  assert.equal(f.seen.length,1);
  const body=JSON.parse(f.seen[0].body);
  assert.equal(body.model,'gpt-5-6-thinking');
  assert.equal(body.thinking_effort,'max');
  assert.equal(body.requested_default_model,'instant-default');
  assert.equal('conversation_origin' in body,false);
  assert.equal('service_tier' in body,false);
  assert.equal(body.conversation_id,'existing-profile');
  assert.deepEqual(body.messages,original.messages);
  assert.deepEqual(body.metadata,original.metadata);
});
test('scheduler-style profile engine does not intercept conversation init',async()=>{
  const f=engineFixture();
  const original={model:'instant-default',requested_default_model:'instant-default'};
  await f.window.fetch('https://chatgpt.com/backend-api/conversation/init',{method:'POST',body:JSON.stringify(original)});
  assert.equal(f.seen.length,1);
  assert.deepEqual(JSON.parse(f.seen[0].body),original);
});
test('ChatGptBrowser submission uses UI click and never enables CDP Fetch interception',async()=>{
  let submitted=0,userCount=1,userMessageId='user-1';
  const calls=[];
  const probe=()=>({url:'https://chatgpt.com/c/existing-profile',composer:true,streaming:false,stopButtonVisible:false,
    paused:false,userCount,userTextLength:20,userMessageId,assistantCount:1,assistantTextLength:20,
    assistantMessageId:'a1',responseTurnId:'r1',responseTextLength:20,responseFingerprint:'x'});
  const session={async call(method,params={}){
    calls.push(method);
    if(method==='Runtime.evaluate'){
      const expression=params.expression||'';
      if(expression.includes("status:send?'SEND_FOUND'"))return {result:{value:{ready:true,status:'SEND_FOUND'}}};
      if(expression.includes('send.focus?.();send.click()')){submitted++;userCount=2;userMessageId='user-2';return {result:{value:{status:'SUBMITTED'}}};}
      if(expression.includes("return {status:same()?'READY':'MISMATCH'"))return {result:{value:{status:'READY',length:8}}};
      if(expression.includes('__selfrunRequestProfileEngine'))return {result:{value:true}};
      return {result:{value:probe()}};
    }
    if(method==='Page.addScriptToEvaluateOnNewDocument')return {identifier:'profile'};
    if(method==='Page.enable'||method==='Network.enable')return {};
    throw new Error('unexpected method '+method);
  }};
  const browser=new ChatGptBrowser(null,{navigationTimeoutMs:1000});
  const accepted=await browser.submitIntent({session,prompt:'continue',profileOperations:operations,
    conversationUrl:'https://chatgpt.com/c/existing-profile',guard:()=>true,
    intent:{baseline:{userCount:1,userMessageId:'user-1'}}});
  assert.equal(submitted,1);
  assert.equal(accepted.userMessageId,'user-2');
  assert.equal(calls.includes('Fetch.enable'),false);
  assert.equal(calls.includes('Fetch.continueRequest'),false);
  assert.equal(calls.includes('Fetch.failRequest'),false);
});

for(const cancellation of ['signal','guard'])test('browser final send fence after awaited readiness: '+cancellation,async()=>{
  let release,enteredResolve,submitted=0,allowed=true;
  const entered=new Promise(resolve=>{enteredResolve=resolve;});
  const ready=new Promise(resolve=>{release=resolve;});
  const abort=new AbortController();
  const probe={url:'https://chatgpt.com/c/existing-profile',composer:true,streaming:false,userCount:1,userMessageId:'u1'};
  const session={async call(method,params={}){
    assert.equal(method,'Runtime.evaluate');
    const expression=params.expression||'';
    if(expression.includes("status:send?'SEND_FOUND'")){enteredResolve();await ready;return {result:{value:{ready:true,status:'SEND_FOUND'}}};}
    if(expression.includes('send.focus?.();send.click()')){submitted++;return {result:{value:{status:'SUBMITTED'}}};}
    if(expression.includes("return {status:same()?'READY':'MISMATCH'"))return {result:{value:{status:'READY'}}};
    if(expression.includes('__selfrunRequestProfileEngine'))return {result:{value:true}};
    return {result:{value:probe}};
  }};
  const browser=new ChatGptBrowser(null,{navigationTimeoutMs:100});
  const posting=browser.submitIntent({session,prompt:'continue',profileOperations:operations,
    conversationUrl:probe.url,guard:()=>allowed,signal:abort.signal,intent:{baseline:probe}});
  await entered;
  if(cancellation==='signal')abort.abort(new Error('cancelled'));else allowed=false;
  release();
  await assert.rejects(posting,/cancelled|stale|aborted/);
  assert.equal(submitted,0);
});
