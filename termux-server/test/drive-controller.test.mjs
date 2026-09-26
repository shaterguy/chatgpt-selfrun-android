import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchController } from '../src/drive-controller.mjs';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';

function control(overrides = {}) {
  return {
    schema: 'selfrun-task-control-v1',
    task_id: 'SR-TEST',
    control_epoch: 1,
    state: 'RUNNING',
    turn_id: 'SR-TEST:turn:1',
    request_id: 'SR-TEST:turn:1-request',
    reason: 'TEST',
    conversation_url: 'https://chatgpt.com/c/abc-123',
    updated_at_ms: Date.now(),
    ...overrides,
  };
}

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
    config: { recoveryPrompt: '현재 턴에 할당된 잔여작업이 있으면 계속 수행해' },
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

test('Drive dispatch resumes an existing conversation without resending the prompt', async () => {
  const writes = [];
  let submitCalls = 0;
  let resumeCalls = 0;
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async ({ conversationUrl }) => {
      resumeCalls += 1;
      assert.equal(conversationUrl, 'https://chatgpt.com/c/6ab65275-7f9c-83e8-8a58-0e02ce714138');
      return {
        target: { id: 'target-resumed' },
        session: { close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0 },
      };
    },
    submitPrepared: async () => {
      submitCalls += 1;
      throw new Error('must not resend');
    },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: '현재 턴에 할당된 잔여작업이 있으면 계속 수행해' },
  });
  const transport = {
    write: async (path, body) => writes.push({ path, body: structuredClone(body) }),
  };
  const body = dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/6ab65275-7f9c-83e8-8a58-0e02ce714138',
  });

  await controller.resume('job/resume.json', body, transport);

  assert.equal(resumeCalls, 1);
  assert.equal(submitCalls, 0);
  assert.equal(writes.at(-1).body.server_status, 'STARTED');
  assert.equal(writes.at(-1).body.conversation_url, body.conversation_url);
  assert.equal(stateStore.snapshot().status, 'RUNNING');
});

test('Task CONTROL applies only increasing epochs and STOPPED closes the active browser', async () => {
  let sessionClosed = 0;
  let targetClosed = 0;
  const browser = {
    chromium: { closeTarget: async () => { targetClosed += 1; return true; } },
    resume: async () => ({
      target: { id: 'target-control' },
      session: { close() { sessionClosed += 1; } },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: '현재 턴에 할당된 잔여작업이 있으면 계속 수행해' },
  });
  const transport = { write: async () => {} };

  await controller.control('__SELFRUN_CONTROL__SR-TEST.json', control({ control_epoch: 2, state: 'RUNNING' }));
  await controller.resume('job/control.json', dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/abc-123',
  }), transport);
  assert.equal(controller.active.controlState, 'RUNNING');
  assert.equal(controller.active.controlEpoch, 2);

  await controller.control('__SELFRUN_CONTROL__SR-TEST.json', control({ control_epoch: 1, state: 'PAUSED' }));
  assert.equal(controller.active.controlState, 'RUNNING');
  assert.equal(controller.active.controlEpoch, 2);

  await controller.control('__SELFRUN_CONTROL__SR-TEST.json', control({ control_epoch: 3, state: 'PAUSED' }));
  assert.equal(controller.active.controlState, 'PAUSED');
  assert.equal(controller.active.controlEpoch, 3);

  await controller.control('__SELFRUN_CONTROL__SR-TEST.json', control({ control_epoch: 4, state: 'STOPPED' }));
  assert.equal(controller.active, null);
  assert.equal(sessionClosed, 1);
  assert.equal(targetClosed, 1);
});

test('STOPPED control prevents restart recovery from reopening its conversation', async () => {
  let resumeCalls = 0;
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async () => {
      resumeCalls += 1;
      throw new Error('must not reopen while stopped');
    },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue' },
  });
  const transport = { write: async () => {} };

  await controller.control('control-stopped.json', control({
    task_id: 'SR-STOPPED',
    turn_id: 'SR-STOPPED:turn:9',
    request_id: 'SR-STOPPED:turn:9-request',
    control_epoch: 10,
    state: 'STOPPED',
  }), transport);

  await controller.resume('job/stopped.json', dispatch({
    task_id: 'SR-STOPPED',
    turn_id: 'SR-STOPPED:turn:9',
    request_id: 'SR-STOPPED:turn:9-request',
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/stopped-turn',
  }), transport);

  assert.equal(resumeCalls, 0);
  assert.equal(controller.getActive('job/stopped.json'), null);
});

test('Drive resume retries publication without reopening the conversation', async () => {
  let resumeCalls = 0;
  let writeCalls = 0;
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async () => {
      resumeCalls += 1;
      return {
        target: { id: 'target-resume-retry' },
        session: { close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0 },
      };
    },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: '현재 턴에 할당된 잔여작업이 있으면 계속 수행해' },
  });
  const transport = {
    write: async () => {
      writeCalls += 1;
      if (writeCalls === 1) throw new Error('Drive quota');
    },
  };
  const body = dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/6ab65275-7f9c-83e8-8a58-0e02ce714138',
  });

  await assert.rejects(controller.resume('job/resume-retry.json', body, transport), /Drive quota/);
  assert.equal(controller.active.publishPending, true);
  assert.equal(stateStore.snapshot().status, 'RUNNING');

  await controller.resume('job/resume-retry.json', body, transport);
  assert.equal(resumeCalls, 1);
  assert.equal(writeCalls, 2);
  assert.equal(controller.active.publishPending, false);
});

test('Drive dispatch rejects the local-chatgpt placeholder as a conversation URL', async () => {
  const writes = [];
  const browser = {
    chromium: { closeTarget: async () => true },
    prepare: async () => ({
      target: { id: 'target-placeholder' },
      session: { close() {} },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
    submitPrepared: async () => ({ url: 'https://chatgpt.com/c/local-chatgpt' }),
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: '현재 턴에 할당된 잔여작업이 있으면 계속 수행해' },
  });
  const transport = {
    write: async (path, body) => writes.push({ path, body: structuredClone(body) }),
  };

  await controller.prepare('job/placeholder.json', dispatch(), transport);
  await controller.send('job/placeholder.json', dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'READY_TO_SUBMIT',
  }), transport);

  assert.equal(writes.at(-1).body.server_status, 'ERROR');
  assert.match(writes.at(-1).body.server_error, /canonical conversation URL unavailable/);
  assert.equal(stateStore.snapshot().status, 'ERROR');
});

function stalledActivity(overrides = {}) {
  return {
    status: 'STALLED',
    pageUrl: 'https://chatgpt.com/c/abc-123',
    streaming: false,
    paused: false,
    assistantCount: 1,
    assistantTextLength: 20,
    assistantMessageId: 'data-testid:conversation-turn-2',
    userCount: 1,
    userTextLength: 10,
    userMessageId: 'data-testid:conversation-turn-1',
    lastActivityAt: new Date().toISOString(),
    pageError: null,
    ...overrides,
  };
}

async function startStalledResume({ resultText, resultError, snapshot,
  activity = stalledActivity(), controlState = 'RUNNING', allowUnknownControlRecovery = false }) {
  let sendCalls = 0;
  let snapshotCalls = 0;
  let resultReadCalls = 0;
  let lastPrompt = null;
  let resolveActivity;
  const activityDone = new Promise((resolve) => { resolveActivity = resolve; });
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async () => ({
      target: { id: 'target-stalled' },
      session: { close() {} },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
    monitor: async ({ onActivity }) => {
      const action = await onActivity(activity);
      resolveActivity(action);
      return new Promise(() => {});
    },
    livenessSnapshot: async () => {
      snapshotCalls += 1;
      if (snapshot instanceof Error) throw snapshot;
      return snapshot;
    },
    sendContinuation: async ({ prompt }) => {
      sendCalls += 1;
      lastPrompt = prompt;
    },
  };
  const stateStore = new MemoryStateStore();
  const writes = [];
  const transport = {
    write: async (path, body) => writes.push({ path, body: structuredClone(body) }),
    readGoogleDocText: async () => {
      resultReadCalls += 1;
      if (resultError) throw resultError;
      return resultText;
    },
  };
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: {
      recoveryPrompt: '현재 턴에 할당된 잔여작업이 있으면 계속 수행해',
      allowUnknownControlRecovery,
    },
  });
  if (controlState != null) {
    await controller.control('__SELFRUN_CONTROL__SR-TEST.json', control({ state: controlState }));
  }
  await controller.resume('job/stalled.json', dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/abc-123',
    result_document_id: 'RESULT-DOC',
  }), transport);
  const action = await activityDone;
  return {
    action,
    controller,
    sendCalls: () => sendCalls,
    snapshotCalls: () => snapshotCalls,
    resultReadCalls: () => resultReadCalls,
    lastPrompt: () => lastPrompt,
    writes,
    events: stateStore.events,
  };
}

test('liveness recovery completes without continuation when Result is committed', async () => {
  const run = await startStalledResume({
    resultText: '{"committed":true}',
    snapshot: stalledActivity(),
  });
  assert.equal(run.snapshotCalls(), 0);
  assert.equal(run.sendCalls(), 0);
  assert.equal(run.writes.some(({ body }) => body.server_status === 'COMPLETED'
    && body.completion_source === 'RESULT_DOCUMENT'), true);
});

test('liveness recovery retries Result lookup failures without erasing stall age', async () => {
  const run = await startStalledResume({
    resultError: new Error('Drive unavailable'),
    snapshot: stalledActivity(),
  });
  assert.equal(run.action?.resetLiveness, undefined);
  assert.equal(run.action?.retryAfterMs, 15000);
  assert.equal(run.snapshotCalls(), 0);
  assert.equal(run.sendCalls(), 0);
  const check = run.events.find(({ event }) => event === 'LIVENESS_RESULT_CHECK');
  assert.equal(check?.details.result_state, 'UNAVAILABLE');
  assert.equal(check?.details.reason, 'READ_ERROR');
  assert.match(check?.details.error, /Drive unavailable/);
  const decision = run.events.find(({ event }) => event === 'LIVENESS_RECOVERY_DECISION');
  assert.equal(decision?.details.decision, 'DEFER_RESULT_UNAVAILABLE');
  assert.equal(decision?.details.reset_liveness, false);
});

test('UNKNOWN control suppresses recovery by default', async () => {
  const run = await startStalledResume({
    controlState: null,
    resultText: '{"committed":false}',
    snapshot: stalledActivity(),
  });
  assert.equal(run.action?.resetLiveness, true);
  assert.equal(run.resultReadCalls(), 0);
  assert.equal(run.snapshotCalls(), 0);
  assert.equal(run.sendCalls(), 0);
});

test('UNKNOWN control can be temporarily allowed for legacy app compatibility', async () => {
  const current = stalledActivity();
  const run = await startStalledResume({
    controlState: null,
    allowUnknownControlRecovery: true,
    resultText: '{"committed":false}',
    snapshot: current,
    activity: current,
  });
  assert.equal(run.resultReadCalls(), 1);
  assert.equal(run.snapshotCalls(), 1);
  assert.equal(run.sendCalls(), 1);
  assert.equal(run.lastPrompt(), '현재 턴에 할당된 잔여작업이 있으면 계속 수행해');
});

test('non-RUNNING task CONTROL states suppress liveness recovery before Result inspection', async () => {
  for (const controlState of [
    'WAITING_USER_INTERVENTION',
    'PAUSED',
    'RESUME_REQUESTED',
    'RESUME_STOPPED_REQUESTED',
  ]) {
    const run = await startStalledResume({
      controlState,
      resultText: '{"committed":false}',
      snapshot: stalledActivity(),
    });
    assert.equal(run.action?.resetLiveness, true, controlState);
    assert.equal(run.resultReadCalls(), 0, controlState);
    assert.equal(run.snapshotCalls(), 0, controlState);
    assert.equal(run.sendCalls(), 0, controlState);
  }
});

test('liveness recovery is deferred while generation is paused', async () => {
  const run = await startStalledResume({
    resultText: '{"committed":false}',
    snapshot: stalledActivity({ paused: true }),
    activity: stalledActivity({ paused: true }),
  });
  assert.equal(run.action?.resetLiveness, true);
  assert.equal(run.snapshotCalls(), 0);
  assert.equal(run.sendCalls(), 0);
});

test('visible Stop button alone does not suppress recovery when cursor is unchanged', async () => {
  const current = stalledActivity({ streaming: true });
  const run = await startStalledResume({
    resultText: '{"committed":false}',
    snapshot: current,
    activity: current,
  });
  assert.equal(run.resultReadCalls(), 1);
  assert.equal(run.snapshotCalls(), 1);
  assert.equal(run.sendCalls(), 1);
  const decision = run.events.find(
    ({ event, details }) => event === 'LIVENESS_RECOVERY_DECISION'
      && details.decision === 'SEND',
  );
  assert.equal(decision?.details.reset_liveness, false);
});

test('liveness recovery retries cursor probe failures without erasing stall age', async () => {
  const run = await startStalledResume({
    resultText: '{"committed":false}',
    snapshot: new Error('CDP probe unavailable'),
  });
  assert.equal(run.action?.resetLiveness, undefined);
  assert.equal(run.action?.retryAfterMs, 15000);
  assert.equal(run.snapshotCalls(), 1);
  assert.equal(run.sendCalls(), 0);
  const probe = run.events.find(({ event }) => event === 'LIVENESS_CURSOR_RECHECK');
  assert.equal(probe?.details.status, 'ERROR');
  assert.match(probe?.details.error, /CDP probe unavailable/);
  const decision = run.events.find(
    ({ event, details }) => event === 'LIVENESS_RECOVERY_DECISION'
      && details.decision === 'DEFER_CURSOR_PROBE_ERROR',
  );
  assert.equal(decision?.details.reset_liveness, false);
});

test('liveness recovery is deferred when the visible message cursor advanced', async () => {
  const run = await startStalledResume({
    resultText: '{"committed":false}',
    snapshot: stalledActivity({ userMessageId: 'data-testid:conversation-turn-3' }),
  });
  assert.equal(run.action?.resetLiveness, true);
  assert.equal(run.snapshotCalls(), 1);
  assert.equal(run.sendCalls(), 0);
  const recheck = run.events.find(({ event }) => event === 'LIVENESS_CURSOR_RECHECK');
  assert.equal(recheck?.details.status, 'OK');
  assert.equal(recheck?.details.same_cursor, false);
  const decision = run.events.find(
    ({ event, details }) => event === 'LIVENESS_RECOVERY_DECISION'
      && details.decision === 'DEFER_CURSOR_CHANGED',
  );
  assert.equal(decision?.details.reset_liveness, true);
});

test('liveness recovery sends continuation only when Result is not committed and cursor is unchanged', async () => {
  const current = stalledActivity();
  const run = await startStalledResume({
    resultText: '{"committed":false}',
    snapshot: current,
    activity: current,
  });
  assert.equal(run.action?.resetLiveness, true);
  assert.equal(run.snapshotCalls(), 1);
  assert.equal(run.sendCalls(), 1);
  assert.equal(run.lastPrompt(), '현재 턴에 할당된 잔여작업이 있으면 계속 수행해');
  assert.equal(run.writes.some(({ body }) => body.server_status === 'RECOVERY_SENT'), true);
  const decision = run.events.find(
    ({ event, details }) => event === 'LIVENESS_RECOVERY_DECISION'
      && details.decision === 'SEND',
  );
  assert.equal(decision?.details.reset_liveness, false);
  assert.equal(run.events.some(({ event }) => event === 'LIVENESS_RECOVERY_SENT'), true);
});

test('browser monitor does not treat Stop button visibility changes as liveness activity', async () => {
  const originalNow = Date.now;
  let now = 1000;
  let evaluateCalls = 0;
  let stalledCalls = 0;
  const baseProbe = {
    url: 'https://chatgpt.com/c/abc-123',
    streaming: false,
    stopButtonVisible: false,
    paused: false,
    assistantCount: 0,
    assistantTextLength: 0,
    assistantMessageId: null,
    userCount: 1,
    userTextLength: 10,
    userMessageId: 'data-testid:conversation-turn-1',
    errorText: null,
  };
  const session = {
    call: async (method) => {
      assert.equal(method, 'Runtime.evaluate');
      evaluateCalls += 1;
      now += 6;
      let value = baseProbe;
      if (evaluateCalls === 2 || evaluateCalls === 3) {
        value = { ...baseProbe, streaming: true, stopButtonVisible: true };
      } else if (evaluateCalls >= 4) {
        value = { ...baseProbe, assistantCount: 1, assistantTextLength: 5,
          assistantMessageId: 'data-testid:conversation-turn-2' };
      }
      return { result: { value } };
    },
  };
  Date.now = () => now;
  try {
    const browser = new ChatGptBrowser(null, { stallAfterMs: 10, probeIntervalMs: 1 });
    const result = await browser.monitor({
      session,
      baseline: { assistantCount: 0, assistantTextLength: 0, userCount: 1, userTextLength: 10 },
      livenessGate: async () => ({ state: 'RUNNING', epoch: 1 }),
      onActivity: async (activity) => {
        if (activity.status === 'STALLED') {
          stalledCalls += 1;
          return { resetLiveness: true };
        }
      },
    });
    assert.equal(stalledCalls, 1);
    assert.equal(result.status, 'COMPLETED');
  } finally {
    Date.now = originalNow;
  }
});

test('continuation acceptance ignores a pre-existing Stop button', async () => {
  let evaluateCalls = 0;
  const probe = {
    url: 'https://chatgpt.com/c/abc-123',
    streaming: true,
    stopButtonVisible: true,
    paused: false,
    assistantCount: 0,
    assistantTextLength: 0,
    assistantMessageId: null,
    userCount: 1,
    userTextLength: 10,
    userMessageId: 'data-testid:conversation-turn-1',
    errorText: null,
  };
  const values = [
    probe,
    { status: 'READY' },
    probe,
    { status: 'SUBMITTED' },
    probe,
    { ...probe, userCount: 2, userTextLength: 20,
      userMessageId: 'data-testid:conversation-turn-2' },
  ];
  const session = {
    call: async (method) => {
      assert.equal(method, 'Runtime.evaluate');
      const value = values[evaluateCalls];
      evaluateCalls += 1;
      return { result: { value } };
    },
  };
  const browser = new ChatGptBrowser(null, {});
  const accepted = await browser.sendContinuation({
    session,
    prompt: '현재 턴에 할당된 잔여작업이 있으면 계속 수행해',
    profileOperations: [],
  });
  assert.equal(evaluateCalls, 6);
  assert.equal(accepted.userCount, 2);
});

test('browser monitor schedules stalled verification retries without resetting real activity time', async () => {
  let evaluateCalls = 0;
  let stalledCalls = 0;
  const stalledTimes = [];
  const baseProbe = {
    url: 'https://chatgpt.com/c/abc-123',
    streaming: false,
    paused: false,
    assistantCount: 0,
    assistantTextLength: 0,
    assistantMessageId: null,
    userCount: 1,
    userTextLength: 10,
    userMessageId: 'data-testid:conversation-turn-1',
    errorText: null,
  };
  const session = {
    call: async (method) => {
      assert.equal(method, 'Runtime.evaluate');
      evaluateCalls += 1;
      const value = evaluateCalls >= 4
        ? { ...baseProbe, assistantCount: 1, assistantTextLength: 5,
          assistantMessageId: 'data-testid:conversation-turn-2' }
        : baseProbe;
      return { result: { value } };
    },
  };
  const browser = new ChatGptBrowser(null, { stallAfterMs: 1, probeIntervalMs: 2 });
  const result = await browser.monitor({
    session,
    baseline: {
      assistantCount: 0,
      assistantTextLength: 0,
      userCount: 1,
      userTextLength: 10,
    },
    livenessGate: async () => ({ state: 'RUNNING', epoch: 1 }),
    onActivity: async (activity) => {
      if (activity.status !== 'STALLED') return;
      stalledCalls += 1;
      stalledTimes.push(activity.lastActivityAt);
      if (stalledCalls === 1) {
        assert.equal(activity.verificationRetry, false);
        return { retryAfterMs: 1 };
      }
      assert.equal(activity.verificationRetry, true);
      return { resetLiveness: true };
    },
  });
  assert.equal(stalledCalls, 2);
  assert.equal(stalledTimes[0], stalledTimes[1]);
  assert.equal(result.status, 'COMPLETED');
});


test('parallel Drive dispatches stay active independently and STOPPED only closes its task', async () => {
  const closedTargets = [];
  const closedSessions = [];
  let resumeCalls = 0;
  const browser = {
    chromium: { closeTarget: async (id) => { closedTargets.push(id); return true; } },
    resume: async ({ conversationUrl }) => {
      resumeCalls += 1;
      const suffix = conversationUrl.endsWith('/parallel-a') ? 'a' : 'b';
      return {
        target: { id: `target-${suffix}` },
        session: { close() { closedSessions.push(suffix); } },
        baseline: { assistantCount: 0, assistantTextLength: 0 },
      };
    },
    monitor: async () => new Promise(() => {}),
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

  await controller.control('control-a.json', control({
    task_id: 'SR-A',
    turn_id: 'SR-A:turn:1',
    request_id: 'SR-A:turn:1-request',
    control_epoch: 1,
  }));
  await controller.resume('job/a.json', dispatch({
    task_id: 'SR-A',
    turn_id: 'SR-A:turn:1',
    request_id: 'SR-A:turn:1-request',
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/parallel-a',
  }), transport);

  await controller.control('control-b.json', control({
    task_id: 'SR-B',
    turn_id: 'SR-B:turn:1',
    request_id: 'SR-B:turn:1-request',
    control_epoch: 1,
  }));
  await controller.resume('job/b.json', dispatch({
    task_id: 'SR-B',
    turn_id: 'SR-B:turn:1',
    request_id: 'SR-B:turn:1-request',
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/parallel-b',
  }), transport);

  assert.equal(resumeCalls, 2);
  assert.equal(controller.activeDispatches().length, 2);
  assert.ok(controller.getActive('job/a.json'));
  assert.ok(controller.getActive('job/b.json'));
  assert.equal(writes.some(({ body }) => body.server_status === 'SUPERSEDED'), false);

  await controller.control('control-a.json', control({
    task_id: 'SR-A',
    turn_id: 'SR-A:turn:1',
    request_id: 'SR-A:turn:1-request',
    control_epoch: 2,
    state: 'STOPPED',
  }), transport);

  assert.equal(controller.getActive('job/a.json'), null);
  assert.ok(controller.getActive('job/b.json'));
  assert.deepEqual(closedTargets, ['target-a']);
  assert.deepEqual(closedSessions, ['a']);
});

test('RUNNING control reattaches a stopped superseded dispatch by exact task turn and request', async () => {
  let resumeCalls = 0;
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async ({ conversationUrl }) => {
      resumeCalls += 1;
      assert.equal(conversationUrl, 'https://chatgpt.com/c/orphan-turn');
      return {
        target: { id: 'target-orphan' },
        session: { close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0 },
      };
    },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue' },
  });
  const path = '__SELFRUN_DISPATCH__SR-ORPHAN:turn:9-request__A1.json';
  let stored = dispatch({
    task_id: 'SR-ORPHAN',
    turn_id: 'SR-ORPHAN:turn:9',
    request_id: 'SR-ORPHAN:turn:9-request',
    client_status: 'SEND_REQUESTED',
    server_status: 'SUPERSEDED',
    conversation_url: 'https://chatgpt.com/c/orphan-turn',
    result_document_id: 'RESULT-ORPHAN',
  });
  const writes = [];
  const transport = {
    list: async () => [{ path, modTime: '2026-09-26T00:00:00Z', size: 1 }],
    read: async (requested) => {
      assert.equal(requested, path);
      return structuredClone(stored);
    },
    write: async (requested, body) => {
      assert.equal(requested, path);
      stored = structuredClone(body);
      writes.push(structuredClone(body));
    },
    readGoogleDocText: async () => '{"committed":false}',
  };

  await controller.control('control-orphan.json', control({
    task_id: 'SR-ORPHAN',
    turn_id: 'SR-ORPHAN:turn:9',
    request_id: 'SR-ORPHAN:turn:9-request',
    control_epoch: 20,
    state: 'RUNNING',
  }), transport);

  assert.equal(resumeCalls, 1);
  assert.ok(controller.getActive(path));
  assert.equal(controller.getActive(path).controlState, 'RUNNING');
  assert.equal(stored.server_status, 'STARTED');
  assert.equal(writes.at(-1).conversation_url, 'https://chatgpt.com/c/orphan-turn');
  assert.equal(stateStore.events.some(({ event }) => event === 'DRIVE_DISPATCH_REATTACH_REQUESTED'), true);
});

test('same request retry still supersedes only the older attempt', async () => {
  const closed = [];
  let n = 0;
  const browser = {
    chromium: { closeTarget: async (id) => { closed.push(id); return true; } },
    resume: async () => {
      n += 1;
      return {
        target: { id: `retry-target-${n}` },
        session: { close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0 },
      };
    },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue' },
  });
  const writes = [];
  const transport = { write: async (path, body) => writes.push({ path, body: structuredClone(body) }) };

  const common = {
    task_id: 'SR-RETRY',
    turn_id: 'SR-RETRY:turn:1',
    request_id: 'SR-RETRY:turn:1-request',
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/retry',
  };
  await controller.resume('job/retry-a1.json', dispatch({ ...common, dispatch_attempt: 1 }), transport);
  await controller.resume('job/retry-a2.json', dispatch({ ...common, dispatch_attempt: 2 }), transport);

  assert.equal(controller.activeDispatches().length, 1);
  assert.equal(controller.getActive('job/retry-a1.json'), null);
  assert.ok(controller.getActive('job/retry-a2.json'));
  assert.deepEqual(closed, ['retry-target-1']);
  assert.equal(writes.some(({ path, body }) =>
    path === 'job/retry-a1.json' && body.server_status === 'SUPERSEDED'), true);
});
