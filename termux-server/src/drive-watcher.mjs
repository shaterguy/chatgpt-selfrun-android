const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const CONTROL_PREFIX = '__SELFRUN_CONTROL__';
const TERMINAL_CONTROL_STATES = new Set(['STOPPED', 'DONE']);

const clean = (value) => String(value || '').trim();
const controlPath = (taskId) => `${CONTROL_PREFIX}${clean(taskId)}.json`;
const terminalControl = (control) => TERMINAL_CONTROL_STATES.has(clean(control?.state));

export class DriveDispatchWatcher {
  constructor({ transport, controller, config, canDispatch = null }) {
    this.transport = transport;
    this.controller = controller;
    this.config = config;
    this.canDispatch = typeof canDispatch === 'function' ? canDispatch : () => true;
    this.running = false;
    this.seen = new Map();
    this.resumeRetryAt = new Map();
  }

  #retryEntry(path) {
    const value = this.resumeRetryAt.get(path);
    if (value && typeof value === 'object') return value;
    const at = Number(value || 0);
    return at > 0 ? { at, taskId: '' } : null;
  }

  #clearTaskRetries(taskId) {
    const normalized = clean(taskId);
    for (const [path, entry] of this.resumeRetryAt.entries()) {
      if (clean(this.#retryEntry(path)?.taskId) === normalized) this.resumeRetryAt.delete(path);
    }
  }

  async start() {
    if (this.running) return;
    this.running = true;
    let nextFullScanAt = 0;
    while (this.running) {
      if (!this.canDispatch()) {
        await delay(Math.min(5000, Math.max(1000, Number(this.config.drivePollMs || 1000))));
        continue;
      }
      try {
        const actives = typeof this.controller.activeDispatches === 'function'
          ? this.controller.activeDispatches()
          : (this.controller.active ? [this.controller.active] : []);
        for (const active of actives) {
          if (!active?.publishPending || !active.path || !active.body) continue;
          try {
            await this.transport.write(active.path, active.body);
            active.publishPending = false;
          } catch {}
        }
        const preparedActive = actives.some((active) => active?.serverStatus === 'READY_TO_SUBMIT');
        if (preparedActive) await this.#scanPreparedActives(actives);
        if (!this.running) break;
        if (!preparedActive || Date.now() >= nextFullScanAt) {
          await this.scanOnce();
          nextFullScanAt = Date.now() + Math.max(1000, Number(this.config.drivePollMs || 1000));
        }
      } catch (error) {
        console.error(JSON.stringify({
          event: 'DRIVE_WATCH_ERROR',
          error: String(error?.message || error),
        }));
      }
      if (!this.running) break;
      const delayActives = typeof this.controller.activeDispatches === 'function'
        ? this.controller.activeDispatches()
        : (this.controller.active ? [this.controller.active] : []);
      const preparedActives = delayActives.filter((active) => active?.serverStatus === 'READY_TO_SUBMIT');
      const preparedActive = preparedActives.length > 0;
      const preparedFast = preparedActives.some((active) => {
        const preparedAt = Number(active?.body?.prepared_at_ms || 0);
        return preparedAt <= 0 || Date.now() - preparedAt < 15000;
      });
      const recoveryActive = delayActives.some((active) => active?.serverStatus === 'RECOVERY_SENT');
      const nextDelay = preparedActive
        ? preparedFast
          ? Math.max(500, Number(this.config.preparedPollMs || 1000))
          : Math.min(5000, Math.max(1000, Number(this.config.drivePollMs || 1000)))
        : recoveryActive
          ? Math.min(10000, Math.max(1000, Number(this.config.drivePollMs || 1000)))
          : Math.max(1000, Number(this.config.drivePollMs || 1000));
      await delay(nextDelay);
    }
  }

  async #scanPreparedActives(actives) {
    for (const active of actives) {
      if (!active?.path || active.serverStatus !== 'READY_TO_SUBMIT') continue;
      let body;
      try {
        body = await this.transport.read(active.path);
      } catch {
        continue;
      }
      if (body?.schema !== 'selfrun-server-dispatch-v1') continue;
      const clientStatus = clean(body.client_status);
      if (clientStatus === 'CANCELLED' || clientStatus === 'SUPERSEDED') {
        await this.controller.cancel(active.path, body, this.transport);
        continue;
      }
      if (clientStatus === 'SEND_REQUESTED') {
        await this.controller.send(active.path, body, this.transport);
      }
    }
  }

  stop() {
    this.running = false;
  }

  async scanOnce() {
    if (!this.canDispatch()) return;
    const files = await this.transport.list();
    const filesByPath = new Map(files.map((file) => [String(file.path || ''), file]));
    for (const path of this.resumeRetryAt.keys()) {
      if (!filesByPath.has(path)) this.resumeRetryAt.delete(path);
    }
    const scanControls = new Map();
    const blockedTasks = new Set();
    const orderedFiles = [...files].sort((a, b) => {
      const aControl = String(a.path || '').startsWith('__SELFRUN_CONTROL__') ? 0 : 1;
      const bControl = String(b.path || '').startsWith('__SELFRUN_CONTROL__') ? 0 : 1;
      if (aControl !== bControl) return aControl - bControl;
      if (aControl === 0) return String(b.modTime || '').localeCompare(String(a.modTime || ''));
      return String(a.modTime || '').localeCompare(String(b.modTime || ''));
    });
    for (const file of orderedFiles) {
      const fingerprint = `${file.modTime}|${file.size}`;
      const pathActive = typeof this.controller.getActive === 'function'
        ? this.controller.getActive(file.path)
        : (this.controller.active?.path === file.path ? this.controller.active : null);
      const publishPending = !!pathActive?.publishPending;
      const scheduledRetryAt = Number(this.#retryEntry(file.path)?.at || 0);
      const scheduledRetryDue = scheduledRetryAt > 0 && Date.now() >= scheduledRetryAt;
      if (this.seen.get(file.path) === fingerprint && !publishPending && !scheduledRetryDue) continue;

      let body;
      try {
        body = await this.transport.read(file.path);
      } catch {
        continue;
      }

      if (body?.schema === 'selfrun-task-control-v1') {
        const taskId = clean(body.task_id);
        if (taskId) {
          scanControls.set(taskId, body);
          if (terminalControl(body)) {
            blockedTasks.add(taskId);
            this.#clearTaskRetries(taskId);
          }
        }
        await this.controller.control(file.path, body, this.transport);
        this.seen.set(file.path, fingerprint);
        continue;
      }

      if (body?.schema !== 'selfrun-server-dispatch-v1') {
        this.seen.set(file.path, fingerprint);
        continue;
      }

      const taskId = clean(body.task_id);
      let taskControl = scanControls.get(taskId) || null;
      if (!taskControl && taskId && typeof this.controller.controlForTask === 'function') {
        taskControl = this.controller.controlForTask(taskId);
      }
      if (!taskControl && taskId) {
        const controlFile = filesByPath.get(controlPath(taskId));
        if (controlFile) {
          try {
            const authority = await this.transport.read(controlFile.path);
            if (authority?.schema === 'selfrun-task-control-v1'
                && clean(authority.task_id) === taskId) {
              taskControl = authority;
              scanControls.set(taskId, authority);
              if (terminalControl(authority)) blockedTasks.add(taskId);
            }
          } catch {
            continue;
          }
        }
      }
      if (blockedTasks.has(taskId) || terminalControl(taskControl)) {
        this.resumeRetryAt.delete(file.path);
        this.seen.set(file.path, fingerprint);
        continue;
      }
      if (clean(taskControl?.state) === 'RUNNING'
          && (clean(body.turn_id) !== clean(taskControl?.turn_id)
            || clean(body.request_id) !== clean(taskControl?.request_id))) {
        this.resumeRetryAt.delete(file.path);
        this.seen.set(file.path, fingerprint);
        continue;
      }

      const clientStatus = String(body.client_status || '').trim();
      const serverStatus = String(body.server_status || 'PENDING').trim();
      let resumeRetryAt = Number(body.resume_retry_at_ms || 0);
      if (serverStatus === 'ERROR'
          && clean(taskControl?.state) === 'RUNNING'
          && resumeRetryAt <= 0
          && clean(body.conversation_url)) {
        resumeRetryAt = Date.now() + Math.max(5000, Number(this.config.resumeRetryMs || 30000));
        body = { ...body, resume_retry_at_ms: resumeRetryAt, updated_at_ms: Date.now() };
        await this.transport.write(file.path, body);
      }
      if (serverStatus === 'ERROR' && resumeRetryAt > 0) {
        this.resumeRetryAt.set(file.path, { at: resumeRetryAt, taskId });
      } else {
        this.resumeRetryAt.delete(file.path);
      }
      const createdAt = Number(body.created_at_ms || 0);
      const now = Date.now();
      const fresh = createdAt > 0 && now >= createdAt
        && now - createdAt <= this.config.dispatchFreshMs;
      const activityAt = Math.max(
        createdAt,
        Number(body.updated_at_ms || 0),
        Number(body.started_at_ms || 0),
        Number(body.recovery_sent_at_ms || 0),
      );
      const recoveryMs = Number(this.config.dispatchRecoveryMs || this.config.dispatchFreshMs);
      const recoverable = activityAt > 0 && now >= activityAt && now - activityAt <= recoveryMs;

      if (clientStatus === 'CANCELLED' || clientStatus === 'SUPERSEDED') {
        await this.controller.cancel(file.path, body, this.transport);
        this.seen.set(file.path, fingerprint);
        continue;
      }

      if (clientStatus === 'CREATE_REQUESTED' && serverStatus === 'PENDING' && fresh) {
        await this.controller.prepare(file.path, body, this.transport);
        this.seen.set(file.path, fingerprint);
        continue;
      }

      if (clientStatus === 'SEND_REQUESTED'
          && serverStatus === 'ERROR'
          && clean(taskControl?.state) === 'RUNNING'
          && resumeRetryAt > 0) {
        if (now >= resumeRetryAt) {
          if (typeof this.controller.retryResumeFromControl === 'function') {
            await this.controller.retryResumeFromControl(taskControl, this.transport);
          } else {
            await this.controller.resume(file.path, body, this.transport);
          }
          this.resumeRetryAt.delete(file.path);
        }
        this.seen.set(file.path, fingerprint);
        continue;
      }

      if (clientStatus === 'SEND_REQUESTED' && recoverable
          && (serverStatus === 'STARTED' || serverStatus === 'RECOVERY_SENT')) {
        await this.controller.resume(file.path, body, this.transport);
        this.seen.set(file.path, fingerprint);
        continue;
      }

      if (clientStatus === 'SEND_REQUESTED' && fresh
          && serverStatus === 'READY_TO_SUBMIT') {
        await this.controller.send(file.path, body, this.transport);
      }
      this.seen.set(file.path, fingerprint);
    }
  }
}
