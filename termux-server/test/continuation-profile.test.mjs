import test from 'node:test';
import assert from 'node:assert/strict';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';

function fixture({ malformed = false, submitTimeout = false, initFirst = false } = {}) {
  let handler, sent = false, submitted = 0;
  const payloads = [], failures = [];
  const probe = () => ({
    url: 'https://chatgpt.com/c/existing-profile',
    composer: true, streaming: true, stopButtonVisible: true, paused: false,
    userCount: sent ? 2 : 1, userTextLength: sent ? 20 : 10,
    userMessageId: sent ? 'user-2' : 'user-1',
  });
  const session = {
    on(method, fn) {
      assert.equal(method, 'Fetch.requestPaused');
      handler = fn;
      return () => { handler = null; };
    },
    async call(method, params = {}) {
      if (method === 'Fetch.enable' || method === 'Fetch.disable') return {};
      if (method === 'Fetch.continueRequest') {
        if (params.requestId === 'initialization-post') return {};
        payloads.push(JSON.parse(Buffer.from(params.postData, 'base64').toString('utf8')));
        sent = true;
        return {};
      }
      if (method === 'Fetch.failRequest') { failures.push(params.requestId); return {}; }
      assert.equal(method, 'Runtime.evaluate');
      const expression = params.expression;
      if (expression.includes('send.click()')) {
        submitted += 1;
        const request = {
          requestId: 'continuation-post',
          request: {
            url: 'https://chatgpt.com/backend-api/f/conversation',
            method: 'POST',
            postData: malformed ? '{bad' : JSON.stringify({
              model: 'instant-default', requested_default_model: 'instant-default',
              thinking_effort: 'standard', conversation_id: 'existing-profile',
              messages: [{ id: 'user-2', author: { role: 'user' } }],
            }),
          },
        };
        if (handler && initFirst) {
          await handler({ requestId: 'initialization-post', request: {
            url: 'https://chatgpt.com/backend-api/conversation/init',
            method: 'POST', postData: '{"model":"instant-default"}',
          } });
          setTimeout(async () => {
            if (handler) await handler(request);
            else sent = true;
          }, 10);
        } else if (handler) await handler(request);
        else sent = true;
        if (submitTimeout) throw new Error('CDP command timeout: Runtime.evaluate');
        return { result: { value: { status: 'SUBMITTED' } } };
      }
      if (expression.includes('const expected=')) return { result: { value: { status: 'READY' } } };
      if (expression.includes("status:send?'SEND_FOUND'")) return { result: { value: { ready: true } } };
      return { result: { value: probe() } };
    },
  };
  return { session, payloads, failures, get submitted() { return submitted; }, get handler() { return handler; } };
}
const operations = [
  { op: 'SET', path: 'model', value: 'gpt-5-6-thinking' },
  { op: 'SET', path: 'thinking_effort', value: 'max' },
];
test('continuation POST applies designated model and reasoning while preserving conversation', async () => {
  const f = fixture();
  const browser = new ChatGptBrowser(null, { navigationTimeoutMs: 1000 });
  const accepted = await browser.sendContinuation({ session: f.session, prompt: 'continue', profileOperations: operations });
  assert.equal(f.payloads.length, 1);
  assert.equal(f.payloads[0].model, 'gpt-5-6-thinking');
  assert.equal(f.payloads[0].requested_default_model, 'gpt-5-6-thinking');
  assert.equal(f.payloads[0].thinking_effort, 'max');
  assert.equal(f.payloads[0].conversation_id, 'existing-profile');
  assert.equal(f.payloads[0].messages[0].id, 'user-2');
  assert.equal(accepted.userCount, 2);
  assert.equal(f.submitted, 1);
  assert.equal(f.handler, null);
});
test('continuation invalid POST is aborted instead of sent with default profile', async () => {
  const f = fixture({ malformed: true });
  const browser = new ChatGptBrowser(null, { navigationTimeoutMs: 1000 });
  await assert.rejects(browser.sendContinuation({ session: f.session, prompt: 'continue', profileOperations: operations }), /not valid JSON/);
  assert.deepEqual(f.failures, ['continuation-post']);
  assert.equal(f.payloads.length, 0);
  assert.equal(f.handler, null);
});
test('continuation accepts intercepted POST after evaluate timeout without duplicate submission', async () => {
  const f = fixture({ submitTimeout: true });
  const browser = new ChatGptBrowser(null, { navigationTimeoutMs: 1000 });
  await browser.sendContinuation({ session: f.session, prompt: 'continue', profileOperations: operations });
  assert.equal(f.payloads[0].thinking_effort, 'max');
  assert.equal(f.submitted, 1);
});

test('continuation keeps profile interception until message POST following initialization', async () => {
  const f = fixture({ initFirst: true });
  const browser = new ChatGptBrowser(null, { navigationTimeoutMs: 1000 });
  await browser.sendContinuation({ session: f.session, prompt: 'continue', profileOperations: operations });
  assert.equal(f.payloads.length, 1);
  assert.equal(f.payloads[0].model, 'gpt-5-6-thinking');
  assert.equal(f.payloads[0].thinking_effort, 'max');
  assert.equal(f.submitted, 1);
});
