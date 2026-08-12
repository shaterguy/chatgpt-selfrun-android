package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

final class SelfRunHistoryStore {
    private static final String PREFS = "selfrun_history";
    private static final String KEY_PRIMARY = "runs";
    private static final String KEY_BACKUP = "runsBackup";
    private static final int MAX_RUNS = 100;

    private final SharedPreferences prefs;

    SelfRunHistoryStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized boolean sync(SelfRunStore store) {
        if (store == null || store.runId().isEmpty()) return true;
        JSONArray current = read();
        JSONObject nextSnapshot = snapshot(store);
        JSONObject previousSnapshot = find(current, store.runId());
        if (sameSnapshot(previousSnapshot, nextSnapshot)) return true;

        JSONArray next = new JSONArray();
        next.put(nextSnapshot);
        for (int i = 0; i < current.length() && next.length() < MAX_RUNS; i++) {
            JSONObject item = current.optJSONObject(i);
            if (item == null || store.runId().equals(item.optString("runId"))) continue;
            next.put(item);
        }
        String previous = prefs.getString(KEY_PRIMARY, "[]");
        return prefs.edit().putString(KEY_BACKUP, previous).putString(KEY_PRIMARY, next.toString()).commit();
    }

    synchronized JSONArray read() {
        JSONArray primary = parse(prefs.getString(KEY_PRIMARY, "[]"));
        if (primary != null) return primary;
        JSONArray backup = parse(prefs.getString(KEY_BACKUP, "[]"));
        return backup == null ? new JSONArray() : backup;
    }

    synchronized JSONObject get(String runId) {
        if (runId == null || runId.isEmpty()) return null;
        JSONObject item = find(read(), runId);
        if (item == null) return null;
        try { return new JSONObject(item.toString()); }
        catch (Exception ignored) { return null; }
    }

    private static JSONObject find(JSONArray array, String runId) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && runId.equals(item.optString("runId"))) return item;
        }
        return null;
    }

    private static boolean sameSnapshot(JSONObject previous, JSONObject next) {
        if (previous == null || next == null) return false;
        String[] strings = {"runId", "mode", "projectUrl", "requirement", "conversationUrl", "phase", "status",
                "role", "pendingModel", "pendingReasoning", "lastSignal", "lastErrorCode", "lastErrorMessage"};
        for (String key : strings) {
            if (!previous.optString(key).equals(next.optString(key))) return false;
        }
        if (previous.optLong("createdAt") != next.optLong("createdAt")) return false;
        if (previous.optInt("turn") != next.optInt("turn")) return false;
        if (previous.optBoolean("active") != next.optBoolean("active")) return false;
        if (previous.optBoolean("paused") != next.optBoolean("paused")) return false;
        if (previous.optBoolean("userStopped") != next.optBoolean("userStopped")) return false;
        return previous.optBoolean("terminal") == next.optBoolean("terminal");
    }

    private static JSONObject snapshot(SelfRunStore store) {
        JSONObject item = new JSONObject();
        try {
            item.put("runId", store.runId());
            item.put("createdAt", store.createdAt());
            item.put("updatedAt", System.currentTimeMillis());
            item.put("mode", store.mode());
            item.put("projectUrl", store.projectUrl());
            item.put("requirement", bounded(store.requirement(), 4_000));
            item.put("conversationUrl", store.conversationUrl());
            item.put("phase", store.phase());
            item.put("status", bounded(store.status(), 1_000));
            item.put("role", store.role());
            item.put("pendingModel", store.pendingModel());
            item.put("pendingReasoning", store.pendingReasoning());
            item.put("lastSignal", bounded(store.lastSignal(), 1_000));
            item.put("turn", store.turn());
            item.put("active", store.active());
            item.put("paused", store.paused());
            item.put("userStopped", store.userStopped());
            item.put("lastErrorCode", store.lastErrorCode());
            item.put("lastErrorMessage", bounded(store.lastErrorMessage(), 1_000));
            item.put("terminal", SelfRunStore.PHASE_DONE.equals(store.phase()) || store.userStopped());
        } catch (Exception ignored) {
        }
        return item;
    }

    private static JSONArray parse(String value) {
        try { return new JSONArray(value == null ? "[]" : value); }
        catch (Exception ignored) { return null; }
    }

    private static String bounded(String value, int max) {
        String safe = value == null ? "" : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
