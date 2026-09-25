import http from 'node:http';
import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';

const MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export class RcloneRcClient {
  constructor(config) {
    this.config = config;
    this.child = null;
    this.starting = null;
  }

  async call(command, input = {}, timeoutMs = 120000) {
    await this.ensureStarted();
    return this.#post(command, input, timeoutMs);
  }

  async ensureStarted() {
    try {
      await this.#post('rc/noop', {}, 1500);
      return;
    } catch {}

    if (this.starting) return this.starting;
    this.starting = this.#start();
    try {
      await this.starting;
    } finally {
      this.starting = null;
    }
  }

  async #start() {
    await fs.mkdir(this.config.dataDir, { recursive: true });
    await fs.rm(this.config.rcloneRcSocket, { force: true }).catch(() => {});
    this.child = spawn(this.config.rcloneCommand, [
      'rcd',
      '--rc-addr', this.config.rcloneRcSocket,
      '--rc-no-auth',
      '--log-file', this.config.rcloneRcLogFile,
      '--log-level', 'INFO',
    ], {
      detached: false,
      stdio: 'ignore',
      env: process.env,
    });

    let lastError = null;
    for (let attempt = 0; attempt < 80; attempt += 1) {
      if (this.child.exitCode !== null) {
        throw new Error('rclone rcd exited before socket became ready: ' + this.child.exitCode);
      }
      try {
        await this.#post('rc/noop', {}, 1000);
        return;
      } catch (error) {
        lastError = error;
        await delay(250);
      }
    }
    throw new Error('rclone rcd socket did not become ready', { cause: lastError });
  }

  #post(command, input, timeoutMs) {
    const payload = Buffer.from(JSON.stringify(input));
    return new Promise((resolve, reject) => {
      const request = http.request({
        socketPath: this.config.rcloneRcSocket,
        path: '/' + String(command || '').replace(/^\/+/, ''),
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'content-length': payload.length,
        },
      }, (response) => {
        const chunks = [];
        let bytes = 0;
        response.on('data', (chunk) => {
          bytes += chunk.length;
          if (bytes > MAX_RESPONSE_BYTES) {
            request.destroy(new Error('rclone RC response too large'));
            return;
          }
          chunks.push(chunk);
        });
        response.on('end', () => {
          const raw = Buffer.concat(chunks).toString('utf8');
          let body = {};
          try { body = raw.trim() ? JSON.parse(raw) : {}; }
          catch (error) { reject(new Error('rclone RC returned invalid JSON', { cause: error })); return; }
          if ((response.statusCode || 500) < 200 || (response.statusCode || 500) >= 300) {
            reject(new Error(body.error || ('rclone RC HTTP ' + response.statusCode)));
            return;
          }
          resolve(body);
        });
      });
      request.setTimeout(timeoutMs, () => request.destroy(new Error('rclone RC request timeout')));
      request.on('error', reject);
      request.end(payload);
    });
  }

  async close() {
    if (this.child && this.child.exitCode === null) {
      this.child.kill('SIGTERM');
      await Promise.race([
        new Promise((resolve) => this.child.once('exit', resolve)),
        delay(2000),
      ]).catch(() => {});
    }
    this.child = null;
    await fs.rm(this.config.rcloneRcSocket, { force: true }).catch(() => {});
  }
}
