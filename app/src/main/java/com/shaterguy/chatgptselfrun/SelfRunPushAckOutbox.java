package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Durable exactly-once-by-key ACK outbox. Network delivery is retried separately by WorkManager. */
final class SelfRunPushAckOutbox {
    enum AckState { RECEIVED, PROCESSED }

    static final String PREFS = "selfrun_push_ack_outbox";
    private static final String PREFIX = "ack:";

    static final class Entry {
        final String key;
        final JSONObject body;
        Entry(String key, JSONObject body) { this.key = key; this.body = body; }
    }

    private SelfRunPushAckOutbox() { }

    static String enqueue(Context context, SelfRunPushEvent event, AckState state) {
        if (context == null || event == null || state == null) throw new IllegalArgumentException("ACK identity required");
        String key = PREFIX + event.eventId + ":" + state.name();
        JSONObject body;
        try {
            body = new JSONObject()
                    .put("schema", "selfrun-push-ack-v1")
                    .put("eventId", event.eventId)
                    .put("installationId", event.installationId)
                    .put("applicationId", event.applicationId)
                    .put("taskId", event.taskId)
                    .put("turnId", event.turnId)
                    .put("resultDocumentId", event.resultDocumentId)
                    .put("state", state.name());
        } catch (JSONException impossible) {
            throw new IllegalStateException("failed to encode ACK outbox entry", impossible);
        }
        SharedPreferences prefs = prefs(context);
        String existing = prefs.getString(key, "");
        if (!body.toString().equals(existing)) {
            if (!prefs.edit().putString(key, body.toString()).commit()) {
                throw new IllegalStateException("ACK outbox persist failed");
            }
        }
        SelfRunPushAckWorker.schedule(context);
        return key;
    }

    static List<Entry> pending(Context context) {
        List<Entry> result = new ArrayList<>();
        for (Map.Entry<String, ?> raw : prefs(context).getAll().entrySet()) {
            if (!raw.getKey().startsWith(PREFIX) || !(raw.getValue() instanceof String)) continue;
            try {
                JSONObject body = new JSONObject((String) raw.getValue());
                result.add(new Entry(raw.getKey(), body));
            } catch (Throwable ignored) { }
        }
        result.sort(Comparator.comparing(entry -> entry.key));
        return result;
    }

    static int pendingCount(Context context) { return pending(context).size(); }

    static void markDelivered(Context context, String key) {
        if (key == null || !key.startsWith(PREFIX)) return;
        prefs(context).edit().remove(key).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
