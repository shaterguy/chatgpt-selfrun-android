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

    static final String PAUSE_CAUSE_UI = "UI_PAUSE";
    static final String PAUSE_CAUSE_PROTOCOL = "PROTOCOL_SIGNAL";

    private static final String PHASE_BOOTSTRAP_MODEL = "BOOTSTRAP_MODEL";
    private static final String PHASE_BOOTSTRAP_REASONING = "BOOTSTRAP_REASONING";
    private static final String PHASE_BOOTSTRAP_SEND = "BOOTSTRAP_SEND";
    private static final String PHASE_APPLY_REASONING = "APPLY_REASONING";

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
                .putString("pauseResumePhase", "")
                .putString("pauseCause", "")
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
    String pauseResumePhase() { return prefs.getString("pauseResumePhase", ""); }
    String pauseCause() { return prefs.getString("pauseCause", ""); }
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
        String requested = safe(value);
        String current = phase();
        boolean resumeTransition = PHASE_PAUSED.equals(current) && !paused()
                && (PHASE_BOOTSTRAP.equals(requested) || PHASE_SEND_CONTINUE.equals(requested));
        String next = resumeTransition
                ? resolvePausedResumePhase(pauseResumePhase(), !conversationUrl().isEmpty())
                : requested;
        if (next.equals(current)) {
            if (resumeTransition) clearPauseResumeMetadata();
            return;
        }
        SharedPreferences.Editor editor = prefs.edit()
                .putString("phase", next)
                .putLong("phaseStartedAt", System.currentTimeMillis());
        if (resumeTransition) {
            editor.putString("pauseResumePhase", "").putString("pauseCause", "");
        }
        editor.apply();
        syncHistory();
    }
    void restartPhaseClock() {
        prefs.edit().putLong("phaseStartedAt", System.currentTimeMillis()).apply();
    }
    void setStatus(String value) { putString("status", value, true); }
    void setRole(String value) { putString("role", value, true); }
    void setPendingModel(String value) { putString("pendingModel", value, true); }
    void setPendingReasoning(String value) { putString("pendingReasoning", value, true); }
    void setLastSignal(String value) {
        String next = signalValueAfterResume(lastSignal(), safe(value), phase(), pauseCause());
        putString("lastSignal", next, true);
    }
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
    void setPaused(boolean value) {
        if (value == paused()) return;
        if (value) {
            String resumePhase = capturePauseResumePhase(phase(), lastSignal(), runId(), lastErrorCode());
            String cause = resumePhase.isEmpty() ? ""
                    : isProtocolPauseSignal(lastSignal(), runId()) ? PAUSE_CAUSE_PROTOCOL : PAUSE_CAUSE_UI;
            prefs.edit()
                    .putBoolean("paused", true)
                    .putString("pauseResumePhase", resumePhase)
                    .putString("pauseCause", cause)
                    .apply();
        } else {
            prefs.edit().putBoolean("paused", false).apply();
        }
        syncHistory();
    }
    void setActive(boolean value) { putBoolean("active", value, true); }
    void setUserStopped(boolean value) { putBoolean("userStopped", value, true); }

    void syncHistory() { history.sync(this); }

    static String capturePauseResumePhase(String currentPhase, String lastSignal,
            String runId, String lastErrorCode) {
        if (!safe(lastErrorCode).isEmpty()) return "";
        if (isProtocolPauseSignal(lastSignal, runId)) return PHASE_SEND_CONTINUE;
        return isResumablePhase(currentPhase) ? safe(currentPhase) : "";
    }

    static String resolvePausedResumePhase(String storedPhase, boolean hasConversation) {
        String stored = safe(storedPhase);
        if (hasConversation) {
            if (PHASE_WAIT_ASSISTANT.equals(stored)
                    || PHASE_APPLY_PREFS.equals(stored)
                    || PHASE_APPLY_REASONING.equals(stored)
                    || PHASE_SEND_CONTINUE.equals(stored)) {
                return stored;
            }
            return PHASE_SEND_CONTINUE;
        }
        if (PHASE_BOOTSTRAP.equals(stored)
                || PHASE_BOOTSTRAP_MODEL.equals(stored)
                || PHASE_BOOTSTRAP_REASONING.equals(stored)
                || PHASE_BOOTSTRAP_SEND.equals(stored)) {
            return stored;
        }
        return PHASE_BOOTSTRAP;
    }

    static String signalValueAfterResume(String currentSignal, String requestedSignal,
            String currentPhase, String pauseCause) {
        String current = safe(currentSignal);
        String requested = safe(requestedSignal);
        if ("USER_RESUME".equals(requested)
                && PHASE_PAUSED.equals(safe(currentPhase))
                && PAUSE_CAUSE_UI.equals(safe(pauseCause))
                && "RECOVERY".equals(current)) {
            return current;
        }
        return requested;
    }

    static boolean isProtocolPauseSignal(String signal, String runId) {
        String text = safe(signal);
        String id = safe(runId);
        if (id.isEmpty()) return false;
        return text.startsWith("[SELF_RUN_USER_ACTION_REQUIRED " + id)
                || text.startsWith("[SELF_RUN_PAUSE " + id);
    }

    private static boolean isResumablePhase(String phase) {
        String value = safe(phase);
        return PHASE_BOOTSTRAP.equals(value)
                || PHASE_BOOTSTRAP_MODEL.equals(value)
                || PHASE_BOOTSTRAP_REASONING.equals(value)
                || PHASE_BOOTSTRAP_SEND.equals(value)
                || PHASE_WAIT_ASSISTANT.equals(value)
                || PHASE_APPLY_PREFS.equals(value)
                || PHASE_APPLY_REASONING.equals(value)
                || PHASE_SEND_CONTINUE.equals(value);
    }

    private void clearPauseResumeMetadata() {
        if (pauseResumePhase().isEmpty() && pauseCause().isEmpty()) return;
        prefs.edit().putString("pauseResumePhase", "").putString("pauseCause", "").apply();
    }

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
