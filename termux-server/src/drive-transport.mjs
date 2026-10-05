import { GoogleOAuthTokenProvider } from './google-oauth.mjs';

const MAX_JSON_BYTES = 2 * 1024 * 1024;
const API = 'https://www.googleapis.com/drive/v3';
const VERSION_API = 'https://www.googleapis.com/drive/v2';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v2';
const NETWORK_CODES = new Set([
  'ENOTFOUND', 'EAI_AGAIN', 'ECONNRESET', 'ECONNREFUSED', 'ETIMEDOUT',
  'ENETUNREACH', 'EHOSTUNREACH', 'EPIPE', 'UND_ERR_CONNECT_TIMEOUT',
  'UND_ERR_HEADERS_TIMEOUT', 'UND_ERR_BODY_TIMEOUT', 'UND_ERR_SOCKET',
]);

function transportError(operation, stage, status = null, cause = null) {
  const http_status = Number.isInteger(status) && status >= 100 && status <= 599 ? status : null;
  let cause_code = null;
  for (let error = cause, depth = 0; error && depth < 5; error = error.cause, depth++) {
    if (NETWORK_CODES.has(error.code)) {cause_code = error.code; break;}
  }
  // Never retain a raw exception, URL, auth token, or response body as a cause.
  const message = 'Drive ' + operation + (http_status === null ? '' : ' HTTP ' + http_status)
    + ' ' + stage + ' failed' + (cause_code ? ' (' + cause_code + ')' : '');
  return Object.assign(new Error(message), {code: 'DRIVE_TRANSPORT_ERROR', operation, stage, http_status, cause_code});
}

function parseJson(operation, text, status) {
  try {return JSON.parse(text);} catch (error) {throw transportError(operation, 'parse', status, error);}
}

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

  async #fetch(operation, url, init = {}, retry = true) {
    const now = Date.now();
    if (now < this.blockedUntil) {
      throw transportError(operation, 'rate_limit');
    }
    let access;
    try {access = await this.#token();} catch (error) {throw transportError(operation, 'auth', null, error);}
    let response;
    try {
      response = await fetch(url, {
        ...init,
        signal:init.signal||AbortSignal.timeout(30000),
        headers: { ...(init.headers || {}), authorization: 'Bearer ' + access },
      });
    } catch (error) {throw transportError(operation, 'request', null, error);}
    if (response.status === 401 && retry) {
      try {await this.auth.forceRefresh();} catch (error) {throw transportError(operation, 'auth', 401, error);}
      return this.#fetch(operation, url, init, false);
    }
    if (response.status === 403 || response.status === 429) {
      let detail = '';
      try {detail = await response.clone().text();} catch { /* Keep quota detection best-effort. */ }
      if (/rateLimitExceeded|Quota exceeded|RESOURCE_EXHAUSTED/i.test(detail)) {
        this.blockedUntil = Date.now() + 60000;
      }
    }
    if (!response.ok) {
      await this.#text(operation, response);
      const stage = response.status === 401 ? 'auth' : response.status === 429 ? 'rate_limit' : 'response';
      throw transportError(operation, stage, response.status);
    }
    return response;
  }

  async #text(operation, response) {
    try {return await response.text();} catch (error) {throw transportError(operation, 'body', response.status, error);}
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
    const response = await this.#fetch('list', url);
    const text = await this.#text('list', response);
    const body = parseJson('list', text || '{}', response.status);
    if (!body || typeof body !== 'object') throw transportError('list', 'parse', response.status);
    const files = Array.isArray(body.files) ? body.files : [];
    const out = [];
    for (const file of files) {
      if (file === null) throw transportError('list', 'parse', response.status);
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
    const response = await this.#fetch('resolve', url);
    const text = await this.#text('resolve', response);
    const body = parseJson('resolve', text || '{}', response.status);
    if (!body || typeof body !== 'object') throw transportError('resolve', 'parse', response.status);
    const files = body.files || [];
    if (!Array.isArray(files) || files.includes(null)) throw transportError('resolve', 'parse', response.status);
    const exact = files.find((f) => f.name === name);
    if (!exact?.id) throw Object.assign(new Error('Drive dispatch file not found'),{code:'DRIVE_FILE_NOT_FOUND'});
    this.ids.set(name, exact.id);
    return exact.id;
  }

  async #version(id) {
    const response=await this.#fetch('version', VERSION_API+'/files/'+encodeURIComponent(id)+'?fields=id,etag,version');
    const metadata=parseJson('version', await this.#text('version', response), response.status);
    if(!metadata?.etag)throw transportError('version', 'parse', response.status);
    return metadata.etag;
  }
  async read(relativePath) {
    const id=await this.#id(relativePath);
    // Bind the body to an unchanged file version; media responses omit ETags.
    for(let attempt=0;attempt<3;attempt++) {
      const before=await this.#version(id);
      const response=await this.#fetch('read', API+'/files/'+encodeURIComponent(id)+'?alt=media');
      const raw=await this.#text('read', response);
      if(Buffer.byteLength(raw,'utf8')>MAX_JSON_BYTES)throw transportError('read', 'body', response.status);
      const after=await this.#version(id);
      if(before!==after)continue;
      const body=parseJson('read', raw, response.status);
      if(!body||typeof body!=='object'||Array.isArray(body))throw transportError('read', 'parse', response.status);
      this.etags.set(body,after);return body;
    }
    throw new Error('Drive read revision changed repeatedly');
  }

  async write(relativePath, body, options={}) {
    const id = await this.#id(relativePath);
    const expected=options.expected?this.etags.get(options.expected):null;
    if(options.expected&&!expected)throw new Error('Drive conditional publication requires an ETag');
    let data;
    try {data = JSON.stringify(body, null, 2) + '\n';} catch (error) {throw transportError('write', 'parse', null, error);}
    if (Buffer.byteLength(data, 'utf8') > MAX_JSON_BYTES) throw transportError('write', 'body');
    const response = await this.#fetch(
      'write',
      UPLOAD + '/files/' + encodeURIComponent(id) + '?uploadType=media',
      { method: 'PUT', headers: { 'content-type': 'application/json; charset=utf-8',...(expected?{'if-match':expected}:{}) }, body: data },
    );
    await this.#text('write', response);
  }

  async readGoogleDocText(documentId) {
    const id = String(documentId || '').trim();
    if (!id) throw new Error('Google Doc id required');
    const url = API + '/files/' + encodeURIComponent(id) + '/export?'
      + new URLSearchParams({ mimeType: 'text/plain' });
    const response = await this.#fetch('export', url);
    const text = await this.#text('export', response);
    return text;
  }

  async close() { }
}
