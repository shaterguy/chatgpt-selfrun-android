package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

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

    @Test public void pendingResultNeverEmitsProcessedAck() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String pendingCatch = between(coordinator,
                "catch (ResultPendingException pending)",
                "catch (Throwable error)");
        assertFalse(pendingCatch.contains("acknowledgeProcessed(push)"));
        assertTrue(pendingCatch.contains("SelfRunServerResultRecheckWorker.scheduleInitial"));
        assertTrue(pendingCatch.contains("SelfRunServerResultRecheckWorker.scheduleNext"));
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
        assertFalse(pushHandler.contains(
                "if (serverFallbackTurns.contains(exact.turnId())) {\n                    acknowledgeProcessed(push);"));
        assertTrue(coordinator.contains("if (push != null) acknowledgeProcessed(push);"));
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
