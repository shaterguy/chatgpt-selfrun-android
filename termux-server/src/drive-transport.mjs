import { GoogleOAuthTokenProvider } from './google-oauth.mjs';

const MAX_JSON_BYTES = 2 * 1024 * 1024;
const API = 'https://www.googleapis.com/drive/v3';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';

function safeName(value) {
  const text = String(value || '').replace(/\\/g, '/').replace(/^\/+/, '');
  if (!text || text.includes('..') || text.includes('/')) throw new Error('unsafe Drive dispatch name');
  return text;
}

export class DriveDispatchTransport {
  constructor(config) {
    this.config = config;
    this.folderId = config.driveRunsFolderId;
    this.ids = new Map();
    this.blockedUntil = 0;
    this.auth = new GoogleOAuthTokenProvider(config);
  }

  async #token() {
    return this.auth.accessToken();
  }

  async #fetch(url, init = {}, retry = true) {
    const now = Date.now();
    if (now < this.blockedUntil) {
      throw new Error('Drive API rate limited until ' + new Date(this.blockedUntil).toISOString());
    }
    const access = await this.#token();
    const response = await fetch(url, {
      ...init,
      headers: { ...(init.headers || {}), authorization: 'Bearer ' + access },
    });
    if (response.status === 401 && retry) {
      await this.auth.forceRefresh();
      return this.#fetch(url, init, false);
    }
    if (response.status === 403 || response.status === 429) {
      const detail = await response.clone().text().catch(() => '');
      if (/rateLimitExceeded|Quota exceeded|RESOURCE_EXHAUSTED/i.test(detail)) {
        this.blockedUntil = Date.now() + 60000;
      }
    }
    return response;
  }

  async list() {
    const q = "'" + this.folderId + "' in parents and trashed=false and "
      + "(name contains '__SELFRUN_DISPATCH__' or name contains '__SELFRUN_CONTROL__')";
    const url = API + '/files?' + new URLSearchParams({
      q,
      spaces: 'drive',
      fields: 'files(id,name,modifiedTime,size,mimeType,parents)',
      pageSize: '1000',
      orderBy: 'modifiedTime',
    });
    const response = await this.#fetch(url);
    const text = await response.text();
    if (!response.ok) throw new Error('Drive list HTTP ' + response.status + ': ' + text.slice(0, 500));
    const body = JSON.parse(text || '{}');
    const files = Array.isArray(body.files) ? body.files : [];
    const out = [];
    for (const file of files) {
      const name = String(file.name || '');
      const supported = name.startsWith('__SELFRUN_DISPATCH__')
        || name.startsWith('__SELFRUN_CONTROL__');
      if (!supported || !name.endsWith('.json')) continue;
      this.ids.set(name, String(file.id || ''));
      out.push({ path: name, modTime: String(file.modifiedTime || ''), size: Number(file.size || 0) });
    }
    return out.sort((a, b) => a.modTime.localeCompare(b.modTime));
  }

  async #id(relativePath) {
    const name = safeName(relativePath);
    const cached = this.ids.get(name);
    if (cached) return cached;
    const esc = name.replace(/'/g, "\\'");
    const q = "'" + this.folderId + "' in parents and trashed=false and name='" + esc + "'";
    const url = API + '/files?' + new URLSearchParams({ q, spaces: 'drive', fields: 'files(id,name)', pageSize: '10' });
    const response = await this.#fetch(url);
    const text = await response.text();
    if (!response.ok) throw new Error('Drive resolve HTTP ' + response.status + ': ' + text.slice(0, 500));
    const files = JSON.parse(text || '{}').files || [];
    const exact = files.find((f) => f.name === name);
    if (!exact?.id) throw new Error('Drive dispatch file not found: ' + name);
    this.ids.set(name, exact.id);
    return exact.id;
  }

  async read(relativePath) {
    const id = await this.#id(relativePath);
    const response = await this.#fetch(API + '/files/' + encodeURIComponent(id) + '?alt=media');
    const raw = await response.text();
    if (!response.ok) throw new Error('Drive read HTTP ' + response.status + ': ' + raw.slice(0, 500));
    if (Buffer.byteLength(raw, 'utf8') > MAX_JSON_BYTES) throw new Error('dispatch JSON too large');
    const body = JSON.parse(raw);
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('Drive dispatch body must be an object');
    return body;
  }

  async write(relativePath, body) {
    const id = await this.#id(relativePath);
    const data = JSON.stringify(body, null, 2) + '\n';
    if (Buffer.byteLength(data, 'utf8') > MAX_JSON_BYTES) throw new Error('dispatch JSON too large');
    const response = await this.#fetch(
      UPLOAD + '/files/' + encodeURIComponent(id) + '?uploadType=media',
      { method: 'PATCH', headers: { 'content-type': 'application/json; charset=utf-8' }, body: data },
    );
    const text = await response.text();
    if (!response.ok) throw new Error('Drive write HTTP ' + response.status + ': ' + text.slice(0, 500));
  }

  async readGoogleDocText(documentId) {
    const id = String(documentId || '').trim();
    if (!id) throw new Error('Google Doc id required');
    const url = API + '/files/' + encodeURIComponent(id) + '/export?'
      + new URLSearchParams({ mimeType: 'text/plain' });
    const response = await this.#fetch(url);
    const text = await response.text();
    if (!response.ok) {
      throw new Error('Drive export HTTP ' + response.status + ': ' + text.slice(0, 500));
    }
    return text;
  }

  async close() { }
}
