package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class SelfRun3EngineTest {
    @Test public void sendClaimIsDurableAndDuplicateClaimDoesNotDispatchAgain() {
        SelfRun3Engine.State ready = readyState();
        JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "at", 100L);
        SelfRun3Engine.State claimed = reduce(ready, "claim", SelfRun3Engine.Kind.CLAIM_SEND, p);
        assertEquals(SelfRun3Engine.Stage.DISPATCHING, claimed.stage());
        assertTrue(claimed.flag("sendClaimed"));
        SelfRun3Engine.State duplicate = reduce(claimed, "claim2", SelfRun3Engine.Kind.CLAIM_SEND, p);
        assertSame(claimed, duplicate);
    }

    @Test public void staleRequestCallbackCannotAdvanceCurrentTurn() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "requestId", "other-request");
        SelfRun3Engine.State after = reduce(claimed, "stale-start", SelfRun3Engine.Kind.STARTED, p);
        assertSame(claimed, after);
        assertFalse(after.flag("dispatchObserved"));
    }

    @Test public void positiveNoDispatchProofReopensSamePreparedTurn() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject p = request(claimed); SelfRun3Engine.put(p, "status", "SEND_DISABLED");
        SelfRun3Engine.State reopened = reduce(claimed, "unsent", SelfRun3Engine.Kind.UNSENT, p);
        assertEquals(SelfRun3Engine.Stage.READY, reopened.stage());
        assertFalse(reopened.flag("sendClaimed"));
    }

    @Test(expected = IllegalStateException.class)
    public void unknownNoDispatchClaimCannotReopenTurn() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject p = request(claimed); SelfRun3Engine.put(p, "status", "TIMEOUT");
        reduce(claimed, "bad-unsent", SelfRun3Engine.Kind.UNSENT, p);
    }

    @Test public void pauseDuringRunningResponseResumesWaitingWithoutFreshSend() {
        SelfRun3Engine.State claimed = claimedState();
        SelfRun3Engine.State waiting = reduce(claimed, "started", SelfRun3Engine.Kind.STARTED, request(claimed));
        assertEquals(SelfRun3Engine.Stage.WAITING, waiting.stage());
        JSONObject pause = new JSONObject(); SelfRun3Engine.put(pause, "reason", "USER_PAUSE");
        SelfRun3Engine.State paused = reduce(waiting, "pause", SelfRun3Engine.Kind.PAUSE, pause);
        SelfRun3Engine.State resumed = reduce(paused, "resume", SelfRun3Engine.Kind.RESUME, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.WAITING, resumed.stage());
        assertTrue(resumed.flag("sendClaimed"));
        assertEquals(SelfRun3Engine.Action.WAIT, SelfRun3Engine.nextAction(resumed));
    }

    @Test public void stoppedTaskRequiresFreshDriveReadAndRepreparesSameTurn() throws Exception {
        SelfRun3Engine.State claimed = claimedState();
        SelfRun3Engine.State waiting = reduce(claimed, "started", SelfRun3Engine.Kind.STARTED, request(claimed));
        SelfRun3Engine.State stopped = reduce(waiting, "stop", SelfRun3Engine.Kind.STOP, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.STOPPED, stopped.stage());
        assertTrue(stopped.flag("taskStopped"));
        assertTrue(stopped.flag("sendClaimed"));

        SelfRun3Engine.State normalResume = reduce(stopped, "normal-resume", SelfRun3Engine.Kind.RESUME, new JSONObject());
        assertSame(stopped, normalResume);

        SelfRun3Engine.State resumed = reduce(stopped, "resume-stopped", SelfRun3Engine.Kind.RESUME_STOPPED, SelfRun3StoppedRecovery.plan(stopped, x -> SelfRun3Engine.emptyResult(x).toString()));
        assertEquals(SelfRun3Engine.Stage.PREPARING, resumed.stage());
        assertFalse(resumed.flag("taskStopped"));
        assertFalse(resumed.flag("sendClaimed"));
        assertEquals(SelfRun3Engine.Action.PREPARE_TURN, SelfRun3Engine.nextAction(resumed));

        SelfRun3Engine.State duplicate = reduce(resumed, "resume-stopped-again", SelfRun3Engine.Kind.RESUME_STOPPED, new JSONObject());
        assertSame(resumed, duplicate);
    }

    @Test public void stoppedTaskWithCommittedDriveResultResumesAtCommitWithoutRedispatch() throws Exception {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject resultPayload = new JSONObject();
        SelfRun3Engine.put(resultPayload, "text", continueResult(claimed).toString());
        SelfRun3Engine.State withResult = reduce(claimed, claimed.turnId() + ":result", SelfRun3Engine.Kind.RESULT, resultPayload);
        assertEquals(SelfRun3Engine.Action.COMMIT, SelfRun3Engine.nextAction(withResult));

        SelfRun3Engine.State stopped = reduce(withResult, "stop-with-result", SelfRun3Engine.Kind.STOP, new JSONObject());
        SelfRun3Engine.State resumed = reduce(stopped, "resume-stopped-result", SelfRun3Engine.Kind.RESUME_STOPPED, SelfRun3StoppedRecovery.plan(stopped, x -> continueResult(x).toString()));
        assertEquals(SelfRun3Engine.Stage.RECONCILING, resumed.stage());
        assertTrue(resumed.flag("sendClaimed"));
        assertTrue(resumed.hasResult());
        assertEquals(SelfRun3Engine.Action.COMMIT, SelfRun3Engine.nextAction(resumed));
    }

    @Test public void committedResultAdvancesWithoutTransportEnd() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject resultPayload = new JSONObject();
        SelfRun3Engine.put(resultPayload, "text", continueResult(claimed).toString());
        SelfRun3Engine.State withResult = reduce(claimed, claimed.turnId() + ":result", SelfRun3Engine.Kind.RESULT, resultPayload);
        assertTrue(withResult.hasResult());
        assertFalse(withResult.flag("ended"));
        assertEquals(SelfRun3Engine.Action.COMMIT, SelfRun3Engine.nextAction(withResult));
        JSONObject end = request(withResult); SelfRun3Engine.put(end, "source", "message_stream_complete");
        SelfRun3Engine.State ended = reduce(withResult, "ended", SelfRun3Engine.Kind.ENDED, end);
        assertEquals(SelfRun3Engine.Action.COMMIT, SelfRun3Engine.nextAction(ended));
    }

    @Test public void canonicalContinueNextPhaseCanBypassLegacyTransitionTable() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject result = continueResult(claimed);
        SelfRun3Engine.put(result, "phase_completed", "PLAN_REVIEWED");
        SelfRun3Engine.put(result, "next_phase", "VERIFY");
        JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "text", result.toString());
        SelfRun3Engine.State withResult = reduce(claimed, claimed.turnId() + ":result", SelfRun3Engine.Kind.RESULT, payload);
        SelfRun3Engine.State next = reduce(withResult, "commit", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.PREPARING, next.stage());
        assertEquals("VERIFY", next.text("phase"));
    }

    @Test public void unknownNextPhaseDoesNotInvalidateCommittedResult() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject result = continueResult(claimed);
        SelfRun3Engine.put(result, "next_phase", "REVIEW");
        JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "text", result.toString());
        SelfRun3Engine.State withResult = reduce(claimed, claimed.turnId() + ":result", SelfRun3Engine.Kind.RESULT, payload);
        SelfRun3Engine.State next = reduce(withResult, "commit", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.PREPARING, next.stage());
        assertEquals("PLAN", next.text("phase"));
    }

    @Test public void nonterminalDoneRoutingHintFallsBackInsteadOfRejectingTurn() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject result = continueResult(claimed);
        SelfRun3Engine.put(result, "next_phase", "DONE");
        JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "text", result.toString());
        SelfRun3Engine.State withResult = reduce(claimed, claimed.turnId() + ":result", SelfRun3Engine.Kind.RESULT, payload);
        SelfRun3Engine.State next = reduce(withResult, "commit", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.PREPARING, next.stage());
        assertEquals("PLAN", next.text("phase"));
    }

    @Test public void conflictingValidNextProfilesCreateRepairInsteadOfGuessing() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject result = continueResult(claimed);
        JSONObject next = new JSONObject();
        SelfRun3Engine.put(next, "type", "SERIAL");
        SelfRun3Engine.put(next, "objective", "use exact result-selected profile");
        SelfRun3Engine.put(next, "profile", chatProfile("gpt-5-6-thinking", "xhigh"));
        SelfRun3Engine.put(result, "next_execution", next);
        SelfRun3Engine.put(result, "next_profile", chatProfile("gpt-5-6-thinking", "high"));
        SelfRun3Engine.put(result, "profile", chatProfile("gpt-5-6-thinking", "medium"));
        JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "text", result.toString());
        SelfRun3Engine.State withResult = reduce(claimed, claimed.turnId() + ":result-priority", SelfRun3Engine.Kind.RESULT, payload);
        SelfRun3Engine.State advanced = reduce(withResult, "commit-priority", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals("CHAT", advanced.config().optString("mode"));
        assertEquals(claimed.config().optString("model"), advanced.config().optString("model"));
        assertEquals("medium", advanced.config().optString("reasoning"));
        assertEquals("REPAIR", advanced.text("executionKind"));
        assertTrue(advanced.json().optJSONArray("repairProblems").toString().contains("CONFLICT"));
    }

    @Test public void normalContinuationCannotUseTopLevelOrCurrentProfileFallback() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject result = continueResult(claimed);
        result.remove("next_profile");
        SelfRun3Engine.put(result, "profile", chatProfile("gpt-5-6-thinking", "high"));
        JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "text", result.toString());
        SelfRun3Engine.State withResult = reduce(claimed, claimed.turnId() + ":result-no-next-profile", SelfRun3Engine.Kind.RESULT, payload);
        SelfRun3Engine.State repair = reduce(withResult, "commit-no-next-profile", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals("REPAIR", repair.text("executionKind"));
        assertEquals(claimed.resource("resultDocumentId"), repair.text("repairTargetDocumentId"));
    }

    @Test public void hybridWorkToChatUsesExactResultProfile() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "taskMode", "HYBRID");
        SelfRun3Engine.put(config, "mode", "WORK");
        SelfRun3Engine.put(config, "model", "sol");
        SelfRun3Engine.put(config, "reasoning", "high");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/");
        SelfRun3Engine.State state = SelfRun3Engine.create("hybrid-task", "hybrid-task:turn:1", config);
        state = resource(state, "folderId", "folder");
        state = resource(state, "requirementDocumentId", "requirements");
        state = reduce(state, "hybrid-setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        state = resource(state, "resultDocumentId", "hybrid-result");
        JSONObject ready = new JSONObject(); SelfRun3Engine.put(ready, "prompt", "hello"); SelfRun3Engine.put(ready, "inputRevision", 0L);
        state = reduce(state, "hybrid-ready", SelfRun3Engine.Kind.TURN_READY, ready);
        JSONObject claim = new JSONObject(); SelfRun3Engine.put(claim, "at", 100L);
        state = reduce(state, "hybrid-claim", SelfRun3Engine.Kind.CLAIM_SEND, claim);

        JSONObject result = continueResult(state);
        SelfRun3Engine.put(result, "next_profile", chatProfile("gpt-5-6-thinking", "xhigh"));
        JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "text", result.toString());
        state = reduce(state, "hybrid-result-event", SelfRun3Engine.Kind.RESULT, payload);
        state = reduce(state, "hybrid-commit", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals("CHAT", state.config().optString("mode"));
        assertEquals("gpt-5-6-thinking", state.config().optString("model"));
        assertEquals("xhigh", state.config().optString("reasoning"));
    }

    @Test public void doneStatusIsTrustedAsAiDecisionInsteadOfRevalidatedByApp() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject result = doneResult(claimed);
        JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "text", result.toString());
        SelfRun3Engine.State withResult = reduce(claimed, claimed.turnId() + ":result", SelfRun3Engine.Kind.RESULT, payload);
        SelfRun3Engine.State done = reduce(withResult, "commit", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.DONE, done.stage());
        assertTrue(done.terminal());
    }

    @Test public void identityOnlyCommittedResultCreatesRecoveryTurn() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject result = identity(claimed);
        SelfRun3Engine.put(result, "committed", true);
        JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "text", result.toString());
        SelfRun3Engine.State withResult = reduce(claimed, claimed.turnId() + ":result", SelfRun3Engine.Kind.RESULT, payload);
        SelfRun3Engine.State next = reduce(withResult, "commit", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.PREPARING, next.stage());
        assertEquals("PLAN", next.text("phase"));
        assertEquals(2, next.turn());
    }

    @Test public void lateUserInputPreventsTerminalDoneAndCreatesFreshPlanTurn() {
        SelfRun3Engine.State verify = verifyCompletedState();
        JSONObject commit = new JSONObject();
        SelfRun3Engine.put(commit, "lateInput", true);
        SelfRun3Engine.put(commit, "lateInputRevision", 8L);
        SelfRun3Engine.put(commit, "nextTurnId", "task:turn:2");
        SelfRun3Engine.State after = reduce(verify, "commit-late", SelfRun3Engine.Kind.COMMIT, commit);
        assertEquals(SelfRun3Engine.Stage.PREPARING, after.stage());
        assertEquals("PLAN", after.text("phase"));
        assertEquals(2, after.turn());
        assertFalse(after.terminal());
        assertEquals(5L, after.time("lastConsumedInputRevision"));
    }

    @Test public void normalDoneCanOnlyCommitFromVerifiedResultFixture() {
        SelfRun3Engine.State verify = verifyCompletedState();
        SelfRun3Engine.State done = reduce(verify, "commit-done", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.DONE, done.stage());
        assertTrue(done.terminal());
    }

    private static SelfRun3Engine.State readyState() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/");
        SelfRun3Engine.State s = SelfRun3Engine.create("task", "task:turn:1", config);
        s = resource(s, "folderId", "folder");
        s = resource(s, "requirementDocumentId", "requirements");
        s = reduce(s, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        s = resource(s, "resultDocumentId", "resultdoc");
        JSONObject p = new JSONObject();
        SelfRun3Engine.put(p, "prompt", "hello");
        SelfRun3Engine.put(p, "inputText", "");
        SelfRun3Engine.put(p, "inputRevision", 0L);
        return reduce(s, "ready", SelfRun3Engine.Kind.TURN_READY, p);
    }

    private static SelfRun3Engine.State claimedState() {
        SelfRun3Engine.State s = readyState();
        JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "at", 100L);
        return reduce(s, "claim", SelfRun3Engine.Kind.CLAIM_SEND, p);
    }

    private static SelfRun3Engine.State verifyCompletedState() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        JSONObject raw = new JSONObject();
        SelfRun3Engine.put(raw, "schema", SelfRun3Engine.STATE_SCHEMA);
        SelfRun3Engine.put(raw, "taskId", "task");
        SelfRun3Engine.put(raw, "turnId", "task:turn:1");
        SelfRun3Engine.put(raw, "requestId", "task:turn:1-request");
        SelfRun3Engine.put(raw, "turn", 1);
        SelfRun3Engine.put(raw, "stage", SelfRun3Engine.Stage.RECONCILING.name());
        SelfRun3Engine.put(raw, "phase", "VERIFY");
        SelfRun3Engine.put(raw, "config", config);
        JSONObject resources = new JSONObject(); SelfRun3Engine.put(resources, "resultDocumentId", "resultdoc");
        SelfRun3Engine.put(raw, "resources", resources);
        SelfRun3Engine.put(raw, "sendClaimed", true);
        SelfRun3Engine.put(raw, "accepted", true);
        SelfRun3Engine.put(raw, "ended", true);
        SelfRun3Engine.put(raw, "inputRevision", 5L);
        SelfRun3Engine.put(raw, "lastConsumedInputRevision", 2L);
        SelfRun3Engine.State s = new SelfRun3Engine.State(raw);
        SelfRun3Engine.put(raw, "result", doneResult(s).toString());
        return new SelfRun3Engine.State(raw);
    }

    private static JSONObject continueResult(SelfRun3Engine.State s) {
        JSONObject r = identity(s);
        SelfRun3Engine.put(r, "committed", true);
        SelfRun3Engine.put(r, "status", "CONTINUE");
        SelfRun3Engine.put(r, "phase_completed", "PLAN");
        SelfRun3Engine.put(r, "next_phase", "WORK");
        SelfRun3Engine.put(r, "next_input", "");
        SelfRun3Engine.put(r, "next_profile", chatProfile("gpt-5-6-thinking", "medium"));
        SelfRun3Engine.put(r, "handoff", handoff());
        return r;
    }

    private static JSONObject doneResult(SelfRun3Engine.State s) {
        JSONObject r = identity(s);
        SelfRun3Engine.put(r, "committed", true);
        SelfRun3Engine.put(r, "status", "DONE");
        SelfRun3Engine.put(r, "phase_completed", "VERIFY_DONE");
        SelfRun3Engine.put(r, "next_phase", "DONE");
        SelfRun3Engine.put(r, "next_input", "");
        SelfRun3Engine.put(r, "handoff", handoff());
        return r;
    }

    private static JSONObject identity(SelfRun3Engine.State s) {
        JSONObject r = new JSONObject();
        SelfRun3Engine.put(r, "schema", SelfRun3Engine.RESULT_SCHEMA);
        SelfRun3Engine.put(r, "task_id", s.taskId());
        SelfRun3Engine.put(r, "turn_id", s.turnId());
        SelfRun3Engine.put(r, "turn", s.turn());
        SelfRun3Engine.put(r, "document_id", s.resource("resultDocumentId"));
        SelfRun3Engine.put(r, "event_id", s.turnId() + ":result");
        return r;
    }

    private static JSONObject handoff() {
        JSONObject h = new JSONObject();
        SelfRun3Engine.put(h, "objective", "test");
        SelfRun3Engine.put(h, "completed", "done");
        SelfRun3Engine.put(h, "remaining", "none");
        SelfRun3Engine.put(h, "evidence", "test");
        SelfRun3Engine.put(h, "constraints", "none");
        SelfRun3Engine.put(h, "next_action", "continue");
        for (String key : new String[]{"requirements","decisions","assumptions","materials","external_state","verification_state","do_not_repeat"}) SelfRun3Engine.put(h,key,new org.json.JSONArray());
        return h;
    }

    private static JSONObject chatProfile(String model, String reasoning) {
        JSONObject profile = new JSONObject();
        SelfRun3Engine.put(profile, "mode", "CHAT");
        SelfRun3Engine.put(profile, "model", model);
        SelfRun3Engine.put(profile, "reasoning", reasoning);
        return profile;
    }

    private static JSONObject request(SelfRun3Engine.State s) {
        JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "requestId", s.requestId()); return p;
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State s, String key, String value) {
        JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "key", key); SelfRun3Engine.put(p, "value", value);
        return reduce(s, "resource:" + key, SelfRun3Engine.Kind.RESOURCE, p);
    }

    private static SelfRun3Engine.State reduce(SelfRun3Engine.State s, String id,
                                                SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(s, new SelfRun3Engine.Event(id, kind, s.taskId(), s.turnId(), payload));
    }
}
