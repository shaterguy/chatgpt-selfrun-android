package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunObserverPauseLifecycleTest {
    @Test
    public void pageObserverDropsStaleMutationQuietProbeAndDetachWork() {
        String install = SelfRunDomObserver.install("token", "lease-new", "SR-1", 7, 9);
        String detach = SelfRunDomObserver.detach("lease-old");
        assertTrue(install.contains("lease, runId, generation, epoch, active:true"));
        assertTrue(install.contains("const current = () => window[key] === state && state.active"));
        assertTrue(install.contains("previousGeneration === generation && previousEpoch >= epoch"));
        assertTrue(install.contains("quietTimer"));
        assertTrue(detach.contains("if (state.lease !== expectedLease) return 'STALE'"));
        assertTrue(detach.contains("state.active = false"));
        assertTrue(detach.contains("takeRecords"));
        assertTrue(detach.contains("clearTimeout(state.quietTimer)"));
        assertFalse(install.contains("setInterval"));
    }

    @Test
    public void preservedPauseInvalidatesEvaluationAndAssistantTimersWithoutDestroyingWebView() throws Exception {
        String text = source();
        String body = between(text, "private void enterPreservedPause", "private void updateWakeLockForState");
        assertTrue(body.contains("cancelAssistantWaitMonitoring"));
        assertTrue(body.contains("invalidateExecutionEpoch();"));
        assertTrue(body.contains("invalidateDomEvaluation();"));
        assertTrue(body.contains("detachDomObserver(cause);"));
        assertTrue(body.contains("WakeLockController.State.PAUSED"));
        assertFalse(body.contains("generation++;"));
        assertFalse(body.contains("cleanupWebView()"));
        assertFalse(body.contains("loadUrl("));
    }

    @Test
    public void resumeWaitsForCurrentObserverAndUsesResumeSemanticProbe() throws Exception {
        String text = source();
        String resume = between(text, "private void resumeFromUi()", "private void enterPreservedPause");
        String gate = between(text, "private boolean openResumeObserverGate", "private static Uri chatGptOrigin");
        assertTrue(resume.contains("boolean resumedAssistantWait = inAssistantWait();"));
        assertTrue(resume.contains("resumeObserverGate = !rateLimited && (preserved || resumedAssistantWait);"));
        assertTrue(resume.contains("ensureDomObserver();"));
        assertTrue(gate.contains("startAssistantWaitMonitoring(\"resume_ready\")"));
        assertTrue(gate.contains("assistantWaitRuntime.requestImmediate(AssistantWaitCoordinator.Source.RESUME_PROBE)"));
        assertTrue(gate.contains("updateWakeLockForState(\"resume_observer_ready\")"));
        assertFalse(resume.contains("SelfRunDom.sendTurn"));
    }

    @Test
    public void navigationInvalidatesExecutionEvaluationAndOldObserverBeforeNewHydration() throws Exception {
        String text = source();
        String body = between(text, "public void onPageStarted", "public void onPageFinished");
        assertTrue(body.contains("invalidateDomObserverForNavigation();"));
        assertTrue(body.contains("generation++;"));
        assertTrue(body.contains("invalidateExecutionEpoch();"));
        assertTrue(body.contains("invalidateDomEvaluation();"));
        assertFalse(body.contains("SelfRunDom.sendTurn"));
    }

    @Test
    public void livenessWatchdogRemainsLowFrequencyAndSeparateFromSemanticRuntime() throws Exception {
        String text = source();
        String runtime = runtimeSource();
        assertTrue(text.contains("DOM_WATCHDOG_MS = 15_000L"));
        assertTrue(coordinatorSource().contains("SEMANTIC_PROBE_INTERVAL_MS = 45_000L"));
        assertTrue(runtime.contains("AssistantWaitCoordinator.Source.WATCHDOG_PROBE"));
        assertFalse(runtime.contains("SelfRunDomObserver.health"));
        assertFalse(text.contains("setInterval("));
    }

    private static String runtimeSource() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/AssistantWaitRuntime.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static String coordinatorSource() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/AssistantWaitCoordinator.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static String source() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from + 1);
        assertTrue(from >= 0 && to > from);
        return text.substring(from, to);
    }
}
