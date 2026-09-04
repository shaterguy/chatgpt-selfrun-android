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
    private static final String DRIVE_TEXT = "driveText";
    private static final String REVISION = "revision";
    private static final String LOCKED_CONTINUATION = "lockedContinuation";
    private static final String LOCKED_REVISION = "lockedRevision";
    private static final String LOCK_PROBE_CONTINUATION = "lockProbeContinuation";
    private static final String LOCK_PROBE_REVISION = "lockProbeRevision";
    private static final String PREFLIGHT_CONTINUATION = "preflightContinuation";
    private static final String PREFLIGHT_REVISION = "preflightRevision";
    private static final String LEGACY_BOUND_CONTINUATION = "boundContinuation";
    private static SharedPreferences prefs;
    private static SharedPreferences runPrefs;
    private static SharedPreferences.OnSharedPreferenceChangeListener runListener;

    static final class ClickPlan {
        final String prompt;
        final boolean clickAllowed;

        ClickPlan(String prompt, boolean clickAllowed) {
            this.prompt = safe(prompt);
            this.clickAllowed = clickAllowed;
        }
    }

    private UserNextInputStore() {}

    static synchronized void initialize(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        runPrefs = app.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE);
        migrateLegacyBinding();
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
        return safe(runId).equals(prefs.getString(RUN_ID, "")) ? safe(prefs.getString(TEXT, "")) : "";
    }

    static synchronized boolean editable(String runId) {
        ensureInitialized();
        cleanupReservation();
        if (runId == null || runId.isEmpty() || !runId.equals(runPrefs.getString("runId", ""))) return false;
        if (!runPrefs.getBoolean("active", false) || runPrefs.getBoolean("userStopped", false)) return false;
        String phase = safe(runPrefs.getString("phase", SelfRunStore.PHASE_IDLE));
        boolean locked = runId.equals(prefs.getString(RUN_ID, ""))
                && !safe(prefs.getString(LOCKED_CONTINUATION, "")).isEmpty();
        return phaseAllowsEditing(phase, locked);
    }

    static synchronized boolean submissionLocked(String runId) {
        ensureInitialized();
        cleanupReservation();
        return safe(runId).equals(prefs.getString(RUN_ID, ""))
                && !safe(prefs.getString(LOCKED_CONTINUATION, "")).isEmpty();
    }

    static synchronized boolean managesContinuation(String runId) {
        ensureInitialized();
        cleanupReservation();
        return !SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(runId)
                && currentSendContext(runId);
    }

    static synchronized boolean beginLockedRetryProbe(String runId, String continuationIdentity) {
        ensureInitialized();
        cleanupReservation();
        String identity = safe(continuationIdentity);
        if (!currentSendContext(runId) || identity.isEmpty() || !identity.equals(currentContinuationIdentity())) return false;
        if (!safe(runId).equals(prefs.getString(RUN_ID, ""))) return false;
        String locked = safe(prefs.getString(LOCKED_CONTINUATION, ""));
        long revision = prefs.getLong(REVISION, 0L);
        long lockedRevision = prefs.getLong(LOCKED_REVISION, -1L);
        if (!identity.equals(locked) || revision != lockedRevision) return false;
        boolean committed = prefs.edit().putString(LOCK_PROBE_CONTINUATION, identity)
                .putLong(LOCK_PROBE_REVISION, revision).commit();
        if (!committed) throw new IllegalStateException("user next-input lock retry probe failed");
        return true;
    }

    static synchronized boolean save(String runId, String text) {
        ensureInitialized();
        if (!editable(runId)) return false;
        String value = safe(text);
        if (!withinUtf8Limit(value, MAX_USER_UTF8_BYTES)) return false;
        if (value.isEmpty()) return delete(runId);
        String storedRunId = safe(prefs.getString(RUN_ID, ""));
        long revision = storedRunId.equals(runId) ? prefs.getLong(REVISION, 0L) : 0L;
        if (storedRunId.equals(runId) && value.equals(safe(prefs.getString(TEXT, "")))) return true;
        SharedPreferences.Editor edit = prefs.edit();
        if (!storedRunId.equals(runId)) edit.clear();
        return edit.putString(RUN_ID, runId).putString(TEXT, value).putLong(REVISION, revision + 1L)
                .remove(PREFLIGHT_CONTINUATION).remove(PREFLIGHT_REVISION)
                .remove(LOCK_PROBE_CONTINUATION).remove(LOCK_PROBE_REVISION).commit();
    }

    static synchronized boolean delete(String runId) {
        ensureInitialized();
        if (!editable(runId)) return false;
        if (!safe(runId).equals(prefs.getString(RUN_ID, ""))) return true;
        if (safe(prefs.getString(TEXT, "")).isEmpty()) return true;
        long revision = prefs.getLong(REVISION, 0L);
        return prefs.edit().putString(TEXT, "").putLong(REVISION, revision + 1L)
                .remove(PREFLIGHT_CONTINUATION).remove(PREFLIGHT_REVISION)
                .remove(LOCK_PROBE_CONTINUATION).remove(LOCK_PROBE_REVISION).commit();
    }

    static synchronized String merge(String runId, String driveNextInput) {
        ensureInitialized();
        cleanupReservation();
        String drive = safe(driveNextInput);
        if (!currentSendContext(runId)) return drive;
        ensurePayloadRecord(runId, drive);
        return mergedPayload(runId);
    }

    static synchronized String promptForPreparation(String runId, String originalPrompt) {
        ensureInitialized();
        cleanupReservation();
        if (!normalContinuationPrompt(originalPrompt) || !currentSendContext(runId)) return safe(originalPrompt);
        if (safe(runId).equals(prefs.getString(RUN_ID, ""))
                && safe(prefs.getString(LOCKED_CONTINUATION, "")).isEmpty()
                && !safe(prefs.getString(PREFLIGHT_CONTINUATION, "")).isEmpty()) {
            prefs.edit().remove(PREFLIGHT_CONTINUATION).remove(PREFLIGHT_REVISION).commit();
        }
        return composePrompt(originalPrompt, mergedPayload(runId));
    }

    static synchronized ClickPlan nextClickPlan(String runId, String continuationIdentity, String originalPrompt) {
        ensureInitialized();
        cleanupReservation();
        String latestPrompt = normalContinuationPrompt(originalPrompt) && currentSendContext(runId)
                ? composePrompt(originalPrompt, mergedPayload(runId)) : safe(originalPrompt);
        String identity = safe(continuationIdentity);
        if (!currentSendContext(runId) || identity.isEmpty() || !identity.equals(currentContinuationIdentity())) {
            return new ClickPlan(latestPrompt, false);
        }
        String storedRunId = safe(prefs.getString(RUN_ID, ""));
        if (!runId.equals(storedRunId)) return new ClickPlan(latestPrompt, false);

        String locked = safe(prefs.getString(LOCKED_CONTINUATION, ""));
        if (!locked.isEmpty()) {
            long currentRevision = prefs.getLong(REVISION, 0L);
            long lockedRevision = prefs.getLong(LOCKED_REVISION, -1L);
            if (!identity.equals(locked) || currentRevision != lockedRevision) {
                return new ClickPlan(latestPrompt, false);
            }
            String probe = safe(prefs.getString(LOCK_PROBE_CONTINUATION, ""));
            long probeRevision = prefs.getLong(LOCK_PROBE_REVISION, -1L);
            if (lockProbeMatches(probe, probeRevision, identity, currentRevision)) {
                boolean reopened = prefs.edit()
                        .remove(LOCKED_CONTINUATION).remove(LOCKED_REVISION)
                        .remove(LOCK_PROBE_CONTINUATION).remove(LOCK_PROBE_REVISION)
                        .putString(PREFLIGHT_CONTINUATION, identity)
                        .putLong(PREFLIGHT_REVISION, currentRevision).commit();
                if (!reopened) throw new IllegalStateException("user next-input retry reopen failed");
                return new ClickPlan(latestPrompt, false);
            }
            return new ClickPlan(latestPrompt, true);
        }

        long revision = prefs.getLong(REVISION, 0L);
        String preflight = safe(prefs.getString(PREFLIGHT_CONTINUATION, ""));
        long preflightRevision = prefs.getLong(PREFLIGHT_REVISION, -1L);
        if (preflightMatches(preflight, preflightRevision, identity, revision)) {
            boolean committed = prefs.edit().putString(LOCKED_CONTINUATION, identity)
                    .putLong(LOCKED_REVISION, revision)
                    .remove(PREFLIGHT_CONTINUATION).remove(PREFLIGHT_REVISION)
                    .remove(LOCK_PROBE_CONTINUATION).remove(LOCK_PROBE_REVISION).commit();
            if (!committed) throw new IllegalStateException("user next-input submission lock failed");
            return new ClickPlan(latestPrompt, true);
        }
        boolean committed = prefs.edit().putString(PREFLIGHT_CONTINUATION, identity)
                .putLong(PREFLIGHT_REVISION, revision)
                .remove(LOCK_PROBE_CONTINUATION).remove(LOCK_PROBE_REVISION).commit();
        if (!committed) throw new IllegalStateException("user next-input preflight state failed");
        return new ClickPlan(latestPrompt, false);
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

    static boolean phaseAllowsEditing(String phase, boolean submissionLocked) {
        String current = safe(phase);
        return !submissionLocked && !SelfRunStore.PHASE_DONE.equals(current) && !SelfRunStore.PHASE_IDLE.equals(current);
    }

    static boolean preflightMatches(String preflightIdentity, long preflightRevision,
                                    String currentIdentity, long currentRevision) {
        String preflight = safe(preflightIdentity);
        String current = safe(currentIdentity);
        return !preflight.isEmpty() && preflight.equals(current) && preflightRevision == currentRevision;
    }

    static boolean lockProbeMatches(String probeIdentity, long probeRevision,
                                    String currentIdentity, long currentRevision) {
        String probe = safe(probeIdentity);
        String current = safe(currentIdentity);
        return !probe.isEmpty() && probe.equals(current) && probeRevision == currentRevision;
    }

    static String mergeText(String driveNextInput, String userInput) {
        String drive = safe(driveNextInput);
        String user = safe(userInput);
        if (drive.isEmpty()) return user;
        if (user.isEmpty()) return drive;
        return drive + "\n\n" + user;
    }

    static String composePrompt(String originalPrompt, String mergedPayload) {
        String original = safe(originalPrompt);
        if (!normalContinuationPrompt(original)) return original;
        int newline = original.indexOf('\n');
        String base = newline < 0 ? original : original.substring(0, newline);
        String payload = safe(mergedPayload);
        return payload.isEmpty() ? base : base + "\n" + payload;
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
                || SelfRunStore.PHASE_POST_PROTOCOL_DRIVE_SYNC.equals(phase)
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
        String pausedFromPhase = safe(runPrefs.getString("pausedFromPhase", ""));
        String currentIdentity = currentContinuationIdentity();
        String locked = safe(prefs.getString(LOCKED_CONTINUATION, ""));
        if (!locked.isEmpty() && shouldConsumeBoundReservation(phase, pausedFromPhase, locked, currentIdentity)) {
            prefs.edit().clear().commit();
            return;
        }
        String preflight = safe(prefs.getString(PREFLIGHT_CONTINUATION, ""));
        if (preflight.isEmpty()) return;
        if (SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)
                || SelfRunStore.PHASE_POST_PROTOCOL_DRIVE_SYNC.equals(phase)
                || SelfRunStore.PHASE_APPLY_PREFS.equals(phase)
                || SelfRunStore.PHASE_APPLY_REASONING.equals(phase)
                || (SelfRunStore.PHASE_PAUSED.equals(phase)
                && SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(pausedFromPhase))) {
            prefs.edit().clear().commit();
            return;
        }
        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase) && !currentIdentity.isEmpty()
                && !preflight.equals(currentIdentity)) {
            prefs.edit().remove(PREFLIGHT_CONTINUATION).remove(PREFLIGHT_REVISION)
                    .remove(LOCK_PROBE_CONTINUATION).remove(LOCK_PROBE_REVISION).commit();
        }
    }

    private static void ensurePayloadRecord(String runId, String driveText) {
        String storedRunId = safe(prefs.getString(RUN_ID, ""));
        String drive = safe(driveText);
        if (!runId.equals(storedRunId)) {
            long revision = drive.isEmpty() ? 0L : 1L;
            boolean committed = prefs.edit().clear().putString(RUN_ID, runId).putString(TEXT, "")
                    .putString(DRIVE_TEXT, drive).putLong(REVISION, revision).commit();
            if (!committed) throw new IllegalStateException("user next-input payload initialization failed");
            return;
        }
        String storedDrive = safe(prefs.getString(DRIVE_TEXT, ""));
        if (storedDrive.equals(drive)) return;
        String locked = safe(prefs.getString(LOCKED_CONTINUATION, ""));
        if (!locked.isEmpty()) {
            if (!storedDrive.isEmpty()) throw new IllegalStateException("locked continuation drive input changed");
            boolean committed = prefs.edit().putString(DRIVE_TEXT, drive)
                    .putLong(LOCKED_REVISION, prefs.getLong(REVISION, 0L)).commit();
            if (!committed) throw new IllegalStateException("locked continuation drive input restore failed");
            return;
        }
        long revision = prefs.getLong(REVISION, 0L);
        boolean committed = prefs.edit().putString(DRIVE_TEXT, drive).putLong(REVISION, revision + 1L)
                .remove(PREFLIGHT_CONTINUATION).remove(PREFLIGHT_REVISION)
                .remove(LOCK_PROBE_CONTINUATION).remove(LOCK_PROBE_REVISION).commit();
        if (!committed) throw new IllegalStateException("user next-input drive payload update failed");
    }

    private static String mergedPayload(String runId) {
        if (!safe(runId).equals(prefs.getString(RUN_ID, ""))) return "";
        String merged = mergeText(prefs.getString(DRIVE_TEXT, ""), prefs.getString(TEXT, ""));
        if (!withinUtf8Limit(merged, MAX_COMBINED_UTF8_BYTES)) {
            throw new IllegalArgumentException("USER_NEXT_INPUT_COMBINED_TOO_LARGE");
        }
        return merged;
    }

    private static boolean currentSendContext(String runId) {
        return safe(runId).equals(runPrefs.getString("runId", ""))
                && runPrefs.getBoolean("active", false) && !runPrefs.getBoolean("userStopped", false)
                && SelfRunStore.PHASE_SEND_CONTINUE.equals(safe(runPrefs.getString("phase", "")));
    }

    private static String currentContinuationIdentity() {
        if (!SelfRunStore.PHASE_SEND_CONTINUE.equals(safe(runPrefs.getString("phase", "")))) return "";
        return continuationIdentity(runPrefs.getInt("driveSignalCursor", 0), runPrefs.getLong("phaseStartedAt", 0L));
    }

    private static boolean normalContinuationPrompt(String prompt) {
        String value = safe(prompt);
        return value.contains("[SELF_RUN_CONTINUE ") && !value.contains(" RECOVERY_ID=");
    }

    private static void migrateLegacyBinding() {
        if (prefs != null && prefs.contains(LEGACY_BOUND_CONTINUATION)) {
            prefs.edit().remove(LEGACY_BOUND_CONTINUATION).commit();
        }
    }

    private static void ensureInitialized() {
        if (!initialized()) throw new IllegalStateException("UserNextInputStore not initialized");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
