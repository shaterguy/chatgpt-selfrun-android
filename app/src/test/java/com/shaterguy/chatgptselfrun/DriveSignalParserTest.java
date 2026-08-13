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

    @Test public void ignoresLegacyMetadataWrongJobAndMalformedLines() {
        String text = "[SELF_RUN_DRIVE_COMMIT_V1]\nEVENT_SEQ=9\n[/SELF_RUN_DRIVE_COMMIT_V1]\n"
                + "[2026.08.13 | 22:10:00] [SELF_RUN_DONE SR-OTHER]\n"
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
}
