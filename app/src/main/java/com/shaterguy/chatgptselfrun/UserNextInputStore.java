package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable user-authored text to append to exactly the next SelfRun continuation. */
final class UserNextInputStore {
    private static final String PREFS = "selfrun_drive_user_next_input";
    private static final String RUN_ID = "runId";
    private static final String TARGET_TURN = "targetTurn";
    private static final String TEXT = "text";
    private static SharedPreferences prefs;
    private static SharedPreferences runPrefs;

    private UserNextInputStore() {}

    static synchronized void initialize(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        runPrefs = app.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE);
    }

    static synchronized String current(String runId) {
        ensureInitialized();
        if (!safe(runId).equals(prefs.getString(RUN_ID, ""))) return "";
        int currentTurn = runPrefs.getInt("turn", 0);
        if (!appliesToNextTurn(prefs.getInt(TARGET_TURN, -1), currentTurn)) return "";
        return safe(prefs.getString(TEXT, ""));
    }

    static synchronized boolean editable(String runId) {
        ensureInitialized();
        if (runId == null || runId.isEmpty() || !runId.equals(runPrefs.getString("runId", ""))) return false;
        if (!runPrefs.getBoolean("active", false) || runPrefs.getBoolean("userStopped", false)) return false;
        String phase = safe(runPrefs.getString("phase", SelfRunStore.PHASE_IDLE));
        return !SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)
                && !SelfRunStore.PHASE_DONE.equals(phase)
                && !SelfRunStore.PHASE_IDLE.equals(phase);
    }

    static synchronized boolean save(String runId, String text) {
        ensureInitialized();
        if (!editable(runId)) return false;
        String value = safe(text);
        if (value.isEmpty()) return delete(runId);
        int targetTurn = runPrefs.getInt("turn", 0) + 1;
        return prefs.edit().putString(RUN_ID, runId).putInt(TARGET_TURN, targetTurn).putString(TEXT, value).commit();
    }

    static synchronized boolean delete(String runId) {
        ensureInitialized();
        if (!editable(runId)) return false;
        if (!runId.equals(prefs.getString(RUN_ID, ""))) return true;
        return prefs.edit().clear().commit();
    }

    static synchronized String merge(String runId, String driveNextInput) {
        return mergeText(driveNextInput, current(runId));
    }

    static boolean appliesToNextTurn(int targetTurn, int currentTurn) {
        return targetTurn == currentTurn + 1;
    }

    static String mergeText(String driveNextInput, String userInput) {
        String drive = safe(driveNextInput);
        String user = safe(userInput);
        if (drive.isEmpty()) return user;
        if (user.isEmpty()) return drive;
        return drive + "\n\n" + user;
    }

    private static void ensureInitialized() {
        if (prefs == null || runPrefs == null) throw new IllegalStateException("UserNextInputStore not initialized");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
