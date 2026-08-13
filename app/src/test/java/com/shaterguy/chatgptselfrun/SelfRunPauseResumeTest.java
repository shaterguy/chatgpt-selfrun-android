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
        int pause = text.indexOf("private void pauseFromUi()");
        int resume = text.indexOf("private void resumeFromUi()", pause);
        assertTrue(pause >= 0 && resume > pause);
        String body = text.substring(pause, resume);
        assertTrue(body.contains("enterPreservedPause(\"UI_PAUSE\""));
        assertFalse(body.contains("cleanupWebView()"));
    }

    @Test
    public void preservedPauseStopsAutomationButDoesNotDestroyOrPauseWebView() throws Exception {
        String text = source();
        int method = text.indexOf("private void enterPreservedPause");
        int nextMethod = text.indexOf("private void updateWakeLockForState", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);
        assertTrue(body.contains("handler.removeCallbacksAndMessages(null)"));
        assertTrue(body.contains("invalidateExecutionEpoch()"));
        assertTrue(body.contains("detachDomObserver(cause)"));
        assertFalse(body.contains("cleanupWebView()"));
        assertFalse(body.contains("webView.onPause()"));
        assertFalse(body.contains("pauseTimers"));
    }

    @Test
    public void sameWebViewResumeDoesNotReloadCanonicalConversation() throws Exception {
        String text = source();
        int method = text.indexOf("private void resumeFromUi()");
        int nextMethod = text.indexOf("private void enterPreservedPause", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);
        int preserved = body.indexOf("if (preserved)");
        int reconnectElse = body.indexOf("} else {", preserved);
        assertTrue(preserved >= 0 && reconnectElse > preserved);
        String preservedBody = body.substring(preserved, reconnectElse);
        assertTrue(preservedBody.contains("ensureDomObserver()"));
        assertFalse(preservedBody.contains("loadUrl("));
        assertFalse(preservedBody.contains("cleanupWebView()"));
        assertFalse(preservedBody.contains("scheduleStep("));
    }

    @Test
    public void resumeObserverGatePreventsDomEvaluationUntilObserverReady() throws Exception {
        String text = source();
        int resume = text.indexOf("private void resumeFromUi()");
        int nextMethod = text.indexOf("private void enterPreservedPause", resume);
        assertTrue(resume >= 0 && nextMethod > resume);
        String body = text.substring(resume, nextMethod);
        assertTrue(body.contains("resumeObserverGate = preserved && !rateLimited;"));

        int request = text.indexOf("private void requestDomEvaluation");
        int drain = text.indexOf("private void drainPendingDomEvaluation", request);
        assertTrue(request >= 0 && drain > request);
        assertTrue(text.substring(request, drain).contains("resumeObserverGate"));
    }

    @Test
    public void observerReadyOpensGateBeforeRequestingEvaluation() throws Exception {
        String text = source();
        int observer = text.indexOf("private void ensureDomObserver()");
        int nextMethod = text.indexOf("private boolean observerPageReady", observer);
        assertTrue(observer >= 0 && nextMethod > observer);
        String body = text.substring(observer, nextMethod);
        int ready = body.indexOf("if (data.startsWith(\"ready|\"))");
        int evaluation = body.indexOf("requestDomEvaluation(0L, \"observer_ready\");", ready);
        assertTrue(ready >= 0);
        assertTrue(evaluation > ready);
    }

    @Test
    public void staleEvaluateGuardIncludesRunGenerationWebViewAndRateLimitBeforeSharedMutation() throws Exception {
        String text = source();
        int method = text.indexOf("private void evaluate(String phase, String script)");
        int nextMethod = text.indexOf("private static boolean isWaitingStatus", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);
        int callback = body.indexOf("active.evaluateJavascript(script, raw -> {");
        int executionGuard = body.indexOf("if (!isCurrentExecution(active, activeGeneration, activeExecutionEpoch, activeRunId))", callback);
        int staleLog = body.indexOf("recordStaleCallback(\"evaluate\")", executionGuard);
        int rateLimitGuard = body.indexOf("if (isRateLimited())", staleLog);
        int rateLimitLog = body.indexOf("recordStaleCallback(\"evaluate_rate_limit\")", rateLimitGuard);
        int clearInFlight = body.indexOf("evaluationInFlight = false;", rateLimitLog);
        int clearRateLimit = body.indexOf("rateLimitedUntilElapsed = 0L;", clearInFlight);
        assertTrue(callback >= 0);
        assertTrue(executionGuard > callback);
        assertTrue(staleLog > executionGuard);
        assertTrue(rateLimitGuard > staleLog);
        assertTrue(rateLimitLog > rateLimitGuard);
        assertTrue(clearInFlight > rateLimitLog);
        assertTrue(clearRateLimit > clearInFlight);
    }

    @Test
    public void rateLimitInvalidatesInFlightEvaluationBeforePowerReleaseAndExpiryScheduling() throws Exception {
        String text = source();
        int method = text.indexOf("private void rateLimit(String reason)");
        int nextMethod = text.indexOf("private void scheduleRateLimitExpiry()", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);
        int deadline = body.indexOf("rateLimitedUntilElapsed = now + delay;");
        int generationAdvance = body.indexOf("generation++;", deadline);
        int clearInFlight = body.indexOf("evaluationInFlight = false;", generationAdvance);
        int clearPending = body.indexOf("domEvaluationPending = false;", clearInFlight);
        int powerRelease = body.indexOf("updateWakeLockForState(\"rate_limit_wait\")", clearPending);
        int expiry = body.indexOf("scheduleRateLimitExpiry();", powerRelease);
        assertTrue(deadline >= 0);
        assertTrue(generationAdvance > deadline);
        assertTrue(clearInFlight > generationAdvance);
        assertTrue(clearPending > clearInFlight);
        assertTrue(powerRelease > clearPending);
        assertTrue(expiry > powerRelease);
    }

    private static String source() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
