package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class NoAssistantBodyFreshnessPolicyTest {
    @Test public void dev8FreshnessProbeDoesNotReadAssistantOrUserBodyText() {
        String js = ConversationSyncInstrumentation.documentStartScript();
        assertFalse(js.contains("innerText"));
        assertFalse(js.contains("textContent"));
        assertFalse(js.contains("assistant"));
        assertFalse(js.contains("user message"));
        String guard = ContinuationGuardDom.freshnessReady(
                "https://chatgpt.com/c/conversation123", "tok", "h", "c", "s");
        assertFalse(guard.contains("data-message-author-role=\\\"assistant\\\""));
    }
}
