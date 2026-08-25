package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class DriveSignalDocumentTransportTest {
    private static final String RUN = "SR-20260825-103707-00PULQ";
    private static final String PARENT = "folder_12345678";

    private static String signal(String timestamp, String tail) {
        return "[" + timestamp + "] [SELF_RUN_TURN_COMPLETED " + RUN
                + (tail == null || tail.isEmpty() ? "" : " " + tail) + "]";
    }

    @Test public void acceptsCanonicalChatWorkAndTerminalTitles() {
        assertTrue(DriveSignalDocumentTransport.isCanonicalTitle(
                signal("2026.08.25 | 10:45:00", ""), RUN));
        assertTrue(DriveSignalDocumentTransport.isCanonicalTitle(
                signal("2026.08.25 | 10:45:01", "MODEL=sol REASONING=xhigh"), RUN));
        assertTrue(DriveSignalDocumentTransport.isCanonicalTitle(
                "[2026.08.25 | 10:45:02] [SELF_RUN_DONE " + RUN + "]", RUN));
    }

    @Test public void nextInputTitleContainsMarkerOnlyAndBodyMaterializesLegacyCanonicalSignal() {
        String title = signal("2026.08.25 | 10:46:00", "NEXT_INPUT_B64URL=BODY");
        assertTrue(DriveSignalDocumentTransport.isCanonicalTitle(title, RUN));
        assertTrue(DriveSignalDocumentTransport.needsBodyRead(title));
        String logical = DriveSignalDocumentTransport.materialize(title, "NEXT_INPUT_B64URL=QQ\n", RUN);
        assertTrue(logical.endsWith("NEXT_INPUT_B64URL=QQ]"));
        NextInputCodec.Decoded decoded = DriveSignalParser.nextInput(logical);
        assertTrue(decoded.present);
        assertTrue(decoded.valid);
        assertEquals("A", decoded.text);
    }

    @Test public void workNextInputKeepsModelAndReasoningInTitle() {
        String title = signal("2026.08.25 | 10:47:00",
                "MODEL=terra REASONING=max NEXT_INPUT_B64URL=BODY");
        String logical = DriveSignalDocumentTransport.materialize(title, "NEXT_INPUT_B64URL=QQ", RUN);
        DriveSignalParser.WorkProfile profile = DriveSignalParser.workProfile(logical);
        assertTrue(profile.valid);
        assertEquals("terra", profile.model);
        assertEquals("max", profile.reasoning);
    }

    @Test public void rejectsInlinePayloadForeignRunAndMalformedBody() {
        assertFalse(DriveSignalDocumentTransport.isCanonicalTitle(
                signal("2026.08.25 | 10:48:00", "NEXT_INPUT_B64URL=QQ"), RUN));
        assertFalse(DriveSignalDocumentTransport.isCanonicalTitle(
                "[2026.08.25 | 10:48:01] [SELF_RUN_DONE SR-OTHER-RUN]", RUN));
        String title = signal("2026.08.25 | 10:48:02", "NEXT_INPUT_B64URL=BODY");
        for (String body : new String[]{
                "NEXT_INPUT_B64URL=QQ\nEXTRA",
                "NEXT_INPUT_B64URL=QQ\n\n"}) {
            try {
                DriveSignalDocumentTransport.materialize(title, body, RUN);
                fail("multi-line signal body must be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("one line"));
            }
        }
        try {
            DriveSignalDocumentTransport.materialize(title, "NEXT_INPUT_B64URL=QQ==", RUN);
            fail("padded Base64URL must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("NEXT_INPUT body"));
        }
        try {
            DriveSignalDocumentTransport.materialize(title, "NEXT_INPUT_B64URL=__8", RUN);
            fail("invalid UTF-8 payload must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("UTF-8 Base64URL"));
        }
    }

    @Test public void orderingUsesCreatedTimeThenTitleTimestampThenFileId() {
        String aTitle = signal("2026.08.25 | 10:50:00", "");
        String bTitle = signal("2026.08.25 | 10:50:01", "");
        String cTitle = signal("2026.08.25 | 10:40:00", "");
        assertTrue(DriveSignalDocumentTransport.compareFields(
                "2026-08-25T01:50:00Z", aTitle, "signal_A0000001",
                "2026-08-25T01:50:00Z", bTitle, "signal_B0000001", RUN) < 0);
        assertTrue(DriveSignalDocumentTransport.compareFields(
                "2026-08-25T01:50:00Z", bTitle, "signal_B0000001",
                "2026-08-25T01:50:01Z", cTitle, "signal_C0000001", RUN) < 0);
        assertTrue(DriveSignalDocumentTransport.compareFields(
                "2026-08-25T01:50:00Z", aTitle, "signal_A0000001",
                "2026-08-25T01:50:00Z", aTitle, "signal_B0000001", RUN) < 0);
    }

    @Test public void candidateRequiresExactParentNativeDocAndProviderCreatedTime() {
        String title = signal("2026.08.25 | 10:50:00", "");
        assertTrue(DriveSignalDocumentTransport.isCandidateFields(
                "signal_A0000001", title, DriveApiClient.MIME_DOCUMENT, PARENT,
                false, false, "2026-08-25T01:50:00.123Z", RUN, PARENT));
        assertFalse(DriveSignalDocumentTransport.isCandidateFields(
                "signal_A0000001", title, DriveApiClient.MIME_DOCUMENT, PARENT,
                false, false, "2026-08-25T01:50:00.123Z", RUN, "other_folder_123"));
        assertFalse(DriveSignalDocumentTransport.isCandidateFields(
                "signal_B0000001", title, DriveApiClient.MIME_DOCUMENT, PARENT,
                false, false, "", RUN, PARENT));
        assertFalse(DriveSignalDocumentTransport.isCandidateFields(
                "signal_C0000001", title, DriveApiClient.MIME_FOLDER, PARENT,
                false, false, "2026-08-25T01:50:00Z", RUN, PARENT));
        assertFalse(DriveSignalDocumentTransport.isCandidateFields(
                "signal_D0000001", title, DriveApiClient.MIME_DOCUMENT, PARENT,
                false, true, "2026-08-25T01:50:00Z", RUN, PARENT));
    }
}
