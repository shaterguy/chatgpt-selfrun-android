import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DriveDispatchController } from '../src/drive-controller.mjs';
import { ChatGptBrowser, conversationResumeReady, conversationStateReady, detectPageErrorText } from '../src/browser/chatgpt.mjs';

class MemoryStateStore {
  constructor() {
    this.value = { generation: 0, lastSignalId: null };
    this.events = [];
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
  async recordEvent(event, details = {}) {
    this.events.push({ event, details: structuredClone(details) });
    return this.snapshot();
  }
}

function control(overrides = {}) {
  return {
    schema: 'selfrun-task-control-v1',
    task_id: 'SR-HOTFIX',
    control_epoch: 1,
    state: 'RUNNING',
    turn_id: 'SR-HOTFIX:turn:1',
    request_id: 'SR-HOTFIX:turn:1-request',
    reason: 'TEST',
    conversation_url: 'https://chatgpt.com/c/hotfix',
    updated_at_ms: Date.now(),
    ...overrides,
  };
}

function dispatch(overrides = {}) {
  return {
    schema: 'selfrun-server-dispatch-v1',
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    task_id: 'SR-HOTFIX',
    turn_id: 'SR-HOTFIX:turn:1',
    request_id: 'SR-HOTFIX:turn:1-request',
    dispatch_attempt: 1,
    server_control_epoch: 1,
    project_url: 'https://chatgpt.com/g/example/project',
    prompt: 'TASK_ID=SR-HOTFIX',
    profile_operations: [],
    conversation_url: 'https://chatgpt.com/c/hotfix',
    ...overrides,
  };
}

test('resume readiness requires only a stable user message identity and composer', () => {
  assert.equal(conversationResumeReady({ composer: true, userMessageId: 'user-1' }), true);
  assert.equal(conversationResumeReady({ composer: true, userMessageId: 'user-1', streaming: false, assistantMessageId: null }), true);
  assert.equal(conversationResumeReady({ composer: true, userMessageId: 'user-1', errorText: 'Could not load this ChatGPT conversation' }), true);
  assert.equal(conversationResumeReady({ composer: false, userMessageId: 'user-1' }), false);
  assert.equal(conversationResumeReady({ composer: true, userMessageId: null }), false);
});

test('structural conversation readiness requires stable message identity and response state', () => {
  assert.equal(conversationStateReady({
    userMessageId: 'user-1', assistantMessageId: 'assistant-1', streaming: false, paused: false,
  }), true);
  assert.equal(conversationStateReady({
    userMessageId: 'user-1', assistantMessageId: null, streaming: true, paused: false,
  }), true);
  assert.equal(conversationStateReady({
    userMessageId: 'user-1', assistantMessageId: null, streaming: false, paused: false,
  }), false);
  assert.equal(conversationStateReady({
    userMessageId: null, assistantMessageId: 'assistant-1', streaming: false, paused: false,
  }), false);
});

test('browser monitor reports structural failure when neither resume nor response structure is available', async () => {
  let calls = 0;
  const session = {
    call: async (method) => {
      assert.equal(method, 'Runtime.evaluate');
      calls += 1;
      return { result: { value: {
        url: 'https://chatgpt.com/c/structural-failure',
        composer: false, streaming: false, stopButtonVisible: false, paused: false,
        assistantCount: 0, assistantTextLength: 0, assistantMessageId: null,
        userCount: 0, userTextLength: 0, userMessageId: null,
        errorText: null,
      } } };
    },
  };
  const browser = new ChatGptBrowser(null, {
    stallAfterMs: 600000, probeIntervalMs: 0, conversationStateGraceMs: 0,
  });
  const result = await browser.monitor({
    session,
    baseline: { assistantCount: 0, assistantTextLength: 0, userCount: 2, userTextLength: 100 },
    livenessGate: async () => ({ state: 'RUNNING', epoch: 1 }),
  });
  assert.equal(result.status, 'PAGE_ERROR');
  assert.equal(result.probe.errorText, 'Conversation state unavailable');
  assert.equal(calls, 1);
});

test('browser monitor ignores visible page error text while usable and does not complete on response idle', async () => {
  let calls = 0;
  const statuses = [];
  const session = {
    call: async (method) => {
      assert.equal(method, 'Runtime.evaluate');
      calls += 1;
      if (calls === 1) {
        return { result: { value: {
          url: 'https://chatgpt.com/c/error-but-usable',
          composer: true, streaming: false, stopButtonVisible: false, paused: false,
          assistantCount: 0, assistantTextLength: 0, assistantMessageId: null,
          userCount: 2, userTextLength: 100, userMessageId: 'user-2',
          errorText: 'Could not load this ChatGPT conversation',
        } } };
      }
      if (calls === 2) {
        return { result: { value: {
          url: 'https://chatgpt.com/c/error-but-usable',
          composer: true, streaming: false, stopButtonVisible: false, paused: false,
          assistantCount: 1, assistantTextLength: 5, assistantMessageId: 'assistant-1',
          userCount: 2, userTextLength: 100, userMessageId: 'user-2',
          errorText: 'Could not load this ChatGPT conversation',
        } } };
      }
      return { result: { value: {
        url: 'https://chatgpt.com/c/error-but-usable',
        composer: false, streaming: false, stopButtonVisible: false, paused: false,
        assistantCount: 0, assistantTextLength: 0, assistantMessageId: null,
        userCount: 0, userTextLength: 0, userMessageId: null,
        errorText: 'Could not load this ChatGPT conversation',
      } } };
    },
  };
  const browser = new ChatGptBrowser(null, {
    stallAfterMs: 600000, probeIntervalMs: 0, conversationStateGraceMs: 0,
  });
  const result = await browser.monitor({
    session,
    baseline: { assistantCount: 0, assistantTextLength: 0, userCount: 2, userTextLength: 100 },
    livenessGate: async () => ({ state: 'RUNNING', epoch: 1 }),
    onActivity: async (activity) => { statuses.push(activity.status); },
  });
  assert.equal(result.status, 'PAGE_ERROR');
  assert.equal(result.probe.errorText, 'Could not load this ChatGPT conversation');
  assert.equal(statuses.includes('COMPLETED'), false);
  assert.equal(calls, 3);
});

test('page error detector recognizes the observed conversation load failure fixture', () => {
  const body = readFileSync(
    new URL('./fixtures/chatgpt-conversation-load-error.txt', import.meta.url),
    'utf8',
  );
  assert.equal(detectPageErrorText(body), 'Could not load this ChatGPT conversation');
});

test('browser monitor retries transient Runtime.evaluate timeouts without completing on response idle', async () => {
  let calls = 0;
  const statuses = [];
  const session = {
    call: async (method) => {
      assert.equal(method, 'Runtime.evaluate');
      calls += 1;
      if (calls <= 2) throw new Error('CDP command timeout: Runtime.evaluate');
      if (calls === 3) {
        return {
          result: {
            value: {
              url: 'https://chatgpt.com/c/hotfix',
              composer: true,
              streaming: false,
              stopButtonVisible: false,
              paused: false,
              assistantCount: 1,
              assistantTextLength: 5,
              assistantMessageId: 'assistant-1',
              userCount: 1,
              userTextLength: 10,
              userMessageId: 'user-1',
              errorText: null,
            },
          },
        };
      }
      return {
        result: {
          value: {
            url: 'https://chatgpt.com/c/hotfix',
            composer: false,
            streaming: false,
            stopButtonVisible: false,
            paused: false,
            assistantCount: 0,
            assistantTextLength: 0,
            assistantMessageId: null,
            userCount: 0,
            userTextLength: 0,
            userMessageId: null,
            errorText: 'Could not load this ChatGPT conversation',
          },
        },
      };
    },
  };
  const browser = new ChatGptBrowser(null, {
    stallAfterMs: 1000,
    probeIntervalMs: 0,
    conversationStateGraceMs: 0,
  });
  const result = await browser.monitor({
    session,
    baseline: { assistantCount: 0, assistantTextLength: 0, userCount: 1, userTextLength: 10 },
    livenessGate: async () => ({ state: 'RUNNING', epoch: 1 }),
    onActivity: async (activity) => { statuses.push(activity.status); },
  });
  assert.equal(calls, 4);
  assert.equal(result.status, 'PAGE_ERROR');
  assert.equal(statuses.includes('COMPLETED'), false);
});

test('browser monitor gives up only after three Runtime.evaluate retries', async () => {
  let calls = 0;
  const session = {
    call: async (method) => {
      assert.equal(method, 'Runtime.evaluate');
      calls += 1;
      throw new Error('CDP command timeout: Runtime.evaluate');
    },
  };
  const browser = new ChatGptBrowser(null, { stallAfterMs: 1000, probeIntervalMs: 0 });
  await assert.rejects(
    browser.monitor({
      session,
      baseline: { assistantCount: 0, assistantTextLength: 0 },
      livenessGate: async () => ({ state: 'RUNNING', epoch: 1 }),
    }),
    /CDP command timeout: Runtime\.evaluate/,
  );
  assert.equal(calls, 4);
});

test('monitor error evicts active bookkeeping while preserving conversation target', async () => {
  let sessionClosed = 0;
  let targetClosed = 0;
  const browser = {
    chromium: { closeTarget: async () => { targetClosed += 1; return true; } },
    resume: async () => ({
      target: { id: 'target-hotfix' },
      session: { close() { sessionClosed += 1; } },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
    sendContinuation: async () => ({ userCount: 2, userTextLength: 20, userMessageId: 'resume-user' }),
    monitor: async () => {
      throw new Error('CDP command timeout: Runtime.evaluate');
    },
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue' },
  });
  const writes = [];
  const transport = {
    write: async (path, body) => writes.push({ path, body: structuredClone(body) }),
  };

  await controller.control('control-hotfix.json', control());
  await controller.resume('dispatch-hotfix.json', dispatch(), transport);

  for (let i = 0; i < 20 && controller.getActive('dispatch-hotfix.json'); i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 5));
  }

  assert.equal(controller.getActive('dispatch-hotfix.json'), null);
  assert.equal(controller.activeDispatches().length, 0);
  assert.equal(stateStore.snapshot().activeCount, 0);
  assert.equal(sessionClosed, 1);
  assert.equal(targetClosed, 0);
  assert.equal(writes.some(({ body }) => body.server_status === 'ERROR'), true);
});


test('canonical conversation POST wins when submit Runtime.evaluate acknowledgement times out', async () => {
  let runtimeCalls = 0;
  let fetchPaused = null;
  const session = {
    on(method, handler) {
      assert.equal(method, 'Fetch.requestPaused');
      fetchPaused = handler;
      return () => { fetchPaused = null; };
    },
    async call(method, params) {
      if (method === 'Fetch.enable' || method === 'Fetch.disable' || method === 'Fetch.continueRequest') {
        return {};
      }
      assert.equal(method, 'Runtime.evaluate');
      runtimeCalls += 1;
      if (runtimeCalls === 1) {
        return { result: { value: { ready: true, status: 'SEND_FOUND' } } };
      }
      if (runtimeCalls === 2) {
        queueMicrotask(() => fetchPaused?.({
          requestId: 'request-1',
          request: {
            url: 'https://chatgpt.com/backend-api/conversation',
            method: 'POST',
            postData: JSON.stringify({ model: 'gpt-5-6-thinking' }),
          },
        }));
        throw new Error('CDP command timeout: Runtime.evaluate');
      }
      return { result: { value: {
        url: 'https://chatgpt.com/c/canonical-after-timeout',
        readyState: 'complete', composer: true,
        streaming: true, stopButtonVisible: true, paused: false,
        assistantCount: 0, assistantTextLength: 0, assistantMessageId: null,
        userCount: 1, userTextLength: 10, userMessageId: 'user-1',
        errorText: null,
      } } };
    },
  };
  const browser = new ChatGptBrowser(null, { navigationTimeoutMs: 1000 });
  const probe = await browser.submitPrepared({ session, profileOperations: [], signal: null });
  assert.equal(probe.url, 'https://chatgpt.com/c/canonical-after-timeout');
  assert.equal(runtimeCalls, 3);
});


test('prepared send Runtime.evaluate timeout recovers with a fresh target once', async () => {
  let prepareCount = 0;
  let submitCount = 0;
  const closedTargets = [];
  const writes = [];
  const browser = {
    chromium: {
      listExistingTargets: async () => [{
        id: 'target-1',
        type: 'page',
        url: 'https://chatgpt.com/g/example/project',
      }],
      closeTarget: async (id) => { closedTargets.push(id); return true; },
      residentSetMb: async () => 100,
      isTargetIdle: () => false,
    },
    prepare: async () => {
      prepareCount += 1;
      const id = 'target-' + prepareCount;
      return {
        target: { id },
        session: { close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0, userCount: 0, userTextLength: 0 },
      };
    },
    submitPrepared: async () => {
      submitCount += 1;
      if (submitCount === 1) throw new Error('CDP command timeout: Runtime.evaluate');
      return { url: 'https://chatgpt.com/c/recovered-fresh' };
    },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue', browserIdleRecycleMs: 0, browserIdleRssMb: 0 },
  });
  const transport = { write: async (_path, body) => writes.push(structuredClone(body)) };
  const initial = dispatch({
    client_status: 'CREATE_REQUESTED',
    server_status: 'PREPARING',
    conversation_url: undefined,
  });

  await controller.control('control-runtime-timeout.json', control({
    conversation_url: '',
  }), transport);
  await controller.prepare('dispatch-runtime-timeout.json', initial, transport);
  await controller.send('dispatch-runtime-timeout.json', {
    ...initial,
    client_status: 'SEND_REQUESTED',
    server_status: 'READY_TO_SUBMIT',
  }, transport);

  assert.equal(prepareCount, 2);
  assert.equal(submitCount, 2);
  assert.deepEqual(closedTargets, ['target-1']);
  assert.equal(writes.at(-1).server_status, 'STARTED');
  assert.equal(writes.at(-1).conversation_url, 'https://chatgpt.com/c/recovered-fresh');
  assert.equal(stateStore.events.some(({ event, details }) =>
    event === 'DRIVE_DISPATCH_SEND_SESSION_RECOVERED'
      && details.mode === 'FRESH_PREPARE'), true);
});

test('prepared send timeout adopts an already-created conversation without resending', async () => {
  let prepareCount = 0;
  let submitCount = 0;
  let resumeCount = 0;
  const closedTargets = [];
  const writes = [];
  const browser = {
    chromium: {
      listExistingTargets: async () => [{
        id: 'target-1',
        type: 'page',
        url: 'https://chatgpt.com/c/already-created',
      }],
      closeTarget: async (id) => { closedTargets.push(id); return true; },
      residentSetMb: async () => 100,
      isTargetIdle: () => false,
    },
    prepare: async () => {
      prepareCount += 1;
      return {
        target: { id: 'target-1' },
        session: { close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0, userCount: 0, userTextLength: 0 },
      };
    },
    resume: async ({ conversationUrl }) => {
      resumeCount += 1;
      assert.equal(conversationUrl, 'https://chatgpt.com/c/already-created');
      return {
        target: { id: 'target-2' },
        session: { close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0, userCount: 1, userTextLength: 10 },
      };
    },
    submitPrepared: async () => {
      submitCount += 1;
      throw new Error('CDP command timeout: Runtime.evaluate');
    },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue', browserIdleRecycleMs: 0, browserIdleRssMb: 0 },
  });
  const transport = { write: async (_path, body) => writes.push(structuredClone(body)) };
  const initial = dispatch({
    client_status: 'CREATE_REQUESTED',
    server_status: 'PREPARING',
    conversation_url: undefined,
  });

  await controller.control('control-existing-conversation.json', control({
    conversation_url: '',
  }), transport);
  await controller.prepare('dispatch-existing-conversation.json', initial, transport);
  await controller.send('dispatch-existing-conversation.json', {
    ...initial,
    client_status: 'SEND_REQUESTED',
    server_status: 'READY_TO_SUBMIT',
  }, transport);

  assert.equal(prepareCount, 1);
  assert.equal(submitCount, 1);
  assert.equal(resumeCount, 1);
  assert.deepEqual(closedTargets, ['target-1']);
  assert.equal(writes.at(-1).server_status, 'STARTED');
  assert.equal(writes.at(-1).conversation_url, 'https://chatgpt.com/c/already-created');
  assert.equal(stateStore.events.some(({ event, details }) =>
    event === 'DRIVE_DISPATCH_SEND_SESSION_RECOVERED'
      && details.mode === 'RESUME_EXISTING'), true);
});
