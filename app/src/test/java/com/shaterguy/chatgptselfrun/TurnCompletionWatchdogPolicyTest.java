package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class TurnCompletionWatchdogPolicyTest {
    @Test public void watchdogTriggersOnlyAfterThirtyMinutesOfDriveWait() {
        long start = 1_000_000L;
        assertFalse(SelfRunService.turnCompletionWatchdogDue(
                SelfRunStore.PHASE_WAIT_DRIVE_COMMIT, start,
                start + SelfRunService.TURN_COMPLETION_WATCHDOG_MS - 1L));
        assertTrue(SelfRunService.turnCompletionWatchdogDue(
                SelfRunStore.PHASE_WAIT_DRIVE_COMMIT, start,
                start + SelfRunService.TURN_COMPLETION_WATCHDOG_MS));
        assertFalse(SelfRunService.turnCompletionWatchdogDue(
                SelfRunStore.PHASE_WAIT_INTERNAL_SEND, start,
                start + SelfRunService.TURN_COMPLETION_WATCHDOG_MS));
        assertFalse(SelfRunService.turnCompletionWatchdogDue(
                SelfRunStore.PHASE_WAIT_DRIVE_COMMIT, 0L, Long.MAX_VALUE));
        assertFalse(SelfRunService.turnCompletionWatchdogDue(
                SelfRunStore.PHASE_WAIT_DRIVE_COMMIT, start, start - 1L));
    }

    @Test public void onlyKnownNonStopComposerStatesMayStartRecovery() {
        assertTrue(SelfRunService.watchdogCanRecoverFromButton(SelfRunContinuationDom.SEND_ENABLED));
        assertTrue(SelfRunService.watchdogCanRecoverFromButton(SelfRunContinuationDom.SEND_DISABLED));
        assertTrue(SelfRunService.watchdogCanRecoverFromButton(SelfRunContinuationDom.COMPOSER_IDLE));
        assertFalse(SelfRunService.watchdogCanRecoverFromButton(SelfRunContinuationDom.STOP));
        assertFalse(SelfRunService.watchdogCanRecoverFromButton(SelfRunContinuationDom.UNKNOWN));
    }

    @Test public void timeoutUsesDriveRecheckBeforePlainContinuation() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("PHASE_WATCHDOG_BUTTON"));
        assertTrue(service.contains("PHASE_WATCHDOG_RECHECK"));
        assertTrue(service.contains("PHASE_WATCHDOG_SEND_CONTINUE"));
        assertTrue(service.contains("turnCompletionWatchdogDue(snapshot.phase,store.phaseStartedAt(),System.currentTimeMillis())"));
        assertTrue(service.contains("transition(PHASE_WATCHDOG_RECHECK"));
        assertTrue(service.contains("watchdogRecheck=PHASE_WATCHDOG_RECHECK.equals(snapshot.phase)"));
        assertTrue(service.contains("transition(PHASE_WATCHDOG_SEND_CONTINUE"));
        assertTrue(service.contains("PHASE_WATCHDOG_SEND_CONTINUE.equals(store.phase())?SelfRunProtocol.driveContinuation(store.runId())"));
    }

    @Test public void stopRestartsThirtyMinuteWindowWithoutComposerMutation() throws Exception {
        String service = source("SelfRunService.java");
        String handler = section(service, "private void handleWebResult", "private String driveBootstrap");
        assertTrue(handler.contains("PHASE_WATCHDOG_BUTTON.equals(phase)"));
        assertTrue(handler.contains("SelfRunContinuationDom.STOP.equals(status)"));
        assertTrue(handler.contains("transition(SelfRunStore.PHASE_WAIT_DRIVE_COMMIT"));
        assertTrue(handler.contains("watchdog_stop_still_generating"));
        assertTrue(handler.contains("watchdogCanRecoverFromButton(status)"));
        assertFalse(section(handler, "if(PHASE_WATCHDOG_BUTTON.equals(phase))", "if(SelfRunStore.PHASE_APPLY_PREFS.equals(phase)")
                .contains("clickPreparedDriveTurn"));
    }

    @Test public void watchdogSendSkipsWorkPreferenceReplay() throws Exception {
        String service = source("SelfRunService.java");
        String handler = section(service, "private void handleWebResult", "private String driveBootstrap");
        String watchdog = section(handler, "if(PHASE_WATCHDOG_BUTTON.equals(phase))", "if(SelfRunStore.PHASE_APPLY_PREFS.equals(phase)");
        assertTrue(watchdog.contains("PHASE_WATCHDOG_RECHECK"));
        assertFalse(watchdog.contains("PHASE_APPLY_PREFS"));
        assertFalse(watchdog.contains("PHASE_APPLY_REASONING"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String section(String text, String start, String end) {
        int a = text.indexOf(start);
        int b = text.indexOf(end, a + start.length());
        assertTrue(a >= 0 && b > a);
        return text.substring(a, b);
    }
}
