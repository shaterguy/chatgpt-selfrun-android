import fs from 'node:fs/promises';
import path from 'node:path';

const EMPTY_STATE = Object.freeze({
  generation: 0,
  status: 'IDLE',
  activeSignal: null,
  activeTargetId: null,
  conversationUrl: null,
  acceptedAt: null,
  lastActivityAt: null,
  lastSignalId: null,
  lastError: null,
});

export class StateStore {
  constructor(config) {
    this.config = config;
    this.state = { ...EMPTY_STATE };
    this.writeQueue = Promise.resolve();
  }

  async init() {
    await fs.mkdir(this.config.dataDir, { recursive: true });
    try {
      this.state = { ...EMPTY_STATE, ...JSON.parse(await fs.readFile(this.config.stateFile, 'utf8')) };
    } catch (error) {
      if (error?.code !== 'ENOENT') throw error;
      await this.persist();
    }
    return this.snapshot();
  }

  snapshot() {
    return structuredClone(this.state);
  }

  async replace(next, event = null) {
    return this.#enqueue(async () => {
      this.state = { ...EMPTY_STATE, ...next };
      await this.persist();
      if (event) await this.appendEvent(event, this.state);
      return this.snapshot();
    });
  }

  async patch(changes, event = null) {
    return this.#enqueue(async () => {
      this.state = { ...this.state, ...changes };
      await this.persist();
      if (event) await this.appendEvent(event, this.state);
      return this.snapshot();
    });
  }

  async patchIfCurrent(generation, signalId, changes, event = null) {
    return this.#enqueue(async () => {
      if (this.state.generation !== generation || this.state.lastSignalId !== signalId) {
        return { applied: false, state: this.snapshot() };
      }
      this.state = { ...this.state, ...changes };
      await this.persist();
      if (event) await this.appendEvent(event, this.state);
      return { applied: true, state: this.snapshot() };
    });
  }

  async recordEvent(event, details = {}) {
    return this.#enqueue(async () => {
      await this.appendEvent(event, this.state, details);
      return this.snapshot();
    });
  }

  #enqueue(operation) {
    const queued = this.writeQueue.then(operation, operation);
    this.writeQueue = queued.catch(() => {});
    return queued;
  }

  async persist() {
    const temp = this.config.stateFile + '.tmp-' + process.pid + '-' + Date.now();
    await fs.writeFile(temp, JSON.stringify(this.state, null, 2) + '\n', { mode: 0o600 });
    await fs.rename(temp, this.config.stateFile);
  }

  async appendEvent(event, state = this.state, details = null) {
    const row = JSON.stringify({
      at: new Date().toISOString(),
      event,
      generation: state.generation,
      status: state.status,
      signalId: state.activeSignal?.signalId ?? null,
      turnId: state.activeSignal?.envelope?.TURN_ID ?? null,
      conversationUrl: state.conversationUrl ?? null,
      ...(details && typeof details === 'object' ? { details } : {}),
    });
    await fs.appendFile(this.config.eventsFile, row + '\n', { mode: 0o600 });
  }
}

export async function ensureToken(config) {
  try {
    return (await fs.readFile(config.tokenFile, 'utf8')).trim();
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
  }

  const crypto = await import('node:crypto');
  const token = crypto.randomBytes(32).toString('base64url');
  await fs.mkdir(path.dirname(config.tokenFile), { recursive: true });
  await fs.writeFile(config.tokenFile, token + '\n', { mode: 0o600 });
  return token;
}
