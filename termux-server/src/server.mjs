import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { config } from './config.mjs';
import { StateStore, ensureToken } from './state-store.mjs';
import { ChromiumManager } from './browser/cdp.mjs';
import { ChatGptBrowser } from './browser/chatgpt.mjs';
import { DispatchStore } from './drive/dispatch-store.mjs';
import { RcloneRcClient } from './drive/rclone-rc.mjs';
import { DriveDispatcher } from './drive/dispatcher.mjs';

const startedAt = new Date().toISOString();
const stateStore = new StateStore(config);
await stateStore.init();
const token = await ensureToken(config);
const chromium = new ChromiumManager(config);
const browser = new ChatGptBrowser(chromium, config);
const rcloneRc = new RcloneRcClient(config);
const dispatchStore = new DispatchStore(config, rcloneRc);
const dispatcher = new DriveDispatcher({
  store: dispatchStore,
  browser,
  stateStore,
  config,
});

await stateStore.patch({
  status: 'DRIVE_WATCHING',
  activeSignal: null,
  activeTargetId: null,
  conversationUrl: null,
  lastError: null,
}, 'SERVER_V4_START');
dispatcher.start();

function json(res, status, body) {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': data.length,
    'cache-control': 'no-store',
  });
  res.end(data);
}

function authorized(req) {
  const value = String(req.headers.authorization || '');
  if (!value.startsWith('Bearer ')) return false;
  const supplied = Buffer.from(value.slice(7));
  const expected = Buffer.from(token);
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', 'http://127.0.0.1');

    if (req.method === 'GET' && url.pathname === '/health') {
      const state = stateStore.snapshot();
      return json(res, 200, {
        ok: true,
        version: config.version,
        startedAt,
        status: state.status,
        drive: dispatcher.snapshot(),
      });
    }

    if (!authorized(req)) return json(res, 401, { error: 'unauthorized' });

    if (req.method === 'GET' && url.pathname === '/v1/state') {
      return json(res, 200, {
        state: stateStore.snapshot(),
        drive: dispatcher.snapshot(),
      });
    }

    if (req.method === 'POST' && url.pathname === '/v1/browser/probe') {
      const result = await chromium.probe();
      return json(res, 200, result);
    }

    return json(res, 404, { error: 'not_found' });
  } catch (error) {
    return json(res, 500, { error: String(error?.message || error) });
  }
});

server.listen(config.port, config.host, () => {
  console.log(JSON.stringify({
    event: 'SERVER_READY',
    host: config.host,
    port: config.port,
    version: config.version,
    driveRoot: config.driveRemote + config.driveRunsRoot,
  }));
});

let shuttingDown = false;
async function shutdown() {
  if (shuttingDown) return;
  shuttingDown = true;
  await dispatcher.stop().catch(() => {});
  await rcloneRc.close().catch(() => {});
  server.close(() => process.exit(0));
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => { void shutdown(); });
}
