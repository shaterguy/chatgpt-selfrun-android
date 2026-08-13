package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunPauseResumeTest {
    @Test
    public void protocolPauseSignalsPreserveWebView() {
        assertTrue(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.USER_ACTION));
        assertTrue(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.PAUSE));
        assertFalse(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.NEXT));
        assertFalse(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.DONE));
    }

    @Test
    public void manualPausePathUsesSamePreservedWebViewPolicy() throws Exception {
        String text = source();
        String body = between(text, "private void pauseFromUi()", "private void resumeFromUi()");
        assertTrue(body.contains("enterPreservedPause(\"UI_PAUSE\""));
        assertFalse(body.contains("cleanupWebView()"));
    }

    @Test
    public void preservedPauseStopsAutomationTimersAndDoesNotDestroyWebView() throws Exception {
        String text = source();
        String body = between(text, "private void enterPreservedPause", "private void updateWakeLockForState");
        assertTrue(body.contains("handler.removeCallbacksAndMessages(null)"));
        assertTrue(body.contains("cancelAssistantWaitMonitoring"));
        assertTrue(body.contains("invalidateExecutionEpoch()"));
        assertTrue(body.contains("invalidateDomEvaluation()"));
        assertTrue(body.contains("detachDomObserver(cause)"));
        assertFalse(body.contains("cleanupWebView()"));
        assertFalse(body.contains("webView.onPause()"));
        assertFalse(body.contains("pauseTimers"));
    }

    @Test
    public void sameWebViewResumeDoesNotReloadOrSendContinuation() throws Exception {
        String text = source();
        String body = between(text, "private void resumeFromUi()", "private void enterPreservedPause");
        int preserved = body.indexOf("if (preserved)");
        int reconnectElse = body.indexOf("} else {", preserved);
        assertTrue(preserved >= 0 && reconnectElse > preserved);
        String preservedBody = body.substring(preserved, reconnectElse);
        assertTrue(preservedBody.contains("ensureDomObserver()"));
        assertFalse(preservedBody.contains("loadUrl("));
        assertFalse(preservedBody.contains("cleanupWebView()"));
        assertFalse(preservedBody.contains("scheduleStep("));
        assertFalse(preservedBody.contains("SelfRunDom.sendTurn"));
    }

    @Test
    public void resumeObserverGateCoversRestoredWaitAssistantUntilCurrentObserverReady() throws Exception {
        String text = source();
        String body = between(text, "private void resumeFromUi()", "private void enterPreservedPause");
        assertTrue(body.contains("boolean resumedAssistantWait = inAssistantWait();"));
        assertTrue(body.contains("resumeObserverGate = !rateLimited && (preserved || resumedAssistantWait);"));

        String request = between(text, "private void requestDomEvaluation", "private static int evaluationPriority");
        assertTrue(request.contains("resumeObserverGate"));
    }

    @Test
    public void observerReadyOpensGateBeforeExactlyOneResumeSemanticProbe() throws Exception {
        String text = source();
        String gate = between(text, "private boolean openResumeObserverGate", "private static Uri chatGptOrigin");
        int clear = gate.indexOf("resumeObserverGate = false;");
        int wake = gate.indexOf("updateWakeLockForState(\"resume_observer_ready\")");
        int probe = gate.indexOf("assistantWaitRuntime.requestImmediate(AssistantWaitCoordinator.Source.RESUME_PROBE)");
        assertTrue(clear >= 0 && wake > clear && probe > wake);
        assertTrue(count(gate, "assistantWaitRuntime.requestImmediate(AssistantWaitCoordinator.Source.RESUME_PROBE)") == 1);
    }

    @Test
    public void staleEvaluateGuardReleasesOwnedEvaluationBeforePendingDrain() throws Exception {
        String text = source();
        String body = between(text, "private void evaluate(String phase, String script)",
                "private static boolean isWaitingStatus");
        assertTrue(body.contains("recordStaleCallback(\"evaluate\", \"execution_changed\")"));
        assertTrue(body.contains("recordStaleCallback(\"evaluate\", \"assistant_epoch_changed\")"));
        assertTrue(count(body, "releaseDomEvaluation(activeEvaluationEpoch);") >= 3);
        assertFalse(body.contains("evaluationInFlight = false;"));
        assertTrue(text.contains("if (evaluationLifecycle.release(expectedEvaluationEpoch)) drainPendingDomEvaluation();"));
    }

    @Test
    public void rateLimitInvalidatesEvaluationBeforePowerReleaseAndExpiryScheduling() throws Exception {
        String text = source();
        String body = between(text, "private void rateLimit(String reason)", "private void scheduleRateLimitExpiry()");
        int deadline = body.indexOf("rateLimitedUntilElapsed = now + delay;");
        int generationAdvance = body.indexOf("generation++;", deadline);
        int invalidate = body.indexOf("invalidateDomEvaluation();", generationAdvance);
        int clearPending = body.indexOf("domEvaluationPending = false;", invalidate);
        int powerRelease = body.indexOf("updateWakeLockForState(\"rate_limit_wait\")", clearPending);
        int expiry = body.indexOf("scheduleRateLimitExpiry();", powerRelease);
        assertTrue(deadline >= 0);
        assertTrue(generationAdvance > deadline);
        assertTrue(invalidate > generationAdvance);
        assertTrue(clearPending > invalidate);
        assertTrue(powerRelease > clearPending);
        assertTrue(expiry > powerRelease);
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from + 1);
        assertTrue(from >= 0 && to > from);
        return text.substring(from, to);
    }

    private static String source() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
