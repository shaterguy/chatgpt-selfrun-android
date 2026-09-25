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
        await this.scanOnce();
      } catch (error) {
        // The next scan retries after the normal interval. No dispatch is guessed.
      }
      await delay(this.config.drivePollMs);
    }
  }

  stop() {
    this.running = false;
  }

  async scanOnce() {
    const files = await this.transport.list();
    for (const file of files) {
      const fingerprint = `${file.modTime}|${file.size}`;
      if (this.seen.get(file.path) === fingerprint) continue;

      let body;
      try {
        body = await this.transport.read(file.path);
      } catch {
        continue;
      }

      this.seen.set(file.path, fingerprint);
      if (body?.schema !== 'selfrun-server-dispatch-v1') continue;

      const clientStatus = String(body.client_status || '').trim();
      const serverStatus = String(body.server_status || 'PENDING').trim();

      if (clientStatus === 'CANCELLED' || clientStatus === 'SUPERSEDED') {
        await this.controller.cancel(file.path, body, this.transport);
        continue;
      }

      if (clientStatus === 'CREATE_REQUESTED' && serverStatus === 'PENDING') {
        await this.controller.prepare(file.path, body, this.transport);
        continue;
      }

      if (clientStatus === 'SEND_REQUESTED'
          && (serverStatus === 'READY_TO_SUBMIT' || serverStatus === 'RECOVERY_SENT')) {
        await this.controller.send(file.path, body, this.transport);
      }
    }
  }
}
