package com.shaterguy.chatgptselfrun;

/** Pure policy boundary between event-driven server wait and local short polling. */
final class SelfRunServerWaitPolicy {
    static final long RECOVERY_INTERVAL_MINUTES = 15L;

    private SelfRunServerWaitPolicy() { }

    static boolean useServerPush(SelfRun3RuntimeSettings.WorkMode mode, boolean localFallback) {
        return mode == SelfRun3RuntimeSettings.WorkMode.SERVER && !localFallback;
    }
}
