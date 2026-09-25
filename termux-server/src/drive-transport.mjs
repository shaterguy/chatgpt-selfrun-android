import { spawn } from 'node:child_process';

function runRclone(args, input = null) {
  return new Promise((resolve, reject) => {
    const child = spawn('rclone', args, {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: process.env,
    });
    const stdout = [];
    const stderr = [];
    child.stdout.on('data', (chunk) => stdout.push(chunk));
    child.stderr.on('data', (chunk) => stderr.push(chunk));
    child.on('error', reject);
    child.on('close', (code) => {
      const out = Buffer.concat(stdout).toString('utf8');
      const err = Buffer.concat(stderr).toString('utf8');
      if (code === 0) resolve(out);
      else reject(new Error(`rclone exited ${code}: ${err.trim()}`));
    });
    if (input !== null) child.stdin.end(String(input));
    else child.stdin.end();
  });
}

export class DriveDispatchTransport {
  constructor(config) {
    this.config = config;
  }

  async list() {
    const raw = await runRclone([
      'lsjson',
      this.config.driveRunsPath,
      '--recursive',
      '--files-only',
      '--include',
      '__SELFRUN_DISPATCH__*.json',
      '--max-age',
      '7d',
    ]);
    const items = JSON.parse(raw || '[]');
    return items
      .filter((item) => item && item.Path && !item.IsDir)
      .map((item) => ({
        path: item.Path,
        modTime: item.ModTime || '',
        size: Number(item.Size || 0),
      }))
      .sort((a, b) => String(a.modTime).localeCompare(String(b.modTime)));
  }

  remotePath(relativePath) {
    const base = this.config.driveRunsPath.replace(/\/+$/, '');
    const relative = String(relativePath || '').replace(/^\/+/, '');
    return `${base}/${relative}`;
  }

  async read(relativePath) {
    const raw = await runRclone(['cat', this.remotePath(relativePath)]);
    const body = JSON.parse(raw);
    if (!body || typeof body !== 'object' || Array.isArray(body)) {
      throw new Error('Drive dispatch body must be an object');
    }
    return body;
  }

  async write(relativePath, body) {
    const data = JSON.stringify(body, null, 2) + '\n';
    await runRclone(['rcat', this.remotePath(relativePath)], data);
  }
}
