package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WakeLockControllerTest {
    @Test
    public void repeatedSemanticStateIsIdempotentAndHoldStatesShareOneLock() {
        FakeLock lock = new FakeLock();
        FakeClock clock = new FakeClock();
        WakeLockController controller = new WakeLockController(lock, clock);

        controller.apply(WakeLockController.State.AUTOMATION, "bootstrap");
        controller.apply(WakeLockController.State.AUTOMATION, "observer_duplicate");
        controller.apply(WakeLockController.State.AUTOMATION, "watchdog_duplicate");
        assertTrue(lock.held);
        assertEquals(1, lock.acquireCount);
        assertEquals(0, lock.releaseCount);

        clock.advance(1000L);
        controller.apply(WakeLockController.State.RECOVERY, "renderer_recovery");
        assertTrue(lock.held);
        assertEquals(1, lock.acquireCount);
        assertEquals(0, lock.releaseCount);

        clock.advance(500L);
        controller.apply(WakeLockController.State.AUTOMATION, "recovery_complete");
        assertTrue(lock.held);
        assertEquals(1, lock.acquireCount);
        assertEquals(0, lock.releaseCount);
    }

    @Test
    public void pauseRateLimitDoneAndStopReleaseImmediatelyAndResumeReacquires() {
        FakeLock lock = new FakeLock();
        FakeClock clock = new FakeClock();
        WakeLockController controller = new WakeLockController(lock, clock);

        controller.apply(WakeLockController.State.AUTOMATION, "start");
        controller.apply(WakeLockController.State.RATE_LIMIT, "http_429");
        assertFalse(lock.held);
        assertEquals(1, lock.releaseCount);

        controller.apply(WakeLockController.State.AUTOMATION, "rate_limit_expired");
        assertTrue(lock.held);
        assertEquals(2, lock.acquireCount);

        controller.apply(WakeLockController.State.PAUSED, "user_pause");
        assertFalse(lock.held);
        assertEquals(2, lock.releaseCount);

        controller.apply(WakeLockController.State.AUTOMATION, "resume_prepare");
        assertTrue(lock.held);
        assertEquals(3, lock.acquireCount);

        controller.apply(WakeLockController.State.DONE, "terminal");
        assertFalse(lock.held);
        assertEquals(3, lock.releaseCount);

        controller.close("stop");
        controller.apply(WakeLockController.State.AUTOMATION, "late_callback");
        assertFalse(lock.held);
        assertEquals(3, lock.acquireCount);
        assertTrue(controller.isClosed());
    }

    @Test
    public void metricsSeparateAutomationAndRecoveryHeldDurations() {
        FakeLock lock = new FakeLock();
        FakeClock clock = new FakeClock();
        WakeLockController controller = new WakeLockController(lock, clock);

        controller.apply(WakeLockController.State.AUTOMATION, "start");
        clock.advance(1200L);
        controller.apply(WakeLockController.State.RECOVERY, "recovery");
        clock.advance(300L);
        controller.apply(WakeLockController.State.PAUSED, "pause");
        clock.advance(5000L);

        WakeLockController.Metrics metrics = controller.metrics();
        assertFalse(metrics.held);
        assertEquals(1500L, metrics.totalHeldMs);
        assertEquals(1200L, metrics.heldMs(WakeLockController.State.AUTOMATION));
        assertEquals(300L, metrics.heldMs(WakeLockController.State.RECOVERY));
        assertEquals(0L, metrics.heldMs(WakeLockController.State.PAUSED));
        assertEquals(1L, metrics.acquireCount);
        assertEquals(1L, metrics.releaseCount);
    }

    @Test
    public void closeIsIdempotentAndNeverLeavesHeldState() {
        FakeLock lock = new FakeLock();
        FakeClock clock = new FakeClock();
        WakeLockController controller = new WakeLockController(lock, clock);

        controller.apply(WakeLockController.State.RECOVERY, "recover");
        clock.advance(42L);
        controller.close("destroy");
        controller.close("destroy_again");

        assertFalse(lock.held);
        assertEquals(1, lock.acquireCount);
        assertEquals(1, lock.releaseCount);
        assertEquals(WakeLockController.State.STOPPED, controller.state());
        assertEquals(42L, controller.metrics().totalHeldMs);
    }

    private static final class FakeClock implements WakeLockController.Clock {
        long now;
        @Override public long elapsedRealtime() { return now; }
        void advance(long millis) { now += millis; }
    }

    private static final class FakeLock implements WakeLockController.LockHandle {
        boolean held;
        int acquireCount;
        int releaseCount;

        @Override public boolean isHeld() { return held; }

        @Override public void acquire() {
            if (held) throw new AssertionError("duplicate acquire");
            held = true;
            acquireCount++;
        }

        @Override public void release() {
            if (!held) throw new AssertionError("duplicate release");
            held = false;
            releaseCount++;
        }
    }
}
