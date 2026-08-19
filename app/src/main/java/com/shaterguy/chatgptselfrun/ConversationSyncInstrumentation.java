package com.shaterguy.chatgptselfrun;

import android.net.Uri;
import android.webkit.WebView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Privacy-safe document-start observation for the ChatGPT page's own conversation sync path.
 * The probe never navigates, never submits UI actions, and never forwards conversation bodies.
 */
final class ConversationSyncInstrumentation {
    static final String BRIDGE_NAME = "__selfRunDriveSyncBridge";
    static final int MAX_MESSAGE_CHARS = 4096;
    static final Set<String> TRUSTED_ORIGINS;

    static {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        origins.add("https://chatgpt.com");
        origins.add("https://www.chatgpt.com");
        TRUSTED_ORIGINS = Collections.unmodifiableSet(origins);
    }

    enum Type {
        DOCUMENT_READY,
        CONVERSATION_CHANNEL_OPEN,
        CONVERSATION_CHANNEL_CLOSED,
        CONVERSATION_REMOTE_UPDATE,
        CONVERSATION_REVALIDATION_START,
        CONVERSATION_REVALIDATION_COMPLETE,
        CLIENT_STATE,
        COMPOSER_REPLACED,
        CLIENT_STATE_RESET,
        UNCLASSIFIED_CHANNEL_TRAFFIC,
        UNKNOWN
    }

    static final class Event {
        final Type type;
        final long sequence;
        final boolean conversationMatch;
        final int httpStatus;
        final String headKey;
        final String composerKey;
        final String stateSignature;
        final int turnCount;
        final String reason;

        Event(Type type, long sequence, boolean conversationMatch, int httpStatus,
              String headKey, String composerKey, String stateSignature,
              int turnCount, String reason) {
            this.type = type;
            this.sequence = sequence;
            this.conversationMatch = conversationMatch;
            this.httpStatus = httpStatus;
            this.headKey = safe(headKey);
            this.composerKey = safe(composerKey);
            this.stateSignature = safe(stateSignature);
            this.turnCount = Math.max(0, turnCount);
            this.reason = safe(reason);
        }
    }

    interface Listener {
        void onConversationSyncEvent(Event event);
    }

    static final class Proof {
        final boolean proven;
        final long probeEpoch;
        final long remoteEpoch;
        final long revalidationEpoch;
        final String headKey;
        final String composerKey;
        final String stateSignature;
        final String source;

        private Proof(boolean proven, long probeEpoch, long remoteEpoch, long revalidationEpoch,
                      String headKey, String composerKey, String stateSignature, String source) {
            this.proven = proven;
            this.probeEpoch = probeEpoch;
            this.remoteEpoch = remoteEpoch;
            this.revalidationEpoch = revalidationEpoch;
            this.headKey = safe(headKey);
            this.composerKey = safe(composerKey);
            this.stateSignature = safe(stateSignature);
            this.source = safe(source);
        }

        static Proof unproven() {
            return new Proof(false, 0L, 0L, 0L, "", "", "", "unproven");
        }
    }

    /** Mutable native mirror; only privacy-safe structural metadata is retained. */
    static final class Session implements Listener {
        private long probeEpoch;
        private long remoteEpoch;
        private long revalidationEpoch;
        private long lastEventSequence;
        private long dirtySinceSequence;
        private long clientStateSequence;
        private long lastRevalidationSequence;
        private long lastRemoteSequence;
        private long lastChannelCloseSequence;
        private boolean channelOpen;
        private boolean dirty = true;
        private String dirtyReason = "initial";
        private String headKey = "";
        private String composerKey = "";
        private String stateSignature = "";
        private String headAtDirty = "";

        @Override public synchronized void onConversationSyncEvent(Event event) {
            if (event == null || event.sequence <= lastEventSequence) return;
            lastEventSequence = event.sequence;
            switch (event.type) {
                case DOCUMENT_READY -> {
                    probeEpoch++;
                    channelOpen = false;
                    markDirty(event.sequence, "document_start");
                }
                case CONVERSATION_CHANNEL_OPEN -> {
                    channelOpen = true;
                    if (lastChannelCloseSequence > 0L) markDirty(event.sequence, "channel_reconnected");
                }
                case CONVERSATION_CHANNEL_CLOSED -> {
                    channelOpen = false;
                    lastChannelCloseSequence = event.sequence;
                    markDirty(event.sequence, "channel_closed");
                }
                case CONVERSATION_REMOTE_UPDATE -> {
                    remoteEpoch++;
                    lastRemoteSequence = event.sequence;
                    if (event.conversationMatch) markDirty(event.sequence, "remote_update");
                }
                case UNCLASSIFIED_CHANNEL_TRAFFIC -> markDirty(event.sequence, "unclassified_channel_traffic");
                case CONVERSATION_REVALIDATION_START -> {
                    if (event.conversationMatch) markDirty(event.sequence, "revalidation_started");
                }
                case CONVERSATION_REVALIDATION_COMPLETE -> {
                    if (event.conversationMatch && event.httpStatus > 0 && event.httpStatus < 400) {
                        revalidationEpoch++;
                        lastRevalidationSequence = event.sequence;
                    }
                }
                case COMPOSER_REPLACED -> markDirty(event.sequence, "composer_replaced");
                case CLIENT_STATE_RESET -> markDirty(event.sequence, "client_state_reset");
                case CLIENT_STATE -> {
                    headKey = event.headKey;
                    composerKey = event.composerKey;
                    stateSignature = event.stateSignature;
                    clientStateSequence = event.sequence;
                    tryClearDirty();
                }
                default -> { }
            }
        }

        private void markDirty(long sequence, String reason) {
            if (!dirty) headAtDirty = headKey;
            dirty = true;
            dirtySinceSequence = Math.max(dirtySinceSequence, sequence);
            dirtyReason = safe(reason);
        }

        private void tryClearDirty() {
            if (!channelOpen || headKey.isEmpty() || composerKey.isEmpty() || stateSignature.isEmpty()) return;
            if ("document_start".equals(dirtyReason) && clientStateSequence > dirtySinceSequence) {
                dirty = false;
                return;
            }
            if ("remote_update".equals(dirtyReason)
                    && lastRemoteSequence >= dirtySinceSequence
                    && clientStateSequence > lastRemoteSequence
                    && !headKey.equals(headAtDirty)) {
                dirty = false;
                return;
            }
            if (lastRevalidationSequence > dirtySinceSequence
                    && clientStateSequence > lastRevalidationSequence) {
                dirty = false;
            }
        }

        synchronized void forceDirty(String reason) {
            markDirty(lastEventSequence + 1L, reason);
        }

        synchronized boolean isDirty() { return dirty; }
        synchronized boolean channelOpen() { return channelOpen; }
        synchronized String dirtyReason() { return dirtyReason; }
        synchronized String composerKey() { return composerKey; }
        synchronized String headKey() { return headKey; }

        synchronized Proof proof() {
            if (dirty || !channelOpen || headKey.isEmpty() || composerKey.isEmpty() || stateSignature.isEmpty()) {
                return Proof.unproven();
            }
            String source = lastRevalidationSequence > dirtySinceSequence ? "revalidation"
                    : (lastRemoteSequence > 0L ? "remote_render" : "document_load");
            return new Proof(true, probeEpoch, remoteEpoch, revalidationEpoch,
                    headKey, composerKey, stateSignature, source);
        }
    }

    private ConversationSyncInstrumentation() { }

    static boolean supported() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
    }

    static boolean install(WebView webView, Listener listener) {
        if (webView == null || listener == null || !supported()) return false;
        try {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, TRUSTED_ORIGINS,
                    new WebViewCompat.WebMessageListener() {
                        @Override public void onPostMessage(WebView view, WebMessageCompat message,
                                                            Uri sourceOrigin, boolean isMainFrame,
                                                            JavaScriptReplyProxy replyProxy) {
                            if (view != webView || !isMainFrame || !trustedOrigin(sourceOrigin)) return;
                            if (message == null || message.getType() != WebMessageCompat.TYPE_STRING) return;
                            String data = message.getData();
                            if (data == null || data.isEmpty() || data.length() > MAX_MESSAGE_CHARS) return;
                            Event event = parse(data);
                            if (event != null) listener.onConversationSyncEvent(event);
                        }
                    });
            WebViewCompat.addDocumentStartJavaScript(webView, documentStartScript(), TRUSTED_ORIGINS);
            return true;
        } catch (Throwable unsupported) {
            return false;
        }
    }

    static boolean trustedOrigin(Uri origin) {
        if (origin == null || !"https".equalsIgnoreCase(origin.getScheme())) return false;
        String host = origin.getHost();
        if (host == null) return false;
        if (!("chatgpt.com".equalsIgnoreCase(host) || "www.chatgpt.com".equalsIgnoreCase(host))) return false;
        int port = origin.getPort();
        return port == -1 || port == 443;
    }

    private static Event parse(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optInt("v", -1) != 1) return null;
            long sequence = o.optLong("seq", -1L);
            if (sequence < 0L) return null;
            String typeRaw = o.optString("type", "UNKNOWN");
            Type type;
            try { type = Type.valueOf(typeRaw); } catch (IllegalArgumentException badType) { type = Type.UNKNOWN; }
            return new Event(type, sequence, o.optInt("cm", 0) == 1,
                    o.optInt("status", 0), bounded(o.optString("head", ""), 96),
                    bounded(o.optString("composer", ""), 96), bounded(o.optString("sig", ""), 96),
                    o.optInt("turns", 0), bounded(o.optString("reason", ""), 80));
        } catch (Throwable malformed) {
            return null;
        }
    }

    private static String bounded(String value, int max) {
        String safe = safe(value);
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static String safe(String value) { return value == null ? "" : value; }

    static String documentStartScript() {
        return """
                (()=>{
                  const bridge=globalThis.__selfRunDriveSyncBridge;
                  if(!bridge||typeof bridge.postMessage!=='function'||globalThis.__selfRunDriveSyncProbeV1)return;
                  const state={v:1,seq:0,wsOpen:0,lastSig:'',lastComposer:'',composerIds:new WeakMap(),composerSeq:0,scheduled:false};
                  globalThis.__selfRunDriveSyncProbeV1=state;
                  const emit=(type,x={})=>{try{bridge.postMessage(JSON.stringify({v:1,seq:++state.seq,type,cm:x.cm?1:0,status:Number(x.status||0),head:String(x.head||'').slice(0,96),composer:String(x.composer||'').slice(0,96),sig:String(x.sig||'').slice(0,96),turns:Number(x.turns||0),reason:String(x.reason||'').slice(0,80)}));}catch(_){}};
                  const invalidate=()=>{try{globalThis.__selfRunDriveFreshnessToken='';globalThis.__selfRunDrivePreparedContinuation=null;}catch(_){}};
                  const parts=()=>location.pathname.split('/').filter(Boolean);
                  const conversationId=()=>{const p=parts(),i=p.indexOf('c');return i>=0&&i+1<p.length?p[i+1]:'';};
                  const fnv=s=>{let h=2166136261;for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619);}return('00000000'+(h>>>0).toString(16)).slice(-8);};
                  const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
                  const turnNodes=()=>[...document.querySelectorAll('main [data-message-author-role],main [data-testid^="conversation-turn"],main article[data-testid^="conversation-turn"]')].filter(visible);
                  const headKey=()=>{const xs=turnNodes(),e=xs.length?xs[xs.length-1]:null;if(!e)return'';const raw=[e.getAttribute?.('data-message-id'),e.getAttribute?.('data-id'),e.id,e.getAttribute?.('data-testid')].filter(Boolean).join('|');return raw?fnv(raw):'';};
                  const composers=()=>[...document.querySelectorAll('textarea#prompt-textarea,textarea[data-testid="prompt-textarea"],div#prompt-textarea[contenteditable="true"],main form [contenteditable="true"][data-lexical-editor="true"],main form [contenteditable="true"]')].filter(e=>visible(e)&&!e.closest('[data-message-author-role],[data-testid^="conversation-turn"],article[data-testid^="conversation-turn"],[role="dialog"]'));
                  const composerKey=()=>{const xs=composers(),e=xs.length?xs[xs.length-1]:null;if(!e)return'';let id=state.composerIds.get(e);if(!id){id='c'+(++state.composerSeq);state.composerIds.set(e,id);}return id;};
                  const snapshot=reason=>{const head=headKey(),composer=composerKey(),turns=turnNodes().length,sig=fnv(head+'|'+composer+'|'+turns);if(state.lastComposer&&composer&&state.lastComposer!==composer){invalidate();emit('COMPOSER_REPLACED',{reason:'composer_replaced'});}state.lastComposer=composer;if(sig!==state.lastSig){state.lastSig=sig;emit('CLIENT_STATE',{head,composer,sig,turns,reason});}};
                  const scheduleSnapshot=reason=>{if(state.scheduled)return;state.scheduled=true;setTimeout(()=>{state.scheduled=false;snapshot(reason);},50);};
                  emit('DOCUMENT_READY',{reason:'document_start'});
                  const NativeWebSocket=globalThis.WebSocket;
                  if(typeof NativeWebSocket==='function'){
                    const Wrapped=new Proxy(NativeWebSocket,{construct(target,args,newTarget){const ws=Reflect.construct(target,args,newTarget);ws.addEventListener('open',()=>{state.wsOpen++;emit('CONVERSATION_CHANNEL_OPEN',{reason:'websocket_open'});},{passive:true});ws.addEventListener('close',()=>{state.wsOpen=Math.max(0,state.wsOpen-1);invalidate();emit('CONVERSATION_CHANNEL_CLOSED',{reason:'websocket_close'});},{passive:true});ws.addEventListener('message',ev=>{try{const cid=conversationId();if(!cid)return;if(typeof ev.data==='string'){const cm=ev.data.includes(cid);if(cm){invalidate();emit('CONVERSATION_REMOTE_UPDATE',{cm:true,reason:'websocket_message'});scheduleSnapshot('remote_message');}}else{invalidate();emit('UNCLASSIFIED_CHANNEL_TRAFFIC',{reason:'opaque_websocket_message'});}}catch(_){invalidate();emit('UNCLASSIFIED_CHANNEL_TRAFFIC',{reason:'websocket_inspection_error'});}}, {passive:true});return ws;}});
                    try{Object.setPrototypeOf(Wrapped,NativeWebSocket);}catch(_){}
                    globalThis.WebSocket=Wrapped;
                  }
                  const NativeFetch=globalThis.fetch;
                  if(typeof NativeFetch==='function'){
                    globalThis.fetch=new Proxy(NativeFetch,{apply(target,thisArg,args){let requestUrl='';try{const a=args[0];requestUrl=typeof a==='string'?a:String(a?.url||'');}catch(_){}const cid=conversationId(),cm=!!cid&&requestUrl.includes(cid);if(cm)emit('CONVERSATION_REVALIDATION_START',{cm:true,reason:'fetch_start'});let promise;try{promise=Reflect.apply(target,thisArg,args);}catch(error){throw error;}return Promise.resolve(promise).then(response=>{const status=Number(response?.status||0);if(cm){emit('CONVERSATION_REVALIDATION_COMPLETE',{cm:true,status,reason:'fetch_complete'});scheduleSnapshot('revalidation');return response;}try{const len=Number(response?.headers?.get?.('content-length')||0),ct=String(response?.headers?.get?.('content-type')||'');if(cid&&(!len||len<=1048576)&&/json|text/i.test(ct)){response.clone().text().then(text=>{if(typeof text==='string'&&text.includes(cid)){emit('CONVERSATION_REVALIDATION_COMPLETE',{cm:true,status,reason:'fetch_response_match'});scheduleSnapshot('revalidation_response');}}).catch(()=>{});}}catch(_){}return response;});}});
                  }
                  const installObserver=()=>{if(!document.documentElement)return false;try{const mo=new MutationObserver(()=>scheduleSnapshot('dom_mutation'));mo.observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['id','data-testid','data-message-id','data-id','aria-label','title','role']});globalThis.addEventListener('pagehide',()=>{invalidate();emit('CLIENT_STATE_RESET',{reason:'pagehide'});},{once:true});scheduleSnapshot('observer_start');return true;}catch(_){return false;}};
                  if(!installObserver())document.addEventListener('DOMContentLoaded',()=>{installObserver();},{once:true});
                })();
                """;
    }
}
