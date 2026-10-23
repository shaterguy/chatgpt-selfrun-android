import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
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
function fixture(t,{native=()=>Promise.resolve(new Response('')),RequestType=Request}={}) {
  const audit={},events={},timers=new Set(),location={href:url,origin:'https://chatgpt.com'};
  class XHR {open(){} send(){}}
  const window={fetch:native,addEventListener:(name,fn)=>events[name]=fn,history:{
    pushState(_state,_title,next){location.href=new URL(next,location.href).href;},replaceState(){},
  }};
  let source=buildPageRuntimeSource();
  source=insert(source,'let current=null,sequence=0;','audit.receipt=()=>current;');
  source=insert(source,"const state={operations:null,last:{ok:false,reason:'not_configured'}};",'audit.profile=state;');
  source=insert(source,"let ownership=null,active=false,revoked=false,armedKey=null,entryTransition=null;",
    'audit.page=()=>({ownership,active,revoked,armedKey,entryTransition,profileEngine,receiptObserver,profileFetch,receiptFetch,profileOpen,profileSend});');
  vm.runInNewContext(source,{audit,window,location,XMLHttpRequest:XHR,Request:RequestType,Response,URL,TextEncoder,TextDecoder,Uint8Array,crypto,
    setTimeout:(fn,ms)=>{const id=setTimeout(fn,ms);id.unref();timers.add(id);return id;},clearTimeout:id=>{timers.delete(id);clearTimeout(id);}});
  const run=(operation,payload={},owner=nonce)=>window.__selfrunExtensionPage.run(operation,payload,owner,location.href,Date.now()+5000,'selfrun-static-test-key');
  t.after(()=>{for(const id of timers)clearTimeout(id);});
  const start=()=>{run('installProfile');run('profileConfigure',{operations});run('installReceipt');run('receiptArm',{key});};
  return{audit,window,run,start,events,location,send:()=>window.fetch('https://chatgpt.com/backend-api/conversation',{method:'POST',body:body()})};
}

test('ordinary receipt retirement releases current and scrubs retained entry while permitting the next receipt',async t=>{
  const headers=deferred(),f=fixture(t,{native:()=>headers.promise});f.start();
  const sending=f.send();await tick();const entry=f.audit.receipt();
  assert.equal(entry.prompt,key.prompt);assert.equal(entry.message_id,userId);
  f.run('receiptRetire',{key});
  assert.equal(f.audit.receipt(),null,'retire must release its current entry');
  for(const value of [key.prompt,key.intent_id,key.generation,userId,'owned'])assert.equal(JSON.stringify(entry).includes(value),false,'retired entry retains '+value);
  assert.equal(f.audit.page().armedKey,null);
  const next={...key,intent_id:'next',generation:'next-generation',prompt:'next prompt'};
  f.run('receiptArm',{key:next});assert.equal(f.run('receiptSnapshot',{key:next}).intent_id,'next');
  let cloned=false;headers.resolve({ok:true,status:200,clone(){cloned=true;throw Error('late response must remain untouched');}});await sending;await tick();
  assert.equal(cloned,false);assert.equal(f.run('receiptSnapshot',{key:next}).message_id,null);
});

test('terminalDispose clears owned private runtime, blocks rearm and leaves a live sibling usable',async t=>{
  const headers=deferred(),seen=[],f=fixture(t,{native:function(...args){seen.push({receiver:this,args});return headers.promise;}}),sibling=fixture(t);
  f.start();sibling.start();const sending=f.send();await tick();
  const entry=f.audit.receipt(),old=f.audit.page(),profile=f.audit.profile;
  assert.throws(()=>f.run('terminalDispose',{},'different-owner-123456'),/owner mismatch/i);
  assert.equal(f.audit.receipt(),entry,'a different owner cannot discard this execution');
  assert.equal(f.run('terminalDispose'),true);
  const state=f.audit.page();assert.equal(state.active,false);assert.equal(state.revoked,true);
  for(const name of ['ownership','armedKey','entryTransition','profileEngine','receiptObserver','profileFetch','receiptFetch','profileOpen','profileSend'])assert.equal(state[name],null,name+' must be released');
  assert.equal(f.audit.receipt(),null);assert.equal(profile.operations,null);
  for(const value of [key.prompt,key.intent_id,key.generation,userId,'owned'])assert.equal(JSON.stringify(entry).includes(value),false,'disposed entry retains '+value);
  assert.throws(()=>old.receiptObserver.arm(key),/disposed/i);
  assert.throws(()=>old.profileEngine.configure([['set','model','late'],['remove','thinking_effort'],['remove','conversation_origin'],['remove','service_tier']]),/disposed/i);
  assert.throws(()=>f.run('receiptArm',{key}),/ownership|disposed/i);
  let cloned=false;const response={ok:true,status:200,clone(){cloned=true;throw Error('late response must remain untouched');}};
  headers.resolve(response);assert.equal(await sending,response);await tick();assert.equal(cloned,false);assert.equal(f.audit.receipt(),null);assert.equal(profile.operations,null);
  const receiver={},argument={private:'not inspected'};assert.equal(f.window.fetch.call(receiver,argument),headers.promise);assert.equal(seen.at(-1).receiver,receiver);assert.equal(seen.at(-1).args[0],argument);
  assert.equal(sibling.run('receiptSnapshot',{key}).intent_id,key.intent_id);assert.equal(sibling.audit.page().active,true);
  sibling.run('receiptRetire',{key});sibling.run('receiptArm',{key:{...key,generation:'sibling-next'}});
  assert.equal(sibling.run('receiptSnapshot',{key:{...key,generation:'sibling-next'}}).generation,'sibling-next');
});

test('terminal disposal clears profile state after native forwarding without a profile body wait',async t=>{
  const read=deferred(),nativeCalls=[];
  class SlowRequest {constructor(input,init){this.url=typeof input==='string'?input:input.url;this.method=init?.method||input.method;}clone(){return{text:()=>read.promise};}}
  const f=fixture(t,{RequestType:SlowRequest,native:(...args)=>{nativeCalls.push(args);return Promise.resolve(new Response(''));}});
  f.start();const sending=f.send();await tick();
  f.run('terminalDispose');read.resolve(body());await sending;
  assert.equal(nativeCalls.length,1);assert.equal(f.audit.profile.operations,null);assert.equal(f.audit.receipt(),null);
});

test('terminal disposal does not require a still-armed receipt key',t=>{
  const f=fixture(t);f.start();f.run('receiptRetire',{key});
  assert.equal(f.audit.page().armedKey,null);assert.equal(f.audit.page().active,true);
  assert.equal(f.run('terminalDispose'),true);assert.equal(f.audit.profile.operations,null);
  assert.equal(f.audit.page().ownership,null);assert.equal(f.audit.page().revoked,true);
});

for(const cause of ['pagehide','repurpose'])test(cause+' revokes and clears the same private runtime',t=>{
  const f=fixture(t);f.start();const entry=f.audit.receipt();
  if(cause==='pagehide')f.events.pagehide();else f.window.history.pushState({},'', '/c/unrelated');
  assert.equal(f.audit.page().revoked,true);assert.equal(f.audit.page().ownership,null);
  assert.equal(f.audit.receipt(),null);assert.equal(entry.prompt,null);assert.equal(f.audit.profile.operations,null);
  assert.throws(()=>f.run('receiptArm',{key}),/ownership/i);
});

function contentFixture() {
  const audit={},events={},sent=[],observers=[];let receiver;
  class Observer {constructor(changed){this.changed=changed;this.disconnected=false;observers.push(this);}observe(){}disconnect(){this.disconnected=true;}}
  let source=contentRuntime.toString();
  source=insert(source,'let active=false,dirty=true,cache=null,observer=null;','audit.state=()=>({active,dirty,cache,observer});');
  const chrome={runtime:{id:'fixture-extension',getURL:path=>'chrome-extension://fixture-extension/'+path,sendMessage:async message=>{sent.push(message);},onMessage:{addListener:fn=>receiver=fn}}};
  vm.runInNewContext('('+source+')(snapshot,()=>({}));',{audit,chrome,crypto,MutationObserver:Observer,document:{readyState:'complete'},location:{href:url},addEventListener:(name,fn)=>events[name]=fn,snapshot:()=>({privateBody:'cached private observation'})});
  const nonce=sent[0].nonce,sender={id:chrome.runtime.id,url:chrome.runtime.getURL('controller.html')};
  const send=kind=>{let result;receiver({kind,nonce,deadlineAt:Date.now()+5000},sender,value=>result=value);return result;};
  send('selfrun.content.activate');send('selfrun.content.snapshot');
  return{audit,events,observers,send,sent};
}
for(const cause of ['deactivate','pagehide'])test('content '+cause+' clears cached observation and disconnects its observer',()=>{
  const f=contentFixture();assert.equal(f.audit.state().cache.privateBody,'cached private observation');
  if(cause==='deactivate')assert.equal(f.send('selfrun.content.deactivate').ok,true);else f.events.pagehide();
  assert.equal(f.audit.state().active,false);assert.equal(f.audit.state().cache,null);assert.equal(f.audit.state().observer,null);assert.equal(f.audit.state().dirty,true);assert.equal(f.observers[0].disconnected,true);
  const before=f.sent.length;f.observers[0].changed();assert.equal(f.sent.length,before,'late mutation callback cannot report a retired observation');
});

test('terminal content disposal refuses late activation and cannot refill its observation cache',()=>{
  const f=contentFixture();f.send('selfrun.content.dispose');
  assert.equal(f.send('selfrun.content.activate')?.ok,false,'late activation must be rejected');
  f.send('selfrun.content.snapshot');
  assert.equal(f.audit.state().active,false);assert.equal(f.audit.state().cache,null);
  assert.equal(f.audit.state().observer,null);assert.equal(f.observers.length,1);assert.equal(f.observers[0].disconnected,true);
  assert.equal(f.send('selfrun.content.dispose').ok,true,'terminal disposal is idempotent');
});

test('ordinary content deactivation still allows nonterminal reactivation',()=>{
  const f=contentFixture();f.send('selfrun.content.deactivate');
  assert.equal(f.send('selfrun.content.activate').ok,true);assert.equal(f.audit.state().active,true);
  assert.equal(f.send('selfrun.content.snapshot').ok,true);assert.equal(f.audit.state().cache.privateBody,'cached private observation');
  assert.equal(f.observers.length,2);assert.equal(f.observers[1].disconnected,false);
});
