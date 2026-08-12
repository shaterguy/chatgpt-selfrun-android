from pathlib import Path


def read(path):
    return Path(path).read_text()


def write(path, text):
    Path(path).write_text(text)


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))


# Version and branch metadata.
replace_once("app/build.gradle", "versionCode 16", "versionCode 17")
replace_once("app/build.gradle", "versionName '0.2.3-dev4'", "versionName '0.2.3-dev5'")
replace_once(
    "app/src/main/java/com/shaterguy/chatgptselfrun/MainActivity.java",
    "v0.2.3-dev4 · 첫 화면은 작업 대시보드입니다.",
    "v0.2.3-dev5 · 첫 화면은 작업 대시보드입니다.",
)
replace_once(
    "app/src/main/java/com/shaterguy/chatgptselfrun/WebViewConfig.java",
    "ChatGPTSelfRun/0.2.3-dev4",
    "ChatGPTSelfRun/0.2.3-dev5",
)

build_path = ".github/workflows/build.yml"
build = read(build_path)
branch_line = "      - v0.2.3-dev4\n"
if build.count(branch_line) != 1:
    raise SystemExit("build.yml: dev4 branch line mismatch")
build = build.replace(branch_line, branch_line + "      - v0.2.3-dev5\n", 1)
build = build.replace("grep -q 'versionCode 16' \"$BUILD\"", "grep -q 'versionCode 17' \"$BUILD\"")
build = build.replace(
    "grep -q \"versionName '0.2.3-dev4'\" \"$BUILD\"",
    "grep -q \"versionName '0.2.3-dev5'\" \"$BUILD\"",
)
build = build.replace("grep -q 'v0.2.3-dev4' \"$MAIN\"", "grep -q 'v0.2.3-dev5' \"$MAIN\"")
build = build.replace(
    "grep -q 'ChatGPTSelfRun/0.2.3-dev4' \"$WEB\"",
    "grep -q 'ChatGPTSelfRun/0.2.3-dev5' \"$WEB\"",
)
build = build.replace("versionCode='16'", "versionCode='17'")
build = build.replace("versionName='0.2.3-dev4'", "versionName='0.2.3-dev5'")
build = build.replace(
    "chatgpt-selfrun-android-v0.2.3-dev4-",
    "chatgpt-selfrun-android-v0.2.3-dev5-",
)
anchor = "          grep -q 'detachDomObserver(cause)' \"$SERVICE\"\n"
if anchor not in build:
    raise SystemExit("build.yml: observer lifecycle policy anchor missing")
build = build.replace(
    anchor,
    anchor
    + "          grep -q 'executionEpoch' \"$SERVICE\"\n"
    + "          grep -q 'resumeObserverGate' \"$SERVICE\"\n"
    + "          grep -q 'SelfRunDomObserver.detach(detachedLease)' \"$SERVICE\"\n"
    + "          grep -q 'if (!current()) return;' \"$OBSERVER\"\n"
    + "          grep -q \"if (state.lease !== expectedLease) return 'STALE'\" \"$OBSERVER\"\n",
    1,
)
write(build_path, build)

# Page-side observer lifecycle.
observer_path = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunDomObserver.java"
replace_once(
    observer_path,
    '    static String install(String token, String lease) {\n        return """',
    '    static String install(String token, String lease) {\n'
    '        return install(token, lease, "", 0, 0);\n'
    '    }\n\n'
    '    static String install(String token, String lease, String runId, int generation, int epoch) {\n'
    '        return """',
)
replace_once(
    observer_path,
    "                  const lease = %s;\n                  const eventNames = ['input', 'change', 'click', 'submit'];",
    "                  const lease = %s;\n"
    "                  const runId = %s;\n"
    "                  const generation = %d;\n"
    "                  const epoch = %d;\n"
    "                  const eventNames = ['input', 'change', 'click', 'submit'];",
)
replace_once(
    observer_path,
    "                    if (!state) return;\n                    try { state.observer?.disconnect(); } catch (_) {}",
    "                    if (!state) return;\n"
    "                    state.active = false;\n"
    "                    try { state.observer?.takeRecords?.(); } catch (_) {}\n"
    "                    try { state.observer?.disconnect(); } catch (_) {}",
)
replace_once(
    observer_path,
    "                  cleanup(window[key]);\n"
    "                  const state = {\n"
    "                    lease, observer:null, timer:0, bridgeListener:null, eventListener:null, port:null, root:null,",
    "                  const previous = window[key];\n"
    "                  if (previous) {\n"
    "                    const sameRun = String(previous.runId || '') === runId;\n"
    "                    const previousGeneration = Number(previous.generation || 0);\n"
    "                    const previousEpoch = Number(previous.epoch || 0);\n"
    "                    if (sameRun && (previousGeneration > generation\n"
    "                        || (previousGeneration === generation && previousEpoch >= epoch))) return 'STALE_INSTALL';\n"
    "                    cleanup(previous);\n"
    "                  }\n"
    "                  const state = {\n"
    "                    lease, runId, generation, epoch, active:true, observer:null, timer:0, bridgeListener:null, eventListener:null, port:null, root:null,",
)
replace_once(
    observer_path,
    "                  window[key] = state;\n                  const visible = e => !!e && e.isConnected && e.offsetParent !== null;",
    "                  window[key] = state;\n"
    "                  const current = () => window[key] === state && state.active\n"
    "                    && state.lease === lease && state.runId === runId\n"
    "                    && state.generation === generation && state.epoch === epoch;\n"
    "                  const visible = e => !!e && e.isConnected && e.offsetParent !== null;",
)
replace_once(
    observer_path,
    "                  const notify = () => {\n                    state.lastMutationAt = Date.now();",
    "                  const notify = () => {\n                    if (!current()) return;\n                    state.lastMutationAt = Date.now();",
)
replace_once(
    observer_path,
    "                    state.timer = setTimeout(() => {\n                      state.timer = 0;\n                      const fingerprint = snapshot();",
    "                    state.timer = setTimeout(() => {\n                      if (!current()) return;\n                      state.timer = 0;\n                      const fingerprint = snapshot();",
)
replace_once(
    observer_path,
    "                  const onMutations = mutations => {\n                    state.lastObserverCallbackAt = Date.now();",
    "                  const onMutations = mutations => {\n                    if (!current()) return;\n                    state.lastObserverCallbackAt = Date.now();",
)
replace_once(
    observer_path,
    "                  state.bridgeListener = event => {\n                    if (event.data !== token || !event.ports || event.ports.length < 1) return;",
    "                  state.bridgeListener = event => {\n                    if (!current() || event.data !== token || !event.ports || event.ports.length < 1) return;",
)
replace_once(
    observer_path,
    '                """.formatted(q(token), q(lease));',
    '                """.formatted(q(token), q(lease), q(runId), generation, epoch);',
)
replace_once(
    observer_path,
    "                  if (!state) return JSON.stringify({status:'MISSING'});\n"
    "                  if (state.lease !== %s) return JSON.stringify({status:'STALE', lease:state.lease || ''});",
    "                  if (!state) return JSON.stringify({status:'MISSING'});\n"
    "                  if (!state.active) return JSON.stringify({status:'STALE', lease:state.lease || ''});\n"
    "                  if (state.lease !== %s) return JSON.stringify({status:'STALE', lease:state.lease || ''});",
)
replace_once(
    observer_path,
    "    static String detach() {\n"
    "        return \"\"\"\n"
    "                (() => {\n"
    "                  const key = '__chatgptSelfRunDomObserver';\n"
    "                  const state = window[key];\n"
    "                  if (!state) return 'MISSING';\n"
    "                  try { state.observer?.disconnect(); } catch (_) {}",
    "    static String detach(String lease) {\n"
    "        return \"\"\"\n"
    "                (() => {\n"
    "                  const key = '__chatgptSelfRunDomObserver';\n"
    "                  const expectedLease = %s;\n"
    "                  const state = window[key];\n"
    "                  if (!state) return 'MISSING';\n"
    "                  if (state.lease !== expectedLease) return 'STALE';\n"
    "                  state.active = false;\n"
    "                  try { state.observer?.takeRecords?.(); } catch (_) {}\n"
    "                  try { state.observer?.disconnect(); } catch (_) {}",
)
replace_once(
    observer_path,
    "                  try { delete window[key]; } catch (_) { window[key] = null; }\n"
    "                  return 'DETACHED';\n"
    "                })()\n"
    '                """;\n'
    "    }",
    "                  if (window[key] === state) {\n"
    "                    try { delete window[key]; } catch (_) { window[key] = null; }\n"
    "                  }\n"
    "                  return 'DETACHED';\n"
    "                })()\n"
    '                """.formatted(q(lease));\n'
    "    }",
)

# Native lifecycle.
service_path = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java"
replace_once(
    service_path,
    "    private int observerEpoch;\n    private int rateLimitAttempt;",
    "    private int observerEpoch;\n"
    "    private int rateLimitAttempt;\n"
    "    private long executionEpoch;\n"
    "    private boolean resumeObserverGate;",
)
replace_once(
    service_path,
    "                    invalidateDomObserverForNavigation();\n"
    "                    generation++;\n"
    "                    evaluationInFlight = false;",
    "                    invalidateDomObserverForNavigation();\n"
    "                    generation++;\n"
    "                    invalidateExecutionEpoch();\n"
    "                    resumeObserverGate = false;\n"
    "                    evaluationInFlight = false;",
)
replace_once(
    service_path,
    "    private void runStep() {\n        if (!canRun() || webView == null) return;",
    "    private void runStep() {\n        if (!canRun() || resumeObserverGate || webView == null) return;",
)
replace_once(
    service_path,
    "        int activeGeneration = generation;\n"
    "        String activeRunId = store.runId();\n"
    "        String trigger = pendingEvaluationTrigger;",
    "        int activeGeneration = generation;\n"
    "        long activeExecutionEpoch = executionEpoch;\n"
    "        String activeRunId = store.runId();\n"
    "        String trigger = pendingEvaluationTrigger;",
)
replace_once(
    service_path,
    "            if (!isCurrentExecution(active, activeGeneration, activeRunId) || isRateLimited()) return;",
    "            if (!isCurrentExecution(active, activeGeneration, activeExecutionEpoch, activeRunId) || isRateLimited()) return;",
)
replace_once(
    service_path,
    "        int activeGeneration = generation;\n"
    "        int activeEpoch = observerEpoch;\n"
    "        String activeRunId = store.runId();\n"
    "        String activeLease = observerLease;",
    "        int activeGeneration = generation;\n"
    "        long activeExecutionEpoch = executionEpoch;\n"
    "        int activeEpoch = observerEpoch;\n"
    "        String activeRunId = store.runId();\n"
    "        String activeLease = observerLease;",
)
replace_once(
    service_path,
    "            if (active != webView || activeGeneration != generation || activeEpoch != observerEpoch\n"
    "                    || !activeRunId.equals(store.runId()) || !activeLease.equals(observerLease)) return;",
    "            if (active != webView || activeGeneration != generation || activeExecutionEpoch != executionEpoch\n"
    "                    || activeEpoch != observerEpoch || !activeRunId.equals(store.runId())\n"
    "                    || !activeLease.equals(observerLease)) return;",
)
replace_once(
    service_path,
    '            String pageState = health.optString("fingerprint", "");',
    "            if (openResumeObserverGate(active, activeGeneration, activeExecutionEpoch, activeEpoch,\n"
    '                    activeRunId, activeLease, "watchdog_health")) {\n'
    "                scheduleWatchdog();\n"
    "                return;\n"
    "            }\n\n"
    '            String pageState = health.optString("fingerprint", "");',
)
replace_once(
    service_path,
    "        if (active == null || !canRun() || isRateLimited() || observerPort != null || observerInstallInFlight) return;\n"
    "        Uri targetOrigin = chatGptOrigin(active.getUrl());",
    "        if (active == null || !canRun() || isRateLimited() || observerPort != null || observerInstallInFlight) return;\n"
    "        if (!observerPageReady(active)) {\n"
    "            scheduleWatchdog();\n"
    "            return;\n"
    "        }\n"
    "        Uri targetOrigin = chatGptOrigin(active.getUrl());",
)
replace_once(
    service_path,
    "        int activeGeneration = generation;\n"
    "        int activeEpoch = ++observerEpoch;\n"
    "        String activeRunId = store.runId();",
    "        int activeGeneration = generation;\n"
    "        long activeExecutionEpoch = executionEpoch;\n"
    "        int activeEpoch = ++observerEpoch;\n"
    "        String activeRunId = store.runId();",
)
replace_once(
    service_path,
    "        active.evaluateJavascript(SelfRunDomObserver.install(token, lease), raw -> {\n"
    "            if (active != webView || activeGeneration != generation || activeEpoch != observerEpoch\n"
    "                    || !lease.equals(observerLease)) return;",
    "        active.evaluateJavascript(SelfRunDomObserver.install(token, lease, activeRunId, activeGeneration, activeEpoch), raw -> {\n"
    "            if (active != webView || activeGeneration != generation || activeExecutionEpoch != executionEpoch\n"
    "                    || activeEpoch != observerEpoch || !lease.equals(observerLease)) return;",
)
replace_once(
    service_path,
    "                        if (active != webView || activeGeneration != generation || activeEpoch != observerEpoch\n"
    "                                || observerPort != port || !canRun() || isRateLimited()",
    "                        if (active != webView || activeGeneration != generation || activeExecutionEpoch != executionEpoch\n"
    "                                || activeEpoch != observerEpoch || observerPort != port || !canRun() || isRateLimited()",
)
replace_once(
    service_path,
    '                            requestDomEvaluation(0L, "observer_ready");\n'
    "                            scheduleWatchdog();",
    "                            boolean resumed = openResumeObserverGate(active, activeGeneration, activeExecutionEpoch,\n"
    '                                    activeEpoch, activeRunId, lease, "observer_ready");\n'
    '                            if (!resumed) requestDomEvaluation(0L, "observer_ready");\n'
    "                            scheduleWatchdog();",
)

service = read(service_path)
helper_anchor = "    private static Uri chatGptOrigin(String url) {"
if service.count(helper_anchor) != 1:
    raise SystemExit("SelfRunService: helper anchor mismatch")
helpers = '''    private boolean observerPageReady(WebView active) {
        return active != null && active == webView && canRun() && !recoveryInProgress
                && active.getProgress() >= 100 && routeAcceptable(active.getUrl());
    }

    private boolean openResumeObserverGate(WebView active, int activeGeneration, long activeExecutionEpoch,
            int activeEpoch, String activeRunId, String activeLease, String source) {
        if (!resumeObserverGate) return false;
        if (!isCurrentExecution(active, activeGeneration, activeExecutionEpoch, activeRunId)
                || activeEpoch != observerEpoch || observerPort == null
                || !activeLease.equals(observerLease) || !observerPageReady(active)) return false;
        resumeObserverGate = false;
        runLog.record(store, "RESUME_OBSERVER_READY",
                "source=" + source + ";generation=" + activeGeneration + ";epoch=" + activeEpoch);
        requestDomEvaluation(0L, "resume_observer_ready");
        return true;
    }

'''
write(service_path, service.replace(helper_anchor, helpers + helper_anchor, 1))

old_detach = '''    private void detachDomObserver(String cause) {
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
'''
new_detach = '''    private void detachDomObserver(String cause) {
        WebView active = webView;
        String detachedLease = observerLease;
        boolean hadObserver = observerPort != null || observerInstallInFlight || !detachedLease.isEmpty();
        handler.removeCallbacks(watchdogRunnable);
        observerEpoch++;
        observerInstallInFlight = false;
        observerHealthInFlight = false;
        observerLease = "";
        lastObserverState = "";
        closeObserverPort();
        if (active != null && !detachedLease.isEmpty()) {
            try {
                observerMaintenanceEvaluationCount = saturatingIncrement(observerMaintenanceEvaluationCount);
                active.evaluateJavascript(SelfRunDomObserver.detach(detachedLease), null);
            } catch (Throwable ignored) {}
        }
        if (hadObserver) runLog.record(store, "DOM_OBSERVER_DETACHED", "cause=" + cause);
    }
'''
replace_once(service_path, old_detach, new_detach)
replace_once(
    service_path,
    "        rateLimitedUntilElapsed = now + delay;\n        generation++;\n        evaluationInFlight = false;",
    "        rateLimitedUntilElapsed = now + delay;\n"
    "        generation++;\n"
    "        invalidateExecutionEpoch();\n"
    "        resumeObserverGate = false;\n"
    "        evaluationInFlight = false;",
)
replace_once(
    service_path,
    "        WebView expectedWebView = webView;\n"
    "        int expectedGeneration = generation;\n"
    "        String expectedRunId = store.runId();\n"
    "        handler.postDelayed(() -> {\n"
    "            if (!isCurrentExecution(expectedWebView, expectedGeneration, expectedRunId) || isRateLimited()) return;",
    "        WebView expectedWebView = webView;\n"
    "        int expectedGeneration = generation;\n"
    "        long expectedExecutionEpoch = executionEpoch;\n"
    "        String expectedRunId = store.runId();\n"
    "        handler.postDelayed(() -> {\n"
    "            if (!isCurrentExecution(expectedWebView, expectedGeneration, expectedExecutionEpoch, expectedRunId) || isRateLimited()) return;",
)
replace_once(
    service_path,
    "    private boolean isCurrentExecution(WebView expectedWebView, int expectedGeneration, String expectedRunId) {\n"
    "        return expectedWebView == webView && expectedGeneration == generation\n"
    "                && expectedRunId != null && expectedRunId.equals(store.runId()) && canRun();\n"
    "    }",
    "    private boolean isCurrentExecution(WebView expectedWebView, int expectedGeneration,\n"
    "            long expectedExecutionEpoch, String expectedRunId) {\n"
    "        return expectedWebView == webView && expectedGeneration == generation\n"
    "                && expectedExecutionEpoch == executionEpoch\n"
    "                && expectedRunId != null && expectedRunId.equals(store.runId()) && canRun();\n"
    "    }\n\n"
    "    private void invalidateExecutionEpoch() {\n"
    "        executionEpoch = executionEpoch == Long.MAX_VALUE ? 1L : executionEpoch + 1L;\n"
    "    }",
)
replace_once(
    service_path,
    "        if (webView == null || !canRun() || isRateLimited()) return;",
    "        if (webView == null || !canRun() || resumeObserverGate || isRateLimited()) return;",
)
replace_once(
    service_path,
    "        if (!domEvaluationPending || evaluationInFlight || webView == null || !canRun() || isRateLimited()) return;",
    "        if (!domEvaluationPending || evaluationInFlight || webView == null || !canRun()\n"
    "                || resumeObserverGate || isRateLimited()) return;",
)

old_resume = '''    private void resumeFromUi() {
        if (!store.paused() || store.userStopped() || store.runId().isEmpty()) return;
        boolean preserved = webView != null;
        store.setPaused(false);
        store.setActive(true);
        store.setUserStopped(false);
        store.clearLastError();
        store.setLastSignal("USER_RESUME");
        if (store.conversationUrl().isEmpty()) store.setPhase(SelfRunStore.PHASE_BOOTSTRAP);
        else store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE);
        boolean rateLimited = isRateLimited();
        recoveryInProgress = !preserved && !rateLimited;
        store.setStatus(rateLimited
                ? "사용자 재개 · rate-limit 만료 대기"
                : store.conversationUrl().isEmpty()
                        ? "사용자 재개 · 새 대화 bootstrap 복구 중"
                        : preserved
                                ? "사용자 재개 · 동일 WebView continuation 준비"
                                : "사용자 재개 · WebView 소실 복구 후 continuation 준비");
        runLog.record(store, "UI_RESUME", rateLimited ? "rate_limit_wait" : preserved ? "same_webview" : "webview_recovery");
        startForegroundCompat();
        updateWakeLockForState("resume_prepare");
        if (rateLimited) {
            scheduleRateLimitExpiry();
            return;
        }

        if (preserved) {
            ensureDomObserver();
            scheduleWatchdog();
        } else {
            runLog.record(store, "WEBVIEW_RECOVERY_RECONNECT", store.conversationUrl().isEmpty()
                    ? "source=resume;target=project" : "source=resume;target=persisted_conversation");
            handler.post(this::ensureEngine);
        }
    }
'''
new_resume = '''    private void resumeFromUi() {
        if (!store.paused() || store.userStopped() || store.runId().isEmpty()) return;
        boolean preserved = webView != null;
        invalidateExecutionEpoch();
        store.setPaused(false);
        store.setActive(true);
        store.setUserStopped(false);
        store.clearLastError();
        store.setLastSignal("USER_RESUME");
        if (store.conversationUrl().isEmpty()) store.setPhase(SelfRunStore.PHASE_BOOTSTRAP);
        else store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE);
        boolean rateLimited = isRateLimited();
        resumeObserverGate = preserved && !rateLimited;
        recoveryInProgress = !preserved && !rateLimited;
        store.setStatus(rateLimited
                ? "사용자 재개 · rate-limit 만료 대기"
                : store.conversationUrl().isEmpty()
                        ? "사용자 재개 · 새 대화 bootstrap 복구 중"
                        : preserved
                                ? "사용자 재개 · 동일 WebView observer 복구 후 continuation 준비"
                                : "사용자 재개 · WebView 소실 복구 후 continuation 준비");
        runLog.record(store, "UI_RESUME", rateLimited ? "rate_limit_wait" : preserved ? "same_webview" : "webview_recovery");
        startForegroundCompat();
        updateWakeLockForState("resume_prepare");
        if (rateLimited) {
            resumeObserverGate = false;
            scheduleRateLimitExpiry();
            return;
        }

        if (preserved) {
            if (!routeAcceptable(webView.getUrl())) {
                resumeObserverGate = false;
                restoreCanonical("resume_route");
                return;
            }
            ensureDomObserver();
            scheduleWatchdog();
        } else {
            runLog.record(store, "WEBVIEW_RECOVERY_RECONNECT", store.conversationUrl().isEmpty()
                    ? "source=resume;target=project" : "source=resume;target=persisted_conversation");
            handler.post(this::ensureEngine);
        }
    }
'''
replace_once(service_path, old_resume, new_resume)
replace_once(
    service_path,
    "        handler.removeCallbacksAndMessages(null);\n        generation++;\n        evaluationInFlight = false;",
    "        handler.removeCallbacksAndMessages(null);\n"
    "        invalidateExecutionEpoch();\n"
    "        resumeObserverGate = false;\n"
    "        evaluationInFlight = false;",
)
replace_once(
    service_path,
    "        evaluationInFlight = false;\n        domEvaluationPending = false;\n        generation++;\n        if (host != null) {",
    "        evaluationInFlight = false;\n"
    "        domEvaluationPending = false;\n"
    "        resumeObserverGate = false;\n"
    "        invalidateExecutionEpoch();\n"
    "        generation++;\n"
    "        if (host != null) {",
)

# Close tests.
pause_test = "app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunPauseResumeTest.java"
replace_once(
    pause_test,
    '        int generationAdvance = body.indexOf("generation++;");\n        int observerDetach = body.indexOf("detachDomObserver(cause);");',
    '        int executionAdvance = body.indexOf("invalidateExecutionEpoch();");\n        int observerDetach = body.indexOf("detachDomObserver(cause);");',
)
replace_once(
    pause_test,
    "        assertTrue(generationAdvance >= 0);",
    '        assertTrue(executionAdvance >= 0);\n        assertFalse(body.contains("generation++;"));',
)
replace_once(
    pause_test,
    "        assertTrue(generationAdvance < observerDetach);",
    "        assertTrue(executionAdvance < observerDetach);",
)
replace_once(
    pause_test,
    '                "if (!isCurrentExecution(active, activeGeneration, activeRunId) || isRateLimited()) return;", callback);',
    '                "if (!isCurrentExecution(active, activeGeneration, activeExecutionEpoch, activeRunId) || isRateLimited()) return;", callback);',
)
replace_once(
    pause_test,
    '        assertTrue(body.contains("boolean preserved = webView != null;"));\n        assertTrue(body.contains("if (preserved)"));',
    '        assertTrue(body.contains("boolean preserved = webView != null;"));\n'
    '        assertTrue(body.contains("resumeObserverGate = preserved && !rateLimited;"));\n'
    '        assertTrue(body.contains("if (preserved)"));',
)

battery_test = "app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunBatteryEfficiencyTest.java"
replace_once(
    battery_test,
    "        String detach = SelfRunDomObserver.detach();",
    '        String detach = SelfRunDomObserver.detach("test-lease");',
)
replace_once(
    battery_test,
    '        assertTrue(detach.contains("observer?.disconnect()"));',
    '        assertTrue(detach.contains("state.lease !== expectedLease"));\n'
    '        assertTrue(detach.contains("state.active = false"));\n'
    '        assertTrue(detach.contains("takeRecords"));\n'
    '        assertTrue(detach.contains("observer?.disconnect()"));',
)
replace_once(
    battery_test,
    '        assertTrue(text.contains("domEvaluationPending"));',
    '        assertTrue(text.contains("domEvaluationPending"));\n'
    '        assertTrue(text.contains("activeExecutionEpoch != executionEpoch"));\n'
    '        assertTrue(text.contains("resumeObserverGate"));',
)

new_test = '''package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunObserverPauseLifecycleTest {
    @Test
    public void pageObserverDropsStaleMutationDebounceAndDetachWork() {
        String install = SelfRunDomObserver.install("token", "lease-new", "SR-1", 7, 9);
        String detach = SelfRunDomObserver.detach("lease-old");
        assertTrue(install.contains("lease, runId, generation, epoch, active:true"));
        assertTrue(install.contains("const current = () => window[key] === state && state.active"));
        assertTrue(install.contains("if (!current()) return;"));
        assertTrue(install.contains("previousGeneration === generation && previousEpoch >= epoch"));
        assertTrue(detach.contains("if (state.lease !== expectedLease) return 'STALE'"));
        assertTrue(detach.contains("state.active = false"));
        assertTrue(detach.contains("takeRecords"));
        assertFalse(install.contains("setInterval"));
    }

    @Test
    public void preservedPauseUsesExecutionEpochWithoutChangingWebViewGeneration() throws Exception {
        String text = source();
        int pause = text.indexOf("private void enterPreservedPause");
        int wake = text.indexOf("private void updateWakeLockForState", pause);
        String body = text.substring(pause, wake);
        assertTrue(body.contains("invalidateExecutionEpoch();"));
        assertTrue(body.contains("resumeObserverGate = false;"));
        assertTrue(body.contains("detachDomObserver(cause);"));
        assertFalse(body.contains("generation++;"));
        assertFalse(body.contains("cleanupWebView()"));
        assertFalse(body.contains("loadUrl("));
        assertFalse(body.contains("reload("));
        assertFalse(body.contains("pauseTimers"));
    }

    @Test
    public void normalResumeWaitsForCurrentObserverReadyBeforeDomWork() throws Exception {
        String text = source();
        assertTrue(text.contains("resumeObserverGate = preserved && !rateLimited;"));
        assertTrue(text.contains("if (!canRun() || resumeObserverGate || webView == null) return;"));
        assertTrue(text.contains("|| resumeObserverGate || isRateLimited()) return;"));
        assertTrue(text.contains("openResumeObserverGate(active, activeGeneration, activeExecutionEpoch"));
        assertTrue(text.contains("requestDomEvaluation(0L, \"resume_observer_ready\")"));
        assertTrue(text.contains("SelfRunDomObserver.detach(detachedLease)"));
        assertTrue(text.contains("SelfRunDomObserver.install(token, lease, activeRunId, activeGeneration, activeEpoch)"));
        assertTrue(text.contains("activeExecutionEpoch != executionEpoch"));
        assertFalse(text.contains("pauseTimers()"));
        assertFalse(text.contains("webView.onPause()"));
        assertFalse(text.contains("webView.onResume()"));
    }

    @Test
    public void lowFrequencyWatchdogRemainsRecoveryPath() throws Exception {
        String text = source();
        assertTrue(text.contains("DOM_WATCHDOG_MS = 15_000L"));
        assertTrue(text.contains("scheduleWatchdog();"));
        assertTrue(text.contains("watchdog_observer_recovery"));
        assertFalse(text.contains("setInterval("));
    }

    private static String source() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
'''
Path("app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunObserverPauseLifecycleTest.java").write_text(new_test)
