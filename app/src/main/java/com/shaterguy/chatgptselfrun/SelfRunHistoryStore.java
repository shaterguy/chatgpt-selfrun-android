package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Durable user-visible history projection for active SelfRun 3 tasks. */
final class SelfRunHistoryStore {
    private static final String PREFS = "selfrun_drive_history";
    private static final String KEY_PRIMARY = "runs";
    private static final String KEY_BACKUP = "runsBackup";
    private static final int MAX_RUNS = 100;

    private final Context app;
    private final SharedPreferences prefs;
    private final SelfRunHealthObservationStore health;

    SelfRunHistoryStore(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        health = new SelfRunHealthObservationStore(app);
    }

    synchronized boolean sync(SelfRunStore store) {
        if (store == null || store.runId().isEmpty()) return true;
        JSONArray current = read();
        JSONArray next = new JSONArray();
        next.put(snapshot(store));
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
        JSONArray array = read();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && runId.equals(item.optString("runId"))) {
                try { return new JSONObject(item.toString()); }
                catch (Exception ignored) { return null; }
            }
        }
        return null;
    }

    /** Retained only for old UI callers; V3 has no rollover state. */
    synchronized boolean rolloverProgressObserved(String runId) { return false; }

    private JSONObject snapshot(SelfRunStore store) {
        JSONObject item = new JSONObject();
        try {
            item.put("runId", store.runId());
            item.put("createdAt", store.createdAt());
            item.put("updatedAt", System.currentTimeMillis());
            item.put("phaseStartedAt", store.phaseStartedAt());
            item.put("mode", store.mode());
            item.put("projectUrl", store.projectUrl());
            item.put("requirement", bounded(store.requirement(), 4_000));
            item.put("conversationUrl", store.conversationUrl());
            item.put("driveAccountId", store.runDriveAccountId());
            item.put("driveRunsBaseFolderId", store.driveRunsBaseFolderId());
            item.put("runBaseFolderId", store.runBaseFolderId());
            item.put("jobFolderId", store.jobFolderId());
            item.put("turnDocumentId", store.turnDocumentId());
            item.put("turnDocumentUrl", store.turnDocumentUrl());
            item.put("phase", store.phase());
            item.put("status", bounded(store.status(), 1_000));
            item.put("pendingModel", store.pendingModel());
            item.put("pendingReasoning", store.pendingReasoning());
            item.put("turn", store.turn());
            item.put("active", store.active());
            item.put("paused", store.paused());
            item.put("userStopped", store.userStopped());
            item.put("lastErrorCode", store.lastErrorCode());
            item.put("lastErrorMessage", bounded(store.lastErrorMessage(), 1_000));
            item.put("terminal", SelfRunStore.PHASE_DONE.equals(store.phase()) || store.userStopped());
            SelfRunHealthSnapshot healthSnapshot = health.updateFromStore(store);
            if (healthSnapshot != null) item.put("health", healthSnapshot.toJson());
            BootstrapRunStateStore.appendHistory(app, store.runId(), item);
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
