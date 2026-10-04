import test from 'node:test';
import assert from 'node:assert/strict';
import { CdpSession } from '../src/browser/cdp.mjs';

class Socket extends EventTarget {
  static OPEN = 1;
  constructor(url) {
    super(); this.url = url; this.readyState = 0; this.sent = [];
    queueMicrotask(() => { this.readyState = 1; this.dispatchEvent(new Event('open')); });
  }
  send(raw) { this.sent.push(JSON.parse(raw)); }
  receive(value) { this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(value) })); }
  close() { this.readyState = 3; this.dispatchEvent(new Event('close')); }
}

async function fixture(t) {
  const originalSocket = globalThis.WebSocket;
  globalThis.WebSocket = Socket;
  t.after(() => { globalThis.WebSocket = originalSocket; });
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let now = 1000;
  t.mock.method(performance, 'now', () => now);
  const events = [];
  t.mock.method(console, 'warn', line => events.push(JSON.parse(line)));
  const session = await new CdpSession('ws://127.0.0.1:19227/devtools/page/private-url?token=secret').connect();
  t.after(() => session.close());
  return { session, socket: session.socket, events,
    advanceClock(ms) { now += ms; }, tick(ms) { now += ms; t.mock.timers.tick(ms); } };
}

test('Runtime timeout records bounded numeric transport evidence without changing its deadline or payload', async t => {
  const f = await fixture(t);
  const params = { expression: 'private conversation; cookie=secret', returnByValue: true, awaitPromise: true };
  const outcome = f.session.call('Runtime.evaluate', params, { timeoutMs: 5000 }).catch(error => error);
  f.tick(4900);
  assert.equal(f.events.length, 0);
  f.socket.receive({ method: 'Network.dataReceived', params: { private_payload: 'secret' } });
  f.tick(100);
  const error = await outcome;
  assert.equal(error.message, 'CDP command timeout: Runtime.evaluate');
  assert.deepEqual(f.socket.sent, [{ id: 1, method: 'Runtime.evaluate', params }]);
  assert.equal(f.events.length, 1);
  const event = f.events[0];
  assert.equal(event.event, 'CDP_QUERY_TIMEOUT');
  assert.equal(event.method, 'Runtime.evaluate');
  assert.equal(event.timeout_ms, 5000);
  assert.equal(event.elapsed_ms, 5000);
  assert.equal(event.socket_state, 1);
  assert.equal(event.pending_count, 0);
  assert.equal(event.last_inbound_age_ms, 100);
  assert.equal(event.inbound_frames_since_send, 1);
  assert.equal(event.inbound_replies_since_send, 0);
  assert.equal(error.cdpDiagnostics.elapsed_ms, 5000);
  assert.doesNotMatch(JSON.stringify(event), /private|cookie|token|secret|127\.0\.0\.1/);
});

test('late Runtime reply is timed once without resolving or corrupting a newer command', async t => {
  const f = await fixture(t);
  const first = f.session.call('Runtime.evaluate', { expression: '1' }, { timeoutMs: 250 }).catch(error => error);
  f.tick(250); await first;
  const second = f.session.call('Runtime.evaluate', { expression: '2' }, { timeoutMs: 500 });
  f.tick(75);
  f.socket.receive({ id: 1, result: { value: 'private late body' } });
  assert.equal(f.session.pending.size, 1);
  const late = f.events.find(event => event.event === 'CDP_QUERY_LATE_REPLY');
  assert.ok(late);
  assert.equal(late.command_id, 1);
  assert.equal(late.elapsed_ms, 325);
  assert.equal(late.after_timeout_ms, 75);
  assert.equal(late.session_id, f.events[0].session_id);
  assert.doesNotMatch(JSON.stringify(late), /private late body/);
  f.socket.receive({ id: 1, error: { message: 'private duplicate' } });
  assert.equal(f.events.filter(event => event.event === 'CDP_QUERY_LATE_REPLY').length, 1);
  f.socket.receive({ id: 2, result: { value: 2 } });
  assert.deepEqual(await second, { value: 2 });
});

test('late-reply evidence retains at most 32 IDs for 60 seconds and ignores expired replies', async t => {
  const f = await fixture(t);
  const outcomes = Array.from({ length: 40 }, () => f.session.call('Runtime.evaluate', {}, { timeoutMs: 250 }).catch(error => error));
  f.tick(250); await Promise.all(outcomes);
  assert.equal(f.session.timedOutQueries.size, 32);
  f.socket.receive({ id: 1, result: {} });
  assert.equal(f.events.filter(event => event.event === 'CDP_QUERY_LATE_REPLY').length, 0);
  f.tick(59999);
  assert.equal(f.session.timedOutQueries.size, 32);
  f.tick(1);
  assert.equal(f.session.timedOutQueries.size, 0);
  f.socket.receive({ id: 40, result: {} });
  assert.equal(f.events.filter(event => event.event === 'CDP_QUERY_LATE_REPLY').length, 0);
});

test('socket close clears late-reply retention and preserves pending command rejection', async t => {
  const f = await fixture(t);
  const first = f.session.call('Runtime.evaluate', {}, { timeoutMs: 250 }).catch(error => error);
  f.tick(250); await first;
  assert.equal(f.session.timedOutQueries.size, 1);
  const second = f.session.call('Runtime.evaluate', {}, { timeoutMs: 1000 }).catch(error => error);
  f.session.close();
  assert.equal((await second).message, 'CDP websocket closed');
  assert.equal(f.session.timedOutQueries.size, 0);
  assert.equal(f.session.pending.size, 0);
  f.tick(60000);
  assert.equal(f.events.length, 1);
});

test('late reply beyond the monotonic retention deadline is ignored even before its expiry callback runs', async t => {
  const f = await fixture(t);
  const pending = f.session.call('Runtime.evaluate', {}, { timeoutMs: 250 }).catch(error => error);
  f.tick(250); await pending;
  f.advanceClock(60001);
  f.socket.receive({ id: 1, result: { value: 'private' } });
  assert.equal(f.events.filter(event => event.event === 'CDP_QUERY_LATE_REPLY').length, 0);
  assert.equal(f.session.timedOutQueries.size, 0);
});

test('a timeout during delayed socket closure cannot repopulate late-reply retention', async t => {
  const f = await fixture(t);
  const pending = f.session.call('Runtime.evaluate', {}, { timeoutMs: 250 }).catch(error => error);
  f.socket.close = () => { f.socket.readyState = 2; };
  f.session.close();
  f.tick(250);
  assert.equal((await pending).message, 'CDP command timeout: Runtime.evaluate');
  assert.equal(f.session.timedOutQueries.size, 0);
  f.socket.readyState = 3; f.socket.dispatchEvent(new Event('close'));
});

test('diagnostic logger failures never replace the original timeout outcome', async t => {
  const f = await fixture(t);
  t.mock.method(console, 'warn', () => { throw new Error('logger failed'); });
  const pending = f.session.call('Runtime.evaluate', {}, { timeoutMs: 250 }).catch(error => error);
  f.tick(250);
  assert.equal((await pending).message, 'CDP command timeout: Runtime.evaluate');
  f.socket.receive({ id: 1, result: { value: 'private' } });
  assert.equal(f.session.pending.size, 0);
});

test('ordinary successful replies and non-Runtime timeouts keep their existing behavior', async t => {
  const f = await fixture(t);
  const successful = f.session.call('Runtime.evaluate', { expression: '1' });
  f.socket.receive({ id: 1, result: { result: { value: 1 } } });
  assert.deepEqual(await successful, { result: { value: 1 } });
  const timeout = f.session.call('Page.enable', {}, { timeoutMs: 250 }).catch(error => error);
  f.tick(250);
  assert.equal((await timeout).message, 'CDP command timeout: Page.enable');
  assert.equal(f.events.length, 0);
  assert.equal(f.session.timedOutQueries.size, 0);
});
