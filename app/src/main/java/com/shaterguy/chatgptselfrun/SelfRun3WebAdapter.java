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
    private boolean preparing, loading, closed, dispatchConfirmed;
    private int step, evaluation, generation;
    private long prepareStarted;
    private final java.util.Map<String, String[]> confirmedRequests = new java.util.HashMap<>();
    
    SelfRun3WebAdapter(Context context, Listener listener) { this.context = context; this.listener = listener; }
    static void protocolEvent(WebView view, JSONObject event) {
        SelfRun3WebAdapter a = active;
        if (a == null || a.closed || a.web != view || a.state == null) return;
        if ("conversation_identity".equals(event.optString("stage"))) {
            String[] owner = a.confirmedRequests.get(event.optString("turnToken"));
            String id = event.optString("conversationId");
            if (owner != null && owner[0].equals(event.optString("runId"))
                    && owner[1].equals(event.optString("turnId"))
                    && id.matches("[A-Za-z0-9_-]{1,128}"))
                a.listener.onConversation(owner[0], owner[1], "https://chatgpt.com/c/" + id);
            return;
        }
        SelfRun3Engine.State s = a.state;
        if (!s.taskId().equals(event.optString("runId")) || !s.requestId().equals(event.optString("turnToken"))) return;
        if (!"turn_request".equals(event.optString("stage"))
                || !"canonical_post".equals(event.optString("source")) || a.dispatchConfirmed
                || !s.flag("sendClaimed")) return;
        a.dispatchConfirmed = true;
        a.confirmedRequests.put(s.requestId(), new String[]{s.taskId(), s.turnId()});
        a.quiesce();
        a.listener.onStarted(s.taskId(), s.turnId(), s.requestId());
        a.captureConversation();
    }

    void prepare(SelfRun3Engine.State s) {
        requireMain();
        boolean newAttempt = web == null || state == null || !state.requestId().equals(s.requestId());
        state = s; closed = false; preparing = true;
        if (newAttempt) {
            quiesce(); dispatchConfirmed = false; step = 0;
            prepareStarted = SystemClock.elapsedRealtime();
            preparing = true;
        }
        ensureWeb(false);
        if (newAttempt && web != null && !closed) {
            String target = s.config().optString("projectUrl");
            if (!trusted(target)) { fail("TARGET_INVALID"); return; }
            generation++; evaluation++; loading = true; web.loadUrl(target);
        }
        if (host != null) host.attachOutput();
        if (!loading) advance();
    }
    private void ensureWeb(boolean detached) {
        if (web != null) return;
        String target = state.config().optString("projectUrl");
        if (!trusted(target)) { fail("TARGET_INVALID"); return; }
        host = HeadlessWebViewHost.create(context, false); web = host.webView(); active = this;
        if (detached) host.detachOutput();
        if (!WebViewConfig.applySelfRun3Automation(web)) { fail("TURN_PROTOCOL_UNAVAILABLE"); return; }
        loading = true;
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) { generation++; evaluation++; loading = true; }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != web || closed) return;
                loading = false; captureConversation();
                if (preparing) later(SelfRun3WebAdapter.this::advance, 500L);
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) { if (view == web && !closed) captureConversation(); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (view != web || closed) return true;
                if (!request.isForMainFrame()) return false;
                Uri u = request.getUrl();
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
    }
    private void advance() {
        if (!preparing || closed || loading || web == null || state == null) return;
        if (SystemClock.elapsedRealtime() - prepareStarted >= SelfRun3PowerPolicy.WEB_PREPARATION_MAX_MS) { fail("WEB_PREPARATION_TIMEOUT"); return; }
        String script;
        if (step == 0) {
            BootstrapRunStateStore.touchBootstrap(context, state.taskId(), ChatReasoningPreferenceStore.selectionForRun(context, state.taskId()), System.currentTimeMillis());
            script = SelfRunDom.prepareInitialContext(state.config().optString("projectUrl"), state.config().optString("mode"), state.taskId());
        } else if (step <= 1) {
            step = 1; script = profileScript(state);
        } else {
            script = SelfRunContinuationDom.prepareBootstrap(state.config().optString("projectUrl"), state.text("prompt"), marker(state));
        }
        evaluate(script, result -> {
            String status = result.optString("status");
            if ("READY".equals(status) && step < 2) { step++; later(this::advance, 0L); return; }
            if ("READY_TO_SUBMIT".equals(status)) {
                preparing = false;
                listener.onPrepared(state.taskId(), state.turnId(), state.requestId()); return;
            }
            if ("AUTH_REQUIRED".equals(status) || status.endsWith("_FAILED") || status.endsWith("_UNAVAILABLE")
                    || "PROFILE_ERROR".equals(status) || "TARGET_ERROR".equals(status)) { fail(status); return; }
            later(this::advance, SelfRun3PowerPolicy.WEB_STEP_RETRY_MS);
        });
    }
    void submit(SelfRun3Engine.State claimed) {
        requireMain();
        if (web == null || closed || dispatchConfirmed || !SelfRun3PowerPolicy.maySend(claimed)) { fail("SUBMISSION_STATE_INVALID"); return; }
        state = claimed; preparing = false;
        String action = SelfRunContinuationDom.clickPreparedBootstrap(claimed.config().optString("projectUrl"), claimed.text("prompt"), marker(claimed));
        String wrapped = "(()=>{if(!" + SelfRun3DispatchScript.arm(claimed.taskId(), claimed.turnId(), claimed.requestId())
                + ")return JSON.stringify({status:'TURN_PROTOCOL_UNAVAILABLE'});return (" + action + ");})()";
        evaluate(wrapped, result -> {
            String status = result.optString("status");
            if (java.util.Set.of("SEND_DISABLED", "STOP", "COMPOSER_CLEARING", "COMPOSER_INPUTTING", "TARGET_ERROR", "AUTH_REQUIRED", "TURN_PROTOCOL_BUSY", "TURN_PROTOCOL_UNAVAILABLE").contains(status))
                listener.onUnsent(claimed.taskId(), claimed.turnId(), claimed.requestId(), status);
            else if ("CALLBACK_AMBIGUOUS".equals(status) || "SCRIPT_ERROR".equals(status)) fail("SUBMISSION_OUTCOME_UNKNOWN");
            else {
                listener.onDispatched(claimed.taskId(), claimed.turnId(), claimed.requestId());
                later(() -> {
                    if (!dispatchConfirmed) fail("SUBMISSION_OUTCOME_UNKNOWN");
                }, SelfRun3PowerPolicy.CALLBACK_TIMEOUT_MS);
            }
            captureConversation();
        });
    }
    private static String profileScript(SelfRun3Engine.State s) {
        JSONObject c = s.config();
        String calls = RequestProfileScript.beginTarget(c.optString("mode"), s.taskId());
        if ("WORK".equals(c.optString("mode"))) {
            calls += RequestProfileScript.setWorkModel(c.optString("model"))
                    + RequestProfileScript.setWorkReasoning(c.optString("reasoning"));
        } else {
            calls += RequestProfileScript.setChatReasoning(c.optString("reasoning",
                    s.turn() == 1 ? c.optString("chatBootstrap") : c.optString("chatContinuation")));
        }
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
        if (web == null || state == null || !dispatchConfirmed) return;
        String url = web.getUrl();
        if (allowedRoute(url) && !SelfRunScript.conversationId(url).isEmpty()) listener.onConversation(state.taskId(), state.turnId(), url);
    }
    private boolean allowedRoute(String url) {
        if (!trusted(url) || state == null) return false;
        return SelfRunScript.projectId(state.config().optString("projectUrl")).equals(SelfRunScript.projectId(url));
    }
    static boolean trusted(String raw) {
        try { Uri u = Uri.parse(raw); return "https".equals(u.getScheme()) && ("chatgpt.com".equals(u.getHost()) || "www.chatgpt.com".equals(u.getHost())) && (u.getPort() == -1 || u.getPort() == 443) && u.getUserInfo() == null; }
        catch (Exception e) { return false; }
    }
    void detach() { requireMain(); if (host != null) host.detachOutput(); }
    void quiesce() { preparing = false; evaluation++; handler.removeCallbacksAndMessages(null); detach(); }
    void close() { requireMain(); confirmedRequests.clear(); closed = true; preparing = false; evaluation++; handler.removeCallbacksAndMessages(null); disposeHost(); }
    private void disposeHost() { if (active == this) active = null; if (host != null) host.destroy(); host = null; web = null; loading = false; }
    private void fail(String code) { quiesce(); if (state != null) listener.onFailure(state.taskId(),state.turnId(),state.requestId(),code); }
    private void later(Runnable action, long delay) { String request = state.requestId(); handler.postDelayed(() -> { if (!closed && state != null && request.equals(state.requestId())) action.run(); }, delay); }
    private static String marker(SelfRun3Engine.State s) { return "v3-" + s.requestId(); }
    private static String q(String value) { return SelfRunScript.quote(value); }
    private static JSONObject object(String raw) { try { return new JSONObject(raw); } catch (Exception e) { return new JSONObject(); } }
    private static void requireMain() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("browser port requires main thread"); }
}
