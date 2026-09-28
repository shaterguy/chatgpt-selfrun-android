import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';

test('watcher routes Drive document states without direct app-server networking', async () => {
  const calls = [];
  const createdAt = Date.now();
  const bodies = new Map([
    ['pending.json', {
      schema: 'selfrun-server-dispatch-v1',
      client_status: 'CREATE_REQUESTED',
      server_status: 'PENDING',
      created_at_ms: createdAt,
    }],
    ['send.json', {
      schema: 'selfrun-server-dispatch-v1',
      client_status: 'SEND_REQUESTED',
      server_status: 'READY_TO_SUBMIT',
      created_at_ms: createdAt,
    }],
    ['cancel.json', {
      schema: 'selfrun-server-dispatch-v1',
      client_status: 'CANCELLED',
      server_status: 'READY_TO_SUBMIT',
      created_at_ms: createdAt,
    }],
  ]);
  const transport = {
    list: async () => [...bodies.keys()].map((path, index) => ({
      path,
      modTime: `2026-09-25T00:00:0${index}Z`,
      size: 10 + index,
    })),
    read: async (path) => structuredClone(bodies.get(path)),
  };
  const controller = {
    prepare: async (path) => calls.push(['prepare', path]),
    send: async (path) => calls.push(['send', path]),
    cancel: async (path) => calls.push(['cancel', path]),
  };
  const watcher = new DriveDispatchWatcher({
    transport,
    controller,
    config: { drivePollMs: 2000, dispatchFreshMs: 600000 },
  });

  await watcher.scanOnce();
  assert.deepEqual(calls, [
    ['prepare', 'pending.json'],
    ['send', 'send.json'],
    ['cancel', 'cancel.json'],
  ]);

  await watcher.scanOnce();
  assert.equal(calls.length, 3);
});

test('watcher routes Task CONTROL documents to the controller', async () => {
  const calls = [];
  const body = {
    schema: 'selfrun-task-control-v1',
    task_id: 'SR-TEST',
    control_epoch: 4,
    state: 'PAUSED',
    turn_id: 'SR-TEST:turn:1',
    request_id: 'SR-TEST:turn:1-request',
    updated_at_ms: Date.now(),
  };
  const transport = {
    list: async () => [{
      path: '__SELFRUN_CONTROL__SR-TEST.json',
      modTime: '2026-09-25T00:00:00Z',
      size: 200,
    }],
    read: async () => structuredClone(body),
  };
  const controller = {
    active: null,
    control: async (path, value) => calls.push([path, value.state, value.control_epoch]),
  };
  const watcher = new DriveDispatchWatcher({
    transport,
    controller,
    config: { drivePollMs: 5000, dispatchFreshMs: 600000 },
  });

  await watcher.scanOnce();
  assert.deepEqual(calls, [['__SELFRUN_CONTROL__SR-TEST.json', 'PAUSED', 4]]);
  await watcher.scanOnce();
  assert.equal(calls.length, 1);
});

test('watcher processes newest task CONTROL first after restart', async () => {
  const calls = [];
  const bodies = new Map([
    ['__SELFRUN_CONTROL__SR-OLD.json', {
      schema: 'selfrun-task-control-v1', task_id: 'SR-OLD', control_epoch: 1,
      state: 'RUNNING', turn_id: 'SR-OLD:turn:1', request_id: 'SR-OLD:turn:1-request',
      updated_at_ms: 1,
    }],
    ['__SELFRUN_CONTROL__SR-NEW.json', {
      schema: 'selfrun-task-control-v1', task_id: 'SR-NEW', control_epoch: 2,
      state: 'RUNNING', turn_id: 'SR-NEW:turn:2', request_id: 'SR-NEW:turn:2-request',
      updated_at_ms: 2,
    }],
  ]);
  const transport = {
    list: async () => [
      { path: '__SELFRUN_CONTROL__SR-OLD.json', modTime: '2026-09-26T00:00:00Z', size: 10 },
      { path: '__SELFRUN_CONTROL__SR-NEW.json', modTime: '2026-09-27T00:00:00Z', size: 10 },
    ],
    read: async (path) => structuredClone(bodies.get(path)),
  };
  const controller = {
    control: async (path) => calls.push(path),
  };
  const watcher = new DriveDispatchWatcher({
    transport,
    controller,
    config: { drivePollMs: 5000, dispatchFreshMs: 600000 },
  });

  await watcher.scanOnce();
  assert.deepEqual(calls, [
    '__SELFRUN_CONTROL__SR-NEW.json',
    '__SELFRUN_CONTROL__SR-OLD.json',
  ]);
});

test('watcher ignores stale dispatches that do not match the current RUNNING control turn', async () => {
  const calls = [];
  const now = Date.now();
  const controlPath = '__SELFRUN_CONTROL__SR-CURRENT.json';
  const oldPath = '__SELFRUN_DISPATCH__SR-CURRENT:turn:12-request__A5.json';
  const currentPath = '__SELFRUN_DISPATCH__SR-CURRENT:turn:13-request__A1.json';
  const bodies = new Map([
    [controlPath, {
      schema: 'selfrun-task-control-v1', task_id: 'SR-CURRENT', control_epoch: 38,
      state: 'RUNNING', turn_id: 'SR-CURRENT:turn:13', request_id: 'SR-CURRENT:turn:13-request',
      updated_at_ms: now,
    }],
    [oldPath, {
      schema: 'selfrun-server-dispatch-v1', client_status: 'SEND_REQUESTED', server_status: 'STARTED',
      task_id: 'SR-CURRENT', turn_id: 'SR-CURRENT:turn:12', request_id: 'SR-CURRENT:turn:12-request',
      created_at_ms: now - 1000, started_at_ms: now - 1000, updated_at_ms: now - 1000,
    }],
    [currentPath, {
      schema: 'selfrun-server-dispatch-v1', client_status: 'CREATE_REQUESTED', server_status: 'PENDING',
      task_id: 'SR-CURRENT', turn_id: 'SR-CURRENT:turn:13', request_id: 'SR-CURRENT:turn:13-request',
      created_at_ms: now,
    }],
  ]);
  const transport = {
    list: async () => [
      { path: oldPath, modTime: '2026-09-27T00:00:00Z', size: 10 },
      { path: controlPath, modTime: '2026-09-27T00:00:02Z', size: 10 },
      { path: currentPath, modTime: '2026-09-27T00:00:03Z', size: 10 },
    ],
    read: async (path) => structuredClone(bodies.get(path)),
  };
  const controller = {
    control: async () => calls.push(['control']),
    resume: async (path) => calls.push(['resume', path]),
    prepare: async (path) => calls.push(['prepare', path]),
    send: async () => {}, cancel: async () => {},
  };
  const watcher = new DriveDispatchWatcher({
    transport, controller,
    config: { drivePollMs: 5000, dispatchFreshMs: 600000, dispatchRecoveryMs: 7200000 },
  });

  await watcher.scanOnce();
  assert.deepEqual(calls, [
    ['control'],
    ['prepare', currentPath],
  ]);
});

test('watcher resumes a recent active conversation after restart', async () => {
  const calls = [];
  const now = Date.now();
  const transport = {
    list: async () => [{ path: 'started.json', modTime: '2026-09-25T00:00:00Z', size: 10 }],
    read: async () => ({
      schema: 'selfrun-server-dispatch-v1',
      client_status: 'SEND_REQUESTED',
      server_status: 'STARTED',
      created_at_ms: now - 60 * 60 * 1000,
      started_at_ms: now - 60 * 60 * 1000,
      updated_at_ms: now - 60 * 60 * 1000,
    }),
  };
  const controller = {
    prepare: async () => calls.push('prepare'),
    send: async () => calls.push('send'),
    resume: async () => calls.push('resume'),
    cancel: async () => calls.push('cancel'),
  };
  const watcher = new DriveDispatchWatcher({
    transport,
    controller,
    config: { drivePollMs: 5000, dispatchFreshMs: 600000, dispatchRecoveryMs: 7200000 },
  });

  await watcher.scanOnce();
  assert.deepEqual(calls, ['resume']);
});

test('watcher retries the same dispatch after a transient controller failure', async () => {
  let resumeCalls = 0;
  const now = Date.now();
  const transport = {
    list: async () => [{ path: 'retry.json', modTime: '2026-09-25T00:00:00Z', size: 10 }],
    read: async () => ({
      schema: 'selfrun-server-dispatch-v1',
      client_status: 'SEND_REQUESTED',
      server_status: 'STARTED',
      created_at_ms: now,
      updated_at_ms: now,
    }),
  };
  const controller = {
    active: null,
    prepare: async () => {},
    send: async () => {},
    cancel: async () => {},
    resume: async () => {
      resumeCalls += 1;
      if (resumeCalls === 1) throw new Error('temporary');
    },
  };
  const watcher = new DriveDispatchWatcher({
    transport,
    controller,
    config: { drivePollMs: 5000, dispatchFreshMs: 600000, dispatchRecoveryMs: 7200000 },
  });

  await assert.rejects(watcher.scanOnce(), /temporary/);
  await watcher.scanOnce();
  assert.equal(resumeCalls, 2);
});

test('watcher retries a rate-limited errored resume when its persisted retry time arrives', async () => {
  const realNow = Date.now;
  let now = 1_800_000_000_000;
  Date.now = () => now;
  try {
    const taskId = 'SR-RATE';
    const controlPath = `__SELFRUN_CONTROL__${taskId}.json`;
    const dispatchPath = '__SELFRUN_DISPATCH__SR-RATE:turn:2-request__A2.json';
    const controlBody = {
      schema: 'selfrun-task-control-v1',
      task_id: taskId,
      control_epoch: 7,
      state: 'RUNNING',
      turn_id: 'SR-RATE:turn:2',
      request_id: 'SR-RATE:turn:2-request',
      updated_at_ms: now,
    };
    const dispatchBody = {
      schema: 'selfrun-server-dispatch-v1',
      task_id: taskId,
      turn_id: 'SR-RATE:turn:2',
      request_id: 'SR-RATE:turn:2-request',
      project_url: 'https://chatgpt.com/g/example/project',
      prompt: 'TASK_ID=SR-RATE',
      profile_operations: [],
      dispatch_attempt: 2,
      client_status: 'SEND_REQUESTED',
      server_status: 'ERROR',
      conversation_url: 'https://chatgpt.com/c/rate-limited-resume',
      resume_retry_at_ms: now + 60000,
      updated_at_ms: now,
    };
    let currentControl = null;
    let retryCalls = 0;
    const transport = {
      list: async () => [
        { path: controlPath, modTime: '2026-09-26T00:00:00Z', size: 200 },
        { path: dispatchPath, modTime: '2026-09-26T00:00:01Z', size: 500 },
      ],
      read: async (path) => structuredClone(path === controlPath ? controlBody : dispatchBody),
    };
    const controller = {
      active: null,
      control: async (_path, value) => { currentControl = structuredClone(value); },
      controlForTask: () => currentControl,
      retryResumeFromControl: async (value) => {
        assert.equal(value.state, 'RUNNING');
        retryCalls += 1;
      },
      resume: async () => { throw new Error('fallback resume must not be used'); },
      cancel: async () => {},
      prepare: async () => {},
      send: async () => {},
    };
    const watcher = new DriveDispatchWatcher({
      transport,
      controller,
      config: { drivePollMs: 5000, dispatchFreshMs: 600000, dispatchRecoveryMs: 7200000 },
    });

    await watcher.scanOnce();
    assert.equal(retryCalls, 0);
    now += 59999;
    await watcher.scanOnce();
    assert.equal(retryCalls, 0);
    now += 1;
    await watcher.scanOnce();
    assert.equal(retryCalls, 1);
  } finally {
    Date.now = realNow;
  }
});

test('watcher ignores stale unprocessed dispatch after restart', async () => {
  const calls = [];
  const transport = {
    list: async () => [{ path: 'stale.json', modTime: '2026-09-25T00:00:00Z', size: 10 }],
    read: async () => ({
      schema: 'selfrun-server-dispatch-v1',
      client_status: 'CREATE_REQUESTED',
      server_status: 'PENDING',
      created_at_ms: Date.now() - 700000,
    }),
  };
  const controller = {
    prepare: async () => calls.push('prepare'),
    send: async () => calls.push('send'),
    cancel: async () => calls.push('cancel'),
  };
  const watcher = new DriveDispatchWatcher({
    transport,
    controller,
    config: { drivePollMs: 5000, dispatchFreshMs: 600000 },
  });

  await watcher.scanOnce();
  assert.deepEqual(calls, []);
});

test('watcher processes terminal task CONTROL before and blocks restart dispatch recovery regardless of Drive listing order', async () => {
  const calls = [];
  const now = Date.now();
  const bodies = new Map([
    ['started.json', {
      schema: 'selfrun-server-dispatch-v1',
      client_status: 'SEND_REQUESTED',
      server_status: 'STARTED',
      task_id: 'SR-ORDER',
      turn_id: 'SR-ORDER:turn:9',
      request_id: 'SR-ORDER:turn:9-request',
      created_at_ms: now - 1000,
      started_at_ms: now - 1000,
      updated_at_ms: now - 1000,
    }],
    ['__SELFRUN_CONTROL__SR-ORDER.json', {
      schema: 'selfrun-task-control-v1',
      task_id: 'SR-ORDER',
      control_epoch: 5,
      state: 'STOPPED',
      turn_id: 'SR-ORDER:turn:9',
      request_id: 'SR-ORDER:turn:9-request',
      updated_at_ms: now,
    }],
  ]);
  const transport = {
    list: async () => [
      { path: 'started.json', modTime: '2026-09-26T00:00:00Z', size: 10 },
      { path: '__SELFRUN_CONTROL__SR-ORDER.json', modTime: '2026-09-26T00:00:01Z', size: 10 },
    ],
    read: async (path) => structuredClone(bodies.get(path)),
  };
  const controller = {
    control: async () => calls.push('control'),
    resume: async () => calls.push('resume'),
    prepare: async () => {},
    send: async () => {},
    cancel: async () => {},
  };
  const watcher = new DriveDispatchWatcher({
    transport,
    controller,
    config: { drivePollMs: 5000, dispatchFreshMs: 600000, dispatchRecoveryMs: 7200000 },
  });

  await watcher.scanOnce();
  assert.deepEqual(calls, ['control']);
});

test('STOPPED Drive control blocks restart recovery without retaining controller control memory', async () => {
  const calls = [];
  const now = Date.now();
  let dispatchRevision = 1;
  const taskControlPath = '__SELFRUN_CONTROL__SR-BLOCKED.json';
  const dispatchPath = '__SELFRUN_DISPATCH__SR-BLOCKED:turn:9-request__A1.json';
  const bodies = new Map([
    [taskControlPath, {
      schema: 'selfrun-task-control-v1',
      task_id: 'SR-BLOCKED',
      control_epoch: 7,
      state: 'STOPPED',
      turn_id: 'SR-BLOCKED:turn:9',
      request_id: 'SR-BLOCKED:turn:9-request',
      updated_at_ms: now,
    }],
    [dispatchPath, {
      schema: 'selfrun-server-dispatch-v1',
      client_status: 'SEND_REQUESTED',
      server_status: 'STARTED',
      task_id: 'SR-BLOCKED',
      turn_id: 'SR-BLOCKED:turn:9',
      request_id: 'SR-BLOCKED:turn:9-request',
      created_at_ms: now - 1000,
      started_at_ms: now - 1000,
      updated_at_ms: now - 1000,
    }],
  ]);
  const transport = {
    list: async () => [
      { path: dispatchPath, modTime: `2026-09-26T00:00:0${dispatchRevision}Z`, size: 100 + dispatchRevision },
      { path: taskControlPath, modTime: '2026-09-26T00:00:00Z', size: 200 },
    ],
    read: async (path) => structuredClone(bodies.get(path)),
  };
  const controller = {
    active: null,
    control: async (path, value) => calls.push(['control', path, value.state]),
    controlForTask: () => null,
    resume: async (path) => calls.push(['resume', path]),
    prepare: async () => {},
    send: async () => {},
    cancel: async () => {},
  };
  const watcher = new DriveDispatchWatcher({
    transport,
    controller,
    config: { drivePollMs: 5000, dispatchFreshMs: 600000, dispatchRecoveryMs: 7200000 },
  });

  await watcher.scanOnce();
  assert.deepEqual(calls, [['control', taskControlPath, 'STOPPED']]);

  dispatchRevision = 2;
  bodies.get(dispatchPath).updated_at_ms = now + 1000;
  await watcher.scanOnce();
  assert.deepEqual(calls, [['control', taskControlPath, 'STOPPED']]);
});


test('prepared dispatch reads only its exact file and sends without waiting for a full folder scan', async () => {
  let watcher;
  let listCalls = 0;
  let readCalls = 0;
  let sendCalls = 0;
  const active = {
    path: '__SELFRUN_DISPATCH__SR-FAST:turn:1-request__A1.json',
    serverStatus: 'READY_TO_SUBMIT',
    publishPending: false,
  };
  const transport = {
    list: async () => { listCalls += 1; return []; },
    read: async (path) => {
      readCalls += 1;
      assert.equal(path, active.path);
      return {
        schema: 'selfrun-server-dispatch-v1',
        client_status: 'SEND_REQUESTED',
        server_status: 'READY_TO_SUBMIT',
        task_id: 'SR-FAST',
        turn_id: 'SR-FAST:turn:1',
        request_id: 'SR-FAST:turn:1-request',
        created_at_ms: Date.now(),
      };
    },
  };
  const controller = {
    activeDispatches: () => [active],
    send: async (path) => {
      assert.equal(path, active.path);
      sendCalls += 1;
      active.serverStatus = 'STARTED';
      watcher.stop();
    },
    cancel: async () => { throw new Error('cancel not expected'); },
  };
  watcher = new DriveDispatchWatcher({
    transport,
    controller,
    config: { drivePollMs: 15000, preparedPollMs: 1, dispatchFreshMs: 600000 },
  });

  await watcher.start();
  assert.equal(sendCalls, 1);
  assert.equal(readCalls, 1);
  assert.equal(listCalls, 0);
});
