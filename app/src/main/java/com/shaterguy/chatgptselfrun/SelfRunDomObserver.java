package com.shaterguy.chatgptselfrun;

/** SelfRun-owned page observer. It never pauses ChatGPT page timers. */
final class SelfRunDomObserver {
    private static final String KEY = "__chatgptSelfRunDomObserver";

    private SelfRunDomObserver() {}

    static String install(String token) {
        return """
                (() => {
                  const key = '__chatgptSelfRunDomObserver';
                  const token = %s;
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
                  const state = {observer:null, timer:0, bridgeListener:null, eventListener:null, port:null};
                  window[key] = state;
                  const relevantSelector = 'button,form,article,[data-message-author-role],[data-turn],[contenteditable="true"],[role="menu"],[role="listbox"],[role="option"],[role="radio"],[role="tab"]';
                  const relevantNode = node => {
                    if (!node || node.nodeType !== 1) return false;
                    try {
                      return !!node.matches?.(relevantSelector) || !!node.querySelector?.(relevantSelector);
                    } catch (_) { return false; }
                  };
                  const notify = () => {
                    if (!state.port) return;
                    if (state.timer) clearTimeout(state.timer);
                    state.timer = setTimeout(() => {
                      state.timer = 0;
                      try { state.port?.postMessage('changed'); } catch (_) {}
                    }, 180);
                  };
                  const onMutations = mutations => {
                    for (const mutation of mutations) {
                      if (mutation.type === 'attributes') { notify(); return; }
                      if (mutation.type === 'childList') {
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
                    if (root) {
                      state.observer = new MutationObserver(onMutations);
                      state.observer.observe(root, {
                        subtree:true,
                        childList:true,
                        attributes:true,
                        attributeFilter:['aria-busy','aria-disabled','aria-checked','aria-selected','aria-pressed','data-is-streaming','data-state']
                      });
                    }
                    state.eventListener = notify;
                    for (const name of eventNames) {
                      try { document.addEventListener(name, state.eventListener, true); } catch (_) {}
                    }
                    try { state.port.postMessage('ready'); } catch (_) {}
                  };
                  window.addEventListener('message', state.bridgeListener);
                  return 'WAIT_PORT';
                })()
                """.formatted(q(token));
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
