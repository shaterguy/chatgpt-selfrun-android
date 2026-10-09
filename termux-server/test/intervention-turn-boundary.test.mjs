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

async function resolveIntervention(f) {
  await f.existing();f.resultReadIds.length=0;
  const required = interventionResult(f, 'USER_ACTION_REQUIRED');
  const originalObserver = f.observations.at(-1);
  await f.observe({});
  await f.controller.cleanupTerminalResources();
  assert.equal(f.record(),null);
  assert.equal(f.record(),null);
  assert.equal(originalObserver.signal.aborted, true, 'verified committed Result ends old-turn observation');
  assert.equal(f.controller.sessions.size, 0);
  assert.deepEqual(f.controller.repository.records(), []);
  assert.deepEqual(f.closedTargets, ['target-owned']);
  assert.ok(f.releases >= 1, 'the last owned browser process can be released');
  assert.deepEqual(f.resultReadIds, ['RESULT-1', 'RESULT-1'], 'retire only after exact committed readback');
  assert.equal(f.sends, 0);

  f.probe.paused = true;
  await appControl(f, 'WAITING_USER_INTERVENTION');
  assert.equal(f.record(),null, 'a later waiting signal cannot revive consumed work');
  assert.equal(f.record(),null);
  assert.equal(originalObserver.signal.aborted, true);
  assert.equal(f.controller.sessions.size, 0);

  const resolved = interventionResult(f, 'USER_ACTION_RESOLVED');
  for (const field of ['schema', 'task_id', 'turn_id', 'turn', 'document_id', 'event_id', 'committed']) {
    assert.equal(resolved[field], required[field], 'intervention resolution preserves ' + field);
  }
  await f.controller.tick(f.transport);
  assert.equal(f.controller.sessions.size, 0);
  assert.equal(f.sends, 0);

  // Resolution is app-owned after consumption. An old-identity RUNNING refresh
  // and disappearance of the UI pause cannot recreate its observer or input.
  f.probe.paused = false;
  f.probe.userCount += 1;
  f.probe.userMessageId = 'manual-intervention-resolution';
  f.control.conversation_url = f.probe.url;
  await appControl(f, 'RUNNING');
  await f.controller.tick(f.transport);
  assert.equal(f.controller.sessions.size, 0, 'old RUNNING cannot restore a committed observer');
  await f.observe({});

  return { required, resolved, originalObserver };
}

// Removing terminal Result consumption would leave the old observer/tab alive.
// Removing terminal replay fencing would let later old-identity controls revive it.
for (const restart of [false, true]) {
  test('intervention: committed old turn stays consumed through resolution and higher RUNNING epochs; restart=' + restart, async t => {
    const f = setup(t);
    await resolveIntervention(f);
    if (restart) {
      await f.controller.quiesceForStandby();
      f.controller = f.recreate();
      await f.controller.tick(f.transport);
    }
    await f.elapse();
    assert.equal(f.sends, 0);
    assert.equal(f.record(),null);
    assert.equal(f.record(),null);
    assert.equal(f.observations.at(-1).signal.aborted, true);
    assert.equal(f.controller.sessions.size, 0);
    assert.equal(f.record(),null);
    await appControl(f, 'RUNNING');
    await f.controller.tick(f.transport);
    f.body.dispatch_attempt += 1;
    await f.ingest();
    await f.elapse();
    assert.equal(f.sends, 0, 'a later old-turn control epoch does not bypass committed Result');

    assert.equal(f.resumes, 1, 'higher epochs and attempts cannot reopen consumed identity');
    assert.deepEqual(f.closedTargets, ['target-owned']);
    assert.deepEqual(f.controller.repository.records(), []);
    assert.equal(f.prepares, 0);
    assert.equal(f.quiesces, 0);
  });
}

test('intervention: app successor with new turn/request/Result starts through the normal handshake', async t => {
  const f = setup(t);
  const { resolved } = await resolveIntervention(f);
  await f.elapse();
  const oldKey = keyFor(f.body);
  const oldObserver = f.observations.at(-1);
  const oldUrl = f.probe.url;
  const next = {
    ...f.body,
    turn_id: 'SAFE-TEST:turn:2',
    request_id: 'SAFE-TEST:turn:2-request',
    result_document_id: 'RESULT-2',
    previous_result_document_id: 'RESULT-1',
    conversation_url: '',
    client_status: 'CREATE_REQUESTED',
    server_status: 'PENDING',
    dispatch_attempt: 1,
    server_control_epoch: f.control.control_epoch + 1,
  };
  f.result(false, 'RESULT-2', 2);
  f.control = { ...f.control, turn_id: next.turn_id, request_id: next.request_id, conversation_url: '',
    control_epoch: next.server_control_epoch };
  await f.controller.control(null, f.control, f.transport);
  assert.equal(oldObserver.signal.aborted, true);
  f.body = next;
  f.path = '__SELFRUN_DISPATCH__successor.json';
  f.probe.url = 'https://chatgpt.com/c/successor-owned';
  let resultReads = 0;
  const read = f.transport.readGoogleDocText;
  f.transport.readGoogleDocText = async id => { resultReads += 1; return read(id); };
  await f.ingest();
  assert.equal(f.sends, 0);
  assert.equal(f.record().state, 'PREPARED');
  assert.equal(f.rows.get(f.path).server_status, 'READY_TO_SUBMIT');
  f.body.client_status = 'SEND_REQUESTED';
  await f.ingest();
  assert.equal(f.sends, 1);
  assert.equal(f.sent[0].kind, 'initial');
  assert.equal(f.record().intent.outcome, 'CONFIRMED');
  assert.equal(f.record().conversation_url, f.probe.url);
  assert.notEqual(f.record().conversation_url, oldUrl);
  assert.equal(resultReads, 0, 'the server does not take over app successor/Result routing');
  assert.deepEqual(JSON.parse(f.docs.get('RESULT-1')), resolved);
  assert.equal(JSON.parse(f.docs.get('RESULT-2')).committed, false);
  assert.equal(f.controller.sessions.has(oldKey), false);
});

test('intervention: WAITING signal fences the old observer before its first Result read', async t => {
  const f = setup(t);
  await f.existing();
  const observer = f.observations.at(-1);
  assert.equal(f.record().result_state, 'UNKNOWN');
  let resultReads = 0;
  f.transport.readGoogleDocText = async () => { resultReads += 1; throw new Error('not published yet'); };
  await appControl(f, 'WAITING_USER_INTERVENTION');
  await observer.onActivity({ ...f.probe, status: 'STALLED' });
  await f.controller.tick(f.transport);
  assert.equal(resultReads, 0);
  assert.equal(f.record().state, 'PAUSED');
  assert.equal(observer.signal.aborted, true);
  assert.equal(f.controller.sessions.size, 0);
  assert.equal(f.sends, 0);
});

for (const committed of [false, true]) test('intervention: WAITING arriving during a pending Result read fences its late callback; committed=' + committed, async t => {
  const f = setup(t);
  await f.existing();
  if (committed) interventionResult(f, 'USER_ACTION_REQUIRED');
  const entered = deferred(), release = deferred();
  const raw = f.docs.get('RESULT-1');
  f.transport.readGoogleDocText = async () => { entered.resolve(); await release.promise; return raw; };
  const observing = f.observe({ status: 'STALLED' });
  await entered.promise;
  await appControl(f, 'WAITING_USER_INTERVENTION');
  const revision = f.record().revision;
  release.resolve();
  await observing;
  assert.equal(f.record().revision, revision);
  assert.equal(f.record().state, 'PAUSED');
  assert.equal(f.controller.sessions.size, 0);
  assert.deepEqual(f.closedTargets, [], 'a stale Result callback cannot retire a paused generation');
  assert.equal(f.sends, 0);
});

for (const unavailable of ['missing', 'invalid']) {
  test('intervention: consumed old Result is never reread or revived when later ' + unavailable, async t => {
    const f = setup(t);
    await resolveIntervention(f);
    if (unavailable === 'missing') f.docs.delete('RESULT-1');
    else f.docs.set('RESULT-1', '{incomplete replacement');
    await f.elapse();
    await f.controller.tick(f.transport);
    assert.equal(f.record(),null);

    assert.equal(f.sends, 0);
    assert.equal(f.record(),null);
    assert.equal(f.observations.at(-1).signal.aborted, true);
  });
  test('intervention: an unreadable Result without committed proof cannot authorize a nudge or retirement; ' + unavailable, async t => {
    const f = setup(t);
    await f.existing();
    if (unavailable === 'missing') f.docs.delete('RESULT-1');
    else f.docs.set('RESULT-1', '{incomplete replacement');
    await f.observe({});
    await f.elapse();
    await f.controller.tick(f.transport);
    assert.equal(f.record().result_state, 'UNAVAILABLE');
    assert.notEqual(f.record().retired, true);
    assert.equal(f.sends, 0);
    assert.equal(f.record().intent, null);
    assert.equal(f.observations.at(-1).signal.aborted, false);
    assert.deepEqual(f.closedTargets, []);
  });
}

test('intervention: committed Result arriving after durable recovery intent blocks the final submit', async t => {
  const f = setup(t);
  await f.existing();
  await f.observe({});
  const flush = f.controller.repository.flushCurrent.bind(f.controller.repository);
  let committedAtSubmitBoundary = false;
  f.controller.repository.flushCurrent = async (...args) => {
    await flush(...args);
    if (f.record().state === 'RECOVERY_POST') {
      committedAtSubmitBoundary = true;
      interventionResult(f, 'USER_ACTION_REQUIRED');
    }
  };
  await f.elapse();
  await f.controller.cleanupTerminalResources();
  assert.equal(committedAtSubmitBoundary, true, 'exercise the final Result recheck, not an earlier guard');
  assert.equal(f.record(),null);
  assert.equal(f.record(),null);
  assert.equal(f.sends, 0);
  assert.equal(f.controller.sessions.size, 0);
  assert.deepEqual(f.closedTargets, ['target-owned']);
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
