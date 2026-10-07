import http from 'node:http';
import fs from 'node:fs/promises';
import crypto from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { config } from '../src/config.mjs';

const OAUTH_ERRORS = new Set([
  'access_denied', 'invalid_request', 'invalid_client', 'invalid_grant',
  'unauthorized_client', 'unsupported_grant_type', 'invalid_scope',
  'server_error', 'temporarily_unavailable', 'disallowed_useragent',
  'admin_policy_enforced', 'org_internal',
]);
class AuthorizationError extends Error {}
const failure = code => new AuthorizationError(code);
const safeError = error => error instanceof AuthorizationError ? error.message : 'authorization_failed';
const oauthError = code => OAUTH_ERRORS.has(code) ? code : 'unknown_error';

async function main() {
  // The installed writer shares the service's lock and rotates generation IDs.
  // Fail closed before authorization if that writer is not installed.
  const library = process.env.TERMUX_GOOGLE_AUTH_LIBRARY
    || path.join(os.homedir(), '.local/lib/termux-google-auth/store.mjs');
  if (!path.isAbsolute(library)) throw failure('shared_store_unavailable');
  let writeAuthorizedToken;
  try {
    ({writeAuthorizedToken} = await import(pathToFileURL(library).href));
    if (typeof writeAuthorizedToken !== 'function') throw failure('shared_store_unavailable');
  } catch { throw failure('shared_store_unavailable'); }

  let client;
  try { client = JSON.parse(await fs.readFile(config.driveAuthClientFile, 'utf8')); }
  catch { throw failure('oauth_client_unavailable'); }
  if (!client?.client_id) throw failure('oauth_client_id_missing');

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
      if (url.searchParams.get('state') !== state) throw failure('oauth_state_mismatch');
      const denied = url.searchParams.get('error');
      if (denied) throw failure('oauth_denied ' + oauthError(denied));
      const code = url.searchParams.get('code');
      if (!code) throw failure('oauth_code_missing');

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
        throw failure('oauth_token_exchange_failed ' + tokenResponse.status + ' ' + oauthError(token?.error));
      }
      if (!token.refresh_token) throw failure('oauth_refresh_token_missing');

      const stored = {
        access_token: token.access_token,
        refresh_token: token.refresh_token,
        token_type: token.token_type || 'Bearer',
        scope: token.scope || '',
        expires_at_ms: Date.now() + Number(token.expires_in || 3600) * 1000,
      };
      await writeAuthorizedToken(config.driveAuthTokenFile, stored);

      const probe = await fetch('https://www.googleapis.com/drive/v3/about?fields=storageQuota', {
        headers: { authorization: 'Bearer ' + stored.access_token },
      });
      if (!probe.ok) throw failure('drive_api_verification_failed ' + probe.status);

      res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('SelfRun Drive authorization completed. You can close this page.');
      finish.resolve();
    } catch (error) {
      const safe = safeError(error);
      res.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
      res.end(safe);
      finish.reject(failure(safe));
    }
  });
  server.once('error', () => finish.reject(failure('oauth_callback_unavailable')));
  server.listen(port, host, () => {
    console.log('AUTH_URL=' + authUrl.toString());
    console.log('WAITING_FOR_GOOGLE_OAUTH_CALLBACK');
  });

  let timeout;
  try {
    await Promise.race([
      done,
      new Promise((_, reject) => {
        timeout = setTimeout(() => reject(failure('oauth_authorization_timed_out')), 10 * 60 * 1000);
      }),
    ]);
    console.log('DRIVE_AUTH_OK');
  } finally {
    clearTimeout(timeout);
    server.close();
  }
}

await main().catch(error => {
  console.error('DRIVE_AUTH_FAILED ' + safeError(error));
  process.exitCode = 1;
});
