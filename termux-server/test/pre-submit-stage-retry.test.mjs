import test from 'node:test';
import assert from 'node:assert/strict';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';
import { fixture } from './helpers/lifecycle-fixture.mjs';

const baseTime=1791698204077;
const stages=['recoveryInput','installProfile','profileConfigure','installReceipt','input','receiptArm','sendReady'];
function setup(t,{failing,failures=1,afterSend=false}={}) {
  let now=baseTime;
  t.mock.method(Date,'now',()=>now);
  const f=fixture();
  Object.assign(f,{calls:[],submitCalls:0,submitIntentCalls:0,readbacks:0,inputLength:0,failuresLeft:failures});
  f.probe={url:f.body.project_url,composer:true,readyState:'complete',streaming:false,stopButtonVisible:false,paused:false,userCount:0,assistantCount:0,userTextLength:0,assistantTextLength:0};
  let armed=null,receipt=null;
  f.session={transport:'extension',close(){},async run(operation,payload={}){
    f.calls.push(operation);
    if(operation===failing&&f.failuresLeft>0){f.failuresLeft--;throw Object.assign(new Error('Extension connection replaced'),{code:'EXTENSION_DISCONNECTED',released:operation==='submit'});}
    if(operation==='snapshot'||operation==='progress'||operation==='recoveryInput')return {...f.probe};
    if(operation==='receiptSnapshot')return receipt;
    if(operation==='input'){f.inputLength=payload.prompt.length;return {status:'READY',length:f.inputLength};}
    if(operation==='receiptArm'){armed=payload.key;return true;}
    if(operation==='sendReady')return {ready:true,status:'SEND_FOUND'};
    if(operation==='submit'){
      f.submitCalls++;
      if(afterSend)throw Object.assign(new Error('Extension connection replaced'),{code:'EXTENSION_DISCONNECTED',released:true});
      f.probe={...f.probe,url:'https://chatgpt.com/c/fixture-owned',userCount:1,streaming:true,stopButtonVisible:true};
      receipt={version:1,intent_id:armed.intent_id,generation:armed.generation,request_id:armed.generation+':1',
        message_id:'92dd19de-1242-4d48-8f34-413e8ed42aa4',conversation_id:'fixture-owned',ack:'cot_token',invalid:null,released:true};
      return {status:'SUBMITTED'};
    }
    return true;
  }};
  f.real=new ChatGptBrowser(null,{navigationTimeoutMs:10});
  f.browser.prepare=async()=>{f.prepares++;return {target:{id:'target-new'},session:f.session,baseline:{...f.probe}};};
  f.browser.livenessSnapshot=async()=>({...f.probe});
  f.browser.submitIntent=async args=>{f.submitIntentCalls++;return f.real.submitIntent(args);};
  f.browser.readSubmission=async args=>{f.readbacks++;return f.real.readSubmission(args);};
  f.advance=async(ms=1000)=>{now+=ms;await f.controller.tick(f.transport);await f.settle();};
  t.after(()=>f.controller.quiesceForStandby());
  return f;
}

for(const failing of stages)test(`retries ${failing} disconnect on the same target, then submits once`,async t=>{
  const f=setup(t,{failing});await f.ingest();
  assert.equal(f.record().state,'POST_PENDING');
  assert.equal(f.record().reason,'PRE_SUBMIT_RETRY');
  assert.equal(f.record().intent.outcome,'ABSENT');
  assert.equal(f.record().intent.absence_proof,'SUBMIT_NOT_CALLED');
  assert.equal(f.record().intent.pre_submit_stage,failing);
  assert.equal(f.record().intent.sends,0);assert.equal(f.record().intent.released,false);
  assert.equal(f.submitCalls,0);assert.equal(f.readbacks,0);
  await f.advance();
  assert.equal(f.submitCalls,1);assert.equal(f.submitIntentCalls,2);assert.equal(f.prepares,1);
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.record().submission_confirmed,true);
  assert.equal(f.record().intent.sends,1);assert.equal(f.record().intent.outcome,'CONFIRMED');
  assert.equal(f.record().intent.pre_submit_stage,null);
  assert.equal(f.observations.length,1);
});

test('a saved proven pre-submit retry resumes the same owned target after a server restart',async t=>{
  const f=setup(t,{failing:'input'});await f.ingest();
  let attaches=0;
  f.browser.attachTarget=async({targetId})=>{attaches++;assert.equal(targetId,'target-new');return {target:{id:targetId},session:f.session,baseline:{...f.probe}};};
  await f.controller.quiesceForStandby();f.controller=f.recreate();
  await f.advance();
  assert.equal(attaches,1);assert.equal(f.prepares,1);assert.equal(f.submitCalls,1);
  assert.equal(f.record().submission_confirmed,true);
});

test('repeated pre-submit disconnects do not exhaust actual send budget',async t=>{
  const f=setup(t,{failing:'input',failures:4});await f.ingest();
  for(let n=0;n<3;n++){await f.advance();assert.equal(f.submitCalls,0);assert.equal(f.record().intent.sends,0);}
  await f.advance();assert.equal(f.submitCalls,1);assert.equal(f.submitIntentCalls,5);
  assert.equal(f.record().submission_confirmed,true);assert.equal(f.prepares,1);
});

test('a disconnect during submit stays uncertain and is never automatically replayed',async t=>{
  const f=setup(t,{failing:null,afterSend:true});await f.ingest();
  assert.equal(f.submitCalls,1);assert.equal(f.record().state,'POST_UNCERTAIN');
  assert.equal(f.record().intent.released,true);assert.equal(f.record().intent.outcome,'UNKNOWN');
  for(let n=0;n<3;n++)await f.advance(600000);
  assert.equal(f.submitCalls,1);assert.equal(f.submitIntentCalls,1);
});

for(const terminal of ['STOPPED','DONE'])test(`${terminal} before the retry discards execution and never restarts it`,async t=>{
  const f=setup(t,{failing:'input'});await f.ingest();
  const before=f.calls.length;
  f.control={...f.control,state:terminal,control_epoch:2};
  await f.controller.control(null,f.control,f.transport);
  await f.advance(600000);
  assert.equal(f.submitCalls,0);assert.equal(f.calls.length,before);
  assert.equal(f.controller.sessions.size,0);assert.equal(f.record(),null);
});

test('ordinary released=false errors from another browser implementation are not trusted as local pre-submit proof',async t=>{
  const f=setup(t,{failing:null});
  f.browser.submitIntent=async()=>{throw Object.assign(new Error('unproven failure'),{released:false,pre_submit_stage:'input',absenceProof:'SUBMIT_NOT_CALLED'});};
  await f.ingest();await f.advance(600000);
  assert.equal(f.record().state,'POST_UNCERTAIN');assert.equal(f.submitCalls,0);
  assert.equal(f.record().intent.outcome,'UNKNOWN');
});

for(const stoppingAt of stages)test(`STOP during ${stoppingAt} allows no later execution stage`,async t=>{
  const f=setup(t,{failing:null}),abort=new AbortController();
  const run=f.session.run.bind(f.session);
  f.session.run=async(operation,payload)=>{
    const value=await run(operation,payload);
    if(operation===stoppingAt)abort.abort(new Error('app STOP'));
    return value;
  };
  await assert.rejects(f.real.submitIntent({session:f.session,prompt:'test input',kind:'initial',
    intent:{id:'i',receipt_generation:'g',prompt:'test input',baseline:{}},conversationUrl:'',profileOperations:[],
    signal:abort.signal,guard:()=>!abort.signal.aborted,onRequest:async()=>{}}));
  const stopped=f.calls.indexOf(stoppingAt);
  assert.deepEqual(f.calls.slice(stopped+1).filter(operation=>operation!=='receiptRetire'),[]);
  assert.equal(f.submitCalls,0);
});
