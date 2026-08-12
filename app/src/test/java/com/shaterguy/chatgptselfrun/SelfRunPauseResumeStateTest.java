package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunPauseResumeStateTest {
    private static final String RUN_ID = "SR-20260813-072304-DCF136";

    @Test
    public void manualUiPauseRestoresExactAssistantAndPreferencePhases() {
        assertEquals(SelfRunStore.PHASE_WAIT_ASSISTANT,
                SelfRunStore.resolvePausedResumePhase(SelfRunStore.PHASE_WAIT_ASSISTANT, true));
        assertEquals(SelfRunStore.PHASE_APPLY_PREFS,
                SelfRunStore.resolvePausedResumePhase(SelfRunStore.PHASE_APPLY_PREFS, true));
        assertEquals("APPLY_REASONING",
                SelfRunStore.resolvePausedResumePhase("APPLY_REASONING", true));
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunStore.resolvePausedResumePhase(SelfRunStore.PHASE_SEND_CONTINUE, true));
    }

    @Test
    public void manualUiPauseRestoresExactBootstrapPhaseBeforeConversationCapture() {
        assertEquals(SelfRunStore.PHASE_BOOTSTRAP,
                SelfRunStore.resolvePausedResumePhase(SelfRunStore.PHASE_BOOTSTRAP, false));
        assertEquals("BOOTSTRAP_MODEL",
                SelfRunStore.resolvePausedResumePhase("BOOTSTRAP_MODEL", false));
        assertEquals("BOOTSTRAP_REASONING",
                SelfRunStore.resolvePausedResumePhase("BOOTSTRAP_REASONING", false));
        assertEquals("BOOTSTRAP_SEND",
                SelfRunStore.resolvePausedResumePhase("BOOTSTRAP_SEND", false));
    }

    @Test
    public void invalidOrMissingResumeTargetFallsBackByConversationContext() {
        assertEquals(SelfRunStore.PHASE_BOOTSTRAP,
                SelfRunStore.resolvePausedResumePhase("", false));
        assertEquals(SelfRunStore.PHASE_BOOTSTRAP,
                SelfRunStore.resolvePausedResumePhase("WAIT_ASSISTANT", false));
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunStore.resolvePausedResumePhase("", true));
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunStore.resolvePausedResumePhase("BOOTSTRAP_SEND", true));
    }

    @Test
    public void protocolPauseConsumesAssistantSignalThenTargetsContinuation() {
        String userAction = "[SELF_RUN_USER_ACTION_REQUIRED " + RUN_ID + " AUTH0_ALLOWED_SUB_UPDATE]";
        String protocolPause = "[SELF_RUN_PAUSE " + RUN_ID + " REASON=EXTERNAL_WAIT]";

        assertTrue(SelfRunStore.isProtocolPauseSignal(userAction, RUN_ID));
        assertTrue(SelfRunStore.isProtocolPauseSignal(protocolPause, RUN_ID));
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunStore.capturePauseResumePhase(
                        SelfRunStore.PHASE_WAIT_ASSISTANT, userAction, RUN_ID, ""));
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunStore.capturePauseResumePhase(
                        SelfRunStore.PHASE_WAIT_ASSISTANT, protocolPause, RUN_ID, ""));
    }

    @Test
    public void manualPauseCapturesCurrentRunningPhaseInsteadOfForcingContinuation() {
        String next = "[SELF_RUN_NEXT " + RUN_ID + " ROLE=VERIFIER]";

        assertFalse(SelfRunStore.isProtocolPauseSignal(next, RUN_ID));
        assertEquals(SelfRunStore.PHASE_WAIT_ASSISTANT,
                SelfRunStore.capturePauseResumePhase(
                        SelfRunStore.PHASE_WAIT_ASSISTANT, next, RUN_ID, ""));
        assertEquals(SelfRunStore.PHASE_APPLY_PREFS,
                SelfRunStore.capturePauseResumePhase(
                        SelfRunStore.PHASE_APPLY_PREFS, next, RUN_ID, ""));
        assertEquals("APPLY_REASONING",
                SelfRunStore.capturePauseResumePhase(
                        "APPLY_REASONING", next, RUN_ID, ""));
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunStore.capturePauseResumePhase(
                        SelfRunStore.PHASE_SEND_CONTINUE, "RECOVERY", RUN_ID, ""));
    }

    @Test
    public void nonPreservedErrorPauseDoesNotCaptureAnExecutionPhase() {
        assertEquals("",
                SelfRunStore.capturePauseResumePhase(
                        SelfRunStore.PHASE_WAIT_ASSISTANT,
                        "[SELF_RUN_NEXT " + RUN_ID + " ROLE=VERIFIER]",
                        RUN_ID,
                        "AUTH_REQUIRED"));
    }

    @Test
    public void manualPauseDuringSignalRecoveryKeepsRecoverySubmissionKind() {
        assertEquals("RECOVERY",
                SelfRunStore.signalValueAfterResume(
                        "RECOVERY",
                        "USER_RESUME",
                        SelfRunStore.PHASE_PAUSED,
                        SelfRunStore.PAUSE_CAUSE_UI));
        assertEquals("USER_RESUME",
                SelfRunStore.signalValueAfterResume(
                        "[SELF_RUN_PAUSE " + RUN_ID + " REASON=EXTERNAL_WAIT]",
                        "USER_RESUME",
                        SelfRunStore.PHASE_PAUSED,
                        SelfRunStore.PAUSE_CAUSE_PROTOCOL));
    }

    @Test
    public void storePersistsResumeTargetBeforePausedPhaseCanOverwriteIt() throws Exception {
        String text = storeSource();
        int method = text.indexOf("void setPaused(boolean value)");
        int nextMethod = text.indexOf("void setActive(boolean value)", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);

        int capture = body.indexOf("capturePauseResumePhase(phase(), lastSignal(), runId(), lastErrorCode())");
        int persistTarget = body.indexOf(".putString(\"pauseResumePhase\", resumePhase)");
        int persistCause = body.indexOf(".putString(\"pauseCause\", cause)");
        assertTrue(capture >= 0);
        assertTrue(persistTarget > capture);
        assertTrue(persistCause > persistTarget);
    }

    @Test
    public void storeConsumesDurableResumeTargetWhenServiceLeavesPausedFallbackPhase() throws Exception {
        String text = storeSource();
        int method = text.indexOf("void setPhase(String value)");
        int nextMethod = text.indexOf("void restartPhaseClock()", method);
        assertTrue(method >= 0 && nextMethod > method);
        String body = text.substring(method, nextMethod);

        assertTrue(body.contains("PHASE_PAUSED.equals(current) && !paused()"));
        assertTrue(body.contains("resolvePausedResumePhase(pauseResumePhase(), !conversationUrl().isEmpty())"));
        assertTrue(body.contains("editor.putString(\"pauseResumePhase\", \"\").putString(\"pauseCause\", \"\")"));
    }

    @Test
    public void rendererLossWhilePausedDoesNotClearDurableResumeTarget() throws Exception {
        String text = serviceSource();
        int renderer = text.indexOf("public boolean onRenderProcessGone");
        int nextOverride = text.indexOf("});", renderer);
        assertTrue(renderer >= 0 && nextOverride > renderer);
        String body = text.substring(renderer, nextOverride);

        assertTrue(body.contains("if (paused)"));
        assertTrue(body.contains("WEBVIEW_RECOVERY_DEFERRED"));
        assertFalse(body.contains("setPaused(false)"));
        assertFalse(body.contains("setPhase("));
        assertFalse(body.contains("pauseResumePhase"));
    }

    @Test
    public void existingServiceResumeFallbackIsInterceptedOnlyAfterPausedFlagClears() throws Exception {
        String text = serviceSource();
        int resume = text.indexOf("private void resumeFromUi()");
        int nextMethod = text.indexOf("private void enterPreservedPause", resume);
        assertTrue(resume >= 0 && nextMethod > resume);
        String body = text.substring(resume, nextMethod);

        int unpause = body.indexOf("store.setPaused(false);");
        int phaseFallback = body.indexOf("store.setPhase(SelfRunStore.PHASE_BOOTSTRAP);");
        assertTrue(unpause >= 0);
        assertTrue(phaseFallback > unpause);
        assertTrue(body.contains("store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE);"));
    }

    private static String storeSource() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static String serviceSource() throws Exception {
        Path source = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
