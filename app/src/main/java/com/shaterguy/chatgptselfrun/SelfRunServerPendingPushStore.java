package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Durable identity of the SERVER push generation currently known to be unresolved/PENDING. */
final class SelfRunServerPendingPushStore {
    private static final String PREFS =
            BuildConfig.APPLICATION_ID + ".selfrun-server-pending-push";
    private static final String PREFIX = "turn:";

    private SelfRunServerPendingPushStore() { }

    static boolean isSamePending(Context context, SelfRunPushEvent event) {
        if (event == null) return false;
        SelfRunPushEvent pending = load(context, event.turnId);
        return pending != null && pending.sameLogicalEvent(event);
    }

    static void markPending(Context context, SelfRunPushEvent event) {
        if (context == null || event == null) throw new IllegalArgumentException("pending push required");
        JSONObject body = new JSONObject();
        SelfRun3Engine.put(body, "eventId", event.eventId);
        SelfRun3Engine.put(body, "installationId", event.installationId);
        SelfRun3Engine.put(body, "applicationId", event.applicationId);
        SelfRun3Engine.put(body, "taskId", event.taskId);
        SelfRun3Engine.put(body, "turnId", event.turnId);
        SelfRun3Engine.put(body, "resultDocumentId", event.resultDocumentId);
        if (!prefs(context).edit().putString(key(event.turnId), body.toString()).commit()) {
            throw new IllegalStateException("pending push persist failed");
        }
    }

    static SelfRunPushEvent load(Context context, String turnId) {
        if (context == null || turnId == null || turnId.isEmpty()) return null;
        String raw = prefs(context).getString(key(turnId), "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject body = new JSONObject(raw);
            Map<String, String> data = new HashMap<>();
            data.put("schema", SelfRunPushEvent.SCHEMA);
            data.put("type", SelfRunPushEvent.TYPE_RESULT_CHANGED);
            data.put("eventId", body.optString("eventId"));
            data.put("installationId", body.optString("installationId"));
            data.put("applicationId", body.optString("applicationId"));
            data.put("taskId", body.optString("taskId"));
            data.put("turnId", body.optString("turnId"));
            data.put("resultDocumentId", body.optString("resultDocumentId"));
            SelfRunPushEvent event = SelfRunPushEvent.parse(data, BuildConfig.APPLICATION_ID);
            if (!turnId.equals(event.turnId)) throw new IllegalArgumentException("turn mismatch");
            return event;
        } catch (Throwable invalid) {
            prefs(context).edit().remove(key(turnId)).commit();
            return null;
        }
    }

    static boolean clearIfSame(Context context, SelfRunPushEvent event) {
        if (context == null || event == null) return false;
        SelfRunPushEvent pending = load(context, event.turnId);
        if (pending == null || !pending.sameLogicalEvent(event)) return false;
        return prefs(context).edit().remove(key(event.turnId)).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String turnId) {
        return PREFIX + turnId;
    }
}
