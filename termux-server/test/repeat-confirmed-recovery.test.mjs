import test from 'node:test';
import assert from 'node:assert/strict';
import { keyFor } from '../src/lifecycle.mjs';
import { fixture, MemoryStore, deferred } from './helpers/lifecycle-fixture.mjs';

const WINDOW = 600000;

// Exercise the real controller/store with deterministic time; only browser/Drive I/O is synthetic.
function clockFixture(t) {
  const originalNow = Date.now;
  let now = 1700000000000;
  Date.now = () => now;
  const f = fixture();
  f.controller.config.stallAfterMs = WINDOW;
  t.after(async () => {
    try { await f.controller.quiesceForStandby(); }
    finally { Date.now = originalNow; }
  });
  return {f, advance: ms => { now += ms; }, now: () => now};
}

async function start(f) {
  await f.existing();
  await f.observe({status: 'RUNNING'});
}

async function restart(f, source = 'local') {
  const projected = structuredClone(f.rows.get(f.path));
  await f.controller.quiesceForStandby();
  if (source === 'Drive') f.store = new MemoryStore();
  f.controller = f.recreate();
  if (source === 'Drive') await f.controller.ingest(f.path, projected, f.transport);
  else await f.controller.tick(f.transport);
}

const receipt = intent => ({
  message_id: intent.message_id, released: true,
  receipt: {version: 1, intent_id: intent.id, generation: intent.receipt_generation,
    request_id: 'synthetic-late-request', message_id: intent.message_id,
    conversation_id: 'test-owned', ack: 'cot_token', invalid: null},
});

test('unchanged confirmed recovery sends one fresh intent per complete ten-minute interval', async t => {
  const {f, advance, now} = clockFixture(t);
  await start(f);
  const ids = new Set(), generations = new Set();
  for (let interval = 1; interval <= 3; interval++) {
    const quietSince = f.record().last_progress_at;
    for (let minute = 1; minute <= 9; minute++) {
      advance(60000);
      await f.observe({status: 'RUNNING'});
      assert.equal(f.sends, interval - 1, 'minute observations cannot trigger another send');
      assert.equal(f.record().last_progress_at, quietSince);
    }
    advance(59999);
    await f.observe({status: 'RUNNING'});
    assert.equal(f.sends, interval - 1);
    advance(1);
    await f.observe({status: 'RUNNING'});
    const r = f.record();
    assert.equal(f.sends, interval);
    assert.equal(r.state, 'OBSERVING');
    assert.equal(r.intent.outcome, 'CONFIRMED');
    assert.equal(r.intent.sends, 1);
    assert.equal(r.recovery_count, interval);
    assert.equal(r.last_nudge_attempt_at, now());
    assert.equal(ids.has(r.intent.id), false);
    assert.equal(generations.has(r.intent.receipt_generation), false);
    ids.add(r.intent.id); generations.add(r.intent.receipt_generation);
    assert.equal(f.rows.get(f.path).lifecycle_record.intent.id, r.intent.id);
    await Promise.all([f.observe({status: 'RUNNING'}), f.observe({status: 'RUNNING'})]);
    assert.equal(f.sends, interval, 'same-time observations cannot flood');
  }
});

test('stale browser STALLED labels cannot bypass the renewed durable deadline', async t => {
  const {f, advance} = clockFixture(t);
  await start(f);
  advance(WINDOW); await f.observe({status: 'RUNNING'});
  for (let minute = 1; minute <= 9; minute++) {
    advance(60000); await f.observe({status: 'STALLED'});
    assert.equal(f.sends, 1);
    assert.notEqual(f.record().state, 'BLOCKED');
  }
  advance(60000); await f.observe({status: 'STALLED'});
  assert.equal(f.sends, 2);
});

for (const source of ['local', 'Drive']) {
  test(`${source} restart preserves confirmed attempt identity and remaining quiet time`, async t => {
    const {f, advance} = clockFixture(t);
    await start(f);
    advance(WINDOW); await f.observe({status: 'RUNNING'});
    const confirmed = f.record();
    advance(540000); await restart(f, source);
    assert.equal(f.record().last_progress_at, confirmed.last_progress_at);
    assert.equal(f.record().last_nudge_attempt_at, confirmed.last_nudge_attempt_at);
    assert.deepEqual(f.record().intent, confirmed.intent);
    advance(59999); await f.observe({status: 'STALLED'});
    assert.equal(f.sends, 1);
    assert.equal(f.record().intent.id, confirmed.intent.id);
    advance(1); await f.observe({status: 'STALLED'});
    assert.equal(f.sends, 2);
    assert.notEqual(f.record().intent.id, confirmed.intent.id);
    assert.notEqual(f.record().intent.receipt_generation, confirmed.intent.receipt_generation);
  });
}

test('late receipt from a confirmed attempt cannot overwrite the next uncertain attempt', async t => {
  const {f, advance} = clockFixture(t), submissions = [];
  const submit = f.browser.submitIntent;
  f.browser.submitIntent = async args => { submissions.push(args); return submit(args); };
  await start(f);
  advance(WINDOW); await f.observe({status: 'RUNNING'});
  const first = f.record().intent;
  f.mode = 'unknown';
  advance(WINDOW); await f.observe({status: 'RUNNING'});
  assert.equal(f.sends, 2);
  assert.equal(f.record().intent.outcome, 'UNKNOWN');
  const before = f.store.snapshot();
  await assert.rejects(submissions[0].onRequest(receipt(first)), /identity changed|stale/i);
  assert.deepEqual(f.store.snapshot(), before);
  assert.notEqual(f.record().intent.id, first.id);
});

for (const outcome of ['UNKNOWN', 'PENDING', 'ABSENT']) {
  test(`${outcome} recovery gets a fresh attempt only after its durable ten-minute window across restart`, async t => {
    const {f, advance, now} = clockFixture(t);
    await start(f); f.mode = 'unknown';
    advance(WINDOW); await f.observe({status: 'RUNNING'});
    const first = f.record();
    assert.equal(first.last_nudge_attempt_at, now());
    if (outcome !== 'UNKNOWN') {
      const snapshot = f.store.snapshot(), r = snapshot.lifecycle.requests[keyFor(f.body)];
      r.intent.outcome = outcome;
      r.state = outcome === 'PENDING' ? 'RECOVERY_POST' : 'POST_UNCERTAIN';
      if (outcome === 'ABSENT') Object.assign(r.intent, {released: false, absence_proof: 'INTERCEPTED_REQUEST_ABORTED'});
      await f.controller.quiesceForStandby(); await f.store.patch(snapshot);
      f.controller = f.recreate(); await f.controller.tick(f.transport);
    }
    for (let minute = 1; minute <= 9; minute++) {
      advance(60000);
      if (minute === 5) await restart(f);
      await f.controller.tick(f.transport);
      await f.observe({status: 'STALLED'});
      assert.equal(f.sends, 1);
      assert.equal(f.record().intent.id, first.intent.id);
      assert.equal(f.record().intent.receipt_generation, first.intent.receipt_generation);
      assert.equal(f.record().last_nudge_attempt_at, first.last_nudge_attempt_at);
    }
    advance(60000); await f.observe({status: 'STALLED'});
    const second = f.record();
    assert.equal(f.sends, 2);
    assert.equal(second.recovery_count, 2);
    assert.equal(second.last_nudge_attempt_at, now());
    assert.notEqual(second.intent.id, first.intent.id);
    advance(60000); await f.observe({status: 'STALLED'});
    assert.equal(f.sends, 2);
    advance(540000); await f.observe({status: 'STALLED'});
    assert.equal(f.sends, 3);
  });
}

test('late confirmation does not postpone the next recovery attempt deadline', async t => {
  const {f, advance} = clockFixture(t);
  await start(f); f.mode = 'unknown';
  advance(WINDOW); await f.observe({status: 'RUNNING'});
  const first = f.record();
  advance(540000); f.sentId = first.intent.message_id;
  await f.observe({status: 'STALLED'});
  assert.equal(f.record().intent.outcome, 'CONFIRMED');
  assert.equal(f.record().last_progress_at, first.last_progress_at);
  assert.equal(f.record().last_nudge_attempt_at, first.last_nudge_attempt_at);
  advance(60000); await f.observe({status: 'STALLED'});
  assert.equal(f.sends, 2);
});

test('initial UNKNOWN submission remains fenced across recovery intervals and restart', async t => {
  const {f, advance} = clockFixture(t); f.mode = 'unknown';
  await f.ingest(); const first = f.record();
  for (let interval = 1; interval <= 3; interval++) {
    advance(WINDOW); await restart(f); await f.controller.tick(f.transport);
    assert.equal(f.sends, 1);
    assert.equal(f.record().intent.id, first.intent.id);
    assert.equal(f.record().intent.receipt_generation, first.intent.receipt_generation);
    assert.equal(f.record().state, 'POST_UNCERTAIN');
    assert.equal(f.record().intent.outcome, 'UNKNOWN');
    assert.equal(f.record().last_nudge_attempt_at, undefined);
  }
});

for (const mode of ['success', 'unknown']) test(`actual same-length response progress resets the deadline after a ${mode} nudge`, async t => {
  const {f, advance, now} = clockFixture(t);
  await start(f); f.mode = mode;
  advance(WINDOW); await f.observe({status: 'RUNNING'});
  advance(540000); f.probe.responseFingerprint = 'new-real-response-content';
  await f.observe({status: 'STALLED'});
  assert.equal(f.record().last_progress_at, now());
  advance(WINDOW - 1); await f.observe({status: 'STALLED'});
  assert.equal(f.sends, 1);
  advance(1); await f.observe({status: 'STALLED'});
  assert.equal(f.sends, 2);
});

for (const boundary of ['STOPPED', 'WAITING_USER_INTERVENTION', 'COMMITTED']) {
  test(`${boundary} still suppresses subsequent interval nudges`, async t => {
    const {f, advance} = clockFixture(t);
    await start(f);
    advance(WINDOW); await f.observe({status: 'RUNNING'});
    if (boundary === 'COMMITTED') f.result(true);
    else {
      f.control = {...f.control, state: boundary, control_epoch: 2};
      await f.controller.control(null, f.control, f.transport);
    }
    for (let interval = 1; interval <= 3; interval++) {
      advance(WINDOW); await f.observe({status: 'STALLED'});
      await f.controller.tick(f.transport);
      assert.equal(f.sends, 1);
    }
  });
}

test('old permanent no-progress recovery blocks resume the interval policy after restart', async t => {
  const {f, advance} = clockFixture(t);
  await start(f); advance(WINDOW); await f.observe({status: 'RUNNING'});
  const first = f.record();
  await f.controller.repository.move(keyFor(f.body), 'BLOCKED', 'RECOVERY_ALREADY_APPLIED_NO_PROGRESS', {
    blocked_epoch: first.control_epoch,
    error: {code: 'RECOVERY_NO_PROGRESS', message: 'legacy one-shot policy'},
  });
  advance(540000); await restart(f);
  assert.notEqual(f.record().state, 'BLOCKED');
  assert.equal(f.record().last_nudge_attempt_at, first.last_nudge_attempt_at);
  assert.equal(f.sends, 1);
  advance(60000); await f.observe({status: 'STALLED'});
  assert.equal(f.sends, 2);
});

test('recovery attempt time and identity are durable locally and in Drive before browser submission', async t => {
  const {f, advance, now} = clockFixture(t), submit = f.browser.submitIntent;
  let inspected = false;
  f.browser.submitIntent = async args => {
    const local = f.store.snapshot().lifecycle.requests[keyFor(f.body)];
    const remote = f.rows.get(f.path).lifecycle_record;
    assert.equal(local.last_nudge_attempt_at, now());
    assert.equal(remote.last_nudge_attempt_at, now());
    assert.equal(local.intent.id, args.intent.id);
    assert.equal(remote.intent.receipt_generation, args.intent.receipt_generation);
    inspected = true;
    return submit(args);
  };
  await start(f); advance(WINDOW); await f.observe({status: 'RUNNING'});
  assert.equal(inspected, true);
});


test('old receipt queued behind the next durable intent cannot overwrite that successor', async t => {
  const {f, advance} = clockFixture(t), submissions = [];
  const submit = f.browser.submitIntent;
  f.browser.submitIntent = async args => { submissions.push(args); return submit(args); };
  await start(f); advance(WINDOW); await f.observe({status: 'RUNNING'});
  const first = f.record().intent;
  const entered = deferred(), release = deferred(), patch = f.store.patch.bind(f.store);
  f.store.patch = async value => {
    const r = value.lifecycle?.requests[keyFor(f.body)];
    if (r?.reason === 'SUBMISSION_INTENT_DURABLE' && r.intent.id !== first.id) {
      entered.resolve(); await release.promise;
    }
    return patch(value);
  };
  advance(WINDOW);
  const posting = f.observe({status: 'RUNNING'});
  await entered.promise;
  const oldCallback = assert.rejects(submissions[0].onRequest(receipt(first)), /identity changed|stale/i);
  release.resolve();
  await Promise.all([posting, oldCallback]);
  assert.equal(f.sends, 2);
  assert.notEqual(f.record().intent.id, first.id);
  assert.equal(f.record().intent.outcome, 'CONFIRMED');
});
