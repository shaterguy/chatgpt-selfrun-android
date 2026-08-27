package com.shaterguy.chatgptselfrun;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class BootstrapCanonicalReconnectAndroidTest {
    @Test public void reconnectableUiFailureMapsToExistingCanonicalRecoverySignal() {
        BootstrapResultPolicy.Parsed parsed = BootstrapResultPolicy.parse(
                "{\"status\":\"CHAT_REASONING_OPTION_UNAVAILABLE\",\"detail\":\"retry\",\"diagnostics\":{}}");

        assertTrue(parsed.valid);
        assertEquals("TARGET_ERROR", parsed.status);
        assertEquals("CHAT_REASONING_OPTION_UNAVAILABLE",
                parsed.result.optJSONObject("diagnostics").optString("reconnectCause"));
        assertEquals("", BootstrapResultPolicy.fatalStatus(parsed, 10_000L, 9_000L));
        assertEquals(BootstrapResultPolicy.TIMEOUT,
                BootstrapResultPolicy.fatalStatus(parsed, 10_000L, 10_000L));
    }

    @Test public void conversationTransitionFailureRemainsFailClosed() {
        BootstrapResultPolicy.Parsed parsed = BootstrapResultPolicy.parse(
                "{\"status\":\"CHAT_BOOTSTRAP_NEW_CHAT_FAILED\",\"detail\":\"failed\"}");

        assertTrue(parsed.valid);
        assertEquals("CHAT_BOOTSTRAP_NEW_CHAT_FAILED", parsed.status);
        assertFalse(BootstrapResultPolicy.requiresCanonicalReconnect(parsed.status));
        assertEquals("CHAT_BOOTSTRAP_NEW_CHAT_FAILED",
                BootstrapResultPolicy.fatalStatus(parsed, 10_000L, 9_000L));
    }
}
