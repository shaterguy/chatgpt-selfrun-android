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
