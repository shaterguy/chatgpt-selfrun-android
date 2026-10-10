import test from 'node:test';
import assert from 'node:assert/strict';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';

const messageId='92dd19de-1242-4d48-8f34-413e8ed42aa4';
const receipt=()=>({version:1,intent_id:'intent-1',generation:'generation-1',request_id:'generation-1:1',message_id:messageId,conversation_id:'owned',ack:'cot_token',invalid:null,released:true});
function fixture() {
  const f={calls:[],saved:[],probe:{url:'https://chatgpt.com/c/owned',composer:false,readyState:'loading',userCount:0,assistantCount:0},receipt:receipt(),allowed:true};
  f.intent={id:'intent-1',receipt_generation:'generation-1',prompt:'exact',sends:1,baseline:{userCount:0}};
  f.session={async call(method,params={}){
    f.calls.push(method);
    assert.equal(method,'Runtime.evaluate','readback must never reload, navigate or create a target');
    if(params.expression.includes('__selfrunReceiptObserver'))return {result:{value:f.receipt}};
    return {result:{value:f.probe}};
  }};
  f.browser=new ChatGptBrowser(null,{navigationTimeoutMs:10});
  f.read=extra=>f.browser.readSubmission({session:f.session,intent:f.intent,prompt:'exact',conversationUrl:'https://chatgpt.com/c/owned',guard:()=>f.allowed,
    onRequest:async evidence=>{f.saved.push(structuredClone(evidence));Object.assign(f.intent,evidence);},...extra}).finally(()=>assert.equal(f.calls.every(method=>method==='Runtime.evaluate'),true,'no reload/navigation in any readback')); 
  return f;
}
test('early semantic receipt confirms with no composer or assistant DOM and persists before returning',async()=>{
  const f=fixture();let persisted=false;
  const outcome=await f.read({onRequest:async evidence=>{await Promise.resolve();f.saved.push(evidence);persisted=true;}});
  assert.equal(outcome.state,'CONFIRMED');assert.equal(persisted,true);assert.equal(outcome.messageId,messageId);assert.equal(f.saved[0].receipt.ack,'cot_token');
});
test('readback cannot confirm an unpersisted receipt without callback',async()=>{const f=fixture();assert.equal((await f.read({onRequest:undefined})).state,'UNKNOWN');});
test('durable acknowledgement survives a missing page observer after restart',async()=>{const f=fixture();f.intent={...f.intent,message_id:messageId,released:true,receipt:receipt()};f.receipt=null;assert.equal((await f.read({onRequest:undefined})).state,'CONFIRMED');});
test('restart without durable ack and optimistic user DOM remains UNKNOWN with no replay',async()=>{
  const f=fixture();f.receipt=null;f.probe={...f.probe,composer:true,userCount:1,userMessageId:messageId,assistantCount:1,streaming:true};
  assert.equal((await f.read()).state,'UNKNOWN');assert.equal(f.calls.every(method=>method==='Runtime.evaluate'),true);
});
for(const patch of [{generation:'old'},{intent_id:'old'},{request_id:'old:1'},{message_id:'bad'},{conversation_id:'other'},{ack:null},{invalid:'duplicate_request'}])test('mismatched receipt remains UNKNOWN '+JSON.stringify(patch),async()=>{
  const f=fixture();Object.assign(f.receipt,patch);assert.equal((await f.read()).state,'UNKNOWN');
});
test('durable outgoing UUID mismatch cannot be replaced by another receipt',async()=>{const f=fixture();f.intent.message_id='ffffffff-1242-4d48-8f34-413e8ed42aa4';assert.equal((await f.read()).state,'UNKNOWN');});
test('changed immutable prompt cannot confirm',async()=>{const f=fixture();assert.equal((await f.read({prompt:'different'})).state,'UNKNOWN');});
test('page-held invalidation overrides an older saved positive receipt',async()=>{const f=fixture();f.intent.receipt=receipt();f.receipt.invalid='duplicate_request';f.receipt.ack=null;assert.equal((await f.read()).state,'UNKNOWN');assert.equal(f.saved.at(-1).receipt.invalid,'duplicate_request');});
test('receipt persistence failure never returns CONFIRMED',async()=>{const f=fixture();await assert.rejects(f.read({onRequest:async()=>{throw new Error('disk failed');}}),/disk failed/);});
test('STOP or leadership loss while persisting prevents late CONFIRMED',async()=>{
  const f=fixture();await assert.rejects(f.read({onRequest:async()=>{f.allowed=false;}}),/stale/);
});
test('same-page initial receipt waits for canonical route without reconnecting',async()=>{
  const f=fixture();let reads=0;f.probe={...f.probe,url:'https://chatgpt.com/g/project'};
  const call=f.session.call;f.session.call=async(method,params)=>{if(params.expression.includes('const errorMatch=')){if(++reads>1)f.probe.url='https://chatgpt.com/c/owned';}return call(method,params);};
  f.browser.config.navigationTimeoutMs=1000;
  assert.equal((await f.read({conversationUrl:''})).state,'CONFIRMED');
});
test('proven prior pre-release cancellation preserves ABSENT without DOM inference',async()=>{
  const f=fixture();f.receipt=null;Object.assign(f.intent,{absence_proof:'INTERCEPTED_REQUEST_ABORTED',released:false});
  assert.equal((await f.read()).state,'ABSENT');
  f.intent.absence_proof=null;assert.equal((await f.read()).state,'UNKNOWN');
});

test('initial semantic event without a canonical conversation cannot confirm on a project route',async()=>{
  const f=fixture();f.receipt.conversation_id='';f.probe.url='https://chatgpt.com/g/project';
  assert.equal((await f.read({conversationUrl:''})).state,'UNKNOWN');
});

 test('receipt polling releases abort listeners after each resolved delay',async()=>{
  const {getEventListeners}=await import('node:events');
  const f=fixture(),controller=new AbortController();f.receipt.ack=null;
  f.browser.config.navigationTimeoutMs=250;
  assert.equal((await f.read({signal:controller.signal})).state,'UNKNOWN');
  assert.equal(getEventListeners(controller.signal,'abort').length,0);
 });

for(const status of [403,429,500])test(`HTTP ${status} diagnostics persist through receipt readback without confirming`,async()=>{
  const f=fixture();Object.assign(f.receipt,{ack:null,invalid:'http_error',http_status:status,endpoint_category:'f_conversation',headers:{authorization:'secret'},response_body:'secret'});
  assert.equal((await f.read()).state,'UNKNOWN');
  assert.equal(f.saved.at(-1).receipt.http_status,status);assert.equal(f.saved.at(-1).receipt.endpoint_category,'f_conversation');
  assert.doesNotMatch(JSON.stringify(f.saved),/secret|headers|response_body/);
});
test('receipt readback drops invalid status and arbitrary endpoint data',async()=>{
  const f=fixture();Object.assign(f.receipt,{ack:null,invalid:'http_error',http_status:'500 private',endpoint_category:'https://private?token=secret'});
  await f.read();assert.equal(f.saved.at(-1).receipt.http_status,null);assert.equal(f.saved.at(-1).receipt.endpoint_category,null);
  assert.doesNotMatch(JSON.stringify(f.saved),/private|secret/);
});

for(const stalled of ['progress','receiptSnapshot'])test(`a pending ${stalled} does not prevent the other independent evidence query`,async()=>{
 const f=fixture();let release;const gate=new Promise(r=>release=r),calls=[];
 f.session={transport:'extension',async run(operation){calls.push(operation);if(operation===stalled)await gate;return operation==='receiptSnapshot'?f.receipt:f.probe;}};
 const reading=f.read();
 try{for(let n=0;n<25&&calls.length<2;n++)await new Promise(r=>setTimeout(r,2));assert.ok(calls.includes('progress'));assert.ok(calls.includes('receiptSnapshot'));}
 finally{release();await reading;}
 assert.equal(f.saved.length,1);
});

function failedProgress(f,error=new Error('progress unavailable'),beforeFailure=()=>{}) {
 const calls=[];
 f.session={transport:'extension',async run(operation){
  calls.push(operation);
  if(operation==='progress'){await Promise.resolve();beforeFailure();throw error;}
  assert.equal(operation,'receiptSnapshot','readback must not send or navigate');return f.receipt;
 }};
 return calls;
}
for(const code of ['OBSERVATION_FAILED','EXTENSION_TIMEOUT'])for(const status of [403,429,500])
test(`progress ${code} preserves matching HTTP ${status} diagnostics without confirming`,async()=>{
 const f=fixture(),calls=failedProgress(f,Object.assign(new Error('progress failed'),{code}));
 Object.assign(f.receipt,{ack:null,invalid:'http_error',http_status:status,endpoint_category:'f_conversation',headers:{authorization:'secret'},response_body:'secret'});
 assert.equal((await f.read()).state,'UNKNOWN');
 assert.deepEqual(calls.sort(),['progress','receiptSnapshot']);assert.equal(f.saved.length,1);
 assert.equal(f.saved[0].receipt.http_status,status);assert.equal(f.saved[0].receipt.endpoint_category,'f_conversation');
 assert.doesNotMatch(JSON.stringify(f.saved),/secret|headers|response_body/);
});
test('semantic ACK survives failed observation as evidence but requires canonical page proof to confirm',async()=>{
 const f=fixture();failedProgress(f);
 assert.equal((await f.read()).state,'UNKNOWN');assert.equal(f.saved.length,1);
 assert.equal(f.saved[0].receipt.ack,'cot_token');assert.equal(f.saved[0].message_id,messageId);
});
for(const conflict of ['intent','generation','message','request','conversation','prompt'])
test(`failed observation cannot persist a receipt with a conflicting ${conflict}`,async()=>{
 const f=fixture();failedProgress(f);
 if(conflict==='intent')f.receipt.intent_id='other';
 if(conflict==='generation')f.receipt.generation='other';
 if(conflict==='message')f.intent.message_id='ffffffff-1242-4d48-8f34-413e8ed42aa4';
 if(conflict==='request')f.intent.receipt={...receipt(),request_id:'generation-1:2'};
 if(conflict==='conversation')f.receipt.conversation_id='other';
 assert.equal((await f.read(conflict==='prompt'?{prompt:'different'}:{})).state,'UNKNOWN');
 assert.equal(f.saved.length,0);
});
for(const loss of ['STOP','ownership'])test(`failed observation after ${loss} cannot persist late receipt evidence`,async()=>{
 const f=fixture(),controller=new AbortController(),reason=new Error('STOP requested');
 failedProgress(f,new Error('progress failed'),()=>{if(loss==='STOP')controller.abort(reason);else f.allowed=false;});
 await assert.rejects(f.read({signal:controller.signal}),loss==='STOP'?/STOP requested/:/stale/);
 assert.equal(f.saved.length,0);
});
test('receipt persistence failure during progress failure is reported instead of silently losing evidence',async()=>{
 const f=fixture();failedProgress(f);
 await assert.rejects(f.read({onRequest:async()=>{throw new Error('disk failed');}}),/disk failed/);
});
test('ownership loss during receipt persistence after progress failure cannot return a late outcome',async()=>{
 const f=fixture();failedProgress(f);
 await assert.rejects(f.read({onRequest:async()=>{f.allowed=false;}}),/stale/);
});
