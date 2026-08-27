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
            assertTrue(status, BootstrapResultPolicy.requiresCanonicalReconnect(status));
        }
        assertFalse(BootstrapResultPolicy.requiresCanonicalReconnect("CHAT_BOOTSTRAP_NEW_CHAT_FAILED"));
        assertFalse(BootstrapResultPolicy.requiresCanonicalReconnect(BootstrapResultPolicy.TIMEOUT));
        assertFalse(BootstrapResultPolicy.requiresCanonicalReconnect(BootstrapResultPolicy.STATE_PERSIST_FAILED));
    }

    @Test public void reconnectParsingPreservesCauseAndDeadlineRemainsDominant() throws Exception {
        String source = read("app/src/main/java/com/shaterguy/chatgptselfrun/BootstrapResultPolicy.java",
                "src/main/java/com/shaterguy/chatgptselfrun/BootstrapResultPolicy.java");
        assertTrue(source.contains("diagnostics.put(\"reconnectCause\", status)"));
        assertTrue(source.contains("return new Parsed(result, \"TARGET_ERROR\""));
        int deadline = source.indexOf("if (deadlineAt > 0L && now >= deadlineAt) return TIMEOUT;");
        int nonFatal = source.indexOf("return NON_FATAL.contains(parsed.status) ? \"\" : UNKNOWN_STATUS;");
        assertTrue(deadline >= 0 && nonFatal > deadline);
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
