import test from 'node:test';
import assert from 'node:assert/strict';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';

function fixture({evaluateTimeout=false,held=false,optimistic=false,persistedTurnKey=false}={}) {
  let handler,releaseHook;const failures=[];let reloaded=false,continued=0;
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
      if(s.includes('const matched=')){
        if(persistedTurnKey)assert.match(s,/data-turn-key/);
        return {result:{value:{idMatch:(optimistic&&!reloaded)||(persistedTurnKey&&reloaded),lastTextMatch:optimistic&&!reloaded}}};
      }
      if(s.includes('send.click()')) {
        if(held)void handler({requestId:'paused-post',request:{method:'POST',url:'https://chatgpt.com/backend-api/f/conversation',
          postData:JSON.stringify({conversation_id:'owned',messages:[{id:'new-message',author:{role:'user'}}]})}});
        if(evaluateTimeout)throw new Error('CDP command timeout: Runtime.evaluate');
        return {result:{value:{status:'SUBMITTED'}}};
      }
      if(s.includes('const expected='))return {result:{value:{status:'READY'}}};
      if(s.includes("status:send?'SEND_FOUND'"))return {result:{value:{ready:true}}};
      return {result:{value:probe()}};
    },
  };
  const browser=new ChatGptBrowser(null,{navigationTimeoutMs:100});
  return {session,browser,failures,continued:()=>continued,probe};
}
test('evaluate timeout plus unchanged DOM is UNKNOWN, never absence proof',async()=>{
  const f=fixture({evaluateTimeout:true});
  let error;
  try{await f.browser.submitIntent({session:f.session,prompt:'continue',profileOperations:[],intent:{id:'intent'},conversationUrl:'https://chatgpt.com/c/owned',guard:()=>true});}
  catch(e){error=e;}
  assert.ok(error);assert.equal(error.absenceProof,null);
  const r=await f.browser.readSubmission({session:f.session,prompt:'continue',conversationUrl:'https://chatgpt.com/c/owned',
    intent:{baseline:{userCount:1,userMessageId:'old-message'},absence_proof:error.absenceProof,released:error.released}});
  assert.equal(r.state,'UNKNOWN');
});
test('optimistic user bubble disappears after reload and is not accepted',async()=>{
  const f=fixture({optimistic:true});
  const r=await f.browser.readSubmission({session:f.session,prompt:'continue',conversationUrl:'https://chatgpt.com/c/owned',
    intent:{message_id:'new-message',baseline:{userCount:1,userMessageId:'old-message'}}});
  assert.equal(r.state,'UNKNOWN');
});
test('persisted data-turn-key matching intercepted message id confirms accepted POST after reload',async()=>{
  const f=fixture({persistedTurnKey:true});
  const r=await f.browser.readSubmission({session:f.session,prompt:'현재 턴에 할당된 잔여작업이 있으면 계속 수행해',
    conversationUrl:'https://chatgpt.com/c/owned',
    intent:{message_id:'recovery-turn-id',baseline:{userCount:1,userMessageId:'old-message'}}});
  assert.equal(r.state,'CONFIRMED');
  assert.equal(r.messageId,'recovery-turn-id');
});

test('initial POST can be confirmed from a reload-persistent matching conversation in another target',async()=>{
  let primaryReloaded=false,candidateReloaded=false,candidateClosed=false;
  const primaryProbe=()=>({url:'https://chatgpt.com/g/project/project',composer:true,readyState:'complete',streaming:false,
    userCount:0,userMessageId:null});
  const candidateProbe=()=>({url:'https://chatgpt.com/c/new-owned',composer:true,readyState:'complete',streaming:false,
    userCount:1,userMessageId:'fallback:user'});
  const makeSession=(kind)=>({
    close(){if(kind==='candidate')candidateClosed=true;},
    async call(method,params={}){
      if(method==='Page.reload'){if(kind==='primary')primaryReloaded=true;else candidateReloaded=true;return {};}
      assert.equal(method,'Runtime.evaluate');
      const expr=params.expression;
      if(expr.includes('const matched='))return {result:{value:kind==='candidate'&&candidateReloaded
        ?{idMatch:true,lastTextMatch:true}:{idMatch:false,lastTextMatch:false}}};
      return {result:{value:kind==='candidate'?candidateProbe():primaryProbe()}};
    },
  });
  const primary=makeSession('primary'),candidate=makeSession('candidate');
  const chromium={
    listExistingTargets:async()=>[{id:'candidate',type:'page',webSocketDebuggerUrl:'ws://candidate'}],
    connectTarget:async()=>candidate,
  };
  const browser=new ChatGptBrowser(chromium,{navigationTimeoutMs:100});
  const r=await browser.readSubmission({session:primary,prompt:'repair prompt',conversationUrl:'',
    intent:{message_id:'exact-turn-id',baseline:{userCount:0,userMessageId:null}}});
  assert.equal(primaryReloaded,true);assert.equal(candidateReloaded,true);assert.equal(candidateClosed,true);
  assert.equal(r.state,'CONFIRMED');assert.equal(r.messageId,'exact-turn-id');assert.equal(r.probe.url,'https://chatgpt.com/c/new-owned');
});

test('held intercepted request is explicitly aborted before absence can be returned',async()=>{
  const f=fixture({held:true});let unblock;const held=new Promise(resolve=>{unblock=resolve;});
  let error;
  try{await f.browser.submitIntent({session:f.session,prompt:'continue',profileOperations:[],intent:{id:'intent'},conversationUrl:'https://chatgpt.com/c/owned',
    guard:()=>true,onRequest:()=>held});}catch(e){error=e;}
  assert.ok(error);assert.equal(error.absenceProof,'INTERCEPTED_REQUEST_ABORTED');
  unblock();await new Promise(resolve=>setTimeout(resolve,0));
  assert.equal(f.continued(),0);assert.ok(f.failures.includes('paused-post'));
  const r=await f.browser.readSubmission({session:f.session,prompt:'continue',conversationUrl:'https://chatgpt.com/c/owned',
    intent:{baseline:{userCount:1,userMessageId:'old-message'},absence_proof:error.absenceProof,released:error.released}});
  assert.equal(r.state,'ABSENT');
});

test('initial submission refuses an existing canonical conversation before touching the composer',async()=>{
 const f=fixture();await assert.rejects(f.browser.submitIntent({session:f.session,prompt:'test',kind:'initial',intent:{id:'i'},guard:()=>true,profileOperations:[]}),/existing conversation/);
});
test('prepare rejects existing conversation when new chat control is missing',async()=>{
 let staged=0,closed=0;
 const session={close(){},async call(method,p={}){
  if(method!=='Runtime.evaluate')return {};
  if(p.expression.includes("status:'MISSING'"))return {result:{value:{status:'MISSING'}}};
  if(p.expression.includes('const expected='))staged++;
  return {result:{value:{url:'https://chatgpt.com/c/other-owned',readyState:'complete',composer:true}}};
 }};
 const chromium={createTarget:async()=>({id:'new-target'}),connectTarget:async()=>session,closeTarget:async()=>{closed++;}};
 const browser=new ChatGptBrowser(chromium,{navigationTimeoutMs:100});
 await assert.rejects(browser.prepare({projectUrl:'https://chatgpt.com/',prompt:'test'}),/new conversation/);
 assert.equal(staged,0);assert.equal(closed,1);
});
