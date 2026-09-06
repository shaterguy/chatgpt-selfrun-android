package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ContinuationComposerRecoveryPolicyTest {
    @Test public void reloadsOnlyAfterVisualReadyAndOneSecondWhenComposerIsAbsent() {
        assertTrue(ContinuationComposerRecoveryPolicy.shouldReload(
                SelfRunStore.PHASE_SEND_CONTINUE, true, 1_000L, 2, 1,
                "doc=visible;semantic=0;candidate=none"));
        assertFalse(ContinuationComposerRecoveryPolicy.shouldReload(
                SelfRunStore.PHASE_SEND_CONTINUE, false, 1_000L, 2, 1,
                "semantic=0"));
        assertFalse(ContinuationComposerRecoveryPolicy.shouldReload(
                SelfRunStore.PHASE_SEND_CONTINUE, true, 999L, 2, 1,
                "semantic=0"));
        assertFalse(ContinuationComposerRecoveryPolicy.shouldReload(
                SelfRunStore.PHASE_SEND_CONTINUE, true, 1_000L, 2, 2,
                "semantic=0"));
        assertFalse(ContinuationComposerRecoveryPolicy.shouldReload(
                SelfRunStore.PHASE_SEND_CONTINUE, true, 1_000L, 2, 1,
                "semantic=1"));
        assertFalse(ContinuationComposerRecoveryPolicy.shouldReload(
                SelfRunStore.PHASE_BOOTSTRAP_SEND, true, 1_000L, 2, 1,
                "semantic=0"));
    }
}
