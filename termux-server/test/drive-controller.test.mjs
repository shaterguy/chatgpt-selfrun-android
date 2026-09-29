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
    server_control_epoch: 1,
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
  async recordEvent(event, details = {}, context = null) {
    this.events.push({
      event,
      details: structuredClone(details),
      context: context ? structuredClone(context) : null,
    });
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
  assert.equal(stateStore.snapshot().activeDispatches?.[0]?.server_status, 'STARTED');
  assert.equal(stateStore.snapshot().activeDispatches?.[0]?.conversation_url,
    'https://chatgpt.com/c/abc-123');
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
    sendContinuation: async () => ({ userCount: 2, userTextLength: 20, userMessageId: 'user-2' }),
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
  assert.equal(controller.controls.has('SR-TEST'), false);
  assert.equal(sessionClosed, 1);
  assert.equal(targetClosed, 1);
});

test('terminal CONTROL states are evicted immediately from the in-memory control cache', async () => {
  const controller = new DriveDispatchController({
    browser: {
      chromium: { closeTarget: async () => true },
      monitor: async () => new Promise(() => {}),
    },
    stateStore: new MemoryStateStore(),
    config: { recoveryPrompt: 'continue' },
  });

  for (const state of ['STOPPED', 'DONE']) {
    await controller.control('control-terminal.json', control({
      task_id: `SR-${state}`,
      turn_id: `SR-${state}:turn:9`,
      request_id: `SR-${state}:turn:9-request`,
      control_epoch: 10,
      state,
    }));
    assert.equal(controller.controls.has(`SR-${state}`), false, state);
    assert.equal(controller.controlForTask(`SR-${state}`), null, state);
  }
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
  activity = stalledActivity(), followupActivity = null, controlState = 'RUNNING',
  allowUnknownControlRecovery = false }) {
  let sendCalls = 0;
  let resumeSendCalls = 0;
  let monitorStarted = false;
  let snapshotCalls = 0;
  let resultReadCalls = 0;
  let lastPrompt = null;
  let followupAction = null;
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
      monitorStarted = true;
      const action = await onActivity(activity);
      if (followupActivity) followupAction = await onActivity(followupActivity);
      resolveActivity(action);
      return new Promise(() => {});
    },
    livenessSnapshot: async () => {
      snapshotCalls += 1;
      if (snapshot instanceof Error) throw snapshot;
      return snapshot;
    },
    sendContinuation: async ({ prompt }) => {
      if (monitorStarted) sendCalls += 1;
      else resumeSendCalls += 1;
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
    resumeSendCalls: () => resumeSendCalls,
    snapshotCalls: () => snapshotCalls,
    resultReadCalls: () => resultReadCalls,
    lastPrompt: () => lastPrompt,
    followupAction: () => followupAction,
    writes,
    events: stateStore.events,
  };
}

test('response-idle remains active when Result is not committed', async () => {
  const run = await startStalledResume({
    resultText: '{"committed":false}',
    snapshot: stalledActivity(),
    activity: stalledActivity({ status: 'RESPONSE_IDLE' }),
  });
  assert.equal(run.resultReadCalls(), 1);
  assert.equal(run.snapshotCalls(), 0);
  assert.equal(run.sendCalls(), 0);
  assert.equal(run.action?.retryAfterMs, 15000);
  assert.notEqual(run.controller.getActive('job/stalled.json'), null);
  assert.equal(run.writes.some(({ body }) => body.server_status === 'COMPLETED'), false);
  const check = run.events.find(({ event }) => event === 'RESPONSE_IDLE_RESULT_CHECK');
  assert.equal(check?.details.result_state, 'NOT_COMMITTED');
});

test('response-idle completes only after Result is committed', async () => {
  const run = await startStalledResume({
    resultText: '{"committed":true}',
    snapshot: stalledActivity(),
    activity: stalledActivity({ status: 'RESPONSE_IDLE' }),
  });
  assert.equal(run.resultReadCalls(), 1);
  assert.equal(run.snapshotCalls(), 0);
  assert.equal(run.sendCalls(), 0);
  assert.equal(run.action?.complete, true);
  assert.equal(run.controller.getActive('job/stalled.json'), null);
  assert.equal(run.writes.some(({ body }) => body.server_status === 'COMPLETED'
    && body.completion_source === 'RESULT_DOCUMENT'), true);
});

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

test('recovery status returns to STARTED when conversation progress is detected after continuation', async () => {
  const current = stalledActivity();
  const resumed = stalledActivity({
    status: 'RUNNING',
    userCount: 2,
    userTextLength: 36,
    userMessageId: 'data-testid:conversation-turn-3',
    progressDetected: true,
    lastActivityAt: new Date().toISOString(),
  });
  const run = await startStalledResume({
    resultText: '{"committed":false}',
    snapshot: current,
    activity: current,
    followupActivity: resumed,
  });
  const statuses = run.writes.map(({ body }) => body.server_status);
  const recoverySentIndex = statuses.lastIndexOf('RECOVERY_SENT');
  const restartedIndex = statuses.findIndex(
    (status, index) => index > recoverySentIndex && status === 'STARTED',
  );
  assert.ok(recoverySentIndex >= 0);
  assert.ok(restartedIndex > recoverySentIndex);
  assert.equal(run.controller.active.body.server_status, 'STARTED');
  assert.equal(run.controller.active.serverStatus, 'STARTED');
  assert.equal(
    run.events.some(({ event }) => event === 'LIVENESS_RECOVERY_PROGRESS_RESUMED'),
    true,
  );
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
        if (activity.status === 'RESPONSE_IDLE') return { complete: true };
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
    { ready: true, status: 'SEND_FOUND' },
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
  assert.equal(evaluateCalls, 7);
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
      if (activity.status === 'RESPONSE_IDLE') return { complete: true };
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
    sendContinuation: async () => ({ userCount: 2, userTextLength: 20, userMessageId: 'resume-user' }),
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

test('parallel monitor event keeps its own task identity after another task becomes global latest', async () => {
  const monitorResolvers = new Map();
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async ({ conversationUrl }) => {
      const suffix = conversationUrl.endsWith('/parallel-event-a') ? 'a' : 'b';
      return {
        target: { id: `target-event-${suffix}` },
        session: { id: suffix, close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0 },
      };
    },
    sendContinuation: async () => ({ userCount: 2, userTextLength: 20, userMessageId: 'resume-user' }),
    monitor: async ({ session }) => new Promise((resolve) => {
      monitorResolvers.set(session.id, resolve);
    }),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue', resumeRetryMs: 30000 },
  });
  const writes = [];
  const transport = {
    write: async (path, body) => writes.push({ path, body: structuredClone(body) }),
  };

  await controller.control('control-event-a.json', control({
    task_id: 'SR-EVENT-A',
    turn_id: 'SR-EVENT-A:turn:1',
    request_id: 'SR-EVENT-A:turn:1-request',
    control_epoch: 1,
  }));
  await controller.resume('job/event-a.json', dispatch({
    task_id: 'SR-EVENT-A',
    turn_id: 'SR-EVENT-A:turn:1',
    request_id: 'SR-EVENT-A:turn:1-request',
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/parallel-event-a',
  }), transport);

  await controller.control('control-event-b.json', control({
    task_id: 'SR-EVENT-B',
    turn_id: 'SR-EVENT-B:turn:1',
    request_id: 'SR-EVENT-B:turn:1-request',
    control_epoch: 1,
  }));
  await controller.resume('job/event-b.json', dispatch({
    task_id: 'SR-EVENT-B',
    turn_id: 'SR-EVENT-B:turn:1',
    request_id: 'SR-EVENT-B:turn:1-request',
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/parallel-event-b',
  }), transport);

  assert.equal(controller.activeDispatches().length, 2);
  assert.ok(monitorResolvers.has('a'));
  monitorResolvers.get('a')({
    status: 'PAGE_ERROR',
    probe: { errorText: 'Conversation state unavailable' },
  });

  for (let i = 0; i < 30; i += 1) {
    const found = stateStore.events.find(({ event, details }) =>
      event === 'DRIVE_DISPATCH_MONITOR_RETRY_SCHEDULED'
      && details.task_id === 'SR-EVENT-A');
    if (found) break;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }

  const event = stateStore.events.find(({ event, details }) =>
    event === 'DRIVE_DISPATCH_MONITOR_RETRY_SCHEDULED'
    && details.task_id === 'SR-EVENT-A');
  assert.ok(event);
  assert.equal(event.context.signalId, 'SR-EVENT-A:turn:1-request:attempt:1');
  assert.equal(event.context.turnId, 'SR-EVENT-A:turn:1');
  assert.equal(event.context.conversationUrl, 'https://chatgpt.com/c/parallel-event-a');
  assert.equal(event.context.generation, 1);
});

test('RUNNING control reattaches a stopped superseded dispatch by exact task turn and request', async () => {
  let resumeCalls = 0;
  let continuationCalls = 0;
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
    sendContinuation: async ({ prompt }) => {
      continuationCalls += 1;
      assert.equal(prompt, 'continue');
      return { userCount: 2, userTextLength: 20, userMessageId: 'user-2' };
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
  const unrelatedPath = '__SELFRUN_DISPATCH__SR-OTHER:turn:1-request__A1.json';
  let unrelatedReads = 0;
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
    list: async () => [
      { path: unrelatedPath, modTime: '2026-09-25T00:00:00Z', size: 1 },
      { path, modTime: '2026-09-26T00:00:00Z', size: 1 },
    ],
    read: async (requested) => {
      if (requested === unrelatedPath) {
        unrelatedReads += 1;
        return dispatch({ task_id: 'SR-OTHER', request_id: 'SR-OTHER:turn:1-request' });
      }
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
    control_epoch: 19,
    state: 'STOPPED',
  }), transport);
  assert.equal(controller.controls.has('SR-ORPHAN'), false);

  await controller.control('control-orphan.json', control({
    task_id: 'SR-ORPHAN',
    turn_id: 'SR-ORPHAN:turn:9',
    request_id: 'SR-ORPHAN:turn:9-request',
    control_epoch: 20,
    state: 'RUNNING',
  }), transport);

  assert.equal(resumeCalls, 1);
  assert.equal(continuationCalls, 1);
  assert.equal(unrelatedReads, 0);
  assert.ok(controller.getActive(path));
  assert.equal(controller.getActive(path).controlState, 'RUNNING');
  assert.equal(stored.server_status, 'STARTED');
  assert.equal(stored.resume_continuation_control_epoch, 20);
  assert.equal(writes.at(-1).conversation_url, 'https://chatgpt.com/c/orphan-turn');
  assert.equal(stateStore.events.some(({ event }) => event === 'DRIVE_DISPATCH_REATTACH_REQUESTED'), true);
});

test('RUNNING control reattaches an errored monitor dispatch with a canonical conversation', async () => {
  let resumeCalls = 0;
  let continuationCalls = 0;
  let continuationPrompt = null;
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async ({ conversationUrl }) => {
      resumeCalls += 1;
      assert.equal(conversationUrl, 'https://chatgpt.com/c/error-monitor');
      return {
        target: { id: 'target-error-monitor' },
        session: { close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0 },
      };
    },
    sendContinuation: async ({ prompt }) => {
      continuationCalls += 1;
      continuationPrompt = prompt;
      return { userCount: 2, userTextLength: 20, userMessageId: 'user-2' };
    },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue' },
  });
  const path = '__SELFRUN_DISPATCH__SR-ERROR:turn:2-request__A2.json';
  let stored = dispatch({
    task_id: 'SR-ERROR',
    turn_id: 'SR-ERROR:turn:2',
    request_id: 'SR-ERROR:turn:2-request',
    dispatch_attempt: 2,
    client_status: 'SEND_REQUESTED',
    server_status: 'ERROR',
    server_error: 'CDP command timeout: Runtime.evaluate',
    conversation_url: 'https://chatgpt.com/c/error-monitor',
    result_document_id: 'RESULT-ERROR',
    server_control_epoch: 6,
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

  await controller.control('control-error.json', control({
    task_id: 'SR-ERROR',
    turn_id: 'SR-ERROR:turn:2',
    request_id: 'SR-ERROR:turn:2-request',
    control_epoch: 7,
    state: 'RUNNING',
  }), transport);

  assert.equal(resumeCalls, 1);
  assert.equal(continuationCalls, 1);
  assert.equal(continuationPrompt, 'continue');
  assert.ok(controller.getActive(path));
  assert.equal(controller.getActive(path).controlState, 'RUNNING');
  assert.equal(stored.server_status, 'STARTED');
  assert.equal(stored.server_error, '');
  assert.equal(stored.server_control_epoch, 7);
  assert.equal(stored.resume_continuation_control_epoch, 7);
  assert.ok(stored.resume_continuation_sent_at_ms > 0);
  assert.equal(writes.at(-1).conversation_url, 'https://chatgpt.com/c/error-monitor');
  assert.equal(stateStore.events.some(({ event }) => event === 'DRIVE_DISPATCH_REATTACH_REQUESTED'), true);
});

test('resume continuation failure closes resumed target and schedules retry', async () => {
  let sessionClosed = 0;
  let targetClosed = 0;
  const browser = {
    chromium: { closeTarget: async () => { targetClosed += 1; return true; } },
    resume: async () => ({
      target: { id: 'target-continuation-failure' },
      session: { close() { sessionClosed += 1; } },
      baseline: { assistantCount: 0, assistantTextLength: 0, userCount: 1, userTextLength: 10 },
    }),
    sendContinuation: async () => { throw new Error('Continuation send failed'); },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser, stateStore, config: { recoveryPrompt: 'continue', resumeRetryMs: 30000 },
  });
  const path = '__SELFRUN_DISPATCH__SR-CONT-FAIL:turn:2-request__A2.json';
  let stored = dispatch({
    task_id: 'SR-CONT-FAIL', turn_id: 'SR-CONT-FAIL:turn:2',
    request_id: 'SR-CONT-FAIL:turn:2-request', dispatch_attempt: 2,
    client_status: 'SEND_REQUESTED', server_status: 'ERROR',
    conversation_url: 'https://chatgpt.com/c/continuation-failure',
    result_document_id: 'RESULT-CONT-FAIL', server_control_epoch: 7,
  });
  const transport = {
    list: async () => [{ path, modTime: '2026-09-27T00:00:00Z', size: 1 }],
    read: async () => structuredClone(stored),
    write: async (_path, body) => { stored = structuredClone(body); },
    readGoogleDocText: async () => '{"committed":false}',
  };
  await controller.control('control-cont-fail.json', control({
    task_id: 'SR-CONT-FAIL', turn_id: 'SR-CONT-FAIL:turn:2',
    request_id: 'SR-CONT-FAIL:turn:2-request', control_epoch: 8, state: 'RUNNING',
  }), transport);
  assert.equal(controller.getActive(path), null);
  assert.equal(sessionClosed, 1);
  assert.equal(targetClosed, 1);
  assert.equal(stored.server_status, 'ERROR');
  assert.equal(stored.server_error, 'Continuation send failed');
  assert.equal(stored.resume_retry_count, 1);
  assert.ok(stored.resume_retry_at_ms > 0);
});

test('reconnect sends resume continuation even when control epoch is unchanged', async () => {
  let continuationCalls = 0;
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async () => ({
      target: { id: 'target-same-epoch' },
      session: { close() {} },
      baseline: { assistantCount: 0, assistantTextLength: 0, userCount: 1, userTextLength: 10 },
    }),
    sendContinuation: async () => { continuationCalls += 1; },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({ browser, stateStore, config: { recoveryPrompt: 'continue' } });
  const path = '__SELFRUN_DISPATCH__SR-SAME-EPOCH:turn:2-request__A2.json';
  let stored = dispatch({
    task_id: 'SR-SAME-EPOCH', turn_id: 'SR-SAME-EPOCH:turn:2', request_id: 'SR-SAME-EPOCH:turn:2-request',
    dispatch_attempt: 2, client_status: 'SEND_REQUESTED', server_status: 'ERROR',
    conversation_url: 'https://chatgpt.com/c/same-epoch', result_document_id: 'RESULT-SAME-EPOCH',
    server_control_epoch: 7, resume_continuation_control_epoch: 7, resume_continuation_sent_at_ms: 1,
  });
  const transport = {
    list: async () => [{ path, modTime: '2026-09-26T00:00:00Z', size: 1 }],
    read: async () => structuredClone(stored),
    write: async (_requested, body) => { stored = structuredClone(body); },
    readGoogleDocText: async () => '{"committed":false}',
  };
  await controller.control('control-same-epoch.json', control({
    task_id: 'SR-SAME-EPOCH', turn_id: 'SR-SAME-EPOCH:turn:2', request_id: 'SR-SAME-EPOCH:turn:2-request',
    control_epoch: 7, state: 'RUNNING',
  }), transport);
  assert.equal(continuationCalls, 1);
  assert.ok(controller.getActive(path));
  assert.equal(stored.resume_continuation_control_epoch, 7);
});

test('RUNNING control does not reattach errored dispatch without a canonical conversation', async () => {
  let resumeCalls = 0;
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async () => { resumeCalls += 1; throw new Error('unexpected resume'); },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue' },
  });
  const path = '__SELFRUN_DISPATCH__SR-ERROR-NOURL:turn:2-request__A2.json';
  let stored = dispatch({
    task_id: 'SR-ERROR-NOURL',
    turn_id: 'SR-ERROR-NOURL:turn:2',
    request_id: 'SR-ERROR-NOURL:turn:2-request',
    dispatch_attempt: 2,
    client_status: 'SEND_REQUESTED',
    server_status: 'ERROR',
    server_error: 'CDP command timeout: Network.enable',
    conversation_url: null,
    result_document_id: 'RESULT-ERROR-NOURL',
  });
  const transport = {
    list: async () => [{ path, modTime: '2026-09-26T00:00:00Z', size: 1 }],
    read: async () => structuredClone(stored),
    write: async (_requested, body) => { stored = structuredClone(body); },
    readGoogleDocText: async () => '{"committed":false}',
  };

  await controller.control('control-error-nourl.json', control({
    task_id: 'SR-ERROR-NOURL',
    turn_id: 'SR-ERROR-NOURL:turn:2',
    request_id: 'SR-ERROR-NOURL:turn:2-request',
    control_epoch: 7,
    state: 'RUNNING',
  }), transport);

  assert.equal(resumeCalls, 0);
  assert.equal(controller.getActive(path), null);
  assert.equal(stored.server_status, 'ERROR');
});

test('resume failure schedules a persistent retry while preserving the canonical conversation', async () => {
  const stateStore = new MemoryStateStore();
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async () => { throw new Error('Too Many Requests'); },
    monitor: async () => new Promise(() => {}),
  };
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue', resumeRetryMs: 30000 },
  });
  const path = 'job/rate-limited-resume.json';
  let stored = dispatch({
    task_id: 'SR-RATE',
    turn_id: 'SR-RATE:turn:2',
    request_id: 'SR-RATE:turn:2-request',
    dispatch_attempt: 2,
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/rate-limited-resume',
    result_document_id: 'RESULT-RATE',
  });
  const transport = {
    write: async (requested, body) => {
      assert.equal(requested, path);
      stored = structuredClone(body);
    },
  };
  const started = Date.now();
  await controller.resume(path, stored, transport);
  assert.equal(stored.server_status, 'ERROR');
  assert.equal(stored.server_error, 'Too Many Requests');
  assert.equal(stored.conversation_url, 'https://chatgpt.com/c/rate-limited-resume');
  assert.equal(stored.resume_retry_count, 1);
  assert.ok(stored.resume_retry_at_ms >= started + 29000);
  assert.ok(stored.resume_retry_at_ms <= Date.now() + 31000);
  assert.equal(stateStore.events.some(({ event }) => event === 'DRIVE_DISPATCH_RESUME_RETRY_SCHEDULED'), true);
});

test('monitor page load failure schedules a persistent resume retry', async () => {
  let targetClosed = 0;
  const stateStore = new MemoryStateStore();
  const browser = {
    chromium: { closeTarget: async () => { targetClosed += 1; return true; } },
    resume: async () => ({
      target: { id: 'target-load-failure' },
      session: { close() {} },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
    sendContinuation: async () => ({ userCount: 2, userTextLength: 20, userMessageId: 'resume-user' }),
    monitor: async () => ({
      status: 'PAGE_ERROR',
      probe: { errorText: 'Could not load this ChatGPT conversation' },
    }),
  };
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue', resumeRetryMs: 30000 },
  });
  const path = 'job/load-failure-resume.json';
  let stored = dispatch({
    task_id: 'SR-LOAD-FAIL',
    turn_id: 'SR-LOAD-FAIL:turn:2',
    request_id: 'SR-LOAD-FAIL:turn:2-request',
    dispatch_attempt: 2,
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/load-failure-resume',
    result_document_id: 'RESULT-LOAD-FAIL',
    server_control_epoch: 8,
  });
  const transport = {
    write: async (requested, body) => {
      assert.equal(requested, path);
      stored = structuredClone(body);
    },
  };
  await controller.control('control-load-failure.json', control({
    task_id: 'SR-LOAD-FAIL',
    turn_id: 'SR-LOAD-FAIL:turn:2',
    request_id: 'SR-LOAD-FAIL:turn:2-request',
    control_epoch: 8,
    state: 'RUNNING',
  }), transport);

  const started = Date.now();
  await controller.resume(path, stored, transport);
  for (let i = 0; i < 20 && controller.getActive(path); i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 5));
  }

  assert.equal(controller.getActive(path), null);
  assert.equal(targetClosed, 1);
  assert.equal(stored.server_status, 'ERROR');
  assert.equal(stored.server_error, 'Could not load this ChatGPT conversation');
  assert.equal(stored.conversation_url, 'https://chatgpt.com/c/load-failure-resume');
  assert.equal(stored.resume_retry_count, 1);
  assert.ok(stored.resume_retry_at_ms >= started + 29000);
  assert.ok(stored.resume_retry_at_ms <= Date.now() + 31000);
  assert.equal(
    stateStore.events.some(({ event }) => event === 'DRIVE_DISPATCH_MONITOR_RETRY_SCHEDULED'),
    true,
  );
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

test('completed browser monitor removes the active dispatch immediately', async () => {
  let sessionClosed = 0;
  let targetClosed = 0;
  const browser = {
    chromium: { closeTarget: async () => { targetClosed += 1; return true; } },
    resume: async () => ({
      target: { id: 'target-completed-cleanup' },
      session: { close() { sessionClosed += 1; } },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
    sendContinuation: async () => ({ userCount: 2, userTextLength: 20, userMessageId: 'resume-user' }),
    monitor: async () => ({ status: 'COMPLETED' }),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue' },
  });
  const writes = [];
  const transport = { write: async (path, body) => writes.push({ path, body: structuredClone(body) }) };

  await controller.control('control-completed.json', control({
    task_id: 'SR-COMPLETED',
    turn_id: 'SR-COMPLETED:turn:1',
    request_id: 'SR-COMPLETED:turn:1-request',
    control_epoch: 1,
    state: 'RUNNING',
  }));
  await controller.resume('job/completed.json', dispatch({
    task_id: 'SR-COMPLETED',
    turn_id: 'SR-COMPLETED:turn:1',
    request_id: 'SR-COMPLETED:turn:1-request',
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/completed-cleanup',
  }), transport);

  for (let i = 0; i < 20 && controller.getActive('job/completed.json'); i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 5));
  }

  assert.equal(controller.getActive('job/completed.json'), null);
  assert.equal(controller.activeDispatches().length, 0);
  assert.equal(stateStore.snapshot().activeCount, 0);
  assert.equal(sessionClosed, 1);
  assert.equal(targetClosed, 1);
  assert.equal(writes.some(({ body }) => body.server_status === 'COMPLETED'), true);
});

test('final committed DONE result evicts stale RUNNING task control without reopening the conversation', async () => {
  let resumeCalls = 0;
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async () => {
      resumeCalls += 1;
      throw new Error('final DONE result must not reopen conversation');
    },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue' },
  });
  const path = '__SELFRUN_DISPATCH__SR-DONE:turn:10-request__A1.json';
  let stored = dispatch({
    task_id: 'SR-DONE',
    turn_id: 'SR-DONE:turn:10',
    request_id: 'SR-DONE:turn:10-request',
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/final-done',
    result_document_id: 'RESULT-DONE',
  });
  const transport = {
    list: async () => [{ path, modTime: '2026-09-26T00:00:00Z', size: 1 }],
    read: async () => structuredClone(stored),
    write: async (requested, body) => {
      assert.equal(requested, path);
      stored = structuredClone(body);
    },
    readGoogleDocText: async () => '{"committed":true,"status":"DONE"}',
  };

  await controller.control('control-done.json', control({
    task_id: 'SR-DONE',
    turn_id: 'SR-DONE:turn:10',
    request_id: 'SR-DONE:turn:10-request',
    control_epoch: 22,
    state: 'RUNNING',
  }), transport);

  assert.equal(resumeCalls, 0);
  assert.equal(stored.server_status, 'COMPLETED');
  assert.equal(controller.controls.has('SR-DONE'), false);
  assert.equal(controller.controlForTask('SR-DONE'), null);
  const skipped = stateStore.events.find(({ event }) =>
    event === 'DRIVE_DISPATCH_REATTACH_SKIPPED_COMMITTED');
  assert.equal(skipped?.details.result_status, 'DONE');
});


test('successor prepare releases the linked predecessor browser before opening the new turn', async () => {
  const order = [];
  let prepareCount = 0;
  const browser = {
    chromium: {
      closeTarget: async (id) => { order.push(`close:${id}`); return true; },
    },
    prepare: async ({ prompt }) => {
      prepareCount += 1;
      const id = `target-${prepareCount}`;
      order.push(`prepare:${prompt}`);
      return {
        target: { id },
        session: { close() { order.push(`session-close:${id}`); } },
        baseline: { assistantCount: 0, assistantTextLength: 0 },
      };
    },
    submitPrepared: async () => ({ url: `https://chatgpt.com/c/conversation-${prepareCount}` }),
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue' },
  });
  const transport = { write: async () => {} };

  const predecessor = dispatch({
    task_id: 'SR-SUCCESSOR',
    turn_id: 'SR-SUCCESSOR:turn:1',
    request_id: 'SR-SUCCESSOR:turn:1-request',
    prompt: 'turn-1',
    result_document_id: 'result-turn-1',
  });
  await controller.prepare('dispatch-turn-1.json', predecessor, transport);
  await controller.send('dispatch-turn-1.json', {
    ...predecessor,
    client_status: 'SEND_REQUESTED',
    server_status: 'READY_TO_SUBMIT',
  }, transport);

  const successor = dispatch({
    task_id: 'SR-SUCCESSOR',
    turn_id: 'SR-SUCCESSOR:turn:2',
    request_id: 'SR-SUCCESSOR:turn:2-request',
    prompt: 'turn-2',
    result_document_id: 'result-turn-2',
    previous_result_document_id: 'result-turn-1',
  });
  await controller.prepare('dispatch-turn-2.json', successor, transport);

  const closeIndex = order.indexOf('close:target-1');
  const nextPrepareIndex = order.indexOf('prepare:turn-2');
  assert.ok(closeIndex >= 0);
  assert.ok(nextPrepareIndex > closeIndex);
  assert.equal(controller.activeDispatches().length, 1);
  assert.equal(controller.activeDispatches()[0].identity.turnId, 'SR-SUCCESSOR:turn:2');
  assert.equal(stateStore.events.some(({ event, details }) =>
    event === 'PREDECESSOR_BROWSER_RELEASED'
      && details.predecessor_turn_id === 'SR-SUCCESSOR:turn:1'
      && details.successor_turn_id === 'SR-SUCCESSOR:turn:2'), true);
});


test('browser prepare is serialized across parallel Drive tasks', async () => {
  let inPrepare = 0;
  let maxPrepare = 0;
  let releaseFirst;
  const firstGate = new Promise((resolve) => { releaseFirst = resolve; });
  let prepareCalls = 0;
  const browser = {
    chromium: {
      closeTarget: async () => true,
      listExistingTargets: async () => [],
      residentSetMb: async () => 100,
      recycle: async () => ({ recycled: false }),
    },
    prepare: async () => {
      prepareCalls += 1;
      inPrepare += 1;
      maxPrepare = Math.max(maxPrepare, inPrepare);
      if (prepareCalls === 1) await firstGate;
      const id = `target-${prepareCalls}`;
      inPrepare -= 1;
      return {
        target: { id },
        session: { close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0 },
      };
    },
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: {
      recoveryPrompt: 'continue',
      browserIdleRecycleMs: 0,
      browserIdleRssMb: 0,
      browserOrphanGcEnabled: true,
    },
  });
  const transport = { write: async () => {} };

  const a = controller.prepare('a.json', dispatch({
    task_id: 'SR-A', turn_id: 'SR-A:turn:1', request_id: 'SR-A:turn:1-request', prompt: 'A',
  }), transport);
  await new Promise((resolve) => setTimeout(resolve, 5));
  const b = controller.prepare('b.json', dispatch({
    task_id: 'SR-B', turn_id: 'SR-B:turn:1', request_id: 'SR-B:turn:1-request', prompt: 'B',
  }), transport);
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(prepareCalls, 1);
  assert.equal(maxPrepare, 1);
  releaseFirst();
  await Promise.all([a, b]);
  assert.equal(prepareCalls, 2);
  assert.equal(maxPrepare, 1);
});

test('idle browser lifecycle closes orphan ChatGPT targets and recycles above RSS limit', async () => {
  const closed = [];
  const recycled = [];
  const browser = {
    chromium: {
      closeTarget: async (id) => { closed.push(id); return true; },
      listExistingTargets: async () => [
        { id: 'active-1', type: 'page', url: 'https://chatgpt.com/c/active' },
        { id: 'orphan-1', type: 'page', url: 'https://chatgpt.com/c/orphan' },
        { id: 'blank-1', type: 'page', url: 'about:blank' },
      ],
      residentSetMb: async () => 750,
      recycle: async (reason) => {
        recycled.push(reason);
        return { recycled: true, reason, rssMb: 750, rootPid: 123 };
      },
    },
    prepare: async () => ({
      target: { id: 'active-1' },
      session: { close() {} },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
    submitPrepared: async () => ({ url: 'https://chatgpt.com/c/active' }),
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: {
      recoveryPrompt: 'continue',
      browserIdleRecycleMs: 60000,
      browserIdleRssMb: 600,
      browserOrphanGcEnabled: true,
    },
  });
  const transport = { write: async () => {} };
  const body = dispatch();
  await controller.prepare('dispatch.json', body, transport);
  await controller.send('dispatch.json', dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'READY_TO_SUBMIT',
  }), transport);

  await controller.control('control.json', control({ state: 'STOPPED', control_epoch: 2 }), transport);

  assert.ok(closed.includes('active-1'));
  assert.ok(closed.includes('orphan-1'));
  assert.ok(!closed.includes('blank-1'));
  assert.deepEqual(recycled, ['idle_rss_limit']);
  assert.equal(stateStore.events.some(({ event }) => event === 'BROWSER_ORPHAN_TARGETS_CLOSED'), true);
  assert.equal(stateStore.events.some(({ event }) => event === 'BROWSER_RECYCLED'), true);
});

test('idle browser recycles after timeout when RSS is below the limit', async () => {
  const recycled = [];
  const browser = {
    chromium: {
      closeTarget: async () => true,
      listExistingTargets: async () => [],
      residentSetMb: async () => 120,
      recycle: async (reason) => {
        recycled.push(reason);
        return { recycled: true, reason, rssMb: 120, rootPid: 321 };
      },
    },
    prepare: async () => ({
      target: { id: 'target-idle' },
      session: { close() {} },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
    submitPrepared: async () => ({ url: 'https://chatgpt.com/c/idle' }),
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: {
      recoveryPrompt: 'continue',
      browserIdleRecycleMs: 10,
      browserIdleRssMb: 600,
      browserOrphanGcEnabled: true,
    },
  });
  const transport = { write: async () => {} };
  await controller.prepare('idle.json', dispatch(), transport);
  await controller.send('idle.json', dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'READY_TO_SUBMIT',
  }), transport);
  await controller.control('control.json', control({ state: 'STOPPED', control_epoch: 2 }), transport);
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.deepEqual(recycled, ['idle_timeout']);
});


test('resume releases browser-open slot when pre-browser state patch fails', async () => {
  let failPatch = true;
  class FailingStateStore extends MemoryStateStore {
    async patch(changes) {
      if (failPatch) {
        failPatch = false;
        throw new Error('synthetic state patch failure');
      }
      return super.patch(changes);
    }
  }
  const browser = {
    chromium: {
      closeTarget: async () => true,
      listExistingTargets: async () => [],
      residentSetMb: async () => 100,
      recycle: async () => ({ recycled: false }),
    },
    resume: async () => {
      throw new Error('resume should not be reached');
    },
    prepare: async () => ({
      target: { id: 'after-failure' },
      session: { close() {} },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
  };
  const stateStore = new FailingStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: {
      recoveryPrompt: 'continue',
      browserIdleRecycleMs: 0,
      browserIdleRssMb: 0,
      browserOrphanGcEnabled: true,
    },
  });
  const transport = { write: async () => {} };
  await assert.rejects(
    controller.resume('resume.json', dispatch({
      client_status: 'SEND_REQUESTED',
      server_status: 'STARTED',
      conversation_url: 'https://chatgpt.com/c/resume-test',
    }), transport),
    /synthetic state patch failure/,
  );
  await Promise.race([
    controller.prepare('after.json', dispatch({
      task_id: 'SR-AFTER',
      turn_id: 'SR-AFTER:turn:1',
      request_id: 'SR-AFTER:turn:1-request',
    }), transport),
    new Promise((_, reject) => setTimeout(() => reject(new Error('browser slot leaked')), 100)),
  ]);
});
