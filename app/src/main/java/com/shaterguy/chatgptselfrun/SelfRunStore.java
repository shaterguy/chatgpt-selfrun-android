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

    static final class PersistenceMetrics {
        final long stateWriteTransactions;
        final long duplicateStateWritesSkipped;
        final SelfRunHistoryStore.Metrics history;

        PersistenceMetrics(long stateWriteTransactions, long duplicateStateWritesSkipped,
                SelfRunHistoryStore.Metrics history) {
            this.stateWriteTransactions = stateWriteTransactions;
            this.duplicateStateWritesSkipped = duplicateStateWritesSkipped;
            this.history = history;
        }
    }

    private final SharedPreferences prefs;
    private final SelfRunHistoryStore history;
    private long stateWriteTransactions;
    private long duplicateStateWritesSkipped;

    SelfRunStore(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences("selfrun", Context.MODE_PRIVATE);
        history = new SelfRunHistoryStore(app);
    }

    void start(String runId, String mode, String projectUrl, String requirement) {
        long now = System.currentTimeMillis();
        applyEditor(prefs.edit()
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
                .putBoolean("userStopped", false));
        syncHistoryCritical();
    }

    void clear() {
        String defaultProject = defaultProjectUrl();
        applyEditor(prefs.edit().clear().putString("defaultProjectUrl", defaultProject));
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

    PersistenceMetrics persistenceMetrics() {
        return new PersistenceMetrics(stateWriteTransactions, duplicateStateWritesSkipped, history.metrics());
    }

    void setConversationUrl(String value) { putString("conversationUrl", value, true, true); }
    void setDefaultProjectUrl(String value) { putString("defaultProjectUrl", value, false, false); }

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
            markNoop();
            return;
        }
        SharedPreferences.Editor editor = prefs.edit()
                .putString("phase", next)
                .putLong("phaseStartedAt", System.currentTimeMillis());
        if (resumeTransition) {
            editor.putString("pauseResumePhase", "").putString("pauseCause", "");
        }
        applyEditor(editor);
        syncHistory();
    }

    void setPhaseAndStatus(String value, String statusValue) {
        String requested = safe(value);
        String current = phase();
        boolean resumeTransition = PHASE_PAUSED.equals(current) && !paused()
                && (PHASE_BOOTSTRAP.equals(requested) || PHASE_SEND_CONTINUE.equals(requested));
        String next = resumeTransition
                ? resolvePausedResumePhase(pauseResumePhase(), !conversationUrl().isEmpty())
                : requested;
        String nextStatus = safe(statusValue);
        boolean phaseChanged = !next.equals(current);
        boolean statusChanged = !nextStatus.equals(status());
        boolean clearPause = resumeTransition && (!pauseResumePhase().isEmpty() || !pauseCause().isEmpty());
        if (!phaseChanged && !statusChanged && !clearPause) {
            markNoop();
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (phaseChanged) {
            editor.putString("phase", next).putLong("phaseStartedAt", System.currentTimeMillis());
        }
        if (statusChanged) editor.putString("status", nextStatus);
        if (clearPause) editor.putString("pauseResumePhase", "").putString("pauseCause", "");
        applyEditor(editor);
        syncHistory();
    }

    void restartPhaseClock() {
        applyEditor(prefs.edit().putLong("phaseStartedAt", System.currentTimeMillis()));
    }

    void setStatus(String value) { putString("status", value, true, false); }
    void setRole(String value) { putString("role", value, true, false); }
    void setPendingModel(String value) { putString("pendingModel", value, true, false); }
    void setPendingReasoning(String value) { putString("pendingReasoning", value, true, false); }

    void setLastSignal(String value) {
        String next = signalValueAfterResume(lastSignal(), safe(value), phase(), pauseCause());
        putString("lastSignal", next, true, false);
    }

    void setLastAssistantKey(String value) { putString("lastAssistantKey", value, false, false); }
    void setAssistantBaselineKey(String value) { putString("assistantBaselineKey", value, false, false); }

    void setLastError(String code, String message) {
        String nextCode = safe(code), nextMessage = safe(message);
        if (nextCode.equals(lastErrorCode()) && nextMessage.equals(lastErrorMessage())) {
            markNoop();
            return;
        }
        applyEditor(prefs.edit().putString("lastErrorCode", nextCode).putString("lastErrorMessage", nextMessage));
        syncHistory();
    }

    void clearLastError() { setLastError("", ""); }

    void setTurn(int value) {
        if (value == turn()) {
            markNoop();
            return;
        }
        applyEditor(prefs.edit().putInt("turn", value));
        syncHistory();
    }

    void setSignalRecoveryCount(int value) {
        if (value == signalRecoveryCount()) {
            markNoop();
            return;
        }
        applyEditor(prefs.edit().putInt("signalRecoveryCount", value));
    }

    void setPaused(boolean value) {
        if (value == paused()) {
            markNoop();
            return;
        }
        if (value) {
            String resumePhase = capturePauseResumePhase(phase(), lastSignal(), runId(), lastErrorCode());
            String cause = resumePhase.isEmpty() ? ""
                    : isProtocolPauseSignal(lastSignal(), runId()) ? PAUSE_CAUSE_PROTOCOL : PAUSE_CAUSE_UI;
            applyEditor(prefs.edit()
                    .putBoolean("paused", true)
                    .putString("pauseResumePhase", resumePhase)
                    .putString("pauseCause", cause));
        } else {
            applyEditor(prefs.edit().putBoolean("paused", false));
        }
        syncHistory();
    }

    void enterPausedState(String statusValue) {
        String currentPhase = phase();
        String nextStatus = safe(statusValue);
        if (paused() && PHASE_PAUSED.equals(currentPhase) && nextStatus.equals(status())) {
            markNoop();
            return;
        }
        String resumePhase = capturePauseResumePhase(currentPhase, lastSignal(), runId(), lastErrorCode());
        String cause = resumePhase.isEmpty() ? ""
                : isProtocolPauseSignal(lastSignal(), runId()) ? PAUSE_CAUSE_PROTOCOL : PAUSE_CAUSE_UI;
        applyEditor(prefs.edit()
                .putBoolean("paused", true)
                .putString("pauseResumePhase", resumePhase)
                .putString("pauseCause", cause)
                .putString("phase", PHASE_PAUSED)
                .putLong("phaseStartedAt", System.currentTimeMillis())
                .putString("status", nextStatus));
        syncHistoryCritical();
    }

    void enterErrorPausedState(String code, String message, String statusValue) {
        String currentPhase = phase();
        String nextCode = safe(code);
        String nextMessage = safe(message);
        String resumePhase = capturePauseResumePhase(currentPhase, lastSignal(), runId(), nextCode);
        applyEditor(prefs.edit()
                .putString("lastErrorCode", nextCode)
                .putString("lastErrorMessage", nextMessage)
                .putBoolean("paused", true)
                .putString("pauseResumePhase", resumePhase)
                .putString("pauseCause", "")
                .putString("phase", PHASE_PAUSED)
                .putLong("phaseStartedAt", System.currentTimeMillis())
                .putString("status", safe(statusValue)));
        syncHistoryCritical();
    }

    void resumeState(String requestedPhase, String statusValue) {
        String currentPhase = phase();
        String nextPhase = PHASE_PAUSED.equals(currentPhase)
                ? resolvePausedResumePhase(pauseResumePhase(), !conversationUrl().isEmpty())
                : safe(requestedPhase);
        String nextSignal = signalValueAfterResume(lastSignal(), "USER_RESUME", currentPhase, pauseCause());
        applyEditor(prefs.edit()
                .putBoolean("paused", false)
                .putBoolean("active", true)
                .putBoolean("userStopped", false)
                .putString("lastErrorCode", "")
                .putString("lastErrorMessage", "")
                .putString("lastSignal", nextSignal)
                .putString("phase", nextPhase)
                .putLong("phaseStartedAt", System.currentTimeMillis())
                .putString("status", safe(statusValue))
                .putString("pauseResumePhase", "")
                .putString("pauseCause", ""));
        syncHistoryCritical();
    }

    void complete(String statusValue) {
        applyEditor(prefs.edit()
                .putString("phase", PHASE_DONE)
                .putLong("phaseStartedAt", System.currentTimeMillis())
                .putString("status", safe(statusValue))
                .putBoolean("active", false)
                .putBoolean("paused", false)
                .putString("lastErrorCode", "")
                .putString("lastErrorMessage", "")
                .putString("pauseResumePhase", "")
                .putString("pauseCause", ""));
        syncHistoryCritical();
    }

    void stopByUser() {
        applyEditor(prefs.edit()
                .putBoolean("active", false)
                .putBoolean("paused", false)
                .putBoolean("userStopped", true)
                .putString("phase", PHASE_IDLE)
                .putLong("phaseStartedAt", System.currentTimeMillis())
                .putString("status", "사용자 중지")
                .putString("pauseResumePhase", "")
                .putString("pauseCause", ""));
        syncHistoryCritical();
    }

    void setActive(boolean value) { putBoolean("active", value, true, false); }
    void setUserStopped(boolean value) { putBoolean("userStopped", value, true, false); }

    void syncHistory() { history.sync(this); }
    void syncHistoryCritical() { history.syncCritical(this); }

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
        if (pauseResumePhase().isEmpty() && pauseCause().isEmpty()) {
            markNoop();
            return;
        }
        applyEditor(prefs.edit().putString("pauseResumePhase", "").putString("pauseCause", ""));
    }

    private void putString(String key, String value, boolean historyRelevant, boolean historyCritical) {
        String next = safe(value);
        if (next.equals(prefs.getString(key, ""))) {
            markNoop();
            return;
        }
        applyEditor(prefs.edit().putString(key, next));
        if (historyRelevant) {
            if (historyCritical) syncHistoryCritical(); else syncHistory();
        }
    }

    private void putBoolean(String key, boolean value, boolean historyRelevant, boolean historyCritical) {
        if (value == prefs.getBoolean(key, false)) {
            markNoop();
            return;
        }
        applyEditor(prefs.edit().putBoolean(key, value));
        if (historyRelevant) {
            if (historyCritical) syncHistoryCritical(); else syncHistory();
        }
    }

    private void applyEditor(SharedPreferences.Editor editor) {
        editor.apply();
        stateWriteTransactions = increment(stateWriteTransactions);
    }

    private void markNoop() {
        duplicateStateWritesSkipped = increment(duplicateStateWritesSkipped);
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
