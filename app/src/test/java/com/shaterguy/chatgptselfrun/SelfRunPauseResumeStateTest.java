package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunPauseResumeStateTest {
    @Test
    public void protocolPauseAlwaysResumesAtContinueWhenConversationExists() {
        String runId = "SR-test";
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunStore.capturePauseResumePhase(SelfRunStore.PHASE_WAIT_ASSISTANT,
                        "[SELF_RUN_USER_ACTION_REQUIRED " + runId + " AUTH]", runId, ""));
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunStore.capturePauseResumePhase("APPLY_REASONING",
                        "[SELF_RUN_PAUSE " + runId + " REASON=WAIT]", runId, ""));
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunStore.resolvePausedResumePhase(SelfRunStore.PHASE_SEND_CONTINUE, true));
    }

    @Test
    public void manualPausePreservesCurrentResumablePhase() {
        String runId = "SR-test";
        assertEquals(SelfRunStore.PHASE_WAIT_ASSISTANT,
                SelfRunStore.capturePauseResumePhase(SelfRunStore.PHASE_WAIT_ASSISTANT,
                        "NEXT", runId, ""));
        assertEquals("APPLY_REASONING",
                SelfRunStore.capturePauseResumePhase("APPLY_REASONING", "NEXT", runId, ""));
        assertEquals("APPLY_REASONING",
                SelfRunStore.resolvePausedResumePhase("APPLY_REASONING", true));
    }

    @Test
    public void recoverySignalIsRetainedAcrossManualResume() {
        assertEquals("RECOVERY", SelfRunStore.signalValueAfterResume(
                "RECOVERY", "USER_RESUME", SelfRunStore.PHASE_PAUSED, SelfRunStore.PAUSE_CAUSE_UI));
        assertEquals("USER_RESUME", SelfRunStore.signalValueAfterResume(
                "RECOVERY", "USER_RESUME", SelfRunStore.PHASE_PAUSED, SelfRunStore.PAUSE_CAUSE_PROTOCOL));
    }

    @Test
    public void errorPauseDoesNotPretendToHaveResumableTarget() {
        assertEquals("", SelfRunStore.capturePauseResumePhase(SelfRunStore.PHASE_WAIT_ASSISTANT,
                "NEXT", "SR-test", "TARGET_MISSING"));
    }

    @Test
    public void conversationResumeFallbackNeverReturnsBootstrap() {
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunStore.resolvePausedResumePhase("", true));
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunStore.resolvePausedResumePhase(SelfRunStore.PHASE_BOOTSTRAP, true));
        assertEquals(SelfRunStore.PHASE_BOOTSTRAP,
                SelfRunStore.resolvePausedResumePhase("", false));
    }

    @Test
    public void storeDurablyPersistsPauseResumeMetadata() throws Exception {
        String text = storeSource();
        assertTrue(text.contains("putString(\"pauseResumePhase\", resumePhase)"));
        assertTrue(text.contains("putString(\"pauseCause\", cause)"));
        assertTrue(text.contains("String pauseResumePhase()"));
        assertTrue(text.contains("String pauseCause()"));
        assertTrue(text.contains("resolvePausedResumePhase(pauseResumePhase(), !conversationUrl().isEmpty())"));
    }

    @Test
    public void setPhaseInterceptsLegacyResumeFallback() throws Exception {
        String text = storeSource();
        int method = text.indexOf("void setPhase(String value)");
        int nextMethod = text.indexOf("void setPhaseAndStatus", method);
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
    public void resumeClearsPauseAndSelectsResumePhaseInOneStoreTransaction() throws Exception {
        String service = serviceSource();
        int resume = service.indexOf("private void resumeFromUi()");
        int nextMethod = service.indexOf("private void enterPreservedPause", resume);
        assertTrue(resume >= 0 && nextMethod > resume);
        String body = service.substring(resume, nextMethod);
        assertTrue(body.contains("store.resumeState("));
        assertFalse(body.contains("store.setPaused(false)"));
        assertFalse(body.contains("store.setPhase(SelfRunStore.PHASE_BOOTSTRAP)"));
        assertFalse(body.contains("store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE)"));

        String store = storeSource();
        int atomic = store.indexOf("void resumeState(String requestedPhase, String statusValue)");
        int complete = store.indexOf("void complete(String statusValue)", atomic);
        assertTrue(atomic >= 0 && complete > atomic);
        String atomicBody = store.substring(atomic, complete);
        assertTrue(atomicBody.contains("resolvePausedResumePhase(pauseResumePhase(), !conversationUrl().isEmpty())"));
        assertTrue(atomicBody.contains("putBoolean(\"paused\", false)"));
        assertTrue(atomicBody.contains("putString(\"phase\", nextPhase)"));
        assertTrue(atomicBody.contains("putString(\"pauseResumePhase\", \"\")"));
        assertTrue(atomicBody.contains("putString(\"pauseCause\", \"\")"));
        assertEquals(1, count(atomicBody, "applyEditor("));
        assertEquals(1, count(atomicBody, "syncHistoryCritical()"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
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
