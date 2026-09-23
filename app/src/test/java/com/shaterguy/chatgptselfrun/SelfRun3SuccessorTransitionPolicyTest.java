package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class SelfRun3SuccessorTransitionPolicyTest {
    @Test public void acceptedSerialResultArmsOnePersistedDeadlineAndCommitCarriesSameTransition() {
        SelfRun3Engine.State claimed = claimedState();
        SelfRun3Engine.State accepted = acceptSerial(claimed, serialContinue(claimed, "medium", "medium"),
                1_000L, 10_000L, 7, 90_000L);

        assertEquals(SelfRun3Engine.Stage.RECONCILING, accepted.stage());
        assertTrue(SelfRun3SuccessorTransitionPolicy.routingValid(accepted));
        assertTrue(SelfRun3SuccessorTransitionPolicy.armed(accepted));
        assertEquals(90_000L, SelfRun3SuccessorTransitionPolicy.remainingMs(
                accepted, 1_000L, 10_000L, 7));
        assertEquals(45_000L, SelfRun3SuccessorTransitionPolicy.remainingMs(
                accepted, 46_000L, 55_000L, 7));

        String predecessorTurn = accepted.turnId();
        String predecessorRequest = accepted.requestId();
        String predecessorResult = accepted.resource("resultDocumentId");
        SelfRun3Engine.State successor = reduce(accepted, "commit", SelfRun3Engine.Kind.COMMIT, new JSONObject());

        assertEquals(2, successor.turn());
        assertEquals(SelfRun3Engine.Stage.PREPARING, successor.stage());
        assertTrue(SelfRun3SuccessorTransitionPolicy.armed(successor));
        JSONObject transition = successor.successorTransition();
        assertEquals(predecessorTurn, transition.optString("predecessorTurnId"));
        assertEquals(predecessorRequest, transition.optString("predecessorRequestId"));
        assertEquals(predecessorResult, transition.optString("predecessorResultDocumentId"));
        assertEquals(successor.turnId(), transition.optString("successorTurnId"));
        assertEquals(successor.requestId(), transition.optString("successorRequestId"));
        assertEquals("SUCCESSOR_TURN_CREATED", transition.optString("stage"));
        assertEquals(45_000L, SelfRun3SuccessorTransitionPolicy.remainingMs(
                successor, 46_000L, 55_000L, 7));
    }

    @Test public void timeoutRedriveKeepsSuccessorIdentityAndProgressThenRearmsBudget() {
        SelfRun3Engine.State accepted = acceptSerial(claimedState(),
                serialContinue(claimedState(), "medium", "medium"), 1_000L, 10_000L, 7, 90_000L);
        SelfRun3Engine.State successor = reduce(accepted, "commit", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        successor = resource(successor, "resultDocumentId", "successor-result");
        String turn = successor.turnId();
        String request = successor.requestId();
        String resultDocument = successor.resource("resultDocumentId");

        assertEquals(0L, SelfRun3SuccessorTransitionPolicy.remainingMs(
                successor, 91_000L, 100_000L, 7));
        JSONObject timeout = new JSONObject();
        SelfRun3Engine.put(timeout, "atElapsed", 91_000L);
        SelfRun3Engine.put(timeout, "atWall", 100_000L);
        SelfRun3Engine.put(timeout, "bootCount", 7);
        SelfRun3Engine.State recovered = reduce(successor, "successor-timeout:1",
                SelfRun3Engine.Kind.SUCCESSOR_TIMEOUT, timeout);

        assertEquals(turn, recovered.turnId());
        assertEquals(request, recovered.requestId());
        assertEquals(resultDocument, recovered.resource("resultDocumentId"));
        assertEquals(1, recovered.successorTransition().optInt("recoveryAttempt"));
        assertEquals("TIMEOUT_RECOVERY", recovered.successorTransition().optString("stage"));
        assertEquals(90_000L, SelfRun3SuccessorTransitionPolicy.remainingMs(
                recovered, 91_000L, 100_000L, 7));
    }

    @Test public void confirmedCanonicalSuccessorStopsWatchdogWithoutChangingRequestIdentity() {
        SelfRun3Engine.State source = claimedState();
        SelfRun3Engine.State successor = reduce(acceptSerial(source,
                        serialContinue(source, "medium", "medium"),
                        1_000L, 10_000L, 7, 90_000L),
                "commit", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        successor = resource(successor, "resultDocumentId", "successor-result");
        JSONObject ready = new JSONObject();
        SelfRun3Engine.put(ready, "prompt", "next");
        SelfRun3Engine.put(ready, "inputText", "");
        SelfRun3Engine.put(ready, "inputRevision", 0L);
        successor = reduce(successor, "turn-ready", SelfRun3Engine.Kind.TURN_READY, ready);
        String requestId = successor.requestId();
        JSONObject claim = new JSONObject(); SelfRun3Engine.put(claim, "at", 20_000L);
        successor = reduce(successor, "claim-successor", SelfRun3Engine.Kind.CLAIM_SEND, claim);
        successor = resource(successor, "conversationUrl", "https://chatgpt.com/c/successor-canonical");

        JSONObject started = new JSONObject();
        SelfRun3Engine.put(started, "requestId", requestId);
        SelfRun3Engine.put(started, "source", "canonical_post");
        SelfRun3Engine.put(started, "protocolStage", "turn_request");
        SelfRun3Engine.put(started, "atElapsed", 30_000L);
        SelfRun3Engine.put(started, "atWall", 40_000L);
        SelfRun3Engine.put(started, "bootCount", 7);
        SelfRun3Engine.State confirmed = reduce(successor, "started-successor",
                SelfRun3Engine.Kind.STARTED, started);

        assertEquals(requestId, confirmed.requestId());
        assertFalse(SelfRun3SuccessorTransitionPolicy.armed(confirmed));
        assertEquals("CANONICAL_CONFIRMED", confirmed.successorTransition().optString("stage"));
        assertEquals(SelfRun3Engine.Stage.WAITING, confirmed.stage());
    }

    @Test public void invalidSerialRoutingIsRecordedOnceAndExistingCommitRepairPathIsUsed() {
        SelfRun3Engine.State claimed = claimedState();
        SelfRun3Engine.State accepted = acceptSerial(claimed,
                serialContinue(claimed, "high", "medium"),
                1_000L, 10_000L, 7, 90_000L);

        assertTrue(SelfRun3SuccessorTransitionPolicy.routingInvalid(accepted));
        assertFalse(SelfRun3SuccessorTransitionPolicy.armed(accepted));
        assertTrue(SelfRun3SuccessorTransitionPolicy.routingProblems(accepted).length() > 0);

        SelfRun3Engine.State repair = reduce(accepted, "commit-invalid",
                SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals("REPAIR", repair.text("executionKind"));
        assertEquals("RESULT_ROUTING_INVALID", repair.text("repairReason"));
        assertEquals(claimed.resource("resultDocumentId"), repair.text("repairTargetDocumentId"));
    }

    @Test public void userActionResolvedSerialContinuationIsTrackedAndCarriesResumeSignal() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject required = identity(claimed);
        SelfRun3Engine.put(required, "committed", true);
        SelfRun3Engine.put(required, "status", "USER_ACTION_REQUIRED");
        SelfRun3Engine.put(required, "reason", "user input");
        JSONObject requiredPayload = new JSONObject();
        SelfRun3Engine.put(requiredPayload, "text", required.toString());
        SelfRun3Engine.State waiting = reduce(claimed, claimed.turnId() + ":result:required",
                SelfRun3Engine.Kind.RESULT, requiredPayload);
        waiting = reduce(waiting, "commit-required", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.WAITING_USER_INTERVENTION, waiting.stage());

        JSONObject resolved = serialContinue(waiting, "medium", "medium");
        SelfRun3Engine.put(resolved, "status", "USER_ACTION_RESOLVED");
        SelfRun3Engine.State accepted = acceptSerial(waiting, resolved,
                2_000L, 20_000L, 7, 90_000L);

        assertTrue(SelfRun3SuccessorTransitionPolicy.armed(accepted));
        assertEquals("USER_ACTION_RESUME",
                accepted.successorTransition().optString("signal"));
        SelfRun3Engine.State successor = reduce(accepted, "commit-resolved",
                SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals("USER_ACTION_RESUME", successor.text("signalType"));
        assertEquals(2, successor.turn());
    }

    @Test public void userActionRequiredPausedAndStoppedSourcesNeverArmSuccessorWatchdog() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject serial = serialContinue(claimed, "medium", "medium");

        JSONObject required = SelfRun3Engine.copy(serial);
        SelfRun3Engine.put(required, "status", "USER_ACTION_REQUIRED");
        SelfRun3Engine.State requiredState = acceptSerial(claimed, required,
                1_000L, 10_000L, 7, 90_000L);
        assertFalse(SelfRun3SuccessorTransitionPolicy.armed(requiredState));
        assertFalse(SelfRun3SuccessorTransitionPolicy.routingValid(requiredState));

        JSONObject paused = SelfRun3Engine.copy(serial);
        SelfRun3Engine.put(paused, "status", "PAUSED");
        SelfRun3Engine.State pausedState = acceptSerial(claimed, paused,
                1_000L, 10_000L, 7, 90_000L);
        assertFalse(SelfRun3SuccessorTransitionPolicy.armed(pausedState));
        assertFalse(SelfRun3SuccessorTransitionPolicy.routingValid(pausedState));

        SelfRun3Engine.State stopped = reduce(claimed, "stop",
                SelfRun3Engine.Kind.STOP, new JSONObject());
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "acceptedAtElapsed", 1_000L);
        SelfRun3Engine.put(payload, "acceptedAtWall", 10_000L);
        SelfRun3Engine.put(payload, "acceptedBootCount", 7);
        SelfRun3Engine.put(payload, "successorTimeoutMs", 90_000L);
        assertFalse(SelfRun3SuccessorTransitionPolicy.shouldTrack(serial, stopped, payload));
    }

    @Test public void initialPreparationAndTerminalResultsDoNotArmSuccessorWatchdog() {
        SelfRun3Engine.State claimed = claimedState();
        assertEquals(SelfRun3SuccessorTransitionPolicy.NO_DEADLINE,
                SelfRun3SuccessorTransitionPolicy.remainingMs(claimed, 0L, 0L, 0));

        JSONObject done = identity(claimed);
        SelfRun3Engine.put(done, "committed", true);
        SelfRun3Engine.put(done, "status", "DONE");
        SelfRun3Engine.put(done, "next_execution", serialPlan("medium"));
        SelfRun3Engine.State acceptedDone = acceptSerial(claimed, done, 1_000L, 10_000L, 7, 90_000L);
        assertFalse(SelfRun3SuccessorTransitionPolicy.armed(acceptedDone));
        assertFalse(SelfRun3SuccessorTransitionPolicy.routingValid(acceptedDone));
    }

    private static SelfRun3Engine.State acceptSerial(SelfRun3Engine.State state, JSONObject result,
                                                     long elapsed, long wall, int boot, long timeout) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "text", result.toString());
        SelfRun3Engine.put(payload, "acceptedAtElapsed", elapsed);
        SelfRun3Engine.put(payload, "acceptedAtWall", wall);
        SelfRun3Engine.put(payload, "acceptedBootCount", boot);
        SelfRun3Engine.put(payload, "successorTimeoutMs", timeout);
        return reduce(state, state.turnId() + ":result:watchdog",
                SelfRun3Engine.Kind.RESULT, payload);
    }

    private static JSONObject serialContinue(SelfRun3Engine.State state,
                                             String primaryReasoning, String secondaryReasoning) {
        JSONObject result = identity(state);
        SelfRun3Engine.put(result, "committed", true);
        SelfRun3Engine.put(result, "status", "CONTINUE");
        SelfRun3Engine.put(result, "phase_completed", "WORK_PARTIAL");
        SelfRun3Engine.put(result, "next_phase", "WORK");
        SelfRun3Engine.put(result, "next_input", "");
        SelfRun3Engine.put(result, "next_execution", serialPlan(primaryReasoning));
        SelfRun3Engine.put(result, "next_profile", profile(secondaryReasoning));
        return result;
    }

    private static JSONObject serialPlan(String reasoning) {
        JSONObject plan = new JSONObject();
        SelfRun3Engine.put(plan, "type", "SERIAL");
        SelfRun3Engine.put(plan, "objective", "continue exactly once");
        SelfRun3Engine.put(plan, "profile", profile(reasoning));
        return plan;
    }

    private static JSONObject profile(String reasoning) {
        JSONObject profile = new JSONObject();
        SelfRun3Engine.put(profile, "mode", "CHAT");
        SelfRun3Engine.put(profile, "model", "gpt-5-6-thinking");
        SelfRun3Engine.put(profile, "reasoning", reasoning);
        return profile;
    }

    private static JSONObject identity(SelfRun3Engine.State state) {
        JSONObject result = new JSONObject();
        SelfRun3Engine.put(result, "schema", SelfRun3Engine.RESULT_SCHEMA);
        SelfRun3Engine.put(result, "task_id", state.taskId());
        SelfRun3Engine.put(result, "turn_id", state.turnId());
        SelfRun3Engine.put(result, "turn", state.turn());
        SelfRun3Engine.put(result, "document_id", state.resource("resultDocumentId"));
        SelfRun3Engine.put(result, "event_id", state.turnId() + ":result");
        return result;
    }

    private static SelfRun3Engine.State claimedState() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/");
        SelfRun3Engine.State state = SelfRun3Engine.create("task", "task:turn:1", config);
        state = resource(state, "folderId", "folder");
        state = resource(state, "requirementDocumentId", "requirement");
        state = reduce(state, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        state = resource(state, "resultDocumentId", "resultdoc");
        JSONObject ready = new JSONObject();
        SelfRun3Engine.put(ready, "prompt", "hello");
        SelfRun3Engine.put(ready, "inputText", "");
        SelfRun3Engine.put(ready, "inputRevision", 0L);
        state = reduce(state, "ready", SelfRun3Engine.Kind.TURN_READY, ready);
        JSONObject claim = new JSONObject(); SelfRun3Engine.put(claim, "at", 100L);
        return reduce(state, "claim", SelfRun3Engine.Kind.CLAIM_SEND, claim);
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return reduce(state, "resource:" + state.turnId() + ":" + key,
                SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State reduce(SelfRun3Engine.State state, String id,
                                                SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event(id, kind, state.taskId(), state.turnId(), payload));
    }
}
