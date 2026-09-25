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
