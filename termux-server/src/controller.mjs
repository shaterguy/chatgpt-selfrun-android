const DISPATCH_TYPES = new Set(['START', 'NEXT']);
const CONTROL_TYPES = new Set(['START', 'NEXT', 'PAUSE', 'STOP']);
const ENVELOPE_KEYS = [
  'TASK_ID',
  'TURN_ID',
  'REQUEST_ID',
  'SELF_RUN_SKILL_DOCUMENT_ID',
  'RESULT_DOCUMENT_ID',
  'REQUIREMENT_DOCUMENT_ID',
  'PREVIOUS_RESULT_DOCUMENT_ID',
];
const REQUIRED_ENVELOPE_KEYS = ENVELOPE_KEYS.filter((key) => key !== 'PREVIOUS_RESULT_DOCUMENT_ID');

function cleanString(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function normalizeProjectUrl(value) {
  const raw = cleanString(value);
  if (!raw) return '';
  const url = new URL(raw);
  if (url.protocol !== 'https:' || !['chatgpt.com', 'www.chatgpt.com'].includes(url.hostname)) {
    throw new Error('projectUrl must be an https://chatgpt.com URL');
  }
  return url.toString();
}

export function normalizeSignal(raw, config) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('Signal body must be an object');
  const signalId = cleanString(raw.signalId);
  const type = cleanString(raw.type).toUpperCase();
  if (!signalId) throw new Error('signalId is required');
  if (!CONTROL_TYPES.has(type)) throw new Error('Unsupported signal type');

  if (!DISPATCH_TYPES.has(type)) return { signalId, type };

  const envelope = {};
  for (const key of ENVELOPE_KEYS) {
    const value = cleanString(raw.envelope?.[key]);
    if (value) envelope[key] = value;
  }
  for (const key of REQUIRED_ENVELOPE_KEYS) {
    if (!envelope[key]) throw new Error(`envelope.${key} is required`);
  }

  const projectUrl = normalizeProjectUrl(raw.projectUrl || config.defaultProjectUrl);
  if (!projectUrl) throw new Error('projectUrl is required for START/NEXT');

  return {
    signalId,
    type,
    projectUrl,
    envelope,
    additionalInput: cleanString(raw.additionalInput),
    receivedAt: new Date().toISOString(),
  };
}

export function buildPrompt(signal) {
  const lines = [];
  for (const key of ENVELOPE_KEYS) {
    const value = signal.envelope?.[key];
    if (value) lines.push(`${key}=${value}`);
  }
  if (signal.additionalInput) {
    lines.push('', '[사용자 추가 지시 원문]', signal.additionalInput);
  }
  return lines.join('\n');
}

export class SelfRunController {
  constructor({ browser, stateStore, config }) {
    this.browser = browser;
    this.stateStore = stateStore;
    this.config = config;
    this.activeAbort = null;
    this.activePromise = null;
  }

  async initialize() {
    const state = this.stateStore.snapshot();
    const active = ['ACCEPTED', 'STAGED', 'RUNNING', 'STALLED'].includes(state.status);
    if (active) {
      await this.stateStore.patch({
        generation: state.generation + 1,
        status: 'INTERRUPTED',
        activeTargetId: null,
        lastError: 'Server restarted during an active browser turn; no automatic resend was performed.',
      }, 'SERVER_RECOVERY_INTERRUPTED');
    }
  }

  async accept(rawSignal) {
    const signal = normalizeSignal(rawSignal, this.config);
    const current = this.stateStore.snapshot();
    if (current.lastSignalId === signal.signalId) {
      return { accepted: true, duplicate: true, generation: current.generation, status: current.status };
    }

    const previousTargetId = current.activeTargetId;
    if (this.activeAbort) {
      this.activeAbort.abort(new Error('Superseded by a newer SelfRun signal'));
      this.activeAbort = null;
    }

    const generation = current.generation + 1;
    await this.stateStore.patch({
      generation,
      status: DISPATCH_TYPES.has(signal.type) ? 'ACCEPTED' : signal.type,
      activeSignal: signal,
      activeTargetId: null,
      conversationUrl: null,
      acceptedAt: new Date().toISOString(),
      lastActivityAt: null,
      lastSignalId: signal.signalId,
      lastError: null,
    }, `SIGNAL_${signal.type}`);

    if (previousTargetId) await this.browser.chromium.closeTarget(previousTargetId);

    if (signal.type === 'STOP' || signal.type === 'PAUSE') {
      return { accepted: true, duplicate: false, generation, status: signal.type };
    }

    const abortController = new AbortController();
    this.activeAbort = abortController;
    this.activePromise = this.#run(signal, generation, abortController.signal)
      .catch(() => {})
      .finally(() => {
        if (this.activeAbort === abortController) this.activeAbort = null;
      });

    return { accepted: true, duplicate: false, generation, status: 'ACCEPTED' };
  }

  #current(generation, signalId) {
    const state = this.stateStore.snapshot();
    return state.generation === generation && state.lastSignalId === signalId;
  }

  async #transition(generation, signalId, changes, event) {
    if (!this.#current(generation, signalId)) return false;
    const result = await this.stateStore.patchIfCurrent(generation, signalId, changes, event);
    return result.applied;
  }

  async #run(signal, generation, abortSignal) {
    const prompt = buildPrompt(signal);
    try {
      const dispatched = await this.browser.dispatch({
        projectUrl: signal.projectUrl,
        prompt,
        signal: abortSignal,
        onTransition: async (status, detail) => {
          await this.#transition(generation, signal.signalId, {
            status: status === 'SENT' ? 'RUNNING' : status,
            activeTargetId: detail.targetId || this.stateStore.snapshot().activeTargetId,
            lastActivityAt: new Date().toISOString(),
          }, `BROWSER_${status}`);
        },
      });

      if (!this.#current(generation, signal.signalId)) {
        dispatched.session.close();
        await this.browser.chromium.closeTarget(dispatched.target.id);
        return;
      }

      await this.#transition(generation, signal.signalId, {
        status: 'RUNNING',
        activeTargetId: dispatched.target.id,
        lastActivityAt: new Date().toISOString(),
      }, 'MONITOR_STARTED');

      await this.browser.monitor({
        session: dispatched.session,
        baseline: dispatched.baseline,
        signal: abortSignal,
        onActivity: async (activity) => {
          if (!this.#current(generation, signal.signalId)) return;
          const conversationUrl = /\/c\//.test(new URL(activity.pageUrl).pathname) ? activity.pageUrl : null;
          await this.#transition(generation, signal.signalId, {
            status: activity.status,
            conversationUrl: conversationUrl || this.stateStore.snapshot().conversationUrl,
            lastActivityAt: activity.lastActivityAt,
            lastError: activity.pageError || null,
          }, activity.status === 'STALLED' ? 'TURN_STALLED' : null);
        },
      });

      dispatched.session.close();
    } catch (error) {
      if (abortSignal.aborted || !this.#current(generation, signal.signalId)) return;
      await this.#transition(generation, signal.signalId, {
        status: error?.code === 'AUTH_REQUIRED' ? 'AUTH_REQUIRED' : 'ERROR',
        lastError: String(error?.message || error),
      }, 'TURN_ERROR');
    }
  }
}
