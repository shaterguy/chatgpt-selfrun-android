package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunBatteryEfficiencyTest {
    @Test
    public void domObserverDebouncesSuppressesEquivalentStateAndAddsQuietCompletionProbe() {
        String install = SelfRunDomObserver.install("test-token", "test-lease");
        assertTrue(install.contains("new MutationObserver"));
        assertTrue(install.contains("fingerprint === state.lastFingerprint"));
        assertTrue(install.contains("state.suppressed++"));
        assertTrue(install.contains("scheduleQuietProbe"));
        assertTrue(install.contains("dispatch('quiet', true)"));
        assertTrue(install.contains("attributeFilter:['class','aria-busy'"));
        assertTrue(install.contains("characterData:true"));
        assertFalse(install.contains("[class*=\"spinner\""));
        assertFalse(install.contains("[class*=\"loading\""));
        assertFalse(install.contains("setInterval"));
    }

    @Test
    public void watchdogHealthSelfProbesObserverWithoutFullAssistantDomScan() {
        String install = SelfRunDomObserver.install("test-token", "test-lease");
        String health = SelfRunDomObserver.health("test-lease");
        assertTrue(install.contains("probe:null, probeSent:0, probeAck:0"));
        assertTrue(install.contains("mutation.target === state.probe"));
        assertTrue(health.contains("state.probe.setAttribute('data-selfrun-probe', String(nextProbe))"));
        assertTrue(health.contains("status:'STALLED'"));
        assertTrue(health.contains("lastAssistantActivityAt"));
        assertFalse(health.contains("querySelectorAll"));
        assertFalse(health.contains("snapshot()"));
    }

    @Test
    public void waitAssistantUsesProductionRuntimeForSemanticAndTimeoutScheduling() throws Exception {
        String service = source("SelfRunService.java");
        String runtime = source("AssistantWaitRuntime.java");
        assertTrue(service.contains("DOM_WATCHDOG_MS = 15_000L"));
        assertTrue(service.contains("AssistantWaitRuntime assistantWaitRuntime"));
        assertTrue(runtime.contains("coordinator.semanticProbeDelay"));
        assertTrue(runtime.contains("AssistantWaitCoordinator.Source.WATCHDOG_PROBE"));
        assertTrue(runtime.contains("coordinator.timeoutDelay"));
        assertTrue(runtime.contains("AssistantWaitCoordinator.Source.TIMEOUT_PROBE"));
        assertFalse(runtime.contains("lastObserverState"));
        assertFalse(runtime.contains("SelfRunDomObserver.health"));
    }

    @Test
    public void assistantTimeoutRuntimeProbesBeforeRecoveryAndUsesInjectedClockScheduler() throws Exception {
        String runtime = source("AssistantWaitRuntime.java");
        assertTrue(runtime.contains("interface Clock"));
        assertTrue(runtime.contains("interface Scheduler"));
        assertTrue(runtime.contains("listener.timeoutFired"));
        assertTrue(runtime.contains("listener.requestProbe(AssistantWaitCoordinator.Source.TIMEOUT_PROBE"));
        assertFalse(runtime.contains("recoverStalledPhase("));
    }

    @Test
    public void observerEventsAreLeaseGuardedDeduplicatedAndCoalesced() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("activeEpoch != observerEpoch"));
        assertTrue(service.contains("!activeRunId.equals(store.runId())"));
        assertTrue(service.contains("!lease.equals(observerLease)"));
        assertTrue(service.contains("nextState.equals(lastObserverState)"));
        assertTrue(service.contains("DOM_EVALUATION_COALESCED"));
        assertTrue(service.contains("DomEvaluationLifecycle evaluationLifecycle"));
        assertTrue(service.contains("releaseDomEvaluation(activeEvaluationEpoch)"));
    }

    @Test
    public void preservedPauseStopsAssistantMonitoringObserverAndWakeLockAutomation() throws Exception {
        String service = source("SelfRunService.java");
        String pause = between(service, "private void enterPreservedPause", "private void updateWakeLockForState");
        assertTrue(pause.contains("handler.removeCallbacksAndMessages(null)"));
        assertTrue(pause.contains("cancelAssistantWaitMonitoring"));
        assertTrue(pause.contains("detachDomObserver(cause)"));
        assertTrue(pause.contains("WakeLockController.State.PAUSED"));
        assertTrue(pause.contains("invalidateDomEvaluation()"));
        assertFalse(pause.contains("pauseTimers"));
    }


    @Test
    public void originScopedWebMessageChannelAndLowFrequencyRecoveryWatchdogRemainGuarded() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("DOM_WATCHDOG_MS = 15_000L"));
        assertTrue(service.contains("watchdogRunnable = this::runDomWatchdog"));
        assertTrue(service.contains("createWebMessageChannel()"));
        assertTrue(service.contains("postWebMessage(new WebMessage(token"));
        assertTrue(service.contains("SelfRunDomObserver.health(activeLease)"));
        assertTrue(service.contains("DOM_OBSERVER_HEALTH_EVALUATE"));
        assertTrue(service.contains("watchdog_missed_event"));
        assertTrue(service.contains("return Uri.parse(\"https://\" + host);"));
        assertFalse(service.contains("addJavascriptInterface"));
        assertFalse(service.contains("WorkManager"));
        assertFalse(service.contains("AlarmManager"));
        assertFalse(service.contains("setRendererPriorityPolicy"));

        String schedule = between(service, "private void scheduleWatchdog()", "private void runDomWatchdog()");
        assertTrue(schedule.contains("handler.postDelayed(watchdogRunnable, DOM_WATCHDOG_MS)"));
        assertFalse(schedule.contains("scheduleStep("));
        assertFalse(schedule.contains("stepRunnable"));
        assertFalse(schedule.contains("updateWakeLockForState"));
    }

    @Test
    public void rateLimitRendererAndNetworkRecoveryKeepExplicitPowerAndStaleGuards() throws Exception {
        String service = source("SelfRunService.java");
        String rate = between(service, "private void rateLimit(String reason)", "private void restoreCanonical");
        assertTrue(rate.contains("recoveryInProgress = false"));
        assertTrue(rate.contains("updateWakeLockForState(\"rate_limit_wait\")"));
        assertTrue(rate.contains("scheduleRateLimitExpiry()"));
        assertTrue(rate.contains("rateLimitTimerEpoch"));
        assertTrue(rate.contains("isCurrentRateLimitTimer(expectedRunId, expectedTimerEpoch, expectedDeadline)"));
        assertTrue(rate.contains("expectedRunId.equals(store.runId())"));
        assertTrue(rate.contains("expectedTimerEpoch == rateLimitTimerEpoch"));
        assertTrue(rate.contains("expectedDeadline == rateLimitedUntilElapsed"));
        assertFalse(rate.contains("isCurrentExecution(expectedWebView, expectedGeneration, expectedRunId)"));
        assertTrue(rate.contains("beginRecovery(\"rate_limit_expired\")"));
        assertTrue(service.contains("beginRecovery(\"renderer_gone\")"));
        assertTrue(service.contains("beginRecovery(\"network_error\")"));
        assertTrue(service.contains("finishRecovery(\"page_finished\")"));
        assertTrue(service.contains("postRecovery("));
    }

    @Test
    public void persistenceNoopAndHistoryWriteGuardsRemainPartOfRegressionContract() throws Exception {
        String store = source("SelfRunStore.java");
        String history = source("SelfRunHistoryStore.java");
        assertTrue(store.contains("markNoop()"));
        assertTrue(store.contains("setPhaseAndStatus"));
        assertTrue(store.contains("enterPausedState"));
        assertTrue(store.contains("resumeState"));
        assertTrue(store.contains("stopByUser"));
        String phaseClock = between(store, "void restartPhaseClock()", "void setStatus");
        assertFalse(phaseClock.contains("syncHistory"));
        assertTrue(history.contains("sameSnapshot(previousSnapshot, nextSnapshot)"));
        assertTrue(history.contains("SYNC_DEBOUNCE_MS = 250L"));
        assertTrue(history.contains("PENDING_SNAPSHOTS"));
        assertTrue(history.contains("staleSnapshotsSkipped"));
        assertTrue(history.contains("physicalWrites"));
        assertFalse(history.contains(".commit()"));
        assertTrue(history.contains(".apply()"));
    }

    @Test
    public void wakeLockOwnershipRemainsCentralizedBySemanticState() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("WakeLockController wakeLockController"));
        assertTrue(service.contains("wakeLockStateFor("));
        assertTrue(service.contains("WakeLockController.State.AUTOMATION"));
        assertTrue(service.contains("WakeLockController.State.RECOVERY"));
        assertTrue(service.contains("WakeLockController.State.RATE_LIMIT"));
        assertFalse(service.contains("PowerManager.WakeLock"));
        assertFalse(service.contains("wakeLock.acquire"));
        assertFalse(service.contains("wakeLock.release"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from + 1);
        assertTrue(from >= 0 && to > from);
        return text.substring(from, to);
    }
}
