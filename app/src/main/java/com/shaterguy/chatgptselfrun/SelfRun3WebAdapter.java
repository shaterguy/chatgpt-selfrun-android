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

/** SelfRun 3.1 browser port: first-message bootstrap plus one-shot outgoing dispatch observation. */
final class SelfRun3WebAdapter {
    interface Listener {
        void onPrepared(String task, String turn, String request);
        void onStarted(String task, String turn, String request);
        void onAccepted(String task, String turn, String url);
        void onConversation(String task, String turn, String url);
        void onUnsent(String task, String turn, String request, String status);
        void onFailure(String task, String turn, String request, String code);
        void onDispatched(String task, String turn, String request);
    }

    private static final Set<String> DEFINITE_UNSENT = Set.of(
            SelfRun3BootstrapTransport.SEND_DISABLED,
            SelfRun3BootstrapTransport.STOP,
            SelfRun3BootstrapTransport.COMPOSER_WAITING,
            SelfRun3BootstrapTransport.COMPOSER_CLEARING,
            SelfRun3BootstrapTransport.COMPOSER_INPUTTING,
            SelfRun3BootstrapTransport.TARGET_ERROR,
            SelfRun3BootstrapTransport.AUTH_REQUIRED,
            "TURN_PROTOCOL_BUSY",
            "TURN_PROTOCOL_UNAVAILABLE");

    private static SelfRun3WebAdapter active;
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SelfRunStore runStore;
    private final SelfRunRunLog runLog;
    private final SelfRun3RuntimeSettings runtimeSettings;
    private HeadlessWebViewHost host;
    private WebView web;
    private SelfRun3Engine.State state;
    private boolean preparing, loading, closed, dispatchConfirmed, projectRouteReadyLogged;
    private int step, evaluation, generation, preparationAttempt, projectCandidateIndex;
    private int projectProbeRetries, projectClickAttempts, projectDirectoryRecoveries;
    private long prepareStarted, prepareTimeoutMs, projectDirectoryReadyAt;
    private String projectDisplayName = "";

    SelfRun3WebAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.runStore = new SelfRunStore(context);
        this.runLog = new SelfRunRunLog(context);
        this.runtimeSettings = new SelfRun3RuntimeSettings(context);
    }

    static boolean ownsProtocolView(WebView view) {
        return active != null && !active.closed && active.web == view && active.state != null;
    }

    static boolean protocolEvent(WebView view, JSONObject event) {
        SelfRun3WebAdapter a = active;
        if (a == null || a.closed || a.web != view || a.state == null) return false;
        SelfRun3Engine.State s = a.state;
        if (!s.taskId().equals(event.optString("runId"))
                || !s.turnId().equals(event.optString("turnId"))
                || !s.requestId().equals(event.optString("turnToken"))) return false;
        if (!"turn_request".equals(event.optString("stage"))
                || !"canonical_post".equals(event.optString("source"))
                || a.dispatchConfirmed || !s.flag("sendClaimed")) return false;
        a.dispatchConfirmed = true;
        a.captureConversation();
        a.listener.onStarted(s.taskId(), s.turnId(), s.requestId());
        a.quiesce();
        return true;
    }

    void prepare(SelfRun3Engine.State s) {
        requireMain();
        boolean newRequest = state == null || !state.requestId().equals(s.requestId());
        boolean restartPreparation = newRequest || !preparing;
        state = s;
        closed = false;
        if (newRequest) {
            quiesce();
            dispatchConfirmed = false;
            preparationAttempt = 0;
            step = 0;
            resetProjectNavigationState();
        } else if (restartPreparation) {
            trace("WEB_PREPARATION_RECOVERY", "status=start;attempt=" + (preparationAttempt + 1)
                    + ";strategy=recreate-webview");
            quiesce();
            step = 0;
            resetProjectNavigationState();
            disposeHost();
        }
        if (restartPreparation) startPreparationTimer();
        preparing = true;
        String target = s.config().optString("projectUrl");
        if (!trusted(target)) { fail("TARGET_INVALID"); return; }
        ensureWeb();
        if (web == null || closed) return;
        if (host != null) host.attachOutput();
        if (restartPreparation) {
            ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(target);
            projectDisplayName = ref == null ? "" : new ProjectCatalog(context).displayName(ref);
            trace("WEBVIEW_LAUNCH", "route=" + (ref == null ? "general" : "projects")
                    + ";attempt=" + preparationAttempt);
            generation++;
            evaluation++;
            loading = true;
            web.stopLoading();
            web.loadUrl(SelfRun3ProjectDirectoryNavigation.entryUrl(target));
            if (!newRequest) {
                trace("WEB_PREPARATION_RECOVERY", "status=reentry;attempt=" + preparationAttempt
                        + ";route=" + (ref == null ? "general" : "projects"));
            }
            return;
        }
        if (!loading) advance();
    }

    private void startPreparationTimer() {
        prepareStarted = SystemClock.elapsedRealtime();
        prepareTimeoutMs = runtimeSettings.webPreparationMs();
        int attempt = ++preparationAttempt;
        String request = state == null ? "" : state.requestId();
        trace("WEB_PREPARATION_WATCHDOG", "status=armed;attempt=" + attempt
                + ";timeoutMs=" + prepareTimeoutMs);
        handler.postDelayed(() -> {
            if (closed || !preparing || state == null || attempt != preparationAttempt
                    || !request.equals(state.requestId())) return;
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - prepareStarted);
            trace("WEB_PREPARATION_WATCHDOG", "status=expired;attempt=" + attempt
                    + ";elapsedMs=" + elapsed + ";timeoutMs=" + prepareTimeoutMs
                    + ";loading=" + loading + ";route=" + routeClass(web == null ? "" : web.getUrl()));
            fail("WEB_PREPARATION_TIMEOUT");
        }, Math.max(1L, prepareTimeoutMs));
    }

    private void resetProjectNavigationState() {
        projectCandidateIndex = 0;
        projectProbeRetries = 0;
        projectClickAttempts = 0;
        projectDirectoryRecoveries = 0;
        projectDirectoryReadyAt = 0L;
        projectRouteReadyLogged = false;
    }

    private void ensureWeb() {
        if (web != null) return;
        String target = state == null ? "" : state.config().optString("projectUrl");
        if (!trusted(target)) { fail("TARGET_INVALID"); return; }
        host = HeadlessWebViewHost.create(context);
        web = host.webView();
        active = this;
        if (!WebViewConfig.applySelfRun3Automation(web)) { fail("TURN_PROTOCOL_UNAVAILABLE"); return; }
        loading = true;
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                if (view != web || closed) return;
                generation++;
                evaluation++;
                loading = true;
                projectDirectoryReadyAt = 0L;
                trace("WEBVIEW_PAGE_START", "route=" + routeClass(url));
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (view != web || closed) return;
                loading = false;
                captureConversation();
                boolean directory = SelfRun3ProjectDirectoryNavigation.isDirectoryPage(url);
                if (directory) {
                    projectProbeRetries = 0;
                    projectClickAttempts = 0;
                    projectDirectoryReadyAt = SystemClock.elapsedRealtime()
                            + SelfRun3ProjectDirectoryRecoveryPolicy.HYDRATION_SETTLE_MS;
                }
                trace("WEBVIEW_PAGE_FINISH", "route=" + routeClass(url)
                        + ";settleMs=" + (directory ? SelfRun3ProjectDirectoryRecoveryPolicy.HYDRATION_SETTLE_MS : 500L));
                if (preparing) later(SelfRun3WebAdapter.this::advance,
                        directory ? SelfRun3ProjectDirectoryRecoveryPolicy.HYDRATION_SETTLE_MS : 500L);
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (view != web || closed) return;
                captureConversation();
                trace("WEBVIEW_NAVIGATION", "route=" + routeClass(url) + ";reload=" + reload);
                if (preparing && state != null && ProjectUrlPolicy.sameProject(
                        state.config().optString("projectUrl"), url)) {
                    later(SelfRun3WebAdapter.this::advance, 250L);
                }
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (view != web || closed) return true;
                if (!request.isForMainFrame()) return false;
                Uri u = request.getUrl();
                if (!allowedPreparationRoute(String.valueOf(u))) {
                    fail("ROUTE_MISMATCH");
                    return true;
                }
                return false;
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler response, SslError error) {
                response.cancel();
                if (view == web && !closed) fail("TLS_REJECTED");
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view == web && request.isForMainFrame()) fail("WEB_CONNECTION_FAILED");
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view == web) {
                    trace("RENDERER_GONE", "projectNavigation=" + preparing);
                    disposeHost();
                    fail("RENDERER_GONE");
                }
                return true;
            }
        });
    }

    private void advance() {
        if (!preparing || closed || web == null || state == null) return;
        if (SystemClock.elapsedRealtime() - prepareStarted >= prepareTimeoutMs) {
            fail("WEB_PREPARATION_TIMEOUT");
            return;
        }
        if (loading) return;
        if (prepareProjectEntryIfNeeded()) return;
        String script;
        if (step == 0) {
            BootstrapRunStateStore.touchBootstrap(
                    context, state.taskId(),
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
            script = SelfRun3BootstrapTransport.prepare(
                    state.config().optString("projectUrl"), state.text("prompt"), marker(state));
        }
        evaluate(script, result -> {
            String status = result.optString("status");
            if ("READY".equals(status) && step < 2) {
                step++;
                later(this::advance, 0L);
                return;
            }
            if (SelfRun3BootstrapTransport.READY_TO_SUBMIT.equals(status)) {
                if (preparationAttempt > 1) {
                    trace("WEB_PREPARATION_RECOVERY", "status=recovered;attempt=" + preparationAttempt
                            + ";route=" + routeClass(web == null ? "" : web.getUrl()));
                }
                preparing = false;
                listener.onPrepared(state.taskId(), state.turnId(), state.requestId());
                return;
            }
            if (SelfRun3BootstrapTransport.AUTH_REQUIRED.equals(status)
                    || status.endsWith("_FAILED") || status.endsWith("_UNAVAILABLE")
                    || "PROFILE_ERROR".equals(status)
                    || SelfRun3BootstrapTransport.TARGET_ERROR.equals(status)) {
                fail(status);
                return;
            }
            later(this::advance, SelfRun3PowerPolicy.WEB_STEP_RETRY_MS);
        });
    }

    private boolean prepareProjectEntryIfNeeded() {
        if (state == null || web == null) return false;
        String target = state.config().optString("projectUrl");
        if (!SelfRun3ProjectDirectoryNavigation.isProjectTarget(target)) return false;
        String actual = web.getUrl();
        if (ProjectUrlPolicy.sameProject(target, actual)) {
            projectProbeRetries = 0;
            projectClickAttempts = 0;
            projectDirectoryReadyAt = 0L;
            if (!projectRouteReadyLogged) {
                projectRouteReadyLogged = true;
                trace("PROJECT_ROUTE_READY", "recoveries=" + projectDirectoryRecoveries
                        + ";candidate=" + projectCandidateIndex);
            }
            return false;
        }
        projectRouteReadyLogged = false;
        if (SelfRun3ProjectDirectoryNavigation.isWrongProjectRoute(target, actual)) {
            projectCandidateIndex++;
            loadProjectDirectory("wrong-project-candidate");
            return true;
        }
        if (!SelfRun3ProjectDirectoryNavigation.isDirectoryPage(actual)) {
            loadProjectDirectory("restore-directory-route");
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        if (projectDirectoryReadyAt > now) {
            later(this::advance, Math.max(1L, projectDirectoryReadyAt - now));
            return true;
        }
        if (SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecoverAfterClick(projectClickAttempts)) {
            recoverProjectDirectory("click-no-transition");
            return true;
        }
        evaluate(SelfRun3ProjectDirectoryNavigation.build(projectDisplayName, projectCandidateIndex), result -> {
            String status = result.optString("status");
            JSONObject diagnostics = result.optJSONObject("diagnostics");
            int rows = diagnostics == null ? -1 : diagnostics.optInt("rows", -1);
            int matches = diagnostics == null ? -1 : diagnostics.optInt("matchingRows", -1);
            trace("PROJECT_DIRECTORY_RESULT", "status=" + safeStatus(status)
                    + ";rows=" + rows + ";matches=" + matches
                    + ";probe=" + projectProbeRetries + ";click=" + projectClickAttempts
                    + ";recovery=" + projectDirectoryRecoveries + ";candidate=" + projectCandidateIndex);
            switch (status) {
                case "PROJECT_ROW_CLICKED":
                    projectProbeRetries = 0;
                    projectClickAttempts++;
                    later(this::advance, SelfRun3ProjectDirectoryRecoveryPolicy.POST_CLICK_SETTLE_MS);
                    break;
                case "RETRY":
                    projectProbeRetries++;
                    if (SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecoverAfterRetry(projectProbeRetries)) {
                        recoverProjectDirectory("directory-hydration-stalled");
                    } else {
                        later(this::advance, SelfRun3PowerPolicy.WEB_STEP_RETRY_MS);
                    }
                    break;
                case "NAVIGATE_DIRECTORY":
                    loadProjectDirectory("script-requested-directory");
                    break;
                case "AUTH_REQUIRED":
                case "PROJECT_NOT_FOUND":
                case "TARGET_CONTEXT_MISMATCH":
                    fail(status);
                    break;
                default:
                    fail("PROJECT_DIRECTORY_FAILED");
                    break;
            }
        });
        return true;
    }

    private void recoverProjectDirectory(String reason) {
        projectDirectoryRecoveries++;
        projectProbeRetries = 0;
        projectClickAttempts = 0;
        projectDirectoryReadyAt = 0L;
        boolean recreateHost = SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecreateHost(projectDirectoryRecoveries);
        trace("PROJECT_DIRECTORY_RECOVERY", "status=start;reason=" + safeStatus(reason)
                + ";recovery=" + projectDirectoryRecoveries
                + ";strategy=" + (recreateHost ? "recreate-webview" : "fresh-directory"));
        if (recreateHost) {
            disposeHost();
            ensureWeb();
            if (web == null || closed) return;
            if (host != null) host.attachOutput();
        }
        loadProjectDirectory("recovery-" + projectDirectoryRecoveries);
    }

    private void loadProjectDirectory(String reason) {
        if (web == null || closed) return;
        projectProbeRetries = 0;
        projectClickAttempts = 0;
        projectDirectoryReadyAt = 0L;
        generation++;
        evaluation++;
        loading = true;
        trace("PROJECT_DIRECTORY_LOAD", "reason=" + safeStatus(reason)
                + ";recovery=" + projectDirectoryRecoveries + ";candidate=" + projectCandidateIndex);
        web.stopLoading();
        web.loadUrl(SelfRun3ProjectDirectoryNavigation.DIRECTORY_URL);
    }

    void submit(SelfRun3Engine.State claimed) {
        requireMain();
        if (web == null || closed || dispatchConfirmed || !SelfRun3PowerPolicy.maySend(claimed)) {
            fail("SUBMISSION_STATE_INVALID");
            return;
        }
        state = claimed;
        preparing = false;
        String action = SelfRun3BootstrapTransport.submit(
                claimed.config().optString("projectUrl"), claimed.text("prompt"), marker(claimed));
        String arm = SelfRun3DispatchScript.arm(claimed.taskId(), claimed.turnId(), claimed.requestId());
        String wrapped = "(()=>{if(!(" + arm + "))return JSON.stringify({status:'TURN_PROTOCOL_UNAVAILABLE'});return (" + action + ");})()";
        evaluate(wrapped, result -> {
            String status = result.optString("status");
            if (DEFINITE_UNSENT.contains(status)) {
                listener.onUnsent(claimed.taskId(), claimed.turnId(), claimed.requestId(), status);
                return;
            }
            if ("CALLBACK_AMBIGUOUS".equals(status) || "SCRIPT_ERROR".equals(status)) {
                fail("SUBMISSION_OUTCOME_UNKNOWN");
                return;
            }
            listener.onDispatched(claimed.taskId(), claimed.turnId(), claimed.requestId());
            later(() -> {
                if (!dispatchConfirmed) fail("SUBMISSION_OUTCOME_UNKNOWN");
            }, SelfRun3PowerPolicy.CALLBACK_TIMEOUT_MS);
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
            calls += RequestProfileScript.setChatReasoning(
                    c.optString("reasoning", c.optString("chatBootstrap", ChatReasoningPreferenceStore.KEEP)));
        }
        return "(()=>{try{" + calls
                + "return JSON.stringify({status:'READY'});}catch(_){return JSON.stringify({status:'PROFILE_ERROR'});}})()";
    }

    private void evaluate(String script, Consumer<JSONObject> callback) {
        WebView current = web;
        int id = ++evaluation, page = generation;
        String request = state.requestId();
        handler.postDelayed(() -> {
            if (current != web || id != evaluation || page != generation || closed
                    || state == null || !request.equals(state.requestId())) return;
            evaluation++;
            callback.accept(object("{\"status\":\"CALLBACK_AMBIGUOUS\"}"));
        }, SelfRun3PowerPolicy.CALLBACK_TIMEOUT_MS);
        try {
            current.evaluateJavascript(script, raw -> {
                if (current != web || id != evaluation || page != generation || closed
                        || state == null || !request.equals(state.requestId())) return;
                evaluation++;
                try {
                    Object value = new JSONTokener(raw == null ? "null" : raw).nextValue();
                    callback.accept(value instanceof String ? object((String) value)
                            : value instanceof JSONObject ? (JSONObject) value
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

    private void captureConversation() {
        if (web == null || state == null || !dispatchConfirmed) return;
        String url = web.getUrl();
        String conversationId = SelfRunScript.conversationId(url);
        if (allowedRoute(url) && !conversationId.isEmpty()) {
            listener.onConversation(state.taskId(), state.turnId(),
                    "https://chatgpt.com/c/" + conversationId);
        }
    }

    private boolean allowedPreparationRoute(String url) {
        if (allowedRoute(url)) return true;
        if (!preparing || state == null) return false;
        String target = state.config().optString("projectUrl");
        if (!SelfRun3ProjectDirectoryNavigation.isProjectTarget(target)) return false;
        return SelfRun3ProjectDirectoryNavigation.isDirectoryPage(url)
                || ProjectUrlPolicy.parseProject(url) != null;
    }

    private boolean allowedRoute(String url) {
        if (!trusted(url) || state == null) return false;
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

    void detach() { requireMain(); if (host != null) host.detachOutput(); }

    void quiesce() {
        preparing = false;
        evaluation++;
        handler.removeCallbacksAndMessages(null);
        detach();
    }

    void close() {
        requireMain();
        closed = true;
        preparing = false;
        evaluation++;
        handler.removeCallbacksAndMessages(null);
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
        long elapsed = prepareStarted <= 0L ? 0L : Math.max(0L, SystemClock.elapsedRealtime() - prepareStarted);
        trace("WEBVIEW_ERROR", "code=" + safeStatus(code) + ";route=" + routeClass(web == null ? "" : web.getUrl())
                + ";attempt=" + preparationAttempt + ";elapsedMs=" + elapsed);
        quiesce();
        if (state != null) listener.onFailure(state.taskId(), state.turnId(), state.requestId(), code);
    }

    private void trace(String event, String detail) {
        if (state == null || !state.taskId().equals(runStore.runId())) return;
        runLog.record(runStore, event, detail);
    }

    private String routeClass(String url) {
        if (SelfRun3ProjectDirectoryNavigation.isDirectoryPage(url)) return "projects";
        if (state != null) {
            String target = state.config().optString("projectUrl");
            if (ProjectUrlPolicy.sameProject(target, url)) return "target-project";
            if (ProjectUrlPolicy.parseProject(url) != null) return "other-project";
        }
        return trusted(url) ? "chatgpt-other" : "untrusted";
    }

    private static String safeStatus(String value) {
        String raw = value == null ? "" : value;
        return raw.matches("[A-Za-z0-9._-]{1,64}") ? raw : "other";
    }

    private void later(Runnable action, long delay) {
        if (state == null) return;
        String request = state.requestId();
        handler.postDelayed(() -> {
            if (!closed && state != null && request.equals(state.requestId())) action.run();
        }, delay);
    }

    private static String marker(SelfRun3Engine.State s) { return "v31-" + s.requestId(); }
    private static JSONObject object(String raw) {
        try { return new JSONObject(raw); } catch (Exception e) { return new JSONObject(); }
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("browser port requires main thread");
    }
}
