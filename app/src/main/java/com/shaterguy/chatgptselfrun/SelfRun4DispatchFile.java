package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;

/** Drive message shared by the Android state machine and the Termux browser executor. */
final class SelfRun4DispatchFile {
    static final String SCHEMA = "selfrun-drive-dispatch-v1";
    static final String SUFFIX = ".selfrun-dispatch.json";
    static final String PREPARE_REQUESTED = "PREPARE_REQUESTED";
    static final String READY_TO_SUBMIT = "READY_TO_SUBMIT";
    static final String SEND_REQUESTED = "SEND_REQUESTED";
    static final String SUBMITTED = "SUBMITTED";
    static final String STARTED = "STARTED";
    static final String ERROR = "ERROR";

    private SelfRun4DispatchFile() {}

    static String fileName(SelfRun3Engine.State state) {
        String fingerprint = SelfRun3ResultWatchdog.fingerprint(state.requestId());
        String task = state.taskId().replaceAll("[^A-Za-z0-9._-]", "_");
        if (task.length() > 80) task = task.substring(0, 80);
        return "dispatch-" + task + "-turn-" + state.turn() + "-"
                + fingerprint.substring(0, 20) + SUFFIX;
    }

    static JSONObject prepare(SelfRun3Engine.State state, String fileId, long attempt) {
        if (state == null || !DriveApiClient.validFileId(fileId) || attempt <= 0L) {
            throw new IllegalArgumentException("valid dispatch identity required");
        }
        JSONObject out = new JSONObject();
        SelfRun3Engine.put(out, "schema", SCHEMA);
        SelfRun3Engine.put(out, "dispatch_file_id", fileId);
        SelfRun3Engine.put(out, "task_id", state.taskId());
        SelfRun3Engine.put(out, "turn_id", state.turnId());
        SelfRun3Engine.put(out, "request_id", state.requestId());
        SelfRun3Engine.put(out, "attempt", attempt);
        SelfRun3Engine.put(out, "project_url", state.config().optString("projectUrl"));
        SelfRun3Engine.put(out, "prompt", state.text("prompt"));
        SelfRun3Engine.put(out, "result_document_id", state.resource("resultDocumentId"));
        SelfRun3Engine.put(out, "requirement_document_id", state.resource("requirementDocumentId"));
        SelfRun3Engine.put(out, "previous_result_document_id", state.text("previousResultDocumentId"));
        SelfRun3Engine.put(out, "execution_mode", state.config().optString("mode"));
        ProfileRegistry.Profile profile = resolveProfile(state);
        SelfRun3Engine.put(out, "profile_fingerprint", profile.fingerprint);
        JSONArray operations = new JSONArray();
        for (ProfileRegistry.Operation operation : profile.operations) operations.put(operation.toJson());
        SelfRun3Engine.put(out, "profile_operations", operations);
        SelfRun3Engine.put(out, "state", PREPARE_REQUESTED);
        SelfRun3Engine.put(out, "conversation_url", "");
        SelfRun3Engine.put(out, "updated_at_ms", System.currentTimeMillis());
        return out;
    }

    static JSONObject sendRequested(JSONObject current, SelfRun3Engine.State state, long attempt) {
        requireMatch(current, state, attempt);
        JSONObject out = copy(current);
        SelfRun3Engine.put(out, "state", SEND_REQUESTED);
        SelfRun3Engine.put(out, "updated_at_ms", System.currentTimeMillis());
        return out;
    }

    static JSONObject resultCommitted(JSONObject current, SelfRun3Engine.State state) {
        if (current == null || state == null
                || !SCHEMA.equals(current.optString("schema"))
                || !state.taskId().equals(current.optString("task_id"))
                || !state.turnId().equals(current.optString("turn_id"))
                || !state.requestId().equals(current.optString("request_id"))) {
            throw new IllegalStateException("dispatch identity changed");
        }
        JSONObject out = copy(current);
        SelfRun3Engine.put(out, "state", RESULT_COMMITTED);
        SelfRun3Engine.put(out, "updated_at_ms", System.currentTimeMillis());
        return out;
    }

    static boolean matches(JSONObject value, SelfRun3Engine.State state, long attempt) {
        if (value == null || state == null || attempt <= 0L) return false;
        return SCHEMA.equals(value.optString("schema"))
                && state.taskId().equals(value.optString("task_id"))
                && state.turnId().equals(value.optString("turn_id"))
                && state.requestId().equals(value.optString("request_id"))
                && attempt == value.optLong("attempt", -1L);
    }

    static String status(JSONObject value) {
        return value == null ? "" : value.optString("state", "");
    }

    static String conversationUrl(JSONObject value) {
        return value == null ? "" : value.optString("conversation_url", "");
    }

    static String errorCode(JSONObject value) {
        return value == null ? "" : value.optString("error_code", "");
    }

    private static void requireMatch(JSONObject current, SelfRun3Engine.State state, long attempt) {
        if (!matches(current, state, attempt)) throw new IllegalStateException("dispatch identity changed");
    }

    private static ProfileRegistry.Profile resolveProfile(SelfRun3Engine.State state) {
        JSONObject config = state.config();
        String mode = config.optString("mode");
        String model = config.optString("model");
        String reasoning = config.optString("reasoning");
        ProfileRegistry.Profile profile;
        if (SelfRunStore.MODE_WORK.equals(mode)) {
            profile = ProfileRegistry.resolveWork(model, reasoning);
        } else {
            profile = model.isEmpty()
                    ? ProfileRegistry.resolveChat(reasoning)
                    : ProfileRegistry.resolveChat(model, reasoning);
        }
        if (profile == null) throw new IllegalStateException("dispatch request profile unavailable");
        return profile;
    }

    private static JSONObject copy(JSONObject value) {
        return value == null ? new JSONObject() : new JSONObject(value.toString());
    }
}
