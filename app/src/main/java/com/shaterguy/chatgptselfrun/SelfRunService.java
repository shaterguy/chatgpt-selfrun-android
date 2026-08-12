package com.shaterguy.chatgptselfrun;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.UUID;

public final class SelfRunService extends Service {
    static final String ACTION_RUN = "com.shaterguy.chatgptselfrun.RUN";
    static final String ACTION_PAUSE = "com.shaterguy.chatgptselfrun.PAUSE";
    static final String ACTION_RESUME = "com.shaterguy.chatgptselfrun.RESUME";
    private static final int NOTIFICATION_ID = 7021;
    private static final String PHASE_BOOTSTRAP_MODEL = "BOOTSTRAP_MODEL";
    private static final String PHASE_BOOTSTRAP_REASONING = "BOOTSTRAP_REASONING";
    private static final String PHASE_BOOTSTRAP_SEND = "BOOTSTRAP_SEND";
    private static final String PHASE_APPLY_REASONING = "APPLY_REASONING";
    private static final long[] RATE_LIMIT_DELAYS = {2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L};
    private static final long STARTUP_TIMEOUT_MS = 120_000L;
    private static final long PREF_TIMEOUT_MS = 120_000L;
    private static final long ASSISTANT_RESPONSE_TIMEOUT_MS = 12 * 60_000L;
    private static final long SUBMISSION_STALL_TIMEOUT_MS = 60_000L;
    private static final long DOM_WATCHDOG_MS = 15_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stepRunnable = this::runStep;
    private final Runnable watchdogRunnable = this::runDomWatchdog;
    private SelfRunStore store;
    private SelfRunRunLog runLog;
    private HeadlessWebViewHost host;
    private WebView webView;
    private WebMessagePort observerPort;
    private boolean evaluationInFlight;
    private boolean domEvaluationPending;
    private boolean observerInstallInFlight;
    private boolean observerHealthInFlight;
    private int generation;
    private int observerEpoch;
    private int rateLimitAttempt;
    private long rateLimitedUntilElapsed;
    private long evaluationCount;
    private long observerMaintenanceEvaluationCount;
    private long observerEventCount;
    private long observerDuplicateEventCount;
    private long watchdogCount;
    private long watchdogRecoveryCount;
    private long submissionWaitStartedElapsed;
    private String observerLease = "";
    private String lastObserverState = "";
    private String pendingEvaluationTrigger = "startup";
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new SelfRunStore(this);
        runLog = new SelfRunRunLog(this);
        NotificationHelper.ensureChannel(this);
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":selfrun");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RUN : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            pauseFromUi();
            return store.active() ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            resumeFromUi();
            return store.active() ? START_STICKY : START_NOT_STICKY;
        }
        if (intent != null && !ACTION_RUN.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!store.active()) {
            stopRelay();
            return START_NOT_STICKY;
        }
        if (store.paused()) {
            startForegroundCompat();
            handler.removeCallbacksAndMessages(null);
            detachDomObserver("service_paused");
            releaseWakeLock();
            runLog.record(store, "SERVICE_PAUSED", "automation_stopped;webview_preserved="
                    + (webView == null ? "0" : "1"));
            return START_STICKY;
        }
        startForegroundCompat();
        updateWakeLockForState();
        runLog.record(store, "SERVICE_START", intent == null ? "sticky_recreate" : "explicit_start");
        handler.post(this::ensureEngine);
        return START_STICKY;
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, NotificationHelper.active(this, store.status()),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, NotificationHelper.active(this, store.status()));
        }
    }

    private void ensureEngine() {
        if (store.paused()) {
            detachDomObserver("ensure_engine_paused");
            releaseWakeLock();
            return;
        }
        if (!store.active()) { stopRelay(); return; }
        String target = targetUrl();
        if (target.isEmpty()) {
            pause("TARGET_MISSING", "대상 프로젝트 또는 conversation URL이 없습니다.");
            return;
        }
        updateWakeLockForState();
        if (webView != null) {
            ensureDomObserver();
            scheduleWatchdog();
            return;
        }
        launchWebView(target);
    }

    private boolean canRun() {
        return store.active() && !store.paused()
                && !SelfRunStore.PHASE_DONE.equals(store.phase())
                && !SelfRunStore.PHASE_IDLE.equals(store.phase());
    }

    private String targetUrl() {
        return store.conversationUrl().isEmpty() ? store.projectUrl() : store.conversationUrl();
    }

    private void launchWebView(String target) {
        cleanupWebView();
        try {
            host = HeadlessWebViewHost.create(this);
            webView = host.webView();
            WebViewConfig.applyAutomation(webView);
            runLog.record(store, "WEBVIEW_LAUNCH", store.conversationUrl().isEmpty() ? "target=project" : "target=conversation");
            WebView active = webView;
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    invalidateDomObserverForNavigation();
                    generation++;
                    evaluationInFlight = false;
                    domEvaluationPending = false;
                    handler.removeCallbacks(stepRunnable);
                    if (store.paused()) {
                        releaseWakeLock();
                        runLog.record(store, "WEBVIEW_EVENT_IGNORED", "page_start;paused=1");
                        return;
                    }
                    updateWakeLockForState();
                    store.setStatus("ChatGPT 화면 로딩 중");
                    runLog.record(store, "WEBVIEW_PAGE_START", store.conversationUrl().isEmpty()
                            ? "route=project" : "route=conversation");
                    startForegroundCompat();
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (store.paused()) {
                        releaseWakeLock();
                        runLog.record(store, "WEBVIEW_EVENT_IGNORED", "page_finish;paused=1");
                        return;
                    }
                    if (isRateLimited()) return;
                    updateWakeLockForState();
                    store.setStatus("ChatGPT 화면 준비 확인 중");
                    runLog.record(store, "WEBVIEW_PAGE_FINISH", "progress=" + view.getProgress());
                    ensureDomObserver();
                    scheduleWatchdog();
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                    if (!request.isForMainFrame()) return;
                    int code = response.getStatusCode();
                    runLog.record(store, "WEBVIEW_ERROR", "type=http;code=" + code);
                    if (store.paused()) return;
                    if (code == 429) rateLimit("HTTP 429");
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (!request.isForMainFrame()) return;
                    int code = error == null ? 0 : error.getErrorCode();
                    runLog.record(store, "WEBVIEW_ERROR", "type=network;code=" + code);
                    if (store.paused()) return;
                    if (code == -15) {
                        rateLimit("WebView ERROR_TOO_MANY_REQUESTS");
                    } else {
                        resetPhaseClock();
                        store.setStatus("일시적 네트워크 오류 · 3초 후 같은 대상 복구");
                        detachDomObserver("network_error");
                        handler.removeCallbacks(stepRunnable);
                        handler.postDelayed(() -> {
                            if (webView != null && canRun() && !isRateLimited()) webView.loadUrl(targetUrl());
                        }, 3_000L);
                    }
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (!request.isForMainFrame()) return false;
                    if (store.paused()) return false;
                    String requested = String.valueOf(request.getUrl());
                    boolean allowed = store.conversationUrl().isEmpty()
                            ? sameProject(store.projectUrl(), requested)
                            : sameConversation(store.conversationUrl(), requested);
                    runLog.record(store, "WEBVIEW_NAVIGATION", "allowed=" + (allowed ? "1" : "0"));
                    if (allowed) return false;
                    handler.postDelayed(() -> restoreCanonical("navigation"), 800L);
                    return true;
                }

                @Override
                public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
                    sslHandler.cancel();
                    runLog.record(store, "WEBVIEW_ERROR", "type=ssl");
                    if (store.paused()) return;
                    handler.post(() -> recoverStalledPhase("ssl_error",
                            "SSL 오류 취소 · 같은 대상 재접속", 2_000L));
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    boolean paused = store.paused();
                    runLog.record(store, "RENDERER_GONE", "crash=" + (detail != null && detail.didCrash())
                            + ";paused=" + (paused ? "1" : "0"));
                    resetPhaseClock();
                    cleanupWebView();
                    if (paused) {
                        store.setStatus("SelfRun 일시정지 · WebView 소실 · 재개 시 conversation 복구");
                        runLog.record(store, "WEBVIEW_RECOVERY_DEFERRED", "renderer_gone_while_paused");
                        startForegroundCompat();
                        releaseWakeLock();
                    } else {
                        store.setStatus("WebView 렌더러 복구 중");
                        updateWakeLockForState();
                        handler.postDelayed(SelfRunService.this::ensureEngine, 2_000L);
                    }
                    return true;
                }
            });
            updateWakeLockForState();
            active.loadUrl(target);
        } catch (Throwable error) {
            runLog.record(store, "WEBVIEW_INIT_FAILED", error.getClass().getSimpleName());
            resetPhaseClock();
            cleanupWebView();
            store.setStatus("WebView 초기화 재시도");
            handler.postDelayed(this::ensureEngine, 2_500L);
        }
    }

    private void runStep() {
        if (!canRun() || webView == null) return;
        if (evaluationInFlight) {
            domEvaluationPending = true;
            return;
        }
        if (isRateLimited()) {
            updateWakeLockForState();
            requestDomEvaluation(Math.max(250L, rateLimitedUntilElapsed - SystemClock.elapsedRealtime()), "rate_limit_expiry");
            return;
        }
        domEvaluationPending = false;
        updateWakeLockForState();
        String actual = webView.getUrl();
        if (!routeAcceptable(actual)) {
            runLog.record(store, "TARGET_DRIFT", store.conversationUrl().isEmpty() ? "expected=project" : "expected=conversation");
            restoreCanonical("step");
            return;
        }
        String phase = store.phase();
        if (timedOut(phase)) return;
        String script;
        switch (phase) {
            case SelfRunStore.PHASE_BOOTSTRAP ->
                    script = SelfRunDom.prepareInitialContext(store.projectUrl(), store.mode(), store.runId());
            case PHASE_BOOTSTRAP_MODEL ->
                    script = WorkPreferenceDom.modelForProject(store.projectUrl(), "sol");
            case PHASE_BOOTSTRAP_REASONING ->
                    script = WorkPreferenceDom.reasoningForProject(store.projectUrl(), "xhigh");
            case PHASE_BOOTSTRAP_SEND ->
                    script = SelfRunDom.sendInitial(store.projectUrl(),
                            SelfRunProtocol.bootstrap(store.runId(), store.mode(), store.requirement()), store.runId());
            case SelfRunStore.PHASE_WAIT_ASSISTANT ->
                    script = SelfRunDom.observeAssistant(store.conversationUrl(), store.assistantBaselineKey());
            case SelfRunStore.PHASE_APPLY_PREFS ->
                    script = WorkPreferenceDom.modelForConversation(store.conversationUrl(), store.pendingModel());
            case PHASE_APPLY_REASONING ->
                    script = WorkPreferenceDom.reasoningForConversation(store.conversationUrl(), store.pendingReasoning());
            case SelfRunStore.PHASE_SEND_CONTINUE -> {
                String outgoing = "RECOVERY".equals(store.lastSignal())
                        ? SelfRunProtocol.signalRecovery(store.runId())
                        : SelfRunProtocol.continuation(store.runId());
                script = SelfRunDom.sendTurn(store.conversationUrl(), outgoing, store.runId(), store.turn() + 1);
            }
            default -> { pause("STATE_INVALID", "알 수 없는 SelfRun 상태입니다: " + phase); return; }
        }
        evaluate(phase, script);
    }

    private boolean timedOut(String phase) {
        long age = Math.max(0L, System.currentTimeMillis() - store.phaseStartedAt());
        if ((SelfRunStore.PHASE_BOOTSTRAP.equals(phase) || PHASE_BOOTSTRAP_SEND.equals(phase))
                && age >= STARTUP_TIMEOUT_MS) {
            recoverStalledPhase("bootstrap_stalled",
                    "프로젝트 새 대화 준비가 오래 정체되어 같은 대상 재접속", 1_500L);
            return true;
        }
        if ((PHASE_BOOTSTRAP_MODEL.equals(phase) || PHASE_BOOTSTRAP_REASONING.equals(phase)
                || SelfRunStore.PHASE_APPLY_PREFS.equals(phase) || PHASE_APPLY_REASONING.equals(phase))
                && age >= PREF_TIMEOUT_MS) {
            recoverStalledPhase("preferences_stalled",
                    "모델·추론 적용 화면이 오래 정체되어 같은 대상 재접속", 1_500L);
            return true;
        }
        if (SelfRunStore.PHASE_WAIT_ASSISTANT.equals(phase) && age >= ASSISTANT_RESPONSE_TIMEOUT_MS) {
            recoverStalledPhase("assistant_wait_stalled",
                    "assistant 응답 대기가 오래 정체되어 같은 conversation 재접속", 1_500L);
            return true;
        }
        return false;
    }

    private void evaluate(String phase, String script) {
        WebView active = webView;
        int activeGeneration = generation;
        String trigger = pendingEvaluationTrigger;
        evaluationInFlight = true;
        evaluationCount = saturatingIncrement(evaluationCount);
        runLog.record(store, "DOM_EVALUATE", "count=" + evaluationCount + ";phase=" + phase + ";trigger=" + trigger);
        active.evaluateJavascript(script, raw -> {
            if (active != webView || activeGeneration != generation) return;
            evaluationInFlight = false;
            if (!canRun()) return;
            try {
                JSONObject result = parse(raw);
                String status = result.optString("status", "SCRIPT_ERROR");
                String detail = result.optString("detail", "");
                if (isWaitingStatus(status)) {
                    runLog.record(store, "DOM_WAIT", "phase=" + phase + ";status=" + status);
                } else {
                    runLog.record(store, "DOM_RESULT", "phase=" + phase + ";status=" + status + ";detail=" + detail);
                }
                if (!"SCRIPT_ERROR".equals(status)) {
                    rateLimitAttempt = 0;
                    rateLimitedUntilElapsed = 0L;
                }
                if ("TARGET_ERROR".equals(status)) {
                    restoreCanonical("script");
                    return;
                }
                if ("AUTH_REQUIRED".equals(status)) {
                    pause("AUTH_REQUIRED", "ChatGPT 로그인이 필요합니다.");
                    return;
                }
                if ("EXISTING_CONVERSATION".equals(status)) {
                    restoreCanonical("unexpected_existing_conversation");
                    return;
                }
                if ("MARKER_FAILED".equals(status)) {
                    uiWait("중복 방지 표식 저장 재시도");
                    return;
                }
                if ("SUBMITTED".equals(status)) {
                    if (PHASE_BOOTSTRAP_SEND.equals(phase) && submissionWaitStartedElapsed == 0L)
                        runLog.record(store, "BOOTSTRAP_SUBMITTED", "first_prompt");
                    submittedWait(detail.isEmpty() ? "제출 확인 대기" : detail);
                    return;
                }
                if (isWaitingStatus(status)) {
                    if ("STALE".equals(status)) {
                        runLog.record(store, "ASSISTANT_BASELINE_WAIT", "previous_assistant");
                    }
                    uiWait(detail.isEmpty() ? status : detail);
                    return;
                }
                handleResult(phase, status, result);
            } finally {
                drainPendingDomEvaluation();
            }
        });
    }

    private static boolean isWaitingStatus(String status) {
        return "UI_WAIT".equals(status) || "WAIT".equals(status) || "GENERATING".equals(status)
                || "STALE".equals(status);
    }

    private void handleResult(String phase, String status, JSONObject result) {
        submissionWaitStartedElapsed = 0L;
        if (SelfRunStore.PHASE_BOOTSTRAP.equals(phase) && "READY".equals(status)) {
            runLog.record(store, "BOOTSTRAP_CONTEXT_READY", "mode=" + store.mode());
            if (SelfRunStore.MODE_WORK.equals(store.mode())) {
                transition(PHASE_BOOTSTRAP_MODEL, "첫 턴 Work 모델 Sol 적용 중", "bootstrap_context_ready");
            } else {
                transition(PHASE_BOOTSTRAP_SEND, "첫 일반 Chat 요청 전송 준비", "bootstrap_context_ready");
            }
            scheduleStep(250L);
            return;
        }
        if (PHASE_BOOTSTRAP_MODEL.equals(phase) && "READY".equals(status)) {
            transition(PHASE_BOOTSTRAP_REASONING, "첫 턴 Work 추론 xHigh 적용 중", "bootstrap_model_ready");
            scheduleStep(250L);
            return;
        }
        if (PHASE_BOOTSTRAP_REASONING.equals(phase) && "READY".equals(status)) {
            runLog.record(store, "PREFERENCE_VERIFIED", "sol_xhigh");
            transition(PHASE_BOOTSTRAP_SEND, "첫 Work 요청 전송 준비", "bootstrap_preferences_ready");
            scheduleStep(250L);
            return;
        }
        if (PHASE_BOOTSTRAP_SEND.equals(phase) && "CONFIRMED".equals(status)) {
            String url = result.optString("conversationUrl", result.optString("url", ""));
            if (SelfRunScript.conversationId(url).isEmpty()) {
                uiWait("conversation URL 확인 대기");
                return;
            }
            store.setConversationUrl(url);
            store.setAssistantBaselineKey(result.optString("assistantKey", ""));
            runLog.record(store, "BOOTSTRAP_CONFIRMED", "conversation=captured");
            transition(SelfRunStore.PHASE_WAIT_ASSISTANT, "첫 assistant 응답 대기", "conversation_captured");
            cleanupWebView();
            handler.post(this::ensureEngine);
            return;
        }
        if (SelfRunStore.PHASE_WAIT_ASSISTANT.equals(phase) && "COMPLETE".equals(status)) {
            handleAssistant(result.optString("text", ""), result.optString("assistantKey", ""));
            return;
        }
        if (SelfRunStore.PHASE_APPLY_PREFS.equals(phase) && "READY".equals(status)) {
            transition(PHASE_APPLY_REASONING, "다음 턴 추론 적용 중", "model_ready");
            scheduleStep(250L);
            return;
        }
        if (PHASE_APPLY_REASONING.equals(phase) && "READY".equals(status)) {
            runLog.record(store, "PREFERENCE_VERIFIED", store.pendingModel() + "_" + store.pendingReasoning());
            transition(SelfRunStore.PHASE_SEND_CONTINUE, "다음 턴 continuation 준비", "preferences_ready");
            scheduleStep(250L);
            return;
        }
        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase) && "CONFIRMED".equals(status)) {
            store.setTurn(store.turn() + 1);
            store.setAssistantBaselineKey(result.optString("assistantKey", ""));
            transition(SelfRunStore.PHASE_WAIT_ASSISTANT,
                    "다음 assistant 응답 대기 · " + store.role(), "continuation_confirmed");
            scheduleStep(250L);
            return;
        }
        if ("READY".equals(status) || "CONFIRMED".equals(status)) scheduleStep(250L);
        else uiWait("화면 상태 재평가");
    }

    private void handleAssistant(String text, String assistantKey) {
        submissionWaitStartedElapsed = 0L;
        if (text == null || text.isBlank()) { uiWait("assistant 본문 대기"); return; }
        if (assistantKey == null || assistantKey.isBlank()) { uiWait("assistant 노드 식별 대기"); return; }
        String key = assistantKey + ":" + Integer.toHexString(text.hashCode()) + ":" + text.length();
        if (key.equals(store.lastAssistantKey())) { uiWait("새 assistant 응답 대기"); return; }
        SelfRunProtocol.Signal signal = SelfRunProtocol.parseLatest(text, store.runId(), store.mode());
        if (signal.type == SelfRunProtocol.Type.NONE) {
            store.setLastAssistantKey(key);
            store.setLastSignal("RECOVERY");
            int recoveryCount = store.signalRecoveryCount();
            store.setSignalRecoveryCount(recoveryCount == Integer.MAX_VALUE ? recoveryCount : recoveryCount + 1);
            transition(SelfRunStore.PHASE_SEND_CONTINUE, "제어 신호 복구 요청 전송 준비", "signal_recovery");
            scheduleStep(250L);
            return;
        }
        store.setLastAssistantKey(key);
        store.setLastSignal(signal.raw);
        store.setSignalRecoveryCount(0);
        runLog.record(store, "SIGNAL_ACCEPTED", signal.type.name());
        if (signal.type == SelfRunProtocol.Type.DONE) {
            store.clearLastError();
            transition(SelfRunStore.PHASE_DONE, "SelfRun 완료", "terminal_signal");
            store.setActive(false);
            runLog.record(store, "DONE", "terminal");
            NotificationHelper.notifyUser(this, "SelfRun 완료", store.runId());
            stopRelay();
            return;
        }
        if (preservesWebViewOnPause(signal.type)) {
            String status = signal.type == SelfRunProtocol.Type.USER_ACTION
                    ? "사용자 조치 대기 · " + signal.actionId
                    : "SelfRun 일시정지";
            enterPreservedPause(signal.type.name(), status);
            NotificationHelper.notifyUser(this, "SelfRun 일시중지", store.status());
            return;
        }
        store.setRole(signal.role);
        if (SelfRunStore.MODE_WORK.equals(store.mode())) {
            store.setPendingModel(signal.model);
            store.setPendingReasoning(signal.reasoning);
            transition(SelfRunStore.PHASE_APPLY_PREFS,
                    "다음 턴 " + signal.role + " · " + signal.model + " / " + signal.reasoning + " 적용 중",
                    "next_work_turn");
        } else {
            transition(SelfRunStore.PHASE_SEND_CONTINUE,
                    "다음 일반 Chat 역할 · " + signal.role, "next_chat_turn");
        }
        scheduleStep(250L);
    }

    static boolean preservesWebViewOnPause(SelfRunProtocol.Type signalType) {
        return signalType == SelfRunProtocol.Type.USER_ACTION || signalType == SelfRunProtocol.Type.PAUSE;
    }

    private void transition(String next, String status, String reason) {
        String previous = store.phase();
        store.setPhase(next);
        store.setStatus(status);
        runLog.record(store, "STATE_TRANSITION", "from=" + previous + ";to=" + next + ";reason=" + reason);
        startForegroundCompat();
        updateWakeLockForState();
    }

    private void uiWait(String detail) {
        store.setStatus(detail + " · DOM 변화 대기");
        ensureDomObserver();
        scheduleWatchdog();
    }

    private void submittedWait(String detail) {
        long now = SystemClock.elapsedRealtime();
        if (submissionWaitStartedElapsed == 0L) submissionWaitStartedElapsed = now;
        if (now - submissionWaitStartedElapsed >= SUBMISSION_STALL_TIMEOUT_MS) {
            recoverStalledPhase("submission_observation_stalled",
                    "제출 표식은 유지 · 같은 대상 재접속 후 계속 확인", 1_500L);
            return;
        }
        store.setStatus(detail + " · 재전송 없이 DOM 변화 대기");
        ensureDomObserver();
        scheduleWatchdog();
    }

    private void scheduleWatchdog() {
        handler.removeCallbacks(watchdogRunnable);
        if (webView != null && canRun()) handler.postDelayed(watchdogRunnable, DOM_WATCHDOG_MS);
    }

    private void runDomWatchdog() {
        if (!canRun() || webView == null) return;
        if (isRateLimited()) {
            scheduleWatchdog();
            return;
        }
        watchdogCount = saturatingIncrement(watchdogCount);
        if (observerPort == null || observerInstallInFlight || observerLease.isEmpty()) {
            watchdogRecoveryCount = saturatingIncrement(watchdogRecoveryCount);
            runLog.record(store, "DOM_WATCHDOG_RECOVERY", "count=" + watchdogCount + ";reason=observer_missing");
            ensureDomObserver();
            requestDomEvaluation(0L, "watchdog_observer_missing");
            scheduleWatchdog();
            return;
        }
        if (observerHealthInFlight) {
            scheduleWatchdog();
            return;
        }

        WebView active = webView;
        int activeGeneration = generation;
        int activeEpoch = observerEpoch;
        String activeRunId = store.runId();
        String activeLease = observerLease;
        observerHealthInFlight = true;
        observerMaintenanceEvaluationCount = saturatingIncrement(observerMaintenanceEvaluationCount);
        runLog.record(store, "DOM_OBSERVER_HEALTH_EVALUATE",
                "count=" + observerMaintenanceEvaluationCount + ";watchdog=" + watchdogCount);
        active.evaluateJavascript(SelfRunDomObserver.health(activeLease), raw -> {
            if (active != webView || activeGeneration != generation || activeEpoch != observerEpoch
                    || !activeRunId.equals(store.runId()) || !activeLease.equals(observerLease)) return;
            observerHealthInFlight = false;
            if (!canRun()) return;
            JSONObject health = parse(raw);
            String status = health.optString("status", "SCRIPT_ERROR");
            boolean alive = "ALIVE".equals(status)
                    && activeLease.equals(health.optString("lease", ""))
                    && health.optBoolean("port", false)
                    && health.optBoolean("rootConnected", false);
            if (!alive) {
                watchdogRecoveryCount = saturatingIncrement(watchdogRecoveryCount);
                runLog.record(store, "DOM_WATCHDOG_RECOVERY",
                        "count=" + watchdogCount + ";reason=" + status.toLowerCase());
                detachDomObserver("watchdog_" + status.toLowerCase());
                ensureDomObserver();
                requestDomEvaluation(0L, "watchdog_observer_recovery");
                scheduleWatchdog();
                return;
            }

            String pageState = health.optString("fingerprint", "");
            long suppressed = health.optLong("suppressed", 0L);
            if (!pageState.isEmpty() && !pageState.equals(lastObserverState)) {
                watchdogRecoveryCount = saturatingIncrement(watchdogRecoveryCount);
                lastObserverState = pageState;
                runLog.record(store, "DOM_WATCHDOG_RECOVERY",
                        "count=" + watchdogCount + ";reason=missed_state_event;suppressed=" + suppressed);
                requestDomEvaluation(0L, "watchdog_missed_event");
            } else {
                runLog.record(store, "DOM_WATCHDOG_HEALTH",
                        "count=" + watchdogCount + ";observer=alive;suppressed=" + suppressed);
            }
            scheduleWatchdog();
        });
    }

    private void ensureDomObserver() {
        WebView active = webView;
        if (active == null || !canRun() || isRateLimited() || observerPort != null || observerInstallInFlight) return;
        Uri targetOrigin = chatGptOrigin(active.getUrl());
        if (targetOrigin == null) {
            watchdogRecoveryCount = saturatingIncrement(watchdogRecoveryCount);
            requestDomEvaluation(0L, "observer_origin_recovery");
            scheduleWatchdog();
            return;
        }
        int activeGeneration = generation;
        int activeEpoch = ++observerEpoch;
        String activeRunId = store.runId();
        String token = UUID.randomUUID().toString();
        String lease = activeRunId + ":" + activeGeneration + ":" + activeEpoch + ":" + UUID.randomUUID();
        observerLease = lease;
        lastObserverState = "";
        observerInstallInFlight = true;
        observerMaintenanceEvaluationCount = saturatingIncrement(observerMaintenanceEvaluationCount);
        runLog.record(store, "DOM_OBSERVER_INSTALL_EVALUATE",
                "count=" + observerMaintenanceEvaluationCount + ";generation=" + activeGeneration + ";epoch=" + activeEpoch);
        active.evaluateJavascript(SelfRunDomObserver.install(token, lease), raw -> {
            if (active != webView || activeGeneration != generation || activeEpoch != observerEpoch
                    || !lease.equals(observerLease)) return;
            observerInstallInFlight = false;
            if (!canRun() || !activeRunId.equals(store.runId())) return;
            try {
                WebMessagePort[] channel = active.createWebMessageChannel();
                if (channel == null || channel.length < 2) throw new IllegalStateException("web message channel unavailable");
                WebMessagePort nativePort = channel[0];
                WebMessagePort pagePort = channel[1];
                nativePort.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                    @Override
                    public void onMessage(WebMessagePort port, WebMessage message) {
                        if (active != webView || activeGeneration != generation || activeEpoch != observerEpoch
                                || observerPort != port || !canRun() || !activeRunId.equals(store.runId())
                                || !lease.equals(observerLease)) return;
                        String data = message == null ? "" : message.getData();
                        if (data.startsWith("ready|")) {
                            lastObserverState = data.substring("ready|".length());
                            runLog.record(store, "DOM_OBSERVER_ATTACHED",
                                    "phase=" + store.phase() + ";generation=" + activeGeneration + ";epoch=" + activeEpoch);
                            requestDomEvaluation(0L, "observer_ready");
                            scheduleWatchdog();
                        } else if (data.startsWith("state|")) {
                            String nextState = data.substring("state|".length());
                            if (nextState.equals(lastObserverState)) {
                                observerDuplicateEventCount = saturatingIncrement(observerDuplicateEventCount);
                                return;
                            }
                            lastObserverState = nextState;
                            observerEventCount = saturatingIncrement(observerEventCount);
                            runLog.record(store, "DOM_OBSERVER_STATE",
                                    "count=" + observerEventCount + ";phase=" + store.phase());
                            requestDomEvaluation(0L, "observer_state");
                            scheduleWatchdog();
                        }
                    }
                });
                observerPort = nativePort;
                active.postWebMessage(new WebMessage(token, new WebMessagePort[]{pagePort}), targetOrigin);
            } catch (Throwable error) {
                observerLease = "";
                closeObserverPort();
                watchdogRecoveryCount = saturatingIncrement(watchdogRecoveryCount);
                runLog.record(store, "DOM_OBSERVER_FAILED", error.getClass().getSimpleName());
                requestDomEvaluation(0L, "observer_install_failed_recovery");
                scheduleWatchdog();
            }
        });
    }

    private static Uri chatGptOrigin(String url) {
        try {
            Uri parsed = Uri.parse(url == null ? "" : url);
            String host = parsed.getHost();
            if (!"https".equalsIgnoreCase(parsed.getScheme())) return null;
            if (!"chatgpt.com".equals(host) && !"www.chatgpt.com".equals(host)) return null;
            return Uri.parse("https://" + host);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void detachDomObserver(String cause) {
        WebView active = webView;
        boolean hadObserver = observerPort != null || observerInstallInFlight || !observerLease.isEmpty();
        handler.removeCallbacks(watchdogRunnable);
        observerEpoch++;
        observerInstallInFlight = false;
        observerHealthInFlight = false;
        observerLease = "";
        lastObserverState = "";
        closeObserverPort();
        if (active != null) {
            try {
                observerMaintenanceEvaluationCount = saturatingIncrement(observerMaintenanceEvaluationCount);
                active.evaluateJavascript(SelfRunDomObserver.detach(), null);
            } catch (Throwable ignored) {}
        }
        if (hadObserver) runLog.record(store, "DOM_OBSERVER_DETACHED", "cause=" + cause);
    }

    private void invalidateDomObserverForNavigation() {
        handler.removeCallbacks(watchdogRunnable);
        observerEpoch++;
        observerInstallInFlight = false;
        observerHealthInFlight = false;
        observerLease = "";
        lastObserverState = "";
        closeObserverPort();
    }

    private void closeObserverPort() {
        WebMessagePort port = observerPort;
        observerPort = null;
        if (port != null) {
            try { port.close(); } catch (Throwable ignored) {}
        }
    }

    private boolean isRateLimited() {
        return rateLimitedUntilElapsed > SystemClock.elapsedRealtime();
    }

    private void rateLimit(String reason) {
        if (!canRun()) return;
        long now = SystemClock.elapsedRealtime();
        if (rateLimitedUntilElapsed > now) return;
        rateLimitAttempt = Math.min(rateLimitAttempt + 1, RATE_LIMIT_DELAYS.length);
        long delay = RATE_LIMIT_DELAYS[rateLimitAttempt - 1];
        rateLimitedUntilElapsed = now + delay;
        resetPhaseClock();
        store.setStatus(reason + " · " + (delay / 1000L) + "초 동안 DOM 실행 중지");
        runLog.record(store, "RATE_LIMIT", "attempt=" + rateLimitAttempt + ";delay=" + delay);
        handler.removeCallbacks(stepRunnable);
        handler.removeCallbacks(watchdogRunnable);
        detachDomObserver("rate_limit");
        updateWakeLockForState();
        handler.postDelayed(() -> {
            if (!canRun()) return;
            updateWakeLockForState();
            if (webView != null) webView.loadUrl(targetUrl());
            else ensureEngine();
        }, delay);
    }

    private void restoreCanonical(String source) {
        if (!canRun() || isRateLimited()) return;
        String target = targetUrl();
        resetPhaseClock();
        store.setStatus("대상 화면 복구 중 · " + source);
        runLog.record(store, "TARGET_RESTORE", "source=" + source);
        handler.removeCallbacks(stepRunnable);
        handler.removeCallbacks(watchdogRunnable);
        detachDomObserver("target_restore");
        handler.postDelayed(() -> {
            if (!canRun()) return;
            if (webView != null && !isRateLimited()) webView.loadUrl(target);
            else ensureEngine();
        }, 1_200L);
    }

    private void recoverStalledPhase(String reason, String status, long delay) {
        if (!canRun()) return;
        String phase = store.phase();
        resetPhaseClock();
        submissionWaitStartedElapsed = 0L;
        store.setStatus(status);
        runLog.record(store, "AUTO_RECOVERY", "reason=" + reason + ";phase=" + phase);
        startForegroundCompat();
        cleanupWebView();
        updateWakeLockForState();
        handler.postDelayed(this::ensureEngine, delay);
    }

    private void resetPhaseClock() {
        if (store != null && canRun()) store.restartPhaseClock();
    }

    private boolean routeAcceptable(String actual) {
        if (actual == null || actual.isEmpty()) return false;
        return store.conversationUrl().isEmpty()
                ? sameProject(store.projectUrl(), actual)
                : sameConversation(store.conversationUrl(), actual);
    }

    private static boolean sameProject(String expected, String actual) {
        String a = SelfRunScript.projectId(expected), b = SelfRunScript.projectId(actual);
        return !a.isEmpty() && a.equals(b);
    }

    private static boolean sameConversation(String expected, String actual) {
        String a = SelfRunScript.conversationId(expected), b = SelfRunScript.conversationId(actual);
        return !a.isEmpty() && a.equals(b);
    }

    private void scheduleStep(long delay) {
        requestDomEvaluation(delay, "phase_transition");
    }

    private void requestDomEvaluation(long delay, String trigger) {
        if (webView == null || !canRun()) return;
        pendingEvaluationTrigger = trigger;
        domEvaluationPending = true;
        if (evaluationInFlight) return;
        handler.removeCallbacks(stepRunnable);
        handler.postDelayed(stepRunnable, Math.max(0L, delay));
    }

    private void drainPendingDomEvaluation() {
        if (!domEvaluationPending || evaluationInFlight || webView == null || !canRun()) return;
        handler.removeCallbacks(stepRunnable);
        handler.post(stepRunnable);
    }

    private JSONObject parse(String raw) {
        try {
            Object outer = new JSONTokener(raw == null ? "" : raw).nextValue();
            String text = outer instanceof String ? (String) outer : String.valueOf(outer);
            return new JSONObject(text);
        } catch (Throwable error) {
            JSONObject result = new JSONObject();
            try { result.put("status", "SCRIPT_ERROR"); } catch (Throwable ignored) {}
            return result;
        }
    }

    private void pause(String code, String message) {
        store.setLastError(code, message);
        store.setPaused(true);
        String previous = store.phase();
        store.setPhase(SelfRunStore.PHASE_PAUSED);
        store.setStatus(code + " · " + message);
        runLog.record(store, "STATE_TRANSITION", "from=" + previous + ";to=PAUSED;reason=" + code);
        runLog.record(store, "PAUSED", code);
        NotificationHelper.notifyUser(this, "SelfRun 확인 필요", store.status());
        stopRelay();
    }

    private void pauseFromUi() {
        if (!store.active() || store.paused() || store.userStopped() || store.runId().isEmpty()) return;
        runLog.record(store, "UI_PAUSE", "user_pause");
        enterPreservedPause("UI_PAUSE", "사용자 일시정지");
    }

    private void resumeFromUi() {
        if (!store.paused() || store.userStopped() || store.runId().isEmpty()) return;
        boolean preserved = webView != null;
        store.setPaused(false);
        store.setActive(true);
        store.setUserStopped(false);
        store.clearLastError();
        store.setLastSignal("USER_RESUME");
        if (store.conversationUrl().isEmpty()) store.setPhase(SelfRunStore.PHASE_BOOTSTRAP);
        else store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE);
        store.setStatus(store.conversationUrl().isEmpty()
                ? "사용자 재개 · 새 대화 bootstrap 복구 중"
                : preserved
                        ? "사용자 재개 · 동일 WebView continuation 준비"
                        : "사용자 재개 · WebView 소실 복구 후 continuation 준비");
        runLog.record(store, "UI_RESUME", preserved ? "same_webview" : "webview_recovery");
        startForegroundCompat();
        updateWakeLockForState();

        if (preserved) {
            ensureDomObserver();
            scheduleWatchdog();
        } else {
            runLog.record(store, "WEBVIEW_RECOVERY_RECONNECT", store.conversationUrl().isEmpty()
                    ? "source=resume;target=project" : "source=resume;target=persisted_conversation");
            handler.post(this::ensureEngine);
        }
    }

    private void enterPreservedPause(String cause, String status) {
        String previous = store.phase();
        store.setPaused(true);
        store.setPhase(SelfRunStore.PHASE_PAUSED);
        store.setStatus(status);
        handler.removeCallbacksAndMessages(null);
        generation++;
        evaluationInFlight = false;
        domEvaluationPending = false;
        submissionWaitStartedElapsed = 0L;
        detachDomObserver(cause);
        releaseWakeLock();
        startForegroundCompat();
        runLog.record(store, "STATE_TRANSITION", "from=" + previous + ";to=PAUSED;reason=" + cause);
        runLog.record(store, "PAUSED", cause + ";webview=preserved;automation=stopped;low_power=1");
        logDomEfficiency("pause");
    }

    private void updateWakeLockForState() {
        if (canRun() && !isRateLimited()) acquireWakeLock();
        else releaseWakeLock();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void cleanupWebView() {
        handler.removeCallbacks(stepRunnable);
        handler.removeCallbacks(watchdogRunnable);
        observerEpoch++;
        observerInstallInFlight = false;
        observerHealthInFlight = false;
        observerLease = "";
        lastObserverState = "";
        closeObserverPort();
        evaluationInFlight = false;
        domEvaluationPending = false;
        generation++;
        if (host != null) {
            host.destroy();
            host = null;
        }
        webView = null;
    }

    private void stopRelay() {
        logDomEfficiency("stop");
        cleanupWebView();
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void logDomEfficiency(String cause) {
        if (runLog == null || store == null) return;
        runLog.record(store, "DOM_EFFICIENCY",
                "cause=" + cause
                        + ";fullChecks=" + evaluationCount
                        + ";maintenanceEvaluations=" + observerMaintenanceEvaluationCount
                        + ";stateEvents=" + observerEventCount
                        + ";nativeDuplicates=" + observerDuplicateEventCount
                        + ";watchdogs=" + watchdogCount
                        + ";watchdogRecoveries=" + watchdogRecoveryCount);
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    @Override
    public void onDestroy() {
        cleanupWebView();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
