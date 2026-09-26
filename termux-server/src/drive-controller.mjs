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
  constructor({ browser, stateStore, config }) {
    this.browser = browser;
    this.stateStore = stateStore;
    this.config = config;
    this.active = null;
    this.actives = new Map();
    this.controls = new Map();
  }

  getActive(path) {
    return this.actives.get(path) || null;
  }

  activeDispatches() {
    return [...this.actives.values()];
  }

  #isActive(active) {
    return !!active
      && this.actives.get(active.path) === active
      && !active.abortController.signal.aborted;
  }

  async #syncActiveSummary() {
    const rows = this.activeDispatches().map((active) => ({
      path: active.path,
      task_id: active.identity.taskId,
      turn_id: active.identity.turnId,
      request_id: active.identity.requestId,
      generation: active.generation,
      server_status: active.serverStatus,
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

  async #resumeFromControl(control, transport) {
    if (typeof transport?.list !== 'function' || typeof transport?.read !== 'function') return;
    const files = await transport.list();
    const candidates = [];
    for (const file of files) {
      if (!String(file.path || '').startsWith('__SELFRUN_DISPATCH__')) continue;
      let body;
      try {
        body = await transport.read(file.path);
      } catch {
        continue;
      }
      if (body?.schema !== DISPATCH_SCHEMA
          || clean(body.task_id) !== control.task_id
          || clean(body.turn_id) !== clean(control.turn_id)
          || clean(body.request_id) !== clean(control.request_id)) continue;
      candidates.push({ path: file.path, body });
    }
    candidates.sort((a, b) => Number(b.body.dispatch_attempt || 0) - Number(a.body.dispatch_attempt || 0));
    const candidate = candidates[0];
    if (!candidate || this.getActive(candidate.path)) return;
    const body = candidate.body;
    if (clean(body.client_status) !== 'SEND_REQUESTED') return;
    if (!['STARTED', 'RECOVERY_SENT', 'SUPERSEDED'].includes(clean(body.server_status))) return;
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
      }).catch(() => {});
      await this.stateStore.recordEvent('DRIVE_DISPATCH_REATTACH_SKIPPED_COMMITTED', {
        task_id: control.task_id,
        turn_id: clean(control.turn_id),
        request_id: clean(control.request_id),
      });
      return;
    }

    await this.stateStore.recordEvent('DRIVE_DISPATCH_REATTACH_REQUESTED', {
      task_id: control.task_id,
      turn_id: clean(control.turn_id),
      request_id: clean(control.request_id),
      previous_server_status: clean(body.server_status),
    });
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

    await this.#supersede(path, body, transport);
    const generation = this.stateStore.snapshot().generation + 1;
    const abortController = new AbortController();
    const identity = {
      taskId: body.task_id,
      turnId: body.turn_id,
      requestId: body.request_id,
      attempt: Number(body.dispatch_attempt),
    };

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

    try {
      const prepared = await this.browser.prepare({
        projectUrl: body.project_url,
        prompt: body.prompt,
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
      const current = this.getActive(path);
      if (current?.serverStatus === 'READY_TO_SUBMIT') {
        current.publishPending = true;
        throw error;
      }
      const next = {
        ...body,
        server_status: 'ERROR',
        server_error: String(error?.message || error).slice(0, 500),
        server_generation: generation,
        updated_at_ms: Date.now(),
      };
      await transport.write(path, next).catch(() => {});
      await this.stateStore.patchIfCurrent(generation, this.stateStore.snapshot().lastSignalId, {
        status: error?.code === 'AUTH_REQUIRED' ? 'AUTH_REQUIRED' : 'ERROR',
        lastError: next.server_error,
      }, 'DRIVE_DISPATCH_ERROR');
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

    try {
      const resumed = await this.browser.resume({
        conversationUrl,
        signal: abortController.signal,
      });
      if (abortController.signal.aborted) {
        throw abortController.signal.reason || new Error('superseded');
      }
      const control = this.controls.get(identity.taskId);
      const active = {
        path,
        body: { ...body },
        identity,
        generation,
        abortController,
        target: resumed.target,
        session: resumed.session,
        baseline: resumed.baseline,
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
      };
      await this.#registerActive(active);
      await this.stateStore.patchIfCurrent(generation, signalId, {
        status: 'RUNNING',
        activeTargetId: resumed.target.id,
        conversationUrl,
        lastActivityAt: new Date().toISOString(),
        lastError: null,
      }, 'DRIVE_DISPATCH_RESUMED');
      void this.#monitor(active, transport);
      await transport.write(path, active.body);
    } catch (error) {
      if (abortController.signal.aborted) return;
      const current = this.getActive(path);
      if (current && ['STARTED', 'COMPLETED'].includes(current.serverStatus)) {
        current.publishPending = true;
        throw error;
      }
      const next = {
        ...body,
        server_status: 'ERROR',
        server_error: String(error?.message || error).slice(0, 500),
        updated_at_ms: Date.now(),
      };
      await transport.write(path, next).catch(() => {});
      await this.stateStore.patchIfCurrent(generation, signalId, {
        status: error?.code === 'AUTH_REQUIRED' ? 'AUTH_REQUIRED' : 'ERROR',
        lastError: next.server_error,
      }, 'DRIVE_DISPATCH_RESUME_ERROR');
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
      const probe = await this.browser.submitPrepared({
        session: active.session,
        profileOperations: body.profile_operations,
        signal: active.abortController.signal,
      });
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
        if (parsed?.committed === true) return { state: 'COMMITTED', reason: 'JSON' };
        if (parsed?.committed === false) return { state: 'NOT_COMMITTED', reason: 'JSON' };
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
    return clean(expected?.assistantMessageId) === clean(current?.assistantMessageId);
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
      });
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
              active.abortController.abort(new Error('result committed before recovery'));
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
              await transport.write(active.path, active.body);
              await this.browser.sendContinuation({
                session: active.session,
                prompt: this.config.recoveryPrompt,
                profileOperations: active.body.profile_operations,
                signal: active.abortController.signal,
              });
              active.body = {
                ...active.body,
                server_status: 'RECOVERY_SENT',
                recovery_count: active.recoveryCount,
                recovery_sent_at_ms: Date.now(),
              };
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
    }
  }

  async #supersede(nextPath, nextBody, transport) {
    const requestId = clean(nextBody?.request_id);
    if (!requestId) return;
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

  async #closeActive(expected = this.active) {
    const active = expected;
    if (!active || this.actives.get(active.path) !== active) return;
    active.abortController.abort(new Error('Drive dispatch closed'));
    try { active.session.close(); } catch {}
    try { await this.browser.chromium.closeTarget(active.target.id); } catch {}
    this.actives.delete(active.path);
    if (this.active === active) {
      const remaining = this.activeDispatches();
      this.active = remaining.length ? remaining[remaining.length - 1] : null;
    }
    await this.#syncActiveSummary();
  }
}
