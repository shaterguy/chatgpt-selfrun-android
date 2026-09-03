package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class TurnDocumentRetryWiringTest {
    @Test public void threeMinuteSignalMissRoutesToOneRetryPerCompletionCycleBeforeExistingRollover() throws Exception {
        String service = src("SelfRunService.java");
        String coordinator = src("SelfRunRolloverCoordinator.java");
        String protocol = src("SelfRunProtocol.java");

        assertEquals(3 * 60_000L, SelfRunService.POST_DOM_DRIVE_MAX_WAIT_MS);
        assertTrue(service.contains("rolloverConversation(SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT)"));
        assertTrue(coordinator.contains("RESULT_TURN_DOCUMENT_RETRY"));
        assertTrue(coordinator.contains("TURN_DOCUMENT_RETRY_USED"));
        assertTrue(coordinator.contains("TURN_DOCUMENT_RETRY_PENDING"));
        assertTrue(coordinator.contains("SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT.equals(cause)"));
        assertTrue(coordinator.contains("turnDocumentRetryPromptPending(store.runId())"));
        assertTrue(coordinator.contains("store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE)"));
        assertTrue(protocol.contains("[SELF_RUN_TURN_DOCUMENT_RETRY "));
        assertTrue(protocol.contains("turnDocumentRetryPromptPending(runId)"));
    }

    @Test public void retryPromptCleanupAndBudgetRestoreAreSeparateDurableTransitions() throws Exception {
        String coordinator = src("SelfRunRolloverCoordinator.java");

        assertTrue(coordinator.contains("cleanupTurnDocumentRetryPrompt()"));
        assertTrue(coordinator.contains("restoreTurnDocumentRetryBudgetAfterConsumedCompletion"));
        assertTrue(coordinator.contains("pendingDriveSignalType"));
        assertTrue(coordinator.contains("pendingDriveSignalRaw"));
        assertTrue(coordinator.contains("commitDetectedAt"));
        assertTrue(coordinator.contains("SelfRunStore.PHASE_SEND_CONTINUE"));
        assertTrue(coordinator.contains("SelfRunStore.PHASE_APPLY_PREFS"));
        assertTrue(coordinator.contains("DriveSignalParser.Type.TURN_COMPLETED.name()"));
        assertTrue(coordinator.contains("remove(TURN_DOCUMENT_RETRY_USED)"));
        assertTrue(coordinator.contains("retryPrefs.getBoolean(TURN_DOCUMENT_RETRY_PENDING, false)"));
    }

    @Test public void repairProfileTargetSurvivesTheWebViewRecreationDoneBeforeRetry() throws Exception {
        String service = src("SelfRunService.java");
        String profile = src("RequestProfileScript.java");

        assertTrue(service.contains("stopAutomationCallbacks();\n        cleanupWebView();"));
        assertTrue(profile.contains("const TARGET_STORE='selfrun-drive:request-profile-target:v3'"));
        assertTrue(profile.contains("state.target=restoreTarget();"));
        assertTrue(profile.contains("persistTarget();state.last={ok:true,reason:'target_begun'"));
        assertTrue(profile.contains("if(!t||!t.ready)fail('target_not_ready')"));
    }

    @Test public void repairBypassesNormalNextInputReservationAndRunsInEmulatorRegression() throws Exception {
        String nextInput = src("UserNextInputStore.java");
        String runner = androidTest("SelfRunAndroidTestRunner.java");
        String regression = androidTest("TurnDocumentRetryAndroidTest.java");
        String workflow = read(".github/workflows/build-drive-test.yml", "../.github/workflows/build-drive-test.yml");

        assertTrue(nextInput.contains("!SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(runId)"));
        assertTrue(runner.contains("TurnDocumentRetryAndroidTest"));
        assertTrue(regression.contains("duplicateTimeoutWhileRetryPendingKeepsSameRunAndRetryPrompt"));
        assertTrue(regression.contains("secondTimeoutWithoutValidCompletionRollsOver"));
        assertTrue(regression.contains("consumedChatCompletionRestoresSameRunRetryBudgetForNextCycle"));
        assertTrue(regression.contains("nextCycleStillAllowsOnlyOneRetryBeforeRollover"));
        assertTrue(regression.contains("usedBudgetSurvivesCoordinatorRecreationWithoutValidCompletion"));
        assertTrue(regression.contains("restoredBudgetSurvivesCoordinatorRecreation"));
        assertTrue(regression.contains("consumedWorkCompletionRestoresSameRunRetryBudget"));
        assertTrue(regression.contains("malformedCompletionDoesNotRestoreRetryBudget"));
        assertTrue(regression.contains("transportRetryDoesNotConsumeLateUserNextInput"));
        assertTrue(workflow.contains("com.shaterguy.chatgptselfrun.TurnDocumentRetryAndroidTest"));
    }

    @Test public void repositoryProtocolStatesRetryBudgetIsPerCompletionCycle() throws Exception {
        String protocolDoc = read("docs/SELF_RUN_DRIVE_V1_PROTOCOL.md", "../docs/SELF_RUN_DRIVE_V1_PROTOCOL.md");
        String runtimeDoc = read("docs/SELF_RUN_DRIVE_RUNTIME.md", "../docs/SELF_RUN_DRIVE_RUNTIME.md");
        assertTrue(protocolDoc.contains("[SELF_RUN_TURN_DOCUMENT_RETRY <RUN_ID>]"));
        assertTrue(protocolDoc.contains("작업 진행용 CONTINUE가 아니며"));
        assertTrue(protocolDoc.contains("기존 `SELF_RUN_TURN_COMPLETED` signal document만 다시 생성"));
        assertTrue(protocolDoc.contains("각 독립적인 답변 완료 사이클마다 1회"));
        assertTrue(protocolDoc.contains("`SEND_CONTINUE`"));
        assertTrue(protocolDoc.contains("`APPLY_PREFS`"));
        assertTrue(runtimeDoc.contains("`PENDING` 해제는 prompt lifecycle 정리"));
        assertTrue(runtimeDoc.contains("`USED` 복구는 정상 `TURN_COMPLETED` 소비 성공 경계"));
    }

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String androidTest(String file) throws Exception {
        return read("app/src/androidTest/java/com/shaterguy/chatgptselfrun/" + file,
                "src/androidTest/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
