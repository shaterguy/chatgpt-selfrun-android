import fs from 'node:fs/promises';

export class GoogleOAuthTokenProvider {
  constructor(config) {
    this.config = config;
    this.refreshing = null;
  }

  async #readJson(file) {
    const raw = await fs.readFile(file, 'utf8');
    return JSON.parse(raw);
  }

  async #client() {
    const client = await this.#readJson(this.config.driveAuthClientFile);
    if (!client?.client_id) throw new Error('Google OAuth client_id missing');
    return client;
  }

  async #tokenRecord() {
    const token = await this.#readJson(this.config.driveAuthTokenFile);
    if (!token?.refresh_token && !token?.access_token) {
      throw new Error('Google OAuth token missing; run authorization');
    }
    return token;
  }

  async accessToken() {
    let token = await this.#tokenRecord();
    const expiresAt = Number(token.expires_at_ms || 0);
    if (token.access_token && expiresAt - Date.now() > 300000) return token.access_token;
    token = await this.#refresh(token);
    return token.access_token;
  }

  async forceRefresh() {
    const token = await this.#tokenRecord();
    const refreshed = await this.#refresh(token);
    return refreshed.access_token;
  }

  async #refresh(token) {
    if (this.refreshing) return this.refreshing;
    this.refreshing = this.#doRefresh(token).finally(() => { this.refreshing = null; });
    return this.refreshing;
  }

  async #doRefresh(token) {
    if (!token.refresh_token) throw new Error('Google OAuth refresh_token missing');
    const client = await this.#client();
    const params = new URLSearchParams({
      client_id: client.client_id,
      refresh_token: token.refresh_token,
      grant_type: 'refresh_token',
    });
    if (client.client_secret) params.set('client_secret', client.client_secret);
    const response = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: params,
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || !body.access_token) {
      throw new Error('Google OAuth refresh failed: ' + response.status);
    }
    const next = {
      ...token,
      access_token: body.access_token,
      token_type: body.token_type || token.token_type || 'Bearer',
      scope: body.scope || token.scope || '',
      expires_at_ms: Date.now() + Number(body.expires_in || 3600) * 1000,
    };
    const temp = this.config.driveAuthTokenFile + '.tmp-' + process.pid;
    await fs.writeFile(temp, JSON.stringify(next, null, 2) + '\n', { mode: 0o600 });
    await fs.rename(temp, this.config.driveAuthTokenFile);
    return next;
  }
}
