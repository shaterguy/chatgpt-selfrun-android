import test from 'node:test';
import assert from 'node:assert/strict';
import { SelfRunClusterCoordinator } from '../src/cluster-coordinator.mjs';

class FakeTransport {
  constructor(states = []) {
    this.states = states;
    this.writes = new Map();
  }
  async listMemberStates() {
    return structuredClone(this.states);
  }
  async upsertJson(name, body) {
    this.writes.set(name, structuredClone(body));
  }
}

function store(activeCount = 0, status = 'IDLE') {
  return { snapshot: () => ({ activeCount, status }) };
}

const config = { version: 'test', driveRunsFolderId: 'folder' };

test('lower numeric priority wins when no server is busy', async () => {
  const now = 1_800_000_000_000;
  const transport = new FakeTransport([{
    schema: 'selfrun-server-state-v1',
    server_name: 'Galaxy-Tab-S11-Ultra',
    server_priority: 1,
    role: 'STANDBY',
    active_count: 0,
    status: 'IDLE',
    updated_at: new Date(now).toISOString(),
  }]);
  const cluster = new SelfRunClusterCoordinator({
    stateStore: store(0),
    config,
    transport,
    serverName: 'Galaxy-S25',
    serverPriority: 2,
    settleMs: 0,
    now: () => now,
  });
  await cluster.refresh();
  assert.equal(cluster.snapshot().role, 'STANDBY');
  assert.equal(cluster.snapshot().active_server, 'Galaxy-Tab-S11-Ultra');
  assert.equal(cluster.canDispatch(), false);
});

test('busy active incumbent keeps ownership until its work drains', async () => {
  const now = 1_800_000_000_000;
  let activeCount = 1;
  const stateStore = { snapshot: () => ({ activeCount, status: activeCount ? 'RUNNING' : 'IDLE' }) };
  const transport = new FakeTransport([{
    schema: 'selfrun-server-state-v1',
    server_name: 'Galaxy-Tab-S11-Ultra',
    server_priority: 1,
    role: 'STANDBY',
    active_count: 0,
    status: 'IDLE',
    updated_at: new Date(now).toISOString(),
  }]);
  const cluster = new SelfRunClusterCoordinator({
    stateStore,
    config,
    transport,
    serverName: 'Galaxy-S25',
    serverPriority: 2,
    settleMs: 0,
    now: () => now,
  });
  cluster.role = 'ACTIVE';
  cluster.claimReadyAt = now;
  await cluster.refresh();
  assert.equal(cluster.snapshot().role, 'ACTIVE');
  assert.equal(cluster.snapshot().active_server, 'Galaxy-S25');
  assert.equal(cluster.canDispatch(), true);

  activeCount = 0;
  await cluster.refresh();
  assert.equal(cluster.snapshot().role, 'STANDBY');
  assert.equal(cluster.snapshot().active_server, 'Galaxy-Tab-S11-Ultra');
  assert.equal(cluster.canDispatch(), false);
});

test('stale higher-priority member is ignored', async () => {
  const now = 1_800_000_000_000;
  const transport = new FakeTransport([{
    schema: 'selfrun-server-state-v1',
    server_name: 'Galaxy-Tab-S11-Ultra',
    server_priority: 1,
    role: 'ACTIVE',
    active_count: 0,
    status: 'IDLE',
    updated_at: new Date(now - 70000).toISOString(),
  }]);
  const cluster = new SelfRunClusterCoordinator({
    stateStore: store(0),
    config,
    transport,
    serverName: 'Galaxy-S25',
    serverPriority: 2,
    memberTimeoutMs: 65000,
    settleMs: 0,
    now: () => now,
  });
  await cluster.refresh();
  assert.equal(cluster.snapshot().role, 'ACTIVE');
  assert.equal(cluster.snapshot().active_server, 'Galaxy-S25');
  assert.equal(cluster.canDispatch(), true);
});
