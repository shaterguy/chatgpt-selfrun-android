package com.shaterguy.chatgptselfrun;

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
        assertTrue(text.contains("updateWakeLockForState(\"resume_observer_ready\")"));
        assertTrue(text.contains("requestDomEvaluation(0L, \"resume_observer_ready\")"));
        assertTrue(text.contains("if (resumeObserverGate)"));
        assertTrue(text.contains("WakeLockController.State.PAUSED"));
        assertTrue(text.contains("SelfRunDomObserver.detach(detachedLease)"));
        assertTrue(text.contains("SelfRunDomObserver.install(token, lease, activeRunId, activeGeneration, activeEpoch)"));
        assertTrue(text.contains("activeExecutionEpoch != executionEpoch"));
        assertFalse(text.contains("pauseTimers()"));
        assertFalse(text.contains("webView.onPause()"));
        assertFalse(text.contains("webView.onResume()"));
    }

    @Test
    public void navigationDuringPreservedResumeKeepsGateUntilCurrentObserverReady() throws Exception {
        String text = source();
        int pageStart = text.indexOf("public void onPageStarted");
        int pageFinish = text.indexOf("public void onPageFinished", pageStart);
        assertTrue(pageStart >= 0 && pageFinish > pageStart);
        String pageStartBody = text.substring(pageStart, pageFinish);

        assertTrue(pageStartBody.contains("invalidateDomObserverForNavigation();"));
        assertTrue(pageStartBody.contains("generation++;"));
        assertTrue(pageStartBody.contains("invalidateExecutionEpoch();"));
        assertFalse(pageStartBody.contains("resumeObserverGate = false;"));

        int openGate = text.indexOf("private boolean openResumeObserverGate");
        int nextMethod = text.indexOf("private static Uri chatGptOrigin", openGate);
        assertTrue(openGate >= 0 && nextMethod > openGate);
        String gateBody = text.substring(openGate, nextMethod);
        int clearGate = gateBody.indexOf("resumeObserverGate = false;");
        int wake = gateBody.indexOf("updateWakeLockForState(\"resume_observer_ready\")");
        int evaluate = gateBody.indexOf("requestDomEvaluation(0L, \"resume_observer_ready\")");
        assertTrue(clearGate >= 0);
        assertTrue(wake > clearGate);
        assertTrue(evaluate > wake);
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
