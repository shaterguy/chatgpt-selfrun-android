import { GoogleOAuthTokenProvider } from './google-oauth.mjs';

const MAX_JSON_BYTES = 2 * 1024 * 1024;
const API = 'https://www.googleapis.com/drive/v3';
const VERSION_API = 'https://www.googleapis.com/drive/v2';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v2';

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
    this.etags = new WeakMap();
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
      signal:init.signal||AbortSignal.timeout(30000),
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
      orderBy: 'modifiedTime desc',
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
    return out.sort((a, b) => b.modTime.localeCompare(a.modTime));
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
    if (!exact?.id) throw Object.assign(new Error('Drive dispatch file not found: ' + name),{code:'DRIVE_FILE_NOT_FOUND'});
    this.ids.set(name, exact.id);
    return exact.id;
  }

  async #version(id) {
    const response=await this.#fetch(VERSION_API+'/files/'+encodeURIComponent(id)+'?fields=id,etag,version');
    if(!response.ok)throw new Error('Drive version HTTP '+response.status);
    const metadata=await response.json();
    if(!metadata.etag)throw new Error('Drive version missing ETag');
    return metadata.etag;
  }
  async read(relativePath) {
    const id=await this.#id(relativePath);
    // Bind the body to an unchanged file version; media responses omit ETags.
    for(let attempt=0;attempt<3;attempt++) {
      const before=await this.#version(id);
      const response=await this.#fetch(API+'/files/'+encodeURIComponent(id)+'?alt=media');
      const raw=await response.text();
      if(!response.ok)throw new Error('Drive read HTTP '+response.status);
      if(Buffer.byteLength(raw,'utf8')>MAX_JSON_BYTES)throw new Error('dispatch JSON too large');
      const after=await this.#version(id);
      if(before!==after)continue;
      const body=JSON.parse(raw);
      if(!body||typeof body!=='object'||Array.isArray(body))throw new Error('Drive dispatch body must be an object');
      this.etags.set(body,after);return body;
    }
    throw new Error('Drive read revision changed repeatedly');
  }

  async write(relativePath, body, options={}) {
    const id = await this.#id(relativePath);
    const expected=options.expected?this.etags.get(options.expected):null;
    if(options.expected&&!expected)throw new Error('Drive conditional publication requires an ETag');
    const data = JSON.stringify(body, null, 2) + '\n';
    if (Buffer.byteLength(data, 'utf8') > MAX_JSON_BYTES) throw new Error('dispatch JSON too large');
    const response = await this.#fetch(
      UPLOAD + '/files/' + encodeURIComponent(id) + '?uploadType=media',
      { method: 'PUT', headers: { 'content-type': 'application/json; charset=utf-8',...(expected?{'if-match':expected}:{}) }, body: data },
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
