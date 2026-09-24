package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** V3 result recovery is pinned-document reconciliation, with stale-result repair only after guarded fresh observation. */
public final class TurnDocumentRetryWiringTest {
    @Test public void resultContentCannotCreateImmediateRepairClassification() throws Exception {
        String drive = src("SelfRun3DriveAdapter.java");
        String coordinator = src("SelfRun3Coordinator.java");
        assertFalse(drive.contains("isInvalidCommittedResult"));
        assertFalse(drive.contains("InvalidCommittedResultException"));
        assertFalse(drive.contains("stage=INVALID_COMMITTED"));
        assertFalse(coordinator.contains("INVALID_COMMITTED_RESULT"));
    }

    @Test public void retrySchedulerFreshReadsBeforeWatchdogRepair() throws Exception {
        String coordinator = src("SelfRun3Coordinator.java");
        String pending = between(coordinator, "private void scheduleResultRetry", "private void commitTurn");
        assertTrue(pending.contains("runtimeSettings.resultPollMs()"));
        assertFalse(pending.contains("repairResult("));
        int current = coordinator.indexOf("SelfRun3Engine.State current = ledger.loadExecution(expectedTask, expectedTurn);");
        int observe = coordinator.indexOf("drive.observeResult(token, current)", current);
        int parsed = coordinator.indexOf("SelfRun3Engine.parseResult(observation.candidateBody, current)", observe);
        int watchdog = coordinator.indexOf("SelfRun3ResultWatchdog.shouldRepair(current,", observe);
        assertTrue(current >= 0 && observe > current && parsed > observe && watchdog > parsed);
        assertTrue(coordinator.indexOf("SelfRun3ResultWatchdog.fingerprint(observation.rawBody)", observe) < 0);
        assertTrue(coordinator.contains("runtimeSettings.resultRepairMs()"));
        assertFalse(coordinator.contains("drive.resultVersion(token, state)"));
        assertFalse(coordinator.contains("resultVersions"));
        assertFalse(coordinator.contains("InvalidCommittedResultException"));
        assertTrue(coordinator.contains("SelfRun3Engine.Kind.REPAIR"));
    }

    @Test public void malformedOrPartiallyWrittenResultPreservesRawObservationWithoutCreatingAnotherDocument() throws Exception {
        String drive = src("SelfRun3DriveAdapter.java");
        assertTrue(drive.contains("SelfRun3DriveLookup.findSingleDocumentId"));
        assertTrue(drive.contains("DOCUMENT_CREATE_UNCONFIRMED"));
        assertTrue(drive.contains("static final class ResultObservation"));
        assertTrue(drive.contains("candidate = SelfRun3Engine.emptyResult(s).toString();"));
        assertTrue(drive.contains("new ResultObservation(metadata.modifiedTime + \":\" + metadata.version, raw, candidate)"));
        assertFalse(drive.contains("SELF_RUN_TURN_DOCUMENT_RETRY"));
        assertFalse(drive.contains("DriveSignalParser"));
    }

    @Test public void watchdogRepairDoesNotConsumeLateUserInputReservation() throws Exception {
        String coordinator = src("SelfRun3Coordinator.java");
        int watchdog = coordinator.indexOf("SelfRun3ResultWatchdog.shouldRepair(current,");
        int completed = coordinator.indexOf("SelfRun3Engine.State completed = after;", watchdog);
        String repair = watchdog >= 0 && completed > watchdog ? coordinator.substring(watchdog, completed) : "";
        assertTrue(repair.contains("STALE_RESULT_WATCHDOG"));
        assertFalse(repair.contains("SelfRun3UserInput.consumeIfRevision"));
        assertFalse(repair.contains("SelfRun3UserInput.snapshot"));
        String commit = src("SelfRun3UserInput.java");
        assertTrue(commit.contains("Snapshot latest"));
        assertTrue(commit.contains("lateInput"));
    }

    @Test public void v3ContractUsesFreshExecutionIdentity() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.State state = SelfRun3Engine.create("task", "task:turn:1", config);
        for (String[] entry : new String[][]{
                {"folderId", "folder"}, {"requirementDocumentId", "requirement"},
                {"resultDocumentId", "resultdoc"}}) {
            JSONObject resource = new JSONObject();
            SelfRun3Engine.put(resource, "key", entry[0]);
            SelfRun3Engine.put(resource, "value", entry[1]);
            state = SelfRun3Engine.reduce(state, new SelfRun3Engine.Event("pin:" + entry[0],
                    SelfRun3Engine.Kind.RESOURCE, state.taskId(), state.turnId(), resource));
        }
        String prompt = SelfRun3Protocol.prompt(state, "new user input");
        assertTrue(prompt.contains("\nRESULT_DOCUMENT_ID=resultdoc\n"));
        assertTrue(prompt.startsWith("TASK_ID=" + state.taskId() + "\n"));
        assertTrue(prompt.contains("\nREQUEST_ID=" + state.requestId() + "\n"));
        assertTrue(prompt.contains("\nTURN_ID=" + state.turnId() + "\n"));
        assertTrue(prompt.contains("\nREQUIREMENT_DOCUMENT_ID=requirement\n"));
        assertTrue(prompt.contains("\nnew user input\n"));
        assertFalse(prompt.contains("[SELF_RUN_TURN_DOCUMENT_RETRY "));
    }

    private static String between(String source, String start, String end) {
        int a = source.indexOf(start), b = source.indexOf(end, Math.max(0, a));
        return a >= 0 && b > a ? source.substring(a, b) : "";
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
