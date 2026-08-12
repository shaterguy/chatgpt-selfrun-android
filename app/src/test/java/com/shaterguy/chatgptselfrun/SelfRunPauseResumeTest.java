package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunPauseResumeTest {
    @Test
    public void userActionPausePreservesCurrentWebView() {
        assertTrue(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.USER_ACTION));
    }

    @Test
    public void protocolPauseUsesTheSamePreservedWebViewPolicy() {
        assertTrue(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.PAUSE));
    }

    @Test
    public void terminalSignalsDoNotUseResumablePausePolicy() {
        assertFalse(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.DONE));
        assertFalse(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.NEXT));
    }

    @Test
    public void preservedPauseStopsAutomationBeforePowerReleaseAndKeepsWebViewAlive() throws Exception {
        String text = source();
        int method = text.indexOf("private void enterPreservedPause(String cause, String status)");
        int nextMethod = text.indexOf("private void updateWakeLockForState", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);
        int queueClear = body.indexOf("handler.removeCallbacksAndMessages(null);");
        int generationAdvance = body.indexOf("generation++;");
        int observerDetach = body.indexOf("detachDomObserver(cause);");
        int powerRelease = body.indexOf("setWakeLockState(WakeLockController.State.PAUSED");
        assertTrue(queueClear >= 0);
        assertTrue(generationAdvance >= 0);
        assertTrue(observerDetach >= 0);
        assertTrue(powerRelease >= 0);
        assertTrue(queueClear < observerDetach);
        assertTrue(generationAdvance < observerDetach);
        assertTrue(observerDetach < powerRelease);
        assertFalse(body.contains("cleanupWebView()"));
        assertFalse(body.contains("stopRelay()"));
        assertFalse(body.contains("loadUrl("));
        assertFalse(body.contains("reload("));
        assertFalse(body.contains("onPause()"));
        assertFalse(body.contains("pauseTimers"));
    }

    @Test
    public void preservedResumeAcquiresPowerBeforeObserverReattachWithoutNavigation() throws Exception {
        String text = source();
        int method = text.indexOf("private void resumeFromUi()");
        int nextMethod = text.indexOf("private void enterPreservedPause", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);
        assertTrue(body.contains("boolean preserved = webView != null;"));
        assertTrue(body.contains("if (preserved)"));
        int wake = body.indexOf("updateWakeLockForState(\"resume_prepare\")");
        int preserved = body.indexOf("if (preserved)");
        int observer = body.indexOf("ensureDomObserver();", preserved);
        int watchdog = body.indexOf("scheduleWatchdog();", preserved);
        assertTrue(wake >= 0);
        assertTrue(observer > wake);
        assertTrue(watchdog > wake);
        assertFalse(body.contains("scheduleStep("));
        assertFalse(body.contains("requestDomEvaluation("));
        assertFalse(body.contains("webView.onResume()"));
        assertFalse(body.contains("reload("));
        int elseBlock = body.indexOf("} else {", preserved);
        String preservedBody = body.substring(preserved, elseBlock);
        assertFalse(preservedBody.contains("loadUrl("));
        assertFalse(preservedBody.contains("cleanupWebView()"));
    }

    @Test
    public void observerReadyDrivesTheFirstPostResumeDomEvaluation() throws Exception {
        String text = source();
        int observer = text.indexOf("private void ensureDomObserver()");
        int nextMethod = text.indexOf("private static Uri chatGptOrigin", observer);
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
        int staleGuard = body.indexOf(
                "if (!isCurrentExecution(active, activeGeneration, activeRunId) || isRateLimited()) return;", callback);
        int clearInFlight = body.indexOf("evaluationInFlight = false;", callback);
        int clearRateLimit = body.indexOf("rateLimitedUntilElapsed = 0L;", callback);
        assertTrue(callback >= 0);
        assertTrue(staleGuard > callback);
        assertTrue(clearInFlight > staleGuard);
        assertTrue(clearRateLimit > staleGuard);
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
        int expirySchedule = body.indexOf("scheduleRateLimitExpiry();", powerRelease);
        assertTrue(deadline >= 0);
        assertTrue(generationAdvance > deadline);
        assertTrue(clearInFlight > generationAdvance);
        assertTrue(clearPending > clearInFlight);
        assertTrue(powerRelease > clearPending);
        assertTrue(expirySchedule > powerRelease);
    }

    @Test
    public void rateLimitExpiryIsRunLevelAndIgnoresWebViewNavigationGeneration() throws Exception {
        String text = source();
        int schedule = text.indexOf("private void scheduleRateLimitExpiry()");
        int restore = text.indexOf("private void restoreCanonical", schedule);
        assertTrue(schedule >= 0 && restore > schedule);
        String body = text.substring(schedule, restore);
        assertTrue(body.contains("long expectedDeadline = rateLimitedUntilElapsed;"));
        assertTrue(body.contains("long expectedTimerEpoch = rateLimitTimerEpoch == Long.MAX_VALUE ? 1L : rateLimitTimerEpoch + 1L;"));
        assertTrue(body.contains("rateLimitTimerEpoch = expectedTimerEpoch;"));
        assertTrue(body.contains("String expectedRunId = store.runId();"));
        assertTrue(body.contains("isCurrentRateLimitTimer(expectedRunId, expectedTimerEpoch, expectedDeadline)"));
        assertTrue(body.contains("expectedTimerEpoch == rateLimitTimerEpoch"));
        assertTrue(body.contains("expectedDeadline == rateLimitedUntilElapsed"));
        assertTrue(body.contains("expectedRunId.equals(store.runId())"));
        assertTrue(body.contains("!store.userStopped() && canRun()"));
        assertFalse(body.contains("expectedGeneration"));
        assertFalse(body.contains("expectedWebView"));
        assertFalse(body.contains("isCurrentExecution("));
        int guard = body.indexOf("isCurrentRateLimitTimer(expectedRunId, expectedTimerEpoch, expectedDeadline)");
        int recovery = body.indexOf("beginRecovery(\"rate_limit_expired\")", guard);
        assertTrue(recovery > guard);
    }

    @Test
    public void rateLimitResumeRearmsRunLevelExpiryBeforeAutomationRestarts() throws Exception {
        String text = source();
        int resume = text.indexOf("private void resumeFromUi()");
        int pause = text.indexOf("private void enterPreservedPause", resume);
        assertTrue(resume >= 0 && pause > resume);
        String body = text.substring(resume, pause);
        int capture = body.indexOf("boolean rateLimited = isRateLimited();");
        int recovery = body.indexOf("recoveryInProgress = !preserved && !rateLimited;", capture);
        int wake = body.indexOf("updateWakeLockForState(\"resume_prepare\")", recovery);
        int wait = body.indexOf("if (rateLimited)", wake);
        int schedule = body.indexOf("scheduleRateLimitExpiry();", wait);
        int stop = body.indexOf("return;", schedule);
        int preserved = body.indexOf("if (preserved)", stop);
        assertTrue(capture >= 0);
        assertTrue(recovery > capture);
        assertTrue(wake > recovery);
        assertTrue(wait > wake);
        assertTrue(schedule > wait);
        assertTrue(stop > schedule);
        assertTrue(preserved > stop);
    }

    @Test
    public void powerPolicySeparatesAutomationFromPauseRateLimitAndTerminalStates() {
        assertTrue(SelfRunService.wakeLockStateFor(true, false, false,
                SelfRunStore.PHASE_WAIT_ASSISTANT, false, false) == WakeLockController.State.AUTOMATION);
        assertTrue(SelfRunService.wakeLockStateFor(true, false, false,
                SelfRunStore.PHASE_WAIT_ASSISTANT, false, true) == WakeLockController.State.RECOVERY);
        assertTrue(SelfRunService.wakeLockStateFor(true, false, false,
                SelfRunStore.PHASE_WAIT_ASSISTANT, true, false) == WakeLockController.State.RATE_LIMIT);
        assertTrue(SelfRunService.wakeLockStateFor(true, true, false,
                SelfRunStore.PHASE_PAUSED, false, false) == WakeLockController.State.PAUSED);
        assertTrue(SelfRunService.wakeLockStateFor(true, false, false,
                SelfRunStore.PHASE_DONE, false, false) == WakeLockController.State.DONE);
        assertTrue(SelfRunService.wakeLockStateFor(true, false, false,
                SelfRunStore.PHASE_IDLE, false, false) == WakeLockController.State.IDLE);
        assertTrue(SelfRunService.wakeLockStateFor(false, false, true,
                SelfRunStore.PHASE_IDLE, false, false) == WakeLockController.State.STOPPED);
    }

    @Test
    public void terminalCleanupClosesControllerAndCancelsLateCallbacks() throws Exception {
        String text = source();
        int stop = text.indexOf("private void stopRelay()");
        int log = text.indexOf("private void logDomEfficiency", stop);
        String stopBody = text.substring(stop, log);
        assertTrue(stopBody.contains("handler.removeCallbacksAndMessages(null)"));
        assertTrue(stopBody.contains("setWakeLockState(WakeLockController.State.STOPPED"));
        assertTrue(stopBody.contains("wakeLockController.close(\"stop_relay\")"));

        int destroy = text.indexOf("public void onDestroy()");
        assertTrue(destroy >= 0);
        String destroyBody = text.substring(destroy);
        assertTrue(destroyBody.contains("handler.removeCallbacksAndMessages(null)"));
        assertTrue(destroyBody.contains("wakeLockController.close(\"on_destroy\")"));
    }

    private static String source() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
