package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import java.util.function.BooleanSupplier;

/** Event-driven terminal delivery, independent of the browser and its cached token. */
final class SelfRunTerminalControlDelivery {
    record Entry(String taskId, String turnId, String requestId, String folderId,
                 String conversationUrl, String state, String reason) {
        JSONObject json() throws Exception {
            return new JSONObject().put("task_id", taskId).put("turn_id", turnId)
                    .put("request_id", requestId).put("folder_id", folderId)
                    .put("conversation_url", conversationUrl).put("state", state).put("reason", reason);
        }
        static Entry from(JSONObject value) throws Exception {
            return new Entry(value.getString("task_id"), value.getString("turn_id"),
                    value.getString("request_id"), value.getString("folder_id"),
                    value.optString("conversation_url"), value.getString("state"), value.optString("reason"));
        }
    }
    interface Remote {
        String authorize() throws Exception;
        JSONObject read(Entry entry, String token) throws Exception;
        void write(Entry entry, String token, JSONObject value) throws Exception;
    }
    private static boolean terminal(JSONObject value, Entry entry) {
        return value != null && "selfrun-task-control-v1".equals(value.optString("schema"))
                && entry.taskId().equals(value.optString("task_id"))
                && ("DONE".equals(value.optString("state")) || "STOPPED".equals(value.optString("state")));
    }
    static void deliver(Entry entry, Remote remote, BooleanSupplier current) throws Exception {
        if (!current.getAsBoolean()) return;
        String token = remote.authorize();
        JSONObject before = remote.read(entry, token);
        if (!current.getAsBoolean() || terminal(before, entry)) return;
        // Drive creates metadata before uploading media. An interrupted create
        // leaves the exact task's discovered file empty and is safe to complete.
        if (before != null && before.length() == 0) before = null;
        if (before != null && (!"selfrun-task-control-v1".equals(before.optString("schema"))
                || !entry.taskId().equals(before.optString("task_id"))))
            throw new IllegalStateException("task control identity mismatch");
        long nextEpoch = before == null ? 1L : before.optLong("control_epoch", 0L) + 1L;
        JSONObject body = entry.json().put("schema", "selfrun-task-control-v1")
                .put("control_epoch", nextEpoch).put("updated_at_ms", System.currentTimeMillis());
        body.remove("folder_id");
        Exception writeFailure = null;
        try { if (current.getAsBoolean()) remote.write(entry, token, body); else return; }
        catch (Exception error) { writeFailure = error; }
        // A lost response is not permission to issue a duplicate write.
        JSONObject after = remote.read(entry, token);
        if (terminal(after, entry) && after.optLong("control_epoch", 0L) >= nextEpoch) return;
        if (writeFailure != null) throw writeFailure;
        throw new IllegalStateException("terminal control readback pending");
    }
}
