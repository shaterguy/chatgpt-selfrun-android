package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.function.Consumer;

/** Replaceable browser port. Uses verified input/profile transports, never the V2 execution machine. */
final class SelfRun3WebAdapter {
    interface Listener {
        void onPrepared(String task, String turn, String request);
        void onStarted(String task, String turn, String request);
        void onAccepted(String task, String turn, String request);
        void onEnded(String task, String turn, String request, String source);
        void onConversation(String task, String turn, String url);
        void onUnsent(String task, String turn, String request, String status);
        void onFailure(String task, String turn, String request, String code);
        void onDispatched(String task, String turn, String request);
    }
    private static SelfRun3WebAdapter active;
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HeadlessWebViewHost host;
    private WebView web;
    private SelfRun3Engine.State state;
    private boolean preparing, loading, closed;
    private int step, evaluation, generation;
    private long prepareStarted;
    private Consumer<JSONObject> pendingInspection;

    SelfRun3WebAdapter(Context context, Listener listener) { this.context = context; this.listener = listener; }
    static void protocolEvent(WebView view, JSONObject event) {
        SelfRun3WebAdapter a = active;
        if (a == null || a.closed || a.web != view || a.state == null) return;
        SelfRun3Engine.State s = a.state;
        if (!s.taskId().equals(event.optString("runId")) || !s.requestId().equals(event.optString("turnToken"))) return;
        String stage = event.optString("stage"), source = event.optString("source");
        if ("turn_request".equals(stage)) a.listener.onStarted(s.taskId(), s.turnId(), s.requestId());
        else if ("answering_started".equals(stage)) a.listener.onAccepted(s.taskId(), s.turnId(), s.requestId());
        else if (("complete".equals(stage) || "completion_dispatch".equals(stage)) && TurnProtocolLogBridge.isAllowedCompletionSource(source))
            a.listener.onEnded(s.taskId(), s.turnId(), s.requestId(), source);
        else if ("error".equals(stage)) a.listener.onFailure(s.taskId(), s.turnId(), s.requestId(), "TRANSPORT_INTERRUPTED");
        a.captureConversation();
    }
    void prepare(SelfRun3Engine.State s) {
        requireMain();
        boolean newAttempt = state == null || !state.requestId().equals(s.requestId());
        state = s; closed = false; preparing = true;
        if (newAttempt) { step = 0; prepareStarted = SystemClock.elapsedRealtime(); }
        ensureWeb(false);
        if (host != null) host.attachOutput();
        if (!loading) advance();
    }
    private void ensureWeb(boolean detached) {
        if (web != null) return;
        String target = state.resource("conversationUrl");
        if (target.isEmpty()) target = state.config().optString("projectUrl");
        if (!trusted(target)) { fail("TARGET_INVALID"); return; }
        host = HeadlessWebViewHost.create(context); web = host.webView(); active = this;
        if (detached) host.detachOutput();
        if (!WebViewConfig.applyAutomation(web)) { fail("TURN_PROTOCOL_UNAVAILABLE"); return; }
        loading = true;
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) { generation++; evaluation++; loading = true; }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != web || closed) return;
                loading = false; captureConversation();
                if (pendingInspection != null) { Consumer<JSONObject> cb = pendingInspection; pendingInspection = null; inspect(state, cb); }
                else if (preparing) later(SelfRun3WebAdapter.this::advance, 500L);
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) { if (view == web && !closed) captureConversation(); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (view != web || closed) return true;
                if (!request.isForMainFrame()) return false;
                Uri u = request.getUrl();
                if ("selfrun-drive".equals(u.getScheme()) && "turn-completed".equals(u.getHost())) {
                    if (state.taskId().equals(u.getQueryParameter("run")) && state.requestId().equals(u.getQueryParameter("token"))
                            && TurnProtocolLogBridge.isAllowedCompletionSource(u.getQueryParameter("source")))
                        listener.onEnded(state.taskId(), state.turnId(), state.requestId(), u.getQueryParameter("source"));
                    return true;
                }
                if (!allowedRoute(String.valueOf(u))) { fail("ROUTE_MISMATCH"); return true; }
                return false;
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler response, SslError error) { response.cancel(); fail("TLS_REJECTED"); }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view == web && request.isForMainFrame()) fail("WEB_CONNECTION_FAILED");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view == web) { disposeHost(); fail("RENDERER_GONE"); } return true;
            }
        });
        web.loadUrl(target);
    }
    private void advance() {
        if (!preparing || closed || loading || web == null || state == null) return;
        if (SystemClock.elapsedRealtime() - prepareStarted >= SelfRun3PowerPolicy.WEB_PREPARATION_MAX_MS) { fail("WEB_PREPARATION_TIMEOUT"); return; }
        boolean initial = state.resource("conversationUrl").isEmpty();
        String script;
        if (step == 0 && initial) {
            BootstrapRunStateStore.touchBootstrap(context, state.taskId(), ChatReasoningPreferenceStore.selectionForRun(context, state.taskId()), System.currentTimeMillis());
            script = SelfRunDom.prepareInitialContext(state.config().optString("projectUrl"), state.config().optString("mode"), state.taskId());
        } else if (step <= 1) {
            step = 1; script = profileScript(state);
        } else {
            script = initial ? SelfRunContinuationDom.prepareBootstrap(state.config().optString("projectUrl"), state.text("prompt"), marker(state))
                    : SelfRunContinuationDom.prepareDriveTurn(state.resource("conversationUrl"), state.text("prompt"), marker(state));
        }
        evaluate(script, result -> {
            String status = result.optString("status");
            if ("READY".equals(status) && step < 2) { step++; later(this::advance, 0L); return; }
            if ("READY_TO_SUBMIT".equals(status)) {
                preparing = false;
                listener.onPrepared(state.taskId(), state.turnId(), state.requestId()); return;
            }
            if ("SUBMISSION_CONFIRMED".equals(status)) { preparing = false; listener.onAccepted(state.taskId(), state.turnId(), state.requestId()); detach(); return; }
            if ("AUTH_REQUIRED".equals(status) || status.endsWith("_FAILED") || status.endsWith("_UNAVAILABLE")
                    || "PROFILE_ERROR".equals(status) || "TARGET_ERROR".equals(status)) { fail(status); return; }
            later(this::advance, SelfRun3PowerPolicy.WEB_STEP_RETRY_MS);
        });
    }
    void submit(SelfRun3Engine.State claimed) {
        requireMain();
        if (web == null || closed || !SelfRun3PowerPolicy.maySend(claimed)) { fail("SUBMISSION_STATE_INVALID"); return; }
        state = claimed; preparing = false;
        boolean initial = claimed.resource("conversationUrl").isEmpty();
        String action = initial ? SelfRunContinuationDom.clickPreparedBootstrap(claimed.config().optString("projectUrl"), claimed.text("prompt"), marker(claimed))
                : SelfRunContinuationDom.clickPreparedDriveTurn(claimed.resource("conversationUrl"), claimed.text("prompt"), marker(claimed), "");
        String wrapped = "(()=>{const p=window.__selfRunTurnProtocol; if(!p.armCompletion(" + q(claimed.taskId()) + "," + q(claimed.requestId())
                + "))return JSON.stringify({status:'TURN_PROTOCOL_UNAVAILABLE'});return (" + action + ");})()";
        evaluate(ChatGptTurnProtocolScript.bindTurnAndThen(claimed.taskId(), claimed.requestId(), wrapped), result -> {
            String status = result.optString("status");
            detach();
            if (java.util.Set.of("SEND_DISABLED", "STOP", "COMPOSER_CLEARING", "COMPOSER_INPUTTING", "TARGET_ERROR", "AUTH_REQUIRED", "TURN_PROTOCOL_BUSY", "TURN_PROTOCOL_UNAVAILABLE").contains(status))
                listener.onUnsent(claimed.taskId(), claimed.turnId(), claimed.requestId(), status);
            else if ("CALLBACK_AMBIGUOUS".equals(status) || "SCRIPT_ERROR".equals(status)) fail("SUBMISSION_OUTCOME_UNKNOWN");
            else listener.onDispatched(claimed.taskId(), claimed.turnId(), claimed.requestId());
            captureConversation();
        });
    }
    void inspect(SelfRun3Engine.State s, Consumer<JSONObject> callback) {
        requireMain(); state = s;
        if (web == null) {
            if (s.resource("conversationUrl").isEmpty()) { callback.accept(object("{\"ready\":false,\"reason\":\"conversation_unknown\"}")); return; }
            preparing = false; pendingInspection = callback; ensureWeb(true);
            if (web == null) { pendingInspection = null; callback.accept(object("{\"ready\":false}")); }
            return;
        }
        if (loading) { pendingInspection = callback; return; }
        detach(); evaluate(inspectionScript(s), callback);
    }
    static String inspectionScript(SelfRun3Engine.State s) {
        return "(()=>{const out={ready:false,complete:false,accepted:false,receipt:false,signature:'',source:''};"
                + "if(location.protocol!=='https:'||!['chatgpt.com','www.chatgpt.com'].includes(location.hostname))return JSON.stringify(out);"
                + "const p=window.__selfRunTurnProtocol?.snapshot?.();if(p&&p.runId===" + q(s.taskId()) + "&&p.turnToken===" + q(s.requestId()) + "){"
                + "out.accepted=p.sawVisibleAnswer===true||p.sawStreamComplete===true;"
                + "if(p.phase==='COMPLETE'&&['message_stream_complete','finished_successfully_end_turn'].includes(p.completionSource)){out.complete=true;out.source=p.completionSource;return JSON.stringify(out);}}"
                + "const expected=" + q(SelfRunScript.conversationId(s.resource("conversationUrl"))) + ";const parts=location.pathname.split('/').filter(Boolean),ci=parts.indexOf('c');"
                + "if(!expected||ci<0||parts[ci+1]!==expected)return JSON.stringify(out);"
                + "const messages=[...document.querySelectorAll('[data-message-author-role]')];const users=messages.filter(e=>e.getAttribute('data-message-author-role')==='user');"
                + "const assistants=messages.filter(e=>e.getAttribute('data-message-author-role')==='assistant');const u=users.at(-1),a=assistants.at(-1);"
                + "if(!u||!a||!String(u.textContent||'').includes(" + q(s.turnId()) + "))return JSON.stringify(out);"
                + "if(!(u.compareDocumentPosition(a)&Node.DOCUMENT_POSITION_FOLLOWING))return JSON.stringify(out);"
                + "const text=String(a.innerText||a.textContent||'').trim();out.receipt=text.endsWith(" + q(SelfRun3Protocol.receipt(s)) + ");"
                + "const c=document.querySelector('textarea#prompt-textarea,div#prompt-textarea[contenteditable=true],main form [contenteditable=true]');"
                + "const stop=[...document.querySelectorAll('[data-testid=stop-button],[data-testid=stop-generating-button],[data-testid=composer-stop-button]')].some(e=>e.isConnected&&e.offsetParent!==null);"
                + "out.ready=out.receipt&&!!c&&c.isConnected&&!c.disabled&&!c.readOnly&&!stop;"
                + "out.signature=out.ready?" + q(s.turnId() + ":") + "+String(a.getAttribute('data-message-id')||'')+':'+text.length:'';"
                + "return JSON.stringify(out);})()";
    }
    private static String profileScript(SelfRun3Engine.State s) {
        JSONObject c = s.config(); String calls = RequestProfileScript.beginTarget(c.optString("mode"), s.taskId());
        if ("WORK".equals(c.optString("mode"))) calls += RequestProfileScript.setWorkModel(c.optString("model")) + RequestProfileScript.setWorkReasoning(c.optString("reasoning"));
        else calls += RequestProfileScript.setChatReasoning(c.optString(s.turn() == 1 ? "chatBootstrap" : "chatContinuation"));
        return "(()=>{try{" + calls + "return JSON.stringify({status:'READY'});}catch(_){return JSON.stringify({status:'PROFILE_ERROR'});}})()";
    }
    private void evaluate(String script, Consumer<JSONObject> callback) {
        WebView current = web; int id = ++evaluation, page = generation; String request = state.requestId();
        handler.postDelayed(() -> {
            if (current != web || id != evaluation || page != generation || closed || !request.equals(state.requestId())) return;
            evaluation++; callback.accept(object("{\"status\":\"CALLBACK_AMBIGUOUS\"}"));
        }, SelfRun3PowerPolicy.CALLBACK_TIMEOUT_MS);
        try {
            current.evaluateJavascript(script, raw -> {
                if (current != web || id != evaluation || page != generation || closed || !request.equals(state.requestId())) return;
                evaluation++;
                try { Object v = new JSONTokener(raw == null ? "null" : raw).nextValue(); callback.accept(v instanceof String ? object((String)v) : v instanceof JSONObject ? (JSONObject)v : object("{\"status\":\"CALLBACK_AMBIGUOUS\"}")); }
                catch (Exception e) { callback.accept(object("{\"status\":\"CALLBACK_AMBIGUOUS\"}")); }
            });
        } catch (Throwable e) { evaluation++; callback.accept(object("{\"status\":\"CALLBACK_AMBIGUOUS\"}")); }
    }
    private void captureConversation() {
        if (web == null || state == null || !state.flag("sendClaimed")) return;
        String url = web.getUrl();
        if (allowedRoute(url) && !SelfRunScript.conversationId(url).isEmpty()) listener.onConversation(state.taskId(), state.turnId(), url);
    }
    private boolean allowedRoute(String url) {
        if (!trusted(url) || state == null) return false;
        String known = state.resource("conversationUrl");
        if (!known.isEmpty()) return SelfRunScript.conversationId(known).equals(SelfRunScript.conversationId(url));
        return SelfRunScript.projectId(state.config().optString("projectUrl")).equals(SelfRunScript.projectId(url));
    }
    static boolean trusted(String raw) {
        try { Uri u = Uri.parse(raw); return "https".equals(u.getScheme()) && ("chatgpt.com".equals(u.getHost()) || "www.chatgpt.com".equals(u.getHost())) && (u.getPort() == -1 || u.getPort() == 443) && u.getUserInfo() == null; }
        catch (Exception e) { return false; }
    }
    void detach() { requireMain(); if (host != null) host.detachOutput(); }
    void quiesce() { preparing = false; evaluation++; handler.removeCallbacksAndMessages(null); detach(); }
    void close() { requireMain(); closed = true; preparing = false; evaluation++; handler.removeCallbacksAndMessages(null); pendingInspection = null; disposeHost(); }
    private void disposeHost() { if (active == this) active = null; if (host != null) host.destroy(); host = null; web = null; loading = false; }
    private void fail(String code) { preparing = false; detach(); if (state != null) listener.onFailure(state.taskId(),state.turnId(),state.requestId(),code); }
    private void later(Runnable action, long delay) { String request = state.requestId(); handler.postDelayed(() -> { if (!closed && state != null && request.equals(state.requestId())) action.run(); }, delay); }
    private static String marker(SelfRun3Engine.State s) { return "v3-" + s.requestId(); }
    private static String q(String value) { return SelfRunScript.quote(value); }
    private static JSONObject object(String raw) { try { return new JSONObject(raw); } catch (Exception e) { return new JSONObject(); } }
    private static void requireMain() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("browser port requires main thread"); }
}
