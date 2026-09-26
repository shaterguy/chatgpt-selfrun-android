const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export class DriveDispatchWatcher {
  constructor({ transport, controller, config }) {
    this.transport = transport;
    this.controller = controller;
    this.config = config;
    this.running = false;
    this.seen = new Map();
  }

  async start() {
    if (this.running) return;
    this.running = true;
    while (this.running) {
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
        await this.scanOnce();
      } catch (error) {
        console.error(JSON.stringify({
          event: 'DRIVE_WATCH_ERROR',
          error: String(error?.message || error),
        }));
      }
      const delayActives = typeof this.controller.activeDispatches === 'function'
        ? this.controller.activeDispatches()
        : (this.controller.active ? [this.controller.active] : []);
      const fastActive = delayActives.some((active) =>
        active.serverStatus === 'READY_TO_SUBMIT' || active.serverStatus === 'RECOVERY_SENT');
      const nextDelay = fastActive ? 10000 : this.config.drivePollMs;
      await delay(nextDelay);
    }
  }

  stop() {
    this.running = false;
  }

  async scanOnce() {
    const files = await this.transport.list();
    const orderedFiles = [...files].sort((a, b) => {
      const aControl = String(a.path || '').startsWith('__SELFRUN_CONTROL__') ? 0 : 1;
      const bControl = String(b.path || '').startsWith('__SELFRUN_CONTROL__') ? 0 : 1;
      if (aControl !== bControl) return aControl - bControl;
      return String(a.modTime || '').localeCompare(String(b.modTime || ''));
    });
    for (const file of orderedFiles) {
      const fingerprint = `${file.modTime}|${file.size}`;
      const pathActive = typeof this.controller.getActive === 'function'
        ? this.controller.getActive(file.path)
        : (this.controller.active?.path === file.path ? this.controller.active : null);
      const publishPending = !!pathActive?.publishPending;
      if (this.seen.get(file.path) === fingerprint && !publishPending) continue;

      let body;
      try {
        body = await this.transport.read(file.path);
      } catch {
        continue;
      }

      if (body?.schema === 'selfrun-task-control-v1') {
        await this.controller.control(file.path, body, this.transport);
        this.seen.set(file.path, fingerprint);
        continue;
      }

      if (body?.schema !== 'selfrun-server-dispatch-v1') {
        this.seen.set(file.path, fingerprint);
        continue;
      }

      const clientStatus = String(body.client_status || '').trim();
      const serverStatus = String(body.server_status || 'PENDING').trim();
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
