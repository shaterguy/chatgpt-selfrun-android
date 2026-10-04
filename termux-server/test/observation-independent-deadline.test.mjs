import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';

const WINDOW=600000;
async function setup(t) {
  let now=1700000000000;
  t.mock.method(Date,'now',()=>now);
  const f=fixture();f.controller.config.stallAfterMs=WINDOW;
  t.after(()=>f.controller.quiesceForStandby());
  await f.existing();await f.observe({});
  // The monitor stays pending forever; no successful callback can unlock recovery.
  f.browser.livenessSnapshot=async()=>{throw Error('full observation must not gate a nudge');};
  f.browser.recoveryInputSnapshot=async()=>({...f.probe});
  return {f,advance:ms=>{now+=ms;},now:()=>now};
}

test('independent ticks submit at every ten-minute deadline while observation never returns',async t=>{
  const {f,advance,now}=await setup(t);f.mode='unknown';
  const progressAt=f.record().last_progress_at;
  for(let interval=1;interval<=3;interval++) {
    advance(WINDOW-1);await f.controller.tick(f.transport);assert.equal(f.sends,interval-1);
    advance(1);await f.controller.tick(f.transport);assert.equal(f.sends,interval);
    assert.equal(f.record().last_nudge_attempt_at,now());
    assert.equal(f.record().last_progress_at,progressAt);
    assert.equal(f.sent.at(-1).kind,'recovery');
    assert.equal(f.record().intent.outcome,'UNKNOWN');
  }
  assert.equal(f.prepares,0);assert.equal(f.resumes,1,'no reconnect or replacement page');
});

test('observation retry backoff cannot postpone an already due nudge',async t=>{
  const {f,advance}=await setup(t);
  await f.controller.repository.move(f.record().key,'OBSERVING','OBSERVATION_UNAVAILABLE',{
    retry_at:Date.now()+2*WINDOW,error:{code:'OBSERVATION_UNAVAILABLE',message:'Runtime.evaluate timeout'},
  });
  advance(WINDOW);await f.controller.tick(f.transport);assert.equal(f.sends,1);
});

test('failed input access is an attempted, paced recovery rather than a skipped deadline',async t=>{
  const {f,advance,now}=await setup(t);let attempts=0;
  f.browser.recoveryInputSnapshot=async()=>{attempts++;throw Error('input unavailable');};
  for(let interval=1;interval<=3;interval++) {
    advance(WINDOW);await f.controller.tick(f.transport);
    assert.equal(attempts,interval);assert.equal(f.record().last_nudge_attempt_at,now());
    await f.controller.tick(f.transport);assert.equal(attempts,interval);
  }
  assert.equal(f.sends,0);assert.equal(f.prepares,0);assert.equal(f.resumes,1);
});

for(const boundary of ['STOPPED','DONE','WAITING_USER_INTERVENTION','COMMITTED'])test(
  `${boundary} cancels the independent deadline`,async t=>{
    const {f,advance}=await setup(t);
    if(boundary==='COMMITTED')f.result(true);
    else {f.control={...f.control,state:boundary,control_epoch:2};await f.controller.control(null,f.control,f.transport);}
    advance(WINDOW);await f.controller.tick(f.transport);assert.equal(f.sends,0);assert.equal(f.quiesces,0);
  });

test('ticks and an observation cannot overlap a due recovery',async t=>{
  const {f,advance}=await setup(t),entered=deferred(),release=deferred();
  f.onSubmit=async()=>{entered.resolve();await release.promise;};
  advance(WINDOW);const first=f.controller.tick(f.transport);await entered.promise;
  const competing=[f.controller.tick(f.transport),f.controller.tick(f.transport),f.observe({status:'STALLED'})];
  assert.equal(f.sends,1);release.resolve();await Promise.all([first,...competing]);assert.equal(f.sends,1);
});

test('a pending diagnostic receipt cannot occupy the ten-minute recovery lane',async t=>{
  const {f,advance}=await setup(t),entered=deferred(),release=deferred();f.mode='unknown';
  advance(WINDOW);await f.controller.tick(f.transport);await f.settle();
  f.browser.readSubmission=async()=>{entered.resolve();await release.promise;return {state:'UNKNOWN'};};
  advance(1);const reading=f.controller.tick(f.transport);await entered.promise;await reading;
  advance(WINDOW);await f.controller.tick(f.transport);
  const sends=f.sends;release.resolve();await reading;
  assert.equal(sends,2,'receipt diagnostics must not prevent the due attempt');
});

test('input revealed by quiesce can receive the due continuation',async t=>{
  const {f,advance}=await setup(t);f.probe.composer=false;
  const quiesce=f.browser.quiesce;
  f.browser.quiesce=async args=>{await quiesce(args);f.probe.composer=true;return {...f.probe};};
  advance(WINDOW);await f.controller.tick(f.transport);assert.equal(f.sends,1);
});

test('late background receipt cannot replace the next recovery intent',async t=>{
  const {f,advance}=await setup(t),entered=deferred(),release=deferred();f.mode='unknown';
  advance(WINDOW);await f.controller.tick(f.transport);await f.settle();
  let reads=0;
  f.browser.readSubmission=async({intent})=>{
    if(++reads!==1)return {state:'UNKNOWN'};
    const probe={...f.probe};entered.resolve();await release.promise;
    return {state:'CONFIRMED',messageId:intent.message_id,probe};
  };
  advance(1);const reading=f.controller.tick(f.transport);await entered.promise;await reading;
  advance(WINDOW);await f.controller.tick(f.transport);await f.settle();
  const second=f.record().intent;release.resolve();await f.settle();
  assert.equal(f.record().intent.id,second.id);assert.equal(f.record().intent.outcome,'UNKNOWN');
  assert.equal(f.sends,2);
});

test('late background receipt cannot roll back newer observed progress',async t=>{
  const {f,advance,now}=await setup(t),entered=deferred(),release=deferred();f.mode='unknown';
  advance(WINDOW);await f.controller.tick(f.transport);await f.settle();
  f.browser.readSubmission=async({intent})=>{
    const probe={...f.probe};entered.resolve();await release.promise;
    return {state:'CONFIRMED',messageId:intent.message_id,probe};
  };
  advance(1);const reading=f.controller.tick(f.transport);await entered.promise;await reading;
  advance(1000);f.probe.responseFingerprint='new-visible-progress';await f.observe({});
  const progressAt=now();advance(1000);release.resolve();await f.settle();
  assert.equal(f.record().intent.outcome,'CONFIRMED');
  assert.equal(f.record().cursor.responseFingerprint,'new-visible-progress');
  assert.equal(f.record().last_progress_at,progressAt);
});

test('an interrupted durable recovery POST can reconcile and retry at its next deadline',async t=>{
  const {f,advance}=await setup(t);f.mode='unknown';
  advance(WINDOW);await f.controller.tick(f.transport);await f.settle();
  await f.controller.repository.move(f.record().key,'RECOVERY_POST','test interrupted projection',
    {intent:{...f.record().intent,outcome:'PENDING',message_id:null}});
  await f.controller.tick(f.transport);await f.settle();
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.record().intent.outcome,'UNKNOWN');
  assert.equal(f.sends,1);
  advance(WINDOW);await f.controller.tick(f.transport);assert.equal(f.sends,2);
});

test('a due interrupted recovery POST can proceed without waiting for reconciliation',async t=>{
  const {f,advance}=await setup(t);f.mode='unknown';
  advance(WINDOW);await f.controller.tick(f.transport);await f.settle();
  await f.controller.repository.move(f.record().key,'RECOVERY_POST','test interrupted projection',
    {intent:{...f.record().intent,outcome:'PENDING',message_id:null}});
  advance(WINDOW);await f.controller.tick(f.transport);assert.equal(f.sends,2);
});

test('background receipt metadata cannot overwrite an in-flight recovery transition',async t=>{
  const {f,advance}=await setup(t);f.mode='unknown';
  advance(WINDOW);await f.controller.tick(f.transport);await f.settle();
  const reading=deferred(),releaseRead=deferred();let readArgs;
  f.browser.readSubmission=async args=>{readArgs=args;reading.resolve();await releaseRead.promise;return {state:'UNKNOWN'};};
  await f.controller.tick(f.transport);await reading.promise;
  await f.observe({responseFingerprint:'new text'});
  const patching=deferred(),releasePatch=deferred(),patch=f.store.patch.bind(f.store);let paused=false;
  f.store.patch=async value=>{
    const r=Object.values(value.lifecycle?.requests||{})[0];
    if(!paused&&r?.reason==='PROGRESS_DEADLINE_REACHED'){paused=true;patching.resolve();await releasePatch.promise;}
    return patch(value);
  };
  advance(WINDOW);const tick=f.controller.tick(f.transport);await patching.promise;
  const callback=readArgs.onRequest({released:true});releasePatch.resolve();
  await Promise.allSettled([callback,tick]);const sends=f.sends;
  releaseRead.resolve();await f.settle();
  assert.equal(sends,2,'late receipt metadata must not restore OBSERVING during recovery');
});

test('recovery reaches the real send click without running the full response probe',async()=>{
  const calls=[];let clicks=0;
  const session={async call(method,p={}) {
    calls.push(method);
    assert.equal(method,'Runtime.evaluate');
    const source=p.expression;
    if(source.includes('document.body')||source.includes('createTreeWalker'))throw Error('full probe stalled');
    let value=true;
    if(source.includes('send.click()')){clicks++;value={status:'SUBMITTED'};}
    else if(source.includes('const expected='))value={status:'READY'};
    else if(source.includes("status:send?'SEND_FOUND'"))value={ready:true};
    else value={url:'https://chatgpt.com/c/owned',composer:true,streaming:false,stopButtonVisible:false};
    return {result:{value}};
  }};
  const browser=new ChatGptBrowser(null,{navigationTimeoutMs:1});
  await browser.submitIntent({session,prompt:'continue',kind:'recovery',intent:{id:'i',receipt_generation:'g',prompt:'continue'},
    conversationUrl:'https://chatgpt.com/c/owned',profileOperations:[{op:'SET',path:'model',value:'test'},
      {op:'SET',path:'thinking_effort',value:'max'},{op:'REMOVE',path:'conversation_origin'},{op:'REMOVE',path:'service_tier'}],
    guard:()=>true}).catch(()=>{});
  assert.equal(clicks,1,'the click must happen even if response observation is permanently broken');
  assert.equal(calls.every(method=>method==='Runtime.evaluate'),true,'no navigation, reload, or reconnect');
});
