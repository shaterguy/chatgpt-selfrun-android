import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchController } from '../src/drive-controller.mjs';

function dispatch(overrides = {}) {
  return {
    schema: 'selfrun-server-dispatch-v1',
    client_status: 'CREATE_REQUESTED',
    server_status: 'PENDING',
    task_id: 'SR-TEST',
    turn_id: 'SR-TEST:turn:1',
    request_id: 'SR-TEST:turn:1-request',
    dispatch_attempt: 1,
    project_url: 'https://chatgpt.com/g/example/project',
    prompt: 'TASK_ID=SR-TEST',
    profile_operations: [
      { op: 'SET', path: 'model', value: 'gpt-5-6' },
      { op: 'REMOVE', path: 'thinking_effort' },
      { op: 'REMOVE', path: 'conversation_origin' },
      { op: 'REMOVE', path: 'service_tier' },
    ],
    ...overrides,
  };
}

class MemoryStateStore {
  constructor() {
    this.value = { generation: 0, lastSignalId: null };
  }
  snapshot() { return structuredClone(this.value); }
  async patch(changes) {
    this.value = { ...this.value, ...changes };
    return this.snapshot();
  }
  async patchIfCurrent(generation, signalId, changes) {
    if (this.value.generation !== generation || this.value.lastSignalId !== signalId) {
      return { applied: false, state: this.snapshot() };
    }
    this.value = { ...this.value, ...changes };
    return { applied: true, state: this.snapshot() };
  }
}

test('Drive dispatch preserves app claim boundary before browser send', async () => {
  const writes = [];
  let submitCalls = 0;
  const browser = {
    chromium: { closeTarget: async () => true },
    prepare: async () => ({
      target: { id: 'target-1' },
      session: { close() {} },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
    submitPrepared: async () => {
      submitCalls += 1;
      return { url: 'https://chatgpt.com/g/example/c/abc-123' };
    },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: '계속 진행해' },
  });
  const transport = {
    write: async (path, body) => writes.push({ path, body: structuredClone(body) }),
  };

  const body = dispatch();
  await controller.prepare('job/dispatch.json', body, transport);
  assert.equal(writes.at(-1).body.server_status, 'READY_TO_SUBMIT');
  assert.equal(submitCalls, 0);

  const claimed = dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'READY_TO_SUBMIT',
  });
  await controller.send('job/dispatch.json', claimed, transport);
  assert.equal(submitCalls, 1);
  assert.equal(writes.at(-1).body.server_status, 'STARTED');
  assert.equal(writes.at(-1).body.conversation_url, 'https://chatgpt.com/c/abc-123');
  assert.equal(stateStore.snapshot().status, 'RUNNING');
});
