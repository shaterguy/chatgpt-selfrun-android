import test from 'node:test';
import assert from 'node:assert/strict';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';

function fixture({evaluateTimeout=false,held=false,optimistic=false}={}) {
  let handler,releaseHook;const failures=[];let reloaded=false,continued=0,clicks=0,staged=0;
  const probe=()=>({url:'https://chatgpt.com/c/owned',composer:true,readyState:'complete',streaming:false,
    userCount:optimistic&&!reloaded?2:1,userMessageId:optimistic&&!reloaded?'new-message':'old-message'});
  const session={
    on(_name,fn){handler=fn;return()=>{handler=null;};},
    async call(method,params={}) {
      if(method==='Page.reload'){reloaded=true;return {};}
      if(method==='Fetch.enable'||method==='Fetch.disable')return {};
      if(method==='Fetch.failRequest'){failures.push(params.requestId);return {};}
      if(method==='Fetch.continueRequest'){continued++;return {};}
      assert.equal(method,'Runtime.evaluate');
      const s=params.expression;
      if(s.includes('const matched='))return {result:{value:{idMatch:optimistic&&!reloaded,lastTextMatch:optimistic&&!reloaded}}};
      if(s.includes('send.click()')) {
        clicks++;
        if(held)void handler({requestId:'paused-post',request:{method:'POST',url:'https://chatgpt.com/backend-api/f/conversation',
          postData:JSON.stringify({conversation_id:'owned',messages:[{id:'new-message',author:{role:'user'}}]})}});
        if(evaluateTimeout)throw new Error('CDP command timeout: Runtime.evaluate');
        return {result:{value:{status:'SUBMITTED'}}};
      }
      if(s.includes('const expected=')){staged++;return {result:{value:{status:'READY'}}};}
      if(s.includes("status:send?'SEND_FOUND'"))return {result:{value:{ready:true}}};
      return {result:{value:probe()}};
    },
  };
  const browser=new ChatGptBrowser(null,{navigationTimeoutMs:100});
  return {session,browser,failures,continued:()=>continued,clicks:()=>clicks,staged:()=>staged,probe};
}
test('evaluate timeout plus unchanged DOM is UNKNOWN, never absence proof',async()=>{
  const f=fixture({evaluateTimeout:true});
  const intent={id:'intent',receipt_generation:'generation',prompt:'continue',baseline:{userCount:1,userMessageId:'old-message'}};
  await f.browser.submitIntent({session:f.session,prompt:'continue',profileOperations:[{op:'SET',path:'model',value:'test'},{op:'SET',path:'thinking_effort',value:'max'},{op:'REMOVE',path:'conversation_origin'},{op:'REMOVE',path:'service_tier'}],intent,conversationUrl:'https://chatgpt.com/c/owned',guard:()=>true});
  assert.equal(f.clicks(),1,'actual submission click was attempted exactly once');
  const r=await f.browser.readSubmission({session:f.session,prompt:'continue',conversationUrl:'https://chatgpt.com/c/owned',intent});
  assert.equal(r.state,'UNKNOWN');assert.equal(intent.absence_proof,undefined);assert.equal(f.clicks(),1);
});
test('optimistic user bubble is not accepted without semantic response evidence',async()=>{
  const f=fixture({optimistic:true});
  const r=await f.browser.readSubmission({session:f.session,prompt:'continue',conversationUrl:'https://chatgpt.com/c/owned',
    intent:{message_id:'new-message',baseline:{userCount:1,userMessageId:'old-message'}}});
  assert.equal(r.state,'UNKNOWN');
});

test('initial submission hands off an existing conversation without touching the composer or reporting a send',async()=>{
 const f=fixture();let observed=null,submitted=0,requests=0;
 const result=await f.browser.submitIntent({session:f.session,prompt:'test',kind:'initial',intent:{id:'i'},guard:()=>true,profileOperations:[],
  onConversation:async evidence=>observed=evidence,onSubmitted:async()=>submitted++,onRequest:async()=>requests++});
 assert.equal(result.observationOnly,true);assert.equal(result.submitted,false);assert.equal(result.noSubmitEffect,true);
 assert.equal(result.url,'https://chatgpt.com/c/owned');assert.deepEqual(observed,{url:'https://chatgpt.com/c/owned'});
 assert.equal(f.staged(),0);assert.equal(f.clicks(),0);assert.equal(submitted,0);assert.equal(requests,0);
});
test('prepare navigation does not inspect response or click inferred new-chat controls',async()=>{
 let staged=0,closed=0;
 const session={close(){},async call(method,p={}){
  if(method!=='Runtime.evaluate')return {};
  if(p.expression.includes("status:'MISSING'"))return {result:{value:{status:'MISSING'}}};
  if(p.expression.includes('const expected='))staged++;
  return {result:{value:{url:'https://chatgpt.com/c/other-owned',readyState:'complete',composer:true}}};
 }};
 const chromium={createTarget:async()=>({id:'new-target'}),connectTarget:async()=>session,closeTarget:async()=>{closed++;}};
 const browser=new ChatGptBrowser(chromium,{navigationTimeoutMs:100});
 await browser.prepare({projectUrl:'https://chatgpt.com/',prompt:'test'});
 assert.equal(staged,0);assert.equal(closed,0);
});
