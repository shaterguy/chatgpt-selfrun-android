package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SelfRun3ProjectDirectoryRecoveryPolicyTest {
    @Test public void hydrationGetsSchedulerEquivalentSettleWindowBeforeFirstClick() {
        assertTrue(SelfRun3ProjectDirectoryRecoveryPolicy.HYDRATION_SETTLE_MS >= 2_500L);
        assertTrue(SelfRun3ProjectDirectoryRecoveryPolicy.POST_CLICK_SETTLE_MS >= 1_800L);
    }

    @Test public void oneDirectoryDocumentCannotRetryOrClickForever() {
        assertFalse(SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecoverAfterRetry(
                SelfRun3ProjectDirectoryRecoveryPolicy.MAX_RETRIES_PER_DOCUMENT - 1));
        assertTrue(SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecoverAfterRetry(
                SelfRun3ProjectDirectoryRecoveryPolicy.MAX_RETRIES_PER_DOCUMENT));
        assertFalse(SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecoverAfterClick(
                SelfRun3ProjectDirectoryRecoveryPolicy.MAX_CLICKS_PER_DOCUMENT - 1));
        assertTrue(SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecoverAfterClick(
                SelfRun3ProjectDirectoryRecoveryPolicy.MAX_CLICKS_PER_DOCUMENT));
    }

    @Test public void repeatedFreshDocumentRecoveryEventuallyRecreatesWebView() {
        assertFalse(SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecreateHost(1));
        assertFalse(SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecreateHost(2));
        assertTrue(SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecreateHost(3));
        assertFalse(SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecreateHost(4));
        assertTrue(SelfRun3ProjectDirectoryRecoveryPolicy.shouldRecreateHost(6));
    }
}
