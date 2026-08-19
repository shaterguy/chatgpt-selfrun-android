package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class WorkAndChatDev8ParityPolicyTest {
    @Test public void bothModesShareNativeFreshnessAndIdleGate() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String guard = RateLimitAndStopPolicyTest.Source.between(service,
                "private void guardElapsed", "private void ensureWebView");
        assertTrue(guard.contains("MODE_WORK"));
        assertTrue(guard.contains("PHASE_APPLY_PREFS"));
        assertTrue(guard.contains("PHASE_SEND_CONTINUE"));
        String sync = RateLimitAndStopPolicyTest.Source.between(service,
                "private void evaluateConversationSyncReadiness", "private void handleDriveFailure");
        assertTrue(sync.contains("evaluateResponseIdleCheck"));
        assertTrue(service.contains("WorkPreferenceDom.modelForConversation"));
        assertTrue(service.contains("WorkPreferenceDom.reasoningForConversation"));
        assertTrue(service.contains("ContinuationGuardDom.prepareDriveTurn"));
    }
}
