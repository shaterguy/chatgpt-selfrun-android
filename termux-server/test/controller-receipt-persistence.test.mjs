import test from 'node:test';
import assert from 'node:assert/strict';
import { keyFor } from '../src/lifecycle.mjs';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';

const stored = f => f.store.snapshot().lifecycle.requests[keyFor(f.body)];
const evidence = (intent, overrides = {}) => ({
  message_id: 'receipt-user', released: true,
  receipt: {version: 1, intent_id: intent.id, generation: intent.receipt_generation,
    request_id: 'local-observer-request', message_id: 'receipt-user', conversation_id: 'test-owned',
    ack: 'cot_token', invalid: null, ...overrides},
});
const confirmed = f => ({state: 'CONFIRMED', messageId: 'receipt-user', probe: {...f.probe}});

// Browser I/O is stubbed; assertions cover the real controller and durable lifecycle store.
test('receipt generation is persisted locally and projected before the initial send', async () => {
  const f = fixture(), submit = f.browser.submitIntent;
  f.browser.submitIntent = async args => {
    assert.equal(typeof args.intent.receipt_generation, 'string');
    assert.ok(args.intent.receipt_generation.length > 0);
    assert.equal(stored(f).intent.receipt_generation, args.intent.receipt_generation);
    assert.equal(f.rows.get(f.path).lifecycle_record.intent.receipt_generation, args.intent.receipt_generation);
    return submit(args);
  };
  await f.ingest();
  assert.equal(typeof f.record().intent.receipt_generation, 'string');
  assert.equal(f.sends, 1);
  assert.equal(f.record().intent.outcome, 'CONFIRMED');
});

for (const outcome of ['CONFIRMED', 'UNKNOWN']) {
  test(`reconciliation durably saves receipt metadata before returning ${outcome}`, async () => {
    const f = fixture(); f.mode = 'unknown';
    let expected;
    f.browser.readSubmission = async ({intent, onRequest, guard}) => {
      assert.equal(typeof guard, 'function');
      assert.equal(guard(), true);
      assert.equal(typeof onRequest, 'function');
      expected = evidence(intent);
      await onRequest(expected);
      assert.deepEqual(stored(f).intent.receipt, expected.receipt);
      assert.equal(stored(f).intent.outcome, 'UNKNOWN');
      return {...confirmed(f), state: outcome};
    };
    await f.ingest();
    assert.deepEqual(f.record().intent.receipt, expected.receipt);
    assert.deepEqual(f.rows.get(f.path).lifecycle_record.intent.receipt, expected.receipt);
    assert.equal(f.record().intent.outcome, outcome);
  });
}

test('a receipt persistence failure cannot reach confirmed state', async () => {
  const f = fixture(); f.mode = 'unknown';
  f.browser.readSubmission = async ({intent, onRequest}) => {
    assert.equal(typeof onRequest, 'function');
    f.store.fail = true;
    await onRequest(evidence(intent));
    return confirmed(f);
  };
  await assert.rejects(f.ingest(), /disk failed/);
  assert.notEqual(f.record().intent.outcome, 'CONFIRMED');
  assert.notEqual(stored(f).submission_confirmed, true);
  assert.equal(stored(f).intent.receipt, undefined);
});

test('retry changes receipt generation and clears the earlier request identity', async () => {
  const f = fixture(); let first;
  f.browser.submitIntent = async args => {
    f.sends++;
    if (f.sends === 1) {
      first = structuredClone(args.intent);
      await args.onRequest({...evidence(args.intent, {ack: null}), released: false});
      throw Object.assign(new Error('request aborted before release'),
        {released: false, absenceProof: 'INTERCEPTED_REQUEST_ABORTED'});
    }
    assert.equal(args.intent.id, first.id);
    assert.notEqual(args.intent.receipt_generation, first.receipt_generation);
    assert.equal(args.intent.message_id, null);
    assert.equal(args.intent.receipt, null);
    assert.equal(args.intent.absence_proof, null);
    assert.equal(args.intent.released, false);
    await args.onRequest(evidence(args.intent));
  };
  f.browser.readSubmission = async ({intent}) => {
    if (intent.sends === 1) return {state: 'ABSENT', probe: {...f.probe}};
    assert.equal(intent.receipt?.ack, 'cot_token');
    return confirmed(f);
  };
  await f.ingest();
  assert.equal(f.sends, 2);
  assert.equal(f.record().intent.outcome, 'CONFIRMED');
  assert.equal(f.record().intent.receipt.generation, f.record().intent.receipt_generation);
});

test('restart preserves acknowledged receipt generation and reconciles without another send', async () => {
  const f = fixture(); f.mode = 'unknown'; let saved;
  f.browser.readSubmission = async ({intent, onRequest}) => {
    assert.equal(typeof onRequest, 'function');
    saved = evidence(intent);
    await onRequest(saved);
    return {state: 'UNKNOWN', probe: {...f.probe}};
  };
  await f.ingest();
  const original = f.record();
  f.controller = f.recreate();
  f.browser.readSubmission = async ({intent, onRequest}) => {
    assert.equal(intent.receipt_generation, original.intent.receipt_generation);
    assert.deepEqual(intent.receipt, saved.receipt);
    await onRequest(saved);
    return confirmed(f);
  };
  await f.controller.tick(f.transport);
  assert.equal(f.sends, 1);
  assert.equal(f.prepares, 1);
  assert.ok(f.record().run_generation > original.run_generation);
  assert.equal(f.record().intent.receipt_generation, original.intent.receipt_generation);
  assert.deepEqual(f.record().intent.receipt, saved.receipt);
  assert.equal(f.record().intent.outcome, 'CONFIRMED');
});

for (const changed of ['intent', 'generation']) {
  test(`receipt callback rejects a changed ${changed} within the same browser generation`, async () => {
    const f = fixture(); f.mode = 'unknown'; let callback, oldIntent;
    f.browser.readSubmission = async ({intent, onRequest}) => {
      callback = onRequest; oldIntent = structuredClone(intent);
      return {state: 'UNKNOWN', probe: {...f.probe}};
    };
    await f.ingest();
    assert.equal(typeof callback, 'function');
    const next = {...f.record().intent,
      ...(changed === 'intent' ? {id: 'successor-intent'} : {receipt_generation: 'successor-generation'})};
    await f.controller.repository.move(keyFor(f.body), 'POST_UNCERTAIN', 'test successor', {intent: next});
    const before = f.record();
    await assert.rejects(callback(evidence(oldIntent)), /identity changed|stale/i);
    assert.deepEqual(f.record(), before);
  });
}

test('receipt callback rejects evidence from a different receipt generation', async () => {
  const f = fixture(); f.mode = 'unknown'; let rejected = false;
  f.browser.readSubmission = async ({intent, onRequest}) => {
    assert.equal(typeof onRequest, 'function');
    await assert.rejects(onRequest(evidence(intent, {generation: 'wrong-generation'})), /identity|generation/i);
    rejected = true;
    return {state: 'UNKNOWN', probe: {...f.probe}};
  };
  await f.ingest();
  assert.equal(rejected, true);
  assert.equal(f.record().intent.receipt, undefined);
});

for (const fence of ['STOP', 'successor', 'leadership']) {
  test(`${fence} fences the original receipt callback without late writes`, async () => {
    const f = fixture(); f.mode = 'unknown'; let callback, oldIntent;
    f.browser.readSubmission = async ({intent, onRequest}) => {
      callback ||= onRequest; oldIntent ||= structuredClone(intent);
      return {state: 'UNKNOWN', probe: {...f.probe}};
    };
    await f.ingest();
    assert.equal(typeof callback, 'function');
    if (fence === 'STOP') await f.stop();
    if (fence === 'successor') {
      f.control = {...f.control, control_epoch: 2, request_id: 'new-request', turn_id: 'SAFE-TEST:turn:2'};
      await f.controller.controlDurable(null, f.control, f.transport);
    }
    if (fence === 'leadership') f.controller.canDispatch = () => false;
    const before = f.store.snapshot();
    await assert.rejects(callback(evidence(oldIntent)), /stale/i);
    assert.deepEqual(f.store.snapshot(), before);
  });
}

test('leadership loss during receipt persistence prevents confirmed success', async () => {
  const f = fixture(); f.mode = 'unknown';
  const patch = f.store.patch.bind(f.store), entered = deferred(), release = deferred();
  f.store.patch = async value => {
    if (value.lifecycle?.requests[keyFor(f.body)]?.reason === 'POST_REQUEST_IDENTIFIED'
        && value.lifecycle.requests[keyFor(f.body)].intent.receipt?.ack) {
      entered.resolve(); await release.promise;
    }
    return patch(value);
  };
  f.browser.readSubmission = async ({intent, onRequest}) => {
    assert.equal(typeof onRequest, 'function');
    await onRequest(evidence(intent));
    return confirmed(f);
  };
  const running = f.ingest();
  await Promise.race([entered.promise, running]);
  f.controller.canDispatch = () => false;
  release.resolve();
  await assert.rejects(running, /stale/i);
  assert.notEqual(f.record().intent.outcome, 'CONFIRMED');
  assert.notEqual(f.record().submission_confirmed, true);
});

test('STOP readback has a live STOP guard and rejects newly discovered receipt metadata', async () => {
  const f = fixture(); f.mode = 'unknown';
  await f.ingest();
  let stopArgs;
  f.browser.readSubmission = async args => {
    stopArgs = args;
    await args.onRequest(evidence(args.intent));
    return confirmed(f);
  };
  await f.stop();
  assert.equal(typeof stopArgs?.guard, 'function');
  assert.equal(stopArgs.guard(), true);
  assert.equal(typeof stopArgs.onRequest, 'function');
  assert.equal(f.record().state, 'STOPPED');
  assert.equal(f.record().stop_status, 'UNCONFIRMED');
  assert.equal(f.quiesces, 0);
  assert.equal(f.controller.repository.records().length, 0);
});

for (const crash of ['before', 'after']) {
  test(`restart ${crash} receipt metadata commit never resends the request`, async () => {
    const f = fixture(); f.mode = 'unknown';
    f.browser.readSubmission = async ({intent, onRequest}) => {
      if (crash === 'before') f.store.fail = true;
      await onRequest(evidence(intent));
      throw new Error('simulated process exit after metadata commit');
    };
    await assert.rejects(f.ingest(), crash === 'before' ? /disk failed/ : /simulated process exit/);
    const original = stored(f).intent;
    assert.equal(original.receipt?.ack, crash === 'after' ? 'cot_token' : undefined);
    f.store.fail = false;
    f.controller = f.recreate();
    f.browser.readSubmission = async ({intent}) => {
      assert.equal(intent.receipt_generation, original.receipt_generation);
      assert.deepEqual(intent.receipt, original.receipt);
      return {...confirmed(f), state: intent.receipt?.ack ? 'CONFIRMED' : 'UNKNOWN'};
    };
    await f.controller.tick(f.transport);
    assert.equal(f.sends, 1);
    assert.equal(f.prepares, 1);
    assert.equal(f.record().intent.outcome, crash === 'after' ? 'CONFIRMED' : 'UNKNOWN');
  });
}

test('STOP may reuse exactly the receipt already durable before retirement', async () => {
  const f = fixture(); f.mode = 'unknown'; let saved;
  f.browser.readSubmission = async ({intent, onRequest}) => {
    saved = evidence(intent);
    await onRequest(saved);
    return {state: 'UNKNOWN', probe: {...f.probe}};
  };
  await f.ingest();
  let reused = false;
  f.browser.readSubmission = async ({onRequest, guard}) => {
    assert.equal(guard(), true);
    await onRequest(saved);
    reused = true;
    return confirmed(f);
  };
  await f.stop();
  assert.equal(reused, true);
  assert.equal(f.record().stop_status, 'CONFIRMED');
  assert.equal(f.quiesces, 1);
  assert.equal(f.controller.repository.records().length, 0);
});

test('successor control fences a pending STOP receipt callback and quiesce', async () => {
  const f = fixture(); f.mode = 'unknown';
  await f.ingest();
  const entered = deferred(), release = deferred(); let stopArgs;
  f.browser.readSubmission = async args => {
    stopArgs = args; entered.resolve(); await release.promise;
    return confirmed(f);
  };
  const stopping = f.stop();
  await entered.promise;
  assert.equal(stopArgs.guard(), true);
  f.control = {...f.control, state: 'RUNNING', control_epoch: 3, turn_id: 'SAFE-TEST:turn:2', request_id: 'successor'};
  await f.controller.controlDurable(null, f.control, f.transport);
  assert.equal(stopArgs.guard(), false);
  await assert.rejects(stopArgs.onRequest(evidence(stopArgs.intent)), /stale STOP/);
  release.resolve(); await stopping;
  assert.equal(f.quiesces, 0);
  assert.equal(f.record().stop_status, 'UNCONFIRMED');
  assert.equal(f.controller.repository.records().length, 0);
});
