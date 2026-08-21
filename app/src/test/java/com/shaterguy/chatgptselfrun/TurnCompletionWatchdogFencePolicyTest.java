package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class TurnCompletionWatchdogFencePolicyTest {
    @Test public void watchdogRecoverySubmissionPhasesStayPlainContinuationOwned() {
        assertTrue(SelfRunService.isWatchdogRecoverySubmissionPhase("WATCHDOG_SEND_CONTINUE"));
        assertTrue(SelfRunService.isWatchdogRecoverySubmissionPhase("WATCHDOG_CLICK_CONTINUE"));
        assertFalse(SelfRunService.isWatchdogRecoverySubmissionPhase(SelfRunStore.PHASE_SEND_CONTINUE));
    }

    @Test public void preparedRecoveryMustEnterFinalDriveFenceBeforeClick() throws Exception {
        String service = source("SelfRunService.java");
        String handler = section(service, "private void handleWebResult", "private String driveBootstrap");
        String watchdogPrepared = section(handler,
                "if(PHASE_WATCHDOG_SEND_CONTINUE.equals(phase)&&\"READY_TO_SUBMIT\".equals(status))",
                "scheduleWeb(750L)");
        assertTrue(watchdogPrepared.contains("transition(PHASE_WATCHDOG_FINAL_RECHECK"));
        assertTrue(watchdogPrepared.contains("authorizeAndRunDrive"));
        assertFalse(watchdogPrepared.contains("clickPreparedDriveTurn"));
    }

    @Test public void finalFenceConsumesLateDriveSignalsBeforeOpeningClickPhase() throws Exception {
        String service = source("SelfRunService.java");
        String drivePhases = section(service, "private static boolean drivePhase", "static boolean shouldContinueSamePhaseDriveStep");
        assertTrue(drivePhases.contains("PHASE_WATCHDOG_FINAL_RECHECK"));
        assertFalse(drivePhases.contains("PHASE_WATCHDOG_CLICK_CONTINUE"));

        String poll = section(service, "private void pollDriveNow", "private void replayTerminalSideEffect");
        int fence = poll.indexOf("if(watchdogFinalRecheck)");
        int applySignals = poll.indexOf("store.applyDriveSignals(scan.unseen,System.currentTimeMillis())", fence);
        int stillFence = poll.indexOf("if(PHASE_WATCHDOG_FINAL_RECHECK.equals(store.phase()))", fence);
        int openClick = poll.indexOf("transition(PHASE_WATCHDOG_CLICK_CONTINUE", fence);
        int clearAttempt = poll.indexOf("clearContinuationAttempt", fence);
        assertTrue(fence >= 0);
        assertTrue(applySignals > fence);
        assertTrue(stillFence > applySignals);
        assertTrue(openClick > stillFence);
        assertTrue(clearAttempt > openClick);
    }

    @Test public void clickPhaseUsesOnlyPreparedClickAndRestartsFenceIfStateChanges() throws Exception {
        String service = source("SelfRunService.java");
        String webStep = section(service, "private void runWebStep", "private void evaluate");
        assertTrue(webStep.contains("case PHASE_WATCHDOG_CLICK_CONTINUE"));
        assertTrue(webStep.contains("clickPreparedDriveTurn"));

        String evaluate = section(service, "private void evaluate", "private void recordContinuationWait");
        assertTrue(evaluate.contains("PHASE_WATCHDOG_CLICK_CONTINUE.equals(phase)&&SelfRunContinuationDom.STOP.equals(status)"));
        assertTrue(evaluate.contains("transition(SelfRunStore.PHASE_WAIT_DRIVE_COMMIT"));
        assertTrue(evaluate.contains("watchdog_final_click_reprepare"));
        assertTrue(evaluate.contains("transition(PHASE_WATCHDOG_SEND_CONTINUE"));
    }

    @Test public void watchdogMarkerDoesNotDependOnPhaseStartAcrossFenceOrRestart() throws Exception {
        String service = source("SelfRunService.java");
        String marker = section(service, "private String continuationMarkerId", "private void clearContinuationAttempt");
        assertTrue(marker.contains("isWatchdogRecoverySubmissionPhase(store.phase())"));
        assertTrue(marker.contains("\":watchdog-continue:\"+store.driveSignalCursor()"));
        assertTrue(marker.contains("store.phaseStartedAt()"));
        assertTrue(marker.indexOf("\":watchdog-continue:\"+store.driveSignalCursor()") < marker.indexOf("store.phaseStartedAt()"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String section(String text, String start, String end) {
        int a = text.indexOf(start);
        int b = text.indexOf(end, a + start.length());
        assertTrue("missing start: " + start, a >= 0);
        assertTrue("missing end: " + end, b > a);
        return text.substring(a, b);
    }
}
