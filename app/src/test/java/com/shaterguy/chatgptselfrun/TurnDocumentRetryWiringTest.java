package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** V3 result recovery is pinned-document reconciliation, not a V2 title-signal retry cycle. */
public final class TurnDocumentRetryWiringTest {
    @Test public void onlyAnExactCommittedInvalidPayloadAllowsRepair() {
        JSONObject config = new JSONObject(); SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.State state = SelfRun3Engine.create("task", "task:turn:1", config);
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", "resultDocumentId"); SelfRun3Engine.put(payload, "value", "result");
        state = SelfRun3Engine.reduce(state, new SelfRun3Engine.Event("pin", SelfRun3Engine.Kind.RESOURCE,
                state.taskId(), state.turnId(), payload));
        JSONObject body = SelfRun3Engine.emptyResult(state);
        assertFalse(SelfRun3DriveAdapter.isInvalidCommittedResult(body.toString(), state));
        SelfRun3Engine.put(body, "committed", "true");
        assertFalse(SelfRun3DriveAdapter.isInvalidCommittedResult(body.toString(), state));
        SelfRun3Engine.put(body, "committed", true);
        assertTrue(SelfRun3DriveAdapter.isInvalidCommittedResult(body.toString(), state));
        SelfRun3Engine.put(body, "turn_id", "other");
        assertFalse(SelfRun3DriveAdapter.isInvalidCommittedResult(body.toString(), state));
        assertFalse(SelfRun3DriveAdapter.isInvalidCommittedResult("{\"committed\":true", state));
    }

    @Test public void pendingReadsNeverLaunchRepairByElapsedTime() throws Exception {
        String coordinator = src("SelfRun3Coordinator.java");
        String pending = between(coordinator, "private void scheduleResultRetry", "private void repairResult");
        assertTrue(pending.contains("NORMAL_WAIT_POLL_MS"));
        assertFalse(pending.contains("repairResult("));
        assertTrue(coordinator.contains("InvalidCommittedResultException"));
        assertTrue(coordinator.contains("SelfRun3Engine.Kind.REPAIR"));
    }

    @Test public void malformedOrPartiallyWrittenResultIsPendingInsteadOfBlindlyCreatingAnotherDocument() throws Exception {
        String drive = src("SelfRun3DriveAdapter.java");
        assertTrue(drive.contains("SelfRun3DriveLookup.findSingleDocumentId"));
        assertTrue(drive.contains("DOCUMENT_CREATE_UNCONFIRMED"));
        assertTrue(drive.contains("return SelfRun3Engine.emptyResult(s).toString()"));
        assertFalse(drive.contains("SELF_RUN_TURN_DOCUMENT_RETRY"));
        assertFalse(drive.contains("DriveSignalParser"));
    }

    @Test public void repairDoesNotConsumeLateUserInputReservation() throws Exception {
        String coordinator = src("SelfRun3Coordinator.java");
        String repair = between(coordinator, "private void repairResult", "private void commitTurn");
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
