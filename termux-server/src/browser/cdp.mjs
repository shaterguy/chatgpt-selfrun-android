import { spawn } from 'node:child_process';
import fs from 'node:fs';
import fsPromises from 'node:fs/promises';

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export class CdpSession {
  constructor(wsUrl, onActivity = null) {
    this.wsUrl = wsUrl;
    this.onActivity = typeof onActivity === 'function' ? onActivity : null;
    this.socket = null;
    this.seq = 0;
    this.pending = new Map();
    this.listeners = new Map();
  }

  touch() {
    try { this.onActivity?.(); } catch {}
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
    this.touch();
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

  call(method, params = {}, options = {}) {
    this.touch();
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('CDP websocket is not open'));
    }
    const id = ++this.seq;
    const payload = JSON.stringify({ id, method, params });
    const timeoutMs = Math.max(250, Number(options.timeoutMs || 20000));
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`CDP command timeout: ${method}`));
      }, timeoutMs);
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
    this.recycling = null;
    this.baseUrl = `http://127.0.0.1:${config.browserPort}`;
    this.activityDir = config.browserActivityDir;
    this.activityWrites = new Map();
    this.idleSweepRunning = false;
    try { fs.mkdirSync(this.activityDir, { recursive: true }); } catch {}
    const sweepMs = Math.max(10000, Number(config.browserTargetSweepMs || 60000));
    this.idleSweepTimer = setInterval(() => {
      if (this.idleSweepRunning) return;
      this.idleSweepRunning = true;
      void this.closeIdleTargets().catch(() => {}).finally(() => {
        this.idleSweepRunning = false;
      });
    }, sweepMs);
    this.idleSweepTimer.unref?.();
  }

  #activityPath(targetId) {
    const safe = String(targetId || '').replace(/[^A-Za-z0-9._-]/g, '_');
    return safe ? `${this.activityDir}/${safe}` : null;
  }

  touchTarget(targetId, force = false) {
    const id = String(targetId || '');
    const file = this.#activityPath(id);
    if (!file) return;
    const now = Date.now();
    const previous = Number(this.activityWrites.get(id) || 0);
    if (!force && now - previous < 30000) return;
    try {
      fs.mkdirSync(this.activityDir, { recursive: true });
      const fd = fs.openSync(file, 'a');
      fs.closeSync(fd);
      const date = new Date(now);
      fs.utimesSync(file, date, date);
      this.activityWrites.set(id, now);
    } catch {}
  }

  forgetTarget(targetId) {
    const id = String(targetId || '');
    const file = this.#activityPath(id);
    this.activityWrites.delete(id);
    if (!file) return;
    try { fs.unlinkSync(file); } catch {}
  }

  isTargetIdle(targetId, idleMs = this.config.browserTargetIdleMs) {
    const id = String(targetId || '');
    const file = this.#activityPath(id);
    const limit = Math.max(0, Number(idleMs || 0));
    if (!file || !limit) return false;
    try {
      return Date.now() - fs.statSync(file).mtimeMs >= limit;
    } catch {
      this.touchTarget(id, true);
      return false;
    }
  }

  async hasRecentlyActiveTargets(windowMs = this.config.browserTargetIdleMs) {
    const limit = Math.max(0, Number(windowMs || 0));
    if (!limit) return false;
    const targets = await this.listExistingTargets();
    if (!Array.isArray(targets)) return false;
    for (const target of targets) {
      if (String(target?.type || '') !== 'page' || !target?.id) continue;
      if (!this.isTargetIdle(target.id, limit)) return true;
    }
    return false;
  }

  async closeIdleTargets() {
    const idleMs = Math.max(0, Number(this.config.browserTargetIdleMs || 0));
    if (!idleMs) return { closed: [] };
    const targets = await this.listExistingTargets();
    if (!Array.isArray(targets)) return { closed: [] };
    const closed = [];
    for (const target of targets) {
      if (String(target?.type || '') !== 'page' || !target?.id) continue;
      if (!this.isTargetIdle(target.id, idleMs)) continue;
      if (!this.isTargetIdle(target.id, idleMs)) continue;
      if (await this.closeTarget(target.id)) closed.push(String(target.id));
    }
    if (closed.length) {
      const remaining = await this.listExistingTargets();
      const pages = Array.isArray(remaining)
        ? remaining.filter((target) => String(target?.type || '') === 'page')
        : [];
      if (pages.length === 0) await this.recycle('idle_targets_exhausted').catch(() => {});
    }
    return { closed };
  }

  #processRows() {
    const rows = [];
    let entries = [];
    try { entries = fs.readdirSync('/proc'); } catch { return rows; }
    for (const entry of entries) {
      if (!/^\d+$/.test(entry)) continue;
      const pid = Number(entry);
      try {
        const cmdline = fs.readFileSync(`/proc/${pid}/cmdline`)
          .toString()
          .replaceAll('\0', ' ')
          .trim();
        const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8').trim().split(/\s+/);
        const status = fs.readFileSync(`/proc/${pid}/status`, 'utf8');
        const rssKb = Number(status.match(/^VmRSS:\s*(\d+)/m)?.[1] || 0);
        rows.push({ pid, ppid: Number(stat[3] || 0), rssKb, cmdline });
      } catch {}
    }
    return rows;
  }

  #rootPid(rows = this.#processRows()) {
    if (this.child?.pid && rows.some((row) => row.pid === this.child.pid)) return this.child.pid;
    const portMarker = `--remote-debugging-port=${this.config.browserPort}`;
    return rows.find((row) => row.cmdline.includes('/lib/chromium/chrome')
      && row.cmdline.includes(portMarker)
      && !row.cmdline.includes('--type='))?.pid || null;
  }

  #treeRows(rows, rootPid) {
    if (!rootPid) return [];
    const ids = new Set([rootPid]);
    let changed = true;
    while (changed) {
      changed = false;
      for (const row of rows) {
        if (ids.has(row.ppid) && !ids.has(row.pid)) {
          ids.add(row.pid);
          changed = true;
        }
      }
    }
    return rows.filter((row) => ids.has(row.pid));
  }

  async listExistingTargets() {
    try {
      const response = await fetch(this.baseUrl + '/json/list', {
        signal: AbortSignal.timeout(1500),
      });
      if (!response.ok) return null;
      return response.json();
    } catch {
      return null;
    }
  }

  async residentSetMb() {
    const rows = this.#processRows();
    const rootPid = this.#rootPid(rows);
    if (!rootPid) return null;
    const rssKb = this.#treeRows(rows, rootPid).reduce((sum, row) => sum + row.rssKb, 0);
    return Math.round((rssKb / 1024) * 10) / 10;
  }

  async recycle(reason = 'manual') {
    if (this.recycling) return this.recycling;
    this.recycling = (async () => {
      const rows = this.#processRows();
      const rootPid = this.#rootPid(rows);
      const rssMb = rootPid
        ? Math.round((this.#treeRows(rows, rootPid).reduce((sum, row) => sum + row.rssKb, 0) / 1024) * 10) / 10
        : null;
      if (!rootPid) {
        this.child = null;
        return { recycled: false, reason, rssMb, rootPid: null };
      }

      try { process.kill(rootPid, 'SIGTERM'); } catch {}
      for (let attempt = 0; attempt < 30; attempt += 1) {
        if (!fs.existsSync(`/proc/${rootPid}`)) break;
        await delay(100);
      }
      if (fs.existsSync(`/proc/${rootPid}`)) {
        try { process.kill(rootPid, 'SIGKILL'); } catch {}
      }

      const profileMarker = `${this.config.browserProfile}/Crash Reports`;
      for (const row of this.#processRows()) {
        if (!row.cmdline.includes('chrome_crashpad_handler')
            || !row.cmdline.includes(profileMarker)) continue;
        try { process.kill(row.pid, 'SIGTERM'); } catch {}
      }

      this.child = null;
      return { recycled: true, reason, rssMb, rootPid };
    })();

    try {
      return await this.recycling;
    } finally {
      this.recycling = null;
    }
  }

  async #version() {
    const response = await fetch(this.baseUrl + '/json/version', { signal: AbortSignal.timeout(1500) });
    if (!response.ok) throw new Error(`Chromium probe failed: ${response.status}`);
    return response.json();
  }

  async ensureStarted() {
    if (this.recycling) await this.recycling;
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
    const target = await response.json();
    this.touchTarget(target?.id, true);
    return target;
  }

  async closeTarget(targetId) {
    if (!targetId) return false;
    try {
      const response = await fetch(this.baseUrl + '/json/close/' + encodeURIComponent(targetId));
      if (response.ok) this.forgetTarget(targetId);
      return response.ok;
    } catch {
      return false;
    }
  }

  async connectTarget(target) {
    if (!target?.webSocketDebuggerUrl) throw new Error('Target has no DevTools websocket URL');
    this.touchTarget(target.id, true);
    return new CdpSession(target.webSocketDebuggerUrl, () => this.touchTarget(target.id)).connect();
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
