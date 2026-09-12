package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** Result-read failures must roll forward through disposable repair turns instead of hard-pausing the task. */
public final class SelfRun3AlwaysRepairPolicyTest {
    @Test public void repairReplacementCanBeRepairedAgain() {
        SelfRun3Engine.State first = initialWaiting();
        String firstTurn = first.turnId();

        SelfRun3Engine.State second = repair(first);
        assertEquals(2, second.turn());
        assertEquals("REPAIR", second.text("executionKind"));
        assertEquals(0, second.number("repairAttempt"));
        assertTrue(second.execution(firstTurn).flag("superseded"));

        second = waitingFromPreparing(second, "result-doc-2");
        String secondTurn = second.turnId();
        SelfRun3Engine.State third = repair(second);
        assertEquals(3, third.turn());
        assertEquals("REPAIR", third.text("executionKind"));
        assertEquals(0, third.number("repairAttempt"));
        assertTrue(third.execution(secondTurn).flag("superseded"));
    }

    @Test public void readResultFailuresRouteToRepairAndRepairFailureRetries() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("if (step == DriveStep.READ_RESULT)"));
        assertTrue(coordinator.contains("repairResult(state, \"READ_RESULT_FAILURE_\""));
        assertTrue(coordinator.contains("V3_RESULT_REPAIR_RETRY"));
        assertTrue(coordinator.contains("scheduleResultRetry(original)"));
        assertFalse(coordinator.contains("V3_RESULT_REPAIR_EXHAUSTED"));
        assertFalse(coordinator.contains("hardPause(\"V3_RESULT_REPAIR_FAILED\""));

        String engine = source("SelfRun3Engine.java");
        assertFalse(engine.contains("put(v,\"repairAttempt\",1)"));
    }

    private static SelfRun3Engine.State initialWaiting() {
        JSONObject config = new JSONObject();
        put(config, "mode", "CHAT");
        put(config, "reasoning", "medium");
        SelfRun3Engine.State state = SelfRun3Engine.create("repair-task", "repair-task:turn:1", config);
        state = resource(state, "folderId", "folder");
        state = resource(state, "requirementDocumentId", "requirements");
        state = event(state, state.turnId(), state.turnId() + ":setup",
                SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        return waitingFromPreparing(state, "result-doc-1");
    }

    private static SelfRun3Engine.State waitingFromPreparing(SelfRun3Engine.State state, String resultDocumentId) {
        assertEquals(SelfRun3Engine.Stage.PREPARING, state.stage());
        state = resource(state, "resultDocumentId", resultDocumentId);

        JSONObject baseline = new JSONObject();
        put(baseline, "documentId", resultDocumentId);
        put(baseline, "fingerprint", SelfRun3ResultWatchdog.fingerprint(SelfRun3Engine.emptyResult(state).toString()));
        state = event(state, state.turnId(), state.turnId() + ":baseline",
                SelfRun3Engine.Kind.RESULT_BASELINE, baseline);

        JSONObject ready = new JSONObject();
        put(ready, "prompt", "repair policy test");
        put(ready, "inputRevision", 0L);
        state = event(state, state.turnId(), state.turnId() + ":ready",
                SelfRun3Engine.Kind.TURN_READY, ready);

        JSONObject claim = new JSONObject();
        put(claim, "at", 1L);
        state = event(state, state.turnId(), state.turnId() + ":claim",
                SelfRun3Engine.Kind.CLAIM_SEND, claim);

        JSONObject started = new JSONObject();
        put(started, "requestId", state.requestId());
        state = event(state, state.turnId(), state.turnId() + ":started",
                SelfRun3Engine.Kind.STARTED, started);
        assertEquals(SelfRun3Engine.Stage.WAITING, state.stage());
        return state;
    }

    private static SelfRun3Engine.State repair(SelfRun3Engine.State state) {
        JSONObject payload = new JSONObject();
        put(payload, "safeToRepair", true);
        put(payload, "reason", "TEST_RESULT_READ_FAILURE");
        return event(state, state.turnId(), state.turnId() + ":repair",
                SelfRun3Engine.Kind.REPAIR, payload);
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        put(payload, "key", key);
        put(payload, "value", value);
        return event(state, state.turnId(), state.turnId() + ":resource:" + key,
                SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State event(SelfRun3Engine.State root, String turn, String id,
                                               SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(root,
                new SelfRun3Engine.Event(id, kind, root.taskId(), turn, payload));
    }

    private static String source(String file) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void put(JSONObject object, String key, Object value) {
        SelfRun3Engine.put(object, key, value);
    }
}
