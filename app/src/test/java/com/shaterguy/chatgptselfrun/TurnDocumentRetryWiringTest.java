package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class TurnDocumentRetryWiringTest {
    @Test public void fiveMinuteSignalMissRoutesToOneShotRepairBeforeExistingRollover() throws Exception {
        String service = src("SelfRunService.java");
        String coordinator = src("SelfRunRolloverCoordinator.java");
        String protocol = src("SelfRunProtocol.java");

        assertEquals(5 * 60_000L, SelfRunService.POST_DOM_DRIVE_MAX_WAIT_MS);
        assertTrue(service.contains("rolloverConversation(SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT)"));
        assertTrue(coordinator.contains("RESULT_TURN_DOCUMENT_RETRY"));
        assertTrue(coordinator.contains("TURN_DOCUMENT_RETRY_USED"));
        assertTrue(coordinator.contains("TURN_DOCUMENT_RETRY_PENDING"));
        assertTrue(coordinator.contains("SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT.equals(cause)"));
        assertTrue(coordinator.contains("store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE)"));
        assertTrue(protocol.contains("[SELF_RUN_TURN_DOCUMENT_RETRY "));
        assertTrue(protocol.contains("turnDocumentRetryPromptPending(runId)"));
    }

    @Test public void repairBypassesNormalNextInputReservationAndRunsInEmulatorRegression() throws Exception {
        String nextInput = src("UserNextInputStore.java");
        String runner = androidTest("SelfRunAndroidTestRunner.java");
        String regression = androidTest("TurnDocumentRetryAndroidTest.java");

        assertTrue(nextInput.contains("!SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(runId)"));
        assertTrue(runner.contains("TurnDocumentRetryAndroidTest"));
        assertTrue(regression.contains("transportRetryDoesNotConsumeLateUserNextInput"));
        assertTrue(regression.contains("secondTimeoutRollsOverAndSuccessorGetsFreshRetryBudget"));
    }

    @Test public void repositoryProtocolStatesRepairIsNotANewDriveSignalType() throws Exception {
        String protocolDoc = read("docs/SELF_RUN_DRIVE_V1_PROTOCOL.md", "../docs/SELF_RUN_DRIVE_V1_PROTOCOL.md");
        assertTrue(protocolDoc.contains("[SELF_RUN_TURN_DOCUMENT_RETRY <RUN_ID>]"));
        assertTrue(protocolDoc.contains("작업 진행용 CONTINUE가 아니며"));
        assertTrue(protocolDoc.contains("기존 `SELF_RUN_TURN_COMPLETED` signal document만 다시 생성"));
        assertTrue(protocolDoc.contains("새로운 RUN_ID이므로 문서 누락 복구 횟수를 predecessor에서 상속하지 않고"));
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
