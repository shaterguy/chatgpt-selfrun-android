import os from 'node:os';
import path from 'node:path';

const home = os.homedir();
const dataDir = process.env.SELFRUN_SERVER_DATA_DIR || path.join(home, '.selfrun-server');

export const config = Object.freeze({
  version: '4.0.0-dev1',
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
  navigationTimeoutMs: Number(process.env.SELFRUN_NAVIGATION_TIMEOUT_MS || 30000),
  startConfirmationTimeoutMs: Number(process.env.SELFRUN_START_CONFIRMATION_TIMEOUT_MS || 90000),
  livenessRecoveryMs: Number(process.env.SELFRUN_LIVENESS_RECOVERY_MS || 300000),
  recoveryPrompt: process.env.SELFRUN_RECOVERY_PROMPT || '계속 진행해.',
  drivePollMs: Number(process.env.SELFRUN_DRIVE_POLL_MS || 2000),
  driveRemote: process.env.SELFRUN_DRIVE_REMOTE || 'selfrun-drive:',
  driveRunsRoot: process.env.SELFRUN_DRIVE_RUNS_ROOT || 'GPT/Self Run/Runs',
  rcloneCommand: process.env.SELFRUN_RCLONE || 'rclone',
  rcloneRcSocket: process.env.SELFRUN_RCLONE_RC_SOCKET || path.join(dataDir, 'rclone-rc.sock'),
  rcloneRcLogFile: process.env.SELFRUN_RCLONE_RC_LOG || path.join(dataDir, 'rclone-rc.log'),
});
