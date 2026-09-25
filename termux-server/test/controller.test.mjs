import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { StateStore } from '../src/state-store.mjs';
import { SelfRunController, buildPrompt, normalizeSignal } from '../src/controller.mjs';

function envelope(turn = 1) {
  return {
    TASK_ID: 'SR-TEST',
    TURN_ID: `SR-TEST:turn:${turn}`,
    REQUEST_ID: `SR-TEST:turn:${turn}-request`,
    SELF_RUN_SKILL_DOCUMENT_ID: 'skill-doc',
    RESULT_DOCUMENT_ID: `result-${turn}`,
    REQUIREMENT_DOCUMENT_ID: 'requirement-doc',
    ...(turn > 1 ? { PREVIOUS_RESULT_DOCUMENT_ID: `result-${turn - 1}` } : {}),
    PHASE: 'SHOULD_NOT_PASS',
  };
}

async function fixture() {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'selfrun-server-'));
  const config = {
    dataDir,
    stateFile: path.join(dataDir, 'state.json'),
    eventsFile: path.join(dataDir, 'events.jsonl'),
    tokenFile: path.join(dataDir, 'token'),
    defaultProjectUrl: '',
  };
  const stateStore = new StateStore(config);
  await stateStore.init();
  const calls = [];
  const closedTargets = [];
  const browser = {
    chromium: {
      closeTarget: async (id) => {
        closedTargets.push(id);
        return true;
      },
    },
    dispatch: async ({ prompt, signal, onTransition }) => {
      const id = `target-${calls.length + 1}`;
      calls.push({ id, prompt, signal });
      await onTransition('STAGED', { targetId: id });
      await onTransition('SENT', { targetId: id });
      return {
        target: { id },
        session: { close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0 },
      };
    },
    monitor: ({ signal }) => new Promise((resolve) => {
      if (signal.aborted) return resolve({ status: 'ABORTED' });
      signal.addEventListener('abort', () => resolve({ status: 'ABORTED' }), { once: true });
    }),
  };
  const controller = new SelfRunController({ browser, stateStore, config });
  return { controller, stateStore, calls, closedTargets, config };
}

const tick = () => new Promise((resolve) => setImmediate(resolve));

test('minimal envelope is rendered without informational fields', () => {
  const config = { defaultProjectUrl: '' };
  const signal = normalizeSignal({
    signalId: 's1',
    type: 'START',
    projectUrl: 'https://chatgpt.com/g/example/project',
    envelope: envelope(1),
  }, config);
  const prompt = buildPrompt(signal);
  assert.match(prompt, /^TASK_ID=SR-TEST/m);
  assert.match(prompt, /TURN_ID=SR-TEST:turn:1/);
  assert.doesNotMatch(prompt, /PHASE=/);
});

test('NEXT invalidates the old generation before starting the new one', async () => {
  const { controller, stateStore, calls, closedTargets } = await fixture();

  const first = await controller.accept({
    signalId: 'signal-1',
    type: 'START',
    projectUrl: 'https://chatgpt.com/g/example/project',
    envelope: envelope(1),
  });
  await tick();
  assert.equal(first.generation, 1);
  assert.equal(calls.length, 1);

  const second = await controller.accept({
    signalId: 'signal-2',
    type: 'NEXT',
    projectUrl: 'https://chatgpt.com/g/example/project',
    envelope: envelope(2),
  });
  await tick();

  assert.equal(second.generation, 2);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].signal.aborted, true);
  assert.ok(closedTargets.includes('target-1'));

  const state = stateStore.snapshot();
  assert.equal(state.generation, 2);
  assert.equal(state.lastSignalId, 'signal-2');
  assert.equal(state.activeSignal.envelope.TURN_ID, 'SR-TEST:turn:2');
});

test('duplicate signal is idempotent', async () => {
  const { controller, stateStore, calls } = await fixture();
  const body = {
    signalId: 'same-signal',
    type: 'START',
    projectUrl: 'https://chatgpt.com/g/example/project',
    envelope: envelope(1),
  };

  const first = await controller.accept(body);
  await tick();
  const second = await controller.accept(body);
  await tick();

  assert.equal(first.generation, 1);
  assert.equal(second.duplicate, true);
  assert.equal(second.generation, 1);
  assert.equal(calls.length, 1);
  assert.equal(stateStore.snapshot().generation, 1);
});

test('STOP aborts active monitoring and advances generation', async () => {
  const { controller, stateStore, calls } = await fixture();
  await controller.accept({
    signalId: 'start',
    type: 'START',
    projectUrl: 'https://chatgpt.com/g/example/project',
    envelope: envelope(1),
  });
  await tick();
  const stopped = await controller.accept({ signalId: 'stop', type: 'STOP' });
  await tick();

  assert.equal(calls[0].signal.aborted, true);
  assert.equal(stopped.generation, 2);
  assert.equal(stateStore.snapshot().status, 'STOP');
});

test('late state update from an old generation is rejected at write time', async () => {
  const { stateStore } = await fixture();
  await stateStore.patch({
    generation: 2,
    status: 'RUNNING',
    lastSignalId: 'signal-2',
  });

  const result = await stateStore.patchIfCurrent(1, 'signal-1', {
    status: 'OLD_EVENT_SHOULD_NOT_WIN',
  });

  assert.equal(result.applied, false);
  assert.equal(stateStore.snapshot().generation, 2);
  assert.equal(stateStore.snapshot().status, 'RUNNING');
  assert.equal(stateStore.snapshot().lastSignalId, 'signal-2');
});
