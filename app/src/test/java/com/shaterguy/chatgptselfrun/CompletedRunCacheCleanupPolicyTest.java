package com.shaterguy.chatgptselfrun;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CompletedRunCacheCleanupPolicyTest {
    @Test public void finalAcknowledgedDoneWithOwnedWebViewIsEligible() {
        assertTrue(SelfRunService.shouldCleanupCompletedRun(
                "run-1", "run-1", SelfRunStore.PHASE_DONE, false, true));
    }

    @Test public void nonFinalOrUnacknowledgedStatesAreIneligible() {
        String[] nonFinal = {
                SelfRunStore.PHASE_WAIT_TURN_COMPLETION,
                SelfRunStore.PHASE_POST_PROTOCOL_DRIVE_SYNC,
                SelfRunStore.PHASE_APPLY_PREFS,
                SelfRunStore.PHASE_APPLY_REASONING,
                SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunStore.PHASE_PAUSED,
                SelfRunStore.PHASE_BOOTSTRAP,
                SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK
        };
        for (String phase : nonFinal) {
            assertFalse(SelfRunService.shouldCleanupCompletedRun(
                    "run-1", "run-1", phase, false, true));
        }
        assertFalse(SelfRunService.shouldCleanupCompletedRun(
                "run-1", "run-1", SelfRunStore.PHASE_DONE, true, true));
        assertFalse(SelfRunService.shouldCleanupCompletedRun(
                "run-1", "run-2", SelfRunStore.PHASE_DONE, false, true));
        assertFalse(SelfRunService.shouldCleanupCompletedRun(
                "run-1", "run-1", SelfRunStore.PHASE_DONE, false, false));
    }
}
