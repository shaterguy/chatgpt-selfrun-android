import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {ServerActionSession} from '../src/browser/server-action-session.mjs';
import {ChatGptBrowser} from '../src/browser/chatgpt.mjs';
import {buildPageRuntimeSource,contentRuntime} from '../src/browser/extension-page-source.mjs';

const nonce='fixture-content-nonce-123456',url='https://chatgpt.com/c/owned';
const key={intent_id:'private-intent',generation:'private-generation',prompt:'private prompt body',conversation_id:'owned'};
const userId='92dd19de-1242-4d48-8f34-413e8ed42aa4';
const operations=[{op:'SET',path:'model',value:'private-model'},{op:'SET',path:'thinking_effort',value:'max'},
  {op:'REMOVE',path:'conversation_origin'},{op:'REMOVE',path:'service_tier'}];
const body=(prompt=key.prompt)=>JSON.stringify({conversation_id:'owned',messages:[{id:userId,author:{role:'user'},content:{parts:[prompt]}}]});
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};};
const tick=()=>new Promise(resolve=>setImmediate(resolve));

// Read retained closure state without adding a production diagnostic surface or
// replacing the generated operations under test. Missing anchors fail loudly.
function insert(source,anchor,addition){assert.equal(source.split(anchor).length,2,'unique generated-source audit anchor');return source.replace(anchor,anchor+addition);}
function fixture(t,{native=()=>Promise.resolve(new Response('')),RequestType=Request,initialUrl=url,armKey=key}={}) {
  const audit={},events={},timers=new Set(),location={href:initialUrl,origin:'https://chatgpt.com'};
  class XHR {open(){} send(){}}
  const window={fetch:native,addEventListener:(name,fn)=>events[name]=fn,history:{
    pushState(_state,_title,next){location.href=new URL(next,location.href).href;},replaceState(){},
  }};
  let source=buildPageRuntimeSource();
  source=insert(source,'let current=null,sequence=0;','audit.receipt=()=>current;');
  source=insert(source,"const state={operations:null,last:{ok:false,reason:'not_configured'}};",'audit.profile=state;');
  source=insert(source,"let ownership=null,active=false,revoked=false,armedKey=null,entryTransition=null;",
    'audit.page=()=>({ownership,active,revoked,armedKey,entryTransition,profileEngine,receiptObserver,profileFetch,receiptFetch,profileOpen,profileSend});');
  vm.runInNewContext(source,{audit,window,location,XMLHttpRequest:XHR,Request:RequestType,Response,URL,TextEncoder,TextDecoder,Uint8Array,crypto,Date,
    setTimeout:(fn,ms)=>{const id=setTimeout(fn,ms);id.unref();timers.add(id);return id;},clearTimeout:id=>{timers.delete(id);clearTimeout(id);}});
  const run=(operation,payload={},owner=nonce)=>window.__selfrunExtensionPage.run(operation,payload,owner,location.href,Date.now()+5000,'selfrun-static-test-key');
  t.after(()=>{for(const id of timers)clearTimeout(id);});
  const start=()=>{run('installProfile');run('profileConfigure',{operations});run('installReceipt');run('receiptArm',{key:armKey});};
  return{audit,window,run,start,events,location,send:()=>window.fetch('https://chatgpt.com/backend-api/conversation',{method:'POST',body:JSON.stringify({messages:[{id:userId,author:{role:'user'},content:{parts:[armKey.prompt]}}]})})};
}


test('initial owned POST survives optimistic WEB route during body read without claiming canonical receipt',async t=>{
 const read=deferred(),calls=[];
 class SlowRequest extends Request {clone(){const original=super.clone();return {text:()=>read.promise.then(()=>original.text())};}}
 const f=fixture(t,{RequestType:SlowRequest,initialUrl:'https://chatgpt.com/g/test/project',armKey:{...key,conversation_id:null},native:(input,init)=>{calls.push(new Request(input,init));return Promise.resolve(new Response(''));}});
 f.start();const sending=f.send();const outcome=sending.then(()=>null,e=>e);await tick();
 f.window.history.pushState({},'', '/c/WEB:00000000-0000-4000-8000-000000000001');
 read.resolve();const error=await outcome;
 assert.equal(error,null,'owned optimistic transition must not dispose in-flight profile');
 assert.equal(calls.length,1);assert.equal(Object.hasOwn(JSON.parse(await calls[0].text()),'model'),false);
 assert.ok(!f.run('receiptSnapshot',{key:{...key,conversation_id:null}}).conversation_id);
});

const entry='https://chatgpt.com/g/test/project',temp='/c/WEB:00000000-0000-4000-8000-000000000001';
for(const cause of ['second-WEB','return-entry','other-origin','pagehide','retire','rearm','expire'])test((cause==='expire'?'optimistic transition survives elapsed route age: ':'route revocation after native forwarding clears runtime for ')+cause,async t=>{
 let now=1700000000000;t.mock.method(Date,'now',()=>now);
 const read=deferred(),calls=[];class SlowRequest extends Request{clone(){const r=super.clone();return{text:()=>read.promise.then(()=>r.text())};}}
 const armKey={...key,conversation_id:null};const f=fixture(t,{RequestType:SlowRequest,initialUrl:entry,armKey,native:r=>{calls.push(r);return Promise.resolve(new Response(''));}});
 f.start();const result=f.send().then(()=>null,e=>e);await tick();f.window.history.pushState({},'',temp);
 if(cause==='second-WEB')f.window.history.pushState({},'','/c/WEB:00000000-0000-0000-0000-000000000000');
 if(cause==='return-entry')f.window.history.pushState({},'',entry);
 if(cause==='other-origin')f.window.history.pushState({},'','https://example.com/c/other');
 if(cause==='pagehide')f.events.pagehide();
 if(cause==='retire')f.run('receiptRetire',{key:armKey});
 if(cause==='rearm')assert.throws(()=>f.run('receiptArm',{key:{...armKey,generation:'another'}}),/generation|disposed/);
 if(cause==='expire'){now+=30001;f.window.history.pushState({},'',temp);}
 read.resolve();if(cause==='expire'){assert.equal(await result,null);assert.equal(calls.length,1);assert.equal(f.audit.page().revoked,false);}else{assert.equal(await result,null);assert.equal(calls.length,1);assert.equal(f.audit.page().revoked,true);}
});
test('only the first canonical route binds after optimistic initial send',async t=>{
 const f=fixture(t,{initialUrl:entry,armKey:{...key,conversation_id:null}});f.start();
 const sent=f.send();f.window.history.pushState({},'',temp);await sent;
 assert.equal(f.audit.page().revoked,false);f.window.history.pushState({},'','/c/saved-canonical');
 assert.equal(f.audit.page().ownership.url,'https://chatgpt.com/c/saved-canonical');
 f.window.history.pushState({},'','/c/another-canonical');assert.equal(f.audit.page().revoked,true);
});
test('unrelated fetch and armed entry alone cannot authorize optimistic navigation',async t=>{
 const f=fixture(t,{initialUrl:entry,armKey:{...key,conversation_id:null}});f.start();
 await f.window.fetch('https://chatgpt.com/backend-api/models');f.window.history.pushState({},'',temp);
 assert.equal(f.audit.page().revoked,true);
});
test('an owned durable conversation cannot transfer into optimistic route',async t=>{
 const f=fixture(t);f.start();const outcome=f.send().then(()=>null,e=>e);f.window.history.pushState({},'',temp);
 await outcome;assert.equal(f.audit.page().revoked,true);
});

test('elapsed optimistic route age does not block a pending native fetch',async t=>{
 let now=1700000000000;t.mock.method(Date,'now',()=>now);const read=deferred(),calls=[];
 class SlowRequest extends Request{clone(){const r=super.clone();return{text:()=>read.promise.then(()=>r.text())};}}
 const f=fixture(t,{RequestType:SlowRequest,initialUrl:entry,armKey:{...key,conversation_id:null},native:r=>{calls.push(r);return Promise.resolve(new Response(''));}});
 f.start();const outcome=f.send().then(()=>null,e=>e);await tick();f.window.history.pushState({},'',temp);now+=30001;read.resolve();
 assert.equal(await outcome,null);assert.equal(calls.length,1);assert.equal(f.audit.page().revoked,false);
});
for(const endpoint of ['/backend-api/conversation/','/backend-api/CONVERSATION','/backend-api/f/conversation'])test('existing eligible route normalization '+endpoint,async t=>{
 const calls=[];const f=fixture(t,{initialUrl:entry,armKey:{...key,conversation_id:null},native:r=>{calls.push(r);return Promise.resolve(new Response(''));}});f.start();
 const sent=f.window.fetch('https://chatgpt.com'+endpoint,{method:'POST',body:JSON.stringify({messages:[{id:userId,author:{role:'user'},content:{parts:[key.prompt]}}]})});
 f.window.history.pushState({},'',temp);await sent;assert.equal(calls.length,1);assert.equal(f.audit.page().revoked,false);
});

test('elapsed route age does not invent failure for separately invoked page fetches',async t=>{
 let now=1700000000000;t.mock.method(Date,'now',()=>now);const read=deferred(),calls=[];
 class SlowRequest extends Request{clone(){const r=super.clone();return{text:()=>read.promise.then(()=>r.text())};}}
 const f=fixture(t,{RequestType:SlowRequest,initialUrl:entry,armKey:{...key,conversation_id:null},native:r=>{calls.push(r);return Promise.resolve(new Response(''));}});
 f.start();const first=f.send().then(()=>null,e=>e);await tick();f.window.history.pushState({},'',temp);
 now+=20000;const second=f.send().then(()=>null,e=>e);now+=10001;read.resolve();
 assert.equal(await first,null);assert.equal(await second,null);assert.equal(calls.length,2);assert.equal(f.audit.page().revoked,false);
});
for(const cause of ['body-unreadable','native-rejection'])test('initial transition revokes on '+cause,async t=>{
 class Unreadable extends Request{clone(){return{text:()=>Promise.reject(new Error('unreadable'))};}}
 let calls=0;const f=fixture(t,{RequestType:cause==='body-unreadable'?Unreadable:Request,initialUrl:entry,armKey:{...key,conversation_id:null},native:()=>{calls++;return Promise.reject(new Error('native rejected'));}});
 f.start();const result=f.send().then(()=>null,e=>e);f.window.history.pushState({},'',temp);
 assert.ok(await result);assert.equal(f.audit.page().revoked,true);assert.equal(calls,1);
});
test('page request and strict action/readback seam keep WEB unknown until canonical proof',async t=>{
 let calls=0;const armKey={...key,conversation_id:null};
 const response='data: '+JSON.stringify({type:'message_marker',event:'first',marker:'cot_token',conversation_id:'saved-canonical'})+'\n\n';
 const f=fixture(t,{initialUrl:entry,armKey,native:()=>{calls++;return Promise.resolve(new Response(response));}});
 f.start();const sent=f.send();f.window.history.pushState({},'',temp);await sent;for(let i=0;i<5;i++)await tick();
 const action=new ServerActionSession({session:{close(){}},installationKey:'selfrun-static-test-key',initialUrl:entry,allowedOrigins:['https://chatgpt.com']});
 const session={transport:'extension',run:async(operation,payload)=>{
   if(operation==='progress')return{url:f.location.href,readyState:'complete',userCount:1,userMessageId:userId};
   assert.equal(operation,'receiptSnapshot');action.verifyUrl(f.location.href);return f.run(operation,payload);
 }};
 const browser=new ChatGptBrowser(null,{navigationTimeoutMs:5}),intent={id:key.intent_id,receipt_generation:key.generation,prompt:key.prompt,sends:1,baseline:{userCount:0}};
 const read=()=>browser.readSubmission({session,intent,prompt:key.prompt,conversationUrl:'',guard:()=>true,onRequest:async evidence=>Object.assign(intent,evidence)});
 assert.equal((await read()).state,'UNKNOWN');assert.equal(action.boundUrl,'https://chatgpt.com'+temp);assert.equal(calls,1);
 f.window.history.pushState({},'','/c/saved-canonical');
 assert.equal((await read()).state,'CONFIRMED');assert.equal(action.boundUrl,'https://chatgpt.com/c/saved-canonical');assert.equal(calls,1);
});

test('native request is forwarded once before a later arm change',async t=>{
 const read=deferred(),calls=[];class SlowRequest extends Request{clone(){const r=super.clone();return{text:()=>read.promise.then(()=>r.text())};}}
 const armKey={...key,conversation_id:null};const f=fixture(t,{RequestType:SlowRequest,initialUrl:entry,armKey,native:r=>{calls.push(r);return Promise.resolve(new Response(''));}});
 f.start();const result=f.send().then(()=>null,e=>e);await tick();f.window.history.pushState({},'',temp);
 f.window.history.pushState({},'','/c/saved-canonical');f.run('receiptRetire',{key:armKey});f.run('receiptArm',{key:{...armKey,generation:'new-generation',conversation_id:'saved-canonical'}});
 read.resolve();assert.equal(await result,null);assert.equal(calls.length,1);
});
