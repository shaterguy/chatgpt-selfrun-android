package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChatWorkContinuationContractTest {
    @Test public void productionChatUsesAdvancedMenuWithoutSliderMutation() throws Exception {
        String dom = src("SelfRunDom.java");
        String menu = src("ChatReasoningOptionDom.java");
        assertTrue(dom.contains("ChatReasoningOptionDom.inline(chatReasoning, runId)"));
        assertFalse(dom.contains("ChatReasoningDom.inline(chatReasoning, runId)"));
        assertTrue(menu.contains("open-reasoning-sheet"));
        assertTrue(menu.contains("open-advanced-control"));
        assertTrue(menu.contains("open-reasoning-menu"));
        assertTrue(menu.contains("sliderObserved"));
        assertFalse(menu.contains("positive-slider-fallback"));
        assertFalse(menu.contains("set-slider"));
        assertFalse(menu.contains("ArrowRight"));
        assertFalse(menu.contains("new PointerEvent"));
    }

    @Test public void workPreferenceWaitsAreFiniteAndTerminal() throws Exception {
        String preference = src("WorkPreferenceDom.java");
        String service = src("SelfRunService.java");
        assertTrue(preference.contains("calibratedTargetValid"));
        assertTrue(preference.contains("__wpShowAdvancedLabel"));
        assertTrue(preference.contains("open-advanced-control"));
        assertTrue(preference.contains("close-current-match"));
        assertTrue(preference.contains("data-animated-slider-trigger"));
        assertTrue(preference.contains("__wpMouse(e,'pointerdown'"));
        assertTrue(preference.contains("__wpTimeoutMs=20000"));
        assertTrue(preference.contains("'SELECTION_TIMEOUT'"));
        assertTrue(preference.contains("'READBACK_MISMATCH'"));
        assertTrue(service.contains("isWorkPreferenceFailureStatus"));
        assertTrue(service.contains("WORK_MODEL_SELECTION_TIMEOUT"));
        assertTrue(service.contains("WORK_MODEL_READBACK_MISMATCH"));
        assertTrue(service.contains("WORK_REASONING_SELECTION_TIMEOUT"));
        assertTrue(service.contains("WORK_REASONING_READBACK_MISMATCH"));
        assertTrue(service.contains("WORK_PREFERENCE_FAILURE"));
    }

    @Test public void normalContinuationDomCallbacksHaveFiniteRecoveryWithoutBlindResubmit() throws Exception {
        String service = src("SelfRunService.java");
        String continuation = src("SelfRunContinuationDom.java");

        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_WAIT_INTERNAL_SEND));
        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_APPLY_PREFS));
        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_APPLY_REASONING));
        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_SEND_CONTINUE));
        assertFalse(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_BOOTSTRAP));
        assertTrue(service.contains("CONTINUATION_CALLBACK_TIMEOUT_MS = 5_000L"));
        assertTrue(service.contains("scheduleContinuationCallbackDeadline"));
        assertTrue(service.contains("domInFlight=false;webEvaluationId++"));
        assertTrue(service.contains("SelfRunWebDiagnostics.callbackTimeoutDetail(phase)"));
        assertTrue(service.contains("restoreCanonical();"));

        assertTrue(continuation.contains("writeMarker({state:'clicked'"));
        assertTrue(continuation.contains("if(m.state==='clicked')return result('VERIFY_REQUIRED'"));
        assertTrue(continuation.contains("verifyDriveTurnSubmission"));
        assertTrue(service.contains("if(\"VERIFY_REQUIRED\".equals(status)){String prompt=continuationPrompt();evaluate(phase,SelfRunContinuationDom.verifyDriveTurnSubmission"));
    }

    @Test public void waitInternalSendUnknownRecoveryIsBoundedWithoutChangingIdleContract() {
        assertFalse(SelfRunService.shouldRecoverWaitInternalSend(
                SelfRunContinuationDom.UNKNOWN, 1_000L, 5_999L));
        assertTrue(SelfRunService.shouldRecoverWaitInternalSend(
                SelfRunContinuationDom.UNKNOWN, 1_000L, 6_000L));
        assertFalse(SelfRunService.shouldRecoverWaitInternalSend(
                SelfRunContinuationDom.SEND_DISABLED, 1_000L, 10_000L));
        assertFalse(SelfRunService.shouldRecoverWaitInternalSend(
                SelfRunContinuationDom.COMPOSER_IDLE, 1_000L, 10_000L));
    }

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
