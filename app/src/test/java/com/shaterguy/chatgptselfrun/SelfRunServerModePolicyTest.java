package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRunServerModePolicyTest {
    @Test public void serverModeUsesPushUnlessThatExecutionFellBackLocally() {
        assertTrue(SelfRunServerWaitPolicy.useServerPush(SelfRun3RuntimeSettings.WorkMode.SERVER, false));
        assertFalse(SelfRunServerWaitPolicy.useServerPush(SelfRun3RuntimeSettings.WorkMode.SERVER, true));
        assertFalse(SelfRunServerWaitPolicy.useServerPush(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE, false));
    }

    @Test public void recoveryWatchdogUsesWorkManagerMinimumFifteenMinutePeriod() {
        assertEquals(15L, SelfRunServerWaitPolicy.RECOVERY_INTERVAL_MINUTES);
    }

    @Test public void foregroundServiceAcceptsPushAndRecoveryActions() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("ACTION_PUSH_RESULT = BuildConfig.APPLICATION_ID + \".PUSH_RESULT\""));
        assertTrue(service.contains("ACTION_SERVER_RECOVERY = BuildConfig.APPLICATION_ID + \".SERVER_RECOVERY\""));
        assertTrue(service.contains("coordinator.onPushResult"));
        assertTrue(service.contains("coordinator.onServerRecovery"));
    }

    @Test public void coordinatorSeparatesServerWaitFromShortPollingAndLogsFallback() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("runtimeSettings.workMode()"));
        assertTrue(coordinator.contains("SelfRunServerWatch"));
        assertTrue(coordinator.contains("SelfRunServerRecoveryWorker.schedule"));
        assertTrue(coordinator.contains("SelfRunServerRecoveryWorker.cancel"));
        assertTrue(coordinator.contains("V3_SERVER_PUSH_FALLBACK"));
        assertTrue(coordinator.contains("SelfRunPushAckOutbox.AckState.PROCESSED"));
    }

    @Test public void recoveryWorkerIsPeriodicAndOnlyWakesActiveServerRuns() throws Exception {
        String worker = source("SelfRunServerRecoveryWorker.java");
        assertTrue(worker.contains("PeriodicWorkRequest"));
        assertTrue(worker.contains("15, TimeUnit.MINUTES"));
        assertTrue(worker.contains("ACTION_SERVER_RECOVERY"));
        assertTrue(worker.contains("WorkMode.SERVER"));
        assertTrue(worker.contains("store.active()"));
        assertTrue(worker.contains("store.paused()"));
        assertTrue(worker.contains("store.userStopped()"));
    }

    @Test public void changingWorkModeWakesCoordinatorWithoutRestartingTask() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("KEY_WORK_MODE"));
        assertTrue(coordinator.contains("registerOnSharedPreferenceChangeListener"));
        assertTrue(coordinator.contains("unregisterOnSharedPreferenceChangeListener"));
    }

    private static String source(String name) throws Exception {
        Path path = resolve(
                "app/src/main/java/com/shaterguy/chatgptselfrun/" + name,
                "src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path resolve(String repositoryPath, String appPath) {
        Path path = Path.of(repositoryPath);
        return Files.exists(path) ? path : Path.of(appPath);
    }
}
