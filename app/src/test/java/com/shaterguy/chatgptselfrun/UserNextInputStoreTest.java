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

    @Test public void reservationAppliesOnlyToImmediatelyNextTurn() {
        assertTrue(UserNextInputStore.appliesToNextTurn(4, 3));
        assertFalse(UserNextInputStore.appliesToNextTurn(4, 4));
        assertFalse(UserNextInputStore.appliesToNextTurn(4, 2));
    }
}
