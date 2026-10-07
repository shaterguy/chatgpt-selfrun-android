import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchTransport } from '../src/drive-transport.mjs';
import { SelfRunClusterCoordinator } from '../src/cluster-coordinator.mjs';

for (const kind of ['dispatch', 'cluster']) {
  test(`${kind} 401 sends the rejected access token to shared refresh exactly once`, async t => {
    const transport = kind === 'dispatch'
      ? new DriveDispatchTransport({driveRunsFolderId: 'folder'})
      : new SelfRunClusterCoordinator({stateStore: {snapshot: () => ({})}, config: {driveRunsFolderId: 'folder'}}).transport;
    const rejected = [];
    transport.auth = {accessToken: async () => 'rejected-token', forceRefresh: async token => rejected.push(token)};
    let calls = 0;
    t.mock.method(globalThis, 'fetch', async () => {
      calls++;
      return new Response('private-response', {status: 401});
    });
    await assert.rejects(kind === 'dispatch' ? transport.list() : transport.listMemberStates(), error => {
      assert.equal(error.message.includes('private-response'), false);
      return true;
    });
    assert.deepEqual(rejected, ['rejected-token']);
    assert.equal(calls, 2);
  });
}

for (const operation of ['list', 'resolve', 'create', 'write']) {
  test(`cluster ${operation} HTTP failures discard raw response bodies`, async t => {
    const transport = new SelfRunClusterCoordinator({stateStore: {snapshot: () => ({})}, config: {driveRunsFolderId: 'folder'}}).transport;
    transport.auth = {accessToken: async () => 'access', forceRefresh: async () => 'access'};
    if (operation === 'write') transport.ids.set('member.json', 'file');
    let calls = 0;
    t.mock.method(globalThis, 'fetch', async () => {
      calls++;
      if (operation === 'create' && calls === 1) return new Response(JSON.stringify({files: []}));
      return new Response('private-response', {status: 403});
    });
    await assert.rejects(operation === 'list' ? transport.listMemberStates() : transport.upsertJson('member.json', {}), error => {
      assert.equal(error.message, 'SelfRun cluster ' + operation + ' HTTP 403');
      return true;
    });
  });
}
