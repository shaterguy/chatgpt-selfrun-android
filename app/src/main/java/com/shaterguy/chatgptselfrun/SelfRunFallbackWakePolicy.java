package com.shaterguy.chatgptselfrun;

final class SelfRunFallbackWakePolicy {
    static final long MIN_WAKE_DELAY_MS = 120_000L;

    private SelfRunFallbackWakePolicy() { }

    static long effectiveDelayMs(long requestedDelayMs) {
        return Math.max(MIN_WAKE_DELAY_MS, Math.max(0L, requestedDelayMs));
    }

    static long scheduledAt(long nowMs, long requestedDelayMs) {
        return saturatingAdd(Math.max(0L, nowMs), effectiveDelayMs(requestedDelayMs));
    }

    static long delayMs(long scheduledAtMs, long actualAtMs) {
        if (scheduledAtMs <= 0L || actualAtMs <= scheduledAtMs) return 0L;
        return actualAtMs - scheduledAtMs;
    }

    static long saturatingAdd(long base, long delta) {
        long safeBase = Math.max(0L, base);
        long safeDelta = Math.max(0L, delta);
        if (safeBase > Long.MAX_VALUE - safeDelta) return Long.MAX_VALUE;
        return safeBase + safeDelta;
    }
}
