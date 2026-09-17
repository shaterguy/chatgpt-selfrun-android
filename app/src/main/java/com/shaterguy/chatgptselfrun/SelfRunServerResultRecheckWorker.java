package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Durable, bounded follow-up reads for SERVER results that were signaled before committed=true. */
public final class SelfRunServerResultRecheckWorker extends Worker {
    private static final String PREFS = BuildConfig.APPLICATION_ID + ".selfrun-server-result-recheck";
    private static final String KEY_TURN_ID = "turnId";
    private static final String KEY_ATTEMPT = "attempt";
    private static final String UNIQUE_PREFIX = "selfrun-server-result-recheck:";
    private static final String TAG_PREFIX = "selfrun-server-result-recheck-tag:";

    public SelfRunServerResultRecheckWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    static boolean scheduleInitial(Context context, String turnId) {
        return scheduleStart(context, turnId);
    }

    static boolean scheduleNext(Context context, String turnId, int completedAttempt) {
        return scheduleExpected(context, turnId, completedAttempt, completedAttempt + 1);
    }

    static boolean isCurrent(Context context, String turnId, int attempt) {
        if (turnId == null || turnId.isEmpty() || attempt < 0) return false;
        return prefs(context).getInt(turnId, -1) == attempt;
    }

    static void clear(Context context, String turnId) {
        if (turnId == null || turnId.isEmpty()) return;
        Context app = context.getApplicationContext();
        prefs(app).edit().remove(turnId).apply();
        WorkManager.getInstance(app).cancelAllWorkByTag(tag(turnId));
    }

    static void clearAll(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = prefs(app);
        Map<String, ?> entries = prefs.getAll();
        for (String turnId : entries.keySet()) {
            WorkManager.getInstance(app).cancelAllWorkByTag(tag(turnId));
        }
        prefs.edit().clear().apply();
    }

    private static synchronized boolean scheduleStart(Context context, String turnId) {
        if (turnId == null || turnId.isEmpty()) return false;
        Context app = context.getApplicationContext();
        int currentAttempt = prefs(app).getInt(turnId, -1);
        if (!SelfRunServerWaitPolicy.mayStartServerResultRecheck(currentAttempt, true)) return false;
        return scheduleExpected(app, turnId, currentAttempt, 0);
    }

    private static synchronized boolean scheduleExpected(Context context, String turnId,
                                                         int expectedAttempt, int nextAttempt) {
        if (turnId == null || turnId.isEmpty()) return false;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = prefs(app);
        if (prefs.getInt(turnId, -1) != expectedAttempt) return false;
        long delayMs = SelfRunServerWaitPolicy.serverResultRecheckDelayMs(nextAttempt);
        if (delayMs < 0L) {
            prefs.edit().putInt(turnId, SelfRunServerWaitPolicy.serverResultRecheckAttemptCount()).apply();
            return false;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        Data input = new Data.Builder()
                .putString(KEY_TURN_ID, turnId)
                .putInt(KEY_ATTEMPT, nextAttempt)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SelfRunServerResultRecheckWorker.class)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setInputData(input)
                .addTag(tag(turnId))
                .build();
        WorkManager.getInstance(app).enqueueUniqueWork(
                UNIQUE_PREFIX + turnId + ":" + nextAttempt, ExistingWorkPolicy.KEEP, request);
        prefs.edit().putInt(turnId, nextAttempt).apply();
        return true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String tag(String turnId) {
        return TAG_PREFIX + turnId;
    }

    @NonNull @Override public Result doWork() {
        Context app = getApplicationContext();
        String turnId = getInputData().getString(KEY_TURN_ID);
        int attempt = getInputData().getInt(KEY_ATTEMPT, -1);
        if (!isCurrent(app, turnId, attempt)) return Result.success();

        SelfRunStore store = new SelfRunStore(app);
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(app);
        if (!store.active() || store.paused() || store.userStopped()
                || settings.workMode() != SelfRun3RuntimeSettings.WorkMode.SERVER) {
            clear(app, turnId);
            return Result.success();
        }
        try {
            app.startService(new Intent(app, SelfRunService.class)
                    .setAction(SelfRunService.ACTION_SERVER_RESULT_RECHECK)
                    .putExtra(SelfRunService.EXTRA_SERVER_RESULT_RECHECK_TURN_ID, turnId)
                    .putExtra(SelfRunService.EXTRA_SERVER_RESULT_RECHECK_ATTEMPT, attempt));
            return Result.success();
        } catch (Throwable unavailable) {
            return Result.retry();
        }
    }
}
