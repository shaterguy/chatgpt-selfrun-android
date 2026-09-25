import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { config } from './config.mjs';
import { StateStore, ensureToken } from './state-store.mjs';
import { ChromiumManager } from './browser/cdp.mjs';
import { ChatGptBrowser } from './browser/chatgpt.mjs';
import { SelfRunController } from './controller.mjs';
import { DriveDispatchTransport } from './drive-transport.mjs';
import { DriveDispatchController } from './drive-controller.mjs';
import { DriveDispatchWatcher } from './drive-watcher.mjs';

const startedAt = new Date().toISOString();
const stateStore = new StateStore(config);
await stateStore.init();
const token = await ensureToken(config);
const chromium = new ChromiumManager(config);
const browser = new ChatGptBrowser(chromium, config);
const controller = new SelfRunController({ browser, stateStore, config });
await controller.initialize();
const driveTransport = new DriveDispatchTransport(config);
const driveController = new DriveDispatchController({ browser, stateStore, config });
const driveWatcher = new DriveDispatchWatcher({
  transport: driveTransport,
  controller: driveController,
  config,
});
void driveWatcher.start();

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

async function readJson(req) {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of req) {
    bytes += chunk.length;
    if (bytes > 1024 * 1024) throw new Error('Request body too large');
    chunks.push(chunk);
  }
  const text = Buffer.concat(chunks).toString('utf8');
  if (!text) return {};
  return JSON.parse(text);
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
        generation: state.generation,
      });
    }

    if (!authorized(req)) return json(res, 401, { error: 'unauthorized' });

    if (req.method === 'GET' && url.pathname === '/v1/state') {
      return json(res, 200, stateStore.snapshot());
    }

    if (req.method === 'POST' && url.pathname === '/v1/signals') {
      const body = await readJson(req);
      const result = await controller.accept(body);
      return json(res, 202, result);
    }

    if (req.method === 'POST' && url.pathname === '/v1/browser/probe') {
      const result = await chromium.probe();
      return json(res, 200, result);
    }

    return json(res, 404, { error: 'not_found' });
  } catch (error) {
    const message = String(error?.message || error);
    const status = /required|must be|unsupported|body too large|JSON/i.test(message) ? 400 : 500;
    return json(res, status, { error: message });
  }
});

server.listen(config.port, config.host, () => {
  console.log(JSON.stringify({
    event: 'SERVER_READY',
    host: config.host,
    port: config.port,
    version: config.version,
    stateFile: config.stateFile,
  }));
});

let shuttingDown = false;
async function shutdown() {
  if (shuttingDown) return;
  shuttingDown = true;
  driveWatcher.stop();
  await driveTransport.close().catch(() => {});
  server.close(() => process.exit(0));
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => { void shutdown(); });
}
