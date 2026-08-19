package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ProbeDirtyInvalidationPolicyTest {
    @Test public void dirtyProbeInvalidatesNativeFreshnessAndResyncsContinuation() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String listener = RateLimitAndStopPolicyTest.Source.between(service,
                "private void onConversationProbeEvent", "private void postWebCallback");
        assertTrue(listener.contains("conversationProbe.isDirty()"));
        assertTrue(listener.contains("invalidateConversationFreshness()"));
        assertTrue(listener.contains("isContinuationPhase(phase)"));
        assertTrue(listener.contains("enterConversationSync"));
    }
}
