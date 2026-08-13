package com.shaterguy.chatgptselfrun;

/**
 * Owns the single in-flight DOM evaluation token. A stale callback may release the
 * evaluation it actually owns, but can never clear a newer evaluation started after
 * navigation, pause/resume, recovery, or generation replacement.
 */
final class DomEvaluationLifecycle {
    private boolean inFlight;
    private long epoch;

    long begin() {
        epoch = nextEpoch(epoch);
        inFlight = true;
        return epoch;
    }

    void invalidate() {
        epoch = nextEpoch(epoch);
        inFlight = false;
    }

    boolean release(long expectedEpoch) {
        if (expectedEpoch != epoch) return false;
        inFlight = false;
        return true;
    }

    boolean inFlight() { return inFlight; }
    long epoch() { return epoch; }

    private static long nextEpoch(long value) {
        return value == Long.MAX_VALUE ? 1L : value + 1L;
    }
}
