const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const STARTED = 'STARTED';

function validDispatch(value) {
  return value
    && value.schema === 'selfrun-drive-dispatch-v1'
    && typeof value.task_id === 'string' && value.task_id.length > 0
    && typeof value.turn_id === 'string' && value.turn_id.length > 0
    && typeof value.request_id === 'string' && value.request_id.length > 0
    && Number.isInteger(Number(value.attempt)) && Number(value.attempt) > 0
    && typeof value.project_url === 'string'
    && typeof value.prompt === 'string' && value.prompt.length > 0
    && Array.isArray(value.profile_operations);
}

function canonicalConversation(value) {
  const raw = String(value || '');
  try {
    const url = new URL(raw);
    const parts = url.pathname.split('/').filter(Boolean);
    const index = parts.indexOf('c');
    const id = index >= 0 && index + 1 < parts.length ? parts[index + 1] : '';
    return url.protocol === 'https:' && url.hostname === 'chatgpt.com' && /^[A-Za-z0-9-]+$/.test(id)
      ? 'https://chatgpt.com/c/' + id : '';
  } catch {
    return '';
  }
}

export class DriveDispatcher {
  constructor({ store, browser, stateStore, config }) {
    this.store = store;
    this.browser = browser;
    this.stateStore = stateStore;
    this.config = config;
    this.running = false;
    this.loopPromise = null;
    this.workers = new Map();
    this.observed = new Map();
    this.lastError = null;
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.loopPromise = this.#loop();
  }

  async stop() {
    this.running = false;
    for (const [key, worker] of this.workers) {
      await this.#retire(key, worker);
    }
    await this.loopPromise?.catch(() => {});
  }

  snapshot() {
    return {
      running: this.running,
      activeDispatches: this.workers.size,
      lastError: this.lastError,
    };
  }

  async #loop() {
    while (this.running) {
      await this.tick();
      if (this.running) await delay(Math.max(500, this.config.drivePollMs));
    }
  }

  async tick() {
    try {
      const entries = await this.store.list();
      const present = new Set(entries.map((entry) => entry.path));
      for (const path of [...this.observed.keys()]) {
        if (!present.has(path)) this.observed.delete(path);
      }
      let cycleError = null;
      for (const entry of entries) {
        const fingerprint = entry.modTime + ':' + entry.size;
        if (this.observed.get(entry.path) === fingerprint) continue;
        try {
          const value = await this.store.read(entry.path);
          if (validDispatch(value)) await this.#observe(entry.path, value);
          this.observed.set(entry.path, fingerprint);
        } catch (error) {
          cycleError = 'dispatch-read:' + String(error?.message || error);
        }
      }
      this.lastError = cycleError;
      await this.stateStore.patch({
        status: this.workers.size ? 'DRIVE_EXECUTING' : 'DRIVE_WATCHING',
        lastError: this.lastError,
      });
    } catch (error) {
      this.lastError = 'drive-watch:' + String(error?.message || error);
      await this.stateStore.patch({
        status: 'DRIVE_WATCH_ERROR',
        lastError: this.lastError,
      }).catch(() => {});
    }
  }

  async #observe(path, value) {
    const state = String(value.state || '');
    const attempt = Number(value.attempt);
    let worker = this.workers.get(path);

    if (state === 'RESULT_COMMITTED' || state === 'ERROR') {
      if (worker) await this.#retire(path, worker);
      return;
    }

    const identityChanged = !worker
      || worker.requestId !== value.request_id
      || worker.attempt !== attempt;

    if (state === 'PREPARE_REQUESTED') {
      if (identityChanged) {
        if (worker) await this.#retire(path, worker);
        worker = this.#newWorker(path, value);
        this.workers.set(path, worker);
        void this.#prepare(worker, value);
      }
      return;
    }

    if (identityChanged) {
      if (worker) await this.#retire(path, worker);
      worker = this.#newWorker(path, value);
      this.workers.set(path, worker);
    }

    if (state === 'READY_TO_SUBMIT') {
      if (!worker.prepared && !worker.preparing) {
        await this.#markError(worker, value, 'SERVER_PREPARED_TARGET_LOST');
      }
      return;
    }

    if (state === 'SEND_REQUESTED') {
      if (worker.prepared && !worker.sending) void this.#submit(worker, value);
      return;
    }

    if (state === 'SUBMITTED') {
      return;
    }

    if (state === STARTED) {
      if (!worker.prepared && !worker.attaching) {
        void this.#reattach(worker, value);
      } else if (worker.prepared && !worker.monitoring) {
        void this.#monitor(worker);
      }
    }
  }

  #newWorker(path, value) {
    return {
      path,
      requestId: value.request_id,
      attempt: Number(value.attempt),
      value,
      abort: new AbortController(),
      prepared: null,
      preparing: false,
      sending: false,
      attaching: false,
      monitoring: false,
    };
  }

  #isCurrent(worker) {
    return this.workers.get(worker.path) === worker && !worker.abort.signal.aborted;
  }

  async #prepare(worker, value) {
    worker.preparing = true;
    try {
      const prepared = await this.browser.prepare({
        projectUrl: value.project_url,
        prompt: value.prompt,
        profileOperations: value.profile_operations,
        markerId: value.request_id + ':' + value.attempt,
        signal: worker.abort.signal,
      });
      if (!this.#isCurrent(worker)) {
        await this.#closePrepared(prepared);
        return;
      }
      worker.prepared = prepared;
      const result = await this.store.mutate(worker.path, {
        requestId: worker.requestId,
        attempt: worker.attempt,
        states: ['PREPARE_REQUESTED'],
      }, (current) => {
        current.state = 'READY_TO_SUBMIT';
        current.server_target_id = prepared.target.id;
        current.server_ready_at_ms = Date.now();
        return current;
      });
      if (!result.applied) {
        await this.#retire(worker.path, worker);
      }
    } catch (error) {
      if (this.#isCurrent(worker) && !worker.abort.signal.aborted) {
        await this.#markError(worker, value, error?.code || 'SERVER_PREPARE_FAILED');
      }
    } finally {
      worker.preparing = false;
    }
  }

  async #submit(worker, value) {
    if (!this.#isCurrent(worker) || !worker.prepared) return;
    worker.sending = true;
    try {
      const started = await this.browser.submitPrepared(worker.prepared, {
        signal: worker.abort.signal,
        onSubmitted: async () => {
          await this.store.mutate(worker.path, {
            requestId: worker.requestId,
            attempt: worker.attempt,
            states: ['SEND_REQUESTED'],
          }, (current) => {
            current.state = 'SUBMITTED';
            current.server_submitted_at_ms = Date.now();
            return current;
          });
        },
      });
      if (!this.#isCurrent(worker)) return;
      const result = await this.store.mutate(worker.path, {
        requestId: worker.requestId,
        attempt: worker.attempt,
        states: ['SUBMITTED', 'SEND_REQUESTED'],
      }, (current) => {
        current.state = STARTED;
        current.conversation_url = started.url;
        current.server_started_at_ms = Date.now();
        current.server_target_id = worker.prepared.target.id;
        current.liveness_status = 'RUNNING';
        current.recovery_count = Number(current.recovery_count || 0);
        return current;
      });
      if (!result.applied) {
        await this.#retire(worker.path, worker);
        return;
      }
      worker.value = result.current;
      void this.#monitor(worker);
    } catch (error) {
      if (this.#isCurrent(worker) && !worker.abort.signal.aborted) {
        await this.#markError(worker, value, error?.code || 'SERVER_SUBMIT_FAILED');
      }
    } finally {
      worker.sending = false;
    }
  }

  async #reattach(worker, value) {
    const url = canonicalConversation(value.conversation_url);
    if (!url) {
      await this.#markError(worker, value, 'SERVER_CONVERSATION_URL_INVALID');
      return;
    }
    worker.attaching = true;
    try {
      const prepared = await this.browser.attachConversation({
        conversationUrl: url,
        profileOperations: value.profile_operations,
        markerId: value.request_id + ':' + value.attempt + ':reattach',
        signal: worker.abort.signal,
      });
      if (!this.#isCurrent(worker)) {
        await this.#closePrepared(prepared);
        return;
      }
      worker.prepared = prepared;
      worker.value = value;
      void this.#monitor(worker);
    } catch (error) {
      if (this.#isCurrent(worker) && !worker.abort.signal.aborted) {
        await this.#markError(worker, value, 'SERVER_REATTACH_FAILED');
      }
    } finally {
      worker.attaching = false;
    }
  }

  async #monitor(worker) {
    if (!this.#isCurrent(worker) || !worker.prepared || worker.monitoring) return;
    worker.monitoring = true;
    try {
      const result = await this.browser.monitor({
        prepared: worker.prepared,
        signal: worker.abort.signal,
        onRecovery: async ({ recoveryCount, lastActivityAt }) => {
          await this.store.mutate(worker.path, {
            requestId: worker.requestId,
            attempt: worker.attempt,
            states: [STARTED],
          }, (current) => {
            current.liveness_status = 'RECOVERY_SENT';
            current.recovery_count = recoveryCount;
            current.last_activity_at_ms = lastActivityAt;
            current.last_recovery_at_ms = Date.now();
            return current;
          }).catch(() => {});
        },
      });
      if (result?.status === 'PAGE_ERROR' && this.#isCurrent(worker)) {
        await this.#markError(worker, worker.value, 'SERVER_PAGE_ERROR');
      }
    } catch (error) {
      if (this.#isCurrent(worker) && !worker.abort.signal.aborted) {
        await this.#markError(worker, worker.value, 'SERVER_MONITOR_FAILED');
      }
    } finally {
      worker.monitoring = false;
    }
  }

  async #markError(worker, value, code) {
    if (!this.#isCurrent(worker)) return;
    await this.store.mutate(worker.path, {
      requestId: worker.requestId,
      attempt: worker.attempt,
      states: [
        'PREPARE_REQUESTED', 'READY_TO_SUBMIT',
        'SEND_REQUESTED', 'SUBMITTED', STARTED,
      ],
    }, (current) => {
      current.state = 'ERROR';
      current.error_code = String(code || 'SERVER_ERROR').slice(0, 120);
      current.server_error_at_ms = Date.now();
      return current;
    }).catch(() => {});
    await this.#retire(worker.path, worker);
  }

  async #retire(path, worker) {
    if (!worker) return;
    if (!worker.abort.signal.aborted) worker.abort.abort(new Error('dispatch superseded'));
    await this.#closePrepared(worker.prepared);
    if (this.workers.get(path) === worker) this.workers.delete(path);
  }

  async #closePrepared(prepared) {
    if (!prepared) return;
    try { prepared.session?.close(); } catch {}
    try { await this.browser.chromium.closeTarget(prepared.target?.id); } catch {}
  }
}
