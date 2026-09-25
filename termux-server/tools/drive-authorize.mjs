import http from 'node:http';
import fs from 'node:fs/promises';
import crypto from 'node:crypto';
import { config } from '../src/config.mjs';

const client = JSON.parse(await fs.readFile(config.driveAuthClientFile, 'utf8'));
if (!client?.client_id) throw new Error('drive-auth-client.json client_id missing');

const host = '127.0.0.1';
const port = Number(process.env.SELFRUN_DRIVE_AUTH_PORT || 53684);
const redirectUri = `http://${host}:${port}`;
const state = crypto.randomBytes(24).toString('base64url');
const verifier = crypto.randomBytes(48).toString('base64url');
const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');

const authUrl = new URL('https://accounts.google.com/o/oauth2/v2/auth');
authUrl.search = new URLSearchParams({
  client_id: client.client_id,
  redirect_uri: redirectUri,
  response_type: 'code',
  scope: 'https://www.googleapis.com/auth/drive',
  access_type: 'offline',
  prompt: 'consent',
  state,
  code_challenge: challenge,
  code_challenge_method: 'S256',
}).toString();

let finish;
const done = new Promise((resolve, reject) => { finish = { resolve, reject }; });

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', redirectUri);
    if (url.pathname !== '/') {
      res.writeHead(404); res.end('not found'); return;
    }
    if (url.searchParams.get('state') !== state) throw new Error('OAuth state mismatch');
    const oauthError = url.searchParams.get('error');
    if (oauthError) throw new Error('OAuth denied: ' + oauthError);
    const code = url.searchParams.get('code');
    if (!code) throw new Error('OAuth code missing');

    const tokenParams = new URLSearchParams({
      client_id: client.client_id,
      code,
      code_verifier: verifier,
      redirect_uri: redirectUri,
      grant_type: 'authorization_code',
    });
    if (client.client_secret) tokenParams.set('client_secret', client.client_secret);
    const tokenResponse = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: tokenParams,
    });
    const token = await tokenResponse.json().catch(() => ({}));
    if (!tokenResponse.ok || !token.access_token) {
      const code = String(token?.error || 'unknown_error');
      const description = String(token?.error_description || '');
      throw new Error('OAuth token exchange failed: ' + tokenResponse.status
        + ' ' + code + (description ? ' - ' + description : ''));
    }
    if (!token.refresh_token) throw new Error('OAuth refresh_token missing; revoke prior grant and retry');

    const stored = {
      access_token: token.access_token,
      refresh_token: token.refresh_token,
      token_type: token.token_type || 'Bearer',
      scope: token.scope || '',
      expires_at_ms: Date.now() + Number(token.expires_in || 3600) * 1000,
    };
    const temp = config.driveAuthTokenFile + '.tmp-' + process.pid;
    await fs.writeFile(temp, JSON.stringify(stored, null, 2) + '\n', { mode: 0o600 });
    await fs.rename(temp, config.driveAuthTokenFile);

    const probe = await fetch('https://www.googleapis.com/drive/v3/about?fields=storageQuota', {
      headers: { authorization: 'Bearer ' + stored.access_token },
    });
    if (!probe.ok) throw new Error('Drive API verification failed: ' + probe.status);

    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('SelfRun Drive authorization completed. You can close this page.');
    finish.resolve();
  } catch (error) {
    res.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(String(error?.message || error));
    finish.reject(error);
  }
});

server.listen(port, host, () => {
  console.log('AUTH_URL=' + authUrl.toString());
  console.log('WAITING_FOR_GOOGLE_OAUTH_CALLBACK');
});

try {
  await Promise.race([
    done,
    new Promise((_, reject) => setTimeout(() => reject(new Error('OAuth authorization timed out')), 10 * 60 * 1000)),
  ]);
  console.log('DRIVE_AUTH_OK');
} finally {
  server.close();
}
