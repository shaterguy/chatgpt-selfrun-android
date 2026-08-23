package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public final class UserNextInputStoreTest {
    @Test public void mergePreservesAllFourInputCombinations() {
        assertEquals("", UserNextInputStore.mergeText("", ""));
        assertEquals("GPT next input", UserNextInputStore.mergeText("GPT next input", ""));
        assertEquals("user next input", UserNextInputStore.mergeText("", "user next input"));
        assertEquals("GPT next input\n\nuser next input",
                UserNextInputStore.mergeText("GPT next input", "user next input"));
    }

    @Test public void mergeDoesNotTrimUserText() {
        assertEquals("GPT\n\n  user text  ", UserNextInputStore.mergeText("GPT", "  user text  "));
    }

    @Test public void reservationIsBoundToOneContinuationIdentity() {
        String first = UserNextInputStore.continuationIdentity(7, 123456L);
        String retry = UserNextInputStore.continuationIdentity(7, 123456L);
        String later = UserNextInputStore.continuationIdentity(7, 123457L);
        assertEquals("7:123456", first);
        assertTrue(UserNextInputStore.reservationApplies(first, retry));
        assertFalse(UserNextInputStore.reservationApplies(first, later));
        assertFalse(UserNextInputStore.reservationApplies("", first));
        assertEquals("", UserNextInputStore.continuationIdentity(7, 0L));
    }

    @Test public void userAndCombinedInputsHaveFiniteUtf8Bounds() {
        String exactUser = "a".repeat(UserNextInputStore.MAX_USER_UTF8_BYTES);
        String oversizedUser = exactUser + "a";
        assertTrue(UserNextInputStore.withinUtf8Limit(exactUser, UserNextInputStore.MAX_USER_UTF8_BYTES));
        assertFalse(UserNextInputStore.withinUtf8Limit(oversizedUser, UserNextInputStore.MAX_USER_UTF8_BYTES));

        String drive = "d".repeat(NextInputCodec.MAX_UTF8_BYTES);
        String user = "u".repeat(UserNextInputStore.MAX_USER_UTF8_BYTES);
        String merged = UserNextInputStore.mergeText(drive, user);
        assertEquals(UserNextInputStore.MAX_COMBINED_UTF8_BYTES, merged.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(UserNextInputStore.withinUtf8Limit(merged, UserNextInputStore.MAX_COMBINED_UTF8_BYTES));
        assertFalse(UserNextInputStore.withinUtf8Limit(merged + "x", UserNextInputStore.MAX_COMBINED_UTF8_BYTES));
    }

    @Test public void boundTextIsConsumedOnlyAfterConfirmedSubmissionPath() {
        String bound = "7:123456";
        assertFalse(UserNextInputStore.shouldConsumeBoundReservation(
                SelfRunStore.PHASE_SEND_CONTINUE, "", bound, bound));
        assertFalse(UserNextInputStore.shouldConsumeBoundReservation(
                SelfRunStore.PHASE_PAUSED, SelfRunStore.PHASE_SEND_CONTINUE, bound, ""));
        assertTrue(UserNextInputStore.shouldConsumeBoundReservation(
                SelfRunStore.PHASE_WAIT_TURN_COMPLETION, "", bound, ""));
        assertTrue(UserNextInputStore.shouldConsumeBoundReservation(
                SelfRunStore.PHASE_PAUSED, SelfRunStore.PHASE_WAIT_TURN_COMPLETION, bound, ""));
        assertTrue(UserNextInputStore.shouldConsumeBoundReservation(
                SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC, "", bound, ""));
        assertTrue(UserNextInputStore.shouldConsumeBoundReservation(
                SelfRunStore.PHASE_SEND_CONTINUE, "", bound, "8:223456"));
    }
}
