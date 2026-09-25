const DISPATCH_SCHEMA = 'selfrun-server-dispatch-v1';

function clean(value) {
  return typeof value === 'string' ? value.trim() : '';
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

function canonicalConversationUrl(url) {
  try {
    const parsed = new URL(url);
    const match = parsed.pathname.match(/\/c\/([A-Za-z0-9-]+)/);
    if (!match) return '';
    return `https://chatgpt.com/c/${match[1]}`;
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
  }

  async prepare(path, rawBody, transport) {
    const body = validateDispatch(rawBody);
    if (clean(body.client_status) !== 'CREATE_REQUESTED') return;
    if (this.active?.path === path && ['READY_TO_SUBMIT', 'STARTED', 'COMPLETED'].includes(this.active.serverStatus)) {
      return;
    }

    await this.#supersede(path, transport);
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

      this.active = {
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
        recovering: false,
      };

      const next = {
        ...body,
        server_status: 'READY_TO_SUBMIT',
        server_generation: generation,
        prepared_at_ms: Date.now(),
        server_error: '',
      };
      this.active.body = next;
      await transport.write(path, next);
      await this.stateStore.patchIfCurrent(generation, this.stateStore.snapshot().lastSignalId, {
        status: 'READY_TO_SUBMIT',
        activeTargetId: prepared.target.id,
        lastActivityAt: new Date().toISOString(),
      }, 'DRIVE_DISPATCH_READY');
    } catch (error) {
      if (abortController.signal.aborted) return;
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

  async send(path, rawBody, transport) {
    const body = validateDispatch(rawBody);
    const active = this.active;
    if (!active || active.path !== path) {
      await transport.write(path, {
        ...body,
        server_status: 'ERROR',
        server_error: 'prepared browser session unavailable',
        updated_at_ms: Date.now(),
      });
      return;
    }
    if (active.serverStatus === 'STARTED' || active.serverStatus === 'COMPLETED') return;
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
      await transport.write(path, active.body);
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
    } catch (error) {
      if (active.abortController.signal.aborted) return;
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
    if (this.active?.path === path) {
      await this.#closeActive();
    }
    if (!['CANCELLED', 'SUPERSEDED'].includes(clean(body.server_status))) {
      await transport.write(path, {
        ...body,
        server_status: 'CANCELLED',
        updated_at_ms: Date.now(),
      }).catch(() => {});
    }
  }

  async #monitor(active, transport) {
    try {
      const result = await this.browser.monitor({
        session: active.session,
        baseline: active.baseline,
        signal: active.abortController.signal,
        onActivity: async (activity) => {
          if (this.active !== active || active.abortController.signal.aborted) return;
          await this.stateStore.patchIfCurrent(
            active.generation,
            this.stateStore.snapshot().lastSignalId,
            {
              status: activity.status,
              conversationUrl: this.stateStore.snapshot().conversationUrl,
              lastActivityAt: activity.lastActivityAt,
              lastError: activity.pageError || null,
            },
            activity.status === 'STALLED' ? 'TURN_STALLED' : null,
          );
          if (activity.status === 'STALLED' && !active.recovering) {
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
            } finally {
              active.recovering = false;
            }
          }
        },
      });

      if (this.active !== active || active.abortController.signal.aborted) return;
      active.serverStatus = result.status;
      active.body = {
        ...active.body,
        server_status: result.status,
        completed_at_ms: result.status === 'COMPLETED' ? Date.now() : undefined,
        updated_at_ms: Date.now(),
      };
      await transport.write(active.path, active.body).catch(() => {});
    } catch (error) {
      if (active.abortController.signal.aborted || this.active !== active) return;
      active.serverStatus = 'ERROR';
      active.body = {
        ...active.body,
        server_status: 'ERROR',
        server_error: String(error?.message || error).slice(0, 500),
        updated_at_ms: Date.now(),
      };
      await transport.write(active.path, active.body).catch(() => {});
    }
  }

  async #supersede(nextPath, transport) {
    const active = this.active;
    if (!active || active.path === nextPath) return;
    active.abortController.abort(new Error('superseded by newer Drive dispatch'));
    active.body = {
      ...active.body,
      server_status: 'SUPERSEDED',
      updated_at_ms: Date.now(),
    };
    await transport.write(active.path, active.body).catch(() => {});
    await this.#closeActive(active);
  }

  async #closeActive(expected = this.active) {
    const active = expected;
    if (!active) return;
    active.abortController.abort(new Error('Drive dispatch closed'));
    try { active.session.close(); } catch {}
    try { await this.browser.chromium.closeTarget(active.target.id); } catch {}
    if (this.active === active) this.active = null;
  }
}
