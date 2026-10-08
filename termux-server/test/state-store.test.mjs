import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { StateStore } from '../src/state-store.mjs';

test('recordEvent preserves explicit dispatch identity when global state belongs to another task', async (t) => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'selfrun-state-store-'));
  t.after(async () => fs.rm(dataDir, { recursive: true, force: true }));

  const config = {
    dataDir,
    stateFile: path.join(dataDir, 'state.json'),
    eventsFile: path.join(dataDir, 'events.jsonl'),
  };
  const store = new StateStore(config);
  await store.init();
  await store.patch({
    generation: 2,
    status: 'RUNNING',
    activeSignal: {
      signalId: 'SR-B:turn:1-request:attempt:1',
      type: 'DRIVE_DISPATCH',
      envelope: { TURN_ID: 'SR-B:turn:1' },
    },
    conversationUrl: 'https://chatgpt.com/c/task-b',
    lastSignalId: 'SR-B:turn:1-request:attempt:1',
  });

  await store.recordEvent('LIVENESS_RESULT_CHECK', {
    task_id: 'SR-A',
    turn_id: 'SR-A:turn:1',
    request_id: 'SR-A:turn:1-request',
  }, {
    generation: 1,
    signalId: 'SR-A:turn:1-request:attempt:1',
    turnId: 'SR-A:turn:1',
    conversationUrl: 'https://chatgpt.com/c/task-a',
  });

  const rows = (await fs.readFile(config.eventsFile, 'utf8')).trim().split('\n');
  const event = JSON.parse(rows.at(-1));
  assert.equal(event.generation, 1);
  assert.equal(event.signalId, 'SR-A:turn:1-request:attempt:1');
  assert.equal(event.turnId, 'SR-A:turn:1');
  assert.equal(event.conversationUrl, 'https://chatgpt.com/c/task-a');
  assert.equal(event.details.task_id, 'SR-A');

  const current = store.snapshot();
  assert.equal(current.generation, 2);
  assert.equal(current.lastSignalId, 'SR-B:turn:1-request:attempt:1');
  assert.equal(current.conversationUrl, 'https://chatgpt.com/c/task-b');
});
