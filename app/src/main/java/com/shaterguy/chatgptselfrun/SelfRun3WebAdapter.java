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
import java.util.Set;
import java.util.function.Consumer;

/** Replaceable V3 browser port with separate verified bootstrap and continuation transports. */
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

    private static final Set<String> DEFINITE_UNSENT = Set.of(
            SelfRun3ComposerTransport.SEND_DISABLED,
            SelfRun3ComposerTransport.SEND_UNAVAILABLE,
            SelfRun3ComposerTransport.STOP,
            SelfRun3ComposerTransport.COMPOSER_WAITING,
            SelfRun3ComposerTransport.COMPOSER_INPUTTING,
            "COMPOSER_CLEARING",
            SelfRun3ComposerTransport.TARGET_ERROR,
            SelfRun3ComposerTransport.AUTH_REQUIRED,
            "TURN_PROTOCOL_BUSY",
            "TURN_PROTOCOL_UNAVAILABLE");

    private static SelfRun3WebAdapter active;
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SelfRunStore diagnosticStore;
    private final SelfRunRunLog diagnosticLog;
    private HeadlessWebViewHost host;
    private WebView web;
    private SelfRun3Engine.State state;
    private boolean preparing, loading, closed;
    private int step, evaluation, generation;
    private long prepareStarted;
    private String lastPrepareTrace = "";
    private String observedRequest = "", acceptedRequest = "", endedRequest = "";
    private String conversationProbeKey = "";
    private Consumer<JSONObject> pendingInspection;

    SelfRun3WebAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        diagnosticStore = new SelfRunStore(context);
        diagnosticLog = new SelfRunRunLog(context);
    }

    static boolean ownsProtocolView(WebView view) {
        return active != null && !active.closed && active.web == view && active.state != null;
    }

    /** Called only after the native bridge has checked origin, main frame and event schema. */
    static boolean protocolEvent(WebView view, JSONObject event) {
        if (!ownsProtocolView(view)) return false;
        SelfRun3WebAdapter a = active;
        SelfRun3Engine.State s = a.state;
        if (!s.taskId().equals(event.optString("runId"))
                || !s.requestId().equals(event.optString("turnToken"))) return false;
        String stage = event.optString("stage"), source = event.optString("source");
        String phase = event.optString("phase");
        if ("turn_request".equals(stage) && "THINKING".equals(phase)
                && "canonical_post".equals(source)) {
            a.observedStart();
        } else if ("answering_started".equals(stage) && "ANSWERING".equals(phase)) {
            a.observedAnswer();
        } else if (("complete".equals(stage) || "completion_dispatch".equals(stage))
                && "COMPLETE".equals(phase) && TurnProtocolLogBridge.isAllowedCompletionSource(source)) {
            a.observedEnd(source);
        } else if ("error".equals(stage) && "ERROR".equals(phase)) {
            a.observedStart();
            a.fail("TRANSPORT_INTERRUPTED");
        } else if (!("completion_ignored".equals(stage) && "THINKING".equals(phase))) {
            return false;
        }
        a.captureConversation();
        return true;
    }

    private void observedStart() {
        if (state.requestId().equals(observedRequest)) return;
        observedRequest = state.requestId();
        if (preparing) evaluation++;
        preparing = false;
        trace("PROTOCOL_ACCEPT", "CANONICAL_POST", step);
        listener.onStarted(state.taskId(), state.turnId(), state.requestId());
        captureConversation();
        detachAfterComposerReady();
    }

    private void observedAnswer() {
        observedStart();
        if (state.requestId().equals(acceptedRequest)) return;
        acceptedRequest = state.requestId();
        listener.onAccepted(state.taskId(), state.turnId(), state.requestId());
    }

    private void observedEnd(String source) {
        observedStart();
        if (state.requestId().equals(endedRequest)) return;
        endedRequest = state.requestId();
        trace("PROTOCOL_ACCEPT", "COMPLETE", step);
        attachForCompletionTransition();
        listener.onEnded(state.taskId(), state.turnId(), state.requestId(), source);
    }

    void prepare(SelfRun3Engine.State s) {
        requireMain();
        boolean newAttempt = state == null || !state.requestId().equals(s.requestId());
        boolean restartPreparation = newAttempt || !preparing;
        state = s;
        closed = false;
        if (!newAttempt && s.requestId().equals(observedRequest)) {
            trace("PREPARE_SKIPPED", "REQUEST_ALREADY_OBSERVED", step);
            detachAfterComposerReady();
            return;
        }
        preparing = true;
        if (newAttempt) {
            step = 0;
            prepareStarted = SystemClock.elapsedRealtime();
            lastPrepareTrace = "";
            observedRequest = acceptedRequest = endedRequest = "";
        } else if (restartPreparation) {
            prepareStarted = SystemClock.elapsedRealtime();
            lastPrepareTrace = "";
        }
        trace("PREPARE_START", s.resource("conversationUrl").isEmpty() ? "INITIAL" : "CONTINUATION", step);
        ensureWeb(false);
        if (host != null) host.attachOutput();
        if (!loading) advance();
    }

    private void ensureWeb(boolean detached) {
        if (web != null) return;
        String target = state.resource("conversationUrl");
        if (target.isEmpty()) target = state.config().optString("projectUrl");
        if (!trusted(target)) {
            fail("TARGET_INVALID");
            return;
        }
        host = HeadlessWebViewHost.create(context);
        web = host.webView();
        active = this;
        if (detached) host.detachOutput();
        if (!WebViewConfig.applyAutomation(web)) {
            fail("TURN_PROTOCOL_UNAVAILABLE");
            return;
        }
        loading = true;
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                generation++;
                evaluation++;
                loading = true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (view != web || closed) return;
                loading = false;
                captureConversation();
                if (pendingInspection != null) {
                    Consumer<JSONObject> cb = pendingInspection;
                    pendingInspection = null;
                    inspect(state, cb);
                } else if (preparing) {
                    later(SelfRun3WebAdapter.this::advance, 500L);
                }
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (view == web && !closed) captureConversation();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (view != web || closed) return true;
                if (!request.isForMainFrame()) return false;
                Uri u = request.getUrl();
                if ("selfrun-drive".equals(u.getScheme()) && "turn-completed".equals(u.getHost())) {
                    if (state.taskId().equals(u.getQueryParameter("run"))
                            && state.requestId().equals(u.getQueryParameter("token"))
                            && TurnProtocolLogBridge.isAllowedCompletionSource(u.getQueryParameter("source"))) {
                        observedEnd(u.getQueryParameter("source"));
                    }
                    return true;
                }
                if (!allowedRoute(String.valueOf(u))) {
                    fail("ROUTE_MISMATCH");
                    return true;
                }
                return false;
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler response, SslError error) {
                response.cancel();
                fail("TLS_REJECTED");
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view == web && request.isForMainFrame()) fail("WEB_CONNECTION_FAILED");
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view == web) {
                    disposeHost();
                    fail("RENDERER_GONE");
                }
                return true;
            }
        });
        web.loadUrl(target);
    }

    private void advance() {
        if (!preparing || closed || loading || web == null || state == null) return;
        if (SystemClock.elapsedRealtime() - prepareStarted >= SelfRun3PowerPolicy.WEB_PREPARATION_MAX_MS) {
            fail("WEB_PREPARATION_TIMEOUT");
            return;
        }
        boolean initial = state.resource("conversationUrl").isEmpty();
        String script;
        if (step == 0 && initial) {
            BootstrapRunStateStore.touchBootstrap(
                    context,
                    state.taskId(),
                    ChatReasoningPreferenceStore.selectionForRun(context, state.taskId()),
                    System.currentTimeMillis());
            script = SelfRunDom.prepareInitialContext(
                    state.config().optString("projectUrl"),
                    state.config().optString("mode"),
                    state.taskId());
        } else if (step <= 1) {
            step = 1;
            script = profileScript(state);
        } else {
            script = initial
                    ? SelfRun3BootstrapTransport.prepare(
                            state.config().optString("projectUrl"), state.text("prompt"), state.requestId())
                    : SelfRun3ComposerTransport.prepareContinuation(
                            state.resource("conversationUrl"), state.text("prompt"));
        }
        boolean continuationComposerStage = !initial && step >= 2;
        String evaluationScript = continuationComposerStage
                ? observeBeforeAndAfter(state, script)
                : observeWithoutBinding(script);
        evaluate(evaluationScript, result -> {
            tracePrepare(result);
            if (continuationComposerStage && consumeObservation(result)) return;
            if (!preparing) return;
            String status = result.optString("status");
            if ("READY".equals(status) && step < 2) {
                step++;
                later(this::advance, 0L);
                return;
            }
            if ("READY_TO_SUBMIT".equals(status)) {
                preparing = false;
                trace("ON_PREPARED", status, step);
                listener.onPrepared(state.taskId(), state.turnId(), state.requestId());
                return;
            }
            if ("AUTH_REQUIRED".equals(status)
                    || status.endsWith("_FAILED")
                    || status.endsWith("_UNAVAILABLE")
                    || "PROFILE_ERROR".equals(status)
                    || "TARGET_ERROR".equals(status)) {
                fail(status);
                return;
            }
            later(this::advance, SelfRun3PowerPolicy.WEB_STEP_RETRY_MS);
        });
    }

    void submit(SelfRun3Engine.State claimed) {
        requireMain();
        if (claimed.requestId().equals(observedRequest)) {
            trace("SUBMIT_SKIPPED", "REQUEST_ALREADY_OBSERVED", step);
            detachAfterComposerReady();
            return;
        }
        if (web == null || closed || !SelfRun3PowerPolicy.maySend(claimed)) {
            fail("SUBMISSION_STATE_INVALID");
            return;
        }
        state = claimed;
        preparing = false;
        boolean initial = claimed.resource("conversationUrl").isEmpty();
        trace("SUBMIT_START", initial ? "INITIAL" : "CONTINUATION", step);

        String action = initial
                ? SelfRun3BootstrapTransport.submit(
                        claimed.config().optString("projectUrl"), claimed.text("prompt"), claimed.requestId())
                : SelfRun3ComposerTransport.submitContinuation(
                        claimed.resource("conversationUrl"), claimed.text("prompt"));
        evaluate(observeBeforeAndAfter(claimed, action), result -> {
            String status = result.optString("status");
            trace("SUBMIT_EVAL", status, step);
            if (consumeObservation(result)) return;

            if (DEFINITE_UNSENT.contains(status)) {
                detachImmediately();
                trace("ON_UNSENT", status, step);
                listener.onUnsent(claimed.taskId(), claimed.turnId(), claimed.requestId(), status);
            } else if ("SUBMISSION_PENDING".equals(status)) {
                detachAfterComposerReady();
                listener.onDispatched(claimed.taskId(), claimed.turnId(), claimed.requestId());
            } else {
                fail("SUBMISSION_OUTCOME_UNKNOWN");
            }
            captureConversation();
        });
    }

    /** Binds one exact request around a physical submission or continuation mutation that may submit. */
    static String observeBeforeAndAfter(SelfRun3Engine.State s, String action) {
        String run = q(s.taskId()), request = q(s.requestId());
        String body = "(()=>{const p=window.__selfRunTurnProtocol;"
                + "if(!p.armCompletion(" + run + "," + request + "))return JSON.stringify({status:'TURN_PROTOCOL_UNAVAILABLE'});"
                + "const current=x=>!!x&&x.runId===" + run + "&&x.turnToken===" + request + ";"
                + "const seen=x=>current(x)&&!!x.requestIdentity&&['THINKING','ANSWERING','COMPLETE','ERROR'].includes(x.phase);"
                + "let before=p.snapshot(),out;"
                + "if(seen(before))out={status:'REQUEST_OBSERVED'};else{try{const value=(" + action + ");"
                + "out=typeof value==='string'?JSON.parse(value):value;out=out||{status:'CALLBACK_AMBIGUOUS'};"
                + "}catch(_){out={status:'JS_EVALUATION_FAILED'};}}"
                + "const after=p.snapshot();"
                + "if(seen(after)){out.status='REQUEST_OBSERVED';out.protocol={runId:after.runId,turnToken:after.turnToken,"
                + "requestIdentity:after.requestIdentity,phase:after.phase,completionSource:after.completionSource||''};}"
                + "out.v3diag={bound:current(after),phase:after?.phase||'UNAVAILABLE',postSeen:seen(after),"
                + "editors:document.querySelectorAll('textarea,[contenteditable],[role=\"textbox\"]').length,"
                + "ready:document.readyState,focused:document.hasFocus(),hidden:document.hidden};"
                + "return JSON.stringify(out);})()";
        return ChatGptTurnProtocolScript.bindTurnAndThen(s.taskId(), s.requestId(), body);
    }

    /** First-turn context/profile/composer preparation never owns a canonical request. */
    static String observeWithoutBinding(String action) {
        return "(()=>{let out;try{const value=(" + action + ");out=typeof value==='string'?JSON.parse(value):value;"
                + "out=out||{status:'CALLBACK_AMBIGUOUS'};}catch(_){out={status:'JS_EVALUATION_FAILED'};}"
                + "const p=window.__selfRunTurnProtocol?.snapshot?.();"
                + "out.v3diag={bound:false,phase:p?.phase||'UNAVAILABLE',postSeen:false,"
                + "editors:document.querySelectorAll('textarea,[contenteditable],[role=\"textbox\"]').length,"
                + "ready:document.readyState,focused:document.hasFocus(),hidden:document.hidden};"
                + "return JSON.stringify(out);})()";
    }

    private boolean consumeObservation(JSONObject result) {
        JSONObject p = result.optJSONObject("protocol");
        if (p != null && state.taskId().equals(p.optString("runId"))
                && state.requestId().equals(p.optString("turnToken"))
                && !p.optString("requestIdentity").isEmpty()) {
            String phase = p.optString("phase");
            if (Set.of("THINKING", "ANSWERING", "COMPLETE", "ERROR").contains(phase)) {
                observedStart();
                if ("ANSWERING".equals(phase)) observedAnswer();
                if ("COMPLETE".equals(phase)
                        && TurnProtocolLogBridge.isAllowedCompletionSource(p.optString("completionSource"))) {
                    observedEnd(p.optString("completionSource"));
                }
                if ("ERROR".equals(phase)) fail("TRANSPORT_INTERRUPTED");
                return true;
            }
        }
        return state.requestId().equals(observedRequest);
    }

    void inspect(SelfRun3Engine.State s, Consumer<JSONObject> callback) {
        requireMain();
        state = s;
        if (web == null) {
            if (s.resource("conversationUrl").isEmpty()) {
                callback.accept(object("{\"ready\":false,\"reason\":\"conversation_unknown\"}"));
                return;
            }
            preparing = false;
            pendingInspection = callback;
            ensureWeb(true);
            if (web == null) {
                pendingInspection = null;
                callback.accept(object("{\"ready\":false}"));
            }
            return;
        }
        if (loading) {
            pendingInspection = callback;
            return;
        }
        detachImmediately();
        evaluate(inspectionScript(s), result -> {
            if (result.optBoolean("complete") || result.optBoolean("receipt")) {
                attachForCompletionTransition();
            }
            callback.accept(result);
        });
    }

    static String inspectionScript(SelfRun3Engine.State s) {
        return "(()=>{const out={ready:false,complete:false,accepted:false,receipt:false,signature:'',source:''};"
                + "if(location.protocol!=='https:'||!['chatgpt.com','www.chatgpt.com'].includes(location.hostname))return JSON.stringify(out);"
                + "const p=window.__selfRunTurnProtocol?.snapshot?.();"
                + "if(p&&p.runId===" + q(s.taskId()) + "&&p.turnToken===" + q(s.requestId()) + "){"
                + "out.accepted=p.sawVisibleAnswer===true||p.sawStreamComplete===true;"
                + "if(p.phase==='COMPLETE'&&['message_stream_complete','finished_successfully_end_turn'].includes(p.completionSource)){"
                + "out.complete=true;out.source=p.completionSource;return JSON.stringify(out);}}"
                + "const expected=" + q(SelfRunScript.conversationId(s.resource("conversationUrl"))) + ";"
                + "const parts=location.pathname.split('/').filter(Boolean),ci=parts.indexOf('c');"
                + "if(!expected||ci<0||parts[ci+1]!==expected)return JSON.stringify(out);"
                + "const messages=[...document.querySelectorAll('[data-message-author-role]')];"
                + "const users=messages.filter(e=>e.getAttribute('data-message-author-role')==='user');"
                + "const assistants=messages.filter(e=>e.getAttribute('data-message-author-role')==='assistant');"
                + "const u=users.at(-1),a=assistants.at(-1);"
                + "if(!u||!a||!String(u.textContent||'').includes(" + q(s.turnId()) + "))return JSON.stringify(out);"
                + "if(!(u.compareDocumentPosition(a)&Node.DOCUMENT_POSITION_FOLLOWING))return JSON.stringify(out);"
                + "const text=String(a.innerText||a.textContent||'').trim();"
                + "out.receipt=text.endsWith(" + q(SelfRun3Protocol.receipt(s)) + ");"
                + "const composerReady=Boolean(" + SelfRun3ComposerTransport.composerReadyExpression() + ");"
                + "out.ready=out.receipt&&composerReady;"
                + "out.signature=out.ready?" + q(s.turnId() + ":")
                + "+String(a.getAttribute('data-message-id')||'')+':'+text.length:'';"
                + "return JSON.stringify(out);})()";
    }

    private static String profileScript(SelfRun3Engine.State s) {
        JSONObject c = s.config();
        String calls = RequestProfileScript.beginTarget(c.optString("mode"), s.taskId());
        if ("WORK".equals(c.optString("mode"))) {
            calls += RequestProfileScript.setWorkModel(c.optString("model"))
                    + RequestProfileScript.setWorkReasoning(c.optString("reasoning"));
        } else if (s.turn() == 1) {
            calls += RequestProfileScript.setChatProfiles(
                    c.optString("chatBootstrap"), c.optString("chatContinuation"));
        } else {
            calls += RequestProfileScript.setChatReasoning(c.optString("chatContinuation"));
        }
        return "(()=>{try{" + calls
                + "return JSON.stringify({status:'READY'});"
                + "}catch(_){return JSON.stringify({status:'PROFILE_ERROR'});}})()";
    }

    private void evaluate(String script, Consumer<JSONObject> callback) {
        WebView current = web;
        int id = ++evaluation, page = generation;
        String request = state.requestId();
        handler.postDelayed(() -> {
            if (current != web || id != evaluation || page != generation || closed
                    || !request.equals(state.requestId())) return;
            evaluation++;
            callback.accept(object("{\"status\":\"CALLBACK_AMBIGUOUS\"}"));
        }, SelfRun3PowerPolicy.CALLBACK_TIMEOUT_MS);
        try {
            current.evaluateJavascript(script, raw -> {
                if (current != web || id != evaluation || page != generation || closed
                        || !request.equals(state.requestId())) return;
                evaluation++;
                try {
                    Object v = new JSONTokener(raw == null ? "null" : raw).nextValue();
                    callback.accept(v instanceof String
                            ? object((String) v)
                            : v instanceof JSONObject
                                    ? (JSONObject) v
                                    : object("{\"status\":\"CALLBACK_AMBIGUOUS\"}"));
                } catch (Exception e) {
                    callback.accept(object("{\"status\":\"CALLBACK_AMBIGUOUS\"}"));
                }
            });
        } catch (Throwable e) {
            evaluation++;
            callback.accept(object("{\"status\":\"CALLBACK_AMBIGUOUS\"}"));
        }
    }

    private void tracePrepare(JSONObject result) {
        String status = result.optString("status");
        JSONObject d = result.optJSONObject("v3diag");
        String detail = d == null ? ";snapshot=missing" : ";bound=" + d.optBoolean("bound")
                + ";protocol=" + safeTrace(d.optString("phase")) + ";postSeen=" + d.optBoolean("postSeen")
                + ";editors=" + Math.max(0, Math.min(10000, d.optInt("editors")))
                + ";document=" + safeTrace(d.optString("ready"))
                + ";focused=" + d.optBoolean("focused") + ";hidden=" + d.optBoolean("hidden");
        String key = step + ":" + status + detail;
        if (key.equals(lastPrepareTrace)) return;
        lastPrepareTrace = key;
        if (state == null || !state.taskId().equals(diagnosticStore.runId())) return;
        diagnosticLog.record(diagnosticStore, "V3_WEB_TRACE", "stage=PREPARE_EVAL;status="
                + safeTrace(status) + ";step=" + step + detail);
    }

    private void trace(String stage, String status, int traceStep) {
        if (state == null || !state.taskId().equals(diagnosticStore.runId())) return;
        String safeStage = safeTrace(stage);
        String safeStatus = safeTrace(status);
        diagnosticLog.record(
                diagnosticStore,
                "V3_WEB_TRACE",
                "stage=" + safeStage + ";status=" + safeStatus + ";step=" + traceStep);
    }

    private static String safeTrace(String value) {
        String safe = value == null
                ? ""
                : value.toUpperCase().replaceAll("[^A-Z0-9_:-]", "_");
        return safe.isEmpty() ? "UNKNOWN" : safe.substring(0, Math.min(100, safe.length()));
    }

    private void captureConversation() {
        if (web == null || state == null
                || (!state.flag("sendClaimed") && !state.requestId().equals(observedRequest))) return;
        String url = web.getUrl();
        if (allowedRoute(url) && !SelfRunScript.conversationId(url).isEmpty()) {
            listener.onConversation(state.taskId(), state.turnId(), url);
            return;
        }
        WebView current = web;
        int page = generation;
        String task = state.taskId(), turn = state.turnId(), request = state.requestId();
        String probe = request + ":" + page + ":" + (request.equals(endedRequest) ? "ended" : "started");
        if (probe.equals(conversationProbeKey)) return;
        conversationProbeKey = probe;
        try {
            current.evaluateJavascript("location.href", raw -> {
                if (current != web || page != generation || closed || state == null
                        || !task.equals(state.taskId()) || !turn.equals(state.turnId())
                        || !request.equals(state.requestId())) return;
                try {
                    if (raw == null || raw.length() > 4096) return;
                    Object value = new JSONTokener(raw).nextValue();
                    if (!(value instanceof String)) return;
                    String observedUrl = (String) value;
                    if (allowedRoute(observedUrl) && !SelfRunScript.conversationId(observedUrl).isEmpty()) {
                        listener.onConversation(task, turn, observedUrl);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private boolean allowedRoute(String url) {
        if (!trusted(url) || state == null) return false;
        String known = state.resource("conversationUrl");
        if (!known.isEmpty()) {
            return SelfRunScript.conversationId(known).equals(SelfRunScript.conversationId(url));
        }
        return SelfRunScript.projectId(state.config().optString("projectUrl"))
                .equals(SelfRunScript.projectId(url));
    }

    static boolean trusted(String raw) {
        try {
            Uri u = Uri.parse(raw);
            return "https".equals(u.getScheme())
                    && ("chatgpt.com".equals(u.getHost()) || "www.chatgpt.com".equals(u.getHost()))
                    && (u.getPort() == -1 || u.getPort() == 443)
                    && u.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Coordinator WAIT detach is generation-aware; explicit pause/failure paths use detachImmediately(). */
    void detach() {
        requireMain();
        if (state != null && state.flag("sendClaimed") && !state.flag("ended")) {
            detachAfterComposerReady();
        } else {
            detachImmediately();
        }
    }

    void detachAfterComposerReady() {
        requireMain();
        if (host != null) host.detachOutputWhenComposerReady();
    }

    void attachForCompletionTransition() {
        requireMain();
        if (host == null) return;
        boolean changed = host.attachOutput();
        trace("OUTPUT_COMPLETE", changed ? "ATTACHED"
                : host.isOutputAttached() ? "ALREADY_ATTACHED" : "UNAVAILABLE", step);
    }

    private void detachImmediately() {
        requireMain();
        if (host != null) host.detachOutput();
    }

    void quiesce() {
        preparing = false;
        evaluation++;
        handler.removeCallbacksAndMessages(null);
        detachImmediately();
    }

    void close() {
        requireMain();
        closed = true;
        preparing = false;
        evaluation++;
        handler.removeCallbacksAndMessages(null);
        pendingInspection = null;
        disposeHost();
    }

    private void disposeHost() {
        if (active == this) active = null;
        if (host != null) host.destroy();
        host = null;
        web = null;
        loading = false;
    }

    private void fail(String code) {
        preparing = false;
        trace("FAILURE", code, step);
        detachImmediately();
        if (state != null) {
            listener.onFailure(state.taskId(), state.turnId(), state.requestId(), code);
        }
    }

    private void later(Runnable action, long delay) {
        String request = state.requestId();
        handler.postDelayed(() -> {
            if (!closed && state != null && request.equals(state.requestId())) action.run();
        }, delay);
    }

    private static String q(String value) {
        return SelfRunScript.quote(value);
    }

    private static JSONObject object(String raw) {
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("browser port requires main thread");
        }
    }
}
