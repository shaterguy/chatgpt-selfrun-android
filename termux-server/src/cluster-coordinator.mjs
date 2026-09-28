import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { GoogleOAuthTokenProvider } from './google-oauth.mjs';

const API = 'https://www.googleapis.com/drive/v3';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';
const MEMBER_PREFIX = '__SELFRUN_SERVER__';
const CLUSTER_FILE = '__SELFRUN_CLUSTER__.json';
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function clean(value) {
  return String(value || '').trim();
}

function safeServerKey(value) {
  return clean(value).replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'unknown';
}

function readText(file) {
  try {
    return fs.readFileSync(file, 'utf8').trim();
  } catch {
    return '';
  }
}

export function resolveSelfRunServerIdentity(env = process.env, home = os.homedir()) {
  const nameFile = env.SELFRUN_SERVER_NAME_FILE || path.join(home, 'SERVER_NAME');
  const priorityFile = env.SELFRUN_SERVER_PRIORITY_FILE || path.join(home, 'SERVER_PRIORITY');
  const serverName = clean(env.SELFRUN_SERVER_NAME) || readText(nameFile) || os.hostname();

  const envPriority = Number(env.SELFRUN_SERVER_PRIORITY || '');
  const filePriority = Number(readText(priorityFile));
  let serverPriority = 100;
  if (Number.isFinite(envPriority) && envPriority > 0) {
    serverPriority = Math.floor(envPriority);
  } else if (Number.isFinite(filePriority) && filePriority > 0) {
    serverPriority = Math.floor(filePriority);
  } else if (serverName === 'Galaxy-Tab-S11-Ultra') {
    serverPriority = 1;
  } else if (serverName === 'Galaxy-S25') {
    serverPriority = 2;
  }
  return { serverName, serverPriority, nameFile, priorityFile };
}

function stateTimestampMs(state) {
  const value = Date.parse(clean(state?.updated_at || state?.heartbeat_at));
  return Number.isFinite(value) ? value : 0;
}

class SelfRunClusterTransport {
  constructor(config) {
    this.folderId = config.driveRunsFolderId;
    this.auth = new GoogleOAuthTokenProvider(config);
    this.ids = new Map();
  }

  async #token() {
    return this.auth.accessToken();
  }

  async #fetch(url, init = {}, retry = true) {
    const access = await this.#token();
    const response = await fetch(url, {
      ...init,
      headers: { ...(init.headers || {}), authorization: 'Bearer ' + access },
    });
    if (response.status === 401 && retry) {
      await this.auth.forceRefresh();
      return this.#fetch(url, init, false);
    }
    return response;
  }

  async listMemberStates() {
    const q = "'" + this.folderId + "' in parents and trashed=false and name contains '" + MEMBER_PREFIX + "'";
    const response = await this.#fetch(API + '/files?' + new URLSearchParams({
      q,
      spaces: 'drive',
      fields: 'files(id,name,modifiedTime,size)',
      pageSize: '1000',
      orderBy: 'modifiedTime desc',
    }));
    const raw = await response.text();
    if (!response.ok) throw new Error('SelfRun cluster list HTTP ' + response.status + ': ' + raw.slice(0, 300));
    const files = JSON.parse(raw || '{}').files || [];
    const out = [];
    for (const file of files) {
      const name = clean(file.name);
      if (!name.startsWith(MEMBER_PREFIX) || !name.endsWith('.json')) continue;
      try {
        const read = await this.#fetch(API + '/files/' + encodeURIComponent(file.id) + '?alt=media');
        const text = await read.text();
        if (!read.ok) continue;
        const body = JSON.parse(text);
        if (body && typeof body === 'object' && !Array.isArray(body)) out.push(body);
        this.ids.set(name, clean(file.id));
      } catch {}
    }
    return out;
  }

  async #resolveId(name) {
    const cached = this.ids.get(name);
    if (cached) return cached;
    const esc = name.replace(/'/g, "\\'");
    const q = "'" + this.folderId + "' in parents and trashed=false and name='" + esc + "'";
    const response = await this.#fetch(API + '/files?' + new URLSearchParams({
      q,
      spaces: 'drive',
      fields: 'files(id,name,modifiedTime)',
      pageSize: '10',
      orderBy: 'modifiedTime desc',
    }));
    const raw = await response.text();
    if (!response.ok) throw new Error('SelfRun cluster resolve HTTP ' + response.status + ': ' + raw.slice(0, 300));
    const exact = (JSON.parse(raw || '{}').files || []).find((file) => file.name === name);
    if (exact?.id) {
      this.ids.set(name, clean(exact.id));
      return clean(exact.id);
    }

    const create = await this.#fetch(API + '/files?fields=id,name', {
      method: 'POST',
      headers: { 'content-type': 'application/json; charset=utf-8' },
      body: JSON.stringify({
        name,
        parents: [this.folderId],
        mimeType: 'application/json',
      }),
    });
    const createdRaw = await create.text();
    if (!create.ok) throw new Error('SelfRun cluster create HTTP ' + create.status + ': ' + createdRaw.slice(0, 300));
    const id = clean(JSON.parse(createdRaw || '{}').id);
    if (!id) throw new Error('SelfRun cluster create returned no file id');
    this.ids.set(name, id);
    return id;
  }

  async upsertJson(name, body) {
    const id = await this.#resolveId(name);
    const data = JSON.stringify(body, null, 2) + '\n';
    const response = await this.#fetch(
      UPLOAD + '/files/' + encodeURIComponent(id) + '?uploadType=media',
      {
        method: 'PATCH',
        headers: { 'content-type': 'application/json; charset=utf-8' },
        body: data,
      },
    );
    const raw = await response.text();
    if (!response.ok) throw new Error('SelfRun cluster write HTTP ' + response.status + ': ' + raw.slice(0, 300));
  }
}

export class SelfRunClusterCoordinator {
  constructor({
    stateStore,
    config,
    transport = null,
    serverName = null,
    serverPriority = null,
    memberTimeoutMs = null,
    settleMs = null,
    heartbeatMs = null,
    now = () => Date.now(),
    sleep = delay,
  }) {
    const identity = resolveSelfRunServerIdentity();
    this.stateStore = stateStore;
    this.config = config;
    this.transport = transport || new SelfRunClusterTransport(config);
    this.serverName = clean(serverName) || identity.serverName;
    this.serverPriority = Number.isFinite(Number(serverPriority)) && Number(serverPriority) > 0
      ? Math.floor(Number(serverPriority))
      : identity.serverPriority;
    this.memberTimeoutMs = Math.max(15000, Number(memberTimeoutMs ?? process.env.SELFRUN_CLUSTER_MEMBER_TIMEOUT_MS ?? 65000));
    this.settleMs = Math.max(0, Number(settleMs ?? process.env.SELFRUN_CLUSTER_SETTLE_MS ?? 10000));
    this.heartbeatMs = Math.max(2000, Number(heartbeatMs ?? process.env.SELFRUN_CLUSTER_HEARTBEAT_MS ?? 5000));
    this.now = now;
    this.sleep = sleep;
    const started = this.now();
    this.role = 'CANDIDATE';
    this.activeServer = null;
    this.activePriority = null;
    this.settleUntil = started + this.settleMs;
    this.claimReadyAt = started + this.settleMs;
    this.lastElectionAt = null;
    this.lastTransitionAt = null;
    this.running = false;
    this.loopPromise = null;
  }

  memberFileName() {
    return MEMBER_PREFIX + safeServerKey(this.serverName) + '.json';
  }

  #isFresh(state, now = this.now()) {
    if (!state) return false;
    if (clean(state.status).toUpperCase() === 'STOPPING') return false;
    const updated = stateTimestampMs(state);
    return updated > 0 && now - updated <= this.memberTimeoutMs;
  }

  canDispatch(now = this.now()) {
    return this.role === 'ACTIVE' && now >= Number(this.claimReadyAt || 0);
  }

  snapshot() {
    return {
      server_name: this.serverName,
      server_priority: this.serverPriority,
      role: this.role,
      active_server: this.activeServer,
      active_priority: this.activePriority,
      member_timeout_ms: this.memberTimeoutMs,
      settle_ms: this.settleMs,
      claim_ready_at: new Date(this.claimReadyAt || 0).toISOString(),
      claim_ready: this.canDispatch(),
      last_election_at: this.lastElectionAt,
      last_transition_at: this.lastTransitionAt,
    };
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.loopPromise = this.#run();
  }

  async #run() {
    while (this.running) {
      try {
        await this.refresh();
      } catch (error) {
        console.error(JSON.stringify({
          event: 'SELFRUN_CLUSTER_ERROR',
          server_name: this.serverName,
          error: String(error?.message || error),
        }));
      }
      if (this.running) await this.sleep(this.heartbeatMs);
    }
  }

  async refresh() {
    const now = this.now();
    const members = {};
    for (const state of await this.transport.listMemberStates()) {
      const name = clean(state?.server_name || state?.host);
      if (!name) continue;
      const previous = members[name];
      if (!previous || stateTimestampMs(state) > stateTimestampMs(previous)) members[name] = state;
    }

    const localState = this.stateStore.snapshot();
    members[this.serverName] = {
      ...(members[this.serverName] || {}),
      schema: 'selfrun-server-state-v1',
      component: this.config.component,
      version_scope: this.config.versionScope,
      version: this.config.version,
      server_version: this.config.version,
      version_label: this.config.versionLabel,
      server_name: this.serverName,
      host: this.serverName,
      server_priority: this.serverPriority,
      role: this.role,
      active_count: Number(localState.activeCount || 0),
      status: clean(localState.status) || 'IDLE',
      updated_at: new Date(now).toISOString(),
      heartbeat_at: new Date(now).toISOString(),
    };

    const candidates = Object.values(members)
      .filter((state) => this.#isFresh(state, now))
      .map((state) => ({
        name: clean(state.server_name || state.host),
        priority: Number(state.server_priority || 100),
        activeCount: Number(state.active_count || 0),
        role: clean(state.role).toUpperCase(),
        state,
      }))
      .filter((entry) => entry.name)
      .sort((a, b) => a.priority - b.priority || a.name.localeCompare(b.name));

    const busyIncumbents = candidates
      .filter((entry) => (entry.role === 'ACTIVE' || entry.role === 'DRAINING') && entry.activeCount > 0);
    const winner = (busyIncumbents[0] || candidates[0] || {
      name: this.serverName,
      priority: this.serverPriority,
    });

    const previousRole = this.role;
    const previousActive = this.activeServer;
    this.activeServer = winner.name;
    this.activePriority = winner.priority;
    this.lastElectionAt = new Date(now).toISOString();

    if (now < this.settleUntil) {
      this.role = 'CANDIDATE';
    } else {
      this.role = winner.name === this.serverName ? 'ACTIVE' : 'STANDBY';
    }

    if (this.role === 'ACTIVE' && previousRole !== 'ACTIVE') {
      this.claimReadyAt = previousRole === 'CANDIDATE'
        ? Math.max(now, this.settleUntil)
        : now + this.settleMs;
    }
    if (this.role !== previousRole || this.activeServer !== previousActive) {
      this.lastTransitionAt = new Date(now).toISOString();
      console.log(JSON.stringify({
        event: 'SELFRUN_CLUSTER_ROLE',
        server_name: this.serverName,
        role: this.role,
        active_server: this.activeServer,
        active_priority: this.activePriority,
        active_count: Number(localState.activeCount || 0),
      }));
    }

    const memberBody = {
      schema: 'selfrun-server-state-v1',
      component: this.config.component,
      version_scope: this.config.versionScope,
      version: this.config.version,
      server_version: this.config.version,
      version_label: this.config.versionLabel,
      server_name: this.serverName,
      host: this.serverName,
      server_priority: this.serverPriority,
      role: this.role,
      active_server: this.activeServer,
      active_priority: this.activePriority,
      active_count: Number(localState.activeCount || 0),
      status: clean(localState.status) || 'IDLE',
      updated_at: new Date(now).toISOString(),
      heartbeat_at: new Date(now).toISOString(),
      claim_ready_at: new Date(this.claimReadyAt || 0).toISOString(),
      claim_ready: this.canDispatch(now),
    };
    await this.transport.upsertJson(this.memberFileName(), memberBody);

    if (this.role === 'ACTIVE') {
      await this.transport.upsertJson(CLUSTER_FILE, {
        schema: 'selfrun-cluster-state-v1',
        component: this.config.component,
        version_scope: this.config.versionScope,
        version: this.config.version,
        server_version: this.config.version,
        version_label: this.config.versionLabel,
        updated_at: new Date(now).toISOString(),
        active_server: this.activeServer,
        active_priority: this.activePriority,
        member_timeout_ms: this.memberTimeoutMs,
        settle_ms: this.settleMs,
        claim_ready_at: new Date(this.claimReadyAt || 0).toISOString(),
        claim_ready: this.canDispatch(now),
      });
    }

    return this.snapshot();
  }

  async close() {
    this.running = false;
    if (this.loopPromise) {
      await Promise.race([this.loopPromise, this.sleep(Math.min(this.heartbeatMs, 2500))]).catch(() => {});
    }
    const now = this.now();
    const localState = this.stateStore.snapshot();
    await this.transport.upsertJson(this.memberFileName(), {
      schema: 'selfrun-server-state-v1',
      component: this.config.component,
      version_scope: this.config.versionScope,
      version: this.config.version,
      server_version: this.config.version,
      version_label: this.config.versionLabel,
      server_name: this.serverName,
      host: this.serverName,
      server_priority: this.serverPriority,
      role: 'STANDBY',
      active_server: this.activeServer,
      active_priority: this.activePriority,
      active_count: Number(localState.activeCount || 0),
      status: 'STOPPING',
      updated_at: new Date(now).toISOString(),
      heartbeat_at: new Date(now).toISOString(),
      claim_ready: false,
    });
  }
}
