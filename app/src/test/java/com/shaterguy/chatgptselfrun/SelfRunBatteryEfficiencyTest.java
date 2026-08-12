package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunBatteryEfficiencyTest {
    @Test
    public void domObserverDebouncesAndSuppressesEquivalentPageState() {
        String install = SelfRunDomObserver.install("test-token", "test-lease");
        String health = SelfRunDomObserver.health("test-lease");
        String detach = SelfRunDomObserver.detach();

        assertTrue(install.contains("new MutationObserver"));
        assertTrue(install.contains("setTimeout"));
        assertTrue(install.contains("lastFingerprint"));
        assertTrue(install.contains("fingerprint === state.lastFingerprint"));
        assertTrue(install.contains("state.suppressed++"));
        assertTrue(install.contains("postMessage('state|' + fingerprint)"));
        assertTrue(install.contains("assistantIdentity"));
        assertTrue(install.contains("streaming"));
        assertTrue(install.contains("completedDigest"));
        assertTrue(install.contains("controlDigest"));
        assertTrue(install.contains("sendDisabled"));
        assertFalse(install.contains("postMessage('changed')"));
        assertFalse(install.contains("setInterval"));
        assertFalse(install.contains("pauseTimers"));

        assertTrue(health.contains("status:'ALIVE'"));
        assertTrue(health.contains("status:'STALLED'"));
        assertTrue(health.contains("rootConnected"));
        assertTrue(health.contains("fingerprint"));
        assertTrue(health.contains("suppressed"));
        assertTrue(health.contains("probeSent"));
        assertTrue(health.contains("probeAck"));
        assertFalse(health.contains("querySelectorAll"));
        assertFalse(health.contains("snapshot()"));

        assertTrue(detach.contains("observer?.disconnect()"));
        assertTrue(detach.contains("clearTimeout"));
        assertTrue(detach.contains("removeEventListener"));
        assertTrue(detach.contains("port?.close()"));
    }

    @Test
    public void watchdogHealthSelfProbesTheObserverWithoutAFullDomScan() {
        String install = SelfRunDomObserver.install("test-token", "test-lease");
        String health = SelfRunDomObserver.health("test-lease");

        assertTrue(install.contains("probe:null, probeSent:0, probeAck:0"));
        assertTrue(install.contains("mutation.target === state.probe"));
        assertTrue(install.contains("state.probeAck = ack"));
        assertTrue(install.contains("state.observer.observe(state.probe"));
        assertTrue(health.contains("previousProbe > 0 && previousAck < previousProbe"));
        assertTrue(health.contains("state.probe.setAttribute('data-selfrun-probe', String(nextProbe))"));
        assertTrue(health.contains("status:'STALLED'"));
        assertFalse(health.contains("querySelector"));
        assertFalse(health.contains("querySelectorAll"));
    }

    @Test
    public void completedAssistantTextFinalizationIsObservedWithoutStreamingTokenChurn() {
        String install = SelfRunDomObserver.install("test-token", "test-lease");

        assertTrue(install.contains("characterData:true"));
        assertTrue(install.contains("state.lastStreaming = streaming"));
        assertTrue(install.contains("state.lastAssistantNode = assistant"));
        assertTrue(install.contains("state.lastAssistantNode?.contains(mutation.target)"));
        assertTrue(install.contains("&& !state.lastStreaming && !state.timer"));
        int firstCompletionGuard = install.indexOf("state.lastAssistantNode?.contains(mutation.target)");
        int secondCompletionGuard = install.indexOf("state.lastAssistantNode?.contains(mutation.target)", firstCompletionGuard + 1);
        assertTrue(firstCompletionGuard >= 0 && secondCompletionGuard > firstCompletionGuard);
        assertTrue(install.contains("const completedText = String(assistant.innerText || assistant.textContent || '')"));
        assertTrue(install.contains("completedText.match(/\\[SELF_RUN_"));
        assertTrue(install.contains("completedDigest = hash(completedText)"));
        assertTrue(install.contains("controlDigest = controls.length ? hash(controls[controls.length - 1]) : ''"));
    }

    @Test
    public void serviceUsesOriginScopedMessageChannelAndRecoveryOnlyWatchdog() throws Exception {
        String text = source("SelfRunService.java");
        assertTrue(text.contains("DOM_WATCHDOG_MS = 15_000L"));
        assertTrue(text.contains("watchdogRunnable = this::runDomWatchdog"));
        assertTrue(text.contains("createWebMessageChannel()"));
        assertTrue(text.contains("postWebMessage(new WebMessage(token"));
        assertTrue(text.contains("SelfRunDomObserver.health(activeLease)"));
        assertTrue(text.contains("DOM_OBSERVER_HEALTH_EVALUATE"));
        assertTrue(text.contains("watchdog_missed_event"));
        assertTrue(text.contains("return Uri.parse(\"https://\" + host);"));
        assertFalse(text.contains("addJavascriptInterface"));
        assertFalse(text.contains("WorkManager"));
        assertFalse(text.contains("AlarmManager"));
        assertFalse(text.contains("setRendererPriorityPolicy"));

        int watchdog = text.indexOf("private void scheduleWatchdog()");
        int runWatchdog = text.indexOf("private void runDomWatchdog()", watchdog);
        assertTrue(watchdog >= 0 && runWatchdog > watchdog);
        String scheduleBody = text.substring(watchdog, runWatchdog);
        assertTrue(scheduleBody.contains("handler.postDelayed(watchdogRunnable, DOM_WATCHDOG_MS)"));
        assertFalse(scheduleBody.contains("scheduleStep("));
        assertFalse(scheduleBody.contains("stepRunnable"));
        assertFalse(scheduleBody.contains("updateWakeLockForState"));
    }

    @Test
    public void normalWaitsArmObserverAndWatchdogWithoutPollingThePhase() throws Exception {
        String text = source("SelfRunService.java");
        int method = text.indexOf("private void uiWait(String detail)");
        int nextMethod = text.indexOf("private void submittedWait", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);
        assertTrue(body.contains("ensureDomObserver();"));
        assertTrue(body.contains("scheduleWatchdog();"));
        assertFalse(body.contains("scheduleStep("));
        assertFalse(body.contains("requestDomEvaluation("));
        assertFalse(body.contains("updateWakeLockForState"));
    }

    @Test
    public void observerEventsAreLeaseGuardedDeduplicatedAndCoalesced() throws Exception {
        String text = source("SelfRunService.java");
        assertTrue(text.contains("active != webView || activeGeneration != generation || activeEpoch != observerEpoch"));
        assertTrue(text.contains("!activeRunId.equals(store.runId())"));
        assertTrue(text.contains("!lease.equals(observerLease)"));
        assertTrue(text.contains("nextState.equals(lastObserverState)"));
        assertTrue(text.contains("observerDuplicateEventCount"));
        assertTrue(text.contains("domEvaluationPending"));
        assertTrue(text.contains("drainPendingDomEvaluation()"));
        assertTrue(text.contains("requestDomEvaluation(0L, \"observer_state\")"));
        assertTrue(text.contains("requestDomEvaluation(0L, \"watchdog_missed_event\")"));

        int observer = text.indexOf("private void ensureDomObserver()");
        int next = text.indexOf("private static Uri chatGptOrigin", observer);
        String observerBody = text.substring(observer, next);
        assertFalse(observerBody.contains("updateWakeLockForState"));
        assertFalse(observerBody.contains("setWakeLockState"));
    }

    @Test
    public void preservedPauseStopsObserverAndWatchdogAndResumeAcquiresBeforeReattach() throws Exception {
        String text = source("SelfRunService.java");
        int pause = text.indexOf("private void enterPreservedPause");
        int wake = text.indexOf("private void updateWakeLockForState", pause);
        assertTrue(pause >= 0 && wake > pause);
        String pauseBody = text.substring(pause, wake);
        assertTrue(pauseBody.contains("handler.removeCallbacksAndMessages(null)"));
        assertTrue(pauseBody.contains("detachDomObserver(cause)"));
        assertTrue(pauseBody.contains("setWakeLockState(WakeLockController.State.PAUSED"));
        assertFalse(pauseBody.contains("releaseWakeLock"));
        assertFalse(pauseBody.contains("pauseTimers"));

        int resume = text.indexOf("private void resumeFromUi()");
        assertTrue(resume >= 0 && pause > resume);
        String resumeBody = text.substring(resume, pause);
        int wakePrepare = resumeBody.indexOf("updateWakeLockForState(\"resume_prepare\")");
        int preservedBlock = resumeBody.indexOf("if (preserved)");
        int observerAttach = resumeBody.indexOf("ensureDomObserver();", preservedBlock);
        assertTrue(wakePrepare >= 0);
        assertTrue(preservedBlock > wakePrepare);
        assertTrue(observerAttach > wakePrepare);
        int elseBlock = resumeBody.indexOf("} else {", preservedBlock);
        String preservedBody = resumeBody.substring(preservedBlock, elseBlock);
        assertFalse(preservedBody.contains("loadUrl("));
        assertFalse(preservedBody.contains("cleanupWebView()"));
        assertFalse(preservedBody.contains("scheduleStep("));
    }

    @Test
    public void wakeLockOwnershipIsCentralizedAndInstrumentedBySemanticState() throws Exception {
        String service = source("SelfRunService.java");
        String controller = source("WakeLockController.java");

        assertTrue(service.contains("WakeLockController wakeLockController"));
        assertTrue(service.contains("wakeLockStateFor("));
        assertTrue(service.contains("WakeLockController.State.AUTOMATION"));
        assertTrue(service.contains("WakeLockController.State.RECOVERY"));
        assertTrue(service.contains("WakeLockController.State.RATE_LIMIT"));
        assertTrue(service.contains("WAKELOCK_STATE"));
        assertTrue(service.contains("WAKELOCK_EFFICIENCY"));
        assertTrue(service.contains("automationHeldMs="));
        assertTrue(service.contains("recoveryHeldMs="));
        assertFalse(service.contains("PowerManager.WakeLock"));
        assertFalse(service.contains("wakeLock.acquire"));
        assertFalse(service.contains("wakeLock.release"));
        assertFalse(service.contains("pauseTimers"));
        assertFalse(service.contains("webView.onPause()"));
        assertFalse(service.contains("webView.onResume()"));

        assertTrue(controller.contains("PowerManager.PARTIAL_WAKE_LOCK"));
        assertTrue(controller.contains("wakeLock.setReferenceCounted(false)"));
        assertTrue(controller.contains("lock.acquire()"));
        assertTrue(controller.contains("lock.release()"));
        assertTrue(controller.contains("SystemClock::elapsedRealtime"));
    }

    @Test
    public void rateLimitAndRecoveryUseExplicitPowerStatesAndStaleGuards() throws Exception {
        String text = source("SelfRunService.java");
        int rate = text.indexOf("private void rateLimit(String reason)");
        int restore = text.indexOf("private void restoreCanonical", rate);
        String rateBody = text.substring(rate, restore);
        assertTrue(rateBody.contains("recoveryInProgress = false"));
        assertTrue(rateBody.contains("updateWakeLockForState(\"rate_limit_wait\")"));
        assertTrue(rateBody.contains("scheduleRateLimitExpiry()"));
        assertTrue(rateBody.contains("rateLimitTimerEpoch"));
        assertTrue(rateBody.contains("isCurrentRateLimitTimer(expectedRunId, expectedTimerEpoch, expectedDeadline)"));
        assertTrue(rateBody.contains("expectedRunId.equals(store.runId())"));
        assertTrue(rateBody.contains("expectedTimerEpoch == rateLimitTimerEpoch"));
        assertTrue(rateBody.contains("expectedDeadline == rateLimitedUntilElapsed"));
        assertFalse(rateBody.contains("isCurrentExecution(expectedWebView, expectedGeneration, expectedRunId)"));
        assertTrue(rateBody.contains("beginRecovery(\"rate_limit_expired\")"));

        assertTrue(text.contains("beginRecovery(\"renderer_gone\")"));
        assertTrue(text.contains("beginRecovery(\"network_error\")"));
        assertTrue(text.contains("finishRecovery(\"page_finished\")"));
        assertTrue(text.contains("postRecovery("));
    }

    @Test
    public void repeatedPersistenceWritesAreGuardedAndPhaseClockResetDoesNotSyncHistory() throws Exception {
        String store = source("SelfRunStore.java");
        String history = source("SelfRunHistoryStore.java");
        assertTrue(store.contains("if (next.equals(prefs.getString(key, \"\"))) return;"));
        assertTrue(store.contains("if (value == prefs.getBoolean(key, false)) return;"));
        int clock = store.indexOf("void restartPhaseClock()");
        int status = store.indexOf("void setStatus", clock);
        assertTrue(clock >= 0 && status > clock);
        assertFalse(store.substring(clock, status).contains("syncHistory"));
        assertTrue(history.contains("if (sameSnapshot(previousSnapshot, nextSnapshot)) return true;"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
