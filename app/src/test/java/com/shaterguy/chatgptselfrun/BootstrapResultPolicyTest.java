package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class BootstrapResultPolicyTest {
    @Test public void callbackPolicyHasFiniteClassificationAndSafeDiagnostics() throws Exception {
        String source = read("app/src/main/java/com/shaterguy/chatgptselfrun/BootstrapResultPolicy.java",
                "src/main/java/com/shaterguy/chatgptselfrun/BootstrapResultPolicy.java");
        assertTrue(source.contains("CHAT_BOOTSTRAP_CALLBACK_INVALID"));
        assertTrue(source.contains("CHAT_BOOTSTRAP_SCRIPT_ERROR"));
        assertTrue(source.contains("CHAT_BOOTSTRAP_UNKNOWN_STATUS"));
        assertTrue(source.contains("CHAT_BOOTSTRAP_TIMEOUT"));
        assertTrue(source.contains("new JSONTokener(raw).nextValue()"));
        assertTrue(source.contains("NON_FATAL.contains(parsed.status)"));
        assertTrue(source.contains("status.startsWith(\"CHAT_REASONING_\")"));
        assertTrue(source.contains("status.startsWith(\"CHAT_BOOTSTRAP_\")"));
        assertTrue(source.contains("compactDiagnostics"));
    }

    @Test public void newConversationUiFailuresRequestCanonicalReconnect() {
        String[] reconnectable = {
                "CHAT_REASONING_TRIGGER_NOT_FOUND",
                "CHAT_REASONING_SLIDER_NOT_FOUND",
                "CHAT_REASONING_ADVANCED_CONTROL_NOT_FOUND",
                "CHAT_REASONING_OPTION_UNAVAILABLE",
                "CHAT_REASONING_READBACK_MISMATCH",
                "CHAT_REASONING_MENU_CLOSE_FAILED",
                "CHAT_BOOTSTRAP_MODE_CONTROL_NOT_FOUND",
                "CHAT_BOOTSTRAP_MODE_READBACK_FAILED",
                "CHAT_BOOTSTRAP_COMPOSER_NOT_FOUND"
        };
        for (String status : reconnectable) {
            BootstrapResultPolicy.Parsed parsed = BootstrapResultPolicy.parse(
                    "{\"status\":\"" + status + "\",\"detail\":\"retry\",\"diagnostics\":{}}");
            assertTrue(parsed.valid);
            assertTrue(BootstrapResultPolicy.requiresCanonicalReconnect(status));
            assertEquals("TARGET_ERROR", parsed.status);
            assertEquals(status, parsed.result.optJSONObject("diagnostics").optString("reconnectCause"));
            assertEquals("", BootstrapResultPolicy.fatalStatus(parsed, 10_000L, 9_000L));
        }
    }

    @Test public void reconnectPolicyDoesNotMaskConversationTransitionFailureOrDeadline() {
        BootstrapResultPolicy.Parsed newChatFailure = BootstrapResultPolicy.parse(
                "{\"status\":\"CHAT_BOOTSTRAP_NEW_CHAT_FAILED\",\"detail\":\"failed\"}");
        assertEquals("CHAT_BOOTSTRAP_NEW_CHAT_FAILED", newChatFailure.status);
        assertFalse(BootstrapResultPolicy.requiresCanonicalReconnect(newChatFailure.status));
        assertEquals("CHAT_BOOTSTRAP_NEW_CHAT_FAILED",
                BootstrapResultPolicy.fatalStatus(newChatFailure, 10_000L, 9_000L));

        BootstrapResultPolicy.Parsed reconnectable = BootstrapResultPolicy.parse(
                "{\"status\":\"CHAT_REASONING_OPTION_UNAVAILABLE\",\"detail\":\"failed\"}");
        assertEquals(BootstrapResultPolicy.TIMEOUT,
                BootstrapResultPolicy.fatalStatus(reconnectable, 10_000L, 10_000L));
    }

    @Test public void serviceTargetErrorRecoveryUsesCanonicalEntryOnlyBeforeConversation() throws Exception {
        String service = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        assertTrue(service.contains("else if(!isContinuationDiagnosticPhase(phase))restoreCanonical()"));
        assertTrue(service.contains("private void restoreCanonical()"));
        assertTrue(service.contains("webView.loadUrl(target)"));
        assertTrue(service.contains("return store.conversationUrl().isEmpty() ? store.projectUrl() : store.conversationUrl()"));
        assertTrue(service.contains("if(SelfRunRolloverPolicy.knownConversation(store.conversationUrl()))"));
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
