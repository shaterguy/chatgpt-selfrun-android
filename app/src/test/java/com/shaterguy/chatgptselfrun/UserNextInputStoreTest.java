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

    @Test public void sendContinueStaysEditableUntilSubmissionSnapshotLocks() {
        assertTrue(UserNextInputStore.phaseAllowsEditing(SelfRunStore.PHASE_SEND_CONTINUE, false));
        assertTrue(UserNextInputStore.phaseAllowsEditing(SelfRunStore.PHASE_PAUSED, false));
        assertFalse(UserNextInputStore.phaseAllowsEditing(SelfRunStore.PHASE_SEND_CONTINUE, true));
        assertFalse(UserNextInputStore.phaseAllowsEditing(SelfRunStore.PHASE_DONE, false));
        assertFalse(UserNextInputStore.phaseAllowsEditing(SelfRunStore.PHASE_IDLE, false));
    }

    @Test public void submissionPreflightRequiresSameIdentityAndRevision() {
        String identity = UserNextInputStore.continuationIdentity(7, 123456L);
        assertTrue(UserNextInputStore.preflightMatches(identity, 4L, identity, 4L));
        assertFalse(UserNextInputStore.preflightMatches(identity, 4L, identity, 5L));
        assertFalse(UserNextInputStore.preflightMatches(identity, 4L,
                UserNextInputStore.continuationIdentity(8, 123456L), 4L));
        assertFalse(UserNextInputStore.preflightMatches("", 4L, identity, 4L));
    }

    @Test public void lockedRetryProbeRequiresSameContinuationAndRevision() {
        String identity = UserNextInputStore.continuationIdentity(7, 123456L);
        assertTrue(UserNextInputStore.lockProbeMatches(identity, 9L, identity, 9L));
        assertFalse(UserNextInputStore.lockProbeMatches(identity, 9L, identity, 10L));
        assertFalse(UserNextInputStore.lockProbeMatches(identity, 9L,
                UserNextInputStore.continuationIdentity(7, 123457L), 9L));
        assertFalse(UserNextInputStore.lockProbeMatches("", 9L, identity, 9L));
    }

    @Test public void staleCachedPayloadIsReplacedWithoutChangingContinuationHeader() {
        String header = "[2026.08.23 | 22:00:00] [SELF_RUN_CONTINUE SR-EXAMPLE]";
        String stale = header + "\nstale user text";
        assertEquals(header + "\nGPT next input\n\nlatest user text",
                UserNextInputStore.composePrompt(stale, "GPT next input\n\nlatest user text"));
        assertEquals(header, UserNextInputStore.composePrompt(stale, ""));
        String recovery = "[2026.08.23 | 22:00:00] [SELF_RUN_CONTINUE SR-EXAMPLE RECOVERY_ID=wd.1]";
        assertEquals(recovery, UserNextInputStore.composePrompt(recovery, "must not append"));
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

    @Test public void lockedTextIsConsumedOnlyAfterConfirmedSubmissionPath() {
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
                SelfRunStore.PHASE_POST_PROTOCOL_DRIVE_SYNC, "", bound, ""));
        assertTrue(UserNextInputStore.shouldConsumeBoundReservation(
                SelfRunStore.PHASE_SEND_CONTINUE, "", bound, "8:223456"));
    }

    @Test public void staleUnsubmittedTextIsDiscardedOnlyWhenRunIsAbandonedOrSuperseded() {
        assertFalse(UserNextInputStore.shouldDiscardStaleReservation(
                "run-a", "run-a", true, false, SelfRunStore.PHASE_WAIT_TURN_COMPLETION));
        assertFalse(UserNextInputStore.shouldDiscardStaleReservation(
                "run-a", "run-a", true, false, SelfRunStore.PHASE_PAUSED));
        assertTrue(UserNextInputStore.shouldDiscardStaleReservation(
                "run-a", "run-a", false, true, SelfRunStore.PHASE_IDLE));
        assertTrue(UserNextInputStore.shouldDiscardStaleReservation(
                "run-a", "run-a", false, false, SelfRunStore.PHASE_DONE));
        assertTrue(UserNextInputStore.shouldDiscardStaleReservation(
                "run-a", "run-b", true, false, SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK));
        assertFalse(UserNextInputStore.shouldDiscardStaleReservation(
                "", "run-b", true, false, SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK));
    }
}
