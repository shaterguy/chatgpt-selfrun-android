import test from 'node:test';
import assert from 'node:assert/strict';
import {LegacyInputTransport} from '../src/browser/legacy-input-transport.mjs';
const operations=[['set','model','chosen'],['remove','thinking_effort'],['remove','conversation_origin'],['remove','service_tier']];
function fixture({evaluate=async()=> 'latest-tip',closeTarget=async()=>true}={}) {
 const calls=[],abort=new AbortController();let listener;
 const session={on(_name,handler){listener=handler;return()=>listener=null;},async call(method,params){calls.push({method,params});return{};}};
 const transport=new LegacyInputTransport({session,evaluate,prompt:'next',conversationId:'owned',operations,signal:abort.signal,retryMs:1,closeTarget});
 const event={requestId:'r',request:{method:'POST',url:'https://chatgpt.com/backend-api/conversation',headers:{Authorization:'token'},postData:JSON.stringify({conversation_id:'owned',parent_message_id:'old',messages:[{author:{role:'user'},content:{parts:['next']}}],model:'old',thinking_effort:'old',other:'keep'})}};
 return {calls,transport,event,abort,get listener(){return listener;}};
}
test('legacy interception patches exactly the decided POST and immediately detaches',async()=>{
 const f=fixture();await f.transport.install();await f.transport.paused(f.event);
 const send=f.calls.find(x=>x.method==='Fetch.continueRequest');const body=JSON.parse(Buffer.from(send.params.postData,'base64'));
 assert.equal(body.parent_message_id,'latest-tip');assert.equal(body.model,'chosen');assert.equal(body.other,'keep');assert.ok(!('thinking_effort'in body));
 assert.equal(f.listener,null);assert.equal(f.calls.at(-1).method,'Fetch.disable');
});
test('legacy interception leaves unrelated traffic unchanged',async()=>{
 const f=fixture();await f.transport.install();await f.transport.paused({...f.event,request:{...f.event.request,method:'GET'}});
 assert.deepEqual(f.calls.at(-1),{method:'Fetch.continueRequest',params:{requestId:'r'}});await f.transport.dispose();
});
test('STOP rejects a paused legacy POST before detaching and cannot forward its late parent',async()=>{
 let release;const f=fixture({evaluate:()=>new Promise(r=>release=r)});await f.transport.install();const pending=f.transport.paused(f.event);
 await new Promise(r=>setImmediate(r));f.abort.abort();await f.transport.dispose();release('late-tip');await pending;
 assert.ok(f.calls.some(x=>x.method==='Fetch.failRequest'));assert.ok(!f.calls.some(x=>x.method==='Fetch.continueRequest'&&x.params.postData));
 assert.ok(f.calls.findIndex(x=>x.method==='Fetch.failRequest')<f.calls.findIndex(x=>x.method==='Fetch.disable'));
});

test('failed POST cancellation closes its exact target before disabling interception',async()=>{
 const f=fixture();let closed=false;f.transport.closeTarget=async()=>{closed=true;return true;};
 const call=f.transport.session.call;f.transport.session.call=async(method,params)=>{if(method==='Fetch.failRequest')throw Error('cancel unavailable');if(method==='Fetch.disable')assert.equal(closed,true);return call(method,params);};
 await f.transport.install();f.transport.pending.add('held');assert.equal(await f.transport.dispose(),true);assert.equal(closed,true);
});
test('a failed target close cannot disable and release an unresolved POST',async()=>{
 const f=fixture({closeTarget:async()=>false});const call=f.transport.session.call;f.transport.session.call=async(method,params)=>{if(method==='Fetch.failRequest')throw Error('cancel unavailable');return call(method,params);};
 await f.transport.install();f.transport.pending.add('held');assert.equal(await f.transport.dispose(),false);
 assert.equal(f.transport.pending.has('held'),true);assert.ok(!f.calls.some(c=>c.method==='Fetch.disable'));
});
