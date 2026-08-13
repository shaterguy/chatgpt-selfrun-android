package com.shaterguy.chatgptselfrun;

/**
 * Tracks the one immediate semantic probe allowed for a WAIT_ASSISTANT resume cycle.
 *
 * <p>The observer-ready message and the observer-health watchdog can race while opening the same
 * resume gate. The first valid opener owns the resume probe. A companion housekeeping callback
 * from the same execution/observer epoch must not enqueue a second immediate full DOM evaluation.
 * Real observer state/quiet events are intentionally not filtered by this helper.</p>
 */
final class ResumeProbeOneShot {
    private long executionEpoch = Long.MIN_VALUE;
    private int observerEpoch = Integer.MIN_VALUE;
    private boolean armed;
    private boolean dispatched;

    void arm(long currentExecutionEpoch) {
        executionEpoch = currentExecutionEpoch;
        observerEpoch = Integer.MIN_VALUE;
        armed = true;
        dispatched = false;
    }

    void cancel() {
        executionEpoch = Long.MIN_VALUE;
        observerEpoch = Integer.MIN_VALUE;
        armed = false;
        dispatched = false;
    }

    boolean dispatch(long currentExecutionEpoch, int currentObserverEpoch) {
        if (!armed || dispatched || executionEpoch != currentExecutionEpoch) return false;
        armed = false;
        dispatched = true;
        observerEpoch = currentObserverEpoch;
        return true;
    }

    boolean isCompanion(long currentExecutionEpoch, int currentObserverEpoch) {
        return dispatched
                && executionEpoch == currentExecutionEpoch
                && observerEpoch == currentObserverEpoch;
    }

    boolean dispatched() {
        return dispatched;
    }
}
