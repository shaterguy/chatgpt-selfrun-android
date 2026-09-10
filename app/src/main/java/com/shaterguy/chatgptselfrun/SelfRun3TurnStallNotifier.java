package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only app-side observer that only warns about a conversation stalled past 125 minutes. */
final class SelfRun3TurnStallNotifier implements AutoCloseable {
    private static final String STORE_PREFS = "selfrun_drive";
    private static final String ALERT_PREFS = "selfrun3-turn-stall-alert-v1";
    private static final String LAST_ALERTED_EXECUTION = "last_alerted_execution";

    private final Context context;
    private final SelfRunStore store;
    private final SelfRun3Ledger ledger;
    private final SharedPreferences projectionPrefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable rearm = this::rearmNow;
    private final SharedPreferences.OnSharedPreferenceChangeListener projectionListener =
            (prefs, key) -> {
                if (key == null || "runId".equals(key) || "turn".equals(key) || "phase".equals(key)
                        || "active".equals(key) || "paused".equals(key)
                        || "userStopped".equals(key)) {
                    requestRearm();
                }
            };

    private boolean started;
    private boolean closed;
    private boolean listenerRegistered;
    private long generation;
    private Runnable scheduledCheck;

    SelfRun3TurnStallNotifier(Context context, SelfRunStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.ledger = new SelfRun3Ledger(this.context);
        this.projectionPrefs = this.context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE);
    }

    void start() {
        if (closed || started) return;
        started = true;
        projectionPrefs.registerOnSharedPreferenceChangeListener(projectionListener);
        listenerRegistered = true;
        requestRearm();
    }

    private void requestRearm() {
        if (closed || !started) return;
        main.removeCallbacks(rearm);
        main.post(rearm);
    }

    private void rearmNow() {
        if (closed || !started) return;
        long expectedGeneration = ++generation;
        if (scheduledCheck != null) {
            main.removeCallbacks(scheduledCheck);
            scheduledCheck = null;
        }
        if (!storeReady()) return;

        String taskId = store.runId();
        int projectedTurn = store.turn();
        io.execute(() -> {
            ScheduledCandidate candidate = readSchedule(taskId);
            main.post(() -> {
                if (!current(expectedGeneration, taskId, projectedTurn) || candidate == null
                        || candidate.turn != projectedTurn) return;
                Runnable check = () -> runScheduledCheck(expectedGeneration, candidate);
                scheduledCheck = check;
                main.postDelayed(check, candidate.remainingMs);
            });
        });
    }

    private ScheduledCandidate readSchedule(String taskId) {
        try {
            if (!SelfRun3RunMarker.current(context, taskId)) return null;
            SelfRun3Engine.State state = ledger.load(taskId);
            long remaining = SelfRun3TurnStallPolicy.remainingDelayMs(state,
                    SystemClock.elapsedRealtime(), System.currentTimeMillis(), currentBootCount());
            if (remaining < 0L) return null;
            return new ScheduledCandidate(state.taskId(), state.turnId(), state.turn(), remaining);
        } catch (Throwable ignored) {
            // This observer must never alter or pause the SelfRun execution it is watching.
            return null;
        }
    }

    private void runScheduledCheck(long expectedGeneration, ScheduledCandidate scheduled) {
        if (!current(expectedGeneration, scheduled.taskId, scheduled.turn)) return;
        scheduledCheck = null;
        io.execute(() -> {
            Candidate candidate = readCandidate(scheduled.taskId, scheduled.turnId);
            main.post(() -> {
                if (candidate != null && current(expectedGeneration, scheduled.taskId, scheduled.turn)) {
                    notifyOnce(candidate);
                }
            });
        });
    }

    private Candidate readCandidate(String taskId, String turnId) {
        try {
            if (!SelfRun3RunMarker.current(context, taskId)) return null;
            SelfRun3Engine.State state = ledger.load(taskId);
            if (state == null || !turnId.equals(state.turnId()) || !eligible(state)) return null;

            // Re-read once before surfacing a user alert so a just-advanced turn wins the race.
            SelfRun3Engine.State latest = ledger.load(taskId);
            if (latest == null || !turnId.equals(latest.turnId()) || !eligible(latest)) return null;
            return new Candidate(latest.taskId(), latest.turnId());
        } catch (Throwable ignored) {
            // This observer must never alter or pause the SelfRun execution it is watching.
            return null;
        }
    }

    private boolean eligible(SelfRun3Engine.State state) {
        return SelfRun3TurnStallPolicy.shouldAlert(state, SystemClock.elapsedRealtime(),
                System.currentTimeMillis(), currentBootCount());
    }

    private boolean storeReady() {
        return store.active() && !store.paused() && !store.userStopped() && !store.runId().isEmpty();
    }

    private boolean current(long expectedGeneration, String taskId, int turn) {
        return !closed && started && generation == expectedGeneration && storeReady()
                && taskId.equals(store.runId()) && turn == store.turn();
    }

    private void notifyOnce(Candidate candidate) {
        String identity = candidate.taskId + "|" + candidate.turnId;
        SharedPreferences prefs = context.getSharedPreferences(ALERT_PREFS, Context.MODE_PRIVATE);
        if (identity.equals(prefs.getString(LAST_ALERTED_EXECUTION, ""))) return;
        prefs.edit().putString(LAST_ALERTED_EXECUTION, identity).apply();
        NotificationHelper.notifyUser(context, "다음 턴 전환 지연",
                "현재 SelfRun 대화가 125분 이상 다음 턴으로 전환되지 않았습니다.");
    }

    private int currentBootCount() {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Throwable unavailable) {
            return -1;
        }
    }

    @Override public void close() {
        closed = true;
        started = false;
        generation++;
        main.removeCallbacks(rearm);
        if (scheduledCheck != null) {
            main.removeCallbacks(scheduledCheck);
            scheduledCheck = null;
        }
        if (listenerRegistered) {
            projectionPrefs.unregisterOnSharedPreferenceChangeListener(projectionListener);
            listenerRegistered = false;
        }
        io.shutdownNow();
        ledger.close();
    }

    private static final class ScheduledCandidate {
        final String taskId;
        final String turnId;
        final int turn;
        final long remainingMs;
        ScheduledCandidate(String taskId, String turnId, int turn, long remainingMs) {
            this.taskId = taskId;
            this.turnId = turnId;
            this.turn = turn;
            this.remainingMs = remainingMs;
        }
    }

    private static final class Candidate {
        final String taskId;
        final String turnId;
        Candidate(String taskId, String turnId) {
            this.taskId = taskId;
            this.turnId = turnId;
        }
    }
}
