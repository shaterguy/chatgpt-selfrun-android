package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/**
 * Installs a document-start network guard before ChatGPT page scripts run.
 *
 * The guard is dormant unless SelfRunDom arms one exact CONTINUE attempt. For that attempt only,
 * it reads the canonical conversation current_node immediately before the page's own conversation
 * POST and rewrites only parent_message_id. All other request fields, the page's normal sentinel
 * flow, cookies, model/mode selection, and the long-lived WebView session remain owned by ChatGPT.
 */
final class SelfRunNetworkGuard {
    static final String ARM_KEY = "selfrun-drive:parent-guard:arm";
    static final String RESULT_KEY = "selfrun-drive:parent-guard:result";
    static final String INSTALLED_FLAG = "__selfRunDriveParentGuardInstalled";

    private SelfRunNetworkGuard() {}

    static boolean install(WebView webView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false;
        WebViewCompat.addDocumentStartJavaScript(
                webView,
                documentStartScript(),
                Set.of("https://chatgpt.com", "https://www.chatgpt.com"));
        return true;
    }

    static String documentStartScript() {
        return """
                (() => {
                  if (window.__selfRunDriveParentGuardInstalled === true) return;
                  window.__selfRunDriveParentGuardInstalled = true;

                  const ARM_KEY = 'selfrun-drive:parent-guard:arm';
                  const RESULT_KEY = 'selfrun-drive:parent-guard:result';
                  const nativeFetch = window.fetch.bind(window);
                  const NativeXHR = window.XMLHttpRequest;
                  const nativeOpen = NativeXHR?.prototype?.open;
                  const nativeSend = NativeXHR?.prototype?.send;
                  const nativeSetHeader = NativeXHR?.prototype?.setRequestHeader;

                  const now = () => Date.now();
                  const normalize = value => String(value ?? '').replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
                  const targetPath = value => {
                    try {
                      const path = new URL(String(value), location.href).pathname.replace(/\/$/, '');
                      return path === '/backend-api/f/conversation' || path === '/backend-api/conversation';
                    } catch (_) { return false; }
                  };
                  const readArm = () => {
                    try {
                      const raw = sessionStorage.getItem(ARM_KEY) || '';
                      const arm = raw ? JSON.parse(raw) : null;
                      if (!arm || !arm.markerId || !arm.conversationId || !arm.expected
                              || Number(arm.expiresAt || 0) <= now()) {
                        if (raw) sessionStorage.removeItem(ARM_KEY);
                        return null;
                      }
                      return arm;
                    } catch (_) { return null; }
                  };
                  const clearArm = () => { try { sessionStorage.removeItem(ARM_KEY); } catch (_) {} };
                  const writeResult = (arm, state, code) => {
                    try {
                      sessionStorage.setItem(RESULT_KEY, JSON.stringify({
                        markerId: String(arm?.markerId || ''),
                        state: String(state || ''),
                        code: String(code || ''),
                        at: now()
                      }));
                    } catch (_) {}
                  };
                  const outgoingAuth = (request, init, xhrHeaders) => {
                    let value = '';
                    try { value = String(request?.headers?.get?.('authorization') || ''); } catch (_) {}
                    try {
                      const headers = new Headers(init?.headers || {});
                      value = String(headers.get('authorization') || value);
                    } catch (_) {}
                    if (xhrHeaders?.authorization) value = String(xhrHeaders.authorization);
                    return value;
                  };
                  const canonicalParent = async (conversationId, auth) => {
                    const options = { credentials: 'include', cache: 'no-store' };
                    if (auth) options.headers = { Authorization: auth };
                    let response = await nativeFetch(
                            '/backend-api/conversation/' + encodeURIComponent(conversationId), options);
                    if ((response.status === 401 || response.status === 403) && !auth) {
                      const sessionResponse = await nativeFetch('/api/auth/session', {
                        credentials: 'include', cache: 'no-store'
                      });
                      if (sessionResponse.ok) {
                        const session = await sessionResponse.json();
                        const token = String(session?.accessToken || session?.access_token || '');
                        if (token) {
                          response = await nativeFetch(
                                  '/backend-api/conversation/' + encodeURIComponent(conversationId), {
                                    credentials: 'include', cache: 'no-store',
                                    headers: { Authorization: 'Bearer ' + token }
                                  });
                        }
                      }
                    }
                    if (!response.ok) throw new Error('canonical_parent_http_' + response.status);
                    const graph = await response.json();
                    const parent = String(graph?.current_node || '');
                    const entry = parent ? graph?.mapping?.[parent] : null;
                    if (!parent || !entry) throw new Error('canonical_parent_missing');
                    const status = String(entry?.message?.status || '');
                    if (/in_progress|streaming|pending/i.test(status)) {
                      throw new Error('canonical_parent_generating');
                    }
                    return parent;
                  };
                  const matchesArmedContinue = (payload, arm) => {
                    if (String(payload?.conversation_id || '') !== String(arm.conversationId)) return false;
                    const expected = normalize(arm.expected);
                    const messages = Array.isArray(payload?.messages) ? payload.messages : [];
                    return messages.some(message => {
                      const parts = Array.isArray(message?.content?.parts) ? message.content.parts : [];
                      return parts.some(part => typeof part === 'string' && normalize(part) === expected);
                    });
                  };
                  const rewritePayload = async (payload, arm, auth) => {
                    if (!Object.prototype.hasOwnProperty.call(payload || {}, 'parent_message_id')) {
                      throw new Error('parent_message_id_missing');
                    }
                    const parent = await canonicalParent(arm.conversationId, auth);
                    payload.parent_message_id = parent;
                    return payload;
                  };
                  const failClosed = (arm, error) => {
                    clearArm();
                    writeResult(arm, 'failed', String(error?.message || error || 'parent_guard_failed'));
                  };

                  window.fetch = async function(input, init) {
                    const request = typeof Request !== 'undefined' && input instanceof Request ? input : null;
                    const method = String(init?.method || request?.method || 'GET').toUpperCase();
                    const url = request?.url || input;
                    const arm = readArm();
                    if (!arm || method !== 'POST' || !targetPath(url)) return nativeFetch(input, init);

                    try {
                      let bodyText = init?.body;
                      if (bodyText == null && request) bodyText = await request.clone().text();
                      if (typeof bodyText !== 'string') return nativeFetch(input, init);
                      const payload = JSON.parse(bodyText);
                      if (!matchesArmedContinue(payload, arm)) return nativeFetch(input, init);
                      const rewritten = await rewritePayload(payload, arm, outgoingAuth(request, init, null));
                      const rewrittenText = JSON.stringify(rewritten);
                      clearArm();
                      let response;
                      if (request) {
                        response = await nativeFetch(new Request(request, { ...(init || {}), body: rewrittenText }));
                      } else {
                        response = await nativeFetch(input, { ...(init || {}), body: rewrittenText });
                      }
                      writeResult(arm, 'forwarded', 'canonical_parent_applied');
                      return response;
                    } catch (error) {
                      failClosed(arm, error);
                      throw error;
                    }
                  };

                  if (NativeXHR && nativeOpen && nativeSend && nativeSetHeader) {
                    NativeXHR.prototype.open = function(method, url, ...rest) {
                      this.__srMethod = String(method || 'GET').toUpperCase();
                      this.__srUrl = String(url || '');
                      this.__srHeaders = {};
                      return nativeOpen.call(this, method, url, ...rest);
                    };
                    NativeXHR.prototype.setRequestHeader = function(name, value) {
                      try { this.__srHeaders[String(name || '').toLowerCase()] = String(value || ''); }
                      catch (_) {}
                      return nativeSetHeader.call(this, name, value);
                    };
                    NativeXHR.prototype.send = function(body) {
                      const xhr = this;
                      const arm = readArm();
                      if (!arm || xhr.__srMethod !== 'POST' || !targetPath(xhr.__srUrl)) {
                        return nativeSend.call(xhr, body);
                      }
                      if (typeof body !== 'string') return nativeSend.call(xhr, body);
                      let payload;
                      try { payload = JSON.parse(body); }
                      catch (_) { return nativeSend.call(xhr, body); }
                      if (!matchesArmedContinue(payload, arm)) return nativeSend.call(xhr, body);

                      Promise.resolve().then(async () => {
                        try {
                          const rewritten = await rewritePayload(
                                  payload, arm, outgoingAuth(null, null, xhr.__srHeaders));
                          clearArm();
                          nativeSend.call(xhr, JSON.stringify(rewritten));
                          writeResult(arm, 'forwarded', 'canonical_parent_applied');
                        } catch (error) {
                          failClosed(arm, error);
                          try { xhr.abort(); } catch (_) {}
                        }
                      });
                      return undefined;
                    };
                  }
                })();
                """;
    }
}
