package com.shaterguy.chatgptselfrun;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Flushes persisted RECEIVED/PROCESSED ACKs only while a network is available. */
public final class SelfRunPushAckWorker extends Worker {
    private static final String UNIQUE_WORK = "selfrun-push-ack-flush";

    public SelfRunPushAckWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    static void schedule(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SelfRunPushAckWorker.class)
                    .setConstraints(constraints)
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request);
        } catch (Throwable ignored) { }
    }

    @NonNull @Override public Result doWork() {
        SelfRunPushGatewayClient gateway = new SelfRunPushGatewayClient();
        boolean retry = false;
        for (SelfRunPushAckOutbox.Entry entry : SelfRunPushAckOutbox.pending(getApplicationContext())) {
            try {
                if (gateway.sendAck(entry.body).optBoolean("ok", false)) {
                    SelfRunPushAckOutbox.markDelivered(getApplicationContext(), entry.key);
                } else {
                    retry = true;
                }
            } catch (Throwable error) {
                retry = true;
            }
        }
        return retry ? Result.retry() : Result.success();
    }
}
