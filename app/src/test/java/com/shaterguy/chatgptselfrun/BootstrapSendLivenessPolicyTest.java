package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class BootstrapSendLivenessPolicyTest {
    @Test public void bootstrapSendCallbackUsesExistingFiveSecondGuard() {
        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_BOOTSTRAP_SEND));
        assertEquals(5_000L, SelfRunService.CONTINUATION_CALLBACK_TIMEOUT_MS);
        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_WAIT_TURN_COMPLETION));
    }

    @Test public void persistentSixtySecondDeadlineFailsClosed() {
        assertTrue(SelfRunService.bootstrapSendTimedOut(0L, 1L));
        assertTrue(SelfRunService.bootstrapSendTimedOut(-1L, 1L));
        assertTrue(SelfRunService.bootstrapSendTimedOut(2L, 1L));
        assertTrue(SelfRunService.bootstrapSendTimedOut(1L, 0L));
        assertFalse(SelfRunService.bootstrapSendTimedOut(1_000L, 60_999L));
        assertTrue(SelfRunService.bootstrapSendTimedOut(1_000L, 61_000L));
        assertEquals(60_000L, SelfRunService.BOOTSTRAP_SEND_MAX_WAIT_MS);
    }

    @Test public void callbackRecoveryAllowsThreeAndRejectsFourth() {
        assertFalse(SelfRunService.bootstrapSendCallbackRecoveryExhausted(0));
        assertFalse(SelfRunService.bootstrapSendCallbackRecoveryExhausted(1));
        assertFalse(SelfRunService.bootstrapSendCallbackRecoveryExhausted(2));
        assertTrue(SelfRunService.bootstrapSendCallbackRecoveryExhausted(3));
        assertEquals(3, SelfRunService.BOOTSTRAP_SEND_MAX_CALLBACK_RECOVERIES);
        assertTrue(SelfRunService.BOOTSTRAP_SEND_POLL_MS >= 750L);
        assertTrue(SelfRunService.BOOTSTRAP_SEND_POLL_MS <= 1_200L);
    }

    @Test public void timeoutRecoveryPreservesPreparedCommandAndObserverIdentity() throws Exception {
        String service = source("SelfRunService.java");
        String recovery = section(service, "private void recoverBootstrapSendCallback()",
                "private void failBootstrapSubmissionTimeout");
        assertTrue(recovery.contains("callbackTimeoutDetail(SelfRunStore.PHASE_BOOTSTRAP_SEND)"));
        assertTrue(recovery.contains("bootstrapSendCallbackRecoveries++"));
        assertTrue(recovery.contains("scheduleWeb(BOOTSTRAP_SEND_POLL_MS)"));
        assertFalse(recovery.contains("beginCommandAttempt"));
        assertFalse(recovery.contains("prepareTurnObserver"));
        assertFalse(recovery.contains("activeCommandPrompt"));
        assertFalse(recovery.contains("commandMarkerId"));
        assertFalse(recovery.contains("turnObserverToken"));
        assertFalse(recovery.contains("restoreCanonical"));
    }

    @Test public void timeoutPausesAndNormalClickedPathsStillEnterWait() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("CHAT_BOOTSTRAP_SUBMISSION_TIMEOUT"));
        assertTrue(service.contains("enterPreservedPause(CHAT_BOOTSTRAP_SUBMISSION_TIMEOUT"));
        assertTrue(service.contains("NotificationHelper.notifyUser(this,\"확인 필요\",store.status())"));
        assertTrue(service.contains("\"BOOTSTRAP_CLICKED\".equals(status)||\"SUBMISSION_CONFIRMED\".equals(status)||\"VERIFY_REQUIRED\".equals(status)"));
        assertTrue(service.contains("store.bootstrapSubmissionConfirmed(token)"));
        assertTrue(service.contains("turnObserverNeedsIdleBaseline || store.turnObserverSawStop()"));
        assertTrue(service.contains("TURN_COMPLETION_STABILITY_MS = 5_000L"));
    }

    @Test public void timeoutDiagnosticsContainNoPromptOrUrlMaterial() throws Exception {
        String service = source("SelfRunService.java");
        String failure = section(service, "private void failBootstrapSubmissionTimeout",
                "private void failBootstrap(String code");
        assertTrue(failure.contains("phase=bootstrap_send"));
        assertFalse(failure.contains("activeCommandPrompt"));
        assertFalse(failure.contains("projectUrl"));
        assertFalse(failure.contains("canonicalUrl"));
        assertFalse(failure.contains("detail"));
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
