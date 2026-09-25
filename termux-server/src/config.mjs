import os from 'node:os';
import path from 'node:path';

const home = os.homedir();
const dataDir = process.env.SELFRUN_SERVER_DATA_DIR || path.join(home, '.selfrun-server');

export const config = Object.freeze({
  version: '3.3.3-dev2',
  host: process.env.SELFRUN_SERVER_HOST || '127.0.0.1',
  port: Number(process.env.SELFRUN_SERVER_PORT || 17831),
  dataDir,
  stateFile: path.join(dataDir, 'state.json'),
  eventsFile: path.join(dataDir, 'events.jsonl'),
  tokenFile: path.join(dataDir, 'token'),
  browserLogFile: path.join(dataDir, 'chromium.log'),
  browserProfile: process.env.SELFRUN_BROWSER_PROFILE || path.join(home, '.config', 'chromium'),
  browserPort: Number(process.env.SELFRUN_BROWSER_PORT || 19227),
  chromiumCommand: process.env.SELFRUN_CHROMIUM || 'chromium-browser',
  probeIntervalMs: Number(process.env.SELFRUN_PROBE_INTERVAL_MS || 1500),
  stallAfterMs: Number(process.env.SELFRUN_STALL_AFTER_MS || 120000),
  navigationTimeoutMs: Number(process.env.SELFRUN_NAVIGATION_TIMEOUT_MS || 30000),
  defaultProjectUrl: process.env.SELFRUN_PROJECT_URL || '',
});
