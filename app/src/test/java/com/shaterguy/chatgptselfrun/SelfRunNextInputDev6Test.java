package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

import static org.junit.Assert.*;

public class SelfRunNextInputDev6Test {
    private static final String RUN = "SR-20260816-112716-39Q4VK";

    @Test public void chatCompletionCarriesStrictNextInput() {
        String input = "승인할게.\n다음 줄도 유지  ";
        String raw = line("NEXT_INPUT_B64URL=" + encode(input));
        DriveSignalParser.Scan scan = DriveSignalParser.scan(raw, RUN, 0, SelfRunStore.MODE_CHAT);
        assertEquals(1, scan.totalCount);
        DriveSignalParser.Event completion = DriveSignalParser.latestCompletion(scan.unseen);
        assertNotNull(completion);
        assertEquals("", completion.protocolError);
        assertTrue(completion.hasNextInput);
        assertEquals(input, completion.nextInput);
        assertEquals(input, DriveSignalParser.nextInput(raw).text);
    }

    @Test public void workProfileAndNextInputCoexist() {
        String input = "현재 선택을 다음 턴에 전달";
        String raw = line("MODEL=sol REASONING=ultra NEXT_INPUT_B64URL=" + encode(input));
        DriveSignalParser.Scan scan = DriveSignalParser.scan(raw, RUN, 0, SelfRunStore.MODE_WORK);
        assertEquals(1, scan.totalCount);
        DriveSignalParser.WorkProfile profile = DriveSignalParser.workProfile(raw);
        assertTrue(profile.valid);
        assertEquals("sol", profile.model);
        assertEquals("ultra", profile.reasoning);
        assertEquals(input, DriveSignalParser.latestCompletion(scan.unseen).nextInput);
    }

    @Test public void malformedNextInputFailsClosedInsteadOfBecomingPlainCompletion() {
        String padded = line("NEXT_INPUT_B64URL=YWJj=");
        DriveSignalParser.Event bad = DriveSignalParser.latestCompletion(
                DriveSignalParser.scan(padded, RUN, 0, SelfRunStore.MODE_CHAT).unseen);
        assertNotNull(bad);
        assertFalse(bad.protocolError.isEmpty());

        String duplicate = line("NEXT_INPUT_B64URL=YWJj NEXT_INPUT_B64URL=YWJj");
        bad = DriveSignalParser.latestCompletion(
                DriveSignalParser.scan(duplicate, RUN, 0, SelfRunStore.MODE_CHAT).unseen);
        assertEquals("TURN_COMPLETED_DUPLICATE_FIELD", bad.protocolError);

        String unknown = line("NEXT_INPUT_B64URL=YWJj EXTRA=x");
        bad = DriveSignalParser.latestCompletion(
                DriveSignalParser.scan(unknown, RUN, 0, SelfRunStore.MODE_CHAT).unseen);
        assertEquals("TURN_COMPLETED_UNKNOWN_FIELD", bad.protocolError);
    }

    @Test public void invalidUtf8AndOversizeAreRejected() {
        assertEquals("NEXT_INPUT_UTF8_INVALID", NextInputCodec.decodeToken("_w").error);
        String tooLarge = "A".repeat(NextInputCodec.MAX_ENCODED_CHARS + 1);
        assertEquals("NEXT_INPUT_ENCODED_TOO_LARGE", NextInputCodec.decodeToken(tooLarge).error);
    }

    @Test public void laterValidCompletionSupersedesEarlierInvalidForResumeSelection() {
        String bad = line("NEXT_INPUT_B64URL=YWJj=");
        String good = line("NEXT_INPUT_B64URL=" + encode("최종 선택"));
        DriveSignalParser.Scan scan = DriveSignalParser.scan(bad + "\n" + good, RUN, 0, SelfRunStore.MODE_CHAT);
        DriveSignalParser.Event completion = DriveSignalParser.latestCompletion(scan.unseen);
        assertEquals("", completion.protocolError);
        assertEquals("최종 선택", completion.nextInput);
    }

    @Test public void laterPauseBlocksEarlierInvalidCompletion() {
        String bad = line("NEXT_INPUT_B64URL=YWJj=");
        String pause = "[2026.08.16 | 11:30:01] [SELF_RUN_PAUSED " + RUN + "]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(bad + "\n" + pause, RUN, 0, SelfRunStore.MODE_CHAT);
        assertTrue(DriveSignalParser.latestBlocking(scan.unseen).cursor
                > DriveSignalParser.latestCompletion(scan.unseen).cursor);
    }

    @Test public void legacyChatExtensionIsStillIgnoredAndHistoryIsRedacted() {
        String legacyInvalid = line("MODEL=sol REASONING=xhigh");
        assertEquals(0, DriveSignalParser.scan(legacyInvalid, RUN, 0, SelfRunStore.MODE_CHAT).totalCount);
        String raw = line("NEXT_INPUT_B64URL=" + encode("secret user text"));
        assertFalse(DriveSignalParser.historySafeRaw(raw).contains(encode("secret user text")));
        assertTrue(DriveSignalParser.historySafeRaw(raw).contains("NEXT_INPUT_B64URL=<redacted>"));
    }

    @Test public void sourceKeepsStableResumeBaselineAndUsesExistingDurableCompletion() throws Exception {
        String service = compact(source("SelfRunService.java"));
        String store = source("SelfRunStore.java");
        assertTrue(service.contains("store.baselineManualResume(scan.totalCount,scan.latest,latestCompletion)"));
        assertTrue(service.contains("SelfRunProtocol.driveContinuation(store.runId(),store.pendingNextInput())"));
        assertTrue(store.contains("String pendingNextInput()"));
        assertTrue(store.contains("pendingDriveSignalRaw"));
        assertFalse(service.contains("DriveResumePolicy"));
        assertFalse(store.contains("pauseAnchor"));
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
    private static String line(String tail) {
        return "[2026.08.16 | 11:30:00] [SELF_RUN_TURN_COMPLETED " + RUN + " " + tail + "]";
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
