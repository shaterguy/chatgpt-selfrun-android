package com.shaterguy.chatgptselfrun;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.util.List;

/** Event-only process exit attribution. Never polled and never changes Run execution state. */
final class SelfRunProcessExitDiagnostics {
    static final class Result {
        final String category;
        final String reason;
        Result(String category, String reason) {
            this.category = category == null ? "" : category;
            this.reason = reason == null ? "unknown" : reason;
        }
    }

    private static boolean capturedForProcess;

    private SelfRunProcessExitDiagnostics() { }

    static synchronized void capture(Context context) {
        if (capturedForProcess) return;
        capturedForProcess = true;
        if (context == null || Build.VERSION.SDK_INT < 30) return;
        try {
            Context app = context.getApplicationContext();
            SelfRunStore store = new SelfRunStore(app);
            if (store.runId().isEmpty() || !store.active() || store.userStopped()
                    || SelfRunStore.PHASE_DONE.equals(store.phase()) || SelfRunStore.PHASE_IDLE.equals(store.phase())) return;

            JSONObject history = new SelfRunHistoryStore(app).get(store.runId());
            long lastActivityAt = history == null ? 0L : history.optLong("updatedAt");
            if (lastActivityAt <= 0L) lastActivityAt = Math.max(store.createdAt(), store.phaseStartedAt());

            SelfRunHealthObservationStore health = new SelfRunHealthObservationStore(app);
            long lastProcessed = health.lastProcessedExitTimestamp(store.runId());
            ActivityManager activity = app.getSystemService(ActivityManager.class);
            if (activity == null) return;
            List<ApplicationExitInfo> exits = activity.getHistoricalProcessExitReasons(null, 0, 5);
            if (exits == null || exits.isEmpty()) return;
            long now = System.currentTimeMillis();
            for (ApplicationExitInfo info : exits) {
                if (info == null) continue;
                long timestamp = info.getTimestamp();
                if (!isRelated(store.createdAt(), lastActivityAt, lastProcessed, timestamp, now)) continue;
                Result result = classifyReason(info.getReason(), Build.VERSION.SDK_INT);
                health.markProcessExitSeen(store.runId(), timestamp);
                if (!result.category.isEmpty()) {
                    health.observeProcessExit(store.runId(), result.category, result.reason, timestamp);
                }
                break;
            }
        } catch (Throwable ignored) { }
    }

    static boolean isRelated(long runCreatedAt, long lastActivityAt, long lastProcessedAt,
                             long exitTimestamp, long now) {
        if (exitTimestamp <= 0L || now <= 0L || exitTimestamp > now + 5_000L) return false;
        long floor = Math.max(Math.max(0L, runCreatedAt), Math.max(0L, lastActivityAt));
        return exitTimestamp >= floor && exitTimestamp > Math.max(0L, lastProcessedAt);
    }

    static Result classifyReason(int reason, int sdkInt) {
        if (reason == ApplicationExitInfo.REASON_CRASH) return new Result("APP_CRASH", "crash");
        if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE) return new Result("APP_CRASH", "native_crash");
        if (reason == ApplicationExitInfo.REASON_ANR) return new Result("APP_ANR", "anr");
        if (reason == ApplicationExitInfo.REASON_LOW_MEMORY) return new Result("LOW_MEMORY", "low_memory");
        if (reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE) return new Result("EXCESSIVE_RESOURCE", "excessive_resource");
        if (reason == ApplicationExitInfo.REASON_DEPENDENCY_DIED) return new Result("PROCESS_EXIT_GENERIC", "dependency_died");
        if (reason == ApplicationExitInfo.REASON_PERMISSION_CHANGE) return new Result("PROCESS_EXIT_GENERIC", "permission_change");
        if (reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE) return new Result("PROCESS_EXIT_GENERIC", "initialization_failure");
        if (reason == ApplicationExitInfo.REASON_SIGNALED) return new Result("PROCESS_EXIT_GENERIC", "signaled");
        if (reason == ApplicationExitInfo.REASON_OTHER) return new Result("PROCESS_EXIT_GENERIC", "other");
        if (reason == ApplicationExitInfo.REASON_USER_REQUESTED) return new Result("PROCESS_EXIT_GENERIC", "user_requested_or_update");
        if (sdkInt >= 33 && reason == ApplicationExitInfo.REASON_FREEZER) return new Result("PROCESS_EXIT_GENERIC", "freezer");
        if (sdkInt >= 34 && reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED) return new Result("PROCESS_EXIT_GENERIC", "package_updated");
        if (sdkInt >= 34 && reason == ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE) return new Result("PROCESS_EXIT_GENERIC", "package_state_change");
        return new Result("", "unknown");
    }
}
