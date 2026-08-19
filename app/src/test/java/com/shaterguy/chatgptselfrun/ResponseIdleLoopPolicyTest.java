package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ResponseIdleLoopPolicyTest {
    @Test public void stopAndUnknownNeverReachClickPath() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String eval = RateLimitAndStopPolicyTest.Source.between(service,
                "private void evaluate(String phase, String script)", "private void recordContinuationWait");
        assertTrue(eval.contains("RESPONSE_ACTIVE_WAIT_10S"));
        assertTrue(eval.contains("SUBMIT_BLOCKED_STOP"));
        assertTrue(eval.contains("ACTION_UNKNOWN"));
        assertFalse(eval.contains("clickStop"));
    }

    @Test public void responseIdleIsCheckedBeforeWorkPreferenceContinuation() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String sync = RateLimitAndStopPolicyTest.Source.between(service,
                "private void evaluateConversationSyncReadiness", "private void handleDriveFailure");
        assertTrue(sync.contains("evaluateResponseIdleCheck"));
        assertTrue(sync.contains("store.finishConversationSync()"));
        assertTrue(sync.indexOf("RESPONSE_IDLE_CONFIRMED") < sync.indexOf("store.finishConversationSync()"));
    }
}
