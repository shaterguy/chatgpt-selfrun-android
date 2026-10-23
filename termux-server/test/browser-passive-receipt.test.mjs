import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import * as browserModule from '../src/browser/chatgpt.mjs';

const userId='92dd19de-1242-4d48-8f34-413e8ed42aa4';
const expected={intent_id:'intent-1',generation:'generation-1',prompt:'계속 🧪\nexact text',conversation_id:'owned'};
const body=(patch={})=>({conversation_id:'owned',messages:[{id:userId,author:{role:'user'},content:{parts:[expected.prompt]}}],...patch});
const marker=(patch={})=>({type:'message_marker',marker:'cot_token',event:'first',conversation_id:'owned',...patch});
const block=value=>'data: '+JSON.stringify(value)+'\n\n';
const tick=()=>new Promise(resolve=>setImmediate(resolve));
function fixture({status=200,profile=false,legacy=false}={}) {
  const pending=[],calls=[],streams=[],cancellations=[];
  const timers=new Set();
  class XHR {open(){} send(){}}
  const window={fetch:function(input,init){
    calls.push({input,init});
    let controller;
    const response=new Response(new ReadableStream({start(c){controller=c;},cancel(reason){cancellations.push(reason);}}),{status,headers:{'Content-Type':'text/event-stream'}});
    streams.push(controller);pending.push(response);return Promise.resolve(response);
  }};
  const context=vm.createContext({window,location:{href:'https://chatgpt.com/c/owned',origin:'https://chatgpt.com'},Request,Response,XMLHttpRequest:XHR,URL,TextDecoder,Uint8Array,
    setTimeout:(fn,ms)=>{const id=setTimeout(fn,ms);id.unref();timers.add(id);return id;},clearTimeout:id=>{timers.delete(id);clearTimeout(id);},crypto});
  if(profile) {
    vm.runInContext(browserModule.requestProfileDocumentStartSource(),context);
    window.__selfrunRequestProfileEngine.configure([['set','model','new-model'],['set','thinking_effort','max'],['remove','conversation_origin'],['remove','service_tier']]);
  }
  const retired=[];let legacyEngine=null;
  if(legacy) {
    const downstream=window.fetch;window.fetch=function(...args){return Reflect.apply(downstream,this,args);};
    legacyEngine={version:'selfrun-passive-receipt-v1',arm:()=>true,snapshot:()=>null,retire:key=>{retired.push(key);return true;}};
    window.__selfrunReceiptObserver=legacyEngine;
  }
  assert.equal(typeof browserModule.receiptObserverDocumentStartSource,'function','passive receipt observer is available');
  const install=()=>vm.runInContext(browserModule.receiptObserverDocumentStartSource(),context);
  install();
  const engine=window.__selfrunReceiptObserver;
  engine.arm(expected);
  return {window,engine,calls,pending,streams,cancellations,install,legacyEngine,retired,
    arm:value=>engine.arm({...expected,...value}),
    snapshot:()=>engine.snapshot({intent_id:expected.intent_id,generation:expected.generation}),
    send:async(value=body(),input)=>window.fetch(input||'https://chatgpt.com/backend-api/f/conversation',{method:'POST',body:JSON.stringify(value)}),
    emit:async(value,index=0)=>{streams[index].enqueue(new TextEncoder().encode(value));await tick();await tick();},
    close:()=>{engine.retire({intent_id:expected.intent_id,generation:expected.generation});for(const t of timers)clearTimeout(t);for(const s of streams)try{s.close();}catch{}},
  };
}

test('passive observer returns original response without waiting for body and accepts early actual marker',async t=>{
  const f=fixture();t.after(f.close);
  const response=await f.send();
  assert.equal(response,f.pending[0]);
  assert.equal(response.bodyUsed,false);
  assert.equal(f.snapshot().ack,null);
  assert.equal(f.snapshot().released,true);
  await f.emit(block(marker()));
  assert.equal(f.snapshot().ack,'cot_token');assert.equal(f.snapshot().message_id,userId);
  assert.equal(f.calls.length,1);assert.equal(response.bodyUsed,false);
  assert.equal('prompt' in f.snapshot(),false);
});

test('incremental decoder handles split UTF-8, data prefix, CRLF and blank delimiters',async t=>{
  const f=fixture();t.after(f.close);await f.send();
  const data=new TextEncoder().encode('data: '+JSON.stringify(marker({note:'한글 🧪'}))+'\r\n\r\n');
  for(const byte of data){f.streams[0].enqueue(Uint8Array.of(byte));await tick();}
  await tick();assert.equal(f.snapshot().ack,'cot_token');
});
for(const event of ['user_visible_token','final_channel_token'])test('recognized marker '+event,async t=>{
  const f=fixture();t.after(f.close);await f.send();await f.emit(block(marker({marker:event})));assert.equal(f.snapshot().ack,event);
});
for(const wrapped of [false,true])test('known assistant message shape '+wrapped,async t=>{
  const f=fixture();t.after(f.close);await f.send();
  const message={id:'assistant-1',author:{role:'assistant'},channel:'final',content:{parts:['응답']}};
  await f.emit(block(wrapped?{v:{message},conversation_id:'owned'}:{type:'message_start',message,conversation_id:'owned'}));
  assert.equal(f.snapshot().ack,'assistant_message');
});
for(const event of [{type:'heartbeat'},{type:'message_marker',marker:'cot_token',event:'last'},{type:'error',message:'failed'},{error:'failed'}, {arbitrary:true,conversation_id:'owned'},'[DONE]'])test('non-ack event remains UNKNOWN '+JSON.stringify(event),async t=>{
  const f=fixture();t.after(f.close);await f.send();await f.emit(typeof event==='string'?'data: '+event+'\n\n':block(event));assert.equal(f.snapshot().ack,null);
});
test('HTTP error cannot acknowledge even a marker body',async t=>{const f=fixture({status:500});t.after(f.close);await f.send();await f.emit(block(marker()));assert.equal(f.snapshot().ack,null);});
for(const patch of [
  {messages:[{id:userId,author:{role:'user'},content:{parts:[expected.prompt+'!']}}]},
  {messages:[{id:'not-a-uuid',author:{role:'user'},content:{parts:[expected.prompt]}}]},
  {conversation_id:'different'},
  {messages:[...body().messages,...body().messages]},
  {messages:[{id:userId,author:{role:'user'},content:{parts:[expected.prompt,{image:'not-text'}]}}]},
])test('outgoing mismatch cannot become ack '+JSON.stringify(patch).slice(0,100),async t=>{
  const f=fixture();t.after(f.close);await f.send(body(patch));await f.emit(block(marker()));assert.equal(f.snapshot().ack,null);
});
test('response conversation mismatch invalidates its acknowledgement',async t=>{const f=fixture();t.after(f.close);await f.send();await f.emit(block(marker({conversation_id:'other'})));assert.equal(f.snapshot().ack,null);assert.ok(f.snapshot().invalid);});
test('initial semantic ack waits for canonical response identity, then retains metadata',async t=>{
  const f=fixture();t.after(f.close);f.arm({generation:'initial',conversation_id:''});
  await f.send(body({conversation_id:undefined}));await f.emit(block(marker({conversation_id:undefined})));
  let s=f.engine.snapshot({intent_id:expected.intent_id,generation:'initial'});assert.equal(s.ack,'cot_token');assert.equal(s.conversation_id,'');
  await f.emit(block({conversation_id:'created'}));s=f.engine.snapshot({intent_id:expected.intent_id,generation:'initial'});assert.equal(s.conversation_id,'created');
});
test('late old response after rearm cannot acknowledge successor',async t=>{
  const f=fixture();t.after(f.close);await f.send();f.arm({generation:'generation-2'});await f.send();
  await f.emit(block(marker()),0);assert.equal(f.engine.snapshot({intent_id:expected.intent_id,generation:'generation-2'}).ack,null);
  await f.emit(block(marker()),1);assert.equal(f.engine.snapshot({intent_id:expected.intent_id,generation:'generation-2'}).ack,'cot_token');
  assert.equal(f.snapshot(),null);
});
test('duplicate eligible POST invalidates receipt even when first is positive',async t=>{
  const f=fixture();t.after(f.close);await f.send();await f.send();await f.emit(block(marker()));await f.emit(block(marker()),1);
  assert.equal(f.snapshot().ack,null);assert.ok(f.snapshot().invalid);
});
test('reinstallation and identical arm preserve observer identity, native model and body fields',async t=>{
  const f=fixture({profile:true});t.after(f.close);const wrapped=f.window.fetch,profile=f.window.__selfrunRequestProfileEngine;
  f.install();f.arm({});assert.equal(f.window.fetch,wrapped);assert.equal(f.window.__selfrunRequestProfileEngine,profile);
  const original=body({model:'old',thinking_effort:'low',conversation_origin:'chat',service_tier:'default',parent_message_id:'parent-uuid',metadata:{keep:true}});
  await f.send(original);await f.emit(block(marker()));
  const sent=JSON.parse(await new Request(f.calls[0].input,f.calls[0].init).text());
  assert.equal(sent.model,'old');assert.equal(sent.thinking_effort,'low');assert.equal(sent.conversation_origin,'chat');assert.equal(sent.service_tier,'default');
  for(const key of ['messages','conversation_id','parent_message_id','metadata'])assert.deepEqual(sent[key],original[key]);
  assert.equal(f.snapshot().ack,'cot_token');assert.equal(f.calls.length,1);
});
test('observer passes input/init untouched and parser errors do not reject original response',async t=>{
  const f=fixture();t.after(f.close);const input='https://chatgpt.com/backend-api/conversation';const init={method:'POST',body:JSON.stringify(body())};
  const response=await f.window.fetch(input,init);assert.equal(f.calls[0].input,input);assert.equal(f.calls[0].init,init);
  await f.emit('data: {malformed}\n\n');assert.equal(f.snapshot().ack,null);assert.equal(response.bodyUsed,false);
});

test('outgoing message identity is observed before response headers, without fabricating ack',async t=>{
  const f=fixture();t.after(f.close);
  // A fresh observer wrapping a downstream fetch that has not resolved any response yet.
  let resolveHeaders;
  const window={fetch:()=>new Promise(resolve=>{resolveHeaders=resolve;})};
  const context=vm.createContext({window,location:{href:'https://chatgpt.com/c/owned',origin:'https://chatgpt.com'},Request,URL,TextDecoder,Uint8Array,setTimeout,clearTimeout});
  vm.runInContext(browserModule.receiptObserverDocumentStartSource(),context);window.__selfrunReceiptObserver.arm(expected);
  const sending=window.fetch('https://chatgpt.com/backend-api/f/conversation',{method:'POST',body:JSON.stringify(body())});
  await tick();
  const observed=window.__selfrunReceiptObserver.snapshot(expected);
  assert.equal(observed.message_id,userId);assert.equal(observed.ack,null);assert.equal(observed.released,true);
  resolveHeaders(new Response('',{status:200}));await sending;window.__selfrunReceiptObserver.retire(expected);
});
test('oversize unframed SSE is bounded and cannot become an ack',async t=>{
  const f=fixture();t.after(f.close);await f.send();await f.emit('data: '+JSON.stringify({note:'x'.repeat(70000)}));
  assert.equal(f.snapshot().ack,null);assert.equal(f.snapshot().invalid,'event_bound');
});
test('retirement ignores late acknowledgement and does not cancel the original branch',async t=>{
  const f=fixture();t.after(f.close);const response=await f.send();
  f.engine.retire(expected);await f.emit(block(marker()));
  assert.equal(f.snapshot(),null);assert.equal(response.bodyUsed,false);assert.equal(f.cancellations.length,0);
  const reader=response.body.getReader();const received=await reader.read();assert.equal(new TextDecoder().decode(received.value),block(marker()));
  void reader.cancel();
});

for(const status of [403,429,500])for(const endpoint of ['conversation','f/conversation'])test(`HTTP ${status} keeps numeric status and ${endpoint} category only`,async t=>{
  const f=fixture({status});t.after(f.close);
  await f.send(body(),'https://chatgpt.com/backend-api/'+endpoint+'?private=secret');await tick();
  const receipt=f.snapshot();assert.equal(receipt.invalid,'http_error');assert.equal(receipt.ack,null);
  assert.equal(receipt.http_status,status);assert.equal(receipt.endpoint_category,endpoint==='conversation'?'conversation':'f_conversation');
  assert.doesNotMatch(JSON.stringify(receipt),/secret|private|https|backend-api|headers|prompt/);
});


test('a legacy page observer upgrades in place without reloading or another fetch',async t=>{
  const f=fixture({status:429,legacy:true});t.after(f.close);
  assert.notEqual(f.engine,f.legacyEngine,'v1 early return must not bypass new diagnostics');
  const input='https://chatgpt.com/backend-api/f/conversation',init={method:'POST',body:JSON.stringify(body())};
  await f.window.fetch(input,init);await tick();
  assert.equal(f.calls.length,1);assert.equal(f.calls[0].input,input);assert.equal(f.calls[0].init,init);
  assert.equal(f.snapshot().http_status,429);
  assert.equal(f.snapshot().endpoint_category,'f_conversation');assert.equal(f.snapshot().invalid,'http_error');
  assert.equal(f.retired.length,1);assert.equal(f.retired[0].generation,expected.generation);
  assert.equal(f.install(),true);assert.equal(f.window.__selfrunReceiptObserver,f.engine);
});
