package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class ResumeDriveTransactionTest {
    private static final String RUN = "SR-20260816-011429-9SZ8A4";

    @Test public void normalAckSupersedesValidPriorNextBeforeCompletion() {
        String prior = completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, "[2026.08.16 | 07:00:00] [SELF_RUN_CONTINUE " + RUN + "]\nCommand Recevied Record Required", prior, false, "");
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack");
        DriveSignalParser.Event next = event(DriveSignalParser.Type.TURN_COMPLETED, 9, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Arrays.asList(ack, next), 7, 9);
        assertTrue(tx.acked());
        assertTrue(tx.normalContinueAck());
        assertEquals(next, tx.materialForTest());
        assertFalse(DriveSignalParser.nextInput(tx.acceptCompletion(next.raw)).present);
    }

    @Test public void rewriteAckCarriesOnlyInvalidProfileNext() {
        String old = "승인할게";
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode(old));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, "[SELF_RUN_TURN_INFO_REWRITE " + RUN + "]", prior, false, "");
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack");
        DriveSignalParser.Event corrected = event(DriveSignalParser.Type.TURN_COMPLETED, 9, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Arrays.asList(ack, corrected), 7, 9);
        assertTrue(tx.rewriteAck());
        NextInputCodec.Decoded decoded = DriveSignalParser.nextInput(tx.acceptCompletion(corrected.raw));
        assertTrue(decoded.present);
        assertTrue(decoded.valid);
        assertEquals(old, decoded.text);
    }

    @Test public void materialBeforeRequiredAckFailsClosed() {
        String prior = completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, "normal", prior, false, "");
        DriveSignalParser.Event completion = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Collections.singletonList(completion), 7, 8);
        assertEquals("COMMAND_RECEIVED_REQUIRED", tx.error());
        assertSame(completion, tx.errorEvent());
    }

    @Test public void ackOnlyConsumesWaitButLeavesNoMaterialDecision() {
        String prior = completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, "normal", prior, false, "");
        tx.observe(Collections.singletonList(event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack")), 7, 8);
        assertTrue(tx.acked());
        assertFalse(tx.awaitingForTest());
        assertTrue(tx.policyEvents().isEmpty());
        assertEquals("", tx.error());
    }

    @Test public void noPostAnchorKeepsInflightAwaitingState() {
        SelfRunStore.ResumeDriveTransaction tx = tx(true, "normal", "", false, "");
        tx.observe(Collections.emptyList(), 7, 7);
        assertTrue(tx.awaitingForTest());
        assertFalse(tx.acked());
        assertTrue(tx.policyEvents().isEmpty());
    }

    @Test public void firstCompletionWinsUntilNewerBlockingOverrides() {
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", "", false, "");
        DriveSignalParser.Event first = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("first")));
        DriveSignalParser.Event second = event(DriveSignalParser.Type.TURN_COMPLETED, 9, completion("MODEL=terra REASONING=high NEXT_INPUT_B64URL=" + NextInputCodec.encode("second")));
        tx.observe(Arrays.asList(first, second), 7, 9);
        assertSame(first, tx.materialForTest());
        DriveSignalParser.Event blocking = event(DriveSignalParser.Type.PAUSED, 10, "paused");
        tx.observe(Collections.singletonList(blocking), 7, 10);
        assertSame(blocking, tx.materialForTest());
    }

    @Test public void guardedDuplicateDoesNotSatisfyOrBreakRequiredAck() {
        String dupRaw = completion("MODEL=sol REASONING=xhigh");
        String fp = DriveSignalParser.completionFingerprint(dupRaw);
        SelfRunStore.ResumeDriveTransaction tx = tx(true, "normal", dupRaw, true, fp);
        DriveSignalParser.Event dup = event(DriveSignalParser.Type.TURN_COMPLETED, 8, dupRaw);
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 9, "ack");
        tx.observe(Arrays.asList(dup, ack), 7, 9);
        assertEquals("", tx.error());
        assertTrue(tx.acked());
        assertTrue(tx.policyEvents().isEmpty());
    }

    private static SelfRunStore.ResumeDriveTransaction tx(boolean awaiting, String prompt, String prior, boolean guard, String fp) {
        return new SelfRunStore.ResumeDriveTransaction(SelfRunStore.MODE_WORK, awaiting,
                SelfRunStore.RETRY_CONTINUE, prompt, !prior.isEmpty(), prior, guard, fp);
    }

    private static DriveSignalParser.Event event(DriveSignalParser.Type type, int cursor, String raw) {
        return new DriveSignalParser.Event(type, "2026.08.16 | 07:00:00", raw, cursor);
    }

    private static String completion(String fields) {
        return "[2026.08.16 | 07:00:00] [SELF_RUN_TURN_COMPLETED " + RUN
                + (fields == null || fields.isEmpty() ? "" : " " + fields) + "]";
    }
}
