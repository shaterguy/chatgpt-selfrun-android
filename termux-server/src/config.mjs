import os from 'node:os';
import path from 'node:path';

const home = os.homedir();
const dataDir = process.env.SELFRUN_SERVER_DATA_DIR || path.join(home, '.selfrun-server');

export const config = Object.freeze({
  version: '4.0.0-dev2',
  host: process.env.SELFRUN_SERVER_HOST || '127.0.0.1',
  port: Number(process.env.SELFRUN_SERVER_PORT || 17831),
  dataDir,
  stateFile: path.join(dataDir, 'state.json'),
  eventsFile: path.join(dataDir, 'events.jsonl'),
  tokenFile: path.join(dataDir, 'token'),
  driveAuthClientFile: process.env.SELFRUN_DRIVE_AUTH_CLIENT_FILE || path.join(dataDir, 'drive-auth-client.json'),
  driveAuthTokenFile: process.env.SELFRUN_DRIVE_AUTH_TOKEN_FILE || path.join(dataDir, 'drive-auth-token.json'),
  browserLogFile: path.join(dataDir, 'chromium.log'),
  browserProfile: process.env.SELFRUN_BROWSER_PROFILE || path.join(home, '.config', 'chromium'),
  browserPort: Number(process.env.SELFRUN_BROWSER_PORT || 19227),
  chromiumCommand: process.env.SELFRUN_CHROMIUM || 'chromium-browser',
  probeIntervalMs: Number(process.env.SELFRUN_PROBE_INTERVAL_MS || 1500),
  stallAfterMs: Number(process.env.SELFRUN_STALL_AFTER_MS || 600000),
  drivePollMs: Number(process.env.SELFRUN_DRIVE_POLL_MS || 1000),
  driveRunsPath: process.env.SELFRUN_DRIVE_RUNS_PATH || 'selfrun-drive:GPT/Self Run/Runs',
  driveRunsFolderId: process.env.SELFRUN_DRIVE_RUNS_FOLDER_ID || '1LaIjBACRA4bgTblTOHOki5OF39Cyxi4c',
  recoveryPrompt: process.env.SELFRUN_RECOVERY_PROMPT || '현재 턴에 할당된 잔여작업이 있으면 계속 수행해',
  allowUnknownControlRecovery: process.env.SELFRUN_ALLOW_UNKNOWN_CONTROL_RECOVERY === '1',
  dispatchFreshMs: Number(process.env.SELFRUN_DISPATCH_FRESH_MS || 600000),
  dispatchRecoveryMs: Number(process.env.SELFRUN_DISPATCH_RECOVERY_MS || 7200000),
  navigationTimeoutMs: Number(process.env.SELFRUN_NAVIGATION_TIMEOUT_MS || 30000),
  defaultProjectUrl: process.env.SELFRUN_PROJECT_URL || '',
});
