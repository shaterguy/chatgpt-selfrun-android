package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BootstrapModeCanonicalizationTest {
    @Test public void lowercaseWorkRequestRemainsWork() {
        String script = BootstrapModeDom.inline("work", "SR-test");
        assertTrue(script.contains("const requestedMode=\"work\";"));
        assertFalse(script.contains("const requestedMode=\"chat\";"));
    }

    @Test public void internalWorkConstantRemainsWork() {
        String script = BootstrapModeDom.inline(SelfRunStore.MODE_WORK, "SR-test");
        assertTrue(script.contains("const requestedMode=\"work\";"));
        assertFalse(script.contains("const requestedMode=\"chat\";"));
    }

    @Test public void lowercaseChatRequestRemainsChat() {
        String script = BootstrapModeDom.inline("chat", "SR-test");
        assertTrue(script.contains("const requestedMode=\"chat\";"));
        assertFalse(script.contains("const requestedMode=\"work\";"));
    }
}
