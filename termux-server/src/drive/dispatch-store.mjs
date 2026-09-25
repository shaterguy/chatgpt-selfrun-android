import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

const SCHEMA = 'selfrun-drive-dispatch-v1';
const SUFFIX = '.selfrun-dispatch.json';
const MAX_JSON_BYTES = 2 * 1024 * 1024;

function safeRelative(value) {
  const text = String(value || '').replace(/\\/g, '/').replace(/^\/+/, '');
  if (!text || text.includes('..') || text.startsWith('/')) throw new Error('unsafe Drive relative path');
  return text;
}

function remotePath(root, relative) {
  const cleanRoot = String(root || '').replace(/^\/+|\/+$/g, '');
  const cleanRelative = safeRelative(relative);
  return cleanRoot ? cleanRoot + '/' + cleanRelative : cleanRelative;
}

export class DispatchStore {
  constructor(config, rc) {
    this.config = config;
    this.rc = rc;
    if (!rc) throw new Error('rclone RC client required');
  }

  async list() {
    const response = await this.rc.call('operations/list', {
      fs: this.config.driveRemote,
      remote: this.config.driveRunsRoot,
      opt: {
        recurse: true,
        filesOnly: true,
        noMimeType: true,
        showHash: false,
      },
    });
    const values = Array.isArray(response.list) ? response.list : [];
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
    await fs.mkdir(this.config.dataDir, { recursive: true });
    const localName = 'dispatch-read-' + crypto.randomUUID() + '.json';
    const localPath = path.join(this.config.dataDir, localName);
    try {
      await this.rc.call('operations/copyfile', {
        srcFs: this.config.driveRemote,
        srcRemote: remotePath(this.config.driveRunsRoot, safe),
        dstFs: this.config.dataDir,
        dstRemote: localName,
      });
      const raw = await fs.readFile(localPath, 'utf8');
      if (!raw.trim()) return null;
      if (Buffer.byteLength(raw, 'utf8') > MAX_JSON_BYTES) throw new Error('dispatch JSON too large');
      const value = JSON.parse(raw);
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('dispatch JSON must be an object');
      if (value.schema !== SCHEMA) return null;
      return value;
    } finally {
      await fs.rm(localPath, { force: true }).catch(() => {});
    }
  }

  async write(relative, value) {
    const safe = safeRelative(relative);
    if (!value || typeof value !== 'object' || Array.isArray(value) || value.schema !== SCHEMA) {
      throw new Error('valid dispatch JSON required');
    }
    const encoded = JSON.stringify(value);
    if (Buffer.byteLength(encoded, 'utf8') > MAX_JSON_BYTES) throw new Error('dispatch JSON too large');
    await fs.mkdir(this.config.dataDir, { recursive: true });
    const localName = 'dispatch-write-' + crypto.randomUUID() + '.json';
    const localPath = path.join(this.config.dataDir, localName);
    try {
      await fs.writeFile(localPath, encoded + '\n', { mode: 0o600 });
      await this.rc.call('operations/copyfile', {
        srcFs: this.config.dataDir,
        srcRemote: localName,
        dstFs: this.config.driveRemote,
        dstRemote: remotePath(this.config.driveRunsRoot, safe),
      });
    } finally {
      await fs.rm(localPath, { force: true }).catch(() => {});
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

    // Re-read immediately before the write. This is not a server-side transaction, but it prevents
    // an already-observed newer Android attempt from being overwritten by a stale browser callback.
    const preflight = await this.read(relative);
    if (!preflight
        || preflight.request_id !== current.request_id
        || Number(preflight.attempt) !== Number(current.attempt)
        || preflight.state !== current.state) {
      return { applied: false, reason: 'preflight_changed', current: preflight };
    }

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
