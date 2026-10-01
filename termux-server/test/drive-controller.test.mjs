import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { keyFor } from '../src/lifecycle.mjs';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';
test('CREATE_REQUESTED is sufficient authority for server-owned prepare and submit',async()=>{
 const f=fixture();await f.ingest();
 assert.equal(f.sends,1);
 assert.equal(f.record().state,'OBSERVING');
 assert.equal(f.record().intent?.outcome,'CONFIRMED');
});
test('matching RUNNING Task Control recovers an attempt-level client cancellation',async()=>{
 const f=fixture();
 await f.controller.controlDurable(null,f.control);
 f.body.client_status='CANCELLED';
 f.rows.set(f.path,structuredClone(f.body));
 const seeded=await f.controller.repository.ingest(f.path,f.body);
 await f.controller.repository.move(seeded.key,'CANCELLED','test attempt timeout');
 await f.controller.tick(f.transport);
 assert.equal(f.sends,1);
 assert.equal(f.record().state,'OBSERVING');
 assert.equal(f.record().intent?.outcome,'CONFIRMED');
});
test('STOPPED Task Control keeps a client-cancelled request terminal',async()=>{
 const f=fixture();
 f.control={...f.control,state:'STOPPED',control_epoch:2};
 await f.controller.controlDurable(null,f.control);
 f.body.client_status='CANCELLED';
 f.rows.set(f.path,structuredClone(f.body));
 const seeded=await f.controller.repository.ingest(f.path,f.body);
 if(f.controller.repository.get(seeded.key).state!=='CANCELLED')
   await f.controller.repository.move(seeded.key,'CANCELLED','test user stop');
 await f.controller.tick(f.transport);
 assert.equal(f.sends,0);
 assert.equal(f.record().state,'CANCELLED');
});
test('initial POST does not wait for unrelated full projection backlog',async()=>{
 const f=fixture();
 f.controller.repository.flush=async()=>{throw new Error('unexpected full projection flush');};
 await f.ingest();
 assert.equal(f.sends,1);
 assert.equal(f.record().state,'OBSERVING');
});
test('repair metadata is opaque and current RUNNING dispatch executes unchanged',async()=>{
 const f=fixture();
 f.body.turn_id='SAFE-TEST:turn:3';f.body.request_id='SAFE-TEST:turn:3-request';
 f.control.turn_id=f.body.turn_id;f.control.request_id=f.body.request_id;
 f.body.previous_result_document_id='OLDER-RESULT';
 f.body.prompt+='REPAIR_TARGET_DOCUMENT_ID=TARGET-RESULT\nREPAIR_REASON=RESULT_ROUTING_INVALID\n';
 f.result(false,'RESULT-1',3);
 await f.ingest();
 assert.equal(f.prepares,1);assert.equal(f.sends,1);assert.equal(f.record().state,'OBSERVING');
});

test('restart recovers uncertain initial POST from another conversation target before original target attach',async()=>{
 const f=fixture();f.mode='unknown';await f.ingest();
 assert.equal(f.record().state,'POST_UNCERTAIN');assert.equal(f.record().intent?.message_id,'message-1');
 let crossReads=0,targetAttaches=0;
 f.probe={...f.probe,url:'https://chatgpt.com/c/recovered-cross-target',streaming:false,stopButtonVisible:false,
   userCount:2,userMessageId:'message-1',responseTurnId:'message-1',responseTextLength:0,responseFingerprint:''};
 f.browser.findSubmissionAcrossTargets=async({intent,prompt})=>{
   crossReads++;assert.equal(intent.message_id,'message-1');assert.equal(prompt,f.record().intent.prompt);
   return {state:'CONFIRMED',messageId:'message-1',probe:{...f.probe}};
 };
 f.browser.attachTarget=async()=>{targetAttaches++;throw Object.assign(new Error('old target gone'),{code:'ATTACH_FAILED'});};
 f.controller=f.recreate();
 await f.controller.tick(f.transport);
 assert.equal(crossReads,1);assert.equal(targetAttaches,0);
 assert.equal(f.record().conversation_url,'https://chatgpt.com/c/recovered-cross-target');
 assert.equal(f.record().intent?.outcome,'CONFIRMED');
 assert.equal(f.record().state,'OBSERVING');
 assert.equal(f.resumes,1);
});

test('missing or unknown Task control cannot attach even with legacy bypass configured',async()=>{
 for(const state of ['UNKNOWN','PAUSED','STOPPED','DONE']) {
  const f=fixture();f.control.state=state;f.controller.config.allowUnknownControlRecovery=true;
  await f.existing();assert.equal(f.resumes,0);assert.equal(f.sends,0);
 }
});
test('Result committed is authoritative even with stopped or stale browser state',async()=>{
 const f=fixture();f.control.state='STOPPED';f.result(true);await f.existing();
 assert.equal(f.record().state,'COMPLETED');assert.equal(f.resumes,0);
});
test('paused generation requires explicit continuation and sends nothing',async()=>{
 const f=fixture();f.probe.paused=true;await f.existing();await f.stall();
 assert.equal(f.record().error.code,'GENERATION_PAUSED');assert.equal(f.sends,0);
});
test('cursor advancement during stall verification prevents recovery',async()=>{
 const f=fixture();await f.existing();await f.observe({});
 f.browser.livenessSnapshot=async()=>({...f.probe,responseTextLength:2000});
 await f.controller.repository.move(keyFor(f.body),'OBSERVING','elapsed',{last_progress_at:0});
 await f.observe({status:'STALLED'});assert.equal(f.sends,0);assert.equal(f.record().state,'OBSERVING');
});
test('Result lookup failure retains stall time and recovery stage',async()=>{
 const f=fixture();await f.existing();await f.observe({});
 await f.controller.repository.move(keyFor(f.body),'STALLED','elapsed',{last_progress_at:123});
 f.docs.set('RESULT-1','invalid');await f.controller.tick(f.transport);
 assert.equal(f.record().last_progress_at,123);assert.equal(f.sends,0);
 f.result(false);await f.controller.tick(f.transport);assert.equal(f.sends,1);
});
test('readback unknown keeps ownership and never opens a fresh conversation',async()=>{
 const f=fixture();f.mode='unknown';await f.existing();await f.stall();
 for(let i=0;i<3;i++)await f.controller.tick(f.transport);
 assert.equal(f.sends,1);assert.equal(f.resumes,1);assert.equal(f.prepares,0);
 assert.equal(f.record().conversation_url,f.probe.url);
});
test('STOP while browser attach is pending rejects late attachment',async()=>{
 const f=fixture(),entered=deferred(),gate=deferred();let closed=0;
 f.browser.resume=async()=>{entered.resolve();await gate.promise;return {target:{id:'late'},session:{close(){closed++;}},baseline:f.probe};};
 const opening=f.existing();await entered.promise;await f.stop();gate.resolve();await opening;
 assert.equal(f.record().state,'STOPPED');assert.equal(f.sends,0);assert.equal(closed,1);
});
test('browser completion hint alone cannot finalize the turn',async()=>{
 const f=fixture();f.browser.monitor=async()=>({status:'COMPLETED'});await f.existing();
 await new Promise(r=>setImmediate(r));assert.notEqual(f.record().state,'COMPLETED');
});
test('higher dispatch attempt preserves logical request and canonical ownership',async()=>{
 const f=fixture();await f.existing();f.body.dispatch_attempt=2;await f.ingest();
 assert.equal(f.record().dispatch_attempt,2);assert.equal(f.resumes,1);assert.equal(f.sends,0);
});
test('standby fences existing callbacks without changing canonical conversation',async()=>{
 const f=fixture();await f.existing();const observer=f.observations.at(-1);
 await f.controller.quiesceForStandby();const before=f.record();
 await observer.onActivity({...f.probe,responseTextLength:10000});
 assert.equal(f.record().revision,before.revision);assert.equal(f.record().conversation_url,before.conversation_url);
});

test('browser monitor does not treat Stop button visibility changes as liveness activity', async () => {
  const originalNow = Date.now;
  let now = 1000;
  let evaluateCalls = 0;
  let stalledCalls = 0;
  let forcePageError = false;
  const baseProbe = {
    url: 'https://chatgpt.com/c/abc-123',
    composer: true,
    streaming: false,
    stopButtonVisible: false,
    paused: false,
    assistantCount: 0,
    assistantTextLength: 0,
    assistantMessageId: null,
    userCount: 1,
    userTextLength: 10,
    userMessageId: 'data-testid:conversation-turn-1',
    errorText: null,
  };
  const session = {
    call: async (method) => {
      assert.equal(method, 'Runtime.evaluate');
      evaluateCalls += 1;
      now += 6;
      if (forcePageError) {
        return { result: { value: {
          ...baseProbe,
          composer: false,
          userCount: 0,
          userTextLength: 0,
          userMessageId: null,
        } } };
      }
      const value = evaluateCalls === 2 || evaluateCalls === 3
        ? { ...baseProbe, streaming: true, stopButtonVisible: true }
        : baseProbe;
      return { result: { value } };
    },
  };
  Date.now = () => now;
  try {
    const browser = new ChatGptBrowser(null, {
      stallAfterMs: 10,
      probeIntervalMs: 1,
      conversationStateGraceMs: 0,
    });
    const result = await browser.monitor({
      session,
      baseline: {
        assistantCount: 0,
        assistantTextLength: 0,
        userCount: 1,
        userTextLength: 10,
        userMessageId: 'data-testid:conversation-turn-1',
      },
      livenessGate: async () => ({ state: 'RUNNING', epoch: 1 }),
      onActivity: async (activity) => {
        if (activity.status === 'STALLED') {
          stalledCalls += 1;
          forcePageError = true;
          return { resetLiveness: true };
        }
      },
    });
    assert.equal(stalledCalls, 1);
    assert.equal(result.status, 'PAGE_ERROR');
  } finally {
    Date.now = originalNow;
  }
});

test('browser monitor schedules stalled verification retries without resetting real activity time', async () => {
  let evaluateCalls = 0;
  let stalledCalls = 0;
  let forcePageError = false;
  const stalledTimes = [];
  const baseProbe = {
    url: 'https://chatgpt.com/c/abc-123',
    composer: true,
    streaming: false,
    paused: false,
    assistantCount: 0,
    assistantTextLength: 0,
    assistantMessageId: null,
    userCount: 1,
    userTextLength: 10,
    userMessageId: 'data-testid:conversation-turn-1',
    errorText: null,
  };
  const session = {
    call: async (method) => {
      assert.equal(method, 'Runtime.evaluate');
      evaluateCalls += 1;
      if (forcePageError) {
        return { result: { value: {
          ...baseProbe,
          composer: false,
          userCount: 0,
          userTextLength: 0,
          userMessageId: null,
        } } };
      }
      return { result: { value: baseProbe } };
    },
  };
  const browser = new ChatGptBrowser(null, {
    stallAfterMs: 1,
    probeIntervalMs: 2,
    conversationStateGraceMs: 0,
  });
  const result = await browser.monitor({
    session,
    baseline: {
      assistantCount: 0,
      assistantTextLength: 0,
      userCount: 1,
      userTextLength: 10,
      userMessageId: 'data-testid:conversation-turn-1',
    },
    livenessGate: async () => ({ state: 'RUNNING', epoch: 1 }),
    onActivity: async (activity) => {
      if (activity.status !== 'STALLED') return;
      stalledCalls += 1;
      stalledTimes.push(activity.lastActivityAt);
      if (stalledCalls === 1) {
        assert.equal(activity.verificationRetry, false);
        return { retryAfterMs: 1 };
      }
      assert.equal(activity.verificationRetry, true);
      forcePageError = true;
      return { resetLiveness: true };
    },
  });
  assert.equal(stalledCalls, 2);
  assert.equal(stalledTimes[0], stalledTimes[1]);
  assert.equal(result.status, 'PAGE_ERROR');
});

test('another logical request cannot take over a live canonical conversation',async()=>{
 const f=fixture();await f.existing();
 const body={...f.body,request_id:'different-request'};
 f.rows.set('other.json',body);
 await assert.rejects(f.controller.repository.ingest('other.json',body),/already owned/);assert.equal(f.resumes,1);
});

test('conversation acquisition also enforces atomic ownership',async()=>{
 const f=fixture();await f.existing();const body={...f.body,request_id:'other',conversation_url:''};
 f.rows.set('other.json',body);const other=await f.controller.repository.ingest('other.json',body);
 await assert.rejects(f.controller.repository.move(other.key,'ATTACHING','claim',{conversation_url:f.probe.url}),/already owned/);
});
test('parallel task STOP and event identity remain isolated',async()=>{
 const f=fixture(),controls=new Map(),bodies=[];
 for(const [task,url] of [['SAFE-A','https://chatgpt.com/c/owned-a'],['SAFE-B','https://chatgpt.com/c/owned-b']]){
  const b={...f.body,task_id:task,turn_id:task+':turn:1',request_id:task+':turn:1-request',conversation_url:url,result_document_id:task+'-result'};
  const p='__SELFRUN_DISPATCH__'+task+'.json';f.rows.set(p,b);bodies.push([p,b]);
  controls.set('__SELFRUN_CONTROL__'+task+'.json',{...f.control,task_id:task,turn_id:b.turn_id,request_id:b.request_id});
  f.docs.set(b.result_document_id,JSON.stringify({schema:'selfrun-turn-result-v3',task_id:task,turn_id:b.turn_id,turn:1,document_id:b.result_document_id,event_id:b.turn_id+':result',committed:false}));
 }
 f.transport.read=async p=>structuredClone(controls.get(p)||f.rows.get(p));
 f.browser.resume=async({conversationUrl})=>({target:{id:conversationUrl},session:{close(){}},baseline:{...f.probe,url:conversationUrl}});
 await Promise.all(bodies.map(([p,b])=>f.controller.ingest(p,b,f.transport)));
 assert.equal(f.controller.sessions.size,2);
 const stopped={...controls.get('__SELFRUN_CONTROL__SAFE-A.json'),state:'STOPPED',control_epoch:2};
 controls.set('__SELFRUN_CONTROL__SAFE-A.json',stopped);await f.controller.control(null,stopped,f.transport);
 assert.equal(f.controller.repository.get(keyFor(bodies[0][1])).state,'STOPPED');
 assert.equal(f.controller.repository.get(keyFor(bodies[1][1])).state,'OBSERVING');
 const observer=f.observations.find(x=>x.baseline.url.endsWith('owned-b'));
 await observer.onActivity({...f.probe,url:'https://chatgpt.com/c/owned-b',responseTextLength:1234});
 const events=f.store.events.filter(x=>x.event==='CONVERSATION_TRANSITION'&&x.details.reason==='RESPONSE_PROGRESS');
 assert.equal(events.at(-1).details.task_id,'SAFE-B');
 assert.equal(f.controller.repository.get(keyFor(bodies[0][1])).state,'STOPPED');
});
