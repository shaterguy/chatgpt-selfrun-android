package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

@Category(ServerOnly.class)
public final class SelfRunServerResultDeliveryPolicyTest {
    @Test public void boundedRecheckScheduleCoversACommitTwoMinutesAfterSignal() {
        long[] expected = {10_000L, 30_000L, 60_000L, 60_000L, 60_000L};
        long elapsed = 0L;
        assertEquals(expected.length, SelfRunServerWaitPolicy.serverResultRecheckAttemptCount());
        for (int attempt = 0; attempt < expected.length; attempt++) {
            assertEquals(expected[attempt], SelfRunServerWaitPolicy.serverResultRecheckDelayMs(attempt));
            elapsed += expected[attempt];
        }
        assertTrue(elapsed > 120_000L);
        assertEquals(-1L, SelfRunServerWaitPolicy.serverResultRecheckDelayMs(expected.length));
    }

    @Test public void fifteenMinuteOnDeviceBackupRearmsAnExhaustedChainAndFindsACommitTwoMinutesLater() {
        long recoveryAt = SelfRunServerWaitPolicy.RECOVERY_INTERVAL_MINUTES * 60_000L;
        long firstChainExhaustedAt = 0L;
        for (int attempt = 0; attempt < SelfRunServerWaitPolicy.serverResultRecheckAttemptCount(); attempt++) {
            firstChainExhaustedAt += SelfRunServerWaitPolicy.serverResultRecheckDelayMs(attempt);
        }
        assertTrue(firstChainExhaustedAt < recoveryAt);

        int exhausted = SelfRunServerWaitPolicy.serverResultRecheckAttemptCount();
        assertFalse(SelfRunServerWaitPolicy.mayStartServerResultRecheck(exhausted, false));
        assertTrue(SelfRunServerWaitPolicy.mayStartServerResultRecheck(exhausted, true));
        for (int active = 0; active < exhausted; active++) {
            assertFalse(SelfRunServerWaitPolicy.mayStartServerResultRecheck(active, true));
        }

        long commitAt = recoveryAt + 120_000L;
        long observationAt = recoveryAt;
        for (int attempt = 0; attempt < exhausted; attempt++) {
            observationAt += SelfRunServerWaitPolicy.serverResultRecheckDelayMs(attempt);
            if (observationAt >= commitAt) break;
        }
        assertTrue(observationAt >= commitAt);
        assertTrue(observationAt - commitAt <= 60_000L);
        assertTrue(observationAt < recoveryAt + SelfRunServerWaitPolicy.RECOVERY_INTERVAL_MINUTES * 60_000L);
    }

    @Test public void pendingResultNeverEmitsProcessedAck() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String pendingCatch = between(coordinator,
                "catch (ResultPendingException pending)",
                "catch (Throwable error)");
        assertFalse(pendingCatch.contains("acknowledgeProcessed(push)"));
        assertTrue(pendingCatch.contains("rememberPendingServerPush(expectedTurn, push, \"PENDING_RESULT\")"));
        assertTrue(pendingCatch.contains("SelfRunServerResultRecheckWorker.scheduleInitial"));
        assertTrue(pendingCatch.contains("SelfRunServerResultRecheckWorker.scheduleNext"));
    }

    @Test public void exhaustedRecheckChainIsRearmedButActiveChainStaysDeduplicated() throws Exception {
        String worker = source("SelfRunServerResultRecheckWorker.java");
        assertTrue(worker.contains("scheduleStart(context, turnId, true)"));
        assertTrue(worker.contains("scheduleStart(context, turnId, false)"));
        assertTrue(worker.contains("mayStartServerResultRecheck(currentAttempt, allowExhausted)"));
        assertTrue(worker.contains("scheduleExpected(app, turnId, currentAttempt, 0)"));
        assertTrue(worker.contains("ExistingWorkPolicy.KEEP"));
    }

    @Test public void readTransportFailureNeverTerminalAcksPush() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertFalse(coordinator.contains(
                "if (push != null && step == DriveStep.READ_RESULT) acknowledgeProcessed(push);"));
    }

    @Test public void processedAckRemainsOnConsumedOrStaleResultsOnly() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String pushHandler = between(coordinator, "void onPushResult", "void onServerRecovery");
        assertTrue(pushHandler.contains("if (exact == null) return;"));
        assertTrue(pushHandler.contains("exact.terminal() || exact.flag(\"superseded\")"));
        assertFalse(pushHandler.contains("serverFallbackTurns.contains(exact.turnId())"));
        assertTrue(coordinator.contains("acknowledgeCompletedServerPushes(expectedTurn, push);"));
        assertTrue(coordinator.contains("SelfRunServerPendingPushStore.clearIfSame(service, push)"));
    }

    @Test public void duplicatePendingPushIsCoalescedWithoutReadOrExhaustedRearm() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String pushHandler = between(coordinator, "void onPushResult", "void onServerRecovery");
        assertTrue(pushHandler.contains("SelfRunServerPendingPushStore.isSamePending(service, push)"));
        assertTrue(pushHandler.contains("SelfRunServerResultRecheckWorker.ensureActive(service, exact.turnId())"));
        assertTrue(pushHandler.contains("V3_SERVER_PUSH_COALESCED"));
        String pendingStore = source("SelfRunServerPendingPushStore.java");
        assertTrue(pendingStore.contains("SharedPreferences"));
        assertTrue(pendingStore.contains("sameLogicalEvent(event)"));
    }

    @Test public void retryableServerPushReadFailureRemainsNonTerminalAndDurable() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String driveStep = between(coordinator, "private void executeDriveStep", "private void scheduleResultRetry");
        int failureStart = driveStep.lastIndexOf("catch (Throwable error)");
        assertTrue("missing READ_RESULT failure catch", failureStart >= 0);
        String failure = driveStep.substring(failureStart);
        assertTrue(failure.contains("rememberPendingServerPush(expectedTurn, push, \"RETRYABLE_DRIVE_FAILURE\")"));
        assertFalse(failure.contains("acknowledgeProcessed(push)"));
    }

    @Test public void diagnosticsSeparateServerPushRecheckAndOnDeviceBackupWithoutRawEventId() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("ON_DEVICE_POLL, SERVER_PUSH, ON_DEVICE_BACKUP, AUTHORITY, SERVER_RECHECK"));
        String trigger = between(coordinator, "private void recordResultReadTrigger", "private void rememberPendingServerPush");
        assertTrue(trigger.contains("V3_RESULT_READ_TRIGGER"));
        assertTrue(trigger.contains("push.safeFingerprint()"));
        assertFalse(trigger.contains("push.eventId"));
    }

    @Test public void boundedRecheckIsDurableAcrossProcessRestartAndDeduplicatedByTurnAttempt() throws Exception {
        String worker = source("SelfRunServerResultRecheckWorker.java");
        assertTrue(worker.contains("SharedPreferences"));
        assertTrue(worker.contains("OneTimeWorkRequest"));
        assertTrue(worker.contains("ExistingWorkPolicy.KEEP"));
        assertTrue(worker.contains("enqueueUniqueWork"));
        assertTrue(worker.contains("isCurrent(app, turnId, attempt)"));
        assertTrue(worker.contains("ACTION_SERVER_RESULT_RECHECK"));
    }

    @Test public void serviceRoutesBoundedRecheckWithoutChangingOnDevicePolling() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("ACTION_SERVER_RESULT_RECHECK"));
        assertTrue(service.contains("coordinator.onServerResultRecheck(turnId, attempt)"));
        assertFalse(SelfRunServerWaitPolicy.useServerPush(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE, false));
    }

    @Test public void vercelContractKeepsReceivedNonTerminalAndProcessedTerminal() throws Exception {
        String policy = repositorySource("command-bridge/src/delivery-policy.ts");
        assertTrue(policy.contains("return state === \"PROCESSED\";"));
        String test = repositorySource("command-bridge/test/workflow-policy.test.ts");
        assertTrue(test.contains("treats only PROCESSED as terminal"));
        assertTrue(test.contains("shouldTerminateDelivery(\"RECEIVED\")"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        assertTrue("missing start marker: " + start, from >= 0);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing end marker: " + end, to > from);
        return source.substring(from, to);
    }

    private static String source(String name) throws Exception {
        return repositorySource("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
    }

    private static String repositorySource(String repositoryPath) throws Exception {
        Path path = Path.of(repositoryPath);
        if (!Files.exists(path)) {
            Path parentPath = Path.of("..").resolve(repositoryPath).normalize();
            if (Files.exists(parentPath)) path = parentPath;
        }
        if (!Files.exists(path) && repositoryPath.startsWith("app/")) {
            path = Path.of(repositoryPath.substring("app/".length()));
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
