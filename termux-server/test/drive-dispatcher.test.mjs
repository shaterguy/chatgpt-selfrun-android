import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatcher } from '../src/drive/dispatcher.mjs';

const flush = () => new Promise((resolve) => setImmediate(resolve));

class FakeStore {
  constructor() {
    this.revision = 1;
    this.path = 'SR-TEST/dispatch-1.selfrun-dispatch.json';
    this.value = {
      schema: 'selfrun-drive-dispatch-v1',
      task_id: 'SR-TEST',
      turn_id: 'SR-TEST:turn:1',
      request_id: 'SR-TEST:turn:1-request',
      attempt: 1,
      project_url: 'https://chatgpt.com/g/example/project',
      prompt: 'TASK_ID=SR-TEST',
      profile_operations: [
        { op: 'SET', path: 'model', value: 'model-x' },
        { op: 'REMOVE', path: 'thinking_effort' },
        { op: 'REMOVE', path: 'conversation_origin' },
        { op: 'REMOVE', path: 'service_tier' },
      ],
      state: 'PREPARE_REQUESTED',
      conversation_url: '',
    };
  }

  async list() {
    return [{
      path: this.path,
      modTime: String(this.revision),
      size: JSON.stringify(this.value).length,
    }];
  }

  async read(path) {
    assert.equal(path, this.path);
    return structuredClone(this.value);
  }

  async write(path, value) {
    assert.equal(path, this.path);
    this.value = structuredClone(value);
    this.revision += 1;
  }

  async mutate(path, expected, mutate) {
    assert.equal(path, this.path);
    const current = structuredClone(this.value);
    if (expected?.requestId && current.request_id !== expected.requestId) {
      return { applied: false, reason: 'request_changed', current };
    }
    if (expected?.attempt != null && Number(current.attempt) !== Number(expected.attempt)) {
      return { applied: false, reason: 'attempt_changed', current };
    }
    if (expected?.states && !expected.states.includes(current.state)) {
      return { applied: false, reason: 'state_changed', current };
    }
    const next = mutate(current);
    this.value = structuredClone(next);
    this.revision += 1;
    return { applied: true, current: structuredClone(this.value) };
  }

  externalState(state) {
    this.value.state = state;
    this.revision += 1;
  }
}

class FakeBrowser {
  constructor() {
    this.prepareCalls = 0;
    this.submitCalls = 0;
    this.closed = [];
    this.monitorSignal = null;
    this.chromium = {
      closeTarget: async (id) => { this.closed.push(id); return true; },
    };
  }

  async prepare(options) {
    this.prepareCalls += 1;
    assert.equal(options.prompt, 'TASK_ID=SR-TEST');
    return {
      target: { id: 'target-1' },
      session: { close() {} },
      markerKey: 'marker',
      baseline: {},
    };
  }

  async submitPrepared(prepared, options) {
    this.submitCalls += 1;
    assert.equal(prepared.target.id, 'target-1');
    await options.onSubmitted();
    return {
      url: 'https://chatgpt.com/c/conversation-1',
      probe: {},
    };
  }

  async attachConversation() {
    throw new Error('reattach should not be used in this test');
  }

  async monitor(options) {
    this.monitorSignal = options.signal;
    return new Promise((resolve) => {
      if (options.signal.aborted) return resolve({ status: 'ABORTED' });
      options.signal.addEventListener('abort', () => resolve({ status: 'ABORTED' }), { once: true });
    });
  }
}

class FakeStateStore {
  constructor() { this.value = {}; }
  async patch(changes) {
    this.value = { ...this.value, ...changes };
    return this.value;
  }
}

test('Drive dispatch performs prepare, claimed send, canonical start, and committed retirement', async () => {
  const store = new FakeStore();
  const browser = new FakeBrowser();
  const stateStore = new FakeStateStore();
  const dispatcher = new DriveDispatcher({
    store,
    browser,
    stateStore,
    config: { drivePollMs: 1000 },
  });

  await dispatcher.tick();
  await flush();
  await flush();
  assert.equal(browser.prepareCalls, 1);
  assert.equal(store.value.state, 'READY_TO_SUBMIT');

  store.externalState('SEND_REQUESTED');
  await dispatcher.tick();
  await flush();
  await flush();
  assert.equal(browser.submitCalls, 1);
  assert.equal(store.value.state, 'STARTED');
  assert.equal(store.value.conversation_url, 'https://chatgpt.com/c/conversation-1');
  assert.equal(dispatcher.snapshot().activeDispatches, 1);

  store.externalState('RESULT_COMMITTED');
  await dispatcher.tick();
  await flush();
  assert.equal(dispatcher.snapshot().activeDispatches, 0);
  assert.equal(browser.monitorSignal.aborted, true);
  assert.ok(browser.closed.includes('target-1'));
});
