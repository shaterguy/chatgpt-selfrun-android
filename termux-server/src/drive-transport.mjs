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

const TASK_FOLDER_RE = /^SR-\d{8}-\d{6}-[A-Z0-9]+$/;
const CONTROL_TASK_RE = /^__SELFRUN_CONTROL__(SR-\d{8}-\d{6}-[A-Z0-9]+)\.json$/;
const DISPATCH_TASK_RE = /^__SELFRUN_DISPATCH__(SR-\d{8}-\d{6}-[A-Z0-9]+)(?::|__|\.)/;

function taskIdFromName(name) {
  const control = String(name || '').match(CONTROL_TASK_RE);
  if (control) return control[1];
  const dispatch = String(name || '').match(DISPATCH_TASK_RE);
  return dispatch ? dispatch[1] : '';
}

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
      try {await this.auth.forceRefresh(access);} catch (error) {throw transportError(operation, 'auth', 401, error);}
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

  async #listAll(operation, params) {
    const files = [];
    let pageToken = '';
    do {
      const query = new URLSearchParams({...params,...(pageToken?{pageToken}:{})});
      const response = await this.#fetch(operation, API + '/files?' + query);
      const text = await this.#text(operation, response);
      const body = parseJson(operation, text || '{}', response.status);
      if (!body || typeof body !== 'object' || !Array.isArray(body.files) || body.files.includes(null)) {
        throw transportError(operation, 'parse', response.status);
      }
      files.push(...body.files);
      pageToken = typeof body.nextPageToken === 'string' ? body.nextPageToken : '';
    } while (pageToken);
    return files;
  }

  async #taskFolders(operation='list') {
    const files = await this.#listAll(operation, {
      q: "'" + this.folderId + "' in parents and trashed=false and mimeType='application/vnd.google-apps.folder'",
      spaces: 'drive',
      fields: 'nextPageToken,files(id,name)',
      pageSize: '1000',
    });
    const folders = new Map();
    for (const file of files) {
      const name = String(file.name || '');
      if (TASK_FOLDER_RE.test(name) && file.id) folders.set(name, String(file.id));
    }
    return folders;
  }

  async #moveRootFile(id, targetFolderId) {
    const query = new URLSearchParams({
      addParents: targetFolderId,
      removeParents: this.folderId,
      fields: 'id,parents',
    });
    const response = await this.#fetch(
      'move',
      API + '/files/' + encodeURIComponent(id) + '?' + query,
      {method:'PATCH',headers:{'content-type':'application/json; charset=utf-8'},body:'{}'},
    );
    await this.#text('move', response);
  }

  async list() {
    const taskFolders = await this.#taskFolders();
    const allowedParents = new Set([this.folderId, ...taskFolders.values()]);
    const files = await this.#listAll('list', {
      q: "trashed=false and mimeType='application/json' and "
        + "(name contains '__SELFRUN_DISPATCH__' or name contains '__SELFRUN_CONTROL__')",
      spaces: 'drive',
      fields: 'nextPageToken,files(id,name,modifiedTime,size,mimeType,parents)',
      pageSize: '1000',
      orderBy: 'modifiedTime desc',
    });
    const out = [];
    for (const file of files) {
      const name = String(file.name || '');
      const supported = name.startsWith('__SELFRUN_DISPATCH__')
        || name.startsWith('__SELFRUN_CONTROL__');
      if (!supported || !name.endsWith('.json')) continue;
      const parents = Array.isArray(file.parents) ? file.parents.map(String) : [];
      if (!parents.some(parent => allowedParents.has(parent))) continue;
      const taskFolderId = taskFolders.get(taskIdFromName(name));
      if (taskFolderId && parents.includes(this.folderId) && !parents.includes(taskFolderId) && file.id) {
        try {
          await this.#moveRootFile(String(file.id), taskFolderId);
        } catch (error) {
          console.error(JSON.stringify({event:'DRIVE_TASK_DOCUMENT_MOVE_FAILED',file_id:String(file.id),code:String(error?.code||'ERROR')}));
        }
      }
      this.ids.set(name, String(file.id || ''));
      out.push({ path: name, modTime: String(file.modifiedTime || ''), size: Number(file.size || 0) });
    }
    return out.sort((a, b) => b.modTime.localeCompare(a.modTime));
  }

  async #id(relativePath) {
    const name = safeName(relativePath);
    const cached = this.ids.get(name);
    if (cached) return cached;
    const taskFolders = await this.#taskFolders('resolve');
    const allowedParents = new Set([this.folderId, ...taskFolders.values()]);
    const esc = name.replace(/'/g, "\\'");
    const files = await this.#listAll('resolve', {
      q: "trashed=false and name='" + esc + "'",
      spaces: 'drive',
      fields: 'nextPageToken,files(id,name,parents)',
      pageSize: '10',
    });
    const exact = files.filter((file) => file.name === name
      && Array.isArray(file.parents)
      && file.parents.some((parent) => allowedParents.has(String(parent))));
    if (!exact.length) throw Object.assign(new Error('Drive dispatch file not found'),{code:'DRIVE_FILE_NOT_FOUND'});
    if (exact.length !== 1) throw new Error('multiple Drive dispatch files found');
    this.ids.set(name, String(exact[0].id));
    return String(exact[0].id);
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
