import { spawn } from 'node:child_process';
import fs from 'node:fs';
import fsPromises from 'node:fs/promises';

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export class CdpSession {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.socket = null;
    this.seq = 0;
    this.pending = new Map();
    this.listeners = new Map();
  }

  async connect() {
    const socket = new WebSocket(this.wsUrl);
    this.socket = socket;
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('CDP websocket connect timeout')), 10000);
      socket.addEventListener('open', () => {
        clearTimeout(timer);
        resolve();
      }, { once: true });
      socket.addEventListener('error', (event) => {
        clearTimeout(timer);
        reject(new Error('CDP websocket connection failed', { cause: event }));
      }, { once: true });
    });
    socket.addEventListener('message', (event) => this.#onMessage(event.data));
    socket.addEventListener('close', () => {
      for (const { reject } of this.pending.values()) reject(new Error('CDP websocket closed'));
      this.pending.clear();
    });
    return this;
  }

  #onMessage(raw) {
    const message = JSON.parse(raw);
    if (message.id) {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(message.error.message || 'CDP command failed'));
      else pending.resolve(message.result || {});
      return;
    }
    const handlers = this.listeners.get(message.method);
    if (handlers) for (const handler of handlers) handler(message.params || {});
  }

  on(method, handler) {
    if (!this.listeners.has(method)) this.listeners.set(method, new Set());
    this.listeners.get(method).add(handler);
    return () => this.listeners.get(method)?.delete(handler);
  }

  call(method, params = {}) {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('CDP websocket is not open'));
    }
    const id = ++this.seq;
    const payload = JSON.stringify({ id, method, params });
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`CDP command timeout: ${method}`));
      }, 20000);
      this.pending.set(id, {
        resolve: (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        reject: (error) => {
          clearTimeout(timer);
          reject(error);
        },
      });
      this.socket.send(payload);
    });
  }

  close() {
    if (this.socket && this.socket.readyState <= WebSocket.OPEN) this.socket.close();
  }
}

export class ChromiumManager {
  constructor(config) {
    this.config = config;
    this.child = null;
    this.baseUrl = `http://127.0.0.1:${config.browserPort}`;
  }

  async #version() {
    const response = await fetch(this.baseUrl + '/json/version', { signal: AbortSignal.timeout(1500) });
    if (!response.ok) throw new Error(`Chromium probe failed: ${response.status}`);
    return response.json();
  }

  async ensureStarted() {
    try {
      return await this.#version();
    } catch {}

    await fsPromises.mkdir(this.config.dataDir, { recursive: true });
    const out = fs.openSync(this.config.browserLogFile, 'a');
    const args = [
      '--headless=new',
      '--no-sandbox',
      '--disable-gpu',
      '--disable-dev-shm-usage',
      '--no-first-run',
      '--no-default-browser-check',
      '--remote-allow-origins=*',
      '--remote-debugging-address=127.0.0.1',
      `--remote-debugging-port=${this.config.browserPort}`,
      `--user-data-dir=${this.config.browserProfile}`,
      'about:blank',
    ];
    this.child = spawn(this.config.chromiumCommand, args, {
      detached: false,
      stdio: ['ignore', out, out],
      env: process.env,
    });
    let lastError = null;
    for (let attempt = 0; attempt < 80; attempt += 1) {
      if (this.child.exitCode !== null) {
        throw new Error(`Chromium exited before DevTools became ready: ${this.child.exitCode}`);
      }
      try {
        return await this.#version();
      } catch (error) {
        lastError = error;
        await delay(250);
      }
    }
    throw new Error('Chromium DevTools did not become ready', { cause: lastError });
  }

  async listTargets() {
    await this.ensureStarted();
    const response = await fetch(this.baseUrl + '/json/list');
    if (!response.ok) throw new Error(`Unable to list Chromium targets: ${response.status}`);
    return response.json();
  }

  async createTarget(url = 'about:blank') {
    await this.ensureStarted();
    const endpoint = this.baseUrl + '/json/new?' + encodeURIComponent(url);
    const response = await fetch(endpoint, { method: 'PUT' });
    if (!response.ok) throw new Error(`Unable to create Chromium target: ${response.status}`);
    return response.json();
  }

  async closeTarget(targetId) {
    if (!targetId) return false;
    try {
      const response = await fetch(this.baseUrl + '/json/close/' + encodeURIComponent(targetId));
      return response.ok;
    } catch {
      return false;
    }
  }

  async connectTarget(target) {
    if (!target?.webSocketDebuggerUrl) throw new Error('Target has no DevTools websocket URL');
    return new CdpSession(target.webSocketDebuggerUrl).connect();
  }

  async probe() {
    const version = await this.ensureStarted();
    const targets = await this.listTargets();
    return {
      ready: true,
      browser: version.Browser || null,
      protocolVersion: version['Protocol-Version'] || null,
      targets: targets.length,
    };
  }
}
