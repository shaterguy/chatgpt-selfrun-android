package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AssistantWaitCoordinatorTest {
    @Test
    public void watchdogSemanticProbeRunsIndependentlyOfObserverEvents() {
        AssistantWaitCoordinator coordinator = new AssistantWaitCoordinator();
        coordinator.begin(1_000L);
        assertEquals(45_000L, coordinator.semanticProbeDelay(1_000L));
        coordinator.markActivity(20_000L);
        assertEquals(1L, coordinator.semanticProbeDelay(45_999L));
        assertEquals(0L, coordinator.semanticProbeDelay(46_000L));
        coordinator.semanticProbeScheduled(46_000L);
        assertEquals(45_000L, coordinator.semanticProbeDelay(46_000L));
    }

    @Test
    public void timeoutRunnableBecomesDueWithoutAnyObserverEvent() {
        AssistantWaitCoordinator coordinator = new AssistantWaitCoordinator();
        coordinator.begin(10_000L);
        assertEquals(AssistantWaitCoordinator.RESPONSE_TIMEOUT_MS, coordinator.timeoutDelay(10_000L));
        assertEquals(0L, coordinator.timeoutDelay(10_000L + AssistantWaitCoordinator.RESPONSE_TIMEOUT_MS));
    }

    @Test
    public void timeoutProbeCompleteWinsBeforeRecovery() {
        AssistantWaitCoordinator coordinator = new AssistantWaitCoordinator();
        coordinator.begin(1_000L);
        AssistantWaitCoordinator.Decision decision = coordinator.onProbe(
                AssistantWaitCoordinator.Source.TIMEOUT_PROBE,
                AssistantWaitCoordinator.Status.COMPLETE,
                1_000L + AssistantWaitCoordinator.RESPONSE_TIMEOUT_MS);
        assertEquals(AssistantWaitCoordinator.Action.COMPLETE, decision.action);
    }

    @Test
    public void timeoutProbeGeneratingUsesExplicitFiniteExtension() {
        AssistantWaitCoordinator coordinator = new AssistantWaitCoordinator();
        coordinator.begin(1_000L);
        long fireAt = 1_000L + AssistantWaitCoordinator.RESPONSE_TIMEOUT_MS;
        AssistantWaitCoordinator.Decision decision = coordinator.onProbe(
                AssistantWaitCoordinator.Source.TIMEOUT_PROBE,
                AssistantWaitCoordinator.Status.GENERATING, fireAt);
        assertEquals(AssistantWaitCoordinator.Action.EXTEND_TIMEOUT, decision.action);
        assertEquals(AssistantWaitCoordinator.GENERATING_TIMEOUT_EXTENSION_MS, decision.delayMs);
        assertEquals(AssistantWaitCoordinator.GENERATING_TIMEOUT_EXTENSION_MS, coordinator.timeoutDelay(fireAt));
    }

    @Test
    public void timeoutProbeWaitRequestsRecovery() {
        AssistantWaitCoordinator coordinator = new AssistantWaitCoordinator();
        coordinator.begin(1_000L);
        AssistantWaitCoordinator.Decision decision = coordinator.onProbe(
                AssistantWaitCoordinator.Source.TIMEOUT_PROBE,
                AssistantWaitCoordinator.Status.WAIT,
                1_000L + AssistantWaitCoordinator.RESPONSE_TIMEOUT_MS);
        assertEquals(AssistantWaitCoordinator.Action.RECOVER, decision.action);
    }

    @Test
    public void resumeProbeWaitUsesBoundedRechecksWithoutSendAction() {
        AssistantWaitCoordinator coordinator = new AssistantWaitCoordinator();
        coordinator.begin(1_000L);
        AssistantWaitCoordinator.Decision decision = coordinator.onProbe(
                AssistantWaitCoordinator.Source.RESUME_PROBE,
                AssistantWaitCoordinator.Status.WAIT, 2_000L);
        assertEquals(AssistantWaitCoordinator.Action.RECHECK, decision.action);
        assertEquals(750L, decision.delayMs);
        long now = 2_750L;
        for (int i = 1; i < AssistantWaitCoordinator.HYDRATION_RECHECK_DELAYS_MS.length; i++) {
            decision = coordinator.onProbe(AssistantWaitCoordinator.Source.RELOAD_PROBE,
                    AssistantWaitCoordinator.Status.WAIT, now);
            assertEquals(AssistantWaitCoordinator.Action.RECHECK, decision.action);
            assertEquals(AssistantWaitCoordinator.HYDRATION_RECHECK_DELAYS_MS[i], decision.delayMs);
            now += decision.delayMs;
        }
        decision = coordinator.onProbe(AssistantWaitCoordinator.Source.RELOAD_PROBE,
                AssistantWaitCoordinator.Status.WAIT, now);
        assertEquals(AssistantWaitCoordinator.Action.WAIT, decision.action);
    }

    @Test
    public void reloadFirstWaitThenHydrationCompleteResumesNormally() {
        AssistantWaitCoordinator coordinator = new AssistantWaitCoordinator();
        coordinator.begin(1_000L);
        AssistantWaitCoordinator.Decision first = coordinator.onProbe(
                AssistantWaitCoordinator.Source.RELOAD_PROBE,
                AssistantWaitCoordinator.Status.WAIT, 2_000L);
        assertEquals(AssistantWaitCoordinator.Action.RECHECK, first.action);
        AssistantWaitCoordinator.Decision second = coordinator.onProbe(
                AssistantWaitCoordinator.Source.RELOAD_PROBE,
                AssistantWaitCoordinator.Status.COMPLETE, 2_750L);
        assertEquals(AssistantWaitCoordinator.Action.COMPLETE, second.action);
    }

    @Test
    public void staleGenerationObserverOrExecutionCallbackIsRejected() {
        assertFalse(AssistantWaitCoordinator.callbackCurrent("run", "run", 3, 4, 7, 7, 10L, 10L));
        assertFalse(AssistantWaitCoordinator.callbackCurrent("run", "run", 3, 3, 6, 7, 10L, 10L));
        assertFalse(AssistantWaitCoordinator.callbackCurrent("run", "run", 3, 3, 7, 7, 9L, 10L));
        assertFalse(AssistantWaitCoordinator.callbackCurrent("run", "other", 3, 3, 7, 7, 10L, 10L));
        assertTrue(AssistantWaitCoordinator.callbackCurrent("run", "run", 3, 3, 7, 7, 10L, 10L));
    }

    @Test
    public void pauseCancellationPreventsOldEpochFromReviving() {
        AssistantWaitCoordinator coordinator = new AssistantWaitCoordinator();
        long firstEpoch = coordinator.begin(1_000L);
        assertTrue(coordinator.accepts(firstEpoch));
        coordinator.cancel();
        assertFalse(coordinator.accepts(firstEpoch));
        long secondEpoch = coordinator.begin(5_000L);
        assertFalse(firstEpoch == secondEpoch);
        assertFalse(coordinator.accepts(firstEpoch));
        assertTrue(coordinator.accepts(secondEpoch));
    }
}
