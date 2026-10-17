import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {buildExtensionFiles, EXTENSION_ID} from '../src/browser/extension-package.mjs';
const flush = () => new Promise(resolve => setImmediate(resolve));
function fixture({contexts = [], rejectContext = false, rejectCreate = false} = {}) {
  const files = buildExtensionFiles({bridgeUrl: 'http://127.0.0.1:17777', ownerId: 'fixture-owner', capability: 'fixture-private-capability-value'});
  const handlers = {}, calls = [], errors = [];
  const runtime = {
    getURL: file => `chrome-extension://${EXTENSION_ID}/${file}`,
    getContexts: async filter => {calls.push(['contexts', filter]); if (rejectContext) {rejectContext = false; throw Error('synthetic failure');} return contexts;},
    onInstalled: {addListener(fn) {handlers.installed = fn;}},
    onStartup: {addListener(fn) {handlers.startup = fn;}},
  };
  const chrome = {runtime, windows: {create: async options => {calls.push(['create', options]); if (rejectCreate) {rejectCreate = false; throw Error('synthetic create failure');} return {id: 41};}}};
  vm.runInNewContext(files['background.js'] || '', {chrome, console: {error: msg => errors.push(msg)}});
  return {files, handlers, calls, errors, setContexts: value => {contexts = value;}};
}
test('generated bootstrap adds no permissions or credentials and does not create a tab before a startup event', () => {
  const f = fixture(), manifest = JSON.parse(f.files['manifest.json']);
  assert.equal(manifest.background?.service_worker, 'background.js');
  assert.deepEqual(manifest.permissions, ['scripting', 'storage']);
  assert.deepEqual(manifest.host_permissions, ['https://chatgpt.com/*', 'http://127.0.0.1/*']);
  assert.equal(typeof f.handlers.installed, 'function'); assert.equal(typeof f.handlers.startup, 'function');
  assert.equal(f.files['background.js'].includes('fixture-private-capability-value'), false);
  assert.equal(f.calls.length, 0);
});
test('install and startup events coalesce into exactly one own controller tab', async () => {
  const f = fixture(); f.handlers.installed(); f.handlers.startup(); await flush(); f.handlers.startup(); await flush();
  assert.equal(f.calls.filter(x => x[0] === 'create').length, 1);
  assert.deepEqual(JSON.parse(JSON.stringify(f.calls[0][1])), {contextTypes: ['TAB'], documentUrls: [`chrome-extension://${EXTENSION_ID}/controller.html`]});
  assert.deepEqual(JSON.parse(JSON.stringify(f.calls[1][1])), {url: `chrome-extension://${EXTENSION_ID}/controller.html`, focused: false, type: 'normal'});
});
test('an existing controller is retained and unrelated tabs are never queried or changed', async () => {
  const f = fixture({contexts: [{contextType: 'TAB', tabId: 99}]}); f.handlers.startup(); await flush();
  assert.equal(f.calls.filter(x => x[0] === 'create').length, 0); assert.equal(f.calls.length, 1);
});
test('successful bootstrap does not recreate a controller after stop or a later duplicate event', async () => {
  const f = fixture(); f.handlers.startup(); await flush(); f.setContexts([]); f.handlers.installed(); f.handlers.startup(); await flush();
  assert.equal(f.calls.filter(x => x[0] === 'create').length, 1);
  assert.deepEqual(Object.keys(f.handlers).sort(), ['installed', 'startup']);
});
test('a failed startup remains failed until a later actual startup event, without a timer retry', async () => {
  const f = fixture({rejectContext: true}); f.handlers.installed(); await flush();
  assert.equal(f.errors.length, 1); assert.equal(f.calls.filter(x => x[0] === 'create').length, 0);
  await flush(); assert.equal(f.calls.length, 1);
  f.handlers.startup(); await flush(); assert.equal(f.calls.filter(x => x[0] === 'create').length, 1);
});
test('a new worker lifetime opens its controller on a new browser startup', async () => {
  for (let i = 0; i < 2; i++) {const f = fixture(); f.handlers.startup(); await flush(); assert.equal(f.calls.filter(x => x[0] === 'create').length, 1);}
});

test('window creation rejection retries only on a later startup event', async () => {
 const f = fixture({rejectCreate: true}); f.handlers.installed(); await flush(); assert.equal(f.errors.length, 1);
 assert.equal(f.calls.filter(x => x[0] === 'create').length, 1); await flush(); assert.equal(f.calls.length, 2);
 f.handlers.startup(); await flush(); assert.equal(f.calls.filter(x => x[0] === 'create').length, 2);
});
