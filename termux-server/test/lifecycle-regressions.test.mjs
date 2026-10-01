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
      return structuredClone(base.fallbackBody||dispatch({client_status:'SEND_REQUESTED',server_status:'STARTED',conversation_url:'https://chatgpt.com/c/abc-123',result_document_id:'RESULT-DOC'}));
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

function stalledActivity(overrides = {}) {
  return {
    status: 'STALLED',
    pageUrl: 'https://chatgpt.com/c/abc-123',
    url: 'https://chatgpt.com/c/abc-123',
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
      await onActivity(activity);
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
    quiesce:async()=>{snapshot.streaming=false;snapshot.stopButtonVisible=false;return snapshot;},
    submitIntent:async({prompt,onRequest})=>{
      await onRequest({message_id:'recovery-input',released:true});
      sendCalls+=1;lastPrompt=prompt;if(sendError)throw sendError;
    },
    readSubmission:async()=>({state:'UNKNOWN',probe:snapshot}),
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
      return JSON.stringify({schema:'selfrun-turn-result-v3',task_id:'SR-TEST',turn_id:'SR-TEST:turn:1',turn:1,
        document_id:'RESULT-DOC',event_id:'SR-TEST:turn:1:result',...JSON.parse(resultText)});
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


test('regression: POST timeout preserves uncertain recovery intent and count', async () => {
  const cursor=stalledActivity({streaming:true,stopButtonVisible:true,responseTurnId:'turn-stuck',responseTextLength:955,responseFingerprint:'fixture-fingerprint'});
  const run=await startStalledResume({resultText:'{"committed":false}',snapshot:cursor,activity:cursor,sendError:new Error('Canonical conversation POST timeout')});
  const last=run.writes.at(-1).body;
  assert.equal(last.recovery_count,1);
  assert.equal(last.lifecycle_state,'POST_UNCERTAIN');
  assert.match(last.server_error,/POST timeout/);
});
test('regression: attaching existing conversation does not send an independent continuation', async () => {
  const run=await startStalledResume({resultText:'{"committed":false}',snapshot:stalledActivity(),activity:stalledActivity(),sendError:new Error('Canonical conversation POST timeout')});
  assert.equal(run.resumeSendCalls(),0);
});
test('regression: repeated existing conversation timeout stops after bounded attempts', async () => {
  const stateStore=new MemoryStateStore();
  let calls=0;
  const browser={chromium:{closeTarget:async()=>true},resume:async()=>{calls++;throw new Error('Timed out waiting for existing conversation');}};
  const controller=new DriveDispatchController({browser,stateStore,config:{resumeRetryMs:1,maxAttachAttempts:3}});
  let stored=dispatch({client_status:'SEND_REQUESTED',server_status:'STARTED',conversation_url:'https://chatgpt.com/c/abc-123'});
  const transport=transportWithControl({read:async()=>stored,write:async(_p,b)=>{stored=structuredClone(b);}},controller);
  for(let n=0;n<6;n++) await controller.resume('retry.json',stored,transport);
  assert.ok(calls<=3,'same failure cannot retry indefinitely');
  assert.equal(stored.lifecycle_state,'BLOCKED');
});
