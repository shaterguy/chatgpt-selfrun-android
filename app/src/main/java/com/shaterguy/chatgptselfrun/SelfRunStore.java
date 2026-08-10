package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

final class SelfRunStore {
    static final String MODE_CHAT = "CHAT";
    static final String MODE_WORK = "WORK";

    static final String PHASE_IDLE = "IDLE";
    static final String PHASE_BOOTSTRAP = "BOOTSTRAP";
    static final String PHASE_WAIT_ASSISTANT = "WAIT_ASSISTANT";
    static final String PHASE_APPLY_PREFS = "APPLY_PREFS";
    static final String PHASE_SEND_CONTINUE = "SEND_CONTINUE";
    static final String PHASE_PAUSED = "PAUSED";
    static final String PHASE_DONE = "DONE";

    private final SharedPreferences prefs;

    SelfRunStore(Context context) {
        prefs = context.getSharedPreferences("selfrun", Context.MODE_PRIVATE);
    }

    void start(String runId, String mode, String projectUrl, String requirement) {
        prefs.edit()
                .putString("runId", runId)
                .putString("mode", mode)
                .putString("projectUrl", projectUrl)
                .putString("requirement", requirement)
                .putString("conversationUrl", "")
                .putString("phase", PHASE_BOOTSTRAP)
                .putString("status", "SelfRun 시작 준비")
                .putString("role", "PLANNER")
                .putString("pendingModel", MODE_WORK.equals(mode) ? "sol" : "")
                .putString("pendingReasoning", MODE_WORK.equals(mode) ? "xhigh" : "")
                .putString("lastSignal", "")
                .putString("lastAssistantKey", "")
                .putInt("turn", 0)
                .putInt("signalRecoveryCount", 0)
                .putBoolean("active", true)
                .putBoolean("paused", false)
                .apply();
    }

    void clear() {
        prefs.edit().clear().apply();
    }

    String runId() { return prefs.getString("runId", ""); }
    String mode() { return prefs.getString("mode", MODE_WORK); }
    String projectUrl() { return prefs.getString("projectUrl", ""); }
    String requirement() { return prefs.getString("requirement", ""); }
    String conversationUrl() { return prefs.getString("conversationUrl", ""); }
    String phase() { return prefs.getString("phase", PHASE_IDLE); }
    String status() { return prefs.getString("status", "대기"); }
    String role() { return prefs.getString("role", ""); }
    String pendingModel() { return prefs.getString("pendingModel", ""); }
    String pendingReasoning() { return prefs.getString("pendingReasoning", ""); }
    String lastSignal() { return prefs.getString("lastSignal", ""); }
    String lastAssistantKey() { return prefs.getString("lastAssistantKey", ""); }
    int turn() { return prefs.getInt("turn", 0); }
    int signalRecoveryCount() { return prefs.getInt("signalRecoveryCount", 0); }
    boolean active() { return prefs.getBoolean("active", false); }
    boolean paused() { return prefs.getBoolean("paused", false); }

    void setConversationUrl(String value) { put("conversationUrl", value); }
    void setPhase(String value) { put("phase", value); }
    void setStatus(String value) { put("status", value); }
    void setRole(String value) { put("role", value); }
    void setPendingModel(String value) { put("pendingModel", value); }
    void setPendingReasoning(String value) { put("pendingReasoning", value); }
    void setLastSignal(String value) { put("lastSignal", value); }
    void setLastAssistantKey(String value) { put("lastAssistantKey", value); }
    void setTurn(int value) { prefs.edit().putInt("turn", value).apply(); }
    void setSignalRecoveryCount(int value) { prefs.edit().putInt("signalRecoveryCount", value).apply(); }
    void setPaused(boolean value) { prefs.edit().putBoolean("paused", value).apply(); }
    void setActive(boolean value) { prefs.edit().putBoolean("active", value).apply(); }

    private void put(String key, String value) {
        prefs.edit().putString(key, value == null ? "" : value).apply();
    }
}
