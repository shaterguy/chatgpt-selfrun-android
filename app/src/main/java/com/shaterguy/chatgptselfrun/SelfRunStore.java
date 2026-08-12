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
    private final SelfRunHistoryStore history;

    SelfRunStore(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences("selfrun", Context.MODE_PRIVATE);
        history = new SelfRunHistoryStore(app);
    }

    void start(String runId, String mode, String projectUrl, String requirement) {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putString("runId", runId)
                .putLong("createdAt", now)
                .putLong("phaseStartedAt", now)
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
                .putString("assistantBaselineKey", "")
                .putString("lastErrorCode", "")
                .putString("lastErrorMessage", "")
                .putInt("turn", 0)
                .putInt("signalRecoveryCount", 0)
                .putBoolean("active", true)
                .putBoolean("paused", false)
                .putBoolean("userStopped", false)
                .apply();
        syncHistory();
    }

    void clear() {
        String defaultProject = defaultProjectUrl();
        prefs.edit().clear().putString("defaultProjectUrl", defaultProject).apply();
    }

    String runId() { return prefs.getString("runId", ""); }
    long createdAt() { return prefs.getLong("createdAt", 0L); }
    long phaseStartedAt() { return prefs.getLong("phaseStartedAt", createdAt()); }
    String mode() { return prefs.getString("mode", MODE_WORK); }
    String projectUrl() { return prefs.getString("projectUrl", ""); }
    String defaultProjectUrl() { return prefs.getString("defaultProjectUrl", ""); }
    String requirement() { return prefs.getString("requirement", ""); }
    String conversationUrl() { return prefs.getString("conversationUrl", ""); }
    String phase() { return prefs.getString("phase", PHASE_IDLE); }
    String status() { return prefs.getString("status", "대기"); }
    String role() { return prefs.getString("role", ""); }
    String pendingModel() { return prefs.getString("pendingModel", ""); }
    String pendingReasoning() { return prefs.getString("pendingReasoning", ""); }
    String lastSignal() { return prefs.getString("lastSignal", ""); }
    String lastAssistantKey() { return prefs.getString("lastAssistantKey", ""); }
    String assistantBaselineKey() { return prefs.getString("assistantBaselineKey", ""); }
    String lastErrorCode() { return prefs.getString("lastErrorCode", ""); }
    String lastErrorMessage() { return prefs.getString("lastErrorMessage", ""); }
    int turn() { return prefs.getInt("turn", 0); }
    int signalRecoveryCount() { return prefs.getInt("signalRecoveryCount", 0); }
    boolean active() { return prefs.getBoolean("active", false); }
    boolean paused() { return prefs.getBoolean("paused", false); }
    boolean userStopped() { return prefs.getBoolean("userStopped", false); }

    void setConversationUrl(String value) { putString("conversationUrl", value, true); }
    void setDefaultProjectUrl(String value) { putString("defaultProjectUrl", value, false); }
    void setPhase(String value) {
        String next = safe(value);
        if (next.equals(phase())) return;
        prefs.edit().putString("phase", next).putLong("phaseStartedAt", System.currentTimeMillis()).apply();
        syncHistory();
    }
    void restartPhaseClock() {
        prefs.edit().putLong("phaseStartedAt", System.currentTimeMillis()).apply();
    }
    void setStatus(String value) { putString("status", value, true); }
    void setRole(String value) { putString("role", value, true); }
    void setPendingModel(String value) { putString("pendingModel", value, true); }
    void setPendingReasoning(String value) { putString("pendingReasoning", value, true); }
    void setLastSignal(String value) { putString("lastSignal", value, true); }
    void setLastAssistantKey(String value) { putString("lastAssistantKey", value, false); }
    void setAssistantBaselineKey(String value) { putString("assistantBaselineKey", value, false); }
    void setLastError(String code, String message) {
        String nextCode = safe(code), nextMessage = safe(message);
        if (nextCode.equals(lastErrorCode()) && nextMessage.equals(lastErrorMessage())) return;
        prefs.edit().putString("lastErrorCode", nextCode).putString("lastErrorMessage", nextMessage).apply();
        syncHistory();
    }
    void clearLastError() { setLastError("", ""); }
    void setTurn(int value) {
        if (value == turn()) return;
        prefs.edit().putInt("turn", value).apply();
        syncHistory();
    }
    void setSignalRecoveryCount(int value) {
        if (value == signalRecoveryCount()) return;
        prefs.edit().putInt("signalRecoveryCount", value).apply();
    }
    void setPaused(boolean value) { putBoolean("paused", value, true); }
    void setActive(boolean value) { putBoolean("active", value, true); }
    void setUserStopped(boolean value) { putBoolean("userStopped", value, true); }

    void syncHistory() { history.sync(this); }

    private void putString(String key, String value, boolean historyRelevant) {
        String next = safe(value);
        if (next.equals(prefs.getString(key, ""))) return;
        prefs.edit().putString(key, next).apply();
        if (historyRelevant) syncHistory();
    }

    private void putBoolean(String key, boolean value, boolean historyRelevant) {
        if (value == prefs.getBoolean(key, false)) return;
        prefs.edit().putBoolean(key, value).apply();
        if (historyRelevant) syncHistory();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
