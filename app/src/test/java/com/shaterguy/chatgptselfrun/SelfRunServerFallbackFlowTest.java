package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public final class SelfRunServerFallbackFlowTest {
    @Test public void forcedServerFailureImmediatelyEnablesLocalReadAndCommittedResultAdvancesTurn() {
        SelfRun3Engine.State claimed = claimedState();
        Set<String> registered = new HashSet<>();
        Set<String> fallback = new HashSet<>();
        Map<String, Long> nextPoll = new HashMap<>();
        registered.add(claimed.turnId());
        nextPoll.put(claimed.turnId(), Long.MAX_VALUE);

        SelfRunServerFallbackPolicy.activate(registered, fallback, nextPoll, claimed.turnId());

        assertFalse(registered.contains(claimed.turnId()));
        assertTrue(fallback.contains(claimed.turnId()));
        assertFalse(SelfRunServerWaitPolicy.useServerPush(
                SelfRun3RuntimeSettings.WorkMode.SERVER, fallback.contains(claimed.turnId())));
        assertTrue(SelfRunServerFallbackPolicy.localReadDue(
                SelfRun3RuntimeSettings.WorkMode.SERVER, fallback, nextPoll, claimed.turnId(), 1234L));

        JSONObject resultPayload = new JSONObject();
        SelfRun3Engine.put(resultPayload, "text", continueResult(claimed).toString());
        SelfRun3Engine.State withResult = reduce(claimed, claimed.turnId() + ":result",
                SelfRun3Engine.Kind.RESULT, resultPayload);
        assertEquals(SelfRun3Engine.Action.COMMIT, SelfRun3Engine.nextAction(withResult));

        SelfRun3Engine.State advanced = reduce(withResult, claimed.turnId() + ":commit",
                SelfRun3Engine.Kind.COMMIT, new JSONObject());
        assertEquals(2, advanced.turn());
        assertEquals(SelfRun3Engine.Stage.PREPARING, advanced.stage());
        assertEquals("WORK", advanced.text("phase"));
    }

    private static SelfRun3Engine.State claimedState() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/");
        SelfRun3Engine.State state = SelfRun3Engine.create("task", "task:turn:1", config);
        state = resource(state, "folderId", "folder");
        state = resource(state, "requirementDocumentId", "requirements");
        state = reduce(state, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        state = resource(state, "resultDocumentId", "resultdoc");
        JSONObject ready = new JSONObject();
        SelfRun3Engine.put(ready, "prompt", "hello");
        SelfRun3Engine.put(ready, "inputText", "");
        SelfRun3Engine.put(ready, "inputRevision", 0L);
        state = reduce(state, "ready", SelfRun3Engine.Kind.TURN_READY, ready);
        JSONObject claim = new JSONObject();
        SelfRun3Engine.put(claim, "at", 100L);
        return reduce(state, "claim", SelfRun3Engine.Kind.CLAIM_SEND, claim);
    }

    private static JSONObject continueResult(SelfRun3Engine.State state) {
        JSONObject result = new JSONObject();
        SelfRun3Engine.put(result, "schema", SelfRun3Engine.RESULT_SCHEMA);
        SelfRun3Engine.put(result, "task_id", state.taskId());
        SelfRun3Engine.put(result, "turn_id", state.turnId());
        SelfRun3Engine.put(result, "turn", state.turn());
        SelfRun3Engine.put(result, "document_id", state.resource("resultDocumentId"));
        SelfRun3Engine.put(result, "event_id", state.turnId() + ":result");
        SelfRun3Engine.put(result, "committed", true);
        SelfRun3Engine.put(result, "status", "CONTINUE");
        SelfRun3Engine.put(result, "phase_completed", "PLAN");
        SelfRun3Engine.put(result, "next_phase", "WORK");
        SelfRun3Engine.put(result, "next_input", "");
        JSONObject handoff = new JSONObject();
        SelfRun3Engine.put(handoff, "objective", "fallback flow test");
        SelfRun3Engine.put(handoff, "completed", "result read");
        SelfRun3Engine.put(handoff, "remaining", "none");
        SelfRun3Engine.put(handoff, "evidence", "deterministic state transition");
        SelfRun3Engine.put(handoff, "constraints", "none");
        SelfRun3Engine.put(handoff, "next_action", "advance");
        for (String key : new String[]{"requirements", "decisions", "assumptions", "materials",
                "external_state", "verification_state", "do_not_repeat"}) {
            SelfRun3Engine.put(handoff, key, new JSONArray());
        }
        SelfRun3Engine.put(result, "handoff", handoff);
        return result;
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return reduce(state, state.turnId() + ":resource:" + key, SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State reduce(SelfRun3Engine.State state, String id,
                                                SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event(id, kind, state.taskId(), state.turnId(), payload));
    }
}
