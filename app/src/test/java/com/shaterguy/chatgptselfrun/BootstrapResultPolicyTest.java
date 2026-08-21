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

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
