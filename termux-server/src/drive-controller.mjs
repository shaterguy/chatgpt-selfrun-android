import { appendTurnStartDirective } from './prompt-directives.mjs';

const DISPATCH_SCHEMA = 'selfrun-server-dispatch-v1';
const CONTROL_SCHEMA = 'selfrun-task-control-v1';
const CONTROL_STATES = new Set([
  'RUNNING', 'WAITING_USER_INTERVENTION', 'PAUSED', 'STOPPED',
  'RESUME_REQUESTED', 'RESUME_STOPPED_REQUESTED', 'DONE',
]);
const LIVENESS_VERIFY_RETRY_MS = 15_000;

function clean(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function errorMessage(error) {
  return String(error?.message || error || 'unknown').slice(0, 500);
}

function cursorDetails(probe) {
  const stopButtonVisible = probe?.stopButtonVisible ?? probe?.streaming;
  return {
    streaming: !!probe?.streaming,
    stop_button_visible: !!stopButtonVisible,
    paused: !!probe?.paused,
    assistant_count: Number(probe?.assistantCount || 0),
    assistant_text_length: Number(probe?.assistantTextLength || 0),
    assistant_message_id: clean(probe?.assistantMessageId) || null,
    response_turn_id: clean(probe?.responseTurnId) || null,
    response_text_length: Number(probe?.responseTextLength || 0),
    response_fingerprint: clean(probe?.responseFingerprint) || null,
    user_count: Number(probe?.userCount || 0),
    user_text_length: Number(probe?.userTextLength || 0),
    user_message_id: clean(probe?.userMessageId) || null,
  };
}

function validateDispatch(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('invalid dispatch body');
  if (body.schema !== DISPATCH_SCHEMA) throw new Error('unsupported dispatch schema');
  for (const key of ['task_id', 'turn_id', 'request_id', 'project_url', 'prompt']) {
    if (!clean(body[key])) throw new Error(`dispatch ${key} required`);
  }
  if (!Array.isArray(body.profile_operations)) throw new Error('dispatch profile_operations required');
  const attempt = Number(body.dispatch_attempt);
  if (!Number.isInteger(attempt) || attempt < 1) throw new Error('dispatch attempt invalid');
  return body;
}

function validateControl(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('invalid control body');
  if (body.schema !== CONTROL_SCHEMA) throw new Error('unsupported control schema');
  const taskId = clean(body.task_id);
  const state = clean(body.state);
  const epoch = Number(body.control_epoch);
  if (!taskId) throw new Error('control task_id required');
  if (!CONTROL_STATES.has(state)) throw new Error('control state invalid');
  if (!Number.isSafeInteger(epoch) || epoch < 1) throw new Error('control epoch invalid');
  return { ...body, task_id: taskId, state, control_epoch: epoch };
}

function canonicalConversationUrl(url) {
  try {
    const parsed = new URL(url);
    const parts = parsed.pathname.split('/').filter(Boolean);
    const index = parts.indexOf('c');
    const raw = index >= 0 && index + 1 < parts.length ? parts[index + 1] : '';
    const id = decodeURIComponent(raw);
    if (!id || id.toLowerCase().startsWith('local-chatgpt')) return '';
    if (!/^[A-Za-z0-9_-]{1,160}$/.test(id)) return '';
    return `https://chatgpt.com/c/${id}`;
  } catch {
    return '';
  }
}

export class DriveDispatchController {
  constructor({ browser, stateStore, config, promptDirectives = null }) {
    this.browser = browser;
    this.stateStore = stateStore;
    this.config = config;
    this.promptDirectives = promptDirectives;
    this.active = null;
    this.actives = new Map();
    this.controls = new Map();
    this.browserOpenTail = Promise.resolve();
    this.browserOpenQueued = 0;
    this.pendingPrepares = new Map();
    this.idleRecycleTimer = null;
    this.browserMaintenance = null;
  }

  getActive(path) {
    return this.actives.get(path) || null;
  }

  activeDispatches() {
    return [...this.actives.values()];
  }

  controlForTask(taskId) {
    return this.controls.get(clean(taskId)) || null;
  }

  #isActive(active) {
    return !!active
      && this.actives.get(active.path) === active
      && !active.abortController.signal.aborted;
  }

  async #directiveState() {
    if (this.promptDirectives?.current) return this.promptDirectives.current();
    return {
      turnStartDirective: clean(this.config.turnStartDirective),
      turnContinueDirective: clean(this.config.recoveryPrompt),
    };
  }

  #cancelIdleRecycle() {
    if (!this.idleRecycleTimer) return;
    clearTimeout(this.idleRecycleTimer);
    this.idleRecycleTimer = null;
  }

  #reserveBrowserOpenSlot() {
    this.browserOpenQueued += 1;
    this.#cancelIdleRecycle();
    const previous = this.browserOpenTail;
    let unlock = null;
    this.browserOpenTail = new Promise((resolve) => { unlock = resolve; });
    let released = false;
    return {
      wait: async () => {
        await previous;
      },
      release: async () => {
        if (released) return;
        released = true;
        this.browserOpenQueued = Math.max(0, this.browserOpenQueued - 1);
        unlock?.();
        await this.#browserTopologyChanged();
      },
    };
  }

  #managedChatGptTarget(target) {
    if (!target?.id || clean(target.type) !== 'page') return false;
    try {
      return new URL(target.url).hostname === 'chatgpt.com';
    } catch {
      return false;
    }
  }

  async #garbageCollectOrphanTargets() {
    if (this.config.browserOrphanGcEnabled === false
        || this.browserOpenQueued > 0
        || typeof this.browser.chromium?.listExistingTargets !== 'function') return 0;
    const targets = await this.browser.chromium.listExistingTargets();
    if (!Array.isArray(targets)) return 0;
    const activeIds = new Set(this.activeDispatches()
      .map((active) => clean(active.target?.id))
      .filter(Boolean));
    const orphanIds = targets
      .filter((target) => this.#managedChatGptTarget(target)
        && !activeIds.has(clean(target.id))
        && (typeof this.browser.chromium?.isTargetIdle !== 'function'
          || this.browser.chromium.isTargetIdle(clean(target.id))))
      .map((target) => clean(target.id))
      .filter(Boolean);
    for (const targetId of orphanIds) {
      try { await this.browser.chromium.closeTarget(targetId); } catch {}
    }
    if (orphanIds.length && typeof this.stateStore.recordEvent === 'function') {
      await this.stateStore.recordEvent('BROWSER_ORPHAN_TARGETS_CLOSED', {
        count: orphanIds.length,
        target_ids: orphanIds,
      }).catch(() => {});
    }
    return orphanIds.length;
  }

  async #recycleBrowserIfIdle(reason, knownRssMb = null) {
    if (this.activeDispatches().length > 0 || this.browserOpenQueued > 0) return false;
    if (typeof this.browser.chromium?.recycle !== 'function') return false;
    if (typeof this.browser.chromium?.hasRecentlyActiveTargets === 'function'
        && await this.browser.chromium.hasRecentlyActiveTargets()) return false;
    const rssMb = knownRssMb ?? (
      typeof this.browser.chromium.residentSetMb === 'function'
        ? await this.browser.chromium.residentSetMb()
        : null
    );
    if (this.activeDispatches().length > 0 || this.browserOpenQueued > 0) return false;
    const result = await this.browser.chromium.recycle(reason);
    if (result?.recycled && typeof this.stateStore.recordEvent === 'function') {
      await this.stateStore.recordEvent('BROWSER_RECYCLED', {
        reason,
        rss_mb: rssMb ?? result.rssMb ?? null,
        root_pid: result.rootPid ?? null,
      }).catch(() => {});
    }
    return !!result?.recycled;
  }

  async #browserTopologyChanged() {
    if (this.browserMaintenance) return this.browserMaintenance;
    this.browserMaintenance = (async () => {
      if (this.browserOpenQueued > 0) {
        this.#cancelIdleRecycle();
        return;
      }

      await this.#garbageCollectOrphanTargets();
      if (this.activeDispatches().length > 0 || this.browserOpenQueued > 0) {
        this.#cancelIdleRecycle();
        return;
      }

      const rssMb = typeof this.browser.chromium?.residentSetMb === 'function'
        ? await this.browser.chromium.residentSetMb()
        : null;
      const rssLimitMb = Math.max(0, Number(this.config.browserIdleRssMb || 0));
      if (rssLimitMb > 0 && Number.isFinite(rssMb) && rssMb >= rssLimitMb) {
        this.#cancelIdleRecycle();
        await this.#recycleBrowserIfIdle('idle_rss_limit', rssMb);
        return;
      }

      const idleMs = Math.max(0, Number(this.config.browserIdleRecycleMs || 0));
      if (!idleMs || this.idleRecycleTimer) return;
      this.idleRecycleTimer = setTimeout(() => {
        this.idleRecycleTimer = null;
        void this.#recycleBrowserIfIdle('idle_timeout').catch(() => {});
      }, idleMs);
      this.idleRecycleTimer.unref?.();
    })();
    try {
      await this.browserMaintenance;
    } finally {
      this.browserMaintenance = null;
    }
  }

  #retryablePreparedSendError(error) {
    return String(error?.message || error) === 'CDP command timeout: Runtime.evaluate';
  }

  async #inspectPreparedTargetConversation(active, graceMs = 1500) {
    const targetId = clean(active?.target?.id);
    const chromium = this.browser?.chromium;
    if (!targetId || !chromium) return { inspected: false, conversationUrl: null };
    const listTargets = typeof chromium.listExistingTargets === 'function'
      ? () => chromium.listExistingTargets()
      : (typeof chromium.listTargets === 'function' ? () => chromium.listTargets() : null);
    if (!listTargets) return { inspected: false, conversationUrl: null };

    const deadline = Date.now() + Math.max(0, Number(graceMs || 0));
    while (true) {
      if (active.abortController.signal.aborted) {
        throw active.abortController.signal.reason || new Error('aborted');
      }
      let targets;
      try {
        targets = await listTargets();
      } catch {
        return { inspected: false, conversationUrl: null };
      }
      if (!Array.isArray(targets)) return { inspected: false, conversationUrl: null };
      const current = targets.find((target) => clean(target?.id) === targetId);
      const conversationUrl = canonicalConversationUrl(current?.url);
      if (conversationUrl) return { inspected: true, conversationUrl };
      if (Date.now() >= deadline) return { inspected: true, conversationUrl: null };
      await new Promise((resolve) => setTimeout(resolve, 150));
    }
  }

  async #recoverPreparedSend(active, body, error) {
    if (!this.#retryablePreparedSendError(error)
        || active.sendRecoveryAttempted
        || active.abortController.signal.aborted) {
      throw error;
    }
    active.sendRecoveryAttempted = true;

    const browserSlot = this.#reserveBrowserOpenSlot();
    try {
      await browserSlot.wait();
      if (active.abortController.signal.aborted) {
        throw active.abortController.signal.reason || new Error('aborted');
      }

      const observed = await this.#inspectPreparedTargetConversation(active);
      if (!observed.inspected) throw error;

      const oldTargetId = clean(active.target?.id);
      try { active.session?.close?.(); } catch {}
      if (oldTargetId) {
        try { await this.browser.chromium.closeTarget(oldTargetId); } catch {}
      }

      let replacement;
      let mode;
      if (observed.conversationUrl) {
        replacement = await this.browser.resume({
          conversationUrl: observed.conversationUrl,
          signal: active.abortController.signal,
        });
        mode = 'RESUME_EXISTING';
      } else {
        replacement = await this.browser.prepare({
          projectUrl: body.project_url,
          prompt: active.effectivePrompt || body.prompt,
          signal: active.abortController.signal,
        });
        mode = 'FRESH_PREPARE';
      }
      if (active.abortController.signal.aborted) {
        try { replacement.session?.close?.(); } catch {}
        try { await this.browser.chromium.closeTarget(replacement.target?.id); } catch {}
        throw active.abortController.signal.reason || new Error('aborted');
      }

      active.target = replacement.target;
      active.session = replacement.session;
      active.baseline = replacement.baseline;
      active.sendRecoveryCount = Math.max(0, Number(active.sendRecoveryCount || 0)) + 1;
      await this.stateStore.patchIfCurrent(
        active.generation,
        this.stateStore.snapshot().lastSignalId,
        {
          activeTargetId: replacement.target?.id || null,
          conversationUrl: observed.conversationUrl || null,
          lastActivityAt: new Date().toISOString(),
          lastError: null,
        },
        'DRIVE_DISPATCH_SEND_SESSION_RECOVERED',
      );
      if (typeof this.stateStore.recordEvent === 'function') {
        await this.stateStore.recordEvent('DRIVE_DISPATCH_SEND_SESSION_RECOVERED', {
          task_id: active.identity.taskId,
          turn_id: active.identity.turnId,
          request_id: active.identity.requestId,
          dispatch_attempt: active.identity.attempt,
          recovery_count: active.sendRecoveryCount,
          mode,
          previous_target_id: oldTargetId || null,
          target_id: replacement.target?.id || null,
          conversation_url: observed.conversationUrl || null,
          error: String(error?.message || error),
        }, this.#activeEventContext(active));
      }
      await this.#syncActiveSummary();

      if (observed.conversationUrl) return { url: observed.conversationUrl };
      return this.browser.submitPrepared({
        session: active.session,
        profileOperations: body.profile_operations,
        signal: active.abortController.signal,
      });
    } finally {
      await browserSlot.release();
    }
  }

  async #syncActiveSummary() {
    const rows = this.activeDispatches().map((active) => ({
      path: active.path,
      task_id: active.identity.taskId,
      turn_id: active.identity.turnId,
      request_id: active.identity.requestId,
      generation: active.generation,
      server_status: clean(active.body?.server_status) || active.serverStatus,
      control_state: clean(active.controlState) || 'UNKNOWN',
      target_id: active.target?.id || null,
      conversation_url: canonicalConversationUrl(active.body?.conversation_url) || null,
    }));
    await this.stateStore.patch({
      activeCount: rows.length,
      activeDispatches: rows,
    });
  }

  async #registerActive(active) {
    this.#cancelIdleRecycle();
    this.actives.set(active.path, active);
    this.active = active;
    await this.#syncActiveSummary();
  }

  async control(path, rawBody, transport) {
    const control = validateControl(rawBody);
    const previous = this.controls.get(control.task_id);
    if (previous && control.control_epoch <= previous.control_epoch) return;
    this.controls.set(control.task_id, { ...control, path });

    const matches = this.activeDispatches()
      .filter((active) => active.identity.taskId === control.task_id);
    for (const active of matches) {
      active.controlState = control.state;
      active.controlEpoch = control.control_epoch;
      active.controlUpdatedAtMs = Number(control.updated_at_ms || Date.now());
      active.body = {
        ...active.body,
        server_control_epoch: control.control_epoch,
        server_control_state: control.state,
      };
      if (typeof transport?.write === 'function' && active.path) {
        try {
          await transport.write(active.path, active.body);
          active.publishPending = false;
        } catch {
          active.publishPending = true;
        }
      }
      await this.stateStore.patchIfCurrent(
        active.generation,
        this.stateStore.snapshot().lastSignalId,
        {
          status: `CONTROL_${control.state}`,
          lastActivityAt: new Date().toISOString(),
          lastError: null,
        },
        'TASK_CONTROL_UPDATED',
      );
    }

    if (control.state === 'STOPPED' || control.state === 'DONE') {
      for (const active of matches) await this.#closeActive(active);
      this.controls.delete(control.task_id);
      return;
    }

    const exactActive = matches.find(
      (active) => active.identity.turnId === clean(control.turn_id)
        && active.identity.requestId === clean(control.request_id),
    );
    if (control.state === 'RUNNING' && !exactActive && transport) {
      await this.#resumeFromControl(control, transport);
    }
  }

  async retryResumeFromControl(rawControl, transport) {
    const control = validateControl(rawControl);
    if (control.state !== 'RUNNING') return;
    await this.#resumeFromControl(control, transport);
  }

  async #resumeFromControl(control, transport) {
    if (typeof transport?.list !== 'function' || typeof transport?.read !== 'function') return;
    const files = await transport.list();
    const candidates = [];
    const dispatchPrefix = `__SELFRUN_DISPATCH__${clean(control.request_id)}__A`;
    for (const file of files) {
      const filePath = String(file.path || '');
      if (!filePath.startsWith(dispatchPrefix) || !filePath.endsWith('.json')) continue;
      let body;
      try {
        body = await transport.read(filePath);
      } catch {
        continue;
      }
      if (body?.schema !== DISPATCH_SCHEMA
          || clean(body.task_id) !== control.task_id
          || clean(body.turn_id) !== clean(control.turn_id)
          || clean(body.request_id) !== clean(control.request_id)) continue;
      candidates.push({ path: filePath, body });
    }
    candidates.sort((a, b) => Number(b.body.dispatch_attempt || 0) - Number(a.body.dispatch_attempt || 0));
    const candidate = candidates[0];
    if (!candidate || this.getActive(candidate.path)) return;
    const body = candidate.body;
    if (clean(body.client_status) !== 'SEND_REQUESTED') return;
    const resumableStatus = clean(body.server_status);
    const recoverableErroredMonitor = resumableStatus === 'ERROR'
      && clean(body.client_status) === 'SEND_REQUESTED'
      && Boolean(canonicalConversationUrl(body.conversation_url));
    if (!['STARTED', 'RECOVERY_SENT', 'SUPERSEDED'].includes(resumableStatus)
        && !recoverableErroredMonitor) return;
    if (!canonicalConversationUrl(body.conversation_url)) return;

    const resultCheck = await this.#resultCommitState({ body }, transport);
    if (resultCheck.state === 'COMMITTED') {
      const now = Date.now();
      await transport.write(candidate.path, {
        ...body,
        server_status: 'COMPLETED',
        completion_source: 'RESULT_DOCUMENT',
        completed_at_ms: now,
        updated_at_ms: now,
        server_error: '',
        resume_retry_at_ms: 0,
      }).catch(() => {});
      await this.stateStore.recordEvent('DRIVE_DISPATCH_REATTACH_SKIPPED_COMMITTED', {
        task_id: control.task_id,
        turn_id: clean(control.turn_id),
        request_id: clean(control.request_id),
        result_status: clean(resultCheck.resultStatus) || null,
      }, this.#dispatchEventContext(body));
      if (clean(resultCheck.resultStatus) === 'DONE') {
        this.controls.delete(control.task_id);
      }
      return;
    }

    await this.stateStore.recordEvent('DRIVE_DISPATCH_REATTACH_REQUESTED', {
      task_id: control.task_id,
      turn_id: clean(control.turn_id),
      request_id: clean(control.request_id),
      previous_server_status: clean(body.server_status),
    }, this.#dispatchEventContext(body));
    await this.resume(candidate.path, {
      ...body,
      server_status: 'STARTED',
      server_error: '',
      updated_at_ms: Date.now(),
    }, transport);
  }

  async prepare(path, rawBody, transport) {
    const body = validateDispatch(rawBody);
    if (clean(body.client_status) !== 'CREATE_REQUESTED') return;
    const existing = this.getActive(path);
    if (existing
        && ['READY_TO_SUBMIT', 'STARTED', 'COMPLETED'].includes(existing.serverStatus)) {
      if (clean(body.server_status) !== clean(existing.body?.server_status)
          || existing.publishPending) {
        await transport.write(path, existing.body);
        existing.publishPending = false;
      }
      return;
    }

    const abortController = new AbortController();
    const identity = {
      taskId: body.task_id,
      turnId: body.turn_id,
      requestId: body.request_id,
      attempt: Number(body.dispatch_attempt),
    };
    const browserSlot = this.#reserveBrowserOpenSlot();
    this.pendingPrepares.set(path, {
      path,
      body: { ...body },
      identity,
      abortController,
      transport,
    });

    let generation = null;
    try {
      await this.#releasePredecessorActives(body);
      await this.#supersede(path, body, transport);
      await browserSlot.wait();
      if (abortController.signal.aborted) return;

      generation = this.stateStore.snapshot().generation + 1;
      await this.stateStore.patch({
        generation,
        status: 'DRIVE_PREPARING',
        activeSignal: {
          signalId: `${identity.requestId}:attempt:${identity.attempt}`,
          type: 'DRIVE_DISPATCH',
          envelope: {
            TASK_ID: identity.taskId,
            TURN_ID: identity.turnId,
            REQUEST_ID: identity.requestId,
          },
        },
        activeTargetId: null,
        conversationUrl: null,
        acceptedAt: new Date().toISOString(),
        lastActivityAt: new Date().toISOString(),
        lastSignalId: `${identity.requestId}:attempt:${identity.attempt}`,
        lastError: null,
      }, 'DRIVE_DISPATCH_PREPARE');

      const directives = await this.#directiveState();
      const effectivePrompt = appendTurnStartDirective(
        body.prompt,
        directives.turnStartDirective,
      );
      const prepared = await this.browser.prepare({
        projectUrl: body.project_url,
        prompt: effectivePrompt,
        signal: abortController.signal,
      });
      if (abortController.signal.aborted) throw abortController.signal.reason || new Error('superseded');

      const control = this.controls.get(identity.taskId);
      const active = {
        path,
        body: { ...body },
        identity,
        generation,
        abortController,
        target: prepared.target,
        session: prepared.session,
        baseline: prepared.baseline,
        effectivePrompt,
        serverStatus: 'READY_TO_SUBMIT',
        recoveryCount: 0,
        verificationFailureCount: 0,
        resultReadFailureCount: 0,
        cursorProbeFailureCount: 0,
        recovering: false,
        publishPending: false,
        controlState: clean(control?.state) || 'UNKNOWN',
        controlEpoch: Number(control?.control_epoch || 0),
        controlUpdatedAtMs: Number(control?.updated_at_ms || 0),
      };

      const next = {
        ...body,
        server_status: 'READY_TO_SUBMIT',
        server_generation: generation,
        prepared_at_ms: Date.now(),
        server_error: '',
      };
      active.body = next;
      await this.#registerActive(active);
      await this.stateStore.patchIfCurrent(generation, this.stateStore.snapshot().lastSignalId, {
        status: 'READY_TO_SUBMIT',
        activeTargetId: prepared.target.id,
        lastActivityAt: new Date().toISOString(),
      }, 'DRIVE_DISPATCH_READY');
      await transport.write(path, next);
    } catch (error) {
      if (abortController.signal.aborted) return;
      const failedGeneration = generation ?? this.stateStore.snapshot().generation;
      const current = this.getActive(path);
      if (current?.serverStatus === 'READY_TO_SUBMIT') {
        current.publishPending = true;
        throw error;
      }
      const next = {
        ...body,
        server_status: 'ERROR',
        server_error: String(error?.message || error).slice(0, 500),
        server_generation: failedGeneration,
        updated_at_ms: Date.now(),
      };
      await transport.write(path, next).catch(() => {});
      await this.stateStore.patchIfCurrent(failedGeneration, this.stateStore.snapshot().lastSignalId, {
        status: error?.code === 'AUTH_REQUIRED' ? 'AUTH_REQUIRED' : 'ERROR',
        lastError: next.server_error,
      }, 'DRIVE_DISPATCH_ERROR');
    } finally {
      const pending = this.pendingPrepares.get(path);
      if (pending?.abortController === abortController) this.pendingPrepares.delete(path);
      await browserSlot.release();
    }
  }

  async resume(path, rawBody, transport) {
    const body = validateDispatch(rawBody);
    if (clean(body.client_status) !== 'SEND_REQUESTED') return;
    const taskControl = this.controls.get(clean(body.task_id));
    if (taskControl && ['STOPPED', 'DONE'].includes(clean(taskControl.state))) return;
    const conversationUrl = canonicalConversationUrl(body.conversation_url);
    if (!conversationUrl) {
      await transport.write(path, {
        ...body,
        server_status: 'ERROR',
        server_error: 'canonical conversation URL unavailable for resume',
        updated_at_ms: Date.now(),
      });
      return;
    }
    const existing = this.getActive(path);
    if (existing && ['STARTED', 'COMPLETED'].includes(existing.serverStatus)) {
      if (clean(body.server_status) !== clean(existing.body?.server_status)
          || canonicalConversationUrl(body.conversation_url)
            !== canonicalConversationUrl(existing.body?.conversation_url)
          || existing.publishPending) {
        await transport.write(path, existing.body);
        existing.publishPending = false;
      }
      return;
    }

    const browserSlot = this.#reserveBrowserOpenSlot();
    try {
      await browserSlot.wait();
      await this.#supersede(path, body, transport);
      const generation = this.stateStore.snapshot().generation + 1;
    const abortController = new AbortController();
    const identity = {
      taskId: body.task_id,
      turnId: body.turn_id,
      requestId: body.request_id,
      attempt: Number(body.dispatch_attempt),
    };
    const signalId = `${identity.requestId}:attempt:${identity.attempt}`;

    await this.stateStore.patch({
      generation,
      status: 'DRIVE_RESUMING',
      activeSignal: {
        signalId,
        type: 'DRIVE_DISPATCH',
        envelope: {
          TASK_ID: identity.taskId,
          TURN_ID: identity.turnId,
          REQUEST_ID: identity.requestId,
        },
      },
      activeTargetId: null,
      conversationUrl,
      acceptedAt: new Date().toISOString(),
      lastActivityAt: new Date().toISOString(),
      lastSignalId: signalId,
      lastError: null,
    }, 'DRIVE_DISPATCH_RESUME');

    let resumed = null;
    try {
      resumed = await this.browser.resume({
        conversationUrl,
        signal: abortController.signal,
      });
      if (abortController.signal.aborted) {
        throw abortController.signal.reason || new Error('superseded');
      }
      const control = this.controls.get(identity.taskId);
      const controlEpoch = Math.max(0, Number(control?.control_epoch || 0));
      const previousControlEpoch = Math.max(0, Number(body.server_control_epoch || 0));
      const continuationEpoch = Math.max(0, Number(body.resume_continuation_control_epoch || 0));
      let resumeContinuationSent = false;
      let continuationAccepted = null;
      if (clean(control?.state) === 'RUNNING') {
        const directives = await this.#directiveState();
        try {
          continuationAccepted = await this.browser.sendContinuation({
            session: resumed.session,
            prompt: directives.turnContinueDirective || this.config.recoveryPrompt,
            profileOperations: body.profile_operations,
            signal: abortController.signal,
          });
          resumeContinuationSent = true;
        } catch (error) {
          const message = String(error?.message || error);
          if (message !== 'Continuation send control not ready: NO_SEND') throw error;
          if (typeof this.stateStore.recordEvent === 'function') {
            await this.stateStore.recordEvent('DRIVE_DISPATCH_RESUME_CONTINUATION_DEFERRED', {
              task_id: identity.taskId,
              turn_id: identity.turnId,
              request_id: identity.requestId,
              reason: 'SEND_CONTROL_NOT_READY',
              error: message,
            }, this.#dispatchEventContext(body, generation));
          }
        }
      }
      const monitorBaseline = resumeContinuationSent
        ? {
            ...resumed.baseline,
            userCount: continuationAccepted?.userCount ?? resumed.baseline.userCount,
            userTextLength: continuationAccepted?.userTextLength ?? resumed.baseline.userTextLength,
            userMessageId: continuationAccepted?.userMessageId ?? resumed.baseline.userMessageId,
          }
        : resumed.baseline;
      const active = {
        path,
        body: { ...body },
        identity,
        generation,
        abortController,
        target: resumed.target,
        session: resumed.session,
        baseline: monitorBaseline,
        serverStatus: 'STARTED',
        recoveryCount: Math.max(0, Number(body.recovery_count || 0)),
        verificationFailureCount: 0,
        resultReadFailureCount: 0,
        cursorProbeFailureCount: 0,
        recovering: false,
        publishPending: false,
        controlState: clean(control?.state) || 'UNKNOWN',
        controlEpoch: Number(control?.control_epoch || 0),
        controlUpdatedAtMs: Number(control?.updated_at_ms || 0),
      };
      active.body = {
        ...body,
        server_status: 'STARTED',
        conversation_url: conversationUrl,
        server_generation: generation,
        resumed_at_ms: Date.now(),
        server_error: '',
        resume_retry_at_ms: 0,
        server_control_epoch: controlEpoch || previousControlEpoch,
        server_control_state: clean(control?.state) || clean(body.server_control_state) || 'UNKNOWN',
        resume_continuation_control_epoch: resumeContinuationSent
          ? controlEpoch
          : Math.max(0, Number(body.resume_continuation_control_epoch || 0)),
        resume_continuation_sent_at_ms: resumeContinuationSent
          ? Date.now()
          : Number(body.resume_continuation_sent_at_ms || 0),
      };
      await this.#registerActive(active);
      await this.stateStore.patchIfCurrent(generation, signalId, {
        status: 'RUNNING',
        activeTargetId: resumed.target.id,
        conversationUrl,
        lastActivityAt: new Date().toISOString(),
        lastError: null,
      }, 'DRIVE_DISPATCH_RESUMED');
      if (resumeContinuationSent && typeof this.stateStore.recordEvent === 'function') {
        await this.stateStore.recordEvent('DRIVE_DISPATCH_RESUME_CONTINUATION_SENT', {
          task_id: identity.taskId,
          turn_id: identity.turnId,
          request_id: identity.requestId,
          control_epoch: controlEpoch,
        }, this.#activeEventContext(active));
      }
      void this.#monitor(active, transport);
      await transport.write(path, active.body);
    } catch (error) {
      if (abortController.signal.aborted) return;
      const current = this.getActive(path);
      if (!current && resumed) {
        try { resumed.session.close(); } catch {}
        try { await this.browser.chromium.closeTarget(resumed.target.id); } catch {}
        resumed = null;
      }
      if (current && ['STARTED', 'COMPLETED'].includes(current.serverStatus)) {
        current.publishPending = true;
        throw error;
      }
      const retryCount = Math.max(0, Number(body.resume_retry_count || 0)) + 1;
      const retryDelayMs = Math.max(5000, Number(this.config.resumeRetryMs || 30000));
      const retryAtMs = Date.now() + retryDelayMs;
      const next = {
        ...body,
        server_status: 'ERROR',
        server_error: String(error?.message || error).slice(0, 500),
        resume_retry_count: retryCount,
        resume_retry_at_ms: retryAtMs,
        updated_at_ms: Date.now(),
      };
      await transport.write(path, next).catch(() => {});
      await this.stateStore.patchIfCurrent(generation, signalId, {
        status: error?.code === 'AUTH_REQUIRED' ? 'AUTH_REQUIRED' : 'ERROR',
        lastError: next.server_error,
      }, 'DRIVE_DISPATCH_RESUME_ERROR');
      if (typeof this.stateStore.recordEvent === 'function') {
        await this.stateStore.recordEvent('DRIVE_DISPATCH_RESUME_RETRY_SCHEDULED', {
          task_id: identity.taskId,
          turn_id: identity.turnId,
          request_id: identity.requestId,
          retry_count: retryCount,
          retry_at_ms: retryAtMs,
          error: next.server_error,
        }, this.#dispatchEventContext(body, generation));
      }
    }
    } finally {
      await browserSlot.release();
    }
  }

  async send(path, rawBody, transport) {
    const body = validateDispatch(rawBody);
    const active = this.getActive(path);
    if (!active) {
      await transport.write(path, {
        ...body,
        server_status: 'ERROR',
        server_error: 'prepared browser session unavailable',
        updated_at_ms: Date.now(),
      });
      return;
    }
    if (active.serverStatus === 'STARTED' || active.serverStatus === 'COMPLETED') {
      if (clean(body.server_status) !== clean(active.body?.server_status)
          || canonicalConversationUrl(body.conversation_url)
            !== canonicalConversationUrl(active.body?.conversation_url)
          || active.publishPending) {
        await transport.write(path, active.body);
        active.publishPending = false;
      }
      return;
    }
    if (clean(body.client_status) !== 'SEND_REQUESTED') return;

    try {
      let probe;
      try {
        probe = await this.browser.submitPrepared({
          session: active.session,
          profileOperations: body.profile_operations,
          signal: active.abortController.signal,
        });
      } catch (error) {
        probe = await this.#recoverPreparedSend(active, body, error);
      }
      const conversationUrl = canonicalConversationUrl(probe.url);
      if (!conversationUrl) throw new Error('canonical conversation URL unavailable');

      active.serverStatus = 'STARTED';
      active.body = {
        ...body,
        server_status: 'STARTED',
        conversation_url: conversationUrl,
        server_generation: active.generation,
        started_at_ms: Date.now(),
        server_error: '',
        server_control_epoch: Math.max(0, Number(active.controlEpoch || 0)),
        server_control_state: clean(active.controlState) || 'UNKNOWN',
      };
      await this.stateStore.patchIfCurrent(
        active.generation,
        this.stateStore.snapshot().lastSignalId,
        {
          status: 'RUNNING',
          conversationUrl,
          lastActivityAt: new Date().toISOString(),
          lastError: null,
        },
        'DRIVE_DISPATCH_STARTED',
      );
      await this.#syncActiveSummary();
      void this.#monitor(active, transport);
      await transport.write(path, active.body);
    } catch (error) {
      if (active.abortController.signal.aborted) return;
      if (active.serverStatus === 'STARTED' || active.serverStatus === 'COMPLETED') {
        active.publishPending = true;
        throw error;
      }
      active.serverStatus = 'ERROR';
      active.body = {
        ...body,
        server_status: 'ERROR',
        server_error: String(error?.message || error).slice(0, 500),
        updated_at_ms: Date.now(),
      };
      await transport.write(path, active.body).catch(() => {});
      await this.stateStore.patchIfCurrent(
        active.generation,
        this.stateStore.snapshot().lastSignalId,
        { status: 'ERROR', lastError: active.body.server_error },
        'DRIVE_DISPATCH_SEND_ERROR',
      );
      await this.#closeActive(active);
    }
  }

  async cancel(path, rawBody, transport) {
    const body = validateDispatch(rawBody);
    const active = this.getActive(path);
    if (active) {
      await this.#closeActive(active);
    }
    if (!['CANCELLED', 'SUPERSEDED'].includes(clean(body.server_status))) {
      await transport.write(path, {
        ...body,
        server_status: 'CANCELLED',
        updated_at_ms: Date.now(),
      }).catch(() => {});
    }
  }

  async #resultCommitState(active, transport) {
    const documentId = clean(active?.body?.result_document_id);
    if (!documentId) return { state: 'UNAVAILABLE', reason: 'MISSING_DOCUMENT_ID' };
    try {
      const raw = await transport.readGoogleDocText(documentId);
      const text = String(raw || '').replace(/^\uFEFF/, '').trim();
      if (!text) return { state: 'UNAVAILABLE', reason: 'EMPTY_DOCUMENT' };
      try {
        const parsed = JSON.parse(text);
        const resultStatus = clean(parsed?.status);
        if (parsed?.committed === true) {
          return { state: 'COMMITTED', reason: 'JSON', resultStatus };
        }
        if (parsed?.committed === false) {
          return { state: 'NOT_COMMITTED', reason: 'JSON', resultStatus };
        }
        return { state: 'UNAVAILABLE', reason: 'COMMITTED_FIELD_MISSING' };
      } catch (error) {
        if (/"committed"\s*:\s*true/.test(text)) {
          return { state: 'COMMITTED', reason: 'TEXT_FALLBACK' };
        }
        if (/"committed"\s*:\s*false/.test(text)) {
          return { state: 'NOT_COMMITTED', reason: 'TEXT_FALLBACK' };
        }
        return { state: 'UNAVAILABLE', reason: 'PARSE_ERROR', error: errorMessage(error) };
      }
    } catch (error) {
      return { state: 'UNAVAILABLE', reason: 'READ_ERROR', error: errorMessage(error) };
    }
  }

  #controlAllowsRecovery(active) {
    const state = clean(active?.controlState) || 'UNKNOWN';
    return state === 'RUNNING'
      || (state === 'UNKNOWN' && this.config.allowUnknownControlRecovery === true);
  }

  #sameLivenessCursor(expected, current) {
    const expectedUser = clean(expected?.userMessageId);
    const currentUser = clean(current?.userMessageId);
    if (!expectedUser || !currentUser || expectedUser !== currentUser) return false;
    if (clean(expected?.assistantMessageId) !== clean(current?.assistantMessageId)) return false;
    if (clean(expected?.responseTurnId) !== clean(current?.responseTurnId)) return false;
    if (Number(expected?.responseTextLength || 0) !== Number(current?.responseTextLength || 0)) return false;
    return clean(expected?.responseFingerprint) === clean(current?.responseFingerprint);
  }

  #dispatchEventContext(body, generation = null) {
    const requestId = clean(body?.request_id);
    const attempt = Number(body?.dispatch_attempt);
    const candidateGeneration = Number(generation ?? body?.server_generation);
    return {
      generation: Number.isFinite(candidateGeneration)
        ? candidateGeneration
        : this.stateStore.snapshot().generation,
      signalId: requestId && Number.isFinite(attempt)
        ? `${requestId}:attempt:${attempt}`
        : null,
      turnId: clean(body?.turn_id) || null,
      conversationUrl: canonicalConversationUrl(body?.conversation_url) || null,
    };
  }

  #activeEventContext(active) {
    return this.#dispatchEventContext(active?.body, active?.generation);
  }

  async #recordLivenessEvent(active, event, details = {}) {
    if (typeof this.stateStore.recordEvent !== 'function') return;
    try {
      await this.stateStore.recordEvent(event, {
        task_id: active.identity.taskId,
        turn_id: active.identity.turnId,
        request_id: active.identity.requestId,
        control_state: clean(active.controlState) || 'UNKNOWN',
        control_epoch: Number(active.controlEpoch || 0),
        ...details,
      }, this.#activeEventContext(active));
    } catch {}
  }

  async #monitor(active, transport) {
    try {
      const result = await this.browser.monitor({
        session: active.session,
        baseline: active.baseline,
        signal: active.abortController.signal,
        livenessGate: async () => ({
          state: this.#controlAllowsRecovery(active) ? 'RUNNING' : active.controlState,
          epoch: active.controlEpoch,
        }),
        onActivity: async (activity) => {
          if (!this.#isActive(active)) return;
          await this.stateStore.patchIfCurrent(
            active.generation,
            this.stateStore.snapshot().lastSignalId,
            {
              status: activity.status,
              conversationUrl: this.stateStore.snapshot().conversationUrl,
              lastActivityAt: activity.lastActivityAt,
              lastError: activity.pageError || null,
            },
            activity.status === 'STALLED' && !activity.verificationRetry ? 'TURN_STALLED' : null,
          );
          if (activity.status === 'STALLED' && !active.recovering) {
            const stallAgeMs = Math.max(0, Date.now() - Date.parse(activity.lastActivityAt || 0));
            await this.#recordLivenessEvent(
              active,
              activity.verificationRetry ? 'LIVENESS_STALL_RECHECK' : 'LIVENESS_STALL_DETECTED',
              {
                stall_age_ms: stallAgeMs,
                last_activity_at: activity.lastActivityAt || null,
                cursor: cursorDetails(activity),
              },
            );

            if (!this.#controlAllowsRecovery(active)) {
              await this.#recordLivenessEvent(active, 'LIVENESS_RECOVERY_DECISION', {
                decision: 'SKIP_CONTROL_BLOCKED',
                reset_liveness: true,
              });
              return { resetLiveness: true };
            }

            const resultStartedAt = Date.now();
            const resultCheck = await this.#resultCommitState(active, transport);
            if (resultCheck.state === 'UNAVAILABLE') {
              active.resultReadFailureCount += 1;
              active.verificationFailureCount += 1;
            } else {
              active.resultReadFailureCount = 0;
            }
            await this.#recordLivenessEvent(active, 'LIVENESS_RESULT_CHECK', {
              result_state: resultCheck.state,
              reason: resultCheck.reason,
              error: resultCheck.error || null,
              duration_ms: Date.now() - resultStartedAt,
              failure_count: active.resultReadFailureCount,
            });

            if (resultCheck.state === 'COMMITTED') {
              active.verificationFailureCount = 0;
              await this.#recordLivenessEvent(active, 'LIVENESS_RECOVERY_DECISION', {
                decision: 'SKIP_RESULT_COMMITTED',
                reset_liveness: false,
              });
              const now = Date.now();
              active.serverStatus = 'COMPLETED';
              active.body = {
                ...active.body,
                server_status: 'COMPLETED',
                completion_source: 'RESULT_DOCUMENT',
                completed_at_ms: now,
                updated_at_ms: now,
                server_error: '',
              };
              try {
                await transport.write(active.path, active.body);
                active.publishPending = false;
              } catch {
                active.publishPending = true;
              }
              await this.stateStore.patchIfCurrent(
                active.generation,
                this.stateStore.snapshot().lastSignalId,
                {
                  status: 'COMPLETED',
                  lastActivityAt: new Date(now).toISOString(),
                  lastError: null,
                },
                'RESULT_COMMITTED_SUPPRESSED_RECOVERY',
              );
              await this.#closeActive(active);
              if (clean(resultCheck.resultStatus) === 'DONE') {
                this.controls.delete(active.identity.taskId);
              }
              return;
            }

            if (resultCheck.state === 'UNAVAILABLE') {
              await this.#recordLivenessEvent(active, 'LIVENESS_RECOVERY_DECISION', {
                decision: 'DEFER_RESULT_UNAVAILABLE',
                reason: resultCheck.reason,
                retry_after_ms: LIVENESS_VERIFY_RETRY_MS,
                reset_liveness: false,
                verification_failure_count: active.verificationFailureCount,
              });
              return { retryAfterMs: LIVENESS_VERIFY_RETRY_MS };
            }

            if (activity.paused) {
              active.verificationFailureCount = 0;
              await this.#recordLivenessEvent(active, 'LIVENESS_RECOVERY_DECISION', {
                decision: 'DEFER_PAUSED',
                reset_liveness: true,
              });
              return { resetLiveness: true };
            }

            let current;
            const cursorStartedAt = Date.now();
            try {
              current = await this.browser.livenessSnapshot({
                session: active.session,
                signal: active.abortController.signal,
              });
              active.cursorProbeFailureCount = 0;
            } catch (error) {
              active.cursorProbeFailureCount += 1;
              active.verificationFailureCount += 1;
              await this.#recordLivenessEvent(active, 'LIVENESS_CURSOR_RECHECK', {
                status: 'ERROR',
                error: errorMessage(error),
                duration_ms: Date.now() - cursorStartedAt,
                failure_count: active.cursorProbeFailureCount,
              });
              await this.#recordLivenessEvent(active, 'LIVENESS_RECOVERY_DECISION', {
                decision: 'DEFER_CURSOR_PROBE_ERROR',
                retry_after_ms: LIVENESS_VERIFY_RETRY_MS,
                reset_liveness: false,
                verification_failure_count: active.verificationFailureCount,
              });
              return { retryAfterMs: LIVENESS_VERIFY_RETRY_MS };
            }
            if (!this.#isActive(active)) return;

            const sameCursor = this.#sameLivenessCursor(activity, current);
            await this.#recordLivenessEvent(active, 'LIVENESS_CURSOR_RECHECK', {
              status: 'OK',
              duration_ms: Date.now() - cursorStartedAt,
              same_cursor: sameCursor,
              stalled_cursor: cursorDetails(activity),
              current_cursor: cursorDetails(current),
            });

            if (current.paused) {
              active.verificationFailureCount = 0;
              await this.#recordLivenessEvent(active, 'LIVENESS_RECOVERY_DECISION', {
                decision: 'DEFER_PAUSED',
                reset_liveness: true,
              });
              return { resetLiveness: true };
            }

            if (!sameCursor) {
              active.verificationFailureCount = 0;
              await this.#recordLivenessEvent(active, 'LIVENESS_RECOVERY_DECISION', {
                decision: 'DEFER_CURSOR_CHANGED',
                reset_liveness: true,
              });
              return { resetLiveness: true };
            }

            active.verificationFailureCount = 0;
            await this.#recordLivenessEvent(active, 'LIVENESS_RECOVERY_DECISION', {
              decision: 'SEND',
              reset_liveness: false,
              recovery_count: active.recoveryCount + 1,
            });

            active.recovering = true;
            try {
              active.recoveryCount += 1;
              active.body = {
                ...active.body,
                server_status: 'RECOVERY_SENDING',
                recovery_count: active.recoveryCount,
                updated_at_ms: Date.now(),
              };
              await this.#syncActiveSummary();
              await transport.write(active.path, active.body);
              const directives = await this.#directiveState();
              try {
                await this.browser.sendContinuation({
                  session: active.session,
                  prompt: directives.turnContinueDirective || this.config.recoveryPrompt,
                  profileOperations: active.body.profile_operations,
                  signal: active.abortController.signal,
                });
              } catch (error) {
                const message = String(error?.message || error);
                if (message === 'Continuation send control not ready: NO_SEND') {
                  active.recoveryCount = Math.max(0, active.recoveryCount - 1);
                  active.body = {
                    ...active.body,
                    server_status: 'STARTED',
                    server_error: '',
                    recovery_count: active.recoveryCount,
                    updated_at_ms: Date.now(),
                  };
                  await this.#syncActiveSummary();
                  await transport.write(active.path, active.body);
                  await this.#recordLivenessEvent(active, 'LIVENESS_RECOVERY_DECISION', {
                    decision: 'DEFER_SEND_CONTROL_NOT_READY',
                    reset_liveness: true,
                    error: message,
                  });
                  return { resetLiveness: true };
                }
                throw error;
              }
              active.body = {
                ...active.body,
                server_status: 'RECOVERY_SENT',
                recovery_count: active.recoveryCount,
                recovery_sent_at_ms: Date.now(),
              };
              await this.#syncActiveSummary();
              await transport.write(active.path, active.body);
              await this.#recordLivenessEvent(active, 'LIVENESS_RECOVERY_SENT', {
                recovery_count: active.recoveryCount,
              });
              return { resetLiveness: true };
            } finally {
              active.recovering = false;
            }
          }
        },
      });

      if (!this.#isActive(active)) return;
      if (result.status === 'PAGE_ERROR') {
        const pageError = clean(result?.probe?.errorText) || 'ChatGPT conversation page error';
        const running = clean(active.controlState) === 'RUNNING';
        const hasConversation = Boolean(canonicalConversationUrl(active.body?.conversation_url));
        const scheduleRetry = running && hasConversation;
        const retryCount = scheduleRetry
          ? Math.max(0, Number(active.body?.resume_retry_count || 0)) + 1
          : Math.max(0, Number(active.body?.resume_retry_count || 0));
        const retryDelayMs = Math.max(5000, Number(this.config.resumeRetryMs || 30000));
        const retryAtMs = scheduleRetry ? Date.now() + retryDelayMs : 0;
        active.serverStatus = 'ERROR';
        active.body = {
          ...active.body,
          server_status: 'ERROR',
          server_error: pageError,
          resume_retry_count: retryCount,
          resume_retry_at_ms: retryAtMs,
          updated_at_ms: Date.now(),
        };
        try {
          await transport.write(active.path, active.body);
          active.publishPending = false;
        } catch {
          active.publishPending = true;
        }
        if (typeof this.stateStore.recordEvent === 'function') {
          await this.stateStore.recordEvent(
            scheduleRetry
              ? 'DRIVE_DISPATCH_MONITOR_RETRY_SCHEDULED'
              : 'DRIVE_DISPATCH_MONITOR_PAGE_ERROR',
            {
              task_id: active.identity.taskId,
              turn_id: active.identity.turnId,
              request_id: active.identity.requestId,
              control_state: clean(active.controlState) || 'UNKNOWN',
              retry_count: retryCount,
              retry_at_ms: retryAtMs,
              error: pageError,
            },
            this.#activeEventContext(active),
          );
        }
        await this.#closeActive(active);
        return;
      }
      active.serverStatus = result.status;
      active.body = {
        ...active.body,
        server_status: result.status,
        completed_at_ms: result.status === 'COMPLETED' ? Date.now() : undefined,
        updated_at_ms: Date.now(),
      };
      try {
        await transport.write(active.path, active.body);
        active.publishPending = false;
      } catch {
        active.publishPending = true;
      }
      await this.#syncActiveSummary();
      if (result.status === 'COMPLETED') {
        await this.#closeActive(active);
      }
    } catch (error) {
      if (!this.#isActive(active)) return;
      active.serverStatus = 'ERROR';
      active.body = {
        ...active.body,
        server_status: 'ERROR',
        server_error: String(error?.message || error).slice(0, 500),
        updated_at_ms: Date.now(),
      };
      try {
        await transport.write(active.path, active.body);
        active.publishPending = false;
      } catch {
        active.publishPending = true;
      }
      await this.#detachActive(active);
    }
  }

  async #releasePredecessorActives(nextBody) {
    const taskId = clean(nextBody?.task_id);
    const turnId = clean(nextBody?.turn_id);
    const previousResultDocumentId = clean(nextBody?.previous_result_document_id);
    if (!taskId || !turnId || !previousResultDocumentId) return;

    const predecessors = this.activeDispatches().filter((active) =>
      active.identity.taskId === taskId
      && active.identity.turnId !== turnId
      && clean(active.body?.result_document_id) === previousResultDocumentId);

    for (const active of predecessors) {
      const eventContext = this.#activeEventContext(active);
      const details = {
        task_id: taskId,
        predecessor_turn_id: active.identity.turnId,
        successor_turn_id: turnId,
        predecessor_result_document_id: previousResultDocumentId,
        target_id: active.target?.id || null,
      };
      await this.#closeActive(active);
      if (typeof this.stateStore.recordEvent === 'function') {
        await this.stateStore.recordEvent('PREDECESSOR_BROWSER_RELEASED', details, eventContext);
      }
    }
  }

  async #supersede(nextPath, nextBody, transport) {
    const requestId = clean(nextBody?.request_id);
    if (!requestId) return;

    for (const [pendingPath, pending] of this.pendingPrepares.entries()) {
      if (pendingPath === nextPath || pending.identity.requestId !== requestId) continue;
      pending.abortController.abort(new Error('superseded by newer retry of the same Drive request'));
      this.pendingPrepares.delete(pendingPath);
      const next = {
        ...pending.body,
        server_status: 'SUPERSEDED',
        updated_at_ms: Date.now(),
      };
      try { await pending.transport?.write?.(pendingPath, next); } catch {}
    }

    for (const active of this.activeDispatches()) {
      if (active.path === nextPath || active.identity.requestId !== requestId) continue;
      active.abortController.abort(new Error('superseded by newer retry of the same Drive request'));
      active.body = {
        ...active.body,
        server_status: 'SUPERSEDED',
        updated_at_ms: Date.now(),
      };
      await transport.write(active.path, active.body).catch(() => {});
      await this.#closeActive(active);
    }
  }

  async #releaseActive(expected = this.active, { closeTarget = true } = {}) {
    const active = expected;
    if (!active || this.actives.get(active.path) !== active) return;
    active.abortController.abort(new Error(
      closeTarget ? 'Drive dispatch closed' : 'Drive dispatch detached',
    ));
    try { active.session.close(); } catch {}
    if (closeTarget) {
      try { await this.browser.chromium.closeTarget(active.target.id); } catch {}
    }
    this.actives.delete(active.path);
    if (this.active === active) {
      const remaining = this.activeDispatches();
      this.active = remaining.length ? remaining[remaining.length - 1] : null;
    }
    await this.#syncActiveSummary();
    await this.#browserTopologyChanged();
  }

  async #detachActive(expected = this.active) {
    await this.#releaseActive(expected, { closeTarget: false });
  }

  async #closeActive(expected = this.active) {
    await this.#releaseActive(expected, { closeTarget: true });
  }
}
