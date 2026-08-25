package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

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
        try {
            DriveSignalDocumentTransport.materialize(title, "NEXT_INPUT_B64URL=QQ\nEXTRA", RUN);
            fail("multi-line signal body must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("one line"));
        }
        try {
            DriveSignalDocumentTransport.materialize(title, "NEXT_INPUT_B64URL=QQ==", RUN);
            fail("padded Base64URL must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("NEXT_INPUT body"));
        }
    }

    @Test public void orderingUsesCreatedTimeThenTitleTimestampThenFileId() throws Exception {
        List<DriveApiClient.Metadata> values = new ArrayList<>();
        values.add(metadata("signal_C0000001", "2026-08-25T01:50:01Z",
                signal("2026.08.25 | 10:40:00", "")));
        values.add(metadata("signal_A0000001", "2026-08-25T01:50:00Z",
                signal("2026.08.25 | 10:50:00", "")));
        values.add(metadata("signal_B0000001", "2026-08-25T01:50:00Z",
                signal("2026.08.25 | 10:50:01", "")));
        values.sort(DriveSignalDocumentTransport.comparator(RUN));
        assertEquals("signal_A0000001", values.get(0).id);
        assertEquals("signal_B0000001", values.get(1).id);
        assertEquals("signal_C0000001", values.get(2).id);
    }

    @Test public void candidateRequiresExactParentNativeDocAndProviderCreatedTime() throws Exception {
        DriveApiClient.Metadata valid = metadata("signal_A0000001", "2026-08-25T01:50:00.123Z",
                signal("2026.08.25 | 10:50:00", ""));
        assertTrue(DriveSignalDocumentTransport.isCandidate(valid, RUN, PARENT));
        assertFalse(DriveSignalDocumentTransport.isCandidate(valid, RUN, "other_folder_123"));
        DriveApiClient.Metadata missingCreated = metadata("signal_B0000001", "",
                signal("2026.08.25 | 10:50:00", ""));
        assertFalse(DriveSignalDocumentTransport.isCandidate(missingCreated, RUN, PARENT));
    }

    private static DriveApiClient.Metadata metadata(String id, String createdTime, String name) throws Exception {
        JSONObject json = new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("mimeType", DriveApiClient.MIME_DOCUMENT)
                .put("parents", new JSONArray().put(PARENT))
                .put("trashed", false)
                .put("shared", false)
                .put("createdTime", createdTime)
                .put("modifiedTime", createdTime)
                .put("version", "1");
        return new DriveApiClient.Metadata(json);
    }
}
