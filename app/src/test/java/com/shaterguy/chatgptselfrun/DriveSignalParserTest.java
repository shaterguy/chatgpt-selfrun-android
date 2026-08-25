package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriveSignalParserTest {
    private static final String JOB = "SR-20260813-220315-A1B2C3";

    @Test public void retiredAckOnlyPreservesCursorAndNeverBecomesAnEvent() {
        String text = "[2026.08.13 | 22:03:19] [SELF_RUN_COMMAND_RECEIVED " + JOB + "]\n"
                + "[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB + "]\n";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 0, SelfRunStore.MODE_CHAT);
        assertEquals(2, scan.totalCount);
        assertEquals(1, scan.unseen.size());
        assertEquals(2, scan.unseen.get(0).cursor);
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, scan.unseen.get(0).type);
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, scan.latest.type);
    }

    @Test public void installedCursorAfterRetiredAckStillReceivesNextCompletionOnce() {
        String text = "[2026.08.13 | 22:03:19] [SELF_RUN_COMMAND_RECEIVED " + JOB + "]\n"
                + "[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB + "]\n";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 1, SelfRunStore.MODE_CHAT);
        assertEquals(1, scan.unseen.size());
        assertEquals(2, scan.unseen.get(0).cursor);
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, scan.unseen.get(0).type);
    }

    @Test public void retiredAckAloneHasNoLatestControlSignal() {
        String text = "[2026.08.13 | 22:03:19] [SELF_RUN_COMMAND_RECEIVED " + JOB + "]\n";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 0, SelfRunStore.MODE_CHAT);
        assertEquals(1, scan.totalCount);
        assertTrue(scan.unseen.isEmpty());
        assertNull(scan.latest);
    }

    @Test public void chatCompletionGrammarRemainsBareOnly() {
        String extended = "[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB + " MODEL=sol REASONING=xhigh]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(extended, JOB, 0, SelfRunStore.MODE_CHAT);
        assertEquals(1, scan.totalCount);
        assertTrue(scan.unseen.isEmpty());
        assertNull(scan.latest);
    }

    @Test public void workCompletionCarriesNextTurnProfile() {
        String raw = "[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB + " MODEL=Sol REASONING=ULTRA]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(raw, JOB, 0, SelfRunStore.MODE_WORK);
        assertEquals(1, scan.totalCount);
        DriveSignalParser.WorkProfile profile = DriveSignalParser.workProfile(scan.latest.raw);
        assertTrue(profile.valid);
        assertEquals("sol", profile.model);
        assertEquals("ultra", profile.reasoning);
    }

    @Test public void missingOrMalformedWorkProfileStillCountsCompletionForRewrite() {
        String bare = "[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB + "]";
        String malformed = "[2026.08.13 | 22:10:42] [SELF_RUN_TURN_COMPLETED " + JOB + " MODEL=sol]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(bare + "\n" + malformed, JOB, 0, SelfRunStore.MODE_WORK);
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
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 0, SelfRunStore.MODE_CHAT);
        assertEquals(6, scan.totalCount);
        assertEquals(DriveSignalParser.Type.PAUSED, scan.latest.type);
        assertEquals(6, scan.latest.cursor);
    }

    @Test public void cursorOnlyReturnsAppendedSignals() {
        String text = "[2026.08.13 | 22:03:19] [SELF_RUN_COMMAND_RECEIVED " + JOB + "]\n"
                + "[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB + "]\n"
                + "[2026.08.13 | 22:15:10] [SELF_RUN_USER_ACTION_REQUIRED " + JOB + "]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 2, SelfRunStore.MODE_CHAT);
        assertEquals(1, scan.unseen.size());
        assertEquals(3, scan.unseen.get(0).cursor);
    }

    @Test public void timestampOrderNeverRejectsProgress() {
        String text = "[2026.08.13 | 22:20:05] [SELF_RUN_COMMAND_RECEIVED " + JOB + "]\n"
                + "[2026.08.13 | 22:10:05] [SELF_RUN_TURN_COMPLETED " + JOB + "]";
        assertEquals(2, DriveSignalParser.scan(text, JOB, 0, SelfRunStore.MODE_CHAT).totalCount);
    }

    @Test public void impossibleRecoveryCursorRebaselinesWithoutReplayingHistory() {
        String text = "[2026.08.13 | 22:03:19] [SELF_RUN_COMMAND_RECEIVED " + JOB + "]\n"
                + "[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB + "]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, Integer.MAX_VALUE, SelfRunStore.MODE_CHAT);
        assertTrue(scan.cursorRebased);
        assertTrue(scan.unseen.isEmpty());
        assertEquals(2, scan.totalCount);
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, scan.latest.type);
    }


    @Test public void actualIncidentKeepsPhysicalCursorSlotsAndDoneAtNine() {
        String text = "[2026.08.24 | 23:04:44] [SELF_RUN_TURN_COMPLETED " + JOB + "]\n"
                + "[2026.08.24 | 23:20:07] [SELF_RUN_TURN_COMPLETED " + JOB + "]\n"
                + "[2026.08.25 | 00:38:14] [SELF_RUN_TURN_COMPLETED " + JOB + "\n"
                + "[2026.08.25 | 00:52:43] [SELF_RUN_TURN_COMPLETED " + JOB + "]\n"
                + "[2026.08.25 | 00:52:43] [SELF_RUN_TURN_COMPLETED " + JOB + "]]\n"
                + "[2026.08.25 | 01:23:53] [SELF_RUN_TURN_COMPLETED " + JOB + "]\n"
                + "[2026.08.25 | 02:52:06] [SELF_RUN_TURN_COMPLETED " + JOB + "\n"
                + "[2026.08.25 | 02:56:25] [SELF_RUN_DONE " + JOB + "]]\n"
                + "[2026.08.25 | 02:57:04] [SELF_RUN_DONE " + JOB + "]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 5, SelfRunStore.MODE_CHAT);
        assertEquals(9, scan.totalCount);
        assertEquals(2, scan.unseen.size());
        assertNotNull(scan.latestCanonical);
        assertEquals(DriveSignalParser.Type.DONE, scan.latestCanonical.type);
        assertEquals(9, scan.latestCanonical.cursor);
    }

    @Test public void legacyCursorMigrationPrefersExactRawThenUniqueMalformedIdentity() {
        String exactRaw = "[2026.08.25 | 00:52:43] [SELF_RUN_TURN_COMPLETED " + JOB + "]";
        String text = "junk\n"
                + "[2026.08.25 | 00:38:14] [SELF_RUN_TURN_COMPLETED " + JOB + "\n"
                + exactRaw + "\n"
                + "[2026.08.25 | 02:52:06] [SELF_RUN_TURN_COMPLETED " + JOB;
        DriveSignalParser.CursorMigration exact = DriveSignalParser.migrateCursor(
                text, JOB, 2, exactRaw, "2026.08.25 | 00:52:43", "TURN_COMPLETED");
        assertTrue(exact.resolved);
        assertEquals(3, exact.cursor);
        assertEquals("EXACT_RAW", exact.method);

        String formerlyValid = "[2026.08.25 | 02:52:06] [SELF_RUN_TURN_COMPLETED " + JOB + "]";
        DriveSignalParser.CursorMigration fallback = DriveSignalParser.migrateCursor(
                text, JOB, 3, formerlyValid, "2026.08.25 | 02:52:06", "TURN_COMPLETED");
        assertTrue(fallback.resolved);
        assertEquals(4, fallback.cursor);
        assertEquals("IDENTITY", fallback.method);
    }

    @Test public void ambiguousLegacyIdentityFailsClosedInsteadOfGuessing() {
        String timestamp = "2026.08.25 | 02:52:06";
        String formerlyValid = "[" + timestamp + "] [SELF_RUN_TURN_COMPLETED " + JOB + "]";
        String text = "[" + timestamp + "] [SELF_RUN_TURN_COMPLETED " + JOB + "\n"
                + "[" + timestamp + "] [SELF_RUN_TURN_COMPLETED " + JOB + "]]";
        DriveSignalParser.CursorMigration migration = DriveSignalParser.migrateCursor(
                text, JOB, 1, formerlyValid, timestamp, "TURN_COMPLETED");
        assertFalse(migration.resolved);
        assertEquals("UNRESOLVED", migration.method);
    }

    @Test public void latestCanonicalDoesNotResurrectOlderPause() {
        String text = "[2026.08.25 | 01:00:00] [SELF_RUN_PAUSED " + JOB + "]\n"
                + "[2026.08.25 | 01:01:00] [SELF_RUN_DONE " + JOB + "]]\n"
                + "[2026.08.25 | 01:02:00] [SELF_RUN_TURN_COMPLETED " + JOB + "]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 3, SelfRunStore.MODE_CHAT);
        assertTrue(scan.unseen.isEmpty());
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, scan.latestCanonical.type);
        assertEquals(3, scan.latestCanonical.cursor);
    }

    @Test public void consumedDoneStillRemainsLatestCanonicalForDominance() {
        String text = "[2026.08.25 | 02:57:04] [SELF_RUN_DONE " + JOB + "]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 1, SelfRunStore.MODE_CHAT);
        assertTrue(scan.unseen.isEmpty());
        assertEquals(DriveSignalParser.Type.DONE, scan.latestCanonical.type);
        assertEquals(1, scan.latestCanonical.cursor);
    }

    private static DriveSignalParser.WorkProfile profile(String model, String reasoning) {
        return DriveSignalParser.workProfile("[2026.08.13 | 22:09:42] [SELF_RUN_TURN_COMPLETED " + JOB
                + " MODEL=" + model + " REASONING=" + reasoning + "]");
    }
}
