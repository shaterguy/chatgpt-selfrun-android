package com.shaterguy.chatgptselfrun;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import org.json.JSONTokener;

public final class SelfRunService extends Service {
    static final String ACTION_RUN = "com.shaterguy.chatgptselfrun.RUN";
    private static final int NOTIFICATION_ID = 7021;
    private static final String PHASE_BOOTSTRAP_MODEL = "BOOTSTRAP_MODEL";
    private static final String PHASE_BOOTSTRAP_REASONING = "BOOTSTRAP_REASONING";
    private static final String PHASE_BOOTSTRAP_SEND = "BOOTSTRAP_SEND";
    private static final String PHASE_APPLY_REASONING = "APPLY_REASONING";
    private static final long[] RATE_LIMIT_DELAYS = {2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L};
    private static final int MAX_SUBMITTED_OBSERVATIONS = 40;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stepRunnable = this::runStep;
    private SelfRunStore store;
    private SelfRunRunLog runLog;
    private HeadlessWebViewHost host;
    private WebView webView;
    private boolean evaluationInFlight;
    private int generation;
    private int uiWaitCount;
    private int submittedObservationCount;
    private int rateLimitAttempt;
    private long rateLimitedUntilElapsed;
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
        if (intent != null && !ACTION_RUN.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!store.active() || store.paused()) {
            stopRelay();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        acquireWakeLock();
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
        if (!canRun()) { stopRelay(); return; }
        String target = targetUrl();
        if (target.isEmpty()) {
            pause("TARGET_MISSING", "대상 프로젝트 또는 conversation URL이 없습니다.");
            return;
        }
        if (webView != null) {
            scheduleStep(300L);
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
            runLog.record(store, "WEBVIEW_LAUNCH", store.conversationUrl().isEmpty() ? "project" : "conversation");
            WebView active = webView;
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    generation++;
                    evaluationInFlight = false;
                    handler.removeCallbacks(stepRunnable);
                    store.setStatus("ChatGPT 화면 로딩 중");
                    startForegroundCompat();
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (isRateLimited()) return;
                    uiWaitCount = 0;
                    store.setStatus("ChatGPT 화면 준비 확인 중");
                    scheduleStep(900L);
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                    if (request.isForMainFrame() && response.getStatusCode() == 429) rateLimit("HTTP 429");
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (!request.isForMainFrame()) return;
                    if (error != null && error.getErrorCode() == -15) {
                        rateLimit("WebView ERROR_TOO_MANY_REQUESTS");
                    } else {
                        store.setStatus("일시적 네트워크 오류 · 3초 후 같은 대화 복구");
                        handler.removeCallbacks(stepRunnable);
                        handler.postDelayed(() -> {
                            if (webView != null && canRun() && !isRateLimited()) webView.loadUrl(targetUrl());
                        }, 3_000L);
                    }
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (!request.isForMainFrame()) return false;
                    String requested = String.valueOf(request.getUrl());
                    if (store.conversationUrl().isEmpty()) return !sameProject(store.projectUrl(), requested);
                    if (sameConversation(store.conversationUrl(), requested)) return false;
                    handler.postDelayed(() -> restoreCanonical("navigation"), 800L);
                    return true;
                }

                @Override
                public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
                    sslHandler.cancel();
                    pause("SSL_ERROR", "SSL 오류로 SelfRun을 일시중지했습니다.");
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    cleanupWebView();
                    store.setStatus("WebView 렌더러 복구 중");
                    handler.postDelayed(SelfRunService.this::ensureEngine, 2_000L);
                    return true;
                }
            });
            active.loadUrl(target);
        } catch (Throwable error) {
            cleanupWebView();
            store.setStatus("WebView 초기화 재시도");
            handler.postDelayed(this::ensureEngine, 2_500L);
        }
    }

    private void runStep() {
        if (!canRun() || webView == null || evaluationInFlight) return;
        if (isRateLimited()) {
            scheduleStep(Math.max(250L, rateLimitedUntilElapsed - SystemClock.elapsedRealtime()));
            return;
        }
        String actual = webView.getUrl();
        if (!routeAcceptable(actual)) {
            restoreCanonical("step");
            return;
        }
        String phase = store.phase();
        String script;
        switch (phase) {
            case SelfRunStore.PHASE_BOOTSTRAP ->
                    script = SelfRunDom.prepareMode(store.projectUrl(), store.mode(), store.runId());
            case PHASE_BOOTSTRAP_MODEL ->
                    script = WorkPreferenceDom.modelForProject(store.projectUrl(), "sol");
            case PHASE_BOOTSTRAP_REASONING ->
                    script = WorkPreferenceDom.reasoningForProject(store.projectUrl(), "xhigh");
            case PHASE_BOOTSTRAP_SEND ->
                    script = SelfRunDom.sendInitial(store.projectUrl(),
                            SelfRunProtocol.bootstrap(store.runId(), store.mode(), store.requirement()), store.runId());
            case SelfRunStore.PHASE_WAIT_ASSISTANT ->
                    script = SelfRunDom.observeAssistant(store.conversationUrl());
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

    private void evaluate(String phase, String script) {
        WebView active = webView;
        int activeGeneration = generation;
        evaluationInFlight = true;
        active.evaluateJavascript(script, raw -> {
            evaluationInFlight = false;
            if (active != webView || activeGeneration != generation || !canRun()) return;
            JSONObject result = parse(raw);
            String status = result.optString("status", "SCRIPT_ERROR");
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
            if ("MARKER_FAILED".equals(status)) {
                pause("SUBMIT_MARKER_FAILED", "중복 방지 표식을 저장하지 못해 자동 제출을 중지했습니다.");
                return;
            }
            if ("SUBMITTED".equals(status)) {
                submittedWait(result.optString("detail", "제출 확인 대기"));
                return;
            }
            if ("UI_WAIT".equals(status) || "WAIT".equals(status) || "GENERATING".equals(status)) {
                uiWait(result.optString("detail", status));
                return;
            }
            handleResult(phase, status, result);
        });
    }

    private void handleResult(String phase, String status, JSONObject result) {
        uiWaitCount = 0;
        submittedObservationCount = 0;
        if (SelfRunStore.PHASE_BOOTSTRAP.equals(phase) && "READY".equals(status)) {
            if (SelfRunStore.MODE_WORK.equals(store.mode())) {
                store.setPhase(PHASE_BOOTSTRAP_MODEL);
                store.setStatus("첫 턴 Work 모델 Sol 적용 중");
            } else {
                store.setPhase(PHASE_BOOTSTRAP_SEND);
                store.setStatus("첫 일반 Chat 요청 전송 준비");
            }
            scheduleStep(250L);
            return;
        }
        if (PHASE_BOOTSTRAP_MODEL.equals(phase) && "READY".equals(status)) {
            store.setPhase(PHASE_BOOTSTRAP_REASONING);
            store.setStatus("첫 턴 Work 추론 xHigh 적용 중");
            scheduleStep(250L);
            return;
        }
        if (PHASE_BOOTSTRAP_REASONING.equals(phase) && "READY".equals(status)) {
            runLog.record(store, "PREFERENCE_VERIFIED", "sol_xhigh");
            store.setPhase(PHASE_BOOTSTRAP_SEND);
            store.setStatus("첫 Work 요청 전송 준비");
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
            store.setPhase(SelfRunStore.PHASE_WAIT_ASSISTANT);
            store.setStatus("첫 assistant 응답 대기");
            cleanupWebView();
            handler.post(this::ensureEngine);
            return;
        }
        if (SelfRunStore.PHASE_WAIT_ASSISTANT.equals(phase) && "COMPLETE".equals(status)) {
            handleAssistant(result.optString("text", ""));
            return;
        }
        if (SelfRunStore.PHASE_APPLY_PREFS.equals(phase) && "READY".equals(status)) {
            store.setPhase(PHASE_APPLY_REASONING);
            store.setStatus("다음 턴 추론 적용 중");
            scheduleStep(250L);
            return;
        }
        if (PHASE_APPLY_REASONING.equals(phase) && "READY".equals(status)) {
            runLog.record(store, "PREFERENCE_VERIFIED", store.pendingModel() + "_" + store.pendingReasoning());
            store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE);
            store.setStatus("다음 턴 continuation 준비");
            scheduleStep(250L);
            return;
        }
        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase) && "CONFIRMED".equals(status)) {
            store.setTurn(store.turn() + 1);
            store.setPhase(SelfRunStore.PHASE_WAIT_ASSISTANT);
            store.setStatus("다음 assistant 응답 대기 · " + store.role());
            scheduleStep(700L);
            return;
        }
        if ("READY".equals(status) || "CONFIRMED".equals(status)) scheduleStep(500L);
        else uiWait("화면 상태 재평가");
    }

    private void handleAssistant(String text) {
        submittedObservationCount = 0;
        if (text == null || text.isBlank()) { uiWait("assistant 본문 대기"); return; }
        String key = Integer.toHexString(text.hashCode()) + ":" + text.length();
        if (key.equals(store.lastAssistantKey())) { scheduleStep(1_200L); return; }
        SelfRunProtocol.Signal signal = SelfRunProtocol.parseLatest(text, store.runId(), store.mode());
        if (signal.type == SelfRunProtocol.Type.NONE) {
            if (store.signalRecoveryCount() >= 1) {
                pause("SIGNAL_MISSING", "SelfRun 제어 신호가 두 번 연속 누락되었습니다.");
                return;
            }
            store.setLastAssistantKey(key);
            store.setLastSignal("RECOVERY");
            store.setSignalRecoveryCount(store.signalRecoveryCount() + 1);
            store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE);
            store.setStatus("제어 신호 복구 요청 전송 준비");
            scheduleStep(250L);
            return;
        }
        store.setLastAssistantKey(key);
        store.setLastSignal(signal.raw);
        store.setSignalRecoveryCount(0);
        runLog.record(store, "SIGNAL_ACCEPTED", signal.type.name());
        if (signal.type == SelfRunProtocol.Type.DONE) {
            store.clearLastError();
            store.setPhase(SelfRunStore.PHASE_DONE);
            store.setStatus("SelfRun 완료");
            store.setActive(false);
            runLog.record(store, "DONE", "terminal");
            NotificationHelper.notifyUser(this, "SelfRun 완료", store.runId());
            stopRelay();
            return;
        }
        if (signal.type == SelfRunProtocol.Type.USER_ACTION || signal.type == SelfRunProtocol.Type.PAUSE) {
            store.setPaused(true);
            store.setPhase(SelfRunStore.PHASE_PAUSED);
            store.setStatus(signal.type == SelfRunProtocol.Type.USER_ACTION
                    ? "사용자 조치 대기 · " + signal.actionId : "SelfRun 일시중지");
            runLog.record(store, "PAUSED", signal.type.name());
            NotificationHelper.notifyUser(this, "SelfRun 일시중지", store.status());
            stopRelay();
            return;
        }
        store.setRole(signal.role);
        if (SelfRunStore.MODE_WORK.equals(store.mode())) {
            store.setPendingModel(signal.model);
            store.setPendingReasoning(signal.reasoning);
            store.setPhase(SelfRunStore.PHASE_APPLY_PREFS);
            store.setStatus("다음 턴 " + signal.role + " · " + signal.model + " / " + signal.reasoning + " 적용 중");
        } else {
            store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE);
            store.setStatus("다음 일반 Chat 역할 · " + signal.role);
        }
        scheduleStep(250L);
    }

    private void uiWait(String detail) {
        uiWaitCount = Math.min(uiWaitCount + 1, 10);
        long delay = Math.min(5_000L, 500L + (long) uiWaitCount * 500L);
        store.setStatus(detail + " · 같은 화면 재확인");
        scheduleStep(delay);
    }

    private void submittedWait(String detail) {
        submittedObservationCount++;
        if (submittedObservationCount >= MAX_SUBMITTED_OBSERVATIONS) {
            pause("SUBMISSION_AMBIGUOUS",
                    "전송 클릭 표식은 있으나 사용자 메시지 DOM을 확인하지 못했습니다. 중복 전송하지 않습니다.");
            return;
        }
        store.setStatus(detail + " · 재전송 없이 확인 중");
        scheduleStep(1_500L);
    }

    private boolean isRateLimited() {
        return rateLimitedUntilElapsed > SystemClock.elapsedRealtime();
    }

    private void rateLimit(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (rateLimitedUntilElapsed > now) return;
        rateLimitAttempt = Math.min(rateLimitAttempt + 1, RATE_LIMIT_DELAYS.length);
        long delay = RATE_LIMIT_DELAYS[rateLimitAttempt - 1];
        rateLimitedUntilElapsed = now + delay;
        store.setStatus(reason + " · " + (delay / 1000L) + "초 동안 DOM 실행 중지");
        runLog.record(store, "RATE_LIMIT", "attempt=" + rateLimitAttempt + ";delay=" + delay);
        handler.removeCallbacks(stepRunnable);
        handler.postDelayed(() -> {
            if (!canRun()) return;
            if (webView != null) webView.loadUrl(targetUrl());
            else ensureEngine();
        }, delay);
    }

    private void restoreCanonical(String source) {
        if (!canRun() || isRateLimited()) return;
        String target = targetUrl();
        store.setStatus("대상 화면 복구 중 · " + source);
        handler.removeCallbacks(stepRunnable);
        handler.postDelayed(() -> {
            if (webView != null && !isRateLimited()) webView.loadUrl(target);
            else ensureEngine();
        }, 1_200L);
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
        handler.removeCallbacks(stepRunnable);
        if (webView != null && canRun()) handler.postDelayed(stepRunnable, delay);
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
        store.setPhase(SelfRunStore.PHASE_PAUSED);
        store.setStatus(code + " · " + message);
        runLog.record(store, "PAUSED", code);
        NotificationHelper.notifyUser(this, "SelfRun 확인 필요", store.status());
        stopRelay();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(10 * 60_000L);
    }

    private void cleanupWebView() {
        handler.removeCallbacks(stepRunnable);
        evaluationInFlight = false;
        generation++;
        if (host != null) {
            host.destroy();
            host = null;
        }
        webView = null;
    }

    private void stopRelay() {
        cleanupWebView();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        cleanupWebView();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
