package com.shaterguy.chatgptselfrun;

/** SelfRun-owned page observer. It never pauses ChatGPT page timers. */
final class SelfRunDomObserver {
    private SelfRunDomObserver() {}

    static String install(String token, String lease) {
        return """
                (() => {
                  const key = '__chatgptSelfRunDomObserver';
                  const token = %s;
                  const lease = %s;
                  const eventNames = ['input', 'change', 'click', 'submit'];
                  const cleanup = state => {
                    if (!state) return;
                    try { state.observer?.disconnect(); } catch (_) {}
                    if (state.timer) { try { clearTimeout(state.timer); } catch (_) {} }
                    if (state.bridgeListener) {
                      try { window.removeEventListener('message', state.bridgeListener); } catch (_) {}
                    }
                    if (state.eventListener) {
                      for (const name of eventNames) {
                        try { document.removeEventListener(name, state.eventListener, true); } catch (_) {}
                      }
                    }
                    try { state.port?.close(); } catch (_) {}
                  };
                  cleanup(window[key]);
                  const state = {
                    lease, observer:null, timer:0, bridgeListener:null, eventListener:null, port:null, root:null,
                    probe:null, probeSent:0, probeAck:0, lastObserverCallbackAt:Date.now(),
                    lastFingerprint:'', lastMutationAt:Date.now(), lastDispatchAt:0, suppressed:0,
                    lastStreaming:false, lastAssistantNode:null
                  };
                  window[key] = state;
                  const visible = e => !!e && e.isConnected && e.offsetParent !== null;
                  const compact = value => String(value ?? '').replace(/\\s+/g, ' ').trim().slice(0, 160);
                  const hash = value => {
                    const s = String(value ?? '');
                    let h = 2166136261;
                    for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
                    return (h >>> 0).toString(36) + ':' + s.length;
                  };
                  const composer = () => {
                    const selectors = [
                      'textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
                      'div#prompt-textarea[contenteditable="true"]',
                      'main form [contenteditable="true"][data-lexical-editor="true"]',
                      'main form [contenteditable="true"]'
                    ];
                    for (const selector of selectors) {
                      const found = [...document.querySelectorAll(selector)].find(visible);
                      if (found) return found;
                    }
                    return null;
                  };
                  const roleOf = e => e?.getAttribute?.('data-message-author-role')
                    || e?.getAttribute?.('data-turn')
                    || e?.querySelector?.('[data-message-author-role]')?.getAttribute?.('data-message-author-role') || '';
                  const relevantSelector = 'button,form,article,[data-message-author-role],[data-turn],[contenteditable="true"],[role="menu"],[role="listbox"],[role="option"],[role="radio"],[role="tab"]';
                  const relevantNode = node => {
                    if (!node || node.nodeType !== 1) return false;
                    try { return !!node.matches?.(relevantSelector) || !!node.querySelector?.(relevantSelector); }
                    catch (_) { return false; }
                  };
                  const snapshot = () => {
                    const c = composer();
                    const cText = c ? ('value' in c ? c.value : (c.innerText || c.textContent || '')) : '';
                    const scope = c?.closest?.('form') || document;
                    const buttons = [...scope.querySelectorAll?.('button') || []].filter(visible);
                    const send = buttons.find(b => b.dataset?.testid === 'send-button'
                      || b.dataset?.testid === 'composer-submit-button'
                      || /send|보내기|submit/i.test((b.getAttribute?.('aria-label') || '') + ' ' + (b.title || ''))) || null;
                    const stop = [...document.querySelectorAll('button')].filter(visible).some(b =>
                      b.dataset?.testid === 'stop-button'
                      || /stop generating|응답 중지|생성 중지/i.test((b.getAttribute?.('aria-label') || '') + ' ' + (b.title || '')));
                    const auth = [...document.querySelectorAll('[data-testid*=login],a[href*="/auth/login"],button')]
                      .filter(visible).some(e => /^(log in|sign up|로그인|가입)$/i.test(compact(e.innerText || e.getAttribute?.('aria-label') || '')));
                    const turns = [...document.querySelectorAll('article,[data-message-author-role]')]
                      .filter((e, i, all) => !all.some((p, j) => j < i && p.contains(e)));
                    let userIndex = -1;
                    let userCount = 0;
                    for (let i = 0; i < turns.length; i++) {
                      if (roleOf(turns[i]) === 'user') { userIndex = i; userCount++; }
                    }
                    let assistant = null;
                    let assistantIndex = -1;
                    for (let i = userIndex + 1; i < turns.length; i++) {
                      const role = roleOf(turns[i]);
                      if (role === 'user') break;
                      if (role === 'assistant') { assistant = turns[i]; assistantIndex = i; break; }
                    }
                    const assistantIdentity = assistant
                      ? (assistant.getAttribute('data-message-id') || assistant.dataset?.messageId || assistant.id || 'index') + ':' + assistantIndex
                      : '';
                    const streaming = !!assistant && (stop || assistant.getAttribute('aria-busy') === 'true'
                      || assistant.getAttribute('data-is-streaming') === 'true'
                      || !!assistant.querySelector('[aria-busy="true"],[data-is-streaming="true"],[class*="spinner" i],[class*="loading" i]'));
                    state.lastStreaming = streaming;
                    state.lastAssistantNode = assistant;
                    let completedDigest = '';
                    let controlDigest = '';
                    if (assistant && !streaming) {
                      const completedText = String(assistant.innerText || assistant.textContent || '');
                      completedDigest = hash(completedText);
                      const controls = completedText.match(/\\[SELF_RUN_[A-Z_]+[^\\]\\r\\n]*\\]/g) || [];
                      controlDigest = controls.length ? hash(controls[controls.length - 1]) : '';
                    }
                    const selected = [...document.querySelectorAll('[aria-checked="true"],[aria-selected="true"],[aria-pressed="true"],input[type="radio"]:checked')]
                      .filter(visible).slice(-24).map(e => compact(e.innerText || e.getAttribute?.('aria-label') || e.value || e.dataset?.state || '')).filter(Boolean);
                    const overlays = [...document.querySelectorAll('[role="menu"],[role="listbox"]')].filter(visible).length;
                    return JSON.stringify({
                      path:location.pathname, auth, composer:!!c, composerKey:hash(cText),
                      send:!!send, sendDisabled:!!send && (send.disabled || send.getAttribute('aria-disabled') === 'true'),
                      stop, userCount, assistantIdentity, streaming, completedDigest, controlDigest, selected, overlays
                    });
                  };
                  const notify = () => {
                    state.lastMutationAt = Date.now();
                    if (!state.port) return;
                    if (state.timer) clearTimeout(state.timer);
                    state.timer = setTimeout(() => {
                      state.timer = 0;
                      const fingerprint = snapshot();
                      if (fingerprint === state.lastFingerprint) { state.suppressed++; return; }
                      state.lastFingerprint = fingerprint;
                      state.lastDispatchAt = Date.now();
                      try { state.port?.postMessage('state|' + fingerprint); } catch (_) {}
                    }, 180);
                  };
                  const onMutations = mutations => {
                    state.lastObserverCallbackAt = Date.now();
                    for (const mutation of mutations) {
                      if (mutation.target === state.probe && mutation.attributeName === 'data-selfrun-probe') {
                        const ack = Number(state.probe?.getAttribute('data-selfrun-probe') || 0);
                        if (ack > state.probeAck) state.probeAck = ack;
                        continue;
                      }
                      if (mutation.type === 'characterData') {
                        state.lastMutationAt = Date.now();
                        if (state.lastAssistantNode?.contains(mutation.target)
                            && !state.lastStreaming && !state.timer) {
                          notify();
                          return;
                        }
                        continue;
                      }
                      if (mutation.type === 'attributes') { notify(); return; }
                      if (mutation.type === 'childList') {
                        if (state.lastAssistantNode?.contains(mutation.target)
                            && !state.lastStreaming && !state.timer) {
                          notify();
                          return;
                        }
                        const nodes = [...mutation.addedNodes, ...mutation.removedNodes];
                        if (nodes.some(relevantNode)) { notify(); return; }
                      }
                    }
                  };
                  state.bridgeListener = event => {
                    if (event.data !== token || !event.ports || event.ports.length < 1) return;
                    try { window.removeEventListener('message', state.bridgeListener); } catch (_) {}
                    state.bridgeListener = null;
                    state.port = event.ports[0];
                    try { state.port.start?.(); } catch (_) {}
                    const root = document.body || document.documentElement;
                    state.root = root;
                    state.observer = new MutationObserver(onMutations);
                    if (root) {
                      state.observer.observe(root, {
                        subtree:true,
                        childList:true,
                        characterData:true,
                        attributes:true,
                        attributeFilter:['aria-busy','aria-disabled','aria-checked','aria-selected','aria-pressed','data-is-streaming','data-state','disabled']
                      });
                    }
                    state.probe = document.createElement('span');
                    state.observer.observe(state.probe, {
                      attributes:true,
                      attributeFilter:['data-selfrun-probe']
                    });
                    state.eventListener = notify;
                    for (const name of eventNames) {
                      try { document.addEventListener(name, state.eventListener, true); } catch (_) {}
                    }
                    state.lastFingerprint = snapshot();
                    state.lastDispatchAt = Date.now();
                    try { state.port.postMessage('ready|' + state.lastFingerprint); } catch (_) {}
                  };
                  window.addEventListener('message', state.bridgeListener);
                  return 'WAIT_PORT';
                })()
                """.formatted(q(token), q(lease));
    }

    static String health(String lease) {
        return """
                (() => {
                  const state = window.__chatgptSelfRunDomObserver;
                  if (!state) return JSON.stringify({status:'MISSING'});
                  if (state.lease !== %s) return JSON.stringify({status:'STALE', lease:state.lease || ''});
                  const previousProbe = Number(state.probeSent || 0);
                  const previousAck = Number(state.probeAck || 0);
                  if (previousProbe > 0 && previousAck < previousProbe) {
                    return JSON.stringify({
                      status:'STALLED', lease:state.lease, port:!!state.port,
                      rootConnected:!!state.root && state.root.isConnected,
                      probeSent:previousProbe, probeAck:previousAck,
                      lastObserverCallbackAt:Number(state.lastObserverCallbackAt || 0),
                      fingerprint:String(state.lastFingerprint || ''),
                      suppressed:Number(state.suppressed || 0)
                    });
                  }
                  const nextProbe = previousProbe + 1;
                  state.probeSent = nextProbe;
                  try {
                    if (!state.probe) throw new Error('probe_missing');
                    state.probe.setAttribute('data-selfrun-probe', String(nextProbe));
                  } catch (_) {
                    return JSON.stringify({
                      status:'STALLED', lease:state.lease, port:!!state.port,
                      rootConnected:!!state.root && state.root.isConnected,
                      probeSent:nextProbe, probeAck:previousAck,
                      lastObserverCallbackAt:Number(state.lastObserverCallbackAt || 0),
                      fingerprint:String(state.lastFingerprint || ''),
                      suppressed:Number(state.suppressed || 0)
                    });
                  }
                  return JSON.stringify({
                    status:'ALIVE', lease:state.lease, port:!!state.port,
                    rootConnected:!!state.root && state.root.isConnected,
                    probeSent:nextProbe, probeAck:previousAck,
                    lastObserverCallbackAt:Number(state.lastObserverCallbackAt || 0),
                    lastMutationAt:Number(state.lastMutationAt || 0),
                    lastDispatchAt:Number(state.lastDispatchAt || 0),
                    fingerprint:String(state.lastFingerprint || ''),
                    suppressed:Number(state.suppressed || 0)
                  });
                })()
                """.formatted(q(lease));
    }

    static String detach() {
        return """
                (() => {
                  const key = '__chatgptSelfRunDomObserver';
                  const state = window[key];
                  if (!state) return 'MISSING';
                  try { state.observer?.disconnect(); } catch (_) {}
                  if (state.timer) { try { clearTimeout(state.timer); } catch (_) {} }
                  if (state.bridgeListener) {
                    try { window.removeEventListener('message', state.bridgeListener); } catch (_) {}
                  }
                  if (state.eventListener) {
                    for (const name of ['input','change','click','submit']) {
                      try { document.removeEventListener(name, state.eventListener, true); } catch (_) {}
                    }
                  }
                  try { state.port?.close(); } catch (_) {}
                  try { delete window[key]; } catch (_) { window[key] = null; }
                  return 'DETACHED';
                })()
                """;
    }

    private static String q(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
