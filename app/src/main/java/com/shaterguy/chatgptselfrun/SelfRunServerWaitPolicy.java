package com.shaterguy.chatgptselfrun;

/** Pure policy boundary between event-driven server wait and local short polling. */
final class SelfRunServerWaitPolicy {
    static final long RECOVERY_INTERVAL_MINUTES = 15L;
    // Keep bounded one-minute follow-ups alive longer than command-bridge's current
    // 10-minute durable resend gap, but still stop before the 15-minute recovery watchdog.
    private static final long[] SERVER_RESULT_RECHECK_DELAYS_MS = {
            10_000L, 30_000L,
            60_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L,
            60_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L
    };

    private SelfRunServerWaitPolicy() { }

    static boolean useServerPush(SelfRun3RuntimeSettings.WorkMode mode, boolean localFallback) {
        return mode == SelfRun3RuntimeSettings.WorkMode.SERVER && !localFallback;
    }

    static long serverResultRecheckDelayMs(int attempt) {
        if (attempt < 0 || attempt >= SERVER_RESULT_RECHECK_DELAYS_MS.length) return -1L;
        return SERVER_RESULT_RECHECK_DELAYS_MS[attempt];
    }

    static int serverResultRecheckAttemptCount() {
        return SERVER_RESULT_RECHECK_DELAYS_MS.length;
    }

    /** A fresh durable signal may re-arm only a fully exhausted bounded recheck chain. */
    static boolean mayStartServerResultRecheck(int storedAttempt, boolean allowExhausted) {
        if (storedAttempt < 0) return true;
        return allowExhausted && storedAttempt >= serverResultRecheckAttemptCount();
    }
}
