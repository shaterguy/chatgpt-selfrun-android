#!/usr/bin/env node
// An isolated loopback CDP-shaped process, never a real browser or account.
import http from 'node:http';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';

const args = process.argv.slice(2);
const port = Number(args.find((arg) => arg.startsWith('--remote-debugging-port='))?.split('=')[1]);
const profile = args.find((arg) => arg.startsWith('--user-data-dir='))?.slice('--user-data-dir='.length);
let settings = {};
try { settings = JSON.parse(fs.readFileSync(`${profile}/fixture-settings.json`, 'utf8')); } catch {}
const browserKey = randomUUID();
let counter = 0;
const targets = new Map();
for (const target of settings.targets || []) targets.set(target.id, target);
const startupUrl = args.at(-1);
let listCount = 0;
let closeCount = 0;
fs.writeFileSync(`${profile}/launch-args.json`, JSON.stringify(args));
const initialTarget = startupUrl?.startsWith('about:blank') && !settings.skipStartup
  && !(settings.honorNoStartupWindow && args.includes('--no-startup-window'))
  ? { id: 'startup', type: 'page', url: settings.startupUrl || startupUrl } : null;
if (initialTarget && !settings.startupAfterLists) targets.set(initialTarget.id, initialTarget);
const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1');
  response.setHeader('Content-Type', 'application/json');
  const send = (value) => response.end(JSON.stringify(value));
  if (url.pathname === '/json/version') return send({ Browser: 'fixture', webSocketDebuggerUrl: `ws://127.0.0.1:${port}/devtools/browser/${browserKey}` });
  if (url.pathname === '/json/list') {
    if (closeCount && settings.listUnavailableAfterClose) { response.statusCode = 503; return send({ error: 'list unavailable' }); }
    listCount += 1;
    if (initialTarget && listCount === settings.startupAfterLists) targets.set(initialTarget.id, initialTarget);
    if (settings.listDelayMs) await new Promise((resolve) => setTimeout(resolve, settings.listDelayMs));
    return send([...targets.values()]);
  }
  if (url.pathname === '/json/new') {
    const target = { id: `created-${++counter}`, type: 'page', url: decodeURIComponent(url.search.slice(1)) };
    targets.set(target.id, target);
    return send(target);
  }
  if (url.pathname.startsWith('/json/close/')) {
    const id = decodeURIComponent(url.pathname.slice('/json/close/'.length));
    if (settings.closeFails) { response.statusCode = 503; return send({ error: 'close unavailable' }); }
    if (settings.closeDelayMs) await new Promise((resolve) => setTimeout(resolve, settings.closeDelayMs));
    closeCount += 1;
    if (!settings.closeKeepsTarget) targets.delete(id);
    return send(true);
  }
  response.statusCode = 404;
  send({ error: 'not found' });
});
server.listen(port, '127.0.0.1');
if (settings.ignoreSigterm || settings.sigtermDelayMs) process.on('SIGTERM', () => {
  fs.writeFileSync(`${profile}/sigterm-received`, 'yes');
  if (!settings.ignoreSigterm) setTimeout(() => process.exit(0), settings.sigtermDelayMs);
});
