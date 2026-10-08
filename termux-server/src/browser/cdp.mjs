import { spawn } from 'node:child_process';
import fs from 'node:fs';
import fsPromises from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

// Explicit operator-approved exception; never general target creation proof.
const APPROVED_IDLE_HOME_URL = 'https://chatgpt.com/';

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const QUERY_DIAGNOSTIC_TTL_MS = 60000;
const QUERY_DIAGNOSTIC_LIMIT = 32;
const LOOP_SAMPLE_MS = 1000;
const DIAGNOSTIC_WINDOW_MS = 60000;
const DIAGNOSTIC_WINDOW_LIMIT = 64;
const diagnosticMethod=method=>/^[A-Za-z]{1,32}\.[A-Za-z]{1,64}$/.test(method)?method:'unknown';

export class CdpSession {
  constructor(wsUrl, onActivity = null) {
    this.wsUrl = wsUrl;
    this.onActivity = typeof onActivity === 'function' ? onActivity : null;
    this.socket = null;
    this.seq = 0;
    this.pending = new Map();
    this.listeners = new Map();
    this.diagnosticId = randomUUID();
    this.timedOutQueries = new Map();
    this.lastInboundAt = null;
    this.inboundFrames = 0;
    this.inboundReplies = 0;
    this.loopTimer = null;
    this.diagnosticWindow = { startedAt: performance.now(), count: 0 };
  }

  #diagnostic(event, details) {
    // Metadata only: never include websocket URLs, request params or reply bodies.
    const now=performance.now();
    if(now-this.diagnosticWindow.startedAt>=DIAGNOSTIC_WINDOW_MS)this.diagnosticWindow={startedAt:now,count:0};
    if(this.diagnosticWindow.count>=DIAGNOSTIC_WINDOW_LIMIT)return;
    this.diagnosticWindow.count+=1;
    try { console.warn(JSON.stringify({ at: new Date().toISOString(), event,
      session_id: this.diagnosticId, method: 'Runtime.evaluate', ...details })); } catch {}
  }

  #forgetTimedOutQuery(id) {
    const retained = this.timedOutQueries.get(id);
    if (retained) clearTimeout(retained.timer);
    this.timedOutQueries.delete(id);
  }

  #clearQueryDiagnostics() {
    for (const id of this.timedOutQueries.keys()) this.#forgetTimedOutQuery(id);
  }

  #sampleLoop() {
    if(this.loopTimer||!this.pending.size)return;
    const expectedAt=performance.now()+LOOP_SAMPLE_MS;
    this.loopTimer=setTimeout(()=>{
      this.loopTimer=null;
      const lag=Math.max(0,Math.round(performance.now()-expectedAt));
      for(const pending of this.pending.values()) {
        pending.timing.node_event_loop_max_delay_ms=Math.max(pending.timing.node_event_loop_max_delay_ms,lag);
        pending.timing.node_event_loop_samples+=1;
      }
      this.#sampleLoop();
    },LOOP_SAMPLE_MS);
    this.loopTimer.unref?.();
  }

  #stopIdleLoop() {
    if(this.pending.size)return;
    clearTimeout(this.loopTimer);this.loopTimer=null;
  }

  #queryTimeout(id, method, startedAt, timeoutMs, framesAtStart, repliesAtStart, timing) {
    const now = performance.now();
    const details = { command_id: id, method:diagnosticMethod(method), timeout_ms: timeoutMs,
      elapsed_ms: Math.max(0, Math.round(now - startedAt)),
      timeout_overrun_ms:Math.max(0,Math.round(now-startedAt-timeoutMs)),...timing,
      socket_state: this.socket?.readyState ?? -1, pending_count: this.pending.size,
      last_inbound_age_ms: this.lastInboundAt === null ? null : Math.max(0, Math.round(now - this.lastInboundAt)),
      inbound_frames_since_send: this.inboundFrames - framesAtStart,
      inbound_replies_since_send: this.inboundReplies - repliesAtStart };
    if (this.socket?.readyState === WebSocket.OPEN) {
      while (this.timedOutQueries.size >= QUERY_DIAGNOSTIC_LIMIT)
        this.#forgetTimedOutQuery(this.timedOutQueries.keys().next().value);
      const timer = setTimeout(() => this.#forgetTimedOutQuery(id), QUERY_DIAGNOSTIC_TTL_MS);
      timer.unref?.();
      this.timedOutQueries.set(id, { method:diagnosticMethod(method), startedAt, timedOutAt: now, timer });
    }
    this.#diagnostic(method==='Runtime.evaluate'?'CDP_QUERY_TIMEOUT':'CDP_COMMAND_TIMEOUT', details);
    return details;
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
      this.#clearQueryDiagnostics();
      for (const { reject } of this.pending.values()) reject(new Error('CDP websocket closed'));
      this.pending.clear();
      this.#stopIdleLoop();
    });
    return this;
  }

  #onMessage(raw) {
    this.lastInboundAt = performance.now();
    this.inboundFrames += 1;
    const message = JSON.parse(raw);
    if (message.id) {
      this.inboundReplies += 1;
      const pending = this.pending.get(message.id);
      if (!pending) {
        const retained = this.timedOutQueries.get(message.id);
        if (retained) {
          this.#forgetTimedOutQuery(message.id);
          if (this.lastInboundAt - retained.timedOutAt >= QUERY_DIAGNOSTIC_TTL_MS) return;
          this.#diagnostic(retained.method==='Runtime.evaluate'?'CDP_QUERY_LATE_REPLY':'CDP_COMMAND_LATE_REPLY', { command_id: message.id,method:retained.method,
            elapsed_ms: Math.max(0, Math.round(this.lastInboundAt - retained.startedAt)),
            after_timeout_ms: Math.max(0, Math.round(this.lastInboundAt - retained.timedOutAt)),
            protocol_error: Boolean(message.error) });
        }
        return;
      }
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
    if (options.signal?.aborted) return Promise.reject(options.signal.reason || new Error('aborted'));
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('CDP websocket is not open'));
    }
    const id = ++this.seq;
    const payload = JSON.stringify({ id, method, params });
    const timeoutMs = Math.max(250, Number(options.timeoutMs || 20000));
    const startedAt = performance.now(), framesAtStart = this.inboundFrames, repliesAtStart = this.inboundReplies;
    const timing={node_event_loop_max_delay_ms:0,node_event_loop_samples:0};
    return new Promise((resolve, reject) => {
      // Only explicitly labelled read-only observations get a soft deadline.
      // Effectful commands retain their existing hard timeout and are never retried here.
      const observation = method === 'Runtime.evaluate' && options.queryLabel === 'progress';
      const label = observation ? 'progress' : null;
      const softTimer = observation ? setTimeout(() => {
        this.#diagnostic('CDP_OBSERVATION_SLOW', { command_id: id, query_label: label,
          elapsed_ms: Math.max(0, Math.round(performance.now() - startedAt)),
          ...timing,
          socket_state: this.socket?.readyState ?? -1, pending_count: this.pending.size });
      }, 5000) : null;
      const cleanup = () => {
        clearTimeout(timer); clearTimeout(softTimer);
        options.signal?.removeEventListener('abort', onAbort);
        this.#stopIdleLoop();
      };
      const onAbort = () => {
        this.pending.delete(id); cleanup();
        reject(options.signal.reason || new Error('aborted'));
      };
      const timer = setTimeout(() => {
        this.pending.delete(id);
        cleanup();
        const error = new Error(`CDP command timeout: ${method}`);
        error.cdpDiagnostics = this.#queryTimeout(id, method, startedAt, timeoutMs, framesAtStart, repliesAtStart, timing);
        reject(error);
      }, timeoutMs);
      this.pending.set(id, {
        timing,
        resolve: (value) => {
          cleanup();
          if (observation) {
            const elapsed = Math.max(0, Math.round(performance.now() - startedAt));
            if (performance.now() - startedAt >= timeoutMs) {
              const error=new Error(`CDP command timeout: ${method}`);
              error.cdpDiagnostics=this.#queryTimeout(id,method,startedAt,timeoutMs,framesAtStart,repliesAtStart,timing);
              this.#forgetTimedOutQuery(id); // This reply already arrived; retain no duplicate wait.
              reject(error);
              return;
            }
            const engine = value?.result?.value?.__selfrun_engine_elapsed_ms;
            if (elapsed >= 5000) this.#diagnostic('CDP_OBSERVATION_REPLY', {
              command_id: id, query_label: label, elapsed_ms: elapsed,
              ...timing,
              engine_elapsed_ms: Number.isFinite(engine) && engine >= 0 ? engine : null,
              outside_engine_ms: Number.isFinite(engine) && engine >= 0 ? Math.max(0, elapsed - engine) : null,
            });
          }
          resolve(value);
        },
        reject: (error) => {
          cleanup();
          reject(error);
        },
      });
      this.#sampleLoop();
      options.signal?.addEventListener('abort', onAbort, { once: true });
      try { this.socket.send(payload); }
      catch (error) { this.pending.delete(id); cleanup(); reject(error); }
    });
  }

  close() {
    this.#clearQueryDiagnostics();
    if (this.socket && this.socket.readyState <= WebSocket.OPEN) this.socket.close();
  }
}

export class ChromiumManager {
  constructor(config) {
    this.config = config;
    this.child = null;
    this.recycling = null;
    this.lifecycleQueue = Promise.resolve();
    this.closingTargets = new Set();
    this.lifecycleGuards = {};
    this.baseUrl = `http://127.0.0.1:${config.browserPort}`;
    this.activityDir = config.browserActivityDir;
    this.activityWrites = new Map();
    this.targetUseEpoch = 0;
    this.ownershipFile = path.join(config.dataDir, `chromium-ownership-${config.browserPort}.json`);
    this.ownership = this.#readOwnership();
    this.idleSweepRunning = false;
    try { fs.mkdirSync(this.activityDir, { recursive: true }); } catch {}
    const sweepMs = Math.max(10000, Number(config.browserTargetSweepMs || 60000));
    this.idleSweepTimer = setInterval(() => {
      if (this.idleSweepRunning) return;
      this.idleSweepRunning = true;
      void this.closeIdleTargets().catch(() => {}).finally(() => { this.idleSweepRunning = false; });
    }, sweepMs);
    this.idleSweepTimer.unref?.();
  }

  #emptyOwnership() {
    return {
      schema: 'selfrun-chromium-ownership-v1', port: Number(this.config.browserPort),
      profile: this.config.browserProfile, browserKey: null, process: null, targets: {},
    };
  }

  #readOwnership() {
    try {
      const saved = JSON.parse(fs.readFileSync(this.ownershipFile, 'utf8'));
      if (saved.schema === 'selfrun-chromium-ownership-v1'
          && saved.port === Number(this.config.browserPort) && saved.profile === this.config.browserProfile
          && saved.targets && typeof saved.targets === 'object' && !Array.isArray(saved.targets)) return saved;
    } catch {}
    return this.#emptyOwnership();
  }

  #saveOwnership() {
    fs.mkdirSync(this.config.dataDir, { recursive: true });
    const temporary = `${this.ownershipFile}.${process.pid}.${randomUUID()}.tmp`;
    try {
      fs.writeFileSync(temporary, JSON.stringify(this.ownership), { mode: 0o600 });
      fs.renameSync(temporary, this.ownershipFile);
    } finally { try { fs.unlinkSync(temporary); } catch {} }
  }

  #serialize(operation) {
    const result = this.lifecycleQueue.then(operation, operation);
    this.lifecycleQueue = result.catch(() => {});
    return result;
  }

  // Synchronous guards keep the final ownership/epoch check in the same turn
  // as sending a close request or process signal. Async guards fail closed.
  #allowed(guard, ...args) {
    try { return !guard || guard(...args) === true; } catch { return false; }
  }

  setLifecycleGuards({ canCloseTarget, canRelease } = {}) {
    this.lifecycleGuards = { canCloseTarget, canRelease };
  }

  #useVersion(version) {
    const browserKey = typeof version?.webSocketDebuggerUrl === 'string' ? version.webSocketDebuggerUrl : null;
    if (browserKey !== this.ownership.browserKey) {
      this.ownership = { ...this.#emptyOwnership(), browserKey };
      this.closingTargets.clear();
      this.#saveOwnership();
    }
    return browserKey;
  }

  #recordTarget(target, startupUrl = null) {
    if (!target?.id || !this.ownership.browserKey) return;
    this.touchTarget(target.id, true);
    this.ownership.targets[String(target.id)] = { created: true, browserKey: this.ownership.browserKey, startupUrl };
    this.#saveOwnership();
  }

  isOwnedTarget(targetId) {
    const proof = this.ownership.targets[String(targetId || '')];
    return proof?.created === true && Boolean(this.ownership.browserKey)
      && proof.browserKey === this.ownership.browserKey;
  }

  targetClosing(targetId) { return this.closingTargets.has(String(targetId || '')); }

  #activityPath(targetId) {
    const safe = String(targetId || '').replace(/[^A-Za-z0-9._-]/g, '_');
    return safe ? `${this.activityDir}/${safe}` : null;
  }

  touchTarget(targetId, force = false) {
    const id = String(targetId || '');
    this.targetUseEpoch += 1; // Use is evidence even if the activity-file write fails.
    // Any use consumes the startup exception even if the page stays blank.
    if (this.ownership.targets[id]?.startupUrl) {
      this.ownership.targets[id].startupUrl = null;
      this.#saveOwnership();
    }
    const file = this.#activityPath(id);
    if (!file) return;
    const now = Date.now();
    if (!force && now - Number(this.activityWrites.get(id) || 0) < 30000) return;
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
    if (Object.hasOwn(this.ownership.targets, id)) {
      delete this.ownership.targets[id];
      this.#saveOwnership();
    }
    if (file) { try { fs.unlinkSync(file); } catch {} }
  }

  isTargetIdle(targetId, idleMs = this.config.browserTargetIdleMs) {
    const id = String(targetId || '');
    const file = this.#activityPath(id);
    const limit = Math.max(0, Number(idleMs || 0));
    if (!file || !limit) return false;
    try { return Date.now() - fs.statSync(file).mtimeMs >= limit; }
    catch { this.touchTarget(id, true); return false; }
  }

  async hasRecentlyActiveTargets(windowMs = this.config.browserTargetIdleMs) {
    const limit = Math.max(0, Number(windowMs || 0));
    if (!limit) return false;
    const targets = await this.listExistingTargets();
    if (!Array.isArray(targets)) return false;
    return targets.some((target) => target?.type === 'page' && target.id && !this.isTargetIdle(target.id, limit));
  }

  async closeIdleTargets() {
    const idleMs = Math.max(0, Number(this.config.browserTargetIdleMs || 0));
    if (!idleMs) return { closed: [] };
    const targets = await this.listExistingTargets();
    if (!Array.isArray(targets)) return { closed: [] };
    const closed = [];
    for (const target of targets) {
      if (target?.type !== 'page' || !this.isOwnedTarget(target.id) || !this.isTargetIdle(target.id, idleMs)) continue;
      const result = await this.closeOwnedTarget(target.id, { guard: () => this.isTargetIdle(target.id, idleMs) });
      if (result.closed) closed.push(String(target.id));
    }
    // Home-only idle browsers have no created-target cleanup entry to trigger release.
    await this.releaseIfUnused().catch(() => {});
    return { closed };
  }

  #processIdentity(pid) {
    try {
      const rawStat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8');
      // comm may contain spaces/parentheses; after the last ')' come fields 3+.
      const stat = rawStat.slice(rawStat.lastIndexOf(')') + 2).trim().split(/\s+/);
      const args = fs.readFileSync(`/proc/${pid}/cmdline`).toString().split('\0').filter(Boolean);
      const status = fs.readFileSync(`/proc/${pid}/status`, 'utf8');
      return {
        pid: Number(pid), ppid: Number(stat[1]), state: stat[0], startTime: stat[19], args,
        rssKb: Number(status.match(/^VmRSS:\s*(\d+)/m)?.[1] || 0),
      };
    } catch { return null; }
  }

  #processRows() {
    try { return fs.readdirSync('/proc').filter((entry) => /^\d+$/.test(entry))
      .map((entry) => this.#processIdentity(Number(entry))).filter(Boolean); }
    catch { return []; }
  }

  #bootId() {
    try { return fs.readFileSync('/proc/sys/kernel/random/boot_id', 'utf8').trim(); }
    catch { return null; }
  }

  #matchesProcess(proof, row = this.#processIdentity(proof?.pid)) {
    return Boolean(proof && row && row.state !== 'Z' && row.state !== 'X'
      && String(row.startTime) === String(proof.startTime));
  }

  #processExited(proof) {
    try {
      const raw = fs.readFileSync(`/proc/${proof.pid}/stat`, 'utf8');
      const stat = raw.slice(raw.lastIndexOf(')') + 2).trim().split(/\s+/);
      return (Boolean(stat[19]) && String(stat[19]) !== String(proof.startTime))
        || stat[0] === 'Z' || stat[0] === 'X';
    } catch (error) {
      // An unreadable process is unknown, never evidence that it exited.
      return error?.code === 'ENOENT' || error?.code === 'ESRCH';
    }
  }

  #ownedProcess() {
    const proof = this.ownership.process;
    if (!proof?.bootId || proof.bootId !== this.#bootId()
        || proof.port !== Number(this.config.browserPort) || proof.profile !== this.config.browserProfile
        || proof.browserKey !== this.ownership.browserKey) return null;
    const row = this.#processIdentity(proof.pid);
    if (!this.#matchesProcess(proof, row)
        || !row.args.includes(`--remote-debugging-port=${proof.port}`)
        || !row.args.includes(`--user-data-dir=${proof.profile}`)
        || row.args.some((arg) => arg.startsWith('--type='))) return null;
    return row;
  }

  #treeRows(rows, rootPid) {
    if (!rootPid) return [];
    const ids = new Set([rootPid]);
    let changed = true;
    while (changed) {
      changed = false;
      for (const row of rows) {
        if (ids.has(row.ppid) && !ids.has(row.pid)) { ids.add(row.pid); changed = true; }
      }
    }
    return rows.filter((row) => ids.has(row.pid));
  }

  async listExistingTargets(timeoutMs = 1500) {
    try {
      const response = await fetch(this.baseUrl + '/json/list', { signal: AbortSignal.timeout(timeoutMs) });
      if (!response.ok) return null;
      return await response.json();
    } catch { return null; }
  }

  async residentSetMb() {
    const root = this.#ownedProcess();
    if (!root) return null;
    const rssKb = this.#treeRows(this.#processRows(), root.pid).reduce((sum, row) => sum + row.rssKb, 0);
    return Math.round((rssKb / 1024) * 10) / 10;
  }

  async #version(timeoutMs = 1500) {
    const response = await fetch(this.baseUrl + '/json/version', { signal: AbortSignal.timeout(timeoutMs) });
    if (!response.ok) throw new Error(`Chromium probe failed: ${response.status}`);
    return response.json();
  }

  async #closeOwnedTarget(targetId, { guard, lifecycle = 'target' } = {}) {
    const id = String(targetId || '');
    const allowed = (target) => this.#allowed(guard, target) && (lifecycle === 'release'
      ? this.#allowed(this.lifecycleGuards.canRelease)
      : lifecycle === 'caller' || this.#allowed(this.lifecycleGuards.canCloseTarget, id, target));
    const skip = (reason) => ({ closed: false, absent: false, reason });
    if (!allowed()) return skip('guard_rejected');
    if (!this.isOwnedTarget(id)) return skip('unowned_target');
    const browserKey = this.ownership.browserKey;
    const targets = await this.listExistingTargets();
    if (!allowed()) return skip('guard_rejected');
    if (!Array.isArray(targets)) return skip('targets_unavailable');
    const observed = targets.find((target) => String(target?.id || '') === id);
    if (!allowed(observed)) return skip('guard_rejected');
    let version;
    try { version = await this.#version(); } catch { return skip('browser_unavailable'); }
    if (!allowed()) return skip('guard_rejected');
    if (this.#useVersion(version) !== browserKey || !this.isOwnedTarget(id)) return skip('browser_changed');
    if (!observed) {
      this.forgetTarget(id);
      this.closingTargets.delete(id);
      return { closed: false, absent: true, reason: 'already_absent' };
    }
    if (!allowed(observed)) return skip('guard_rejected');
    this.closingTargets.add(id);
    try {
      const response = await fetch(this.baseUrl + '/json/close/' + encodeURIComponent(id), {
        signal: AbortSignal.timeout(1500),
      });
      if (!response.ok) return skip('close_failed');
      // A successful close response only acknowledges the command. Preserve the
      // existing cleanup retry and ownership proof until absence is observed.
      const remaining = await this.listExistingTargets();
      if (!Array.isArray(remaining)) return skip('close_unverified');
      let observedVersion;
      try { observedVersion = await this.#version(); } catch { return skip('close_unverified'); }
      if (this.#useVersion(observedVersion) !== browserKey || !this.isOwnedTarget(id)) return skip('browser_changed');
      if (remaining.some((target) => String(target?.id || '') === id)) return skip('target_still_present');
      this.forgetTarget(id);
      return { closed: true, absent: false, reason: 'closed' };
    } catch { return skip('close_failed'); }
  }

  closeOwnedTarget(targetId, { guard } = {}) {
    return this.#serialize(() => this.#closeOwnedTarget(targetId, { guard }));
  }

  // Failure cleanup using the legacy API is also ownership constrained.
  async closeTarget(targetId, { guard } = {}) {
    const result = await this.#serialize(() => this.#closeOwnedTarget(targetId, {
      guard, lifecycle: typeof guard === 'function' ? 'caller' : 'target',
    }));
    return result.closed || result.absent;
  }

  async #closeApprovedHomeTarget(target, { guard, processProof, useEpoch }) {
    const id = String(target?.id || '');
    const skip = reason => ({ closed: false, absent: false, reason });
    const allowed = () => typeof this.lifecycleGuards.canRelease === 'function'
      && this.#allowed(guard) && this.#allowed(this.lifecycleGuards.canRelease)
      && this.targetUseEpoch === useEpoch && this.ownership.process === processProof
      && Boolean(this.#ownedProcess());
    if (!id || target.type !== 'page' || target.url !== APPROVED_IDLE_HOME_URL || !allowed()) {
      return skip('home_guard_rejected');
    }
    // The browser-instance websocket binds the final read and close to this
    // generation even if the debugging port is replaced. No page is attached,
    // navigated, or recorded as created by this manager.
    const session = new CdpSession(processProof.browserKey);
    try {
      await session.connect();
      if (!allowed()) return skip('home_guard_rejected');
      const contexts = await session.call('Target.getBrowserContexts', {}, { timeoutMs: 1500 });
      if (!allowed()) return skip('home_guard_rejected');
      // A context omitted by getBrowserContexts is not positive default-context
      // proof (the returned array covers protocol-created contexts only).
      const defaultContext = contexts.defaultBrowserContextId;
      if (typeof defaultContext !== 'string' || !defaultContext
          || !Array.isArray(contexts.browserContextIds)
          || contexts.browserContextIds.length !== 0) return skip('home_context_unproven');
      const version = await this.#version();
      if (this.#useVersion(version) !== processProof.browserKey || !allowed()) return skip('browser_changed');
      const { targetInfo: current } = await session.call('Target.getTargetInfo', { targetId: id }, { timeoutMs: 1500 });
      // Last awaited read is the exact target's current URL/context. Revalidate
      // process birth/boot/profile/port plus app state/use synchronously before close.
      if (!allowed() || current?.targetId !== id || current.type !== 'page'
          || current.url !== APPROVED_IDLE_HOME_URL || current.browserContextId !== defaultContext
          || current.attached !== false || current.subtype) return skip('home_guard_rejected');
      // Keep this context fence across uncertain closes, empty-target retries and
      // restart. It is release protection, never target-created ownership.
      this.ownership.process.idleHomeContext = defaultContext;
      this.#saveOwnership();
      this.closingTargets.add(id);
      const result = await session.call('Target.closeTarget', { targetId: id }, { timeoutMs: 1500 });
      if (result.success !== true) return skip('close_failed');
      this.forgetTarget(id);
      return { closed: true, absent: false, reason: 'approved_idle_home' };
    } catch { return skip('home_close_unavailable'); }
    finally { session.close(); }
  }

  async #idleHomeReleaseAllowed(processProof, allowed) {
    const same = () => typeof this.lifecycleGuards.canRelease === 'function'
      && allowed() && this.ownership.process === processProof && Boolean(this.#ownedProcess());
    if (!same()) return false;
    const session = new CdpSession(processProof.browserKey);
    try {
      await session.connect();
      if (!same()) return false;
      const contexts = await session.call('Target.getBrowserContexts', {}, { timeoutMs: 1500 });
      if (!same() || contexts.defaultBrowserContextId !== processProof.idleHomeContext
          || !Array.isArray(contexts.browserContextIds) || contexts.browserContextIds.length !== 0) return false;
      // A page may have appeared while the context read was pending. Recheck the
      // empty target set on the same proven browser instance before signaling it.
      const { targetInfos } = await session.call('Target.getTargets', {}, { timeoutMs: 1500 });
      return same() && Array.isArray(targetInfos) && targetInfos.length === 0;
    } catch { return false; }
    finally { session.close(); }
  }

  async #releaseIfUnused({ guard } = {}) {
    const useEpoch = this.targetUseEpoch;
    const allowed = () => this.#allowed(guard) && this.#allowed(this.lifecycleGuards.canRelease)
      && this.targetUseEpoch === useEpoch;
    const skip = (reason) => ({ released: false, reason });
    if (!allowed()) return skip('guard_rejected');
    if (!this.#ownedProcess()) return skip('unowned_process');
    let version;
    try { version = await this.#version(); } catch { return skip('browser_unavailable'); }
    if (!allowed()) return skip('guard_rejected');
    this.#useVersion(version);
    if (!this.#ownedProcess()) return skip('unowned_process');
    let targets = await this.listExistingTargets();
    if (!allowed()) return skip('guard_rejected');
    if (!Array.isArray(targets)) return skip('targets_unavailable');
    // Permit only nonce-proven untouched startup pages or the exact approved
    // idle home. Other manual pages, conversations and non-page uses still block.
    const processProof = this.ownership.process;
    for (const target of targets) {
      const proof = this.ownership.targets[String(target?.id || '')];
      const startup = this.isOwnedTarget(target?.id) && proof.startupUrl && target.url === proof.startupUrl;
      if (target?.type !== 'page' || (!startup && target.url !== APPROVED_IDLE_HOME_URL)) return skip('targets_in_use');
    }
    for (const target of targets) {
      if (target.url === APPROVED_IDLE_HOME_URL) {
        const result = await this.#closeApprovedHomeTarget(target, { guard: allowed, processProof, useEpoch });
        if (!result.closed) return skip(result.reason);
        continue;
      }
      const startupUrl = this.ownership.targets[target.id]?.startupUrl;
      const result = await this.#closeOwnedTarget(target.id, {
        lifecycle: 'release',
        guard: (observed) => allowed() && this.ownership.targets[target.id]?.startupUrl === startupUrl
          && (!observed || observed.url === startupUrl),
      });
      if (!result.closed && !result.absent) return skip(result.reason);
    }
    targets = await this.listExistingTargets();
    if (!allowed()) return skip('guard_rejected');
    if (!Array.isArray(targets) || targets.length !== 0) return skip('targets_in_use');
    if (processProof?.idleHomeContext && !await this.#idleHomeReleaseAllowed(processProof, allowed)) {
      return skip('home_release_unproven');
    }
    const root = this.#ownedProcess();
    if (!root) return skip('unowned_process');
    const tree = this.#treeRows(this.#processRows(), root.pid);
    if (!tree.some((row) => row.pid === root.pid)) tree.push(root);
    const ownedChild = this.child?.pid === root.pid ? this.child : null;
    const childExited = () => !ownedChild || ownedChild.exitCode !== null || ownedChild.signalCode !== null;
    const rssMb = Math.round((tree.reduce((sum, row) => sum + row.rssKb, 0) / 1024) * 10) / 10;
    if (!allowed() || !this.#ownedProcess()) return skip('guard_rejected');
    // Persist quarantine before sending the first signal, so even a server
    // restart or an unkillable process cannot hand a dying browser to demand.
    this.ownership.process.terminating = true;
    this.#saveOwnership();
    try { process.kill(root.pid, 'SIGTERM'); } catch { return skip('signal_failed'); }
    // Once shutdown is committed, hold the lifecycle queue through the exit
    // outcome even if new work changes the guard. That demand must start after
    // release, never attach to this dying browser. Escalation is limited to the
    // exact captured PID/starttime tree, not a port/profile/name match.
    // Signal success itself is not release success.
    for (let attempt = 0; attempt < 30; attempt += 1) {
      if (tree.every((row) => this.#processExited(row))) break;
      await delay(100);
    }
    for (const row of tree) {
      if (!this.#matchesProcess(row)) continue;
      try { process.kill(row.pid, 'SIGKILL'); } catch {}
    }
    for (let attempt = 0; attempt < 30; attempt += 1) {
      if (tree.every((row) => this.#processExited(row)) && childExited()) {
        this.child = null;
        this.ownership = this.#emptyOwnership();
        this.#saveOwnership();
        this.closingTargets.clear();
        return { released: true, reason: 'unused', rootPid: root.pid, rssMb };
      }
      await delay(100);
    }
    return { released: false, reason: 'process_still_running', rootPid: root.pid, rssMb };
  }

  releaseIfUnused(options = {}) {
    const release = this.#serialize(() => this.#releaseIfUnused(options));
    this.recycling = release;
    void release.finally(() => { if (this.recycling === release) this.recycling = null; }).catch(() => {});
    return release;
  }

  async recycle(reason = 'manual') {
    const result = await this.releaseIfUnused();
    return { ...result, recycled: result.released, reason };
  }

  async #captureStartupTarget(startupUrl, useEpoch) {
    // The launch lease is still held: callers cannot create/receive task tabs
    // until this bounded window ends. Never infer ownership from a generic
    // blank/new-tab URL, and never revisit this window on attach or cleanup.
    const proof = this.ownership.process;
    const deadline = performance.now() + 1500;
    const remaining = () => Math.max(1, Math.ceil(deadline - performance.now()));
    const sameProcess = () => this.targetUseEpoch === useEpoch
      && this.ownership.process === proof && Boolean(this.#ownedProcess());
    while (performance.now() < deadline && sameProcess()) {
      const targets = await this.listExistingTargets(remaining());
      if (!sameProcess() || !Array.isArray(targets) || performance.now() >= deadline) return;
      let version;
      try { version = await this.#version(remaining()); } catch { return; }
      if (this.#useVersion(version) !== proof.browserKey || !sameProcess()
          || performance.now() >= deadline) return;
      if (targets.length) {
        const target = targets[0];
        // Any unrelated/mixed target set or concurrent use makes provenance
        // ambiguous. Do not wait for it to disappear and adopt a later page.
        if (targets.length !== 1 || target?.type !== 'page' || target.url !== startupUrl
            || !target.id || this.activityWrites.has(String(target.id))) return;
        this.#recordTarget(target, startupUrl);
        return;
      }
      await delay(Math.min(100, remaining()));
    }
  }

  async #ensureStarted() {
    const terminating = this.ownership.process;
    if (terminating?.terminating) {
      if (!this.#processExited(terminating)) {
        throw new Error('BROWSER_TERMINATING: owned Chromium shutdown is not confirmed');
      }
      this.ownership = this.#emptyOwnership();
      this.#saveOwnership();
      if (this.child?.pid === terminating.pid) this.child = null;
    }
    try {
      const version = await this.#version();
      this.#useVersion(version);
      return version;
    } catch {}
    await fsPromises.mkdir(this.config.dataDir, { recursive: true });
    const out = fs.openSync(this.config.browserLogFile, 'a');
    const startupUrl = `about:blank#selfrun-startup-${randomUUID()}`;
    const startupUseEpoch = this.targetUseEpoch;
    const args = [
      '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
      '--no-first-run', '--no-default-browser-check', '--no-startup-window', '--remote-allow-origins=*',
      '--remote-debugging-address=127.0.0.1',
      `--remote-debugging-port=${this.config.browserPort}`,
      `--user-data-dir=${this.config.browserProfile}`, startupUrl,
    ];
    let child;
    try {
      child = spawn(this.config.chromiumCommand, args, {
        detached: false, stdio: ['ignore', out, out], env: process.env,
      });
    } finally { fs.closeSync(out); }
    this.child = child;
    let spawnError = null;
    child.once('error', (error) => { spawnError = error; });
    let lastError = null;
    for (let attempt = 0; attempt < 80; attempt += 1) {
      if (spawnError) throw spawnError;
      if (child.exitCode !== null || child.signalCode !== null) {
        throw new Error(`Chromium exited before DevTools became ready: ${child.exitCode ?? child.signalCode}`);
      }
      try {
        const version = await this.#version();
        const browserKey = this.#useVersion(version);
        const root = this.#processIdentity(child.pid);
        const bootId = this.#bootId();
        if (browserKey && bootId && root?.args.includes(`--remote-debugging-port=${this.config.browserPort}`)
            && root.args.includes(`--user-data-dir=${this.config.browserProfile}`)
            && root.args.includes(startupUrl) && !root.args.some((arg) => arg.startsWith('--type='))) {
          this.ownership.process = {
            pid: root.pid, startTime: root.startTime, bootId, browserKey,
            port: Number(this.config.browserPort), profile: this.config.browserProfile,
          };
          this.#saveOwnership();
          await this.#captureStartupTarget(startupUrl, startupUseEpoch);
        }
        return version;
      } catch (error) { lastError = error; await delay(250); }
    }
    throw new Error('Chromium DevTools did not become ready', { cause: lastError });
  }

  ensureStarted() { return this.#serialize(() => this.#ensureStarted()); }

  listTargets() {
    return this.#serialize(async () => {
      await this.#ensureStarted();
      const response = await fetch(this.baseUrl + '/json/list');
      if (!response.ok) throw new Error(`Unable to list Chromium targets: ${response.status}`);
      return response.json();
    });
  }

  createTarget(url = 'about:blank') {
    return this.#serialize(async () => {
      await this.#ensureStarted();
      const response = await fetch(this.baseUrl + '/json/new?' + encodeURIComponent(url), { method: 'PUT' });
      if (!response.ok) throw new Error(`Unable to create Chromium target: ${response.status}`);
      const target = await response.json();
      this.#recordTarget(target);
      return target;
    });
  }

  async connectTarget(target) {
    if (!target?.webSocketDebuggerUrl) throw new Error('Target has no DevTools websocket URL');
    if (this.targetClosing(target.id)) throw new Error('Target is closing');
    this.touchTarget(target.id, true);
    return new CdpSession(target.webSocketDebuggerUrl, () => this.touchTarget(target.id)).connect();
  }

  async probe() {
    const version = await this.ensureStarted();
    const targets = await this.listTargets();
    return {
      ready: true, browser: version.Browser || null,
      protocolVersion: version['Protocol-Version'] || null, targets: targets.length,
    };
  }
}
