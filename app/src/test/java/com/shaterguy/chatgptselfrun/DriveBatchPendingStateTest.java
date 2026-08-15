package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriveBatchPendingStateTest {
    private static final String RUN = "SR-20260816-011429-9SZ8A4";

    @Test public void normalContinueAckDropsPriorNextBeforeSameBatchWorkCompletion() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("원격 push를 진행해"));
        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior);
        assertTrue(state.carryNextForTest());
        state.supersede();
        String accepted = state.acceptCompletion(completion("MODEL=sol REASONING=xhigh"));
        NextInputCodec.Decoded next = DriveSignalParser.nextInput(accepted);
        assertFalse(next.present);
        assertFalse(state.carryNextForTest());
    }

    @Test public void rewriteAckKeepsPriorNextForCorrectedCompletionAcrossSameBatch() {
        String oldInput = "승인할게";
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode(oldInput));
        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior);
        String accepted = state.acceptCompletion(completion("MODEL=sol REASONING=xhigh"));
        NextInputCodec.Decoded next = DriveSignalParser.nextInput(accepted);
        assertTrue(next.present);
        assertTrue(next.valid);
        assertEquals(oldInput, next.text);
        assertFalse(state.carryNextForTest());
    }

    @Test public void newerCompletionOwnNextAlwaysWinsOverRewriteCarry() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior);
        String accepted = state.acceptCompletion(completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("new")));
        NextInputCodec.Decoded next = DriveSignalParser.nextInput(accepted);
        assertTrue(next.present);
        assertTrue(next.valid);
        assertEquals("new", next.text);
    }

    @Test public void blockingOrDoneSupersedeMakesLaterCompletionUnableToRecoverOldNext() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior);
        state.supersede();
        assertEquals("", state.rawForTest());
        String accepted = state.acceptCompletion(completion("MODEL=sol REASONING=xhigh"));
        assertFalse(DriveSignalParser.nextInput(accepted).present);
    }

    @Test public void chatModeNeverCarriesWorkRewriteNext() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_CHAT, true, prior);
        String accepted = state.acceptCompletion(completion(""));
        assertFalse(DriveSignalParser.nextInput(accepted).present);
    }

    private static String completion(String fields) {
        return "[2026.08.16 | 06:00:00] [SELF_RUN_TURN_COMPLETED " + RUN
                + (fields == null || fields.isEmpty() ? "" : " " + fields) + "]";
    }
}
