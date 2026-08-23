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
}
