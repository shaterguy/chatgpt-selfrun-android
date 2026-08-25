package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Regression coverage for the dev3 observer-to-Drive continuation path. */
public final class SelfRunDriveDev3PolicyTest {
    @Test public void driveIsSynchronizerAfterDomCompletionNotCompletionClock() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("PHASE_WAIT_TURN_COMPLETION"));
        assertTrue(service.contains("PHASE_POST_DOM_DRIVE_SYNC"));
        assertTrue(service.contains("observeTurnCompletion"));
        assertFalse(service.contains("PHASE_WAIT_DRIVE_COMMIT"));
        assertFalse(service.contains("PHASE_WAIT_INTERNAL_SEND"));
    }

    @Test public void noSignalTimeoutFailsClosedAndCursorMigrationIsExplicit() throws Exception {
        String store = source("SelfRunStore.java");
        String service = source("SelfRunService.java");
        String parser = source("DriveCommitParser.java");
        assertTrue(store.contains("DRIVE_SIGNAL_CURSOR_SCHEMA_PHYSICAL = 2"));
        assertTrue(store.contains("driveSignalCursorSchemaVersion"));
        assertFalse(store.contains("continueAfterPostDomDriveTimeout"));
        assertTrue(parser.contains("migrateCursor"));
        assertTrue(parser.contains("latestCanonical"));
        assertTrue(service.contains("DRIVE_SIGNAL_CURSOR_MIGRATION_UNRESOLVED"));
        assertTrue(service.contains("DRIVE_SIGNAL_CURSOR_OUT_OF_RANGE"));
        assertTrue(service.contains("POST_DOM_DRIVE_SYNC_TIMEOUT"));
        assertTrue(service.contains("action=pause_fail_closed"));
        assertTrue(service.contains("isDominantCanonicalControl"));
        assertFalse(service.contains("action=continue_current_profile"));
    }

    @Test public void driveCompletionPayloadCanOverrideProfileAndNextInput() throws Exception {
        String store = source("SelfRunStore.java");
        String apply = section(store, "void applyDriveSignals", "void beginManualResumeOverride");
        assertTrue(apply.contains("pendingDriveSignalRaw"));
        assertTrue(apply.contains("PHASE_APPLY_PREFS"));
        assertTrue(apply.contains("PHASE_SEND_CONTINUE"));
        assertTrue(store.contains("pendingNextInput()"));
        assertTrue(store.contains("pendingDriveWorkProfile()"));
    }

    @Test public void resumeDecisionNeverUsesLatestSignalType() throws Exception {
        String store = source("SelfRunStore.java");
        String resume = section(store, "void baselineManualResume", "static boolean canCaptureConversationUrl");
        assertTrue(resume.contains("driveSignalCursor"));
        assertTrue(resume.contains("PHASE_SEND_CONTINUE"));
        assertFalse(resume.contains("event.type"));
    }

    @Test public void recoveryIdleBaselineNeedsStopEvidenceForThisRunAndToken() throws Exception {
        String service = source("SelfRunService.java");
        String store = source("SelfRunStore.java");
        assertTrue(service.contains("turnObserverNeedsIdleBaseline=store!=null"));
        assertTrue(service.contains("store.turnObserverSawStop()"));
        String dom = source("SelfRunContinuationDom.java");
        assertTrue(dom.contains("stopSeenCallback"));
        assertTrue(store.contains("PHASE_WAIT_TURN_COMPLETION.equals(phase())"));
        assertTrue(store.contains("token.equals(turnObserverToken())"));
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
