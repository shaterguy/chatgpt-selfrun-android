package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.EnumMap;
import java.util.Map;

final class WakeLockController implements AutoCloseable {
    enum State {
        IDLE(false),
        AUTOMATION(true),
        RECOVERY(true),
        RATE_LIMIT(false),
        PAUSED(false),
        DONE(false),
        STOPPED(false),
        ERROR(false);

        final boolean holdsCpu;

        State(boolean holdsCpu) {
            this.holdsCpu = holdsCpu;
        }
    }

    interface Clock {
        long elapsedRealtime();
    }

    interface LockHandle {
        boolean isHeld();
        void acquire();
        void release();
    }

    static final class Transition {
        final State previousState;
        final State state;
        final boolean held;
        final boolean stateChanged;
        final boolean acquired;
        final boolean released;
        final long totalHeldMs;
        final long acquireCount;
        final long releaseCount;

        Transition(State previousState, State state, boolean held, boolean stateChanged,
                boolean acquired, boolean released, long totalHeldMs,
                long acquireCount, long releaseCount) {
            this.previousState = previousState;
            this.state = state;
            this.held = held;
            this.stateChanged = stateChanged;
            this.acquired = acquired;
            this.released = released;
            this.totalHeldMs = totalHeldMs;
            this.acquireCount = acquireCount;
            this.releaseCount = releaseCount;
        }

        boolean materiallyChanged() {
            return stateChanged || acquired || released;
        }
    }

    static final class Metrics {
        final State state;
        final boolean held;
        final long totalHeldMs;
        final long acquireCount;
        final long releaseCount;
        final Map<State, Long> heldMsByState;

        Metrics(State state, boolean held, long totalHeldMs, long acquireCount,
                long releaseCount, Map<State, Long> heldMsByState) {
            this.state = state;
            this.held = held;
            this.totalHeldMs = totalHeldMs;
            this.acquireCount = acquireCount;
            this.releaseCount = releaseCount;
            this.heldMsByState = heldMsByState;
        }

        long heldMs(State value) {
            Long duration = heldMsByState.get(value);
            return duration == null ? 0L : duration;
        }
    }

    private static final class AndroidLockHandle implements LockHandle {
        private final PowerManager.WakeLock wakeLock;

        AndroidLockHandle(PowerManager.WakeLock wakeLock) {
            this.wakeLock = wakeLock;
        }

        @Override public boolean isHeld() { return wakeLock.isHeld(); }
        @Override public void acquire() { wakeLock.acquire(); }
        @Override public void release() { wakeLock.release(); }
    }

    private final LockHandle lock;
    private final Clock clock;
    private final EnumMap<State, Long> heldMsByState = new EnumMap<>(State.class);
    private State state = State.IDLE;
    private long stateStartedElapsed;
    private long heldStartedElapsed = -1L;
    private long totalHeldMs;
    private long acquireCount;
    private long releaseCount;
    private boolean closed;

    static WakeLockController create(Context context, String tag) {
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power == null) throw new IllegalStateException("PowerManager unavailable");
        PowerManager.WakeLock wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        wakeLock.setReferenceCounted(false);
        return new WakeLockController(new AndroidLockHandle(wakeLock), SystemClock::elapsedRealtime);
    }

    WakeLockController(LockHandle lock, Clock clock) {
        this.lock = lock;
        this.clock = clock;
        this.stateStartedElapsed = clock.elapsedRealtime();
    }

    synchronized Transition apply(State requestedState, String reason) {
        State next = requestedState == null ? State.ERROR : requestedState;
        State previous = state;
        boolean heldBefore = lock.isHeld();
        boolean acquired = false;
        boolean released = false;
        long now = clock.elapsedRealtime();

        if (closed && next.holdsCpu) next = State.STOPPED;

        boolean stateChanged = previous != next;
        if (stateChanged) {
            accountCurrentState(now, heldBefore);
            state = next;
            stateStartedElapsed = now;
        }

        boolean desiredHeld = !closed && state.holdsCpu;
        if (desiredHeld && !heldBefore) {
            lock.acquire();
            acquireCount = saturatingIncrement(acquireCount);
            heldStartedElapsed = now;
            acquired = true;
        } else if (!desiredHeld && heldBefore) {
            accountTotalHeld(now);
            lock.release();
            releaseCount = saturatingIncrement(releaseCount);
            heldStartedElapsed = -1L;
            released = true;
        }

        return new Transition(previous, state, lock.isHeld(), stateChanged, acquired, released,
                totalHeldAt(now), acquireCount, releaseCount);
    }

    synchronized Metrics metrics() {
        long now = clock.elapsedRealtime();
        EnumMap<State, Long> copy = new EnumMap<>(heldMsByState);
        if (lock.isHeld() && state.holdsCpu) {
            copy.put(state, safeAdd(copy.getOrDefault(state, 0L), Math.max(0L, now - stateStartedElapsed)));
        }
        return new Metrics(state, lock.isHeld(), totalHeldAt(now), acquireCount, releaseCount, copy);
    }

    synchronized boolean isClosed() {
        return closed;
    }

    synchronized State state() {
        return state;
    }

    synchronized void close(String reason) {
        if (closed) {
            if (lock.isHeld()) {
                long now = clock.elapsedRealtime();
                accountCurrentState(now, true);
                accountTotalHeld(now);
                lock.release();
                releaseCount = saturatingIncrement(releaseCount);
                heldStartedElapsed = -1L;
            }
            return;
        }
        apply(State.STOPPED, reason);
        closed = true;
        if (lock.isHeld()) {
            long now = clock.elapsedRealtime();
            accountCurrentState(now, true);
            accountTotalHeld(now);
            lock.release();
            releaseCount = saturatingIncrement(releaseCount);
            heldStartedElapsed = -1L;
        }
    }

    @Override
    public void close() {
        close("close");
    }

    private void accountCurrentState(long now, boolean held) {
        if (!held || !state.holdsCpu) return;
        long elapsed = Math.max(0L, now - stateStartedElapsed);
        heldMsByState.put(state, safeAdd(heldMsByState.getOrDefault(state, 0L), elapsed));
    }

    private void accountTotalHeld(long now) {
        if (heldStartedElapsed < 0L) return;
        totalHeldMs = safeAdd(totalHeldMs, Math.max(0L, now - heldStartedElapsed));
    }

    private long totalHeldAt(long now) {
        if (!lock.isHeld() || heldStartedElapsed < 0L) return totalHeldMs;
        return safeAdd(totalHeldMs, Math.max(0L, now - heldStartedElapsed));
    }

    private static long safeAdd(long left, long right) {
        if (right <= 0L) return left;
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }
}
