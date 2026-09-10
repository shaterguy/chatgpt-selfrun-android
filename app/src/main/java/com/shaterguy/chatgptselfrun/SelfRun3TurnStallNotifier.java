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
    static final long CHECK_INTERVAL_MS = 30_000L;
    private static final String PREFS = "selfrun3-turn-stall-alert-v1";
    private static final String LAST_ALERTED_EXECUTION = "last_alerted_execution";

    private final Context context;
    private final SelfRunStore store;
    private final SelfRun3Ledger ledger;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::poll;

    private boolean started;
    private boolean closed;
    private boolean inFlight;

    SelfRun3TurnStallNotifier(Context context, SelfRunStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.ledger = new SelfRun3Ledger(this.context);
    }

    void start() {
        if (closed || started) return;
        started = true;
        main.post(poll);
    }

    private void poll() {
        if (closed || !started) return;
        if (inFlight || !store.active() || store.paused() || store.userStopped()
                || store.runId().isEmpty()) {
            scheduleNext();
            return;
        }

        final String taskId = store.runId();
        inFlight = true;
        io.execute(() -> {
            Candidate candidate = readCandidate(taskId);
            main.post(() -> {
                inFlight = false;
                if (!closed && started && candidate != null && taskId.equals(store.runId())
                        && store.active() && !store.paused() && !store.userStopped()) {
                    notifyOnce(candidate);
                }
                scheduleNext();
            });
        });
    }

    private Candidate readCandidate(String taskId) {
        try {
            if (!SelfRun3RunMarker.current(context, taskId)) return null;
            SelfRun3Engine.State state = ledger.load(taskId);
            if (!eligible(state)) return null;

            // Re-read once before surfacing a user alert so a just-advanced turn wins the race.
            SelfRun3Engine.State latest = ledger.load(taskId);
            if (latest == null || !latest.turnId().equals(state.turnId()) || !eligible(latest)) return null;
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

    private void notifyOnce(Candidate candidate) {
        String identity = candidate.taskId + "|" + candidate.turnId;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    private void scheduleNext() {
        if (closed || !started) return;
        main.removeCallbacks(poll);
        main.postDelayed(poll, CHECK_INTERVAL_MS);
    }

    @Override public void close() {
        closed = true;
        started = false;
        main.removeCallbacks(poll);
        io.shutdownNow();
        ledger.close();
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
