package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;

/** Durable user-authored text to append to exactly the next SelfRun continuation. */
final class UserNextInputStore {
    static final int MAX_USER_UTF8_BYTES = NextInputCodec.MAX_UTF8_BYTES;
    static final int MAX_COMBINED_UTF8_BYTES = NextInputCodec.MAX_UTF8_BYTES * 2 + 2;

    private static final String PREFS = "selfrun_drive_user_next_input";
    private static final String RUN_ID = "runId";
    private static final String TEXT = "text";
    private static final String BOUND_CONTINUATION = "boundContinuation";
    private static SharedPreferences prefs;
    private static SharedPreferences runPrefs;
    private static SharedPreferences.OnSharedPreferenceChangeListener runListener;

    private UserNextInputStore() {}

    static synchronized void initialize(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        runPrefs = app.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE);
        if (runListener == null) {
            runListener = (sharedPreferences, key) -> {
                if ("phase".equals(key) || "pausedFromPhase".equals(key)
                        || "runId".equals(key) || "active".equals(key) || "userStopped".equals(key)) {
                    cleanupReservation();
                }
            };
            runPrefs.registerOnSharedPreferenceChangeListener(runListener);
        }
        cleanupReservation();
    }

    static synchronized boolean initialized() {
        return prefs != null && runPrefs != null;
    }

    static synchronized String current(String runId) {
        ensureInitialized();
        cleanupReservation();
        if (!safe(runId).equals(prefs.getString(RUN_ID, ""))) return "";
        String bound = safe(prefs.getString(BOUND_CONTINUATION, ""));
        if (!bound.isEmpty() && !bound.equals(currentContinuationIdentity())) return "";
        return safe(prefs.getString(TEXT, ""));
    }

    static synchronized boolean editable(String runId) {
        ensureInitialized();
        cleanupReservation();
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
        if (!withinUtf8Limit(value, MAX_USER_UTF8_BYTES)) return false;
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
        cleanupReservation();
        String drive = safe(driveNextInput);
        if (!safe(runId).equals(prefs.getString(RUN_ID, ""))) return drive;
        String identity = currentContinuationIdentity();
        if (identity.isEmpty()) return drive;
        String user = safe(prefs.getString(TEXT, ""));
        if (user.isEmpty()) return drive;
        String bound = safe(prefs.getString(BOUND_CONTINUATION, ""));
        if (bound.isEmpty()) {
            if (!prefs.edit().putString(BOUND_CONTINUATION, identity).commit()) {
                throw new IllegalStateException("user next-input continuation binding failed");
            }
            bound = identity;
        }
        if (!identity.equals(bound)) return drive;
        String merged = mergeText(drive, user);
        if (!withinUtf8Limit(merged, MAX_COMBINED_UTF8_BYTES)) {
            throw new IllegalArgumentException("USER_NEXT_INPUT_COMBINED_TOO_LARGE");
        }
        return merged;
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

    static boolean withinUtf8Limit(String value, int limit) {
        if (limit < 0) return false;
        return safe(value).getBytes(StandardCharsets.UTF_8).length <= limit;
    }

    static boolean shouldDiscardStaleReservation(String storedRunId, String currentRunId,
                                                  boolean active, boolean userStopped, String phase) {
        String stored = safe(storedRunId);
        if (stored.isEmpty()) return false;
        String current = safe(currentRunId);
        if (!stored.equals(current)) return true;
        if (userStopped) return true;
        if (SelfRunStore.PHASE_IDLE.equals(phase) || SelfRunStore.PHASE_DONE.equals(phase)) return true;
        return !active && !SelfRunStore.PHASE_PAUSED.equals(phase);
    }

    static boolean shouldConsumeBoundReservation(String phase, String pausedFromPhase,
                                                  String boundIdentity, String currentIdentity) {
        String bound = safe(boundIdentity);
        if (bound.isEmpty()) return false;
        String current = safe(currentIdentity);
        if (SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)
                || SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC.equals(phase)
                || SelfRunStore.PHASE_APPLY_PREFS.equals(phase)
                || SelfRunStore.PHASE_APPLY_REASONING.equals(phase)
                || SelfRunStore.PHASE_DONE.equals(phase)) return true;
        if (SelfRunStore.PHASE_PAUSED.equals(phase)
                && SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(pausedFromPhase)) return true;
        return SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)
                && !current.isEmpty() && !bound.equals(current);
    }

    private static synchronized void cleanupReservation() {
        if (prefs == null || runPrefs == null) return;
        String storedRunId = safe(prefs.getString(RUN_ID, ""));
        if (storedRunId.isEmpty()) return;
        String phase = safe(runPrefs.getString("phase", SelfRunStore.PHASE_IDLE));
        String currentRunId = safe(runPrefs.getString("runId", ""));
        boolean active = runPrefs.getBoolean("active", false);
        boolean userStopped = runPrefs.getBoolean("userStopped", false);
        if (shouldDiscardStaleReservation(storedRunId, currentRunId, active, userStopped, phase)) {
            prefs.edit().clear().commit();
            return;
        }
        String bound = safe(prefs.getString(BOUND_CONTINUATION, ""));
        if (bound.isEmpty()) return;
        String pausedFromPhase = safe(runPrefs.getString("pausedFromPhase", ""));
        String currentIdentity = currentContinuationIdentity();
        if (shouldConsumeBoundReservation(phase, pausedFromPhase, bound, currentIdentity)) {
            prefs.edit().clear().commit();
        }
    }

    private static String currentContinuationIdentity() {
        if (!SelfRunStore.PHASE_SEND_CONTINUE.equals(safe(runPrefs.getString("phase", "")))) return "";
        return continuationIdentity(runPrefs.getInt("driveSignalCursor", 0), runPrefs.getLong("phaseStartedAt", 0L));
    }

    private static void ensureInitialized() {
        if (!initialized()) throw new IllegalStateException("UserNextInputStore not initialized");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
