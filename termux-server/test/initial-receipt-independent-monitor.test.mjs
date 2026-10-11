import test from 'node:test';
import assert from 'node:assert/strict';
import { keyFor } from '../src/lifecycle.mjs';
import { fixture } from './helpers/lifecycle-fixture.mjs';

const TEN_MINUTES=600000;
function setup(t) {
  const f=fixture();
  f.mode='unknown';f.publishConversationBeforeOutcome=true;
  f.controller.config.stallAfterMs=TEN_MINUTES;
  f.closed=[];f.owned=new Set(['target-new','target-owned']);
  f.browser.chromium={isOwnedTarget:id=>f.owned.has(id),
    closeOwnedTarget:async(id,{guard})=>{
      if(!guard({id,url:f.probe.url}))return {closed:false};
      const closed=f.owned.delete(id);if(closed)f.closed.push(id);return {closed,absent:!closed};
    },releaseIfUnused:async()=>({released:false})};
  f.controller=f.recreate();
  t.after(()=>f.controller.quiesceForStandby());
  return f;
}

test('canonical URL starts observation and projects STARTED before initial ACK readback',async t=>{
  const f=setup(t);let observedDuringSubmit=false;
  f.onSubmit=async()=>{
    observedDuringSubmit=f.observations.length===1;
    assert.equal(f.rows.get(f.path).conversation_url,f.probe.url);
    assert.equal(f.rows.get(f.path).server_status,f.body.server_status);
    assert.equal(f.record().intent.outcome,'PENDING');
    assert.equal(f.record().submission_confirmed,false);
  };
  await f.ingest();
  assert.equal(observedDuringSubmit,true,'observation begins in onConversation, before submit returns');
  assert.equal(f.observations.length,1);
  assert.equal(f.record().intent.outcome,'UNKNOWN');
  assert.equal(f.rows.get(f.path).post_outcome,undefined);
  assert.equal(f.record().submission_confirmed,false);
  assert.equal(f.rows.get(f.path).server_status,f.body.server_status);
});

test('unknown initial receipt does not suppress visible response progress',async t=>{
  const f=setup(t);await f.ingest();
  await f.controller.repository.move(keyFor(f.body),f.record().state,'test old progress',{last_progress_at:1});
  await f.observe({assistantTextLength:1001,responseTextLength:1001,responseFingerprint:'new-visible-text'});
  assert.equal(f.record().cursor.responseTextLength,1001);
  assert.ok(f.record().last_progress_at>1);
  assert.equal(f.record().response_status,'RUNNING');
  assert.equal(f.record().intent.outcome,'UNKNOWN');
  assert.equal(f.record().submission_confirmed,false);
  assert.equal(f.sends,1);
});

test('restart resumes owned canonical conversation with unknown receipt without initial resend',async t=>{
  const f=setup(t);await f.ingest();await f.controller.quiesceForStandby();
  f.controller=f.recreate();await f.controller.tick(f.transport);
  assert.equal(f.observations.length,2);
  assert.equal(f.observations.at(-1).signal.aborted,false);
  assert.equal(f.resumes,1);assert.equal(f.prepares,1);assert.equal(f.sends,1);
  assert.equal(f.record().conversation_url,f.probe.url);
  assert.equal(f.record().intent.outcome,'UNKNOWN');
});

test('canonical URL never permits a duplicate initial send even if readback reports absence',async t=>{
  const f=setup(t);
  f.browser.readSubmission=async()=>({state:'ABSENT',probe:{...f.probe}});
  await f.ingest();await f.controller.tick(f.transport);
  assert.equal(f.sends,1);assert.equal(f.prepares,1);
  assert.equal(f.observations.length,1);
  assert.notEqual(f.record().intent.outcome,'CONFIRMED');
});

test('unknown initial receipt retains real ten-minute visible progress and recovery attempt deadlines',async t=>{
  let now=1700000000000;t.mock.method(Date,'now',()=>now);
  const f=setup(t);await f.ingest();await f.observe({});
  now+=TEN_MINUTES-1;await f.observe({status:'STALLED'});assert.equal(f.sends,1);
  now++;await f.observe({status:'RUNNING'});assert.equal(f.sends,2);
  assert.equal(f.sent[0].kind,'initial');assert.equal(f.sent[1].kind,'recovery');
  assert.equal(f.record().last_nudge_attempt_at,now);
  now+=TEN_MINUTES-1;await f.observe({status:'STALLED'});assert.equal(f.sends,2);
  now++;await f.observe({status:'RUNNING'});assert.equal(f.sends,3);
  assert.equal(f.sent.filter(s=>s.kind==='initial').length,1);
});

test('exact committed Result suppresses continuation while preserving the owned target and unknown-receipt observer',async t=>{
  const f=setup(t);await f.ingest();const observer=f.observations.at(-1);
  assert.ok(observer,'monitor attached before receipt confirmation');
  f.result(true);await f.stall();await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
  assert.equal(f.record().result_state,'COMMITTED');
  assert.equal(observer.signal.aborted,false);assert.equal(f.controller.sessions.size,1);
  assert.deepEqual(f.closed,[]);assert.equal(f.rows.get(f.path).server_status,f.body.server_status);
  await f.controller.tick(f.transport);assert.equal(f.sends,1);
});

test('STOP immediately retires unknown-receipt observer without rereading or resending input',async t=>{
  const f=setup(t);await f.ingest();const observer=f.observations.at(-1),readbacks=f.readbacks;
  assert.ok(observer,'monitor attached before receipt confirmation');
  await f.stop();await f.controller.cleanupTerminalResources();
  assert.equal(observer.signal.aborted,true);assert.equal(f.controller.sessions.size,0);
  assert.equal(f.record(),null);assert.deepEqual(f.closed,['target-new']);
  assert.equal(f.readbacks,readbacks);assert.equal(f.sends,1);assert.equal(f.quiesces,0);
});

test('waiting for user intervention retains unknown-receipt observation and suppresses recovery',async t=>{
  const f=setup(t);await f.ingest();const observer=f.observations.at(-1);
  assert.ok(observer,'monitor attached before receipt confirmation');
  f.control={...f.control,state:'WAITING_USER_INTERVENTION',control_epoch:2};
  await f.controller.control(null,f.control,f.transport);
  await observer.onActivity({...f.probe,status:'STALLED'});await f.controller.tick(f.transport);
  assert.equal(observer.signal.aborted,false);assert.equal(f.controller.sessions.size,1);
  assert.equal(f.sends,1);assert.equal(f.quiesces,0);
});

test('late initial ACK does not renew the visible-progress deadline',async t=>{
  let now=1700000000000;t.mock.method(Date,'now',()=>now);
  const f=setup(t);await f.ingest();await f.observe({});
  const progressAt=f.record().last_progress_at;
  now+=TEN_MINUTES-1;f.sentId=f.record().intent.message_id;
  await f.controller.tick(f.transport);
  assert.equal(f.record().intent.outcome,'CONFIRMED');
  assert.equal(f.record().last_progress_at,progressAt,'ACK is diagnostic, not visible progress');
  now++;await f.observe({status:'RUNNING'});assert.equal(f.sends,2);
});

test('STOP remains consumed across restart for an unknown initial receipt with an owned URL',async t=>{
  const f=setup(t);await f.ingest();const stale=structuredClone(f.rows.get(f.path));
  await f.stop();await f.controller.cleanupTerminalResources();
  f.controller=f.recreate();await f.controller.tick(f.transport);
  await f.controller.ingest(f.path,stale,f.transport);await f.controller.tick(f.transport);
  assert.equal(f.record(),null);assert.equal(f.controller.sessions.size,0);
  assert.equal(f.observations.length,1);assert.equal(f.resumes,0);assert.equal(f.prepares,1);assert.equal(f.sends,1);
});

for(const failure of ['input-lookup','input-lookup-timeout','result'])test(`unknown initial receipt cannot block paced recovery after transient ${failure} failure`,async t=>{
  let now=1700000000000;t.mock.method(Date,'now',()=>now);
  const f=setup(t);await f.ingest();await f.observe({});
  now+=TEN_MINUTES;
  const lookup=f.browser.recoveryInputSnapshot;
  if(failure!=='result')f.browser.recoveryInputSnapshot=async()=>{throw new Error(failure==='input-lookup'?'temporary input lookup error':'CDP command timeout: Runtime.evaluate');};
  if(failure==='result') {
    await f.controller.repository.move(keyFor(f.body),'STALLED','test quiet window elapsed');
    f.docs.set('RESULT-1','temporarily unreadable');
    await f.controller.tick(f.transport);
  } else await f.observe({status:'STALLED'});
  assert.equal(f.sends,1);
  assert.equal(f.record().intent.kind,'initial');assert.equal(f.record().intent.outcome,'UNKNOWN');
  const attempted=f.record().last_nudge_attempt_at;
  if(failure!=='result')assert.equal(attempted,now);
  f.result(false);f.probe.composer=true;
  f.browser.recoveryInputSnapshot=lookup;
  // Result failure has its own shorter read retry; a UI attempt consumes the full quiet window.
  now+=failure==='result'?30000:TEN_MINUTES-1;
  await f.controller.tick(f.transport);
  if(failure!=='result') {
    assert.equal(f.sends,1,'a failed UI attempt still consumes the full ten minutes');
    assert.equal(f.record().last_nudge_attempt_at,attempted);
    now++;
    await f.controller.tick(f.transport);
  }
  await f.observe({status:'STALLED'});
  assert.equal(f.sends,2,'existing conversation recovery resumes without an initial resend');
  assert.equal(f.sent[1].kind,'recovery');assert.equal(f.prepares,1);
  assert.notEqual(f.record().error?.message?.includes('invalid lifecycle transition'),true);
});

test('a late ACK readback cannot be overwritten by the older monitor sample',async t=>{
  let now=1700000000000;t.mock.method(Date,'now',()=>now);
  const f=setup(t);await f.ingest();await f.observe({});
  const oldProbe={...f.probe};
  f.probe={...f.probe,assistantTextLength:1000,responseTextLength:1000,responseFingerprint:'newer-readback'};
  f.browser.readSubmission=async({intent})=>({state:'CONFIRMED',messageId:intent.message_id,probe:{...f.probe}});
  now+=1000;await f.observe(oldProbe);
  assert.equal(f.record().intent.outcome,'CONFIRMED');
  assert.equal(f.record().cursor.responseTextLength,1000,'newer readback wins over the in-flight old monitor sample');
  assert.equal(f.record().cursor.responseFingerprint,'newer-readback');
  const progressed=f.record().last_progress_at;
  now+=1000;await f.observe({});
  assert.equal(f.record().last_progress_at,progressed,'the next sample does not count the same text twice');
});

test('startup monitor sample queued before initial readback cannot replay over the newer cursor',async t=>{
  let now=1700000000000;t.mock.method(Date,'now',()=>now);
  const f=setup(t),monitor=f.browser.monitor;let queued;
  f.browser.monitor=async args=>{
    queued=args.onActivity({...f.probe});
    return monitor(args);
  };
  f.browser.readSubmission=async({intent})=>({state:'CONFIRMED',messageId:intent.message_id,
    probe:{...f.probe,assistantTextLength:1000,responseTextLength:1000,responseFingerprint:'fresh-initial-readback'}});
  await f.ingest();await queued;
  assert.equal(f.record().intent.outcome,'CONFIRMED');
  assert.equal(f.record().cursor.responseTextLength,1000,'queued startup sample cannot overwrite newer confirmed readback');
  const progressed=f.record().last_progress_at;
  now+=1000;await f.observe({assistantTextLength:1000,responseTextLength:1000,responseFingerprint:'fresh-initial-readback'});
  assert.equal(f.record().last_progress_at,progressed);
});
