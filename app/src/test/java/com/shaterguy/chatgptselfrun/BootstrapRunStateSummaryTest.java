package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class BootstrapRunStateSummaryTest {
    @Test public void runStateSourceStoresDeadlineReasoningEvidenceAndHistory() throws Exception {
        String source = read("app/src/main/java/com/shaterguy/chatgptselfrun/BootstrapRunStateStore.java",
                "src/main/java/com/shaterguy/chatgptselfrun/BootstrapRunStateStore.java");
        assertTrue(source.contains("BOOTSTRAP_TIMEOUT_MS = 60_000L"));
        assertTrue(source.contains("REASONING_APPLIED"));
        assertTrue(source.contains("bootstrapDeadlineAt"));
        assertTrue(source.contains("markReasoningApplied"));
        assertTrue(source.contains("markBootstrapFailed"));
        assertTrue(source.contains("appendHistory"));
        assertTrue(source.contains("chatReasoningRequested"));
        assertTrue(source.contains("chatReasoningVerified"));
        assertTrue(source.contains("MAX_RUNS = 120"));
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
