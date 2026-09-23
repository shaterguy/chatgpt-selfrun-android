package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public final class SelfRun3RecoveryBehaviorTest {
    @Test public void stoppedResumeWaitsOnPinnedResultWithoutRedispatch() {
        SelfRun3Engine.State stopped = stop(waiting());
        String originalRequestId = stopped.requestId();
        SelfRun3Engine.State resumed = resume(stopped);

        assertEquals(SelfRun3Engine.Stage.WAITING, resumed.stage());
        assertEquals(stopped.turnId(), resumed.turnId());
        assertEquals(stopped.turn(), resumed.turn());
        assertEquals(stopped.resource("resultDocumentId"), resumed.resource("resultDocumentId"));
        assertEquals(stopped.resource("requirementDocumentId"), resumed.resource("requirementDocumentId"));
        assertEquals(originalRequestId, resumed.requestId());
        assertFalse(resumed.flag("taskStopped"));
        assertTrue(resumed.flag("sendClaimed"));
        assertTrue(resumed.flag("stoppedResumeWait"));
        assertEquals(SelfRun3Engine.Action.WAIT, SelfRun3Engine.nextAction(resumed));
    }

    @Test public void stoppedResumeKeepsCommittedLedgerResultForCommit() {
        SelfRun3Engine.State state = waiting();
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "text", committedResult(state).toString());
        state = event(state, SelfRun3Engine.Kind.RESULT, payload);

        SelfRun3Engine.State resumed = resume(stop(state));
        assertTrue(resumed.hasResult());
        assertTrue(resumed.flag("sendClaimed"));
        assertEquals(SelfRun3Engine.Stage.RECONCILING, resumed.stage());
        assertEquals(SelfRun3Engine.Action.COMMIT, SelfRun3Engine.nextAction(resumed));
    }

    private static SelfRun3Engine.State waiting() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "model", "gpt-5-6-thinking");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.put(config, "taskMode", "CHAT");
        SelfRun3Engine.State state = SelfRun3Engine.create("recovery-test", "recovery-test:turn:1", config);
        state = resource(state, "folderId", "folder");
        state = resource(state, "requirementDocumentId", "requirements");
        state = event(state, SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        state = resource(state, "resultDocumentId", "result-one");
        JSONObject ready = new JSONObject();
        SelfRun3Engine.put(ready, "prompt", "original");
        SelfRun3Engine.put(ready, "inputText", "keep input");
        SelfRun3Engine.put(ready, "inputRevision", 4L);
        state = event(state, SelfRun3Engine.Kind.TURN_READY, ready);
        state = event(state, SelfRun3Engine.Kind.CLAIM_SEND, new JSONObject());
        JSONObject started = new JSONObject();
        SelfRun3Engine.put(started, "requestId", state.requestId());
        return event(state, SelfRun3Engine.Kind.STARTED, started);
    }

    private static JSONObject committedResult(SelfRun3Engine.State state) {
        JSONObject result = SelfRun3Engine.emptyResult(state);
        SelfRun3Engine.put(result, "committed", true);
        SelfRun3Engine.put(result, "status", "CONTINUE");
        SelfRun3Engine.put(result, "next_phase", "WORK");
        JSONObject profile = new JSONObject();
        SelfRun3Engine.put(profile, "mode", "CHAT");
        SelfRun3Engine.put(profile, "model", "gpt-5-6-thinking");
        SelfRun3Engine.put(profile, "reasoning", "medium");
        SelfRun3Engine.put(result, "next_profile", profile);
        return result;
    }

    private static SelfRun3Engine.State stop(SelfRun3Engine.State state) {
        return event(state, SelfRun3Engine.Kind.STOP, new JSONObject());
    }

    private static SelfRun3Engine.State resume(SelfRun3Engine.State state) {
        return event(state, SelfRun3Engine.Kind.RESUME_STOPPED, new JSONObject());
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return event(state, SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State event(SelfRun3Engine.State state, SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(state, new SelfRun3Engine.Event(
                UUID.randomUUID().toString(), kind, state.taskId(), state.turnId(), payload));
    }
}
