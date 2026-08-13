package com.shaterguy.chatgptselfrun;

final class AssistantWaitCoordinator {
    static final long SEMANTIC_PROBE_INTERVAL_MS = 45_000L;
    static final long RESPONSE_TIMEOUT_MS = 12 * 60_000L;
    static final long GENERATING_TIMEOUT_EXTENSION_MS = 3 * 60_000L;
    static final long[] HYDRATION_RECHECK_DELAYS_MS = {750L, 1_500L, 3_000L, 6_000L};

    enum Source {
        OBSERVER("observer"),
        QUIET_PROBE("quiet_probe"),
        WATCHDOG_PROBE("watchdog_probe"),
        TIMEOUT_PROBE("timeout_probe"),
        RESUME_PROBE("resume_probe"),
        RELOAD_PROBE("reload_probe");

        final String label;
        Source(String label) { this.label = label; }

        static Source fromTrigger(String trigger) {
            if (trigger == null) return OBSERVER;
            for (Source source : values()) if (source.label.equals(trigger)) return source;
            if (trigger.startsWith("observer")) return OBSERVER;
            return OBSERVER;
        }
    }

    enum Status { COMPLETE, GENERATING, WAIT, STALE, FAILED }
    enum Action { COMPLETE, WAIT, RECHECK, EXTEND_TIMEOUT, RECOVER }

    static final class Decision {
        final Action action;
        final long delayMs;
        final long phaseAgeMs;
        final long activityAgeMs;

        Decision(Action action, long delayMs, long phaseAgeMs, long activityAgeMs) {
            this.action = action;
            this.delayMs = Math.max(0L, delayMs);
            this.phaseAgeMs = Math.max(0L, phaseAgeMs);
            this.activityAgeMs = Math.max(0L, activityAgeMs);
        }
    }

    private boolean active;
    private long epoch;
    private long startedElapsed;
    private long deadlineElapsed;
    private long lastActivityElapsed;
    private long lastSemanticProbeElapsed;
    private int hydrationAttempt;

    long begin(long nowElapsed) {
        active = true;
        epoch = nextEpoch(epoch);
        startedElapsed = nowElapsed;
        deadlineElapsed = safeAdd(nowElapsed, RESPONSE_TIMEOUT_MS);
        lastActivityElapsed = nowElapsed;
        lastSemanticProbeElapsed = nowElapsed;
        hydrationAttempt = 0;
        return epoch;
    }

    long rearm(long nowElapsed, boolean resetTimeoutWindow) {
        if (!active || resetTimeoutWindow) return begin(nowElapsed);
        epoch = nextEpoch(epoch);
        hydrationAttempt = 0;
        return epoch;
    }

    void cancel() {
        active = false;
        epoch = nextEpoch(epoch);
        hydrationAttempt = 0;
    }

    boolean active() { return active; }
    long epoch() { return epoch; }
    long startedElapsed() { return startedElapsed; }
    long deadlineElapsed() { return deadlineElapsed; }

    boolean accepts(long expectedEpoch) {
        return active && expectedEpoch == epoch;
    }

    long timeoutDelay(long nowElapsed) {
        if (!active) return Long.MAX_VALUE;
        return Math.max(0L, deadlineElapsed - nowElapsed);
    }

    long semanticProbeDelay(long nowElapsed) {
        if (!active) return Long.MAX_VALUE;
        long due = safeAdd(lastSemanticProbeElapsed, SEMANTIC_PROBE_INTERVAL_MS);
        return Math.max(0L, due - nowElapsed);
    }

    void semanticProbeScheduled(long nowElapsed) {
        if (active) lastSemanticProbeElapsed = nowElapsed;
    }

    void markActivity(long nowElapsed) {
        if (active) lastActivityElapsed = Math.max(lastActivityElapsed, nowElapsed);
    }

    Decision onProbe(Source source, Status status, long nowElapsed) {
        long phaseAge = age(nowElapsed, startedElapsed);
        long activityAge = age(nowElapsed, lastActivityElapsed);
        if (!active) return new Decision(Action.WAIT, 0L, phaseAge, activityAge);

        if (status == Status.COMPLETE) {
            hydrationAttempt = 0;
            return new Decision(Action.COMPLETE, 0L, phaseAge, activityAge);
        }
        if (status == Status.GENERATING) {
            markActivity(nowElapsed);
            activityAge = 0L;
            hydrationAttempt = 0;
            if (source == Source.TIMEOUT_PROBE) {
                deadlineElapsed = safeAdd(nowElapsed, GENERATING_TIMEOUT_EXTENSION_MS);
                return new Decision(Action.EXTEND_TIMEOUT, GENERATING_TIMEOUT_EXTENSION_MS, phaseAge, activityAge);
            }
            return new Decision(Action.WAIT, 0L, phaseAge, activityAge);
        }
        if (source == Source.TIMEOUT_PROBE) {
            hydrationAttempt = 0;
            return new Decision(Action.RECOVER, 0L, phaseAge, activityAge);
        }
        if ((source == Source.RELOAD_PROBE || source == Source.RESUME_PROBE)
                && (status == Status.WAIT || status == Status.STALE || status == Status.FAILED)) {
            return nextHydrationDecision(phaseAge, activityAge);
        }
        if (source == Source.RELOAD_PROBE && status == Status.GENERATING) hydrationAttempt = 0;
        return new Decision(Action.WAIT, 0L, phaseAge, activityAge);
    }

    Decision onHydrationRecheck(Status status, long nowElapsed) {
        long phaseAge = age(nowElapsed, startedElapsed);
        long activityAge = age(nowElapsed, lastActivityElapsed);
        if (status == Status.COMPLETE) {
            hydrationAttempt = 0;
            return new Decision(Action.COMPLETE, 0L, phaseAge, activityAge);
        }
        if (status == Status.GENERATING) {
            markActivity(nowElapsed);
            hydrationAttempt = 0;
            return new Decision(Action.WAIT, 0L, phaseAge, 0L);
        }
        if (status == Status.WAIT || status == Status.STALE || status == Status.FAILED) {
            return nextHydrationDecision(phaseAge, activityAge);
        }
        return new Decision(Action.WAIT, 0L, phaseAge, activityAge);
    }

    private Decision nextHydrationDecision(long phaseAge, long activityAge) {
        if (hydrationAttempt >= HYDRATION_RECHECK_DELAYS_MS.length) {
            return new Decision(Action.WAIT, 0L, phaseAge, activityAge);
        }
        long delay = HYDRATION_RECHECK_DELAYS_MS[hydrationAttempt++];
        return new Decision(Action.RECHECK, delay, phaseAge, activityAge);
    }

    static Status statusOf(String status) {
        if ("COMPLETE".equals(status)) return Status.COMPLETE;
        if ("GENERATING".equals(status)) return Status.GENERATING;
        if ("WAIT".equals(status) || "UI_WAIT".equals(status)) return Status.WAIT;
        if ("STALE".equals(status)) return Status.STALE;
        return Status.FAILED;
    }

    static boolean callbackCurrent(String expectedRunId, String currentRunId,
            int expectedGeneration, int currentGeneration, int expectedObserverEpoch, int currentObserverEpoch,
            long expectedExecutionEpoch, long currentExecutionEpoch) {
        return expectedRunId != null && expectedRunId.equals(currentRunId)
                && expectedGeneration == currentGeneration
                && expectedObserverEpoch == currentObserverEpoch
                && expectedExecutionEpoch == currentExecutionEpoch;
    }

    private static long age(long now, long then) {
        if (then <= 0L || now <= then) return 0L;
        return now - then;
    }

    private static long nextEpoch(long value) {
        return value == Long.MAX_VALUE ? 1L : value + 1L;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
