package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class ResumeDriveTransactionTest {
    private static final String RUN = "SR-20260816-011429-9SZ8A4";

    @Test public void normalAckSupersedesValidPriorNextBeforeCompletion() {
        String prior = completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), prior, false, false, "");
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack");
        DriveSignalParser.Event next = event(DriveSignalParser.Type.TURN_COMPLETED, 9, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Arrays.asList(ack, next), 7, 9);
        assertTrue(tx.acked());
        assertTrue(tx.normalContinueAck());
        assertSame(next, tx.materialForTest());
        assertFalse(DriveSignalParser.nextInput(tx.acceptCompletion(next.raw)).present);
        assertEquals(9, tx.consumedCursor());
    }

    @Test public void rewriteAckCarriesInvalidProfileNext() {
        String old = "승인할게";
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode(old));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, rewritePrompt(), prior, false, false, "");
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack");
        DriveSignalParser.Event corrected = event(DriveSignalParser.Type.TURN_COMPLETED, 9, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Arrays.asList(ack, corrected), 7, 9);
        assertTrue(tx.rewriteAck());
        NextInputCodec.Decoded decoded = DriveSignalParser.nextInput(tx.acceptCompletion(corrected.raw));
        assertTrue(decoded.present);
        assertEquals(old, decoded.text);
        assertFalse(tx.carryAuthorizedForTest());
    }

    @Test public void invalidPriorWithoutRewriteAckDoesNotCarry() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", prior, false, false, "");
        DriveSignalParser.Event corrected = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Collections.singletonList(corrected), 7, 8);
        assertFalse(DriveSignalParser.nextInput(tx.acceptCompletion(corrected.raw)).present);
    }

    @Test public void durablePreAnchorRewriteAuthorizationSurvivesPauseUntilCorrection() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", prior, true, false, "");
        DriveSignalParser.Event corrected = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Collections.singletonList(corrected), 7, 8);
        NextInputCodec.Decoded next = DriveSignalParser.nextInput(tx.acceptCompletion(corrected.raw));
        assertTrue(next.present);
        assertEquals("old", next.text);
    }

    @Test public void materialBeforeRequiredAckFailsClosedWhenNothingNewerExists() {
        String prior = completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), prior, false, false, "");
        DriveSignalParser.Event completion = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Collections.singletonList(completion), 7, 8);
        assertEquals("COMMAND_RECEIVED_REQUIRED", tx.error());
        assertSame(completion, tx.errorEvent());
        assertEquals(8, tx.consumedCursor());
    }

    @Test public void missingAckErrorThenDonePreservesNewestDoneAuthority() {
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), "", false, false, "");
        DriveSignalParser.Event early = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        DriveSignalParser.Event lateAck = event(DriveSignalParser.Type.COMMAND_RECEIVED, 9, "ack");
        DriveSignalParser.Event done = event(DriveSignalParser.Type.DONE, 10, "done");
        tx.observe(Arrays.asList(early, lateAck, done), 7, 10);
        assertEquals("", tx.error());
        assertSame(done, tx.materialForTest());
        assertEquals(10, tx.consumedCursor());
    }

    @Test public void missingAckErrorThenPausedPreservesNewestBlockingAuthority() {
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), "", false, false, "");
        DriveSignalParser.Event early = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        DriveSignalParser.Event paused = event(DriveSignalParser.Type.PAUSED, 9, "paused");
        tx.observe(Arrays.asList(early, paused), 7, 9);
        assertEquals("", tx.error());
        assertSame(paused, tx.materialForTest());
    }

    @Test public void invalidThenDoneDoesNotLoseDone() {
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", "", false, false, "");
        DriveSignalParser.Event invalid = new DriveSignalParser.Event(DriveSignalParser.Type.INVALID, "2026.08.16 | 07:00:00", "bad", 8, false, "", "", "NEXT_INPUT_UTF8_INVALID");
        DriveSignalParser.Event done = event(DriveSignalParser.Type.DONE, 9, "done");
        tx.observe(Arrays.asList(invalid, done), 7, 9);
        assertEquals("", tx.error());
        assertSame(done, tx.materialForTest());
        assertEquals(9, tx.consumedCursor());
    }

    @Test public void ackOnlyConsumesWaitButLeavesNoMaterialDecision() {
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), "", false, false, "");
        tx.observe(Collections.singletonList(event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack")), 7, 8);
        assertTrue(tx.acked());
        assertFalse(tx.awaitingForTest());
        assertTrue(tx.policyEvents().isEmpty());
        assertEquals(8, tx.consumedCursor());
    }

    @Test public void noPostAnchorKeepsInflightAwaitingStateAndAnchorCursor() {
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), "", false, false, "");
        tx.observe(Collections.emptyList(), 7, 7);
        assertTrue(tx.awaitingForTest());
        assertFalse(tx.acked());
        assertTrue(tx.policyEvents().isEmpty());
        assertEquals(7, tx.consumedCursor());
    }

    @Test public void firstCompletionWinsUntilNewerBlockingOverrides() {
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", "", false, false, "");
        DriveSignalParser.Event first = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        DriveSignalParser.Event second = event(DriveSignalParser.Type.TURN_COMPLETED, 9, completion("MODEL=terra REASONING=high"));
        tx.observe(Arrays.asList(first, second), 7, 9);
        assertSame(first, tx.materialForTest());
        DriveSignalParser.Event blocking = event(DriveSignalParser.Type.USER_ACTION_REQUIRED, 10, "blocking");
        tx.observe(Collections.singletonList(blocking), 7, 10);
        assertSame(blocking, tx.materialForTest());
    }

    @Test public void guardedDuplicateDoesNotSatisfyOrBreakRequiredAck() {
        String dupRaw = completion("MODEL=sol REASONING=xhigh");
        String fp = DriveSignalParser.completionFingerprint(dupRaw);
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), dupRaw, false, true, fp);
        DriveSignalParser.Event dup = event(DriveSignalParser.Type.TURN_COMPLETED, 8, dupRaw);
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 9, "ack");
        tx.observe(Arrays.asList(dup, ack), 7, 9);
        assertEquals("", tx.error());
        assertTrue(tx.acked());
        assertTrue(tx.policyEvents().isEmpty());
    }

    @Test public void noPostAnchorNeverRegressesAlreadyConsumedDurableCursor() {
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", "", false, false, "");
        tx.observe(Collections.emptyList(), 7, 9);
        assertEquals(7, tx.consumedCursor());
        assertEquals(9, tx.committedCursor(9));
    }

    @Test public void structuralFailureDoesNotAdvancePastLastActuallyProcessedCursor() {
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", "", false, false, "");
        DriveSignalParser.Event first = event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack");
        DriveSignalParser.Event impossible = event(DriveSignalParser.Type.DONE, 99, "done");
        tx.observe(Arrays.asList(first, impossible), 7, 9);
        assertTrue(tx.structuralErrorForTest());
        assertEquals("RESUME_POST_ANCHOR_SIGNAL_INVALID", tx.error());
        assertEquals(8, tx.consumedCursor());
        assertSame(first, tx.lastProcessed());
    }

    private static SelfRunStore.ResumeDriveTransaction tx(boolean awaiting, String prompt, String prior,
                                                            boolean carryAuthorized, boolean guard, String fp) {
        return new SelfRunStore.ResumeDriveTransaction(SelfRunStore.MODE_WORK, awaiting,
                SelfRunStore.RETRY_CONTINUE, prompt, !prior.isEmpty(), prior, carryAuthorized, guard, fp);
    }
    private static String normalPrompt() { return "[2026.08.16 | 07:00:00] [SELF_RUN_CONTINUE " + RUN + "]\nCommand Recevied Record Required"; }
    private static String rewritePrompt() { return "[SELF_RUN_TURN_INFO_REWRITE " + RUN + "]"; }
    private static DriveSignalParser.Event event(DriveSignalParser.Type type, int cursor, String raw) {
        return new DriveSignalParser.Event(type, "2026.08.16 | 07:00:00", raw, cursor);
    }
    private static String completion(String fields) {
        return "[2026.08.16 | 07:00:00] [SELF_RUN_TURN_COMPLETED " + RUN
                + (fields == null || fields.isEmpty() ? "" : " " + fields) + "]";
    }
}
