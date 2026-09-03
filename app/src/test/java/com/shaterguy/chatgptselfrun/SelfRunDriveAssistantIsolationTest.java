package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class SelfRunDriveAssistantIsolationTest {
    @Test public void assistantCompletionObservationIsIsolatedToDedicatedFallback() throws Exception {
        String fallback = source("TurnCompletionDomFallbackScript.java");
        String legacyDom = source("SelfRunDom.java") + source("SelfRunContinuationDom.java");
        assertTrue(fallback.contains("data-message-author-role"));
        assertTrue(fallback.contains("finalActionEvidence"));
        assertTrue(fallback.contains("dom_assistant_final_ui"));
        assertFalse(legacyDom.contains("data-message-author-role=\\\"assistant\\\""));
        assertFalse(legacyDom.contains("article[data-turn=\\\"assistant\\\"]"));
        for (String banned : new String[]{
                "readLatestSelfRunControl", "observeAssistant", "assistantSnapshot", "assistantBaselineKey",
                "ASSISTANT_BASELINE_WAIT", "PHASE_READ_NEXT_CONTROL", "CONTROL_FOUND", "CONTROL_MISSING"}) {
            assertFalse("legacy assistant completion observer remains: " + banned, legacyDom.contains(banned));
        }
    }

    @Test public void completedTurnRoutesFromDomToDriveSynchronization() throws Exception {
        String service = source("SelfRunService.java");
        String store = source("SelfRunStore.java");
        assertTrue(service.contains("isTurnCompletionCallback"));
        assertTrue(service.contains("store.beginPostDomDriveSync(token)"));
        assertTrue(service.contains("handler.post(this::authorizeAndRunDrive)"));
        assertTrue(store.contains("PHASE_POST_DOM_DRIVE_SYNC"));
        assertFalse(service.contains("PHASE_WAIT_DRIVE_COMMIT"));
        assertFalse(service.contains("PHASE_WAIT_INTERNAL_SEND"));
    }

    @Test public void workPreferencesStillComeFromDriveCompletionPayload() throws Exception {
        String store = source("SelfRunStore.java");
        assertTrue(store.contains("pendingDriveWorkProfile()"));
        assertTrue(store.contains("DriveSignalParser.workProfile(pendingDriveSignalRaw())"));
        String apply = section(store, "void applyDriveSignals", "void beginManualResumeOverride");
        assertTrue(apply.contains("MODE_WORK.equals(mode())?PHASE_APPLY_PREFS:PHASE_SEND_CONTINUE"));
        assertFalse(apply.contains("PHASE_WAIT_INTERNAL_SEND"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String section(String text, String start, String end) {
        int a = text.indexOf(start);
        int b = text.indexOf(end, a + start.length());
        assertTrue("missing start: " + start, a >= 0);
        assertTrue("missing end: " + end, b > a);
        return text.substring(a, b);
    }
}
