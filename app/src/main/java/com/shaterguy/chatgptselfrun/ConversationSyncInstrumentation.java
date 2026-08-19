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
 * Privacy-safe document-start observation for the ChatGPT page's own update path.
 * No private endpoint, payload schema, message id, token, cookie, prompt, or assistant body is parsed.
 */
final class ConversationSyncInstrumentation {
    static final String BRIDGE_NAME = "__selfRunDriveSyncBridge";
    static final String PAGE_STATE_NAME = "__selfRunDriveSyncProbeV2";
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
        CHANNEL_ACTIVITY,
        PAGE_FETCH_START,
        PAGE_FETCH_COMPLETE,
        CLIENT_STATE,
        COMPOSER_REPLACED,
        CLIENT_STATE_RESET,
        UNKNOWN
    }

    static final class Event {
        final Type type;
        final long sequence;
        final int httpStatus;
        final int networkId;
        final String headKey;
        final String composerKey;
        final String stateSignature;
        final int turnCount;
        final String reason;

        Event(Type type, long sequence, int httpStatus, int networkId,
              String headKey, String composerKey, String stateSignature,
              int turnCount, String reason) {
            this.type = type == null ? Type.UNKNOWN : type;
            this.sequence = sequence;
            this.httpStatus = httpStatus;
            this.networkId = Math.max(0, networkId);
            this.headKey = bounded(headKey, 96);
            this.composerKey = bounded(composerKey, 96);
            this.stateSignature = bounded(stateSignature, 96);
            this.turnCount = Math.max(0, turnCount);
            this.reason = bounded(reason, 80);
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
        final long eventSequence;
        final String headKey;
        final String composerKey;
        final String stateSignature;
        final String source;

        private Proof(boolean proven, long probeEpoch, long remoteEpoch,
                      long revalidationEpoch, long eventSequence,
                      String headKey, String composerKey,
                      String stateSignature, String source) {
            this.proven = proven;
            this.probeEpoch = probeEpoch;
            this.remoteEpoch = remoteEpoch;
            this.revalidationEpoch = revalidationEpoch;
            this.eventSequence = eventSequence;
            this.headKey = safe(headKey);
            this.composerKey = safe(composerKey);
            this.stateSignature = safe(stateSignature);
            this.source = safe(source);
        }

        static Proof unproven() {
            return new Proof(false, 0L, 0L, 0L, 0L, "", "", "", "unproven");
        }

        String tokenPart() {
            if (!proven) return "";
            return probeEpoch + ":" + remoteEpoch + ":" + revalidationEpoch + ":"
                    + eventSequence + ":" + headKey + ":" + composerKey + ":" + stateSignature;
        }
    }

    /**
     * Native structural mirror. Generic channel activity is deliberately not classified as a
     * conversation update until the active conversation's structural head actually changes.
     */
    static final class Session implements Listener {
        private long probeEpoch;
        private long remoteEpoch;
        private long revalidationEpoch;
        private long lastEventSequence;
        private long dirtySinceSequence;
        private long clientStateSequence;
        private long lastChannelActivitySequence;
        private long lastChannelCloseSequence;
        private long lastFetchStartSequence;
        private long lastFetchCompleteSequence;
        private int lastCompletedNetworkId;
        private boolean channelOpen;
        private boolean dirty = true;
        private String dirtyReason = "initial";
        private String proofSource = "unproven";
        private String headKey = "";
        private String composerKey = "";
        private String stateSignature = "";
        private String headAtDirty = "";
        private String composerAtDirty = "";

        @Override public synchronized void onConversationSyncEvent(Event event) {
            if (event == null) return;
            if (event.type == Type.DOCUMENT_READY && event.sequence <= lastEventSequence) {
                resetDocumentSequence();
            }
            if (event.sequence <= lastEventSequence) return;
            lastEventSequence = event.sequence;
            switch (event.type) {
                case DOCUMENT_READY -> {
                    probeEpoch++;
                    channelOpen = false;
                    headKey = "";
                    composerKey = "";
                    stateSignature = "";
                    markDirty(event.sequence, "document_start");
                }
                case CONVERSATION_CHANNEL_OPEN -> {
                    boolean reconnect = lastChannelCloseSequence > 0L;
                    channelOpen = true;
                    if (reconnect) markDirty(event.sequence, "channel_reconnected");
                }
                case CONVERSATION_CHANNEL_CLOSED -> {
                    channelOpen = false;
                    lastChannelCloseSequence = event.sequence;
                    markDirty(event.sequence, "channel_closed");
                }
                case CHANNEL_ACTIVITY -> {
                    lastChannelActivitySequence = event.sequence;
                    markDirty(event.sequence, "channel_activity_pending");
                }
                case PAGE_FETCH_START -> lastFetchStartSequence = event.sequence;
                case PAGE_FETCH_COMPLETE -> {
                    if (event.httpStatus > 0 && event.httpStatus < 400) {
                        lastFetchCompleteSequence = event.sequence;
                        lastCompletedNetworkId = event.networkId;
                    }
                }
                case COMPOSER_REPLACED -> markDirty(event.sequence, "composer_replaced");
                case CLIENT_STATE_RESET -> markDirty(event.sequence, "client_state_reset");
                case CLIENT_STATE -> applyClientState(event);
                default -> markDirty(event.sequence, "unknown_probe_event");
            }
        }

        private void resetDocumentSequence() {
            lastEventSequence = 0L;
            dirtySinceSequence = 0L;
            clientStateSequence = 0L;
            lastChannelActivitySequence = 0L;
            lastChannelCloseSequence = 0L;
            lastFetchStartSequence = 0L;
            lastFetchCompleteSequence = 0L;
            lastCompletedNetworkId = 0;
            channelOpen = false;
            dirty = true;
            dirtyReason = "document_rollover";
            proofSource = "unproven";
            headKey = "";
            composerKey = "";
            stateSignature = "";
            headAtDirty = "";
            composerAtDirty = "";
        }

        private void applyClientState(Event event) {
            String priorHead = headKey;
            String priorComposer = composerKey;
            headKey = event.headKey;
            composerKey = event.composerKey;
            stateSignature = event.stateSignature;
            clientStateSequence = event.sequence;

            if (!channelOpen || headKey.isEmpty() || composerKey.isEmpty() || stateSignature.isEmpty()) return;

            if ("document_start".equals(dirtyReason)
                    && clientStateSequence > dirtySinceSequence) {
                clearDirty("document_load");
                return;
            }

            if ("channel_activity_pending".equals(dirtyReason)
                    && clientStateSequence > lastChannelActivitySequence
                    && !headKey.equals(headAtDirty)) {
                remoteEpoch++;
                clearDirty("remote_render");
                return;
            }

            if (requiresPageRevalidation(dirtyReason)
                    && lastFetchStartSequence > dirtySinceSequence
                    && lastFetchCompleteSequence > lastFetchStartSequence
                    && clientStateSequence > lastFetchCompleteSequence
                    && lastCompletedNetworkId > 0) {
                revalidationEpoch++;
                clearDirty("page_revalidation");
                return;
            }

            if ("composer_replaced".equals(dirtyReason)
                    && clientStateSequence > dirtySinceSequence
                    && !composerKey.equals(composerAtDirty)) {
                clearDirty("composer_reacquired");
                return;
            }

            if (!dirty && (!headKey.equals(priorHead) || !composerKey.equals(priorComposer))) {
                markDirty(event.sequence, "client_state_changed");
                if (!headKey.isEmpty() && !composerKey.isEmpty() && !stateSignature.isEmpty()) {
                    clearDirty("observed_state_change");
                }
            }
        }

        private static boolean requiresPageRevalidation(String reason) {
            return "channel_reconnected".equals(reason)
                    || "rate_limit".equals(reason)
                    || "manual_resume".equals(reason)
                    || "pause".equals(reason);
        }

        private void markDirty(long sequence, String reason) {
            if (!dirty) {
                headAtDirty = headKey;
                composerAtDirty = composerKey;
            }
            dirty = true;
            dirtySinceSequence = Math.max(dirtySinceSequence, sequence);
            dirtyReason = safe(reason);
            proofSource = "unproven";
        }

        private void clearDirty(String source) {
            dirty = false;
            proofSource = safe(source);
        }

        synchronized void forceDirty(String reason) {
            markDirty(lastEventSequence, reason);
        }

        synchronized boolean isDirty() { return dirty; }
        synchronized boolean channelOpen() { return channelOpen; }
        synchronized String dirtyReason() { return dirtyReason; }
        synchronized String composerKey() { return composerKey; }
        synchronized String headKey() { return headKey; }
        synchronized String stateSignature() { return stateSignature; }
        synchronized long remoteEpoch() { return remoteEpoch; }
        synchronized long eventSequence() { return lastEventSequence; }

        synchronized Proof proof() {
            if (dirty || !channelOpen || headKey.isEmpty()
                    || composerKey.isEmpty() || stateSignature.isEmpty()) {
                return Proof.unproven();
            }
            return new Proof(true, probeEpoch, remoteEpoch, revalidationEpoch,
                    lastEventSequence, headKey, composerKey, stateSignature, proofSource);
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

    static String requestSnapshotScript() {
        return "(()=>{const p=globalThis." + PAGE_STATE_NAME
                + ";if(!p||typeof p.snapshotNow!=='function')return false;p.snapshotNow();return true;})()";
    }

    private static Event parse(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optInt("v", -1) != 2) return null;
            long sequence = o.optLong("seq", -1L);
            if (sequence < 0L) return null;
            Type type;
            try { type = Type.valueOf(o.optString("type", "UNKNOWN")); }
            catch (IllegalArgumentException badType) { type = Type.UNKNOWN; }
            return new Event(type, sequence, o.optInt("status", 0), o.optInt("rid", 0),
                    o.optString("head", ""), o.optString("composer", ""),
                    o.optString("sig", ""), o.optInt("turns", 0),
                    o.optString("reason", ""));
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
                  if(!bridge||typeof bridge.postMessage!=='function'||globalThis.__selfRunDriveSyncProbeV2)return;
                  const state={v:2,seq:0,fetchSeq:0,lastSig:'',lastHead:'',lastComposer:'',current:null,currentComposer:null,composerIds:new WeakMap(),composerSeq:0,scheduled:false};
                  globalThis.__selfRunDriveSyncProbeV2=state;
                  const emit=(type,x={})=>{try{bridge.postMessage(JSON.stringify({v:2,seq:++state.seq,type,status:Number(x.status||0),rid:Number(x.rid||0),head:String(x.head||'').slice(0,96),composer:String(x.composer||'').slice(0,96),sig:String(x.sig||'').slice(0,96),turns:Number(x.turns||0),reason:String(x.reason||'').slice(0,80)}));}catch(_){}};
                  const invalidate=()=>{try{globalThis.__selfRunDriveFreshnessToken='';globalThis.__selfRunDrivePreparedContinuation=null;}catch(_){}};
                  const fnv=s=>{let h=2166136261;for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619);}return('00000000'+(h>>>0).toString(16)).slice(-8);};
                  const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
                  const turnNodes=()=>[...document.querySelectorAll('main [data-message-author-role],main [data-testid^="conversation-turn"],main article[data-testid^="conversation-turn"]')].filter(visible);
                  const lastTurn=()=>{const xs=turnNodes();return xs.length?xs[xs.length-1]:null;};
                  const headKey=()=>{const e=lastTurn();if(!e)return'';const raw=[e.getAttribute?.('data-message-id'),e.getAttribute?.('data-id'),e.id,e.getAttribute?.('data-testid')].filter(Boolean).join('|');return raw?fnv(raw):'';};
                  const composers=()=>[...document.querySelectorAll('textarea#prompt-textarea,textarea[data-testid="prompt-textarea"],div#prompt-textarea[contenteditable="true"],main form [contenteditable="true"][data-lexical-editor="true"],main form [contenteditable="true"]')].filter(e=>visible(e)&&!e.closest('[data-message-author-role],[data-testid^="conversation-turn"],article[data-testid^="conversation-turn"],[role="dialog"]'));
                  const currentComposer=()=>{const xs=composers();return xs.length?xs[xs.length-1]:null;};
                  const composerKey=e=>{if(!e)return'';let id=state.composerIds.get(e);if(!id){id='c'+(++state.composerSeq);state.composerIds.set(e,id);}return id;};
                  const snapshot=(reason,force=false)=>{const c=currentComposer(),head=headKey(),composer=composerKey(c),turns=turnNodes().length,sig=fnv(head+'|'+composer+'|'+turns);if(state.lastComposer&&composer&&state.lastComposer!==composer){invalidate();emit('COMPOSER_REPLACED',{reason:'composer_replaced'});}state.lastHead=head;state.lastComposer=composer;state.current={head,composer,sig,turns};state.currentComposer=c;if(force||sig!==state.lastSig){state.lastSig=sig;emit('CLIENT_STATE',{head,composer,sig,turns,reason});}};
                  const scheduleSnapshot=(reason,force=false)=>{if(state.scheduled&&!force)return;state.scheduled=true;setTimeout(()=>{state.scheduled=false;snapshot(reason,force);},50);};
                  state.snapshotNow=()=>snapshot('native_snapshot',true);
                  emit('DOCUMENT_READY',{reason:'document_start'});
                  const wrapChannel=(Ctor,kind)=>{if(typeof Ctor!=='function')return Ctor;return new Proxy(Ctor,{construct(target,args,newTarget){const channel=Reflect.construct(target,args,newTarget);channel.addEventListener?.('open',()=>emit('CONVERSATION_CHANNEL_OPEN',{reason:kind+'_open'}),{passive:true});channel.addEventListener?.('message',()=>{invalidate();emit('CHANNEL_ACTIVITY',{reason:kind+'_message'});scheduleSnapshot('channel_activity',true);},{passive:true});channel.addEventListener?.('close',()=>{invalidate();emit('CONVERSATION_CHANNEL_CLOSED',{reason:kind+'_close'});},{passive:true});channel.addEventListener?.('error',()=>{invalidate();emit('CONVERSATION_CHANNEL_CLOSED',{reason:kind+'_error'});},{passive:true});return channel;}});};
                  try{if(typeof globalThis.WebSocket==='function'){const Native=globalThis.WebSocket,Wrapped=wrapChannel(Native,'websocket');Object.setPrototypeOf(Wrapped,Native);globalThis.WebSocket=Wrapped;}}catch(_){}
                  try{if(typeof globalThis.EventSource==='function'){const Native=globalThis.EventSource,Wrapped=wrapChannel(Native,'eventsource');Object.setPrototypeOf(Wrapped,Native);globalThis.EventSource=Wrapped;}}catch(_){}
                  const NativeFetch=globalThis.fetch;
                  if(typeof NativeFetch==='function'){
                    globalThis.fetch=new Proxy(NativeFetch,{apply(target,thisArg,args){let sameOrigin=false;try{const a=args[0],u=new URL(typeof a==='string'?a:String(a?.url||''),location.href);sameOrigin=u.origin===location.origin;}catch(_){}const rid=sameOrigin?++state.fetchSeq:0;if(rid)emit('PAGE_FETCH_START',{rid,reason:'same_origin_fetch'});let promise;try{promise=Reflect.apply(target,thisArg,args);}catch(error){throw error;}return Promise.resolve(promise).then(response=>{if(rid){emit('PAGE_FETCH_COMPLETE',{rid,status:Number(response?.status||0),reason:'same_origin_fetch'});scheduleSnapshot('fetch_complete',true);}return response;},error=>{if(rid){emit('PAGE_FETCH_COMPLETE',{rid,status:0,reason:'same_origin_fetch_error'});scheduleSnapshot('fetch_error',true);}throw error;});}});
                  }
                  const installObserver=()=>{if(!document.documentElement)return false;try{const mo=new MutationObserver(()=>scheduleSnapshot('dom_mutation',false));mo.observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['id','data-testid','data-message-id','data-id','aria-label','title','role']});globalThis.addEventListener('pagehide',()=>{invalidate();emit('CLIENT_STATE_RESET',{reason:'pagehide'});},{once:true});scheduleSnapshot('observer_start',true);return true;}catch(_){return false;}};
                  if(!installObserver())document.addEventListener('DOMContentLoaded',()=>installObserver(),{once:true});
                })();
                """;
    }
}
