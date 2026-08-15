package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.*;

public class DriveSignalParserTest {
    private static final String JOB = "SR-20260813-220315-A1B2C3";

    @Test public void parsesAckAndBareChatCompletionTogether() {
        String text = line("SELF_RUN_COMMAND_RECEIVED", "") + "\n" + line("SELF_RUN_TURN_COMPLETED", "");
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 0, SelfRunStore.MODE_CHAT);
        assertEquals(2, scan.totalCount);
        assertEquals(DriveSignalParser.Type.COMMAND_RECEIVED, scan.unseen.get(0).type);
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, scan.unseen.get(1).type);
        assertFalse(scan.unseen.get(1).hasNextInput);
    }

    @Test public void chatCompletionAcceptsNextInputAndDecodesLosslessly() {
        String input = "승인할게.\n둘째 줄 = ] \\\" 😎  ";
        String raw = line("SELF_RUN_TURN_COMPLETED", "NEXT_INPUT_B64URL=" + NextInputCodec.encode(input));
        DriveSignalParser.Event event = DriveSignalParser.scan(raw, JOB, 0, SelfRunStore.MODE_CHAT).latest;
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, event.type);
        assertTrue(event.hasNextInput);
        assertEquals(input, event.nextInput);
        assertEquals(64, event.nextInputFingerprint.length());
    }

    @Test public void workCompletionSupportsProfileWithOptionalNextInput() {
        String input = "원격 push를 진행해";
        String raw = line("SELF_RUN_TURN_COMPLETED", "MODEL=Sol REASONING=ULTRA NEXT_INPUT_B64URL=" + NextInputCodec.encode(input));
        DriveSignalParser.Scan scan = DriveSignalParser.scan(raw, JOB, 0, SelfRunStore.MODE_WORK);
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, scan.latest.type);
        DriveSignalParser.WorkProfile profile = DriveSignalParser.workProfile(scan.latest.raw);
        assertTrue(profile.valid);
        assertEquals("sol", profile.model);
        assertEquals("ultra", profile.reasoning);
        assertEquals(input, scan.latest.nextInput);
    }

    @Test public void missingOrMalformedWorkProfileStillCountsCompletionForRewrite() {
        String bare = line("SELF_RUN_TURN_COMPLETED", "");
        String malformedProfile = line("SELF_RUN_TURN_COMPLETED", "MODEL=sol");
        DriveSignalParser.Scan scan = DriveSignalParser.scan(bare + "\n" + malformedProfile, JOB, 0, SelfRunStore.MODE_WORK);
        assertEquals(2, scan.totalCount);
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, scan.unseen.get(0).type);
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, scan.unseen.get(1).type);
        assertFalse(DriveSignalParser.workProfile(scan.unseen.get(0).raw).valid);
        assertFalse(DriveSignalParser.workProfile(scan.unseen.get(1).raw).valid);
    }

    @Test public void unknownAndDuplicateFieldsFailClosed() {
        DriveSignalParser.Event unknown = DriveSignalParser.scan(
                line("SELF_RUN_TURN_COMPLETED", "BOGUS=x"), JOB, 0, SelfRunStore.MODE_CHAT).latest;
        DriveSignalParser.Event duplicate = DriveSignalParser.scan(
                line("SELF_RUN_TURN_COMPLETED", "NEXT_INPUT_B64URL=YQ NEXT_INPUT_B64URL=Yg"), JOB, 0, SelfRunStore.MODE_CHAT).latest;
        assertEquals(DriveSignalParser.Type.INVALID, unknown.type);
        assertEquals("TURN_COMPLETED_UNKNOWN_FIELD", unknown.protocolError);
        assertEquals(DriveSignalParser.Type.INVALID, duplicate.type);
        assertEquals("TURN_COMPLETED_DUPLICATE_FIELD", duplicate.protocolError);
    }

    @Test public void paddedAndMalformedUtf8PayloadsFailClosed() {
        DriveSignalParser.Event padded = DriveSignalParser.scan(
                line("SELF_RUN_TURN_COMPLETED", "NEXT_INPUT_B64URL=YQ=="), JOB, 0, SelfRunStore.MODE_CHAT).latest;
        String malformed = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{(byte) 0xc3, (byte) 0x28});
        DriveSignalParser.Event invalidUtf8 = DriveSignalParser.scan(
                line("SELF_RUN_TURN_COMPLETED", "NEXT_INPUT_B64URL=" + malformed), JOB, 0, SelfRunStore.MODE_CHAT).latest;
        assertEquals(DriveSignalParser.Type.INVALID, padded.type);
        assertEquals(DriveSignalParser.Type.INVALID, invalidUtf8.type);
        assertEquals("NEXT_INPUT_UTF8_INVALID", invalidUtf8.protocolError);
    }

    @Test public void repairCompletionCanCarryForwardExistingNextInput() {
        String input = "계속해";
        String prior = line("SELF_RUN_TURN_COMPLETED", "MODEL=sol NEXT_INPUT_B64URL=" + NextInputCodec.encode(input));
        String repaired = line("SELF_RUN_TURN_COMPLETED", "MODEL=sol REASONING=xhigh");
        String merged = DriveSignalParser.mergeNextInputIfMissing(repaired, prior);
        assertEquals(input, DriveSignalParser.nextInput(merged).text);
        assertTrue(DriveSignalParser.workProfile(merged).valid);
    }

    @Test public void historyRedactionNeverRetainsPayload() {
        String encoded = NextInputCodec.encode("secret-ish user text");
        String raw = line("SELF_RUN_TURN_COMPLETED", "NEXT_INPUT_B64URL=" + encoded);
        String safe = DriveSignalParser.historySafeRaw(raw);
        assertFalse(safe.contains(encoded));
        assertTrue(safe.contains("NEXT_INPUT_B64URL=<redacted>"));
    }

    @Test public void cursorOnlyReturnsPostAnchorSignalsAndRebasesImpossibleCursor() {
        String text = line("SELF_RUN_COMMAND_RECEIVED", "") + "\n"
                + line("SELF_RUN_TURN_COMPLETED", "") + "\n"
                + line("SELF_RUN_USER_ACTION_REQUIRED", "");
        DriveSignalParser.Scan scan = DriveSignalParser.scan(text, JOB, 2, SelfRunStore.MODE_CHAT);
        assertEquals(1, scan.unseen.size());
        assertEquals(3, scan.unseen.get(0).cursor);
        DriveSignalParser.Scan impossible = DriveSignalParser.scan(text, JOB, Integer.MAX_VALUE, SelfRunStore.MODE_CHAT);
        assertTrue(impossible.cursorRebased);
        assertTrue(impossible.unseen.isEmpty());
        assertEquals(3, impossible.totalCount);
    }

    @Test public void canonicalWorkProfileRestrictionsRemainEnforced() {
        assertTrue(profile("sol", "high").valid);
        assertTrue(profile("sol", "ultra").valid);
        assertTrue(profile("terra", "max").valid);
        assertTrue(profile("luna", "max").valid);
        assertFalse(profile("terra", "ultra").valid);
        assertFalse(profile("luna", "high").valid);
        assertFalse(profile("luna", "ultra").valid);
    }

    private static DriveSignalParser.WorkProfile profile(String model, String reasoning) {
        return DriveSignalParser.workProfile(line("SELF_RUN_TURN_COMPLETED", "MODEL=" + model + " REASONING=" + reasoning));
    }

    private static String line(String signal, String tail) {
        return "[2026.08.13 | 22:09:42] [" + signal + " " + JOB + (tail.isEmpty() ? "" : " " + tail) + "]";
    }
}
