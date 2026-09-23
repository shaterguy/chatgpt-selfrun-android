package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class SelfRun3SuccessorTransitionWiringTest {
    @Test public void acceptedResultOwnsPersistedDeadlineAndTimeoutRecoveryNeverReadsPredecessorDrive() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String engine = source("SelfRun3Engine.java");
        String recovery = between(coordinator, "void onSuccessorWatchdogWake", "private void pause(String reason)");

        assertTrue(coordinator.contains("successorTimeoutMs"));
        assertTrue(coordinator.contains("runtimeSettings.webPreparationMs()"));
        assertTrue(engine.contains("recordSuccessorRouting(v,r,s,p)"));
        assertTrue(engine.contains("SelfRun3SuccessorTransitionPolicy.routingValid(s)"));
        assertTrue(recovery.contains("ledger.load(task)"));
        assertTrue(recovery.contains("SelfRun3Engine.Kind.SUCCESSOR_TIMEOUT"));
        assertTrue(recovery.contains("scheduleNext(0L)"));
        assertFalse(recovery.contains("DriveStep.READ_RESULT"));
        assertFalse(recovery.contains("drive.observeResult"));
        assertFalse(recovery.contains("SelfRun3ResultWatchdog"));
    }

    @Test public void watchdogIsArmedInDriveIoBeforeMainHandlerProgressionCanBeLost() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String resultPath = between(coordinator,
                "JSONObject parsed = SelfRun3Engine.parseResult",
                "SelfRun3Engine.State completed = after;");

        int resultApply = resultPath.indexOf("after = ledger.apply(event(current");
        int alarmArm = resultPath.indexOf("SelfRun3SuccessorWakeScheduler.schedule(service, accepted");
        assertTrue(resultApply >= 0);
        assertTrue(alarmArm > resultApply);
        assertTrue(resultPath.contains("routingValid(accepted)"));
        assertFalse(resultPath.contains("main.post(() ->"));
    }

    @Test public void timeoutSupersedesOnlyAfterDurableDeadlineCheckAndFencesOldCallbacks() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String recovery = between(coordinator, "void onSuccessorWatchdogWake", "private void pause(String reason)");

        assertTrue(recovery.indexOf("remaining > 0L") < recovery.indexOf("recoverExpiredSuccessor("));
        assertTrue(recovery.contains("epoch++;"));
        assertTrue(recovery.contains("serverGeneration++;"));
        assertTrue(recovery.contains("driveInFlight = false;"));
        assertTrue(recovery.contains("authorizationInFlight = false;"));
        assertTrue(recovery.contains("serverRegistrationInFlight = false;"));
        assertTrue(recovery.contains("recoveryCycleInFlight = false;"));
        assertTrue(recovery.contains("preparingRequest = \"\";"));
        assertTrue(recovery.contains("web.quiesce();"));
        assertTrue(recovery.contains("V3_SUCCESSOR_RUNTIME_SUPERSEDED"));
    }

    @Test public void successorWebPreparationConsumesRemainingGlobalBudgetWhileFirstTurnKeepsExistingSetting() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        String timer = between(web, "private void startPreparationTimer()", "private void expireConversationCreation");

        assertTrue(timer.contains("SelfRun3SuccessorTransitionPolicy.remainingMs"));
        assertTrue(timer.contains("if (successorRemaining >= 0L)"));
        assertTrue(timer.contains("prepareTimeoutMs = Math.max(1L, successorRemaining)"));
        assertTrue(timer.contains("else prepareTimeoutMs = runtimeSettings.webPreparationMs();"));
        assertTrue(web.contains("SUCCESSOR_TRANSITION_TIMEOUT"));
        assertTrue(web.contains("scope = successorRemaining >= 0L ? \"successor-transition\" : \"conversation-create\""));
    }

    @Test public void serviceAlarmAndUiExposeSuccessorTransitionWithoutChangingResultRepairWatchdog() throws Exception {
        String service = source("SelfRunService.java");
        String coordinator = source("SelfRun3Coordinator.java");
        String resultWatchdog = source("SelfRun3ResultWatchdog.java");

        assertTrue(service.contains("ACTION_SUCCESSOR_WATCHDOG_WAKE"));
        assertTrue(service.contains("SelfRun3SuccessorWakeScheduler.EXTRA_PREDECESSOR_TURN_ID"));
        assertTrue(coordinator.contains("successor 전환 · watchdog 대기"));
        assertTrue(coordinator.contains("successor 전환 타임아웃 복구"));
        assertTrue(coordinator.contains("Result 라우팅 자동 복구"));
        assertTrue(resultWatchdog.contains("DEFAULT_RESULT_REPAIR_MINUTES * 60_000L"));
        assertTrue(coordinator.contains("SelfRun3ResultWatchdog.shouldRepair"));
        assertTrue(coordinator.contains("STALE_RESULT_WATCHDOG"));
    }

    @Test public void confirmedRequestIsNotRedispatchedAndCanonicalConfirmationDisarmsTransition() throws Exception {
        String engine = source("SelfRun3Engine.java");
        String web = source("SelfRun3WebAdapter.java");

        assertTrue(engine.contains("case DISPATCHING -> s.resource(\"conversationUrl\").isEmpty() ? Action.PREPARE_WEB : Action.WAIT;"));
        assertTrue(engine.contains("put(transition,\"active\",false); put(transition,\"stage\",\"CANONICAL_CONFIRMED\")"));
        assertTrue(web.contains("if (web == null || state == null || !dispatchConfirmed || conversationCaptured) return;"));
        assertTrue(web.contains("listener.onConversation(state.taskId(), state.turnId(), canonical);"));
        assertTrue(web.contains("listener.onStarted(state.taskId(), state.turnId(), state.requestId());"));
    }

    private static String between(String source, String start, String end) {
        int a = source.indexOf(start), b = source.indexOf(end, Math.max(0, a));
        return a >= 0 && b > a ? source.substring(a, b) : "";
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
