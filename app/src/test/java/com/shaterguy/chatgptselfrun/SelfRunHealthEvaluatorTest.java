package com.shaterguy.chatgptselfrun;

import android.app.ApplicationExitInfo;

import org.junit.Test;

import static org.junit.Assert.*;

public final class SelfRunHealthEvaluatorTest {
    @Test public void normalRunIsNormal() {
        SelfRunHealthSnapshot h = evaluate(base(SelfRunStore.PHASE_BOOTSTRAP));
        assertEquals(SelfRunHealthSnapshot.NORMAL, h.level);
        assertEquals("NORMAL", h.category);
    }

    @Test public void chatGptWaitIsNotAnError() {
        SelfRunHealthSnapshot h = evaluate(base(SelfRunStore.PHASE_WAIT_TURN_COMPLETION));
        assertEquals(SelfRunHealthSnapshot.WAITING, h.level);
        assertEquals("WAITING_CHATGPT", h.category);
    }

    @Test public void composerReasonsMapToComposerWait() {
        for (String reason : new String[]{"composer_wait", "input_wait", "composer_clearing", "composer_inputting", "input_reflection_wait"}) {
            SelfRunHealthInput in = web(base(SelfRunStore.PHASE_SEND_CONTINUE), reason, "send_continue", 3_000L);
            assertEquals("WAITING_COMPOSER", evaluate(in).category);
        }
    }

    @Test public void sendReasonsMapToSendWait() {
        for (String reason : new String[]{"send_wait", "send_disabled", "submission_pending"}) {
            SelfRunHealthInput in = web(base(SelfRunStore.PHASE_SEND_CONTINUE), reason, "send_continue", 3_000L);
            assertEquals("WAITING_SEND", evaluate(in).category);
        }
    }

    @Test public void modelAndReasoningWaitsRemainWaiting() {
        SelfRunHealthInput model = web(base(SelfRunStore.PHASE_APPLY_PREFS), "model_wait", "apply_model", 3_000L);
        SelfRunHealthInput reasoning = web(base(SelfRunStore.PHASE_APPLY_REASONING), "reasoning_wait", "apply_reasoning", 3_000L);
        assertEquals("WAITING_MODEL", evaluate(model).category);
        assertEquals("WAITING_REASONING", evaluate(reasoning).category);
    }

    @Test public void driveSyncIsNormalWaiting() {
        SelfRunHealthSnapshot h = evaluate(base(SelfRunStore.PHASE_POST_PROTOCOL_DRIVE_SYNC));
        assertEquals(SelfRunHealthSnapshot.WAITING, h.level);
        assertEquals("WAITING_DRIVE", h.category);
    }

    @Test public void offlineActiveRunIsRecovering() {
        SelfRunHealthInput in = base(SelfRunStore.PHASE_WAIT_TURN_COMPLETION);
        in.networkKnown = true;
        in.networkValidated = false;
        in.networkObservedAt = 3_000L;
        SelfRunHealthSnapshot h = evaluate(in);
        assertEquals(SelfRunHealthSnapshot.RECOVERING, h.level);
        assertEquals("NETWORK_OFFLINE", h.category);
    }

    @Test public void routeMismatchIsRecoveringWithoutRawUrl() {
        SelfRunHealthInput in = web(base(SelfRunStore.PHASE_SEND_CONTINUE), "conversation_mismatch", "send_continue", 3_000L);
        SelfRunHealthSnapshot h = evaluate(in);
        assertEquals(SelfRunHealthSnapshot.RECOVERING, h.level);
        assertEquals("ROUTE_MISMATCH", h.category);
        assertFalse(h.description.contains("http"));
    }

    @Test public void scriptAndSubmissionErrorsAreExplicitErrors() {
        SelfRunHealthSnapshot script = evaluate(web(base(SelfRunStore.PHASE_SEND_CONTINUE), "script_error", "send_continue", 3_000L));
        SelfRunHealthSnapshot submit = evaluate(web(base(SelfRunStore.PHASE_SEND_CONTINUE), "submission_failed", "send_continue", 3_000L));
        assertEquals(SelfRunHealthSnapshot.ERROR, script.level);
        assertEquals("SCRIPT_ERROR", script.category);
        assertEquals(SelfRunHealthSnapshot.ERROR, submit.level);
        assertEquals("SUBMISSION_FAILED", submit.category);
    }

    @Test public void userActionPauseAndStopAreDistinct() {
        SelfRunHealthInput action = base(SelfRunStore.PHASE_PAUSED);
        action.paused = true;
        action.driveSignalType = "USER_ACTION_REQUIRED";
        assertEquals("USER_ACTION_REQUIRED", evaluate(action).category);

        SelfRunHealthInput paused = base(SelfRunStore.PHASE_PAUSED);
        paused.paused = true;
        assertEquals("PAUSED", evaluate(paused).category);

        SelfRunHealthInput stopped = base(SelfRunStore.PHASE_IDLE);
        stopped.active = false;
        stopped.userStopped = true;
        stopped.terminal = true;
        assertEquals("STOPPED", evaluate(stopped).category);
    }

    @Test public void doneHasHighestPriority() {
        SelfRunHealthInput in = base(SelfRunStore.PHASE_DONE);
        in.networkKnown = true;
        in.networkValidated = false;
        in.paused = true;
        in.driveSignalType = "DONE";
        SelfRunHealthSnapshot h = evaluate(in);
        assertEquals(SelfRunHealthSnapshot.TERMINAL, h.level);
        assertEquals("DONE", h.category);
    }

    @Test public void explicitRetryIsRecoveringNotError() {
        SelfRunHealthInput in = base(SelfRunStore.PHASE_SEND_CONTINUE);
        in.lastErrorCode = "WEB_STATE_RETRY";
        in.status = "Drive V1 WebView 단계를 자동 재확인합니다";
        assertEquals(SelfRunHealthSnapshot.RECOVERING, evaluate(in).level);
    }

    @Test public void staleWebObservationCannotOverrideNewPhase() {
        SelfRunHealthInput in = base(SelfRunStore.PHASE_WAIT_TURN_COMPLETION);
        in.phaseStartedAt = 5_000L;
        in.webReason = "script_error";
        in.webPhase = "send_continue";
        in.webObservedAt = 3_000L;
        SelfRunHealthSnapshot h = evaluate(in);
        assertEquals("WAITING_CHATGPT", h.category);
    }

    @Test public void processExitRequiresCurrentRunCorrelation() {
        SelfRunHealthInput in = base(SelfRunStore.PHASE_WAIT_TURN_COMPLETION);
        in.processCategory = "APP_CRASH";
        in.processReason = "crash";
        in.processObservedAt = 4_000L;
        in.updatedAt = 1_500L;
        SelfRunHealthSnapshot h = evaluate(in);
        assertEquals("APP_CRASH", h.category);
        assertEquals(SelfRunHealthSnapshot.CONFIRMED, h.confidence);

        in.updatedAt = 4_500L;
        assertEquals("WAITING_CHATGPT", evaluate(in).category);
    }

    @Test public void applicationExitReasonClassifierDoesNotInferLowMemoryFromSignal() {
        assertEquals("LOW_MEMORY", SelfRunProcessExitDiagnostics.classifyReason(ApplicationExitInfo.REASON_LOW_MEMORY, 30).category);
        assertEquals("PROCESS_EXIT_GENERIC", SelfRunProcessExitDiagnostics.classifyReason(ApplicationExitInfo.REASON_SIGNALED, 30).category);
        assertEquals("APP_ANR", SelfRunProcessExitDiagnostics.classifyReason(ApplicationExitInfo.REASON_ANR, 30).category);
        assertEquals("EXCESSIVE_RESOURCE", SelfRunProcessExitDiagnostics.classifyReason(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, 30).category);
    }

    @Test public void oldApplicationExitInfoIsNotAttachedToCurrentRun() {
        assertFalse(SelfRunProcessExitDiagnostics.isRelated(1_000L, 5_000L, 0L, 4_999L, 8_000L));
        assertFalse(SelfRunProcessExitDiagnostics.isRelated(1_000L, 5_000L, 6_000L, 6_000L, 8_000L));
        assertTrue(SelfRunProcessExitDiagnostics.isRelated(1_000L, 5_000L, 6_000L, 7_000L, 8_000L));
    }

    @Test public void nullInputFallsBackWithoutThrowing() {
        SelfRunHealthSnapshot h = SelfRunHealthEvaluator.evaluate(null, 10_000L);
        assertNotNull(h);
        assertEquals(SelfRunHealthSnapshot.ATTENTION, h.level);
        assertEquals(SelfRunHealthSnapshot.UNKNOWN, h.confidence);
    }

    private static SelfRunHealthInput base(String phase) {
        SelfRunHealthInput in = new SelfRunHealthInput();
        in.runId = "run-test";
        in.phase = phase;
        in.mode = SelfRunStore.MODE_CHAT;
        in.status = "정상";
        in.createdAt = 1_000L;
        in.phaseStartedAt = 2_000L;
        in.updatedAt = 1_500L;
        in.active = true;
        return in;
    }

    private static SelfRunHealthInput web(SelfRunHealthInput in, String reason, String phase, long observedAt) {
        in.webReason = reason;
        in.webPhase = phase;
        in.webObservedAt = observedAt;
        return in;
    }

    private static SelfRunHealthSnapshot evaluate(SelfRunHealthInput in) {
        return SelfRunHealthEvaluator.evaluate(in, 10_000L);
    }
}
