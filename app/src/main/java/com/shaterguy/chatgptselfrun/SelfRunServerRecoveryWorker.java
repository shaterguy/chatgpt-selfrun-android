package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/** Slow correctness watchdog for SERVER mode; it never replaces event-driven FCM delivery. */
public final class SelfRunServerRecoveryWorker extends Worker {
    private static final String UNIQUE_WORK = "selfrun-server-recovery";

    public SelfRunServerRecoveryWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    static void schedule(Context context) {
        Context app = context.getApplicationContext();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                SelfRunServerRecoveryWorker.class,
                15, TimeUnit.MINUTES)
                .setInitialDelay(SelfRunServerWaitPolicy.RECOVERY_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(UNIQUE_WORK);
    }

    @NonNull @Override public Result doWork() {
        Context app = getApplicationContext();
        SelfRunStore store = new SelfRunStore(app);
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(app);
        if (!store.active() || store.paused() || store.userStopped()
                || settings.workMode() != SelfRun3RuntimeSettings.WorkMode.SERVER) {
            return Result.success();
        }
        try {
            app.startService(new Intent(app, SelfRunService.class)
                    .setAction(SelfRunService.ACTION_SERVER_RECOVERY));
            return Result.success();
        } catch (Throwable unavailable) {
            return Result.retry();
        }
    }
}
