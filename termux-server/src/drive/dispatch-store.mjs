import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

const execFileAsync = promisify(execFile);
const SCHEMA = 'selfrun-drive-dispatch-v1';
const SUFFIX = '.selfrun-dispatch.json';
const MAX_JSON_BYTES = 2 * 1024 * 1024;

function joinRemote(remote, root, relative = '') {
  const prefix = String(remote || '').endsWith(':') ? String(remote) : String(remote) + ':';
  const cleanRoot = String(root || '').replace(/^\/+|\/+$/g, '');
  const cleanRelative = String(relative || '').replace(/^\/+/, '');
  return prefix + cleanRoot + (cleanRelative ? '/' + cleanRelative : '');
}

function safeRelative(value) {
  const text = String(value || '').replace(/\\/g, '/').replace(/^\/+/, '');
  if (!text || text.includes('..') || text.startsWith('/')) throw new Error('unsafe Drive relative path');
  return text;
}

export class DispatchStore {
  constructor(config, runner = execFileAsync) {
    this.config = config;
    this.runner = runner;
    this.root = joinRemote(config.driveRemote, config.driveRunsRoot);
  }

  async #rclone(args) {
    const result = await this.runner(this.config.rcloneCommand, args, {
      maxBuffer: 12 * 1024 * 1024,
      timeout: 120000,
      env: process.env,
    });
    return String(result.stdout || '');
  }

  async list() {
    const raw = await this.#rclone([
      'lsjson', this.root, '--recursive', '--files-only',
      '--include', '*' + SUFFIX,
    ]);
    const values = raw.trim() ? JSON.parse(raw) : [];
    if (!Array.isArray(values)) throw new Error('rclone lsjson response is not an array');
    return values
      .filter((item) => item && !item.IsDir && String(item.Path || '').endsWith(SUFFIX))
      .map((item) => ({
        path: safeRelative(item.Path),
        size: Number(item.Size || 0),
        modTime: String(item.ModTime || ''),
      }));
  }

  async read(relative) {
    const safe = safeRelative(relative);
    const raw = await this.#rclone(['cat', joinRemote(this.config.driveRemote, this.config.driveRunsRoot, safe)]);
    if (!raw.trim()) return null;
    if (Buffer.byteLength(raw, 'utf8') > MAX_JSON_BYTES) throw new Error('dispatch JSON too large');
    const value = JSON.parse(raw);
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('dispatch JSON must be an object');
    if (value.schema !== SCHEMA) return null;
    return value;
  }

  async write(relative, value) {
    const safe = safeRelative(relative);
    if (!value || typeof value !== 'object' || Array.isArray(value) || value.schema !== SCHEMA) {
      throw new Error('valid dispatch JSON required');
    }
    const encoded = JSON.stringify(value);
    if (Buffer.byteLength(encoded, 'utf8') > MAX_JSON_BYTES) throw new Error('dispatch JSON too large');
    await fs.mkdir(this.config.dataDir, { recursive: true });
    const local = path.join(this.config.dataDir, 'dispatch-' + crypto.randomUUID() + '.json');
    try {
      await fs.writeFile(local, encoded + '\n', { mode: 0o600 });
      await this.#rclone([
        'copyto', local,
        joinRemote(this.config.driveRemote, this.config.driveRunsRoot, safe),
        '--no-traverse',
      ]);
    } finally {
      await fs.rm(local, { force: true });
    }
  }

  async mutate(relative, expected, mutate) {
    const current = await this.read(relative);
    if (!current) return { applied: false, reason: 'missing' };
    if (expected?.requestId && current.request_id !== expected.requestId) {
      return { applied: false, reason: 'request_changed', current };
    }
    if (expected?.attempt != null && Number(current.attempt) !== Number(expected.attempt)) {
      return { applied: false, reason: 'attempt_changed', current };
    }
    if (expected?.states && !expected.states.includes(String(current.state || ''))) {
      return { applied: false, reason: 'state_changed', current };
    }
    const next = mutate(structuredClone(current));
    if (!next) return { applied: false, reason: 'mutator_skipped', current };
    next.schema = SCHEMA;
    next.updated_at_ms = Date.now();
    await this.write(relative, next);
    const readback = await this.read(relative);
    if (!readback || readback.request_id !== next.request_id
        || Number(readback.attempt) !== Number(next.attempt)
        || readback.state !== next.state) {
      throw new Error('dispatch Drive write readback mismatch');
    }
    return { applied: true, current: readback };
  }
}

export const DispatchSchema = Object.freeze({
  SCHEMA,
  SUFFIX,
  states: Object.freeze({
    PREPARE_REQUESTED: 'PREPARE_REQUESTED',
    READY_TO_SUBMIT: 'READY_TO_SUBMIT',
    SEND_REQUESTED: 'SEND_REQUESTED',
    SUBMITTED: 'SUBMITTED',
    STARTED: 'STARTED',
    RESULT_COMMITTED: 'RESULT_COMMITTED',
    ERROR: 'ERROR',
  }),
});
