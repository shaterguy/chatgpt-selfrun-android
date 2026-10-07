import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {setTimeout as delay} from 'node:timers/promises';

const SECRET = 'private-token-error-description';
async function fixture(t, scenario = 'success', library = true) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'selfrun-auth-helper-'));
  t.after(() => fs.rm(dir, {recursive: true, force: true}));
  const client = path.join(dir, 'client.json');
  const token = path.join(dir, 'existing-token.json');
  const captured = path.join(dir, 'captured.json');
  const store = path.join(dir, 'store.mjs');
  await fs.writeFile(client, JSON.stringify({client_id: 'test-client'}));
  await fs.writeFile(token, JSON.stringify({refresh_token: 'existing-refresh'}));
  const before = await fs.readFile(token, 'utf8');
  if (library) await fs.writeFile(store, `import fs from 'node:fs/promises'; export async function writeAuthorizedToken(file, stored) { await fs.writeFile(${JSON.stringify(captured)}, JSON.stringify({file, stored})); }`);
  const preload = path.join(dir, 'preload.mjs');
  await fs.writeFile(preload, `globalThis.fetch = async url => {
    if (String(url).includes('oauth2.googleapis.com')) {
      if (process.env.AUTH_SCENARIO === 'network') throw new Error(${JSON.stringify(SECRET)});
      if (process.env.AUTH_SCENARIO === 'error') return new Response(JSON.stringify({error:'invalid_grant',error_description:${JSON.stringify(SECRET)}}),{status:400});
      if (process.env.AUTH_SCENARIO === 'unknown') return new Response(JSON.stringify({error:${JSON.stringify(SECRET)},error_description:${JSON.stringify(SECRET)}}),{status:400});
      return new Response(JSON.stringify({access_token:'new-access',refresh_token:'new-refresh',expires_in:3600}));
    }
    if (String(url).includes('www.googleapis.com/drive')) return new Response('{}');
    throw new Error('unexpected external fetch');
  };`);
  const probe = net.createServer();
  await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve));
  const port = probe.address().port;
  await new Promise(resolve => probe.close(resolve));
  const child = spawn(process.execPath, ['--import', preload, 'tools/drive-authorize.mjs'], {
    cwd: path.resolve(import.meta.dirname, '..'),
    env: {...process.env, SELFRUN_DRIVE_AUTH_CLIENT_FILE: client, SELFRUN_DRIVE_AUTH_TOKEN_FILE: token,
      SELFRUN_DRIVE_AUTH_PORT: String(port), TERMUX_GOOGLE_AUTH_LIBRARY: store, AUTH_SCENARIO: scenario},
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', data => {output += data;});
  child.stderr.on('data', data => {output += data;});
  t.after(() => child.kill());
  for (let i = 0; i < 200 && !output.includes('AUTH_URL=') && child.exitCode === null; i++) await delay(10);
  const authLine = output.split('\n').find(line => line.startsWith('AUTH_URL='));
  let response;
  if (authLine) {
    const auth = new URL(authLine.slice('AUTH_URL='.length));
    const callback = new URL(auth.searchParams.get('redirect_uri'));
    callback.searchParams.set('state', auth.searchParams.get('state'));
    callback.searchParams.set(scenario === 'denied' ? 'error' : 'code', scenario === 'denied' ? SECRET : 'test-code');
    response = await fetch(callback);
  }
  if (child.exitCode === null) await Promise.race([once(child, 'exit'), delay(1000)]);
  return {token, before, captured, response, output: () => output, child};
}

test('authorization saves through the installed locked writer at the unchanged token path', async t => {
  const f = await fixture(t);
  assert.equal(f.response.status, 200);
  const saved = await fs.readFile(f.captured, 'utf8').catch(() => null);
  assert.notEqual(saved, null, 'the installed shared writer must receive the authorization save');
  const record = JSON.parse(saved);
  assert.equal(record.file, f.token);
  assert.equal(record.stored.refresh_token, 'new-refresh');
  assert.equal(record.stored.access_token, 'new-access');
  assert.equal(await fs.readFile(f.token, 'utf8'), f.before, 'helper must not bypass installed writer');
  assert.equal(f.child.exitCode, 0, 'successful helper exits without leaving its timeout alive');
});

for (const scenario of ['error', 'unknown', 'network', 'denied']) {
  test(`authorization ${scenario} failures cannot echo raw OAuth text`, async t => {
    const f = await fixture(t, scenario);
    assert.equal(f.response.status, 500);
    const body = await f.response.text();
    assert.equal(body.includes(SECRET), false);
    assert.equal(f.output().includes(SECRET), false);
    if (scenario === 'error') assert.match(body, /invalid_grant/);
    assert.equal(await fs.readFile(f.token, 'utf8'), f.before);
  });
}

test('missing shared writer fails closed without an independent token-file save', async t => {
  const f = await fixture(t, 'success', false);
  assert.equal(await fs.readFile(f.token, 'utf8'), f.before);
  assert.equal(f.output().includes('DRIVE_AUTH_OK'), false);
  assert.equal(f.child.exitCode, 1);
});
