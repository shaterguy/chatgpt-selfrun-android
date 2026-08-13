package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class SelfRunDriveDev3PolicyTest {
    private static String source(String file) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun", file);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun", file);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test public void guardIsWithinThirtyToSixtySeconds() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("CONTINUATION_GUARD_MS = 45_000L"));
        assertFalse(s.contains("CONTINUATION_GUARD_MS = 120_000L"));
    }
    @Test public void driveCompletionNeverUsesAssistantCompletionDetector() throws Exception {
        String s = source("SelfRunService.java");
        assertFalse(s.contains("WAIT_ASSISTANT"));
        assertFalse(s.contains("observeAssistant"));
        assertFalse(s.contains("stop-button"));
        assertFalse(s.contains("GENERATING"));
    }
    @Test public void continuationSuccessIsOnlyUserTurnIncrease() throws Exception {
        String d = source("SelfRunDom.java");
        String part = d.substring(d.indexOf("static String checkDriveTurnSubmitted"),
                d.indexOf("static String observeAssistant"));
        assertTrue(part.contains("count>androidBaseline"));
        assertTrue(part.contains("count>markerBaseline"));
        assertFalse(part.contains("data-message-author-role=\\\"assistant\\\""));
        assertFalse(part.contains("send.click()"));
    }
    @Test public void ambiguousSubmissionSchedulesFiveMinuteRetryInsteadOfPause() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("scheduleSubmissionRetry(SelfRunStore.RETRY_CONTINUE, status)"));
        assertTrue(s.contains("SUBMISSION_RETRY_MS = 5 * 60_000L"));
        assertFalse(s.contains("enterPreservedPause(\"SUBMISSION_AMBIGUOUS\""));
    }
    @Test public void confirmationGraceIsNotTerminalTimeout() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("SUBMISSION_CONFIRMATION_GRACE_MS = 15_000L"));
        assertFalse(s.contains("SUBMISSION_CONFIRMATION_TIMEOUT"));
        assertFalse(s.contains("maxRetryCount"));
    }
    @Test public void retryTimerIsCancelledByPauseAndPersistedByStore() throws Exception {
        String s = source("SelfRunService.java");
        String st = source("SelfRunStore.java");
        assertTrue(s.contains("handler.removeCallbacks(submissionRetryRunnable)"));
        assertTrue(st.contains("submissionRetryDueAt"));
        assertTrue(st.contains("submissionRetryKind"));
        assertTrue(st.contains("submissionRetryAttempt"));
        assertTrue(st.contains("submissionRetryReady"));
    }
    @Test public void retryAttemptCounterCannotTerminateOrOverflow() throws Exception {
        String st = source("SelfRunStore.java");
        assertTrue(st.contains("prior == Integer.MAX_VALUE ? Integer.MAX_VALUE : prior + 1"));
        assertFalse(st.contains("submissionRetryAttempt() >"));
        assertFalse(st.contains("submissionRetryAttempt() =="));
    }
    @Test public void retryRechecksBeforeReclick() throws Exception {
        String d = source("SelfRunDom.java");
        String method = d.substring(d.indexOf("static String prepareDriveTurnRetry"),
                d.indexOf("/** Clicks at most once per attempt"));
        assertTrue(method.indexOf("countPrompt()>baseline") < method.indexOf("READY_TO_SUBMIT"));
    }
    @Test public void baselineIsDurableBeforeContinuationClick() throws Exception {
        String s = source("SelfRunService.java");
        String st = source("SelfRunStore.java");
        assertTrue(s.contains("store.markSubmissionStarted(beforeCount)"));
        assertTrue(st.contains(".putInt(\"submissionBaselineCount\", beforeCount)"));
        assertTrue(s.indexOf("store.markSubmissionStarted(beforeCount)")
                < s.indexOf("SelfRunDom.clickPreparedDriveTurn"));
    }
    @Test public void rendererLossAndNetworkErrorRecoverWithoutEndingJob() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("onRenderProcessGone"));
        assertTrue(s.contains("postWebCallback(SelfRunService.this::ensureWebView, 2_000L)"));
        assertTrue(s.contains("v.loadUrl(canonicalUrl())"));
    }
    @Test public void sslErrorCancelsConnectionAndRetriesWithoutPause() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("WEBVIEW_SSL_RETRY"));
        assertTrue(s.contains("postWebCallback(SelfRunService.this::restoreCanonical, SUBMISSION_RETRY_MS)"));
        assertFalse(s.contains("pauseError(\"WEBVIEW_SSL\""));
    }
    @Test public void driveTransientBackoffIsDelayCappedNotRetryCountCapped() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("retryAttempt == Integer.MAX_VALUE ? Integer.MAX_VALUE : retryAttempt + 1"));
        assertTrue(s.contains("Math.min(Math.max(0, retryAttempt - 1), BACKOFF.length - 1)"));
        assertFalse(s.contains("retryAttempt >= BACKOFF.length"));
    }
    @Test public void manualPausePreservesRetryState() throws Exception {
        String st = source("SelfRunStore.java");
        String pause = st.substring(st.indexOf("void enterPause("), st.indexOf("void leavePause("));
        assertFalse(pause.contains("submissionRetryDueAt"));
    }
    @Test public void resumeRestoresPriorPhaseThenRetrySchedulerWins() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("if (store.hasSubmissionRetry())"));
        assertTrue(s.contains("scheduleOrResumeSubmissionRetry();"));
        assertTrue(s.contains("store.leavePause(next)"));
    }
    @Test public void terminalPauseAndUserActionArePauseNotDone() throws Exception {
        String st = source("SelfRunStore.java");
        String terminal = st.substring(st.indexOf("void consumeTerminal"), st.indexOf("void resumeTerminalWithContinuation"));
        assertTrue(terminal.contains("case PAUSE"));
        assertTrue(terminal.contains("case USER_ACTION"));
        assertTrue(terminal.contains(".putBoolean(\"paused\", true)"));
    }
    @Test public void duplicateDriveCommitCannotScheduleAnotherContinuation() throws Exception {
        String s = source("SelfRunService.java");
        String st = source("SelfRunStore.java");
        assertTrue(s.contains("boolean restoring = commit.id().equals(store.pendingCommitId())"));
        assertTrue(st.contains("lastConsumedEventSeq"));
        assertTrue(st.contains("expectedTurn"));
    }
    @Test public void successImmediatelyReturnsToDriveWait() throws Exception {
        String s = source("SelfRunService.java");
        String st = source("SelfRunStore.java");
        assertTrue(s.contains("finalizeConfirmedContinuation()"));
        assertTrue(st.contains(".putString(\"phase\", PHASE_WAIT_DRIVE_COMMIT)"));
        assertTrue(s.contains("scheduleDrivePoll()"));
    }
    @Test public void noAssistantStateAppearsInContinuationSubmissionScripts() throws Exception {
        String d = source("SelfRunDom.java");
        String part = d.substring(d.indexOf("static String prepareDriveTurn"), d.indexOf("static String observeAssistant"));
        assertFalse(part.contains("stop-button"));
        assertFalse(part.contains("aria-busy"));
        assertFalse(part.contains("data-is-streaming"));
    }
    @Test public void bootstrapDoesNotExposeInternalDriveMetadata() throws Exception {
        String p = source("SelfRunProtocol.java");
        String b = p.substring(p.indexOf("static String bootstrapDrive"), p.indexOf("static String continuation"));
        assertTrue(b.contains("SELF_RUN_CLIENT=DRIVE_V1"));
        assertTrue(b.contains("DRIVE_TURN_DOCUMENT_ID="));
        assertFalse(b.contains("ANDROID_APPLICATION_ID"));
        assertFalse(b.contains("DRIVE_PROTOCOL_VERSION"));
        assertFalse(b.contains("DRIVE_RUNS_BASE_FOLDER_ID"));
        assertFalse(b.contains("DRIVE_JOB_FOLDER_ID"));
        assertFalse(b.contains("DRIVE_TURN_DOCUMENT_URL"));
        assertFalse(b.contains("DRIVE_EXPECTED_TURN"));
    }
    @Test public void noSubmissionFailurePathStopsRuntime() throws Exception {
        String s = source("SelfRunService.java");
        String e = s.substring(s.indexOf("private void evaluate("), s.indexOf("private void handleWebResult"));
        assertFalse(e.contains("stopRuntime()"));
        assertFalse(e.contains("SUBMISSION_CONFIRMATION_TIMEOUT"));
        assertTrue(e.contains("scheduleSubmissionRetry"));
    }
}
