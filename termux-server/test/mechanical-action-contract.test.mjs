import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './helpers/lifecycle-fixture.mjs';
import { keyFor } from '../src/lifecycle.mjs';
import { readResult } from '../src/result-reconciler.mjs';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';

const tick=()=>new Promise(r=>setImmediate(r));
test('initial send has no full liveness-read precondition',async()=>{
 const f=fixture();f.browser.livenessSnapshot=async()=>{throw Error('full response not available');};
 await f.ingest();assert.equal(f.sends,1);assert.equal(f.observations.length,1);
});
test('admission attach and ordinary observation never read the business Result',async()=>{
 const f=fixture();let reads=0;f.transport.readGoogleDocText=async()=>{reads++;throw Error('unavailable');};
 await f.existing();await f.observe({});await f.controller.tick(f.transport);
 assert.equal(reads,0);assert.equal(f.observations.length,1);
});
test('one committed Result check at ten minutes suppresses only the continuation',async()=>{
 const f=fixture();await f.existing();await f.observe({});let reads=0;
 f.result(true);const read=f.transport.readGoogleDocText;f.transport.readGoogleDocText=async id=>{reads++;return read(id);};
 const a=f.observations[0];await f.stall();
 assert.equal(reads,1);assert.equal(f.sends,0);assert.ok(f.record());assert.equal(a.signal.aborted,false);
 assert.equal(f.controller.sessions.size,1);await f.observe({responseTextLength:1999});
 assert.equal(reads,1);assert.equal(f.record().cursor.responseTextLength,1999);
});
test('WAITING_USER_INTERVENTION retains observer and current control polling',async()=>{
 const f=fixture();await f.existing();const a=f.observations[0];
 f.control={...f.control,state:'WAITING_USER_INTERVENTION',control_epoch:2};
 await f.controller.control(null,f.control,f.transport);await f.controller.tick(f.transport);
 assert.equal(a.signal.aborted,false);assert.equal(f.controller.sessions.size,1);
 await f.observe({responseTextLength:2345});assert.equal(f.record().cursor.responseTextLength,2345);
 await f.stall();assert.equal(f.sends,0);await f.stop();assert.equal(f.record(),null);
});
test('server writes only observed conversation URL and never ready or terminal business status',async()=>{
 const f=fixture();const writes=[];const write=f.transport.write;f.transport.write=async(p,b,o)=>{writes.push(structuredClone(b));return write(p,b,o);};
 f.body.client_status='CREATE_REQUESTED';await f.ingest();assert.equal(writes.length,0);
 f.body.client_status='SEND_REQUESTED';await f.ingest();assert.ok(writes.length>0);
 for(const body of writes)assert.deepEqual(body,{...f.body,conversation_url:f.probe.url});
 const count=writes.length;f.control={...f.control,state:'DONE',control_epoch:2};await f.controller.control(null,f.control,f.transport);
 assert.equal(writes.length,count);assert.equal(f.record(),null);assert.deepEqual(f.controller.repository.data.completionOutbox,{});
});
test('strict recovery Result reads one current value without implicit JSON repair',async()=>{
 const f=fixture();f.result(true);let reads=0;
 const record={...f.body,body:f.body};const transport={readGoogleDocText:async()=>{reads++;return f.docs.get('RESULT-1');}};
 assert.equal((await readResult(transport,record)).state,'COMMITTED');assert.equal(reads,1);
 f.docs.set('RESULT-1',f.docs.get('RESULT-1')+'}');
 assert.equal((await readResult(transport,record)).code,'RESULT_JSON_INVALID');assert.equal(reads,2);
});
test('submission starts monitoring immediately after click while no canonical URL exists',async()=>{
 const f=fixture();let submitted=false;f.browser.submitIntent=async args=>{f.sends++;submitted=true;await args.onSubmitted?.();return {submitted:true};};
 f.browser.readSubmission=async()=>({state:'UNKNOWN'});
 await f.ingest();assert.equal(submitted,true);assert.equal(f.observations.length,1);
 await f.observe({url:f.probe.url});assert.equal(f.record().conversation_url,f.probe.url);
 assert.equal(f.rows.get(f.path).conversation_url,f.probe.url);
});
test('real submit seam returns after click without canonical-route wait or full snapshot',async()=>{
 const calls=[];let monitored=false;
 const browser=new ChatGptBrowser(null,{navigationTimeoutMs:1});
 const session={transport:'extension',async run(op){calls.push(op);if(op==='snapshot')throw Error('full snapshot must not gate send');
  if(op==='recoveryInput')return {url:'https://chatgpt.com/g/test/project',composer:true};
  if(op==='input')return {status:'READY'};if(op==='sendReady')return {ready:true};if(op==='submit')return {status:'SUBMITTED'};return true;}};
 const out=await browser.submitIntent({session,prompt:'hello',kind:'initial',intent:{id:'intent',receipt_generation:'generation',prompt:'hello'},profileOperations:[],guard:()=>true,
  onSubmitted:()=>{monitored=true;assert.equal(calls.at(-1),'submit');}});
 assert.equal(monitored,true);assert.equal(out.submitted,true);assert.equal(calls.includes('snapshot'),false);
});
test('create preparation is just owned tab creation then project navigation, without a full-page readiness gate',async()=>{
 const calls=[];const session={transport:'extension',async run(op,payload){calls.push([op,payload]);if(op==='snapshot')throw Error('must not inspect response during creation');return true;},close(){}};
 const chromium={createTarget:async url=>{calls.push(['create',url]);return {id:'owned'};},connectTarget:async()=>session,closeTarget:async()=>true};
 const browser=new ChatGptBrowser(chromium,{navigationTimeoutMs:1});
 await browser.prepare({projectUrl:'https://chatgpt.com/g/test/project',onTarget:async()=>{},guard:()=>true});
 assert.deepEqual(calls.filter(([op])=>['create','navigate','input','submit','snapshot'].includes(op)),[['create','about:blank'],['navigate',{url:'https://chatgpt.com/g/test/project'}]]);
});
test('a failed project navigation retries on its already acquired owned tab',async()=>{
 let creates=0,navigations=0,closes=0;
 const session={transport:'extension',async run(op){if(op==='navigate'&&++navigations===1)throw Error('navigation interrupted');return true;},close(){}};
 const chromium={createTarget:async()=>{creates++;return {id:'owned'};},isOwnedTarget:id=>id==='owned',listExistingTargets:async()=>[{id:'owned',type:'page'}],connectTarget:async()=>session,closeTarget:async()=>{closes++;return true;}};
 const browser=new ChatGptBrowser(chromium,{}),args={projectUrl:'https://chatgpt.com/g/test/project',onTarget:async()=>{},guard:()=>true};
 await assert.rejects(browser.prepare(args),/navigation interrupted/);await browser.prepare({...args,previousTargetId:'owned'});
 assert.equal(creates,1);assert.equal(navigations,2);assert.equal(closes,0);
});
test('initial input does not require a server metadata roundtrip after durable local intent',async()=>{
 const f=fixture();const read=f.transport.read;
 f.transport.read=async path=>{if(path===f.path&&f.record()?.intent)throw Error('metadata unavailable');return read(path);};
 await f.ingest();assert.equal(f.sends,1);
});
test('URL-free monitoring never turns an uncertain initial click into a replay at the recovery deadline',async()=>{
 const f=fixture();f.probe.url=f.body.project_url;f.browser.submitIntent=async args=>{f.sends++;await args.onSubmitted();throw Object.assign(Error('click outcome unknown'),{released:true});};
 f.browser.readSubmission=async()=>({state:'UNKNOWN'});await f.ingest();await f.observe({});
 await f.controller.repository.move(keyFor(f.body),f.record().state,'test deadline',{last_progress_at:0});
 await f.observe({status:'STALLED'});await f.controller.tick(f.transport);
 assert.equal(f.sends,1);assert.equal(f.observations.at(-1).signal.aborted,false);
});
for(const mode of ['confirmed','unknown'])test(`cancelled ${mode} input retains only bounded no-replay proof in the same RUNNING execution until STOP`,async()=>{
 const f=fixture();if(mode==='unknown')f.mode='unknown';await f.ingest();
 f.body.client_status='CANCELLED';await f.ingest();await f.controller.flush(f.transport);
 assert.equal(f.record().state,'CANCELLED');assert.equal(f.record().body,undefined);assert.equal(f.record().intent,undefined);
 await f.controller.quiesceForStandby();f.controller=f.recreate();await f.controller.initialize();
 f.body={...f.body,client_status:'SEND_REQUESTED',dispatch_attempt:2};await f.ingest();
 assert.equal(f.sends,1);assert.equal(f.prepares,1);await f.stop();assert.equal(f.record(),null);
 assert.deepEqual(f.controller.repository.data.controls,{});assert.deepEqual(f.controller.repository.data.retired,{});
});
test('cancelled current-run proof cannot block a fresh successor authorized by current CONTROL',async()=>{
 const f=fixture();f.mode='unknown';await f.ingest();f.body.client_status='CANCELLED';await f.ingest();
 f.body={...f.body,turn_id:'SAFE-TEST:turn:2',request_id:'next',result_document_id:'RESULT-2',client_status:'CREATE_REQUESTED'};
 f.path='next.json';f.control={...f.control,turn_id:f.body.turn_id,request_id:f.body.request_id,control_epoch:2};
 await f.ingest();assert.equal(f.record().state,'PREPARED');assert.equal(f.prepares,2);assert.equal(f.sends,1);
 await f.stop();assert.deepEqual(f.controller.repository.data.retired,{});
});
for(const state of ['PAUSED','WAITING_USER_INTERVENTION'])test(`a ${state} round trip cannot erase cancelled uncertain-input proof`,async()=>{
 const f=fixture();f.mode='unknown';await f.ingest();f.body.client_status='CANCELLED';await f.ingest();
 f.control={...f.control,state,control_epoch:2};await f.controller.control(null,f.control,f.transport);await f.controller.tick(f.transport);
 await f.controller.quiesceForStandby();f.controller=f.recreate();await f.controller.initialize();
 f.control={...f.control,state:'RUNNING',control_epoch:3};await f.controller.control(null,f.control,f.transport);
 f.body={...f.body,client_status:'SEND_REQUESTED',dispatch_attempt:2};await f.ingest();
 assert.equal(f.sends,1);assert.equal(f.prepares,1);await f.stop();assert.equal(f.record(),null);
});
