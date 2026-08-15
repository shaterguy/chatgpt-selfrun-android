package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriveSignalParserTest {
    private static final String JOB = "SR-20260813-220315-A1B2C3";

    @Test public void parsesAckAndCompletionTogether() {
        String text = "[2026.08.13 | 22:03:19] [SELF_RUN_COMMAND_RECEIVED " + JOB + "]\n"
                + "[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB + "]\n";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 0);
        assertEquals(2, scan.totalCount);
        assertEquals(DriveSignalParser.Type.COMMAND_RECEIVED, scan.unseen.get(0).type);
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, scan.unseen.get(1).type);
    }

    @Test public void workCompletionCarriesNextTurnProfile() {
        String raw = "[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB + " MODEL=Sol REASONING=ULTRA]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(raw, JOB, 0);
        assertEquals(1, scan.totalCount);
        DriveSignalParser.WorkProfile profile = DriveSignalParser.workProfile(scan.latest.raw);
        assertTrue(profile.valid);
        assertEquals("sol", profile.model);
        assertEquals("ultra", profile.reasoning);
    }

    @Test public void missingOrMalformedWorkProfileStillCountsCompletionForRewrite() {
        String bare = "[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB + "]";
        String malformed = "[2026.08.13 | 22:10:42] [SELF_RUN_TURN_COMPLETED " + JOB + " MODEL=sol]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(bare + "\n" + malformed, JOB, 0);
        assertEquals(2, scan.totalCount);
        assertFalse(DriveSignalParser.workProfile(scan.unseen.get(0).raw).valid);
        assertFalse(DriveSignalParser.workProfile(scan.unseen.get(1).raw).valid);
    }

    @Test public void canonicalWorkProfileRestrictionsAreEnforced() {
        assertTrue(profile("sol", "high").valid);
        assertTrue(profile("sol", "ultra").valid);
        assertTrue(profile("terra", "max").valid);
        assertTrue(profile("luna", "max").valid);
        assertFalse(profile("terra", "ultra").valid);
        assertFalse(profile("luna", "high").valid);
        assertFalse(profile("luna", "ultra").valid);
    }

    @Test public void ignoresLegacyMetadataWrongJobAndMalformedNonCompletionLines() {
        String text = "[SELF_RUN_DRIVE_COMMIT_V1]\nEVENT_SEQ=9\n[/SELF_RUN_DRIVE_COMMIT_V1]\n"
                + "[2026.08.13 | 22:10:00] [SELF_RUN_DONE SR-OTHER]\n"
                + "[2026.08.13 | 22:19:05] [SELF_RUN_COMMAND_RECEIVED " + JOB + " EXTRA=x]\n"
                + "[2026.08.13 | 22:20:05] [SELF_RUN_PAUSED " + JOB + "]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 0);
        assertEquals(1, scan.totalCount);
        assertEquals(DriveSignalParser.Type.PAUSED, scan.latest.type);
    }

    @Test public void cursorOnlyReturnsAppendedSignals() {
        String text = "[2026.08.13 | 22:03:19] [SELF_RUN_COMMAND_RECEIVED " + JOB + "]\n"
                + "[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB + "]\n"
                + "[2026.08.13 | 22:15:10] [SELF_RUN_USER_ACTION_REQUIRED " + JOB + "]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 2);
        assertEquals(1, scan.unseen.size());
        assertEquals(3, scan.unseen.get(0).cursor);
    }

    @Test public void timestampOrderNeverRejectsProgress() {
        String text = "[2026.08.13 | 22:20:05] [SELF_RUN_COMMAND_RECEIVED " + JOB + "]\n"
                + "[2026.08.13 | 22:10:05] [SELF_RUN_TURN_COMPLETED " + JOB + "]";
        assertEquals(2, DriveSignalParser.scan(text, JOB, 0).totalCount);
    }

    @Test public void impossibleRecoveryCursorRebaselinesWithoutReplayingHistory() {
        String text = "[2026.08.13 | 22:03:19] [SELF_RUN_COMMAND_RECEIVED " + JOB + "]\n"
                + "[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB + "]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, Integer.MAX_VALUE);
        assertTrue(scan.cursorRebased);
        assertTrue(scan.unseen.isEmpty());
        assertEquals(2, scan.totalCount);
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, scan.latest.type);
    }

    private static DriveSignalParser.WorkProfile profile(String model, String reasoning) {
        return DriveSignalParser.workProfile("[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB
                + " MODEL=" + model + " REASONING=" + reasoning + "]");
    }
}
