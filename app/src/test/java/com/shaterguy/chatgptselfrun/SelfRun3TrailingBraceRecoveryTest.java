package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class SelfRun3TrailingBraceRecoveryTest {
    @Test public void oneRedundantTrailingBraceIsRecoveredAfterMachineIdentityValidation() {
        SelfRun3Engine.State state = claimedState();
        String valid = continueResult(state).toString();
        String recovered = SelfRun3DriveAdapter.recoverSingleRedundantTrailingBrace(valid + "}\n", state);
        assertEquals(valid, recovered);
        assertNotNull(SelfRun3Engine.parseResult(recovered, state));
    }

    @Test public void arbitraryTrailingDataIsNotRecovered() {
        SelfRun3Engine.State state = claimedState();
        String valid = continueResult(state).toString();
        assertEquals("", SelfRun3DriveAdapter.recoverSingleRedundantTrailingBrace(valid + "x", state));
    }

    @Test public void semanticCheckpointDriftDoesNotBlockTrailingBraceRecovery() {
        SelfRun3Engine.State state = claimedState();
        JSONObject drifted = continueResult(state);
        SelfRun3Engine.put(drifted, "next_phase", "DONE");
        drifted.remove("handoff");
        String valid = drifted.toString();
        assertEquals(valid, SelfRun3DriveAdapter.recoverSingleRedundantTrailingBrace(valid + "}", state));
    }

    @Test public void wrongMachineIdentityIsNotRecovered() {
        SelfRun3Engine.State state = claimedState();
        JSONObject invalid = continueResult(state);
        SelfRun3Engine.put(invalid, "document_id", "other");
        assertEquals("", SelfRun3DriveAdapter.recoverSingleRedundantTrailingBrace(invalid.toString() + "}", state));
    }

    private static SelfRun3Engine.State claimedState() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "taskMode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "xhigh");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/g/project/project");
        SelfRun3Engine.State s = SelfRun3Engine.create("task", "task:turn:1", config);
        s = resource(s, "folderId", "folder");
        s = resource(s, "requirementDocumentId", "requirements");
        s = reduce(s, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        s = resource(s, "resultDocumentId", "resultdoc");
        JSONObject ready = new JSONObject();
        SelfRun3Engine.put(ready, "prompt", "hello");
        SelfRun3Engine.put(ready, "inputText", "");
        SelfRun3Engine.put(ready, "inputRevision", 0L);
        s = reduce(s, "ready", SelfRun3Engine.Kind.TURN_READY, ready);
        JSONObject claim = new JSONObject(); SelfRun3Engine.put(claim, "at", 100L);
        return reduce(s, "claim", SelfRun3Engine.Kind.CLAIM_SEND, claim);
    }

    private static JSONObject continueResult(SelfRun3Engine.State s) {
        JSONObject r = new JSONObject();
        SelfRun3Engine.put(r, "schema", SelfRun3Engine.RESULT_SCHEMA);
        SelfRun3Engine.put(r, "task_id", s.taskId());
        SelfRun3Engine.put(r, "turn_id", s.turnId());
        SelfRun3Engine.put(r, "turn", s.turn());
        SelfRun3Engine.put(r, "document_id", s.resource("resultDocumentId"));
        SelfRun3Engine.put(r, "event_id", s.turnId() + ":result");
        SelfRun3Engine.put(r, "committed", true);
        SelfRun3Engine.put(r, "status", "CONTINUE");
        SelfRun3Engine.put(r, "phase_completed", "PLAN");
        SelfRun3Engine.put(r, "next_phase", "WORK");
        SelfRun3Engine.put(r, "next_input", "");
        SelfRun3Engine.put(r, "handoff", handoff());
        JSONObject profile = new JSONObject();
        SelfRun3Engine.put(profile, "mode", "CHAT");
        SelfRun3Engine.put(profile, "model", "");
        SelfRun3Engine.put(profile, "reasoning", "instant");
        SelfRun3Engine.put(r, "next_profile", profile);
        return r;
    }

    private static JSONObject handoff() {
        JSONObject h = new JSONObject();
        SelfRun3Engine.put(h, "objective", "test");
        SelfRun3Engine.put(h, "completed", new JSONArray());
        SelfRun3Engine.put(h, "remaining", new JSONArray());
        SelfRun3Engine.put(h, "evidence", new JSONArray());
        SelfRun3Engine.put(h, "constraints", new JSONArray());
        SelfRun3Engine.put(h, "next_action", "continue");
        SelfRun3Engine.put(h, "requirements", new JSONObject());
        SelfRun3Engine.put(h, "decisions", new JSONArray());
        SelfRun3Engine.put(h, "assumptions", new JSONArray());
        SelfRun3Engine.put(h, "materials", new JSONObject());
        SelfRun3Engine.put(h, "external_state", new JSONObject());
        SelfRun3Engine.put(h, "verification_state", new JSONObject());
        SelfRun3Engine.put(h, "do_not_repeat", new JSONArray());
        return h;
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State s, String key, String value) {
        JSONObject p = new JSONObject();
        SelfRun3Engine.put(p, "key", key);
        SelfRun3Engine.put(p, "value", value);
        return reduce(s, "resource:" + key, SelfRun3Engine.Kind.RESOURCE, p);
    }

    private static SelfRun3Engine.State reduce(SelfRun3Engine.State s, String id,
                                                SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(s,
                new SelfRun3Engine.Event(id, kind, s.taskId(), s.turnId(), payload));
    }
}
