package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Mechanical Result identity and exact-file authority contract. */
public final class SelfRun3ResultIdentityContractTest {
    @Test public void exactCommittedResultIdentityIsAccepted() {
        SelfRun3Engine.State state = preparingState();
        assertNotNull(SelfRun3Engine.parseResult(committed(state).toString(), state));
    }

    @Test public void movedExactResultDocumentRemainsReadable() {
        DriveApiClient.Metadata metadata = metadata("result-doc-1234", "moved-folder");
        assertTrue(SelfRun3ResultDocumentPolicy.acceptReadableResult(
                metadata, "result-doc-1234", "original-folder"));
    }

    @Test public void movedDifferentResultDocumentIsStillRejected() {
        DriveApiClient.Metadata metadata = metadata("different-result-doc", "moved-folder");
        assertFalse(SelfRun3ResultDocumentPolicy.acceptReadableResult(
                metadata, "result-doc-1234", "original-folder"));
    }

    @Test public void wrongMechanicalIdentityFieldsAreRejected() {
        SelfRun3Engine.State state = preparingState();
        JSONObject valid = committed(state);

        assertRejectedWith(state, valid, "schema", "other-result-schema");
        assertRejectedWith(state, valid, "task_id", "other-task");
        assertRejectedWith(state, valid, "turn_id", "authority-task:turn:2");
        assertRejectedWith(state, valid, "turn", 2);
        assertRejectedWith(state, valid, "document_id", "other-result-doc");
        assertRejectedWith(state, valid, "event_id", "authority-task:turn:1:other-event");
    }

    @Test public void missingMechanicalIdentityFieldsAreRejected() {
        SelfRun3Engine.State state = preparingState();
        JSONObject valid = committed(state);
        for (String key : List.of("schema", "task_id", "turn_id", "turn", "document_id", "event_id")) {
            JSONObject candidate = copy(valid);
            candidate.remove(key);
            assertNull("missing " + key + " must be rejected",
                    SelfRun3Engine.parseResult(candidate.toString(), state));
        }
    }

    @Test public void stringTurnIsRejectedEvenWhenNumericTextMatches() {
        SelfRun3Engine.State state = preparingState();
        JSONObject candidate = copy(committed(state));
        put(candidate, "turn", "1");
        assertNull(SelfRun3Engine.parseResult(candidate.toString(), state));
    }

    @Test public void wrongIdentityInExtraOrChildTabCannotBecomeCommittedCandidate() {
        SelfRun3Engine.State state = preparingState();
        JSONObject wrong = copy(committed(state));
        put(wrong, "document_id", "child-tab-result-doc");

        SelfRun3ResultDocumentPolicy.Selection selected = SelfRun3ResultDocumentPolicy.select(
                List.of(SelfRun3Engine.emptyResult(state).toString(), wrong.toString()), state);

        assertFalse(selected.committed);
    }

    @Test public void wrongIdentityCannotAdvanceReducer() {
        SelfRun3Engine.State state = preparingState();
        JSONObject wrong = copy(committed(state));
        put(wrong, "task_id", "other-task");
        JSONObject payload = new JSONObject();
        put(payload, "text", wrong.toString());

        SelfRun3Engine.State after = event(state, state.turnId() + ":wrong-result",
                SelfRun3Engine.Kind.RESULT, payload);

        assertFalse(after.hasResult());
        assertEquals(SelfRun3Engine.Stage.PREPARING, after.stage());
    }

    private static void assertRejectedWith(SelfRun3Engine.State state, JSONObject valid,
                                           String key, Object value) {
        JSONObject candidate = copy(valid);
        put(candidate, key, value);
        assertNull("wrong " + key + " must be rejected",
                SelfRun3Engine.parseResult(candidate.toString(), state));
    }

    private static DriveApiClient.Metadata metadata(String id, String parent) {
        JSONObject json = new JSONObject();
        put(json, "id", id);
        put(json, "name", "fixture");
        put(json, "mimeType", DriveApiClient.MIME_DOCUMENT);
        put(json, "parents", new JSONArray().put(parent));
        put(json, "trashed", false);
        put(json, "shared", true);
        put(json, "isAppAuthorized", false);
        return new DriveApiClient.Metadata(json);
    }

    private static SelfRun3Engine.State preparingState() {
        JSONObject config = new JSONObject();
        put(config, "mode", "CHAT");
        put(config, "taskMode", "CHAT");
        put(config, "reasoning", "xhigh");
        SelfRun3Engine.State state = SelfRun3Engine.create(
                "authority-task", "authority-task:turn:1", config);
        state = resource(state, "folderId", "folder-1234");
        state = resource(state, "requirementDocumentId", "requirement-1234");
        state = event(state, state.turnId() + ":setup",
                SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        return resource(state, "resultDocumentId", "result-doc-1234");
    }

    private static JSONObject committed(SelfRun3Engine.State state) {
        JSONObject result = SelfRun3Engine.emptyResult(state);
        put(result, "committed", true);
        put(result, "status", "CONTINUE");
        put(result, "phase_completed", "WORK");
        put(result, "next_phase", "WORK");
        put(result, "next_input", "");
        return result;
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        put(payload, "key", key);
        put(payload, "value", value);
        return event(state, state.turnId() + ":resource:" + key,
                SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State event(SelfRun3Engine.State state, String id,
                                               SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event(id, kind, state.taskId(), state.turnId(), payload));
    }

    private static JSONObject copy(JSONObject value) {
        return SelfRun3Engine.copy(value);
    }

    private static void put(JSONObject object, String key, Object value) {
        SelfRun3Engine.put(object, key, value);
    }
}
