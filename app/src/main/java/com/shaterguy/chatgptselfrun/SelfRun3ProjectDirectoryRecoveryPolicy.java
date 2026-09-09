package com.shaterguy.chatgptselfrun;

/** Bounded recovery policy for intermittent ChatGPT project-directory hydration and no-op clicks. */
final class SelfRun3ProjectDirectoryRecoveryPolicy {
    static final long HYDRATION_SETTLE_MS = 2_500L;
    static final long POST_CLICK_SETTLE_MS = 1_800L;
    static final int MAX_RETRIES_PER_DOCUMENT = 12;
    static final int MAX_CLICKS_PER_DOCUMENT = 2;
    static final int MAX_RECOVERIES_PER_ATTEMPT = 3;

    private SelfRun3ProjectDirectoryRecoveryPolicy() {}

    static boolean shouldRecoverAfterRetry(int retryCount) {
        return retryCount >= MAX_RETRIES_PER_DOCUMENT;
    }

    static boolean shouldRecoverAfterClick(int clickCount) {
        return clickCount >= MAX_CLICKS_PER_DOCUMENT;
    }

    static boolean canRecover(int completedRecoveries) {
        return completedRecoveries < MAX_RECOVERIES_PER_ATTEMPT;
    }

    static boolean shouldRecreateHost(int recoveryNumber) {
        return recoveryNumber >= MAX_RECOVERIES_PER_ATTEMPT;
    }
}
