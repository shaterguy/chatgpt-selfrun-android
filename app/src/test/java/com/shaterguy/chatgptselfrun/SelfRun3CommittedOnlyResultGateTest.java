package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Completion and RESULT_REPAIR eligibility are gated only by JSON boolean committed=true. */
public final class SelfRun3CommittedOnlyResultGateTest {
    @Test public void committedTrueIgnoresMachineIdentityAndSemanticFields() {
        SelfRun3Engine.State state = state();
        JSONObject body = new JSONObject();
        put(body, "committed", true);
        put(body, "schema", "nonstandard");
        put(body, "task_id", "other-task");
        put(body, "turn_id", "other-turn");
        put(body, "turn", 999);
        put(body, "document_id", "other-document");
        put(body, "event_id", "other-event");
        put(body, "status", new JSONObject().put("unexpected", true));
        put(body, "phase_completed", 17);
        put(body, "next_phase", JSONObject.NULL);
        put(body, "handoff", "not-a-handoff");

        JSONObject parsed = SelfRun3Engine.parseResult(body.toString(), state);
        assertNotNull(parsed);
        assertTrue(parsed.optBoolean("committed"));
    }

    @Test public void onlyJsonBooleanTrueIsCommitted() {
        SelfRun3Engine.State state = state();
        JSONObject body = SelfRun3Engine.emptyResult(state);

        assertNull(SelfRun3Engine.parseResult(body.toString(), state));

        put(body, "committed", "true");
        assertNull(SelfRun3Engine.parseResult(body.toString(), state));

        body.remove("committed");
        assertNull(SelfRun3Engine.parseResult(body.toString(), state));

        put(body, "committed", 1);
        assertNull(SelfRun3Engine.parseResult(body.toString(), state));

        put(body, "committed", true);
        assertNotNull(SelfRun3Engine.parseResult(body.toString(), state));
    }

    @Test public void invalidJsonNeverPretendsToBeCommitted() {
        SelfRun3Engine.State state = state();
        assertThrows(RuntimeException.class,
                () -> SelfRun3Engine.parseResult("{\"committed\":true", state));
    }

    private static SelfRun3Engine.State state() {
        JSONObject config = new JSONObject();
        put(config, "mode", "CHAT");
        put(config, "reasoning", "medium");
        SelfRun3Engine.State state = SelfRun3Engine.create("gate-task", "gate-task:turn:1", config);
        JSONObject resource = new JSONObject();
        put(resource, "key", "resultDocumentId");
        put(resource, "value", "result-doc");
        return SelfRun3Engine.reduce(state, new SelfRun3Engine.Event("pin-result",
                SelfRun3Engine.Kind.RESOURCE, state.taskId(), state.turnId(), resource));
    }

    private static void put(JSONObject object, String key, Object value) {
        SelfRun3Engine.put(object, key, value);
    }
}
