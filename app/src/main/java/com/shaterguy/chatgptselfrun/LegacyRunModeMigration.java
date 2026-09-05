package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** One-time reader for retired mode records. It cannot create or advance a staged run. */
final class LegacyRunModeMigration {
    private static SharedPreferences migration;
    private static SharedPreferences legacy;
    private static SharedPreferences runState;
    private LegacyRunModeMigration() {}

    static final class Endpoint {
        final String mode, model, reasoning;
        Endpoint(String mode, String model, String reasoning) {
            this.mode = mode;
            this.model = model;
            this.reasoning = reasoning;
        }
        boolean valid() {
            return SelfRunStore.MODE_WORK.equals(mode)
                    ? ProfileRegistry.resolveWork(model, reasoning) != null
                    : SelfRunStore.MODE_CHAT.equals(mode) && model.isEmpty()
                    && ProfileRegistry.resolveChat(reasoning) != null;
        }
        JSONObject json() {
            JSONObject out = new JSONObject();
            try { out.put("mode", mode).put("model", model).put("reasoning", reasoning); }
            catch (Exception error) { throw new IllegalStateException(error); }
            return out;
        }
    }

    private static synchronized void initialize(Context context) {
        ProfileRegistry.initialize(context);
        migration = context.getApplicationContext().getSharedPreferences("selfrun_drive_mode_migration", Context.MODE_PRIVATE);
        legacy = context.getApplicationContext().getSharedPreferences("selfrun_drive_hybrid_profiles", Context.MODE_PRIVATE);
        runState = context.getApplicationContext().getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE);
    }

    static Endpoint endpoint(JSONObject selection) {
        if (selection == null) return null;
        String stage = selection.optString("stage", "");
        if (!"BOOTSTRAP".equals(stage) && !"CONTINUATION".equals(stage)) return null;
        JSONObject value = selection.optJSONObject("CONTINUATION".equals(stage) ? "continuation" : "bootstrap");
        return readEndpoint(value);
    }

    private static Endpoint readEndpoint(JSONObject value) {
        if (value == null) return null;
        Endpoint endpoint = new Endpoint(value.optString("mode", ""), value.optString("model", ""),
                value.optString("reasoning", ""));
        return endpoint.valid() ? endpoint : null;
    }

    private static Endpoint freeze(String runId) {
        if (!SelfRunProtocolRules.validRunId(runId)) return null;
        try {
            String frozen = migration.getString("endpoint:" + runId, "");
            if (!frozen.isEmpty()) return readEndpoint(new JSONObject(frozen));
            JSONObject selection = new JSONObject(legacy.getString("run:" + runId, ""));
            if (!runId.equals(selection.optString("runId", ""))) return null;
            Endpoint endpoint = endpoint(selection);
            if (endpoint == null) return null;
            if (!migration.edit().putString("endpoint:" + runId, endpoint.json().toString())
                    .putBoolean("notice:" + runId, true).commit()) return null;
            return endpoint;
        } catch (Exception invalid) { return null; }
    }

    static void migrateCurrent(Context context, SharedPreferences state) {
        synchronized (SelfRunStore.RUN_STATE_LOCK) {
        initialize(context);
        if (!"HYBRID".equals(state.getString("mode", ""))) return;
        String runId = state.getString("runId", "");
        Endpoint endpoint = freeze(runId);
        if (endpoint == null) {
            // Preserve phase, paused state and every payload; the service refuses unknown modes.
            if (!state.edit().putString("lastErrorCode", "LEGACY_MODE_UNRESOLVED")
                    .putString("lastErrorMessage", "이전 실행 프로필을 확인할 수 없습니다.").commit())
                throw new IllegalStateException("legacy mode error persistence failed");
            return;
        }
        if (SelfRunStore.MODE_CHAT.equals(endpoint.mode)
                && !ChatReasoningPreferenceStore.save(context, runId, endpoint.reasoning, endpoint.reasoning))
            throw new IllegalStateException("legacy Chat profile persistence failed");
        SharedPreferences.Editor editor = state.edit().putString("mode", endpoint.mode)
                .putString("pendingModel", endpoint.model).putString("pendingReasoning", endpoint.reasoning);
        if ("LEGACY_MODE_UNRESOLVED".equals(state.getString("lastErrorCode", "")))
            editor.putString("lastErrorCode", "").putString("lastErrorMessage", "");
        if (!editor.commit()) throw new IllegalStateException("legacy mode persistence failed");
        }
    }

    static synchronized JSONObject normalizedSnapshot(Context context, JSONObject snapshot) {
        if (snapshot == null || !"HYBRID".equals(snapshot.optString("mode"))) return snapshot;
        initialize(context);
        String runId = snapshot.optString("runId", "");
        Endpoint endpoint = freeze(runId);
        if (endpoint == null) throw new IllegalStateException("legacy mode unresolved");
        if (SelfRunStore.MODE_CHAT.equals(endpoint.mode)
                && !ChatReasoningPreferenceStore.save(context, runId, endpoint.reasoning, endpoint.reasoning))
            throw new IllegalStateException("legacy Chat profile persistence failed");
        try {
            return new JSONObject(snapshot.toString()).put("mode", endpoint.mode)
                    .put("pendingModel", endpoint.model).put("pendingReasoning", endpoint.reasoning);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    static synchronized Endpoint pendingEndpoint(String runId) {
        if (migration == null || runState == null || runState.getBoolean("legacyModeNoticeConsumed:" + runId, false)
                || !migration.getBoolean("notice:" + runId, false)) return null;
        try { return readEndpoint(new JSONObject(migration.getString("endpoint:" + runId, ""))); }
        catch (Exception invalid) { return null; }
    }

    static String appendNotice(String runId, String prompt) {
        Endpoint endpoint = pendingEndpoint(runId);
        if (endpoint == null) return prompt;
        return prompt + "\n\n[실행 모드 갱신] 앱에서 하이브리드 모드가 폐기되었습니다. 이 실행은 이제 MODE="
                + endpoint.mode + "입니다. 기존 작업·대화·인계는 유지합니다. 이후 HANDOFF와 SelfRun 신호에는 이 일반 모드 계약을 적용하세요."
                + (SelfRunStore.MODE_WORK.equals(endpoint.mode)
                ? " 현재 조합은 MODEL=" + endpoint.model + " REASONING=" + endpoint.reasoning
                    + "이며, 이후 TURN_COMPLETED에는 유효한 MODEL/REASONING을 포함하세요."
                : " 이후 TURN_COMPLETED에는 MODEL/REASONING을 포함하지 마세요.");
    }

    static String prepareContinuation(String runId, String action) {
        Endpoint endpoint = pendingEndpoint(runId);
        if (endpoint == null) return action;
        String profile = RequestProfileScript.beginTarget(endpoint.mode.toLowerCase(java.util.Locale.ROOT), runId);
        if (SelfRunStore.MODE_WORK.equals(endpoint.mode))
            profile += RequestProfileScript.setWorkModel(endpoint.model) + RequestProfileScript.setWorkReasoning(endpoint.reasoning);
        else profile += RequestProfileScript.setChatProfiles(endpoint.reasoning, endpoint.reasoning);
        return "(()=>{try{" + profile + "}catch(_){return JSON.stringify({status:'LEGACY_MODE_UNRESOLVED',detail:'saved profile unavailable'});}return (" + action + ");})()";
    }

}
