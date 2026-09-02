package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, privacy-safe Run Health observations. No polling or recovery behavior lives here. */
final class SelfRunHealthObservationStore {
    private static final String PREFS = "selfrun_drive_health";
    private static final String KEY_RECORDS = "records";
    private static final String KEY_ENABLED = "enabled";
    private static final int MAX_RUNS = 100;

    private final Context app;
    private final SharedPreferences prefs;

    SelfRunHealthObservationStore(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean enabled() { return prefs.getBoolean(KEY_ENABLED, true); }

    void setEnabled(boolean enabled) {
        try { prefs.edit().putBoolean(KEY_ENABLED, enabled).commit(); }
        catch (Throwable ignored) { }
    }

    SelfRunHealthSnapshot updateFromStore(SelfRunStore store) {
        if (!enabled() || store == null || store.runId().isEmpty()) return null;
        try {
            JSONObject record = record(store.runId());
            observeErrorState(record, store.lastErrorCode(), store.phase());
            SelfRunHealthInput input = SelfRunHealthInput.fromStore(store, record);
            suppressStaleError(input, record);
            SelfRunHealthSnapshot next = SelfRunHealthEvaluator.evaluate(input, System.currentTimeMillis());
            recordSnapshot(record, next);
            save(record);
            return snapshot(record, "currentHealth", next);
        } catch (Throwable ignored) {
            return SelfRunHealthSnapshot.fallback(System.currentTimeMillis());
        }
    }

    SelfRunHealthSnapshot currentFor(JSONObject historyItem) {
        if (!enabled() || historyItem == null) return null;
        String runId = historyItem.optString("runId");
        if (runId.isEmpty()) return null;
        try {
            JSONObject record = record(runId);
            SelfRunHealthInput input = SelfRunHealthInput.fromHistory(historyItem, record);
            suppressStaleError(input, record);
            SelfRunHealthSnapshot next = SelfRunHealthEvaluator.evaluate(input, System.currentTimeMillis());
            recordSnapshot(record, next);
            save(record);
            return snapshot(record, "currentHealth", next);
        } catch (Throwable ignored) {
            JSONObject stored = historyItem.optJSONObject("health");
            SelfRunHealthSnapshot prior = SelfRunHealthSnapshot.fromJson(stored);
            return prior == null ? SelfRunHealthSnapshot.fallback(System.currentTimeMillis()) : prior;
        }
    }

    void observeRunLog(SelfRunStore store, String event, String safeDetail, long observedAt) {
        if (!enabled() || store == null || store.runId().isEmpty()) return;
        if (!("DOM_RESULT".equals(event) || "TARGET_DRIFT".equals(event))) return;
        try {
            String reason = field(safeDetail, "reason");
            String phase = field(safeDetail, "phase");
            if (reason.isEmpty() && "TARGET_DRIFT".equals(event)) reason = "route_mismatch";
            if (!allowedReason(reason)) return;
            JSONObject record = record(store.runId());
            record.put("webReason", reason);
            record.put("webPhase", safePhase(phase));
            record.put("webObservedAt", Math.max(0L, observedAt));
            record.put("updatedAt", System.currentTimeMillis());
            save(record);
            updateFromStore(store);
        } catch (Throwable ignored) { }
    }

    void observeNetwork(boolean known, boolean validated) {
        observeNetwork(known, validated, System.currentTimeMillis());
    }

    void observeNetwork(boolean known, boolean validated, long observedAt) {
        if (!enabled()) return;
        try {
            String runId = currentRunId();
            if (runId.isEmpty()) return;
            JSONObject record = record(runId);
            record.put("networkKnown", known);
            record.put("networkValidated", known && validated);
            record.put("networkObservedAt", Math.max(0L, observedAt));
            record.put("updatedAt", System.currentTimeMillis());
            save(record);
            refreshCurrentRun(runId);
        } catch (Throwable ignored) { }
    }

    void observeProcessExit(String runId, String category, String reason, long observedAt) {
        if (!enabled() || runId == null || runId.isEmpty() || category == null || category.isEmpty()) return;
        try {
            JSONObject record = record(runId);
            record.put("processCategory", safeProcessCategory(category));
            record.put("processReason", safeProcessReason(reason));
            record.put("processObservedAt", Math.max(0L, observedAt));
            record.put("lastProcessedExitTimestamp", Math.max(record.optLong("lastProcessedExitTimestamp"), observedAt));
            record.put("updatedAt", System.currentTimeMillis());
            save(record);
            refreshCurrentRun(runId);
        } catch (Throwable ignored) { }
    }

    void markProcessExitSeen(String runId, long observedAt) {
        if (!enabled() || runId == null || runId.isEmpty() || observedAt <= 0L) return;
        try {
            JSONObject record = record(runId);
            record.put("lastProcessedExitTimestamp", Math.max(record.optLong("lastProcessedExitTimestamp"), observedAt));
            record.put("updatedAt", System.currentTimeMillis());
            save(record);
        } catch (Throwable ignored) { }
    }

    long lastProcessedExitTimestamp(String runId) {
        try { return record(runId).optLong("lastProcessedExitTimestamp"); }
        catch (Throwable ignored) { return 0L; }
    }

    private void refreshCurrentRun(String runId) {
        try {
            SelfRunStore current = new SelfRunStore(app);
            if (runId.equals(current.runId())) updateFromStore(current);
        } catch (Throwable ignored) { }
    }

    private static void observeErrorState(JSONObject record, String errorCode, String phase) throws Exception {
        String safeCode = safeErrorCode(errorCode);
        boolean initialized = record.optBoolean("errorObservationInitialized", false);
        if (!initialized) {
            record.put("errorObservationInitialized", true);
            record.put("lastErrorCodeSeen", safeCode);
            record.put("lastErrorPhase", "");
            return;
        }
        String previous = record.optString("lastErrorCodeSeen");
        if (safeCode.equals(previous)) return;
        record.put("lastErrorCodeSeen", safeCode);
        record.put("lastErrorPhase", safeCode.isEmpty() ? "" : safeRuntimePhase(phase));
    }

    static void suppressStaleError(SelfRunHealthInput input, JSONObject record) {
        if (input == null || input.lastErrorCode == null || input.lastErrorCode.isEmpty()) return;
        if (record == null) { input.lastErrorCode = ""; return; }
        String code = safeErrorCode(input.lastErrorCode);
        String seen = record.optString("lastErrorCodeSeen");
        String errorPhase = record.optString("lastErrorPhase");
        if (code.isEmpty() || !code.equals(seen) || errorPhase.isEmpty() || !errorPhase.equals(input.phase)) {
            input.lastErrorCode = "";
        }
    }

    private void recordSnapshot(JSONObject record, SelfRunHealthSnapshot candidate) throws Exception {
        SelfRunHealthSnapshot prior = SelfRunHealthSnapshot.fromJson(record.optJSONObject("currentHealth"));
        SelfRunHealthSnapshot stable = prior != null && candidate.sameState(prior)
                ? candidate.withObservedAt(prior.observedAt) : candidate;
        record.put("currentHealth", stable.toJson());
        if (stable.important() && (prior == null || !stable.sameState(prior))) {
            record.put("lastImportantHealth", stable.toJson());
        }
        if (stable.terminal()) record.put("finalHealth", stable.toJson());
        record.put("updatedAt", System.currentTimeMillis());
    }

    private SelfRunHealthSnapshot snapshot(JSONObject record, String key, SelfRunHealthSnapshot fallback) {
        SelfRunHealthSnapshot value = SelfRunHealthSnapshot.fromJson(record.optJSONObject(key));
        return value == null ? fallback : value;
    }

    private JSONObject record(String runId) {
        String safeRunId = safeRunId(runId);
        if (safeRunId.isEmpty()) return new JSONObject();
        JSONArray array = read();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && safeRunId.equals(item.optString("runId"))) {
                try { return new JSONObject(item.toString()); }
                catch (Exception ignored) { return new JSONObject(); }
            }
        }
        JSONObject created = new JSONObject();
        try { created.put("runId", safeRunId); }
        catch (Exception ignored) { }
        return created;
    }

    private synchronized void save(JSONObject record) {
        if (record == null) return;
        String runId = safeRunId(record.optString("runId"));
        if (runId.isEmpty()) return;
        try {
            record.put("runId", runId);
            JSONArray current = read();
            JSONArray next = new JSONArray();
            next.put(record);
            for (int i = 0; i < current.length() && next.length() < MAX_RUNS; i++) {
                JSONObject item = current.optJSONObject(i);
                if (item == null || runId.equals(item.optString("runId"))) continue;
                next.put(item);
            }
            prefs.edit().putString(KEY_RECORDS, next.toString()).commit();
        } catch (Throwable ignored) { }
    }

    private synchronized JSONArray read() {
        try { return new JSONArray(prefs.getString(KEY_RECORDS, "[]")); }
        catch (Throwable ignored) { return new JSONArray(); }
    }

    private String currentRunId() {
        try {
            return safeRunId(app.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE)
                    .getString("runId", ""));
        } catch (Throwable ignored) { return ""; }
    }

    static String field(String detail, String key) {
        if (detail == null || key == null || key.isEmpty()) return "";
        String prefix = key + "=";
        for (String part : detail.split(";")) {
            if (part.startsWith(prefix)) return part.substring(prefix.length());
        }
        return "";
    }

    static boolean allowedReason(String reason) {
        return switch (reason == null ? "" : reason) {
            case "composer_clearing", "composer_inputting", "submission_pending", "send_disabled",
                    "stop_visible", "control_unknown", "script_error", "request_profile_rejected",
                    "submission_failed", "model_wait", "reasoning_wait", "composer_wait", "send_wait",
                    "input_reflection_wait", "input_wait", "ui_wait", "state_wait", "evaluate_javascript",
                    "host_mismatch", "project_mismatch", "conversation_mismatch", "general_target_mismatch",
                    "route_mismatch" -> true;
            default -> false;
        };
    }

    private static String safePhase(String value) {
        return switch (value == null ? "" : value) {
            case "bootstrap_send", "wait_turn_completion", "apply_model", "apply_reasoning", "send_continue", "other" -> value;
            default -> "";
        };
    }

    private static String safeRuntimePhase(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.matches("[A-Z0-9_]{1,64}") ? value : "";
    }

    private static String safeErrorCode(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.matches("[A-Z0-9_]{1,64}") ? value : "";
    }

    private static String safeProcessCategory(String value) {
        return switch (value == null ? "" : value) {
            case "APP_CRASH", "APP_ANR", "LOW_MEMORY", "EXCESSIVE_RESOURCE", "PROCESS_EXIT_GENERIC" -> value;
            default -> "PROCESS_EXIT_GENERIC";
        };
    }

    private static String safeProcessReason(String value) {
        return switch (value == null ? "" : value) {
            case "crash", "native_crash", "anr", "low_memory", "excessive_resource", "dependency_died",
                    "package_updated", "package_state_change", "permission_change", "user_requested_or_update",
                    "initialization_failure", "freezer", "signaled", "other", "unknown" -> value;
            default -> "unknown";
        };
    }

    private static String safeRunId(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.matches("[A-Za-z0-9._:-]{1,100}") ? value : "";
    }
}
