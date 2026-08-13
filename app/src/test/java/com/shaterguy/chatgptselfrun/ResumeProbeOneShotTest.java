package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResumeProbeOneShotTest {
    @Test
    public void watchdogFirstThenReadyLateDispatchesExactlyOnce() {
        ResumeProbeOneShot oneShot = new ResumeProbeOneShot();
        oneShot.arm(11L);

        assertTrue(oneShot.dispatch(11L, 7));
        assertTrue(oneShot.isCompanion(11L, 7));
        assertFalse("late ready must not own another resume probe", oneShot.dispatch(11L, 7));
    }

    @Test
    public void readyFirstThenWatchdogLateDispatchesExactlyOnce() {
        ResumeProbeOneShot oneShot = new ResumeProbeOneShot();
        oneShot.arm(22L);

        assertTrue(oneShot.dispatch(22L, 9));
        assertTrue(oneShot.isCompanion(22L, 9));
        assertFalse("late health callback must not own another resume probe", oneShot.dispatch(22L, 9));
    }

    @Test
    public void staleExecutionOrObserverIsNeverTreatedAsCompanion() {
        ResumeProbeOneShot oneShot = new ResumeProbeOneShot();
        oneShot.arm(33L);
        assertTrue(oneShot.dispatch(33L, 12));

        assertFalse(oneShot.isCompanion(34L, 12));
        assertFalse(oneShot.isCompanion(33L, 13));
    }

    @Test
    public void cancelPreventsOldResumeCycleFromSuppressingNewWork() {
        ResumeProbeOneShot oneShot = new ResumeProbeOneShot();
        oneShot.arm(44L);
        assertTrue(oneShot.dispatch(44L, 3));
        oneShot.cancel();

        assertFalse(oneShot.isCompanion(44L, 3));
        oneShot.arm(45L);
        assertTrue(oneShot.dispatch(45L, 4));
    }
}
