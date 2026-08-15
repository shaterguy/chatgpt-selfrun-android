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
    static final String LIVENESS_FN = "__selfRunDriveParentGuardAlive";
    static final String MEMORY_RESULT = "__selfRunDriveParentGuardResult";

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
                  let guardedFetch = null;
                  let guardedOpen = null;
                  let guardedSend = null;
                  let guardedSetHeader = null;

                  const now = () => Date.now();
                  const normalize = value => String(value ?? '').replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
                  const pathOf = value => {
                    try {
                      const rawPath = new URL(String(value), location.href).pathname;
                      return rawPath.endsWith('/') ? rawPath.slice(0, -1) : rawPath;
                    } catch (_) { return ''; }
                  };
                  const targetPath = value => {
                    const path = pathOf(value);
                    return path === '/backend-api/f/conversation' || path === '/backend-api/conversation';
                  };
                  const readArm = () => {
                    try {
                      const raw = sessionStorage.getItem(ARM_KEY) || '';
                      const arm = raw ? JSON.parse(raw) : null;
                      if (!arm || !arm.markerId || !arm.conversationId || !arm.expected) return null;
                      return arm;
                    } catch (_) { return null; }
                  };
                  const clearArm = () => { try { sessionStorage.removeItem(ARM_KEY); } catch (_) {} };
                  const writeResult = (arm, state, code) => {
                    const value = {
                      markerId: String(arm?.markerId || ''),
                      state: String(state || ''),
                      code: String(code || ''),
                      at: now()
                    };
                    try { window.__selfRunDriveParentGuardResult = value; } catch (_) {}
                    try { sessionStorage.setItem(RESULT_KEY, JSON.stringify(value)); } catch (_) {}
                  };
                  const stage = (arm, state, code = '') => writeResult(arm, state, code);
                  const classify = error => {
                    const raw = String(error?.message || error || '');
                    if (raw === 'armed_continue_payload_mismatch') return 'PAYLOAD_MISMATCH';
                    if (raw === 'parent_message_id_missing') return 'PARENT_ID_MISSING';
                    if (raw === 'conversation_body_unreadable') return 'BODY_UNREADABLE';
                    if (raw === 'conversation_endpoint_mismatch') return 'ENDPOINT_MISMATCH';
                    if (raw === 'canonical_parent_missing') return 'CANONICAL_MISSING';
                    if (raw === 'canonical_parent_generating') return 'CANONICAL_GENERATING';
                    if (raw === 'guard_expired_before_forward') return 'HANDSHAKE_TIMEOUT';
                    if (raw === 'conversation_forward_failed') return 'FORWARD_FAILED';
                    if (/^canonical_parent_http_\\d+$/.test(raw)) return raw.replace('canonical_parent_http_', 'CANONICAL_HTTP_');
                    return 'GUARD_INTERNAL_FAILURE';
                  };
                  const failClosed = (arm, error) => {
                    const code = classify(error);
                    stage(arm, 'failed', code);
                    clearArm();
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
                  const canonicalParent = async (conversationId, auth, arm) => {
                    stage(arm, 'canonical_fetch_start');
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
                    stage(arm, 'canonical_fetch_ok');
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
                  const parseBody = async (request, init) => {
                    let bodyText = init?.body;
                    if (bodyText == null && request) bodyText = await request.clone().text();
                    if (typeof bodyText !== 'string') throw new Error('conversation_body_unreadable');
                    return JSON.parse(bodyText);
                  };
                  const rewritePayload = async (payload, arm, auth) => {
                    if (!matchesArmedContinue(payload, arm)) throw new Error('armed_continue_payload_mismatch');
                    stage(arm, 'payload_matched');
                    if (!Object.prototype.hasOwnProperty.call(payload || {}, 'parent_message_id')) {
                      throw new Error('parent_message_id_missing');
                    }
                    const parent = await canonicalParent(arm.conversationId, auth, arm);
                    if (Number(arm.expiresAt || 0) <= now()) throw new Error('guard_expired_before_forward');
                    payload.parent_message_id = parent;
                    stage(arm, 'parent_rewritten');
                    return payload;
                  };
                  const matchingUnexpectedEndpoint = async (request, init, arm) => {
                    try {
                      const payload = await parseBody(request, init);
                      return matchesArmedContinue(payload, arm);
                    } catch (_) { return false; }
                  };

                  guardedFetch = async function(input, init) {
                    const request = typeof Request !== 'undefined' && input instanceof Request ? input : null;
                    const method = String(init?.method || request?.method || 'GET').toUpperCase();
                    const url = request?.url || input;
                    const arm = readArm();
                    if (!arm || method !== 'POST') return nativeFetch(input, init);
                    if (!targetPath(url)) {
                      if (await matchingUnexpectedEndpoint(request, init, arm)) {
                        failClosed(arm, new Error('conversation_endpoint_mismatch'));
                        throw new Error('selfrun_parent_guard_blocked');
                      }
                      return nativeFetch(input, init);
                    }

                    stage(arm, 'post_intercepted');
                    try {
                      const payload = await parseBody(request, init);
                      const rewritten = await rewritePayload(payload, arm, outgoingAuth(request, init, null));
                      const rewrittenText = JSON.stringify(rewritten);
                      stage(arm, 'forwarding');
                      let response;
                      try {
                        if (request) {
                          response = await nativeFetch(new Request(request, { ...(init || {}), body: rewrittenText }));
                        } else {
                          response = await nativeFetch(input, { ...(init || {}), body: rewrittenText });
                        }
                      } catch (_) {
                        throw new Error('conversation_forward_failed');
                      }
                      stage(arm, 'forwarded', 'CANONICAL_PARENT_APPLIED');
                      clearArm();
                      return response;
                    } catch (error) {
                      failClosed(arm, error);
                      throw error;
                    }
                  };
                  window.fetch = guardedFetch;

                  if (NativeXHR && nativeOpen && nativeSend && nativeSetHeader) {
                    guardedOpen = function(method, url, ...rest) {
                      this.__srMethod = String(method || 'GET').toUpperCase();
                      this.__srUrl = String(url || '');
                      this.__srHeaders = {};
                      return nativeOpen.call(this, method, url, ...rest);
                    };
                    guardedSetHeader = function(name, value) {
                      try { this.__srHeaders[String(name || '').toLowerCase()] = String(value || ''); }
                      catch (_) {}
                      return nativeSetHeader.call(this, name, value);
                    };
                    guardedSend = function(body) {
                      const xhr = this;
                      const arm = readArm();
                      if (!arm || xhr.__srMethod !== 'POST') return nativeSend.call(xhr, body);

                      let payload = null;
                      try {
                        if (typeof body === 'string') payload = JSON.parse(body);
                      } catch (_) {}
                      if (!targetPath(xhr.__srUrl)) {
                        if (payload && matchesArmedContinue(payload, arm)) {
                          failClosed(arm, new Error('conversation_endpoint_mismatch'));
                          try { xhr.abort(); } catch (_) {}
                          return undefined;
                        }
                        return nativeSend.call(xhr, body);
                      }

                      stage(arm, 'post_intercepted');
                      try {
                        if (!payload) throw new Error('conversation_body_unreadable');
                        if (!matchesArmedContinue(payload, arm)) throw new Error('armed_continue_payload_mismatch');
                        stage(arm, 'payload_matched');
                      } catch (error) {
                        failClosed(arm, error);
                        try { xhr.abort(); } catch (_) {}
                        return undefined;
                      }

                      Promise.resolve().then(async () => {
                        try {
                          const rewritten = await rewritePayload(
                                  payload, arm, outgoingAuth(null, null, xhr.__srHeaders));
                          stage(arm, 'forwarding');
                          try { nativeSend.call(xhr, JSON.stringify(rewritten)); }
                          catch (_) { throw new Error('conversation_forward_failed'); }
                          stage(arm, 'forwarded', 'CANONICAL_PARENT_APPLIED');
                          clearArm();
                        } catch (error) {
                          failClosed(arm, error);
                          try { xhr.abort(); } catch (_) {}
                        }
                      });
                      return undefined;
                    };
                    NativeXHR.prototype.open = guardedOpen;
                    NativeXHR.prototype.setRequestHeader = guardedSetHeader;
                    NativeXHR.prototype.send = guardedSend;
                  }

                  window.__selfRunDriveParentGuardAlive = () => {
                    try {
                      const fetchAlive = window.fetch === guardedFetch;
                      const xhrAlive = !NativeXHR || !nativeOpen || !nativeSend || !nativeSetHeader || (
                              NativeXHR.prototype.open === guardedOpen
                              && NativeXHR.prototype.send === guardedSend
                              && NativeXHR.prototype.setRequestHeader === guardedSetHeader);
                      return fetchAlive && xhrAlive;
                    } catch (_) { return false; }
                  };
                })();
                """;
    }
}
