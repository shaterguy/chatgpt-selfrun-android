import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchController } from '../src/drive-controller.mjs';

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

test('liveness recovery is deferred when Result lookup is unavailable', async () => {
  const run = await startStalledResume({
    resultError: new Error('Drive unavailable'),
    snapshot: stalledActivity(),
  });
  assert.equal(run.action?.resetLiveness, true);
  assert.equal(run.snapshotCalls(), 0);
  assert.equal(run.sendCalls(), 0);
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
    'STOPPED',
    'DONE',
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

test('liveness recovery is deferred when the visible message cursor advanced', async () => {
  const run = await startStalledResume({
    resultText: '{"committed":false}',
    snapshot: stalledActivity({ userMessageId: 'data-testid:conversation-turn-3' }),
  });
  assert.equal(run.action?.resetLiveness, true);
  assert.equal(run.snapshotCalls(), 1);
  assert.equal(run.sendCalls(), 0);
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
});
