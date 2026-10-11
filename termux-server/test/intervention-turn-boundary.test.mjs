import test from 'node:test';
import assert from 'node:assert/strict';
import { keyFor } from '../src/lifecycle.mjs';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';

const TEN_MINUTES = 600000;

function setup(t) {
  let now = 1000000;
  t.mock.method(Date, 'now', () => now);
  const f = fixture();
  f.controller.config.stallAfterMs = TEN_MINUTES;
  f.probe.streaming = false;
  f.probe.stopButtonVisible = false;
  f.resultReadIds = [];
  const read = f.transport.readGoogleDocText;
  f.transport.readGoogleDocText = async id => { f.resultReadIds.push(id); return read(id); };
  f.pages = new Map();
  f.closedTargets = [];
  f.releases = 0;
  f.browser.chromium = {
    isOwnedTarget: id => f.pages.has(id),
    closeOwnedTarget: async (id, { guard }) => {
      if (!guard({ id, url: f.pages.get(id) })) return { closed: false, reason: 'guard' };
      const closed = f.pages.delete(id);
      if (closed) f.closedTargets.push(id);
      return { closed, absent: !closed };
    },
    releaseIfUnused: async ({ guard }) => {
      if (!guard() || f.pages.size) return { released: false };
      f.releases += 1;
      return { released: true };
    },
  };
  for (const method of ['prepare', 'resume', 'attachTarget']) {
    const open = f.browser[method];
    f.browser[method] = async args => {
      const attached = await open(args);
      f.pages.set(attached.target.id, f.probe.url);
      await args.onTarget?.(attached.target);
      return attached;
    };
  }
  f.controller = f.recreate();
  f.elapse = async (ms = TEN_MINUTES) => {
    now += ms;
    await f.observe({ status: 'RUNNING' });
  };
  t.after(() => f.controller.quiesceForStandby());
  return f;
}

function interventionResult(f, status) {
  const result = JSON.parse(f.docs.get('RESULT-1'));
  const resolved = status === 'USER_ACTION_RESOLVED';
  Object.assign(result, {
    status,
    committed: true,
    handoff_document_id: resolved ? 'HANDOFF-RESOLVED' : 'HANDOFF-REQUIRED',
    intervention: {
      requested_action: 'Choose the permitted next step',
      user_reported_complete: resolved,
      user_input_summary: resolved ? 'The user supplied the decision' : '',
      user_decisions: resolved ? ['Continue with the selected next step'] : [],
      additional_constraints: [],
      new_information: [],
      resume_action: 'Verify actual state in the new automatic turn',
    },
  });
  f.docs.set('RESULT-1', JSON.stringify(result));
  return result;
}

async function appControl(f, state) {
  f.control = { ...f.control, state, control_epoch: f.control.control_epoch + 1 };
  await f.controller.control(null, f.control, f.transport);
}

for(const restart of [false,true])test('committed intervention keeps observing through resolution and restart='+restart,async t=>{
  const f=setup(t);await f.existing();await f.observe({});const observer=f.observations.at(-1);
  interventionResult(f,'USER_ACTION_REQUIRED');await f.elapse();
  assert.equal(f.sends,0);assert.ok(f.record());assert.equal(observer.signal.aborted,false);assert.deepEqual(f.closedTargets,[]);
  assert.deepEqual(f.resultReadIds,['RESULT-1']);
  await appControl(f,'WAITING_USER_INTERVENTION');interventionResult(f,'USER_ACTION_RESOLVED');
  if(restart){await f.controller.quiesceForStandby();f.controller=f.recreate();await f.controller.tick(f.transport);}
  assert.equal(f.controller.sessions.size,1);await f.observe({responseTextLength:1200});
  assert.equal(f.record().cursor.responseTextLength,1200);assert.equal(f.observations.at(-1).signal.aborted,false);
  await appControl(f,'RUNNING');await f.elapse();assert.equal(f.sends,0,'committed still suppresses only input');
  assert.ok(f.record());assert.deepEqual(f.closedTargets,[]);
  f.result(false);await f.elapse();assert.equal(f.sends,1,'later false Result permits a continuation at a later quiet deadline');
});

test('intervention without committed proof skips only input and remains responsive to activity and STOP',async t=>{
  const f=setup(t);await f.existing();await f.observe({});const observer=f.observations.at(-1);
  await appControl(f,'WAITING_USER_INTERVENTION');await f.elapse();
  assert.equal(f.sends,0);assert.equal(f.resultReadIds.length,1);assert.equal(observer.signal.aborted,false);
  f.probe.responseTextLength=2000;await f.observe({});assert.equal(f.record().cursor.responseTextLength,2000);
  await f.elapse(599999);assert.equal(f.resultReadIds.length,1);await f.elapse(1);assert.equal(f.resultReadIds.length,2);
  await appControl(f,'STOPPED');await f.controller.cleanupTerminalResources();
  assert.equal(f.record(),null);assert.equal(observer.signal.aborted,true);assert.deepEqual(f.closedTargets,['target-owned']);
});

for(const committed of [false,true])test('WAITING during pending ten-minute Result read prevents input and retains observer; committed='+committed,async t=>{
  const f=setup(t);await f.existing();await f.observe({});f.result(committed);
  const entered=deferred(),release=deferred(),raw=f.docs.get('RESULT-1');
  f.transport.readGoogleDocText=async()=>{entered.resolve();await release.promise;return raw;};
  const pending=f.elapse();await entered.promise;await appControl(f,'WAITING_USER_INTERVENTION');
  release.resolve();await pending;
  assert.ok(f.record());assert.equal(f.record().control_state,'WAITING_USER_INTERVENTION');
  assert.equal(f.controller.sessions.size,1);assert.equal(f.observations.at(-1).signal.aborted,false);
  assert.deepEqual(f.closedTargets,[]);assert.equal(f.sends,0);
});

for(const unavailable of ['missing','invalid'])test('unreadable deadline Result keeps monitor alive without input: '+unavailable,async t=>{
  const f=setup(t);await f.existing();await f.observe({});
  if(unavailable==='missing')f.docs.delete('RESULT-1');else f.docs.set('RESULT-1','{invalid');
  await f.elapse();assert.equal(f.record().result_state,'UNAVAILABLE');assert.equal(f.sends,0);
  assert.equal(f.observations.at(-1).signal.aborted,false);assert.deepEqual(f.closedTargets,[]);
  await f.observe({responseTextLength:1222});assert.equal(f.record().cursor.responseTextLength,1222);
});

test('Result is checked once at the quiet deadline, without a second business gate before sending',async t=>{
  const f=setup(t);await f.existing();await f.observe({});
  const flush=f.controller.repository.flushCurrent.bind(f.controller.repository);let changed=false;
  f.controller.repository.flushCurrent=async(...args)=>{await flush(...args);if(f.record()?.state==='RECOVERY_POST'){changed=true;interventionResult(f,'USER_ACTION_REQUIRED');}};
  await f.elapse();assert.equal(changed,true);assert.equal(f.resultReadIds.length,1);assert.equal(f.sends,1);
  assert.ok(f.record());assert.equal(f.observations.at(-1).signal.aborted,false);
});

test('the app-selected next turn uses CREATE then SEND without predecessor Result consumption or readiness signal',async t=>{
  const f=setup(t);await f.existing();await f.observe({});interventionResult(f,'USER_ACTION_RESOLVED');await f.elapse();
  const oldKey=keyFor(f.body),oldObserver=f.observations.at(-1),oldResult=f.docs.get('RESULT-1');
  const next={...f.body,turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',result_document_id:'RESULT-2',previous_result_document_id:'RESULT-1',conversation_url:'',client_status:'CREATE_REQUESTED',server_status:'PENDING',server_control_epoch:2};
  f.control={...f.control,turn_id:next.turn_id,request_id:next.request_id,control_epoch:2};await f.controller.control(null,f.control,f.transport);
  f.body=next;f.path='__SELFRUN_DISPATCH__successor.json';f.probe.url='https://chatgpt.com/c/successor-owned';f.result(false,'RESULT-2',2);f.resultReadIds=[];
  await f.ingest();assert.equal(f.sends,0);assert.equal(f.record().state,'PREPARED');assert.equal(f.rows.get(f.path).server_status,'PENDING');
  f.body.client_status='SEND_REQUESTED';await f.ingest();assert.equal(f.sends,1);assert.equal(f.sent[0].kind,'initial');
  assert.deepEqual(f.resultReadIds,[]);assert.equal(f.docs.get('RESULT-1'),oldResult);assert.equal(oldObserver.signal.aborted,true);
  assert.equal(f.controller.sessions.has(oldKey),false);
});

// A new user-ID condition would incorrectly fail these ordinary liveness cases.
for (const userMessageId of [null, 'new-visible-user-id']) {
  test('ordinary running turn: each quiet ten-minute interval nudges without a user-ID gate; id=' + userMessageId, async t => {
    const f = setup(t);
    f.controller.config.recoveryPrompt = 'configured ordinary continuation';
    await f.existing();
    await f.observe({});
    const progressAt = f.record().last_progress_at;
    f.probe.userMessageId = userMessageId;
    f.probe.userCount += 1;
    for (let minute = 1; minute <= 9; minute += 1) {
      await f.elapse(60000);
      assert.equal(f.sends, 0);
      assert.equal(f.record().last_progress_at, progressAt);
    }
    await f.elapse(60000);
    assert.equal(f.sends, 1);
    assert.equal(f.sent[0].kind, 'recovery');
    assert.equal(f.sent[0].prompt, 'configured ordinary continuation');
    assert.equal(f.record().intent.outcome, 'CONFIRMED');
    await f.elapse();
    await f.controller.tick(f.transport);
    assert.equal(f.sends, 2, 'a fresh full quiet interval authorizes the next recovery attempt');
    assert.equal(f.record().error, null);
    assert.equal(f.prepares, 0);
  });
}
