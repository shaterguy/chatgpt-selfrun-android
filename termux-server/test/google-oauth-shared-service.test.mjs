import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import {EventEmitter} from 'node:events';
import {GoogleOAuthTokenProvider} from '../src/google-oauth.mjs';

const PRIVATE = 'private-auth-body-and-secret';
async function setup(t) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'selfrun-shared-auth-'));
  t.after(() => fs.rm(dir, {recursive: true, force: true}));
  const tokenFile = path.join(dir, 'unchanged-token.json');
  const socketPath = path.join(dir, 'service.sock');
  const clientFile = path.join(dir, 'client.json');
  let respond = null;
  const requests = [];
  // The test runtime forbids Unix listen(), so substitute only the socket boundary.
  // Serialization, consumer state, errors and the provider stay production code.
  t.mock.method(net, 'createConnection', actualPath => {
    assert.equal(actualPath, socketPath);
    const peer = new EventEmitter();
    peer.destroy = () => {peer.destroyed = true;};
    peer.setTimeout = () => peer;
    peer.write = data => {
      const request = JSON.parse(String(data).trim());
      requests.push(request);
      queueMicrotask(() => peer.emit('data', Buffer.from(JSON.stringify(respond(request)) + '\n')));
    };
    queueMicrotask(() => respond ? peer.emit('connect') : peer.emit('error', Object.assign(new Error(PRIVATE), {code: 'ENOENT'})));
    return peer;
  });
  return {tokenFile, socketPath, clientFile, requests, serve: fn => {respond = fn;},
    provider: new GoogleOAuthTokenProvider({driveAuthTokenFile: tokenFile, driveAuthClientFile: clientFile, driveAuthServiceSocket: socketPath})};
}

test('SelfRun consumes the common socket without reading, writing or refreshing OAuth locally', async t => {
  const f = await setup(t);
  f.serve(() => ({ok: true, access_token: 'shared-access', expires_at_ms: Date.now() + 3600000}));
  let fileTouches = 0;
  for (const method of ['readFile', 'writeFile', 'rename']) {
    t.mock.method(fs, method, async () => {fileTouches++; throw new Error(PRIVATE);});
  }
  let oauthCalls = 0;
  t.mock.method(globalThis, 'fetch', async () => {oauthCalls++; throw new Error(PRIVATE);});
  const token = await f.provider.accessToken().catch(() => null);
  assert.equal(token, 'shared-access');
  assert.equal(await f.provider.forceRefresh('rejected-access'), 'shared-access');
  assert.equal(fileTouches, 0);
  assert.equal(oauthCalls, 0);
  assert.deepEqual(f.requests, [{operation: 'access'}, {operation: 'refresh', rejected_access_token: 'rejected-access'}]);
  t.mock.restoreAll();
});

test('a service unavailable at startup is retried successfully on the next call', async t => {
  const f = await setup(t);
  await assert.rejects(f.provider.accessToken(), {code: 'AUTH_SERVICE_UNAVAILABLE'});
  f.serve(() => ({ok: true, access_token: 'ready-access', expires_at_ms: Date.now() + 3600000}));
  assert.equal(await f.provider.accessToken(), 'ready-access');
});

test('a temporarily unready service is not cached as a permanent failure', async t => {
  const f = await setup(t);
  let ready = false;
  f.serve(() => ready
    ? {ok: true, access_token: 'ready-access', expires_at_ms: Date.now() + 3600000}
    : {ok: false, error_code: 'AUTH_NOT_READY'});
  await assert.rejects(f.provider.accessToken(), {code: 'AUTH_NOT_READY'});
  ready = true;
  assert.equal(await f.provider.accessToken(), 'ready-access');
});

for (const errorCode of ['AUTH_REAUTH_REQUIRED', PRIVATE]) {
  test(`shared auth failures expose only an allowlisted code (${errorCode === PRIVATE ? 'unknown' : 'known'})`, async t => {
    const f = await setup(t);
    f.serve(() => ({ok: false, error_code: errorCode, error: PRIVATE, error_description: PRIVATE}));
    await assert.rejects(f.provider.accessToken(), error => {
      assert.equal(error.code, errorCode === PRIVATE ? 'AUTH_INTERNAL_ERROR' : errorCode);
      assert.equal(error.cause, undefined);
      assert.equal([error.message, error.stack, JSON.stringify(error)].some(text => text.includes(PRIVATE)), false);
      return true;
    });
  });
}
