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

test('watcher processes task CONTROL before restart dispatch recovery regardless of Drive listing order', async () => {
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
  assert.deepEqual(calls, ['control', 'resume']);
});
