import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';
import { ChatGptBrowser, conversationResumeReady, conversationStateReady } from '../src/browser/chatgpt.mjs';
import { readResult } from '../src/result-reconciler.mjs';
import { keyFor, cursor } from '../src/lifecycle.mjs';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';

const elapsed=async f=>f.controller.repository.move(keyFor(f.body),f.record().state,'test retry elapsed',{retry_at:0,result_retry_at:0});
const settle=async()=>{for(let i=0;i<20;i++)await new Promise(resolve=>setImmediate(resolve));};

for(const state of ['RUNNING','STOPPED','DONE'])test('old first-seen '+state+' control remains authoritative without age exclusion',async()=>{
 const f=fixture();f.control.state=state;
 const list=f.transport.list;f.transport.list=async()=>(await list()).map(file=>({...file,modTime:'2020-01-01T00:00:00Z'}));
 await new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{dispatchRecoveryMs:7200000}}).scanOnce();
 await settle();
 assert.ok(f.record(),'first-seen request is examined regardless of age');
 assert.equal(f.sends,state==='RUNNING'?1:0);
 assert.equal(f.record().control_state,state);
});

test('old retired dispatch is skipped without reading or resurrecting its input',async()=>{
 const f=fixture();await f.existing();await f.stop();let reads=0;
 const read=f.transport.read;f.transport.read=async name=>{if(name===f.path)reads++;return read(name);};
 const list=f.transport.list;f.transport.list=async()=>(await list()).map(file=>({...file,modTime:'2020-01-01T00:00:00Z'}));
 await new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}}).scanOnce();await settle();
 assert.equal(reads,0);assert.equal(f.record().retired,true);assert.equal(f.resumes,1);
});

for(const code of ['BROWSER_DEAD','AUTH_REQUIRED','CONVERSATION_UNAVAILABLE'])test(code+' retries beyond three failures with pacing while app is active',async()=>{
 const f=fixture();f.controller.config.resumeRetryMs=30000;
 f.attachError=Object.assign(new Error('safe test error'),{code});await f.existing();
 for(let i=0;i<5;i++) {
  const count=f.resumes;await f.controller.tick(f.transport);assert.equal(f.resumes,count,'no retry before deadline');
  await elapsed(f);await f.controller.tick(f.transport);
 }
 assert.equal(f.resumes,6);assert.equal(f.record().blocked_epoch,null);assert.ok(f.record().retry_at>Date.now());
 assert.equal(f.prepares,0);assert.equal(f.sends,0);
 f.attachError=null;await elapsed(f);await f.controller.tick(f.transport);
 assert.equal(f.resumes,7);assert.equal(f.record().state,'OBSERVING');
});

for(const state of ['PAUSED','WAITING_USER_INTERVENTION','STOPPED','DONE'])test(state+' cancels an active attach retry before its next browser operation',async()=>{
 const f=fixture();f.attachError=new Error('ECONNREFUSED');await f.existing();
 f.control={...f.control,state,control_epoch:2};
 await f.controller.tick(f.transport);
 assert.equal(f.resumes,1);assert.equal(f.prepares,0);assert.equal(f.sends,0);
 if(['STOPPED','DONE'].includes(state)) {
  assert.equal(f.record().retired,true);assert.equal(f.record().intent,undefined);assert.equal(f.record().retry_at,undefined);
 }
});

test('Continue generating UI does not veto an app-active stalled recovery',async()=>{
 const f=fixture();f.probe.paused=true;f.probe.streaming=false;f.probe.stopButtonVisible=false;
 await f.existing();await f.stall();
 assert.equal(f.sends,1);assert.equal(f.record().intent.kind,'recovery');assert.notEqual(f.record().error?.code,'GENERATION_PAUSED');
});

test('missing DOM user ID does not disable otherwise usable conversation readiness',()=>{
 assert.equal(conversationResumeReady({composer:true,userMessageId:null}),true);
 assert.equal(conversationStateReady({assistantMessageId:'a1',userMessageId:null}),true);
 assert.equal(conversationStateReady({responseTextLength:10,userMessageId:null}),true);
 assert.equal(conversationStateReady({userMessageId:null,streaming:false}),false);
 assert.equal(conversationResumeReady({composer:false,userMessageId:'u1'}),false);
});

function browserResumeFixture(probe) {
 const connected=[];
 const session={close(){},async call(method){return method==='Runtime.evaluate'?{result:{value:probe}}:{};}};
 const chromium={listExistingTargets:async()=>[{id:'owned',type:'page',url:'https://chatgpt.com/c/owned'}],
  connectTarget:async t=>{connected.push(t.id);return session;},createTarget:async()=>({id:'created'}),closeTarget:async()=>{}};
 return {browser:new ChatGptBrowser(chromium,{navigationTimeoutMs:1}),connected};
}
test('canonical resume succeeds without a DOM user ID',async()=>{
 const f=browserResumeFixture({url:'https://chatgpt.com/c/owned',composer:true,readyState:'complete',userMessageId:null});
 const attached=await f.browser.resume({conversationUrl:'https://chatgpt.com/c/owned'});
 assert.equal(attached.target.id,'owned');
});
for(const probe of [
 {url:'https://chatgpt.com/c/wrong',composer:true,readyState:'complete'},
 {url:'https://example.com/c/owned',composer:true,readyState:'complete'},
 {url:'https://chatgpt.com/c/owned',composer:true,readyState:'complete',loginPage:true},
])test('relaxed readiness still rejects wrong conversation, origin, or login: '+JSON.stringify(probe),async()=>{
 const f=browserResumeFixture(probe);
 await assert.rejects(f.browser.resume({conversationUrl:'https://chatgpt.com/c/owned'}));
});

for(const [kind,code] of [['missing','RESULT_UNAVAILABLE'],['unreadable','RESULT_READ_FAILED'],['malformed','RESULT_READBACK_INVALID']])test('Result '+kind+' surfaces a safe error and waits between retries',async()=>{
 const f=fixture();await f.existing();await f.observe({});
 let reads=0,writes=0;
 const read=f.transport.readGoogleDocText,write=f.transport.write;
 f.transport.readGoogleDocText=async id=>{reads++;if(kind==='unreadable')throw Error('secret response https://private.invalid/token');return read(id);};
 f.transport.write=async(...args)=>{writes++;return write(...args);};
 if(kind==='missing')await f.controller.repository.ingest(f.path,{...f.body,result_document_id:''});
 if(kind==='malformed')f.docs.set('RESULT-1','invalid');
 await f.controller.repository.move(keyFor(f.body),'STALLED','test stalled',{last_progress_at:123});
 await f.controller.tick(f.transport);
 assert.equal(f.record().error?.code,code);assert.doesNotMatch(f.record().error.message,/secret|private.invalid/);
 assert.ok(f.record().result_retry_at>=Date.now()+29000);assert.equal(f.sends,0);
 const before={reads,writes,revision:f.record().revision};
 for(let i=0;i<4;i++){await f.controller.tick(f.transport);await f.observe({status:'STALLED'});}
 assert.deepEqual({reads,writes,revision:f.record().revision},before,'unchanged unavailable Result does not busy-read or busy-write');
 assert.equal(f.rows.get(f.path).server_error_class,code);
 assert.equal(f.record().last_progress_at,123);
 f.transport.readGoogleDocText=async id=>{reads++;return read(id);};f.result(false);
 if(kind==='missing')await f.controller.repository.ingest(f.path,{...f.body,result_document_id:'RESULT-1'});
 await elapsed(f);await f.controller.tick(f.transport);
 assert.equal(f.sends,1);assert.equal(f.record().error,null);
});

test('malformed Result reason differs from transport failure and committed proof still needs exact second read',async()=>{
 const f=fixture();await f.existing();f.result(true);
 let reads=0;const raw=f.docs.get('RESULT-1');
 const result=await readResult({readGoogleDocText:async()=>++reads===1?raw:raw+' '},f.record());
 assert.equal(reads,2);assert.equal(result.code,'RESULT_READBACK_INVALID');assert.equal(result.state,'UNAVAILABLE');
});

test('canonical live resume attaches for observation even while composer is absent',async()=>{
 const f=browserResumeFixture({url:'https://chatgpt.com/c/owned',composer:false,readyState:'complete',userMessageId:null});
 const attached=await f.browser.resume({conversationUrl:'https://chatgpt.com/c/owned'});
 assert.equal(attached.target.id,'owned');
});

test('missing live-page DOM reports unknown observation without claiming target loss',async()=>{
 let calls=0;const statuses=[],abort=new AbortController();
 const browser=new ChatGptBrowser(null,{probeIntervalMs:0,stallAfterMs:600000,conversationStateGraceMs:0});
 const session={async call(){calls++;return {result:{value:{url:'https://chatgpt.com/c/owned',readyState:'complete',composer:false,userMessageId:null,errorText:null}}};}};
 await assert.rejects(browser.monitor({session,signal:abort.signal,livenessGate:()=>({state:'RUNNING',epoch:1}),onActivity:async activity=>{
  statuses.push(activity.status);if(calls===2)abort.abort(Error('test complete'));
 }}),/test complete/);
 assert.deepEqual(statuses,['OBSERVATION_UNKNOWN','OBSERVATION_UNKNOWN']);
});

test('missing composer keeps current target and retries input availability at a paced deadline',async()=>{
 const missing='composer';
 const f=fixture();f.probe.streaming=false;f.probe.stopButtonVisible=false;f.probe[missing]=false;
 await f.existing();await f.stall();
 assert.equal(f.sends,0);assert.equal(f.record().error?.code,'COMPOSER_UNAVAILABLE');assert.equal(f.record().intent,null);
 const observer=f.observations.at(-1),revision=f.record().revision;
 for(let i=0;i<3;i++){await f.controller.tick(f.transport);await f.observe({status:'STALLED'});}
 assert.equal(f.resumes,1);assert.equal(f.prepares,0);assert.equal(observer.signal.aborted,false);
 assert.equal(f.record().revision,revision);
 f.probe[missing]=true;await elapsed(f);
 await f.controller.repository.move(keyFor(f.body),f.record().state,'test input attempt window elapsed',
  {last_nudge_attempt_at:Date.now()-f.controller.config.stallAfterMs});
 await f.controller.tick(f.transport);
 assert.equal(f.sends,1);assert.equal(f.resumes,1);
});

test('monitor activity callback failure retains session and reports callback error, not browser loss',async()=>{
 const f=fixture();f.probe.streaming=false;f.probe.stopButtonVisible=false;
 const start=deferred(),observed=deferred();
 f.browser.monitor=async args=>{
  f.observations.push(args);await start.promise;
  for(let i=0;i<2;i++)await args.onActivity({...f.probe,status:'STALLED'});
  observed.resolve();await new Promise(resolve=>args.signal.addEventListener('abort',resolve,{once:true}));
 };
 await f.existing();
 f.browser.recoveryInputSnapshot=async()=>{throw Error('UI input lookup unavailable');};
 await f.controller.repository.move(keyFor(f.body),'STALLED','test stalled',{last_progress_at:0,cursor:cursor(f.probe)});
 start.resolve();await observed.promise;
 assert.equal(f.resumes,1);assert.equal(f.controller.sessions.size,1);
 assert.notEqual(f.record().reason,'BROWSER_OBSERVATION_LOST');
 assert.equal(f.record().error?.code,'OBSERVATION_CALLBACK_FAILED');
 await f.controller.quiesceForStandby();
});

for(const reason of ['ATTACH_FAILED','ATTACH_BUDGET_EXHAUSTED','GENERATION_PAUSED'])test('persisted legacy '+reason+' gate becomes paced retry without waiting for a new epoch',async()=>{
 const f=fixture();await f.existing();
 await f.controller.repository.move(keyFor(f.body),'BLOCKED',reason,{attach_attempts:3,failure_epoch:1,blocked_epoch:1,retry_at:0});
 await f.controller.tick(f.transport);
 assert.equal(f.record().blocked_epoch,null);assert.ok(f.record().retry_at>Date.now());
 assert.equal(f.resumes,1);assert.equal(f.sends,0);
 await elapsed(f);await f.observe({status:'STALLED'});
 assert.notEqual(f.record().blocked_epoch,1);
});

test('tick-driven quiesce UI failure stays on the current page and paces retry',async()=>{
 const f=fixture();await f.existing();await f.observe({});
 await f.controller.repository.move(keyFor(f.body),'STALLED','test stalled',{last_progress_at:0});
 let quiesces=0;f.browser.quiesce=async()=>{quiesces++;throw Error('Timed out waiting for stuck stream cleanup');};
 await f.controller.tick(f.transport);
 assert.equal(quiesces,1);assert.equal(f.record().error?.code,'OBSERVATION_CALLBACK_FAILED');
 assert.ok(f.record().retry_at>Date.now());assert.equal(f.controller.sessions.size,1);
 await f.controller.tick(f.transport);assert.equal(quiesces,1);assert.equal(f.resumes,1);assert.equal(f.sends,0);
});

test('genuine CDP loss during a recovery callback detaches and retries the same canonical conversation',async()=>{
 const f=fixture();await f.existing();await f.observe({});
 await f.controller.repository.move(keyFor(f.body),'STALLED','test stalled',{last_progress_at:0});
 f.browser.quiesce=async()=>{throw Error('CDP websocket closed');};
 await f.controller.tick(f.transport);
 assert.equal(f.record().error?.code,'BROWSER_DEAD');assert.equal(f.controller.sessions.size,0);
 assert.ok(f.record().retry_at>Date.now());
 await f.controller.tick(f.transport);assert.equal(f.resumes,1);
 await elapsed(f);await f.controller.tick(f.transport);
 assert.equal(f.resumes,2);assert.equal(f.prepares,0);assert.equal(f.sends,0);
});

test('Result failure at final pre-send check durably defers only a never-called recovery submission',async t=>{
 let now=1000000;const window=600000;t.mock.method(Date,'now',()=>now);
 const f=fixture();f.controller.config.stallAfterMs=window;await f.existing();await f.observe({});let invalidate=true;
 const write=f.transport.write;
 f.transport.write=async(name,body)=>{await write(name,body);if(invalidate&&body.lifecycle_record?.reason==='SUBMISSION_INTENT_DURABLE')f.docs.set('RESULT-1','invalid');};
 now+=window;await f.observe({status:'STALLED'});assert.equal(f.sends,0);assert.equal(f.record().intent.outcome,'ABSENT');
 assert.equal(f.record().intent.absence_proof,'NOT_SUBMITTED_RESULT_GATE');
 assert.equal(f.record().state,'POST_UNCERTAIN');assert.equal(f.record().error.code,'RESULT_READBACK_INVALID');
 for(let i=0;i<3;i++) {
  now+=30000;f.result(false);await f.controller.tick(f.transport);await f.observe({status:'STALLED'});
  assert.equal(f.sends,0);assert.equal(f.record().intent.outcome,'ABSENT');assert.equal(f.record().intent.sends,0);
 }
 await f.controller.quiesceForStandby();f.controller=f.recreate();
 invalidate=false;f.result(false);now+=window;await f.controller.tick(f.transport);await f.observe({status:'STALLED'});
 assert.equal(f.sends,1);assert.equal(f.record().intent.outcome,'CONFIRMED');
});


test('app WAITING fences a pending observation error publication',async()=>{
 const f=fixture();await f.existing();await f.observe({});
 await f.controller.repository.move(keyFor(f.body),'STALLED','test stalled',{last_progress_at:0});
 f.browser.quiesce=async()=>{throw Error('Timed out waiting for stuck stream cleanup');};
 const entered=deferred(),release=deferred(),move=f.controller.repository.move.bind(f.controller.repository);
 f.controller.repository.move=async(...args)=>{if(args[2]==='OBSERVATION_CALLBACK_FAILED'){entered.resolve();await release.promise;}return move(...args);};
 const pending=f.controller.tick(f.transport);await entered.promise;
 f.control={...f.control,state:'WAITING_USER_INTERVENTION',control_epoch:2};
 await f.controller.controlDurable(null,f.control,f.transport);
 const revision=f.record().revision;release.resolve();await pending;
 assert.equal(f.record().state,'PAUSED');assert.equal(f.record().revision,revision);
});

test('restarted uncertain initial receipt uses paced CDP retry without resending',async()=>{
 const f=fixture();f.mode='unknown';await f.ingest();await f.controller.quiesceForStandby();f.controller=f.recreate();
 f.browser.readSubmission=async()=>{throw Error('CDP websocket closed');};
 await f.controller.tick(f.transport);
 assert.equal(f.record().intent.outcome,'UNKNOWN');assert.equal(f.sends,1);
 assert.equal(f.controller.sessions.size,0);assert.equal(f.record().error?.code,'BROWSER_DEAD');
 assert.ok(f.record().retry_at>Date.now());
 await f.controller.tick(f.transport);assert.equal(f.sends,1);
});
