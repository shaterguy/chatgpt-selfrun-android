package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AssistantWaitRuntimeTest {
    @Test
    public void r4IndependentTimeoutFiresWithNoObserverEvents() {
        Fixture f = new Fixture();
        long epoch = f.runtime.start();

        f.scheduler.advance(AssistantWaitCoordinator.RESPONSE_TIMEOUT_MS - 1L);
        assertFalse(f.listener.hasProbe(AssistantWaitCoordinator.Source.TIMEOUT_PROBE));

        f.scheduler.advance(1L);
        assertTrue(f.listener.hasProbe(AssistantWaitCoordinator.Source.TIMEOUT_PROBE));
        assertEquals(epoch, f.listener.lastProbeEpoch);
        assertEquals(1, f.listener.timeoutFireCount);
    }

    @Test
    public void r5TimeoutCompleteInvokesCompletionBeforeAnyRecovery() {
        Fixture f = new Fixture();
        long epoch = f.runtime.start();
        f.scheduler.advance(AssistantWaitCoordinator.RESPONSE_TIMEOUT_MS);
        assertTrue(f.listener.hasProbe(AssistantWaitCoordinator.Source.TIMEOUT_PROBE));

        assertTrue(f.runtime.onProbeResult(epoch, result(
                AssistantWaitCoordinator.Source.TIMEOUT_PROBE, "COMPLETE", "done", "assistant-2")));
        assertEquals(1, f.listener.completeCount);
        assertEquals(0, f.listener.recoverCount);
        assertEquals(1, f.listener.continuationCount);
    }

    @Test
    public void r6SemanticProbeFiresEveryFortyFiveSecondsWithoutFingerprintState() {
        Fixture f = new Fixture();
        f.runtime.start();
        f.listener.observerFingerprint = "cached-same";

        f.scheduler.advance(AssistantWaitCoordinator.SEMANTIC_PROBE_INTERVAL_MS);
        assertEquals(1, f.listener.countProbe(AssistantWaitCoordinator.Source.WATCHDOG_PROBE));
        f.listener.observerFingerprint = "cached-same";
        f.scheduler.advance(AssistantWaitCoordinator.SEMANTIC_PROBE_INTERVAL_MS);
        assertEquals(2, f.listener.countProbe(AssistantWaitCoordinator.Source.WATCHDOG_PROBE));
    }

    @Test
    public void r7PauseResumeRearmsTimeoutAndCompletionCanAdvanceOnlyOnce() {
        Fixture f = new Fixture();
        long firstEpoch = f.runtime.start();
        f.scheduler.advance(60_000L);
        f.runtime.cancel();
        assertFalse(f.runtime.active());

        f.scheduler.advance(AssistantWaitCoordinator.RESPONSE_TIMEOUT_MS);
        assertEquals(0, f.listener.timeoutFireCount);

        long resumedEpoch = f.runtime.start();
        assertTrue(resumedEpoch != firstEpoch);
        f.scheduler.advance(AssistantWaitCoordinator.RESPONSE_TIMEOUT_MS);
        assertEquals(1, f.listener.timeoutFireCount);

        AssistantWaitRuntime.ProbeResult complete = result(
                AssistantWaitCoordinator.Source.TIMEOUT_PROBE, "COMPLETE", "done", "assistant-3");
        assertTrue(f.runtime.onProbeResult(resumedEpoch, complete));
        assertTrue(f.runtime.onProbeResult(resumedEpoch, complete));
        assertEquals(1, f.listener.completeCount);
        assertEquals(1, f.listener.continuationCount);
    }

    @Test
    public void r8StaleRuntimeEpochCannotMutateStateOrWakeLock() {
        Fixture f = new Fixture();
        long staleEpoch = f.runtime.start();
        f.runtime.cancel();
        long currentEpoch = f.runtime.start();
        assertTrue(currentEpoch != staleEpoch);

        int stateBefore = f.listener.stateMutations;
        int wakeBefore = f.listener.wakeLockMutations;
        assertFalse(f.runtime.onProbeResult(staleEpoch, result(
                AssistantWaitCoordinator.Source.TIMEOUT_PROBE, "COMPLETE", "old", "assistant-old")));
        assertEquals(stateBefore, f.listener.stateMutations);
        assertEquals(wakeBefore, f.listener.wakeLockMutations);
        assertEquals(0, f.listener.completeCount);

        assertTrue(f.runtime.onProbeResult(currentEpoch, result(
                AssistantWaitCoordinator.Source.OBSERVER, "WAIT", "", "")));
        assertTrue(f.listener.stateMutations > stateBefore);
    }

    @Test
    public void r9ReloadWaitUsesDelayedHydrationAndThenFindsAssistant() {
        Fixture f = new Fixture();
        long epoch = f.runtime.start();
        assertTrue(f.runtime.requestImmediate(AssistantWaitCoordinator.Source.RELOAD_PROBE));
        assertEquals(1, f.listener.countProbe(AssistantWaitCoordinator.Source.RELOAD_PROBE));

        assertTrue(f.runtime.onProbeResult(epoch, result(
                AssistantWaitCoordinator.Source.RELOAD_PROBE, "WAIT", "hydrating", "")));
        assertEquals(750L, f.listener.lastRecheckDelay);
        f.scheduler.advance(749L);
        assertEquals(1, f.listener.countProbe(AssistantWaitCoordinator.Source.RELOAD_PROBE));
        f.scheduler.advance(1L);
        assertEquals(2, f.listener.countProbe(AssistantWaitCoordinator.Source.RELOAD_PROBE));

        assertTrue(f.runtime.onProbeResult(epoch, result(
                AssistantWaitCoordinator.Source.RELOAD_PROBE, "COMPLETE", "hydrated", "assistant-4")));
        assertEquals(1, f.listener.completeCount);
        assertEquals(0, f.listener.recoverCount);
    }

    @Test
    public void hydrationRechecksAreBounded() {
        Fixture f = new Fixture();
        long epoch = f.runtime.start();
        f.runtime.requestImmediate(AssistantWaitCoordinator.Source.RELOAD_PROBE);
        long[] delays = AssistantWaitCoordinator.HYDRATION_RECHECK_DELAYS_MS;
        for (long delay : delays) {
            f.runtime.onProbeResult(epoch, result(
                    AssistantWaitCoordinator.Source.RELOAD_PROBE, "WAIT", "hydrating", ""));
            assertEquals(delay, f.listener.lastRecheckDelay);
            f.scheduler.advance(delay);
        }
        int probesBeforeFinalWait = f.listener.countProbe(AssistantWaitCoordinator.Source.RELOAD_PROBE);
        f.runtime.onProbeResult(epoch, result(
                AssistantWaitCoordinator.Source.RELOAD_PROBE, "WAIT", "still waiting", ""));
        f.scheduler.advance(60_000L);
        assertEquals(probesBeforeFinalWait, f.listener.countProbe(AssistantWaitCoordinator.Source.RELOAD_PROBE));
    }

    private static AssistantWaitRuntime.ProbeResult result(AssistantWaitCoordinator.Source source,
            String status, String text, String assistantKey) {
        return new AssistantWaitRuntime.ProbeResult(source, status, status, text, assistantKey);
    }

    private static final class Fixture {
        final FakeClock clock = new FakeClock();
        final FakeScheduler scheduler = new FakeScheduler(clock);
        final FakeListener listener = new FakeListener();
        final AssistantWaitRuntime runtime = new AssistantWaitRuntime(clock, scheduler, listener);
    }

    private static final class FakeClock implements AssistantWaitRuntime.Clock {
        long now;
        @Override public long elapsedRealtime() { return now; }
    }

    private static final class FakeScheduler implements AssistantWaitRuntime.Scheduler {
        final FakeClock clock;
        final List<Task> tasks = new ArrayList<>();
        long order;

        FakeScheduler(FakeClock clock) { this.clock = clock; }

        @Override public void postDelayed(Runnable runnable, long delayMs) {
            remove(runnable);
            tasks.add(new Task(runnable, clock.now + Math.max(0L, delayMs), order++));
        }

        @Override public void remove(Runnable runnable) {
            tasks.removeIf(task -> task.runnable == runnable);
        }

        void advance(long deltaMs) {
            long target = clock.now + Math.max(0L, deltaMs);
            while (true) {
                Task next = tasks.stream()
                        .filter(task -> task.when <= target)
                        .min(Comparator.comparingLong((Task task) -> task.when)
                                .thenComparingLong(task -> task.order))
                        .orElse(null);
                if (next == null) break;
                tasks.remove(next);
                clock.now = next.when;
                next.runnable.run();
            }
            clock.now = target;
        }
    }

    private static final class Task {
        final Runnable runnable;
        final long when;
        final long order;
        Task(Runnable runnable, long when, long order) {
            this.runnable = runnable;
            this.when = when;
            this.order = order;
        }
    }

    private static final class FakeListener implements AssistantWaitRuntime.Listener {
        final List<AssistantWaitCoordinator.Source> probes = new ArrayList<>();
        long lastProbeEpoch;
        int timeoutFireCount;
        int completeCount;
        int recoverCount;
        int continuationCount;
        int stateMutations;
        int wakeLockMutations;
        long lastRecheckDelay = -1L;
        String observerFingerprint = "";
        boolean runnable = true;

        @Override public boolean canRun() { return runnable; }

        @Override public void requestProbe(AssistantWaitCoordinator.Source source, long runtimeEpoch) {
            probes.add(source);
            lastProbeEpoch = runtimeEpoch;
        }

        @Override public void timeoutFired(long runtimeEpoch, long deadlineElapsed) {
            timeoutFireCount++;
        }

        @Override public void decision(AssistantWaitRuntime.ProbeResult result,
                AssistantWaitCoordinator.Decision decision) {
            stateMutations++;
        }

        @Override public boolean complete(AssistantWaitRuntime.ProbeResult result,
                AssistantWaitCoordinator.Decision decision) {
            completeCount++;
            continuationCount++;
            stateMutations++;
            wakeLockMutations++;
            return true;
        }

        @Override public void timeoutExtended(AssistantWaitRuntime.ProbeResult result,
                AssistantWaitCoordinator.Decision decision) {
            stateMutations++;
        }

        @Override public void recover(AssistantWaitRuntime.ProbeResult result,
                AssistantWaitCoordinator.Decision decision) {
            recoverCount++;
            stateMutations++;
            wakeLockMutations++;
        }

        @Override public void hydrationRecheckScheduled(AssistantWaitRuntime.ProbeResult result,
                AssistantWaitCoordinator.Decision decision) {
            lastRecheckDelay = decision.delayMs;
            stateMutations++;
        }

        @Override public void waiting(AssistantWaitRuntime.ProbeResult result,
                AssistantWaitCoordinator.Decision decision) {
            stateMutations++;
        }

        boolean hasProbe(AssistantWaitCoordinator.Source source) { return probes.contains(source); }
        int countProbe(AssistantWaitCoordinator.Source source) {
            int count = 0;
            for (AssistantWaitCoordinator.Source probe : probes) if (probe == source) count++;
            return count;
        }
    }
}
