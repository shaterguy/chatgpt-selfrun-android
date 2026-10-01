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

function transportWithControl(base = {}, controller = null, controlOverrides = {}) {
  const baseRead = base.read;
  return {
    ...base,
    read: async (path) => {
      if (String(path).startsWith('__SELFRUN_CONTROL__')) {
        const taskId = String(path).slice('__SELFRUN_CONTROL__'.length, -'.json'.length);
        const cached = controller?.controlForTask?.(taskId);
        if (cached) return structuredClone(cached);
        if (typeof baseRead === 'function') {
          const candidate = await baseRead(path);
          if (candidate?.task_id) return control({ task_id: candidate.task_id, turn_id: candidate.turn_id, request_id: candidate.request_id, control_epoch: Number(candidate.server_control_epoch || 1), ...controlOverrides });
        }
        return structuredClone(control({ task_id: taskId, ...controlOverrides }));
      }
      if (typeof baseRead === 'function') return baseRead(path);
      throw new Error('unexpected transport read');
    },
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
  let preparedPrompt = null;
  const browser = {
    chromium: { closeTarget: async () => true },
    prepare: async ({ prompt }) => {
      preparedPrompt = prompt;
      return {
        target: { id: 'target-1' },
        session: { close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0 },
      };
    },
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
    config: {
      turnStartDirective: 'SERVER START DIRECTIVE',
      recoveryPrompt: '현재 턴에 할당된 잔여작업이 있으면 계속 수행해',
    },
  });
  const transport = {
    write: async (path, body) => writes.push({ path, body: structuredClone(body) }),
    read: async () => control(),
  };

  const body = dispatch();
  await controller.prepare('job/dispatch.json', body, transportWithControl(transport, controller));
  assert.equal(preparedPrompt,
    'TASK_ID=SR-TEST\n\n[SelfRun 서버 실행 지시]\nSERVER START DIRECTIVE');
  assert.equal(writes.at(-1).body.prompt, 'TASK_ID=SR-TEST');
  assert.equal(writes.at(-1).body.server_status, 'READY_TO_SUBMIT');
  assert.equal(submitCalls, 0);

  const claimed = dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'READY_TO_SUBMIT',
  });
  await controller.send('job/dispatch.json', claimed, transportWithControl(transport, controller));
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
    sendContinuation: async () => ({ userCount: 1, userTextLength: 10, userMessageId: 'resume-user' }),
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
    read: async () => control(),
  };
  const body = dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/6ab65275-7f9c-83e8-8a58-0e02ce714138',
  });

  await controller.resume('job/resume.json', body, transportWithControl(transport, controller));

  assert.equal(resumeCalls, 1);
  assert.equal(submitCalls, 0);
  assert.equal(writes.at(-1).body.server_status, 'STARTED');
  assert.equal(writes.at(-1).body.conversation_url, body.conversation_url);
  assert.equal(stateStore.snapshot().status, 'RUNNING');
});


test('resume continuation uses the server-managed continue directive', async () => {
  let continuationPrompt = null;
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async () => ({
      target: { id: 'target-dynamic-resume' },
      session: { close() {} },
      baseline: { assistantCount: 0, assistantTextLength: 0, userCount: 1, userTextLength: 10 },
    }),
    sendContinuation: async ({ prompt }) => {
      continuationPrompt = prompt;
      return { userCount: 2, userTextLength: 20, userMessageId: 'user-dynamic-resume' };
    },
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'fallback continue' },
    promptDirectives: {
      current: async () => ({
        turnStartDirective: 'dynamic start',
        turnContinueDirective: 'dynamic continue',
      }),
    },
  });
  const transport = { write: async () => {} };
  await controller.control('__SELFRUN_CONTROL__SR-TEST.json', control({ state: 'RUNNING' }));
  await controller.resume('job/dynamic-resume.json', dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/dynamic-resume',
  }), transportWithControl(transport, controller));
  assert.equal(continuationPrompt, 'dynamic continue');
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
  }), transportWithControl(transport, controller));
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
  assert.equal(controller.controls.has('SR-TEST'), true);
  assert.equal(controller.controlForTask('SR-TEST')?.state, 'STOPPED');
  assert.equal(sessionClosed, 1);
  assert.equal(targetClosed, 1);
});

test('terminal CONTROL states remain authoritative in the in-memory control cache', async () => {
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
    assert.equal(controller.controls.has(`SR-${state}`), true, state);
    assert.equal(controller.controlForTask(`SR-${state}`)?.state, state);
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
    sendContinuation: async () => ({ userCount: 1, userTextLength: 10, userMessageId: 'resume-retry-user' }),
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

  await assert.rejects(controller.resume('job/resume-retry.json', body, transportWithControl(transport, controller)), /Drive quota/);
  assert.equal(controller.active.publishPending, true);
  assert.equal(stateStore.snapshot().status, 'RUNNING');

  await controller.resume('job/resume-retry.json', body, transportWithControl(transport, controller));
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

  await controller.prepare('job/placeholder.json', dispatch(), transportWithControl(transport, controller));
  await controller.send('job/placeholder.json', dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'READY_TO_SUBMIT',
  }), transportWithControl(transport, controller));

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
  allowUnknownControlRecovery = false, sendError = null }) {
  let sendCalls = 0;
  let resumeSendCalls = 0;
  let monitorStarted = false;
  let snapshotCalls = 0;
  let resultReadCalls = 0;
  let lastPrompt = null;
  let followupAction = null;
  let sessionCloseCalls = 0;
  let targetCloseCalls = 0;
  let resolveActivity;
  const activityDone = new Promise((resolve) => { resolveActivity = resolve; });
  const browser = {
    chromium: { closeTarget: async () => { targetCloseCalls += 1; return true; } },
    resume: async () => ({
      target: { id: 'target-stalled' },
      session: { close() { sessionCloseCalls += 1; } },
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
      if (monitorStarted) {
        sendCalls += 1;
        if (sendError) throw sendError;
      } else {
        resumeSendCalls += 1;
      }
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
  }), transportWithControl(transport, controller));
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
    sessionCloseCalls: () => sessionCloseCalls,
    targetCloseCalls: () => targetCloseCalls,
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

test('missing Task Control blocks resume before browser reattachment', async () => {
  let resumeCalls = 0;
  const controller = new DriveDispatchController({
    browser: {
      chromium: { closeTarget: async () => true },
      resume: async () => { resumeCalls += 1; throw new Error('browser must not open'); },
    },
    stateStore: new MemoryStateStore(),
    config: { recoveryPrompt: 'continue' },
  });
  const transport = { write: async () => {} };
  await assert.rejects(
    controller.resume('job/missing-control.json', dispatch({
      client_status: 'SEND_REQUESTED',
      server_status: 'STARTED',
      conversation_url: 'https://chatgpt.com/c/missing-control',
    }), transport),
    /fresh task control unavailable/,
  );
  assert.equal(resumeCalls, 0);
});

test('legacy unknown-control option cannot bypass fresh Task Control authority', async () => {
  let resumeCalls = 0;
  const controller = new DriveDispatchController({
    browser: {
      chromium: { closeTarget: async () => true },
      resume: async () => { resumeCalls += 1; throw new Error('browser must not open'); },
    },
    stateStore: new MemoryStateStore(),
    config: { recoveryPrompt: 'continue', allowUnknownControlRecovery: true },
  });
  const transport = { write: async () => {} };
  await assert.rejects(
    controller.resume('job/legacy-missing-control.json', dispatch({
      client_status: 'SEND_REQUESTED',
      server_status: 'STARTED',
      conversation_url: 'https://chatgpt.com/c/legacy-missing-control',
    }), transport),
    /fresh task control unavailable/,
  );
  assert.equal(resumeCalls, 0);
});

test('non-RUNNING Task Control blocks browser reattachment before Result inspection', async () => {
  for (const controlState of [
    'WAITING_USER_INTERVENTION',
    'PAUSED',
    'RESUME_REQUESTED',
    'RESUME_STOPPED_REQUESTED',
  ]) {
    let resumeCalls = 0;
    let resultReadCalls = 0;
    const controller = new DriveDispatchController({
      browser: {
        chromium: { closeTarget: async () => true },
        resume: async () => { resumeCalls += 1; throw new Error('browser must not open'); },
      },
      stateStore: new MemoryStateStore(),
      config: { recoveryPrompt: 'continue' },
    });
    await controller.control('__SELFRUN_CONTROL__SR-TEST.json', control({ state: controlState }));
    const transport = {
      write: async () => {},
      readGoogleDocText: async () => { resultReadCalls += 1; return '{"committed":false}'; },
    };
    await controller.resume('job/non-running-control.json', dispatch({
      client_status: 'SEND_REQUESTED',
      server_status: 'STARTED',
      conversation_url: 'https://chatgpt.com/c/non-running-control',
      result_document_id: 'RESULT-NON-RUNNING',
    }), transportWithControl(transport, controller));
    assert.equal(resumeCalls, 0, controlState);
    assert.equal(resultReadCalls, 0, controlState);
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

test('liveness recovery send failure keeps the current browser session attached', async () => {
  const current = stalledActivity();
  const run = await startStalledResume({
    resultText: '{"committed":false}',
    snapshot: current,
    activity: current,
    sendError: new Error('Canonical conversation POST timeout'),
  });
  assert.equal(run.action?.resetLiveness, true);
  assert.equal(run.sendCalls(), 1);
  assert.equal(run.sessionCloseCalls(), 0);
  assert.equal(run.targetCloseCalls(), 0);
  assert.ok(run.controller.getActive('job/stalled.json'));
  assert.equal(run.writes.some(({ body }) => body.server_status === 'ERROR'), false);
  assert.equal(run.writes.some(({ body }) => body.server_status === 'STARTED'
    && body.recovery_count === 0), true);
  const decision = run.events.find(
    ({ event, details }) => event === 'LIVENESS_RECOVERY_DECISION'
      && details.decision === 'DEFER_SEND_ERROR',
  );
  assert.equal(decision?.details.reset_liveness, true);
  assert.match(decision?.details.error, /Canonical conversation POST timeout/);
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
  let forcePageError = false;
  const baseProbe = {
    url: 'https://chatgpt.com/c/abc-123',
    composer: true,
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
      if (forcePageError) {
        return { result: { value: {
          ...baseProbe,
          composer: false,
          userCount: 0,
          userTextLength: 0,
          userMessageId: null,
        } } };
      }
      const value = evaluateCalls === 2 || evaluateCalls === 3
        ? { ...baseProbe, streaming: true, stopButtonVisible: true }
        : baseProbe;
      return { result: { value } };
    },
  };
  Date.now = () => now;
  try {
    const browser = new ChatGptBrowser(null, {
      stallAfterMs: 10,
      probeIntervalMs: 1,
      conversationStateGraceMs: 0,
    });
    const result = await browser.monitor({
      session,
      baseline: {
        assistantCount: 0,
        assistantTextLength: 0,
        userCount: 1,
        userTextLength: 10,
        userMessageId: 'data-testid:conversation-turn-1',
      },
      livenessGate: async () => ({ state: 'RUNNING', epoch: 1 }),
      onActivity: async (activity) => {
        if (activity.status === 'STALLED') {
          stalledCalls += 1;
          forcePageError = true;
          return { resetLiveness: true };
        }
      },
    });
    assert.equal(stalledCalls, 1);
    assert.equal(result.status, 'PAGE_ERROR');
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
    { ready: true, status: 'SEND_FOUND' },
    { status: 'SUBMITTED' },
    probe,
    { ...probe, userCount: 2, userTextLength: 20,
      userMessageId: 'data-testid:conversation-turn-2' },
  ];
  let pausedHandler = null;
  const session = {
    on(method, handler) { pausedHandler = handler; return () => { pausedHandler = null; }; },
    call: async (method) => {
      if (method === 'Fetch.enable' || method === 'Fetch.disable' || method === 'Fetch.continueRequest') return {};
      assert.equal(method, 'Runtime.evaluate');
      const value = values[evaluateCalls];
      evaluateCalls += 1;
      if (value?.status === 'SUBMITTED') {
        await pausedHandler({ requestId: 'continuation-stop', request: {
          url: 'https://chatgpt.com/backend-api/conversation', method: 'POST', postData: '{}' } });
      }
      return { result: { value } };
    },
  };
  const browser = new ChatGptBrowser(null, { navigationTimeoutMs: 1000 });
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
  let forcePageError = false;
  const stalledTimes = [];
  const baseProbe = {
    url: 'https://chatgpt.com/c/abc-123',
    composer: true,
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
      if (forcePageError) {
        return { result: { value: {
          ...baseProbe,
          composer: false,
          userCount: 0,
          userTextLength: 0,
          userMessageId: null,
        } } };
      }
      return { result: { value: baseProbe } };
    },
  };
  const browser = new ChatGptBrowser(null, {
    stallAfterMs: 1,
    probeIntervalMs: 2,
    conversationStateGraceMs: 0,
  });
  const result = await browser.monitor({
    session,
    baseline: {
      assistantCount: 0,
      assistantTextLength: 0,
      userCount: 1,
      userTextLength: 10,
      userMessageId: 'data-testid:conversation-turn-1',
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
      forcePageError = true;
      return { resetLiveness: true };
    },
  });
  assert.equal(stalledCalls, 2);
  assert.equal(stalledTimes[0], stalledTimes[1]);
  assert.equal(result.status, 'PAGE_ERROR');
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
  }), transportWithControl(transport, controller));

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
  }), transportWithControl(transport, controller));

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
  }), transportWithControl(transport, controller));

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
  }), transportWithControl(transport, controller));

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
  }), transportWithControl(transport, controller));

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
  }), transportWithControl(transport, controller));
  assert.equal(controller.controls.has('SR-ORPHAN'), true);
  assert.equal(controller.controlForTask('SR-ORPHAN')?.state, 'STOPPED');

  await controller.control('control-orphan.json', control({
    task_id: 'SR-ORPHAN',
    turn_id: 'SR-ORPHAN:turn:9',
    request_id: 'SR-ORPHAN:turn:9-request',
    control_epoch: 20,
    state: 'RUNNING',
  }), transportWithControl(transport, controller));

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
  }), transportWithControl(transport, controller));

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
  }), transportWithControl(transport, controller));
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
  }), transportWithControl(transport, controller));
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
  }), transportWithControl(transport, controller));

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
  await controller.control('control-rate.json', control({ task_id: 'SR-RATE', turn_id: 'SR-RATE:turn:2', request_id: 'SR-RATE:turn:2-request', control_epoch: 2, state: 'RUNNING' }));
  const started = Date.now();
  await controller.resume(path, stored, transportWithControl(transport, controller));
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
  }), transportWithControl(transport, controller));

  const started = Date.now();
  await controller.resume(path, stored, transportWithControl(transport, controller));
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
    sendContinuation: async () => ({ userCount: 2, userTextLength: 20, userMessageId: 'retry-user' }),
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
  await controller.control('control-retry.json', control({ task_id: 'SR-RETRY', turn_id: 'SR-RETRY:turn:1', request_id: 'SR-RETRY:turn:1-request', control_epoch: 1, state: 'RUNNING' }));
  await controller.resume('job/retry-a1.json', dispatch({ ...common, dispatch_attempt: 1 }), transportWithControl(transport, controller));
  await controller.resume('job/retry-a2.json', dispatch({ ...common, dispatch_attempt: 2 }), transportWithControl(transport, controller));

  assert.equal(controller.activeDispatches().length, 1);
  assert.equal(controller.getActive('job/retry-a1.json'), null);
  assert.ok(controller.getActive('job/retry-a2.json'));
  assert.deepEqual(closed, ['retry-target-1']);
  assert.equal(writes.some(({ path, body }) =>
    path === 'job/retry-a1.json' && body.server_status === 'SUPERSEDED'), true);
});

test('browser completion status cannot remove an active dispatch', async () => {
  let sessionClosed = 0;
  let targetClosed = 0;
  const browser = {
    chromium: { closeTarget: async () => { targetClosed += 1; return true; } },
    resume: async () => ({
      target: { id: 'target-browser-completion-ignored' },
      session: { close() { sessionClosed += 1; } },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
    sendContinuation: async () => ({
      userCount: 2,
      userTextLength: 20,
      userMessageId: 'resume-user',
    }),
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

  await controller.control('control-browser-completion.json', control({
    task_id: 'SR-BROWSER-COMPLETION',
    turn_id: 'SR-BROWSER-COMPLETION:turn:1',
    request_id: 'SR-BROWSER-COMPLETION:turn:1-request',
    control_epoch: 1,
    state: 'RUNNING',
  }));
  await controller.resume('job/browser-completion.json', dispatch({
    task_id: 'SR-BROWSER-COMPLETION',
    turn_id: 'SR-BROWSER-COMPLETION:turn:1',
    request_id: 'SR-BROWSER-COMPLETION:turn:1-request',
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/browser-completion-ignored',
  }), transportWithControl(transport, controller));

  await new Promise((resolve) => setTimeout(resolve, 20));

  assert.ok(controller.getActive('job/browser-completion.json'));
  assert.equal(controller.activeDispatches().length, 1);
  assert.equal(stateStore.snapshot().activeCount, 1);
  assert.equal(sessionClosed, 0);
  assert.equal(targetClosed, 0);
  assert.equal(writes.some(({ body }) => body.server_status === 'COMPLETED'), false);

  await controller.control('control-browser-completion.json', control({
    task_id: 'SR-BROWSER-COMPLETION',
    turn_id: 'SR-BROWSER-COMPLETION:turn:1',
    request_id: 'SR-BROWSER-COMPLETION:turn:1-request',
    control_epoch: 2,
    state: 'STOPPED',
  }), transportWithControl(transport, controller));

  assert.equal(controller.getActive('job/browser-completion.json'), null);
  assert.equal(stateStore.snapshot().activeCount, 0);
  assert.equal(sessionClosed, 1);
  assert.equal(targetClosed, 1);
});

test('RUNNING control reattaches a stale COMPLETED dispatch when Result is not committed', async () => {
  let resumeCalls = 0;
  const browser = {
    chromium: { closeTarget: async () => true },
    resume: async () => {
      resumeCalls += 1;
      return {
        target: { id: 'target-stale-completed' },
        session: { close() {} },
        baseline: { assistantCount: 0, assistantTextLength: 0 },
      };
    },
    sendContinuation: async () => ({
      userCount: 2,
      userTextLength: 20,
      userMessageId: 'resume-user',
    }),
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser,
    stateStore,
    config: { recoveryPrompt: 'continue' },
  });
  const path = '__SELFRUN_DISPATCH__SR-STALE-COMPLETED:turn:1-request__A1.json';
  let stored = dispatch({
    task_id: 'SR-STALE-COMPLETED',
    turn_id: 'SR-STALE-COMPLETED:turn:1',
    request_id: 'SR-STALE-COMPLETED:turn:1-request',
    client_status: 'SEND_REQUESTED',
    server_status: 'COMPLETED',
    conversation_url: 'https://chatgpt.com/c/stale-completed',
    result_document_id: 'RESULT-NOT-COMMITTED',
  });
  const transport = {
    list: async () => [{ path, modTime: '2026-09-29T00:00:00Z', size: 1 }],
    read: async () => structuredClone(stored),
    write: async (requested, body) => {
      assert.equal(requested, path);
      stored = structuredClone(body);
    },
    readGoogleDocText: async () => '{"committed":false}',
  };

  await controller.control('control-stale-completed.json', control({
    task_id: 'SR-STALE-COMPLETED',
    turn_id: 'SR-STALE-COMPLETED:turn:1',
    request_id: 'SR-STALE-COMPLETED:turn:1-request',
    control_epoch: 3,
    state: 'RUNNING',
  }), transportWithControl(transport, controller));

  assert.equal(resumeCalls, 1);
  assert.ok(controller.getActive(path));
  assert.equal(controller.activeDispatches().length, 1);
  assert.equal(stateStore.snapshot().activeCount, 1);
  assert.equal(stored.server_status, 'STARTED');

  await controller.control('control-stale-completed.json', control({
    task_id: 'SR-STALE-COMPLETED',
    turn_id: 'SR-STALE-COMPLETED:turn:1',
    request_id: 'SR-STALE-COMPLETED:turn:1-request',
    control_epoch: 4,
    state: 'STOPPED',
  }), transportWithControl(transport, controller));
});

test('final committed DONE result suppresses reopening without fabricating terminal Task Control', async () => {
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
  }), transportWithControl(transport, controller));

  assert.equal(resumeCalls, 0);
  assert.equal(stored.server_status, 'COMPLETED');
  assert.equal(controller.controls.has('SR-DONE'), true);
  assert.equal(controller.controlForTask('SR-DONE')?.state, 'RUNNING');
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
  await controller.prepare('dispatch-turn-1.json', predecessor, transportWithControl(transport, controller));
  await controller.send('dispatch-turn-1.json', {
    ...predecessor,
    client_status: 'SEND_REQUESTED',
    server_status: 'READY_TO_SUBMIT',
  }, transportWithControl(transport, controller));

  const successor = dispatch({
    task_id: 'SR-SUCCESSOR',
    turn_id: 'SR-SUCCESSOR:turn:2',
    request_id: 'SR-SUCCESSOR:turn:2-request',
    prompt: 'turn-2',
    result_document_id: 'result-turn-2',
    previous_result_document_id: 'result-turn-1',
  });
  await controller.prepare('dispatch-turn-2.json', successor, transportWithControl(transport, controller));

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
  }), transportWithControl(transport, controller));
  await new Promise((resolve) => setTimeout(resolve, 5));
  const b = controller.prepare('b.json', dispatch({
    task_id: 'SR-B', turn_id: 'SR-B:turn:1', request_id: 'SR-B:turn:1-request', prompt: 'B',
  }), transportWithControl(transport, controller));
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
  await controller.prepare('dispatch.json', body, transportWithControl(transport, controller));
  await controller.send('dispatch.json', dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'READY_TO_SUBMIT',
  }), transportWithControl(transport, controller));

  await controller.control('control.json', control({ state: 'STOPPED', control_epoch: 2 }), transportWithControl(transport, controller));

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
  await controller.prepare('idle.json', dispatch(), transportWithControl(transport, controller));
  await controller.send('idle.json', dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'READY_TO_SUBMIT',
  }), transportWithControl(transport, controller));
  await controller.control('control.json', control({ state: 'STOPPED', control_epoch: 2 }), transportWithControl(transport, controller));
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
    }), transportWithControl(transport, controller)),
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


test('standby quiesce releases local active browser ownership without rewriting dispatch', async () => {
  let sessionClosed = 0;
  let targetClosed = 0;
  const browser = {
    chromium: {
      closeTarget: async () => { targetClosed += 1; return true; },
      listExistingTargets: async () => [],
      residentSetMb: async () => 100,
      recycle: async () => ({ recycled: false }),
    },
    resume: async () => ({
      target: { id: 'standby-target' },
      session: { close() { sessionClosed += 1; } },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
    sendContinuation: async () => ({ userCount: 1, userTextLength: 10, userMessageId: 'standby-user' }),
    monitor: async () => new Promise(() => {}),
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser, stateStore,
    config: { recoveryPrompt: 'continue', browserIdleRecycleMs: 0, browserIdleRssMb: 0 },
  });
  const writes = [];
  const transport = { write: async (path, body) => writes.push({ path, body: structuredClone(body) }) };
  await controller.resume('standby.json', dispatch({
    client_status: 'SEND_REQUESTED',
    server_status: 'STARTED',
    conversation_url: 'https://chatgpt.com/c/standby',
  }), transportWithControl(transport, controller));
  const before = writes.length;
  assert.equal(stateStore.snapshot().activeCount, 1);
  await controller.quiesceForStandby();
  assert.equal(controller.activeDispatches().length, 0);
  assert.equal(stateStore.snapshot().activeCount, 0);
  assert.equal(sessionClosed, 1);
  assert.equal(targetClosed, 1);
  assert.equal(writes.length, before);
  assert.equal(stateStore.events.some(({ event }) => event === 'CLUSTER_STANDBY_ACTIVE_RELEASED'), true);
});

test('fresh STOPPED control cancels a prepared dispatch before browser submit', async () => {
  let submitCalls = 0;
  let sessionClosed = 0;
  let targetClosed = 0;
  let authority = control({ control_epoch: 1, state: 'RUNNING' });
  const browser = {
    chromium: { closeTarget: async () => { targetClosed += 1; return true; } },
    prepare: async () => ({
      target: { id: 'prepared-stop-target' },
      session: { close() { sessionClosed += 1; } },
      baseline: { assistantCount: 0, assistantTextLength: 0 },
    }),
    submitPrepared: async () => { submitCalls += 1; return { url: 'https://chatgpt.com/c/must-not-send' }; },
  };
  const stateStore = new MemoryStateStore();
  const controller = new DriveDispatchController({
    browser, stateStore, config: { recoveryPrompt: 'continue' },
  });
  const writes = [];
  const transport = {
    read: async () => structuredClone(authority),
    write: async (path, body) => writes.push({ path, body: structuredClone(body) }),
  };
  await controller.prepare('prepared-stop.json', dispatch(), transport);
  authority = control({ control_epoch: 2, state: 'STOPPED' });
  await controller.send('prepared-stop.json', dispatch({
    client_status: 'SEND_REQUESTED', server_status: 'READY_TO_SUBMIT',
  }), transport);
  assert.equal(submitCalls, 0);
  assert.equal(controller.getActive('prepared-stop.json'), null);
  assert.equal(controller.controlForTask('SR-TEST')?.state, 'STOPPED');
  assert.equal(sessionClosed, 1);
  assert.equal(targetClosed, 1);
});

test('terminal Task Control aborts an in-flight prepare for the whole task', async () => {
  let prepareStarted;
  const started = new Promise((resolve) => { prepareStarted = resolve; });
  const browser = {
    chromium: { closeTarget: async () => true },
    prepare: async ({ signal }) => {
      prepareStarted();
      return new Promise((resolve, reject) => {
        signal.addEventListener('abort', () => reject(signal.reason), { once: true });
      });
    },
  };
  const controller = new DriveDispatchController({
    browser, stateStore: new MemoryStateStore(), config: { recoveryPrompt: 'continue' },
  });
  const transport = { write: async () => {} };
  const preparing = controller.prepare('pending-prepare.json', dispatch(), transport);
  await started;
  await controller.control('__SELFRUN_CONTROL__SR-TEST.json',
    control({ control_epoch: 2, state: 'STOPPED' }), transport);
  await preparing;
  assert.equal(controller.getActive('pending-prepare.json'), null);
  assert.equal(controller.controlForTask('SR-TEST')?.state, 'STOPPED');
});
