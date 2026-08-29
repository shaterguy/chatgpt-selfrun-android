package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class ChatWorkContinuationContractTest {
    @Test public void productionChatUsesRequestProfileWithoutUiReasoningMutation() throws Exception {
        String dom = source("SelfRunDom.java");
        String bridge = source("ChatReasoningOptionDom.java");
        assertTrue(dom.contains("ChatReasoningOptionDom.inline(chatReasoning, runId)"));
        assertFalse(dom.contains("ChatReasoningDom.inline(chatReasoning, runId)"));
        assertTrue(bridge.contains("__selfRunRequestProfileEngine"));
        assertTrue(bridge.contains("setChatReasoning"));
        assertTrue(bridge.contains("uiClicks:0"));
        assertFalse(bridge.contains("open-reasoning-sheet"));
        assertFalse(bridge.contains("open-advanced-control"));
        assertFalse(bridge.contains("new PointerEvent"));
    }

    @Test public void workPreferenceStagesAbsoluteTargetAndFailsClosed() throws Exception {
        String preference = source("WorkPreferenceDom.java");
        assertTrue(preference.contains("__selfRunRequestProfileEngine"));
        assertTrue(preference.contains("RequestProfileScript.setWorkModel"));
        assertTrue(preference.contains("RequestProfileScript.setWorkReasoning"));
        assertTrue(preference.contains("WORK_MODEL_READBACK_MISMATCH"));
        assertTrue(preference.contains("WORK_REASONING_READBACK_MISMATCH"));
        assertTrue(preference.contains("uiClicks:0"));
        assertFalse(preference.contains("open-work-mode-fallback"));
        assertFalse(preference.contains("SELECTION_TIMEOUT"));
    }

    @Test public void continuationSubmissionRequiresPositiveDomEvidenceBeforeWaitPhase() throws Exception {
        String service = source("SelfRunService.java");
        String continuation = source("SelfRunContinuationDom.java");
        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_WAIT_TURN_COMPLETION));
        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_APPLY_PREFS));
        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_APPLY_REASONING));
        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_SEND_CONTINUE));
        assertTrue(continuation.contains("writeMarker({state:'clicked'"));
        assertTrue(continuation.contains("continuationClickedVerification()"));
        assertTrue(continuation.contains("continuation submission evidence confirmed"));
        assertTrue(continuation.contains("continuation submission verification pending"));
        assertTrue(continuation.contains("dispatch=CONTINUE_CLICKED"));
        assertFalse(continuation.contains("return result('VERIFY_REQUIRED'"));
        assertFalse(continuation.contains("verifyDriveTurnSubmission"));
        assertFalse(service.contains("SelfRunContinuationDom.verifyDriveTurnSubmission"));
        assertTrue(service.contains("store.beginTurnCompletionWait"));
        String callbackRecovery = section(service, "private void scheduleContinuationCallbackDeadline", "private void recoverBootstrapSendCallback");
        assertTrue(callbackRecovery.contains("PHASE_WAIT_TURN_COMPLETION"));
        assertTrue(callbackRecovery.contains("TURN_OBSERVER_HEALTHCHECK_MS"));
        assertFalse(callbackRecovery.contains("restoreCanonical()"));
    }

    @Test public void responseCompletionUsesObserverNotShortButtonPolling() throws Exception {
        String service = source("SelfRunService.java");
        String continuation = source("SelfRunContinuationDom.java");
        assertTrue(service.contains("PHASE_WAIT_TURN_COMPLETION"));
        assertTrue(service.contains("observeTurnCompletion"));
        assertFalse(service.contains("SelfRunContinuationDom.buttonState("));
        assertFalse(service.contains("scheduleDrivePoll"));
        assertFalse(continuation.contains("setInterval"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String section(String text, String start, String end) {
        int a = text.indexOf(start);
        int b = text.indexOf(end, a + start.length());
        assertTrue("missing start: " + start, a >= 0);
        assertTrue("missing end: " + end, b > a);
        return text.substring(a, b);
    }
}
