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

    @Test public void commitRequiresBothTransportEndAndCommittedResult() {
        SelfRun3Engine.State claimed = claimedState();
        JSONObject resultPayload = new JSONObject();
        SelfRun3Engine.put(resultPayload, "text", continueResult(claimed).toString());
        SelfRun3Engine.State withResult = reduce(claimed, claimed.turnId() + ":result", SelfRun3Engine.Kind.RESULT, resultPayload);
        assertTrue(withResult.hasResult());
        assertFalse(withResult.flag("ended"));
        assertEquals(SelfRun3Engine.Action.CHECK_RECEIPT, SelfRun3Engine.nextAction(withResult));
        JSONObject end = request(withResult); SelfRun3Engine.put(end, "source", "message_stream_complete");
        SelfRun3Engine.State ended = reduce(withResult, "ended", SelfRun3Engine.Kind.ENDED, end);
        assertEquals(SelfRun3Engine.Action.COMMIT, SelfRun3Engine.nextAction(ended));
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

    @Test public void normalDoneCanOnlyCommitFromVerifiedResult() {
        SelfRun3Engine.State verify = verifyCompletedState();
        SelfRun3Engine.State done = reduce(verify, "commit-done", SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(SelfRun3Engine.Stage.DONE, done.stage());
        assertTrue(done.terminal());
    }

    private static SelfRun3Engine.State readyState() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
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
        return h;
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
