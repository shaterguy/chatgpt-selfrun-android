package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class NoAssistantBodyFreshnessPolicyTest {
    @Test public void dev8FreshnessDoesNotReadAssistantBody() throws Exception {
        String probe = RateLimitAndStopPolicyTest.Source.read("ConversationSyncInstrumentation.java");
        String guard = RateLimitAndStopPolicyTest.Source.read("ContinuationGuardDom.java");
        assertFalse(probe.contains("innerText"));
        assertFalse(probe.contains("textContent"));
        assertFalse(probe.contains("assistant"));
        assertFalse(guard.contains("data-message-author-role=\\\"assistant\\\""));
    }
}
