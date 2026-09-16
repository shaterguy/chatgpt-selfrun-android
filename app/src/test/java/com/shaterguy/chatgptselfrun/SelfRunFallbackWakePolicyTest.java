package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRunFallbackWakePolicyTest {
    @Test public void shortHandlerPollGetsLowFrequencyWakeBackstop() {
        assertEquals(120_000L, SelfRunFallbackWakePolicy.effectiveDelayMs(30_000L));
        assertEquals(180_000L, SelfRunFallbackWakePolicy.effectiveDelayMs(180_000L));
        assertEquals(Long.MAX_VALUE, SelfRunFallbackWakePolicy.saturatingAdd(Long.MAX_VALUE - 10L, 20L));
    }

    @Test public void wakeDelayTelemetryNeverReportsNegativeDelay() {
        assertEquals(0L, SelfRunFallbackWakePolicy.delayMs(1_000L, 999L));
        assertEquals(0L, SelfRunFallbackWakePolicy.delayMs(0L, 5_000L));
        assertEquals(750L, SelfRunFallbackWakePolicy.delayMs(1_000L, 1_750L));
    }

    @Test public void scheduledWallClockUsesTheSameEffectiveBackstopAsTheAlarm() {
        long scheduledAt = SelfRunFallbackWakePolicy.scheduledAt(1_000_000L, 30_000L);
        assertEquals(1_120_000L, scheduledAt);
        assertEquals(3_250L, SelfRunFallbackWakePolicy.delayMs(scheduledAt, 1_123_250L));
    }

    @Test public void schedulerUsesOneShotIdleAwareWakeWithoutPersistentWakeLock() throws Exception {
        String scheduler = source("SelfRunFallbackWakeScheduler.java");
        String coordinator = source("SelfRun3Coordinator.java");
        String service = source("SelfRunService.java");
        assertTrue(scheduler.contains("AlarmManager.ELAPSED_REALTIME_WAKEUP"));
        assertTrue(scheduler.contains("setAndAllowWhileIdle"));
        assertTrue(scheduler.contains("FLAG_UPDATE_CURRENT"));
        assertTrue(scheduler.contains("FLAG_NO_CREATE"));
        assertFalse(scheduler.contains("setRepeating"));
        assertFalse(scheduler.contains("setExactAndAllowWhileIdle"));
        assertFalse(scheduler.contains("PowerManager"));
        assertTrue(scheduler.contains("localDueAtWallMs = scheduledAtWall;"));
        assertTrue(scheduler.contains("recordResultPollTiming(dueAt, actualAt, \"LOCAL_DELAY_DUE\")"));
        assertTrue(scheduler.contains("onAlarmFired()"));
        assertTrue(coordinator.contains("SelfRunFallbackWakeScheduler.schedule(service, safeDelay)"));
        assertTrue(coordinator.contains("SelfRunFallbackWakeScheduler.cancel(service)"));
        assertTrue(coordinator.contains("releaseWakeLock();"));
        assertTrue(service.contains("ACTION_RESULT_POLL_WAKE"));
        assertTrue(service.contains("recordResultPollTiming(scheduledAt, actualAt, \"ALARM_MANAGER\")"));
        assertTrue(service.contains("scheduledAt="));
        assertTrue(service.contains("actualAt="));
        assertTrue(service.contains("delayMs="));
        assertTrue(service.contains("wakeReason="));
        assertTrue(service.contains("interactive="));
        assertTrue(service.contains("deviceIdle="));
        assertTrue(service.contains("powerSave="));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
