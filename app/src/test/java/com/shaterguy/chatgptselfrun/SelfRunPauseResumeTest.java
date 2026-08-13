package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunPauseResumeTest {
    @Test
    public void preservedPauseStopsDriveGuardWebAndWakeLock() throws Exception {
        String source = source("SelfRunService.java");
        String stop = method(source, "private void removeAutomationCallbacks()", "private void stopAutomationCallbacks()");
        assertTrue(stop.contains("removeCallbacks(driveRunnable)"));
        assertTrue(stop.contains("removeCallbacks(webRunnable)"));
        assertTrue(stop.contains("removeCallbacks(guardRunnable)"));
        assertTrue(stop.contains("removeCallbacks(driveRetryRunnable)"));
        assertTrue(stop.contains("removeCallbacks(submissionRetryRunnable)"));

        String pause = method(source, "private void enterPreservedPause", "private void removeAutomationCallbacks()");
        assertTrue(pause.contains("removeAutomationCallbacks()"));
        assertTrue(pause.contains("synchronized (automationStateLock)"));
        assertTrue(pause.contains("synchronized (SelfRunStore.RUN_STATE_LOCK)"));
        assertTrue(pause.contains("releaseWakeLock()"));
        assertTrue(pause.contains("pauseWebView()"));
        assertFalse(pause.contains("cleanupWebView()"));
    }

    @Test
    public void driveWaitAndGuardDoNotEvaluateAssistantDomOrHoldWakeLock() throws Exception {
        String source = source("SelfRunService.java");
        String poll = method(source, "private void scheduleDrivePoll()", "private void scheduleWeb");
        String guard = method(source, "private void scheduleGuard()", "private void guardElapsed()");
        assertTrue(poll.contains("releaseWakeLock()"));
        assertTrue(guard.contains("releaseWakeLock()"));
        assertFalse(poll.contains("evaluateJavascript"));
        assertFalse(guard.contains("evaluateJavascript"));
        assertFalse(source.contains("SelfRunDom.observeAssistant"));
        assertFalse(source.contains("WAIT_ASSISTANT"));
        String gate = method(source, "private static boolean isWebAutomationPhase", "private void handleDriveFailure");
        assertFalse(gate.contains("PHASE_WAIT_DRIVE_COMMIT"));
        assertFalse(gate.contains("PHASE_DRIVE_COMMIT_GUARD"));
    }

    @Test public void continuationRecoveryChecksSuccessBeforeFiveMinuteRetryAndNeverTimeoutPauses() throws Exception {
        String source = source("SelfRunService.java");
        assertTrue(source.contains("SelfRunDom.checkDriveTurnSubmitted"));
        assertTrue(source.contains("SUBMISSION_RETRY_MS = 5 * 60_000L"));
        assertTrue(source.contains("scheduleSubmissionRetry(SelfRunStore.RETRY_CONTINUE"));
        assertFalse(source.contains("SUBMISSION_CONFIRMATION_TIMEOUT"));
    }

    @Test
    public void continuationCrashRecoveryChecksOnlyUserMessageMarker() {
        String script = SelfRunDom.checkDriveTurnSubmitted(
                "https://chatgpt.com/g/g-p-demo/c/abc", "[SELF_RUN_CONTINUE SR-1]", "SR-1:1:1", 2);
        assertTrue(script.contains("data-message-author-role=\"user\""));
        assertTrue(script.contains("[SELF_RUN_CONTINUE SR-1]"));
        assertTrue(script.contains("beforeCount"));
        assertFalse(script.contains("SELF_RUN_DRIVE_COMMIT_ID="));
        assertFalse(script.contains("assistant"));
        assertFalse(script.contains("send.click"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun", file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun", file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String method(String source, String start, String next) {
        int a = source.indexOf(start), b = source.indexOf(next, a + start.length());
        assertTrue(a >= 0 && b > a);
        return source.substring(a, b);
    }
}
