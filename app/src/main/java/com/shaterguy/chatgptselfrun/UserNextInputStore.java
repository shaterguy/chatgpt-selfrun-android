package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable user-authored text to append to exactly the next SelfRun continuation. */
final class UserNextInputStore {
    private static final String PREFS = "selfrun_drive_user_next_input";
    private static final String RUN_ID = "runId";
    private static final String TEXT = "text";
    private static final String BOUND_CONTINUATION = "boundContinuation";
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
        String bound = safe(prefs.getString(BOUND_CONTINUATION, ""));
        if (!bound.isEmpty() && !bound.equals(currentContinuationIdentity())) return "";
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
        return prefs.edit().putString(RUN_ID, runId).putString(TEXT, value).remove(BOUND_CONTINUATION).commit();
    }

    static synchronized boolean delete(String runId) {
        ensureInitialized();
        if (!editable(runId)) return false;
        if (!runId.equals(prefs.getString(RUN_ID, ""))) return true;
        return prefs.edit().clear().commit();
    }

    static synchronized String merge(String runId, String driveNextInput) {
        ensureInitialized();
        if (!safe(runId).equals(prefs.getString(RUN_ID, ""))) return safe(driveNextInput);
        String identity = currentContinuationIdentity();
        if (identity.isEmpty()) return safe(driveNextInput);
        String bound = safe(prefs.getString(BOUND_CONTINUATION, ""));
        if (bound.isEmpty()) {
            if (!prefs.edit().putString(BOUND_CONTINUATION, identity).commit()) {
                throw new IllegalStateException("user next-input continuation binding failed");
            }
            bound = identity;
        }
        if (!identity.equals(bound)) return safe(driveNextInput);
        return mergeText(driveNextInput, prefs.getString(TEXT, ""));
    }

    static String continuationIdentity(int driveSignalCursor, long phaseStartedAt) {
        if (phaseStartedAt <= 0L) return "";
        return driveSignalCursor + ":" + phaseStartedAt;
    }

    static boolean reservationApplies(String boundIdentity, String currentIdentity) {
        String bound = safe(boundIdentity);
        String current = safe(currentIdentity);
        return !bound.isEmpty() && bound.equals(current);
    }

    static String mergeText(String driveNextInput, String userInput) {
        String drive = safe(driveNextInput);
        String user = safe(userInput);
        if (drive.isEmpty()) return user;
        if (user.isEmpty()) return drive;
        return drive + "\n\n" + user;
    }

    private static String currentContinuationIdentity() {
        if (!SelfRunStore.PHASE_SEND_CONTINUE.equals(safe(runPrefs.getString("phase", "")))) return "";
        return continuationIdentity(runPrefs.getInt("driveSignalCursor", 0), runPrefs.getLong("phaseStartedAt", 0L));
    }

    private static void ensureInitialized() {
        if (prefs == null || runPrefs == null) throw new IllegalStateException("UserNextInputStore not initialized");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
