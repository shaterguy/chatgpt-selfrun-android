package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class RateLimitRecoveryFreshnessPolicyTest {
    @Test public void recoveryRequiresProofBeforeSubmissionResumes() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String rate = RateLimitAndStopPolicyTest.Source.between(service,
                "private void handleWebRateLimit", "private void onConversationProbeEvent");
        assertTrue(rate.contains("forceDirty(\"rate_limit\")"));
        assertTrue(rate.contains("enterConversationSync"));
        String sync = RateLimitAndStopPolicyTest.Source.between(service,
                "private void evaluateConversationSyncReadiness", "private void handleDriveFailure");
        assertTrue(sync.contains("RATE_LIMIT_RECOVERED"));
        assertTrue(sync.contains("proof.proven"));
    }
}
