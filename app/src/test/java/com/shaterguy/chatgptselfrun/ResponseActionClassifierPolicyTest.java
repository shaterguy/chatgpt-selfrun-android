package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ResponseActionClassifierPolicyTest {
    @Test public void sendNeedsExplicitCurrentSemanticEvidence() {
        String script = ContinuationGuardDom.responseIdleCheck(
                "https://chatgpt.com/c/conversation123", "tok", "h", "c", "s");
        assertTrue(script.contains("tid==='send-button'"));
        assertTrue(script.contains("tid==='composer-submit-button'"));
        assertTrue(script.contains("aria"));
        assertTrue(script.contains("title"));
        assertTrue(script.contains("text"));
        assertTrue(script.contains("sends.length!==1"));
    }
}
