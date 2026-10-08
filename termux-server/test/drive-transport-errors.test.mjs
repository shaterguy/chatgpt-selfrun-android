import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchTransport } from '../src/drive-transport.mjs';

const SECRET = 'private-response-token-and-message';
const NAME = '__SELFRUN_DISPATCH__safe.json';
const OPERATIONS = ['resolve', 'version', 'read', 'write', 'list', 'export'];

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {status});
}

function fixture(t, operation, fail) {
  const transport = new DriveDispatchTransport({driveRunsFolderId: 'folder'});
  transport.auth = {accessToken: async () => SECRET, forceRefresh: async () => {}};
  if (operation !== 'resolve') transport.ids.set(NAME, 'file');
  let calls = 0;
  const fetchMock = t.mock.method(globalThis, 'fetch', async (...args) => {
    calls++;
    if (operation === 'read' && (calls === 1 || calls === 3)) return json({etag: 'v1'});
    return fail(...args);
  });
  const run = () => {
    if (operation === 'list') return transport.list();
    if (operation === 'write') return transport.write(NAME, {state: 'pending'});
    if (operation === 'export') return transport.readGoogleDocText('document');
    return transport.read(NAME);
  };
  return {transport, run, fetchMock};
}

function checkError(expected) {
  return error => {
    assert.equal(error.code, 'DRIVE_TRANSPORT_ERROR');
    assert.deepEqual({...error}, {
      code: 'DRIVE_TRANSPORT_ERROR',
      operation: expected.operation,
      stage: expected.stage,
      http_status: expected.http_status ?? null,
      cause_code: expected.cause_code ?? null,
    });
    assert.equal(error.cause, undefined);
    for (const text of [error.message, error.stack, JSON.stringify(error), JSON.stringify(error, Object.getOwnPropertyNames(error))]) {
      assert.equal(text.includes(SECRET), false);
      assert.equal(text.includes('googleapis.com'), false);
    }
    return true;
  };
}

for (const operation of OPERATIONS) {
  for (const status of [403, 429, 500]) {
    test(`${operation} HTTP ${status} reports only safe structured diagnostics`, async t => {
      const f = fixture(t, operation, () => new Response(SECRET, {status}));
      await assert.rejects(f.run(), checkError({operation, stage: status === 429 ? 'rate_limit' : 'response', http_status: status}));
    });
  }

  for (const cause_code of ['ENOTFOUND', 'ECONNRESET', 'ETIMEDOUT']) {
    test(`${operation} fetch rejection keeps nested ${cause_code} without raw error text`, async t => {
      const cause = Object.assign(new Error(SECRET), {code: cause_code, url: SECRET});
      const f = fixture(t, operation, () => {throw new TypeError('fetch failed ' + SECRET, {cause});});
      await assert.rejects(f.run(), checkError({operation, stage: 'request', cause_code}));
    });
  }

  test(`${operation} response body rejection is sanitized`, async t => {
    const f = fixture(t, operation, () => new Response(new ReadableStream({
      start(controller) {controller.error(Object.assign(new Error(SECRET), {code: 'ECONNRESET'}));},
    })));
    await assert.rejects(f.run(), checkError({operation, stage: 'body', http_status: 200, cause_code: 'ECONNRESET'}));
  });
}

for (const operation of ['resolve', 'version', 'read', 'list']) {
  test(`${operation} JSON parse rejection does not echo response content`, async t => {
    const f = fixture(t, operation, () => new Response('{"value":' + SECRET));
    await assert.rejects(f.run(), checkError({operation, stage: 'parse', http_status: 200}));
  });
}

test('unknown and forged network codes are discarded instead of echoing their contents', async t => {
  const f = fixture(t, 'list', () => {throw Object.assign(new Error(SECRET), {code: SECRET, cause: {code: 'ECONNRESET ' + SECRET}});});
  await assert.rejects(f.run(), checkError({operation: 'list', stage: 'request'}));
});

test('native abort rejection is reported without inferring a network code from its message', async t => {
  const f = fixture(t, 'list', () => {throw new DOMException(SECRET + ' ETIMEDOUT', 'TimeoutError');});
  await assert.rejects(f.run(), checkError({operation: 'list', stage: 'request'}));
});

test('access-token failure is an auth diagnostic and does not attempt a Drive request', async t => {
  const f = fixture(t, 'list', () => {throw Error('unexpected fetch');});
  f.transport.auth.accessToken = async () => {throw new Error(SECRET);};
  await assert.rejects(f.run(), checkError({operation: 'list', stage: 'auth'}));
  assert.equal(f.fetchMock.mock.calls.length, 0);
});

test('401 refresh rejection is an auth diagnostic with the original HTTP status', async t => {
  const f = fixture(t, 'list', () => new Response(SECRET, {status: 401}));
  f.transport.auth.forceRefresh = async () => {throw new Error(SECRET);};
  await assert.rejects(f.run(), checkError({operation: 'list', stage: 'auth', http_status: 401}));
  assert.equal(f.fetchMock.mock.calls.length, 1);
});

test('401 still refreshes once and retries the same request', async t => {
  let refreshed = 0;
  const f = fixture(t, 'list', () => refreshed ? json({files: []}) : new Response(SECRET, {status: 401}));
  f.transport.auth.forceRefresh = async () => {refreshed++;};
  assert.deepEqual(await f.run(), []);
  assert.equal(refreshed, 1);
  assert.equal(f.fetchMock.mock.calls.length, 3);
});

test('a second 401 stops after the existing single refresh', async t => {
  let refreshed = 0;
  const f = fixture(t, 'list', () => new Response(SECRET, {status: 401}));
  f.transport.auth.forceRefresh = async () => {refreshed++;};
  await assert.rejects(f.run(), checkError({operation: 'list', stage: 'auth', http_status: 401}));
  assert.equal(refreshed, 1);
  assert.equal(f.fetchMock.mock.calls.length, 2);
});

for (const status of [403, 429]) {
  test(`HTTP ${status} quota details retain the existing 60-second request suppression`, async t => {
    const now = 100000;
    t.mock.method(Date, 'now', () => now);
    const f = fixture(t, 'list', () => new Response('rateLimitExceeded ' + SECRET, {status}));
    await assert.rejects(f.run(), checkError({operation: 'list', stage: status === 429 ? 'rate_limit' : 'response', http_status: status}));
    assert.equal(f.transport.blockedUntil, now + 60000);
    await assert.rejects(f.run(), checkError({operation: 'list', stage: 'rate_limit'}));
    assert.equal(f.fetchMock.mock.calls.length, 1);
  });
}

test('HTTP 429 without quota text keeps existing non-suppression behavior', async t => {
  const f = fixture(t, 'list', () => new Response(SECRET, {status: 429}));
  await assert.rejects(f.run(), checkError({operation: 'list', stage: 'rate_limit', http_status: 429}));
  assert.equal(f.transport.blockedUntil, 0);
});

test('HTTP 412 remains a failed conditional write and keeps the If-Match header', async t => {
  const expected = {state: 'pending'};
  const f = fixture(t, 'write', () => new Response(SECRET, {status: 412}));
  f.transport.etags.set(expected, '"v1"');
  await assert.rejects(f.transport.write(NAME, {state: 'done'}, {expected}), error => {
    checkError({operation: 'write', stage: 'response', http_status: 412})(error);
    assert.match(error.message, /412/);
    return true;
  });
  assert.equal(f.fetchMock.mock.calls.length, 1);
  assert.equal(f.fetchMock.mock.calls[0].arguments[1].headers['if-match'], '"v1"');
});

test('known absent files keep DRIVE_FILE_NOT_FOUND without echoing the requested name', async t => {
  const f = fixture(t, 'resolve', () => json({files: []}));
  await assert.rejects(f.transport.read(SECRET + '.json'), error => {
    assert.equal(error.code, 'DRIVE_FILE_NOT_FOUND');
    assert.equal(error.message.includes(SECRET), false);
    return true;
  });
});

test('successful read still binds its ETag to a later conditional write', async t => {
  const requests = [];
  const f = fixture(t, 'read', (_url, init) => {
    requests.push(init);
    return json({state: 'pending'});
  });
  const expected = await f.run();
  await f.transport.write(NAME, {state: 'done'}, {expected});
  assert.deepEqual(expected, {state: 'pending'});
  assert.equal(requests[1].headers['if-match'], 'v1');
});

test('write JSON serialization failure is sanitized before sending', async t => {
  const f = fixture(t, 'write', () => {throw Error('unexpected fetch');});
  const body = {};
  body[SECRET] = body;
  await assert.rejects(f.transport.write(NAME, body), checkError({operation: 'write', stage: 'parse'}));
  assert.equal(f.fetchMock.mock.calls.length, 0);
});

for (const [operation, body] of [['resolve', {files: {}}], ['version', null], ['read', []], ['list', null]]) {
  test(`${operation} unusable JSON shape produces a safe parse diagnostic`, async t => {
    const f = fixture(t, operation, () => json(body));
    await assert.rejects(f.run(), checkError({operation, stage: 'parse', http_status: 200}));
  });
}

test('an HTTP error body is still consumed before returning its safe diagnostic', async t => {
  const response = new Response(SECRET, {status: 500});
  const f = fixture(t, 'list', () => response);
  await assert.rejects(f.run(), checkError({operation: 'list', stage: 'response', http_status: 500}));
  assert.equal(response.bodyUsed, true);
});

test('an HTTP error with a broken body preserves its numeric status and safe cause', async t => {
  const f = fixture(t, 'list', () => new Response(new ReadableStream({
    start(controller) {controller.error(Object.assign(new Error(SECRET), {code: 'ECONNRESET'}));},
  }), {status: 500}));
  await assert.rejects(f.run(), checkError({operation: 'list', stage: 'body', http_status: 500, cause_code: 'ECONNRESET'}));
});

for (const operation of ['resolve', 'list']) {
  test(`${operation} null file metadata produces a safe parse diagnostic`, async t => {
    const f = fixture(t, operation, () => json({files: [null]}));
    await assert.rejects(f.run(), checkError({operation, stage: 'parse', http_status: 200}));
  });
}
