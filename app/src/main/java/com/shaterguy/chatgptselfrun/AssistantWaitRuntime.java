package com.shaterguy.chatgptselfrun;

/**
 * Production assistant-wait scheduler/state runtime.
 *
 * <p>The Android service supplies a Handler-backed Scheduler and elapsedRealtime Clock. Tests use
 * the exact same runtime with a deterministic fake scheduler, so timeout, semantic-probe,
 * hydration, pause/resume and stale-epoch behavior are exercised as state transitions rather than
 * source-text assertions.</p>
 */
final class AssistantWaitRuntime {
    interface Clock {
        long elapsedRealtime();
    }

    interface Scheduler {
        void postDelayed(Runnable runnable, long delayMs);
        void remove(Runnable runnable);
    }

    interface Listener {
        boolean canRun();
        void requestProbe(AssistantWaitCoordinator.Source source, long runtimeEpoch);
        void timeoutFired(long runtimeEpoch, long deadlineElapsed);
        void decision(ProbeResult result, AssistantWaitCoordinator.Decision decision);
        boolean complete(ProbeResult result, AssistantWaitCoordinator.Decision decision);
        void timeoutExtended(ProbeResult result, AssistantWaitCoordinator.Decision decision);
        void recover(ProbeResult result, AssistantWaitCoordinator.Decision decision);
        void hydrationRecheckScheduled(ProbeResult result, AssistantWaitCoordinator.Decision decision);
        void waiting(ProbeResult result, AssistantWaitCoordinator.Decision decision);
    }

    static final class ProbeResult {
        final AssistantWaitCoordinator.Source source;
        final String status;
        final String detail;
        final String text;
        final String assistantKey;

        ProbeResult(AssistantWaitCoordinator.Source source, String status, String detail,
                String text, String assistantKey) {
            this.source = source == null ? AssistantWaitCoordinator.Source.OBSERVER : source;
            this.status = status == null ? "SCRIPT_ERROR" : status;
            this.detail = detail == null ? "" : detail;
            this.text = text == null ? "" : text;
            this.assistantKey = assistantKey == null ? "" : assistantKey;
        }
    }

    private final Clock clock;
    private final Scheduler scheduler;
    private final Listener listener;
    private final AssistantWaitCoordinator coordinator = new AssistantWaitCoordinator();
    private final Runnable semanticRunnable = this::fireSemanticProbe;
    private final Runnable timeoutRunnable = this::fireTimeout;
    private final Runnable hydrationRunnable = this::fireHydrationProbe;
    private boolean completionDispatched;
    private long hydrationEpoch;

    AssistantWaitRuntime(Clock clock, Scheduler scheduler, Listener listener) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.listener = listener;
    }

    long start() {
        removeScheduled();
        completionDispatched = false;
        hydrationEpoch = 0L;
        long epoch = coordinator.begin(clock.elapsedRealtime());
        armSemanticProbe();
        armTimeout();
        return epoch;
    }

    void cancel() {
        removeScheduled();
        completionDispatched = false;
        hydrationEpoch = 0L;
        coordinator.cancel();
    }

    boolean active() {
        return coordinator.active();
    }

    long epoch() {
        return coordinator.epoch();
    }

    boolean accepts(long expectedEpoch) {
        return coordinator.accepts(expectedEpoch);
    }

    long deadlineElapsed() {
        return coordinator.deadlineElapsed();
    }

    long timeoutDelay() {
        return coordinator.timeoutDelay(clock.elapsedRealtime());
    }

    void markActivity() {
        coordinator.markActivity(clock.elapsedRealtime());
    }

    boolean requestImmediate(AssistantWaitCoordinator.Source source) {
        if (!runnable()) return false;
        listener.requestProbe(source, coordinator.epoch());
        return true;
    }

    boolean onProbeResult(long expectedEpoch, ProbeResult result) {
        if (!coordinator.accepts(expectedEpoch) || result == null) return false;
        long now = clock.elapsedRealtime();
        if (result.source == AssistantWaitCoordinator.Source.QUIET_PROBE) coordinator.markActivity(now);
        AssistantWaitCoordinator.Status probeStatus = AssistantWaitCoordinator.statusOf(result.status);
        AssistantWaitCoordinator.Decision decision = coordinator.onProbe(result.source, probeStatus, now);
        listener.decision(result, decision);

        if (decision.action == AssistantWaitCoordinator.Action.COMPLETE) {
            if (completionDispatched) return true;
            boolean consumed = listener.complete(result, decision);
            if (consumed && coordinator.accepts(expectedEpoch)) {
                completionDispatched = true;
                removeScheduled();
            }
            return true;
        }
        if (decision.action == AssistantWaitCoordinator.Action.EXTEND_TIMEOUT) {
            armTimeout();
            listener.timeoutExtended(result, decision);
            return true;
        }
        if (decision.action == AssistantWaitCoordinator.Action.RECOVER) {
            listener.recover(result, decision);
            return true;
        }
        if (decision.action == AssistantWaitCoordinator.Action.RECHECK) {
            scheduleHydrationRecheck(expectedEpoch, decision.delayMs);
            listener.hydrationRecheckScheduled(result, decision);
            return true;
        }
        listener.waiting(result, decision);
        return true;
    }

    private boolean runnable() {
        return coordinator.active() && listener.canRun();
    }

    private void armSemanticProbe() {
        scheduler.remove(semanticRunnable);
        if (!runnable()) return;
        scheduler.postDelayed(semanticRunnable,
                coordinator.semanticProbeDelay(clock.elapsedRealtime()));
    }

    private void fireSemanticProbe() {
        if (!runnable()) return;
        long now = clock.elapsedRealtime();
        coordinator.semanticProbeScheduled(now);
        listener.requestProbe(AssistantWaitCoordinator.Source.WATCHDOG_PROBE, coordinator.epoch());
        armSemanticProbe();
    }

    private void armTimeout() {
        scheduler.remove(timeoutRunnable);
        if (!runnable()) return;
        scheduler.postDelayed(timeoutRunnable, coordinator.timeoutDelay(clock.elapsedRealtime()));
    }

    private void fireTimeout() {
        if (!runnable()) return;
        long remaining = coordinator.timeoutDelay(clock.elapsedRealtime());
        if (remaining > 0L) {
            scheduler.postDelayed(timeoutRunnable, remaining);
            return;
        }
        long epoch = coordinator.epoch();
        listener.timeoutFired(epoch, coordinator.deadlineElapsed());
        listener.requestProbe(AssistantWaitCoordinator.Source.TIMEOUT_PROBE, epoch);
    }

    private void scheduleHydrationRecheck(long expectedEpoch, long delayMs) {
        scheduler.remove(hydrationRunnable);
        hydrationEpoch = expectedEpoch;
        if (!runnable()) return;
        scheduler.postDelayed(hydrationRunnable, Math.max(0L, delayMs));
    }

    private void fireHydrationProbe() {
        long expectedEpoch = hydrationEpoch;
        hydrationEpoch = 0L;
        if (!coordinator.accepts(expectedEpoch) || !runnable()) return;
        listener.requestProbe(AssistantWaitCoordinator.Source.RELOAD_PROBE, expectedEpoch);
    }

    private void removeScheduled() {
        scheduler.remove(semanticRunnable);
        scheduler.remove(timeoutRunnable);
        scheduler.remove(hydrationRunnable);
    }
}
