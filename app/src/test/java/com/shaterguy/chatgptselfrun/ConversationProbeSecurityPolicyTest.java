package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ConversationProbeSecurityPolicyTest {
    @Test public void bridgeIsExactOriginOneWayAndNoSensitiveContent() {
        String js = ConversationSyncInstrumentation.documentStartScript();
        assertFalse(ConversationSyncInstrumentation.TRUSTED_ORIGINS.contains("*"));
        assertFalse(js.contains("ev.data"));
        assertFalse(js.contains("clone().text"));
        assertFalse(js.contains("innerText"));
        assertFalse(js.contains("textContent"));
        assertFalse(js.contains(".value"));
        assertFalse(js.contains("Authorization"));
        assertFalse(js.contains("cookie"));
        assertTrue(js.contains("prompt-textarea"));
        assertTrue(js.contains("bridge.postMessage"));
        assertFalse(js.contains("addJavascriptInterface"));
    }
}
