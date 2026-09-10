package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable user-authored text consumed by SelfRun 3 through revision-checked snapshots. */
final class UserNextInputStore {
    private static final String PREFS = "selfrun_drive_user_next_input";
    private static final String RUN_ID = "runId";
    private static final String TEXT = "text";
    private static final String REVISION = "revision";
    private static SharedPreferences prefs;
    private static SharedPreferences runPrefs;

    private UserNextInputStore() {}

    static synchronized void initialize(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        runPrefs = app.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE);
        cleanupStale();
    }

    static synchronized boolean initialized() { return prefs != null && runPrefs != null; }

    static synchronized String current(String runId) {
        ensureInitialized();
        cleanupStale();
        return safe(runId).equals(prefs.getString(RUN_ID, "")) ? safe(prefs.getString(TEXT, "")) : "";
    }

    static synchronized boolean editable(String runId) {
        ensureInitialized();
        cleanupStale();
        if (runId == null || runId.isEmpty() || !runId.equals(runPrefs.getString("runId", ""))) return false;
        if (!runPrefs.getBoolean("active", false) || runPrefs.getBoolean("userStopped", false)) return false;
        return phaseAllowsEditing(runPrefs.getString("phase", SelfRunStore.PHASE_IDLE), false);
    }

    /** V3 has no separate continuation submission lock; revision snapshots provide the boundary. */
    static synchronized boolean submissionLocked(String runId) { return false; }

    static synchronized boolean save(String runId, String text) {
        ensureInitialized();
        if (!editable(runId)) return false;
        String value = safe(text);
        if (value.isEmpty()) return delete(runId);
        String storedRunId = prefs.getString(RUN_ID, "");
        long revision = runId.equals(storedRunId) ? prefs.getLong(REVISION, 0L) : 0L;
        if (runId.equals(storedRunId) && value.equals(prefs.getString(TEXT, ""))) return true;
        SharedPreferences.Editor edit = prefs.edit();
        if (!runId.equals(storedRunId)) edit.clear();
        return edit.putString(RUN_ID, runId).putString(TEXT, value).putLong(REVISION, revision + 1L).commit();
    }

    static synchronized boolean delete(String runId) {
        ensureInitialized();
        if (!editable(runId)) return false;
        if (!safe(runId).equals(prefs.getString(RUN_ID, ""))) return true;
        long revision = prefs.getLong(REVISION, 0L);
        return prefs.edit().putString(TEXT, "").putLong(REVISION, revision + 1L).commit();
    }

    static boolean phaseAllowsEditing(String phase, boolean ignoredSubmissionLock) {
        String current = safe(phase);
        return !SelfRunStore.PHASE_DONE.equals(current) && !SelfRunStore.PHASE_IDLE.equals(current);
    }

    static String mergeText(String priorInput, String userInput) {
        String prior = safe(priorInput), user = safe(userInput);
        if (prior.isEmpty()) return user;
        if (user.isEmpty()) return prior;
        return prior + "\n\n" + user;
    }

    static String composePrompt(String originalPrompt, String mergedPayload) {
        String original = safe(originalPrompt), payload = safe(mergedPayload);
        return payload.isEmpty() ? original : original + "\n" + payload;
    }

    private static void cleanupStale() {
        if (prefs == null || runPrefs == null) return;
        String storedRunId = prefs.getString(RUN_ID, "");
        if (storedRunId.isEmpty()) return;
        String currentRunId = runPrefs.getString("runId", "");
        String phase = runPrefs.getString("phase", SelfRunStore.PHASE_IDLE);
        boolean active = runPrefs.getBoolean("active", false);
        boolean stopped = runPrefs.getBoolean("userStopped", false);
        if (!storedRunId.equals(currentRunId) || stopped || !active
                || SelfRunStore.PHASE_DONE.equals(phase) || SelfRunStore.PHASE_IDLE.equals(phase)) {
            prefs.edit().clear().commit();
        }
    }

    private static void ensureInitialized() {
        if (!initialized()) throw new IllegalStateException("UserNextInputStore not initialized");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
