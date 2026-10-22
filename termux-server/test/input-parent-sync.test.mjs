import test from 'node:test';
import assert from 'node:assert/strict';
import {inputParentRuntime} from '../src/browser/input-parent-sync.mjs';

const url='https://chatgpt.com/c/owned',prompt='continue';
const body=parent=>({conversation_id:'owned',parent_message_id:parent,messages:[{id:'same-user-id',author:{role:'user'},content:{parts:[prompt]}}],model:'profile-model',custom:{keep:true}});
const post=value=>new Request('https://chatgpt.com/backend-api/f/conversation',{method:'POST',headers:{authorization:'Bearer fixture', 'content-type':'application/json'},body:JSON.stringify(value)});
function fixture({firstUnavailable=false}={}) {
 let tip='other-device-tip',reads=0;const sent=[];
 const native=async(input,init)=>{const r=input instanceof Request?input:new Request(new URL(input,url),init);
  if(r.method==='GET'){reads++;if(firstUnavailable&&reads===1)return new Response('{}',{status:503});return Response.json({conversation_id:'owned',current_node:tip,mapping:{[tip]:{message:{id:tip,status:'in_progress'},children:[]}}});}
  sent.push({body:await r.json(),auth:r.headers.get('authorization')});return new Response('accepted');};
 const runtime=inputParentRuntime({nativeFetch:native,href:()=>url,retryMs:1});
 return {runtime,sent,setTip:value=>tip=value,get reads(){return reads;}};
}
test('ordinary requests and input preparation do not fetch conversation state',async()=>{
 const f=fixture();await f.runtime.fetch(post(body('old')));assert.equal(f.reads,0);
 f.runtime.prepare(prompt);assert.equal(f.reads,0);
});
test('matching input appends to the latest server tip and preserves all other payload fields',async()=>{
 const f=fixture();f.runtime.prepare(prompt);f.setTip('newest-after-input-decision');
 const original=body('stale-tab-tip');await f.runtime.fetch(post(original));
 assert.equal(f.reads,1);assert.deepEqual(f.sent[0].body,{...original,parent_message_id:'newest-after-input-decision'});
 assert.equal(f.sent[0].auth,'Bearer fixture');
});
test('a transient parent read is retried within the send without emitting an old-parent POST',async()=>{
 const f=fixture({firstUnavailable:true});f.runtime.prepare(prompt);await f.runtime.fetch(post(body('old')));
 assert.equal(f.reads,2);assert.equal(f.sent.length,1);assert.equal(f.sent[0].body.parent_message_id,'other-device-tip');
});
test('another conversation or an unrelated prompt is not rewritten',async()=>{
 const f=fixture();f.runtime.prepare(prompt);
 const other={...body('original'),conversation_id:'different'};await f.runtime.fetch(post(other));
 const manual=body('manual-parent');manual.messages[0].content.parts=['manual message'];await f.runtime.fetch(post(manual));
 assert.equal(f.reads,0);assert.deepEqual(f.sent.map(x=>x.body),[other,manual]);
});
test('a cookie-only 404 obtains the current page session and then reads the parent',async()=>{
 const requests=[];let sessionReads=0;
 const runtime=inputParentRuntime({href:()=>url,nativeFetch:async(input,init)=>{
  const r=input instanceof Request?input:new Request(new URL(input,url),init);
  if(r.url.endsWith('/api/auth/session')){sessionReads++;return Response.json({accessToken:'fresh-fixture'});}
  if(r.method==='GET')return r.headers.has('authorization')?Response.json({current_node:'fresh',mapping:{fresh:{}}}):new Response('{}',{status:404});
  requests.push(await r.json());return new Response('');
 }});
 runtime.prepare(prompt);const r=post(body('old'));r.headers.delete('authorization');await runtime.fetch(r);
 assert.equal(sessionReads,1);assert.equal(requests[0].parent_message_id,'fresh');
});
test('terminal disposal cancels a pending parent lookup without forwarding the POST',async()=>{
 let entered;const reading=new Promise(r=>entered=r),sent=[];
 const runtime=inputParentRuntime({href:()=>url,nativeFetch:async(input,init)=>{
  const request=input instanceof Request?input:new Request(new URL(input,url),init);
  if(request.method==='POST'){sent.push(request);return new Response('');}
  entered();return new Promise((_,reject)=>request.signal.addEventListener('abort',()=>reject(request.signal.reason),{once:true}));
 }});
 runtime.prepare(prompt);const sending=runtime.fetch(post(body('old')));const rejected=assert.rejects(sending,/abort|dispos|cancel/i);
 await reading;runtime.dispose();await rejected;assert.equal(sent.length,0);
});
