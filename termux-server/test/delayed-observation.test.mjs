import test from 'node:test';
import assert from 'node:assert/strict';
import { CdpSession } from '../src/browser/cdp.mjs';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';
import { fixture as lifecycleFixture } from './helpers/lifecycle-fixture.mjs';

class Socket extends EventTarget {
  static OPEN = 1;
  constructor() {
    super(); this.readyState = 0; this.sent = [];
    queueMicrotask(() => { this.readyState = 1; this.dispatchEvent(new Event('open')); });
  }
  send(raw) { this.sent.push(JSON.parse(raw)); }
  receive(value) { this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(value) })); }
  close() { this.readyState = 3; this.dispatchEvent(new Event('close')); }
}
const settle = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
const probe = { url:'https://chatgpt.com/c/test-owned',composer:true,readyState:'complete',
  streaming:true,assistantCount:1,assistantTextLength:10,assistantMessageId:'a1',
  responseTurnId:'turn-1',responseTextLength:10,responseFingerprint:'new',userCount:1,userMessageId:'u1' };
async function fixture(t) {
  t.mock.property(globalThis,'WebSocket',Socket);
  t.mock.timers.enable({apis:['setTimeout','Date'],now:1700000000000});
  let now=0;t.mock.method(performance,'now',()=>now);
  const events=[];t.mock.method(console,'warn',line=>events.push(JSON.parse(line)));
  const session=await new CdpSession('ws://private?token=secret').connect();
  const browser=new ChatGptBrowser(null,{probeIntervalMs:1000,stallAfterMs:600000,navigationTimeoutMs:5000});
  const abort=new AbortController();
  t.after(()=>{abort.abort(Error('cleanup'));session.close();});
  const f={session,browser,abort,events,socket:session.socket,
    tick:async ms=>{await settle();now+=ms;t.mock.timers.tick(ms);await settle();},
    reply:(id,value=probe)=>session.socket.receive({id,result:{result:{value}}})};
  return f;
}

for(const elapsed of [5000,5357,15000,63088])test(`monitor consumes a valid ${elapsed} ms observation exactly once`,async t=>{
  const f=await fixture(t),seen=[];
  const monitoring=f.browser.monitor({session:f.session,baseline:{...probe,responseFingerprint:'old'},signal:f.abort.signal,
    livenessGate:()=>({state:'RUNNING',epoch:1}),onActivity:a=>{seen.push(a);f.abort.abort(Error('observed'));}}).catch(e=>e);
  await f.tick(elapsed);
  assert.equal(f.socket.sent.length,1,'a pending read must not cause overlapping retries');
  f.reply(1);await settle();
  assert.equal(seen.length,1,'valid late progress must not be discarded');
  assert.equal(seen[0].responseFingerprint,'new');assert.equal(seen[0].progressDetected,true);
  assert.equal((await monitoring).message,'observed');assert.equal(f.session.pending.size,0);
  assert.equal(f.events.filter(e=>e.event==='CDP_OBSERVATION_SLOW').length,1);
});

test('monitor and receipt reconciliation share the one in-flight progress read',async t=>{
  const f=await fixture(t),seen=[];
  const monitoring=f.browser.monitor({session:f.session,signal:f.abort.signal,onActivity:a=>seen.push(a)}).catch(e=>e);
  const receipt=f.browser.readSubmission({session:f.session,signal:f.abort.signal,intent:{},prompt:'private prompt',conversationUrl:probe.url});
  await f.tick(6000);assert.equal(f.socket.sent.length,1);
  f.reply(1);await settle();assert.equal((await receipt).state,'UNKNOWN');assert.equal(seen.length,1);
  f.abort.abort(Error('finished'));await monitoring;
});

test('aborting a pending observation immediately frees its timer and ignores its delayed reply',async t=>{
  const f=await fixture(t),seen=[];
  const monitoring=f.browser.monitor({session:f.session,signal:f.abort.signal,onActivity:a=>seen.push(a)}).catch(e=>e);
  await f.tick(1000);f.abort.abort(Error('STOPPED'));await settle();
  assert.equal(f.session.pending.size,0,'STOP must not wait for the read deadline');
  assert.equal((await monitoring).message,'STOPPED');
  f.reply(1);await settle();assert.equal(seen.length,0);
  await f.tick(100000);assert.equal(f.session.timedOutQueries.size,0);
});

test('a replaced observation generation cannot deliver its result to the new monitor',async t=>{
  const f=await fixture(t),seen=[];
  const old=f.browser.monitor({session:f.session,signal:f.abort.signal,onActivity:a=>seen.push(['old',a])}).catch(e=>e);
  await f.tick(6000);f.abort.abort(Error('new generation'));await old;
  const nextAbort=new AbortController();t.after(()=>nextAbort.abort());
  const next=f.browser.monitor({session:f.session,signal:nextAbort.signal,onActivity:a=>{seen.push(['new',a]);nextAbort.abort(Error('finished'));}}).catch(e=>e);
  await settle();f.reply(1,{...probe,responseFingerprint:'stale'});await settle();assert.equal(seen.length,0);
  f.reply(2);await next;assert.equal(seen.length,1);assert.equal(seen[0][0],'new');assert.equal(seen[0][1].responseFingerprint,'new');
});

test('a never-returning observation has a bounded deadline and permits a paced next read',async t=>{
  const f=await fixture(t);
  const monitoring=f.browser.monitor({session:f.session,signal:f.abort.signal}).catch(e=>e);
  await f.tick(74999);assert.equal(f.socket.sent.length,1);assert.equal(f.session.pending.size,1);
  await f.tick(1);assert.equal(f.session.pending.size,0);
  await f.tick(1000);assert.equal(f.socket.sent.length,2);assert.equal(f.session.pending.size,1);
  f.abort.abort(Error('finished'));await monitoring;assert.equal(f.session.pending.size,0);
});

test('ordinary input/effect commands keep their five-second deadline',async t=>{
  const f=await fixture(t);
  const call=f.session.call('Runtime.evaluate',{expression:'send.click()'},{timeoutMs:5000}).catch(e=>e);
  await f.tick(5000);assert.equal((await call).message,'CDP command timeout: Runtime.evaluate');
  assert.equal(f.session.pending.size,0);f.reply(1);assert.equal(f.socket.sent.length,1);
});

test('slow observation diagnostics distinguish engine time without logging page data',async t=>{
  const f=await fixture(t),seen=[];
  const monitoring=f.browser.monitor({session:f.session,signal:f.abort.signal,onActivity:a=>{seen.push(a);f.abort.abort(Error('finished'));}}).catch(e=>e);
  await f.tick(63088);
  f.reply(1,{...probe,__selfrun_engine_elapsed_ms:23});await monitoring;
  const event=f.events.find(e=>e.event==='CDP_OBSERVATION_REPLY');assert.ok(event);
  assert.equal(event.query_label,'progress');assert.equal(event.elapsed_ms,63088);
  assert.equal(event.engine_elapsed_ms,23);assert.equal(event.outside_engine_ms,63065);
  assert.doesNotMatch(JSON.stringify(f.events),/private|secret|test-owned|fingerprint|assistantMessageId/);
  assert.equal(seen[0].__selfrun_engine_elapsed_ms,undefined);
});

for(const boundary of ['STOPPED','DONE','COMMITTED'])test(`${boundary} fences a delayed real browser observation at the controller`,async t=>{
  const f=await fixture(t),controller=lifecycleFixture();
  controller.browser.resume=async()=>({session:f.session,target:{id:'target-owned'},baseline:controller.probe});
  controller.browser.monitor=f.browser.monitor.bind(f.browser);
  t.after(()=>controller.controller.quiesceForStandby());await controller.existing();
  await f.tick(6000);
  if(boundary==='COMMITTED'){controller.result(true);await controller.controller.repository.move(controller.record().key,'OBSERVING','test due reconciliation',{last_progress_at:Date.now()-600000});await controller.controller.tick(controller.transport);await controller.settle();}
  else {controller.control={...controller.control,state:boundary,control_epoch:2};await controller.controller.control(null,controller.control,controller.transport);}
  if(boundary==='COMMITTED')assert.equal(controller.record().state,'COMMITTED');
  const before=JSON.stringify(controller.record());f.reply(1);await controller.settle();
  assert.equal(JSON.stringify(controller.record()),before);assert.equal(controller.sends,0);assert.equal(f.session.pending.size,0);
});

test('a real pending browser read cannot prevent the independent ten-minute recovery',async t=>{
  const f=await fixture(t),controller=lifecycleFixture();
  controller.browser.resume=async()=>({session:f.session,target:{id:'target-owned'},baseline:controller.probe});
  controller.browser.monitor=f.browser.monitor.bind(f.browser);
  t.after(()=>controller.controller.quiesceForStandby());await controller.existing();
  await controller.controller.repository.move(controller.record().key,'OBSERVING','test imminent deadline',{last_progress_at:Date.now()-599000});
  await f.tick(1000);await controller.controller.tick(controller.transport);await controller.settle();
  assert.equal(controller.sends,1);assert.equal(f.session.pending.size,1);
  assert.equal(controller.prepares,0);assert.equal(controller.sent[0].kind,'recovery');
});

test('a reply beyond the hard deadline is rejected even if its timer callback is delayed',async t=>{
  const f=await fixture(t);let clock=0;t.mock.method(performance,'now',()=>clock);
  const monitoring=f.browser.monitor({session:f.session,signal:f.abort.signal}).catch(e=>e);
  await settle();clock=75001;f.reply(1);await settle();
  assert.equal(f.session.pending.size,0);
  assert.equal(f.events.some(e=>e.event==='CDP_OBSERVATION_REPLY'),false,'expired evidence must not look accepted');
  const timeout=f.events.find(e=>e.event==='CDP_QUERY_TIMEOUT');assert.ok(timeout);
  assert.equal(timeout.timeout_overrun_ms,1);assert.equal(f.session.timedOutQueries.size,0);
  f.abort.abort(Error('finished'));await monitoring;
});

test('successive aborts do not retain pending reads, listeners, or timers',async t=>{
  const {getEventListeners}=await import('node:events');const f=await fixture(t);
  for(let i=0;i<100;i++) {
    const abort=new AbortController(),seen=[];
    const monitoring=f.browser.monitor({session:f.session,signal:abort.signal,onActivity:a=>seen.push(a)}).catch(e=>e);
    await settle();assert.equal(f.session.pending.size,1);abort.abort(Error('STOPPED'));await monitoring;
    assert.equal(f.session.pending.size,0);assert.equal(getEventListeners(abort.signal,'abort').length,0);
    f.reply(i+1);assert.equal(seen.length,0);
  }
  await f.tick(80000);assert.equal(f.events.length,0);assert.equal(f.session.timedOutQueries.size,0);
});

test('a progress reply from before a recovery intent cannot update the new intent',async t=>{
  const f=await fixture(t),seen=[];let generation='old';
  const monitoring=f.browser.monitor({session:f.session,signal:f.abort.signal,
    livenessGate:()=>({state:'RUNNING',epoch:1,observationGeneration:generation}),onActivity:a=>seen.push(a)}).catch(e=>e);
  await settle();await f.tick(6000);generation='new';f.reply(1,{...probe,responseFingerprint:'stale'});await settle();
  assert.equal(seen.length,0,'a pre-recovery read cannot reset the new progress clock');
  assert.equal(f.socket.sent.length,2);f.reply(2);await settle();assert.equal(seen.length,1);
  assert.equal(seen[0].responseFingerprint,'new');f.abort.abort(Error('finished'));await monitoring;
});

test('receipt for a new recovery generation supersedes the old read and shares the new one',async t=>{
  const f=await fixture(t),seen=[];let generation='old';
  const monitoring=f.browser.monitor({session:f.session,signal:f.abort.signal,
    livenessGate:()=>({state:'RUNNING',epoch:1,observationGeneration:generation}),onActivity:a=>seen.push(a)}).catch(e=>e);
  await settle();await f.tick(6000);generation='new';
  const reading=f.browser.readSubmission({session:f.session,signal:f.abort.signal,intent:{receipt_generation:'new'},prompt:'x',conversationUrl:probe.url});
  await settle();assert.equal(f.socket.sent.length,2);assert.equal(f.session.pending.size,1);
  f.reply(1,{...probe,responseFingerprint:'stale'});await settle();assert.equal(seen.length,0);
  f.reply(2);await reading;await settle();assert.equal(seen.length,1);assert.equal(seen[0].responseFingerprint,'new');
  f.abort.abort(Error('finished'));await monitoring;
});

test('a queued observation cannot cross a recovery intent before its state application',async t=>{
  const f=lifecycleFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.observe({});
  const observed=f.observations.at(-1),key=f.record().key;
  let release;const held=new Promise(resolve=>{release=resolve;});f.controller.operations.set(key,held);
  const applying=observed.onActivity({...f.probe,responseFingerprint:'stale queued progress'},null);
  await f.controller.repository.move(key,'OBSERVING','fixture new intent',{intent:{id:'new',receipt_generation:'new-generation'},last_nudge_attempt_at:Date.now()});
  const before=f.record();release();await applying;await f.settle();
  assert.deepEqual(f.record().cursor,before.cursor);assert.equal(f.record().last_progress_at,before.last_progress_at);
});
