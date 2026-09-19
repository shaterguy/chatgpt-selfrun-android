package com.shaterguy.chatgptselfrun;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A task-local serial upload chain survives foreground-service teardown. All failures are auxiliary. */
public final class SelfRunDebugLogUploadWorker extends ListenableWorker {
    // Do not occupy WorkManager's shared worker pool used by execution recovery.
    private static final ExecutorService UPLOADS = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "selfrun-debug-upload"));
    private volatile Future<?> upload;
    public SelfRunDebugLogUploadWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }
    static void schedule(Context context, String taskId) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(SelfRunDebugLogUploadWorker.class)
                .setInputData(new Data.Builder().putString("taskId", taskId).build()).build();
        // KEEP could discard a DONE request arriving as the previous upload completes.
        WorkManager.getInstance(context).enqueueUniqueWork("selfrun-debug-" + taskId,
                ExistingWorkPolicy.APPEND_OR_REPLACE, work);
    }
    @NonNull @Override public ListenableFuture<Result> startWork() {
        return CallbackToFutureAdapter.getFuture(completer -> {
            try {
                upload = UPLOADS.submit(() -> completer.set(performUpload()));
                completer.addCancellationListener(() -> {
                    Future<?> pending = upload;
                    if (pending != null) pending.cancel(true);
                }, Runnable::run);
                if (isStopped()) upload.cancel(true);
            } catch (Throwable ignored) { completer.set(Result.success()); }
            return "selfrun-debug-upload";
        });
    }
    @Override public void onStopped() {
        Future<?> pending = upload;
        if (pending != null) pending.cancel(true);
    }
    private Result performUpload() {
        String taskId = getInputData().getString("taskId");
        try {
            SelfRunDebugLogArchive archive = SelfRunDebugLogSync.archive(getApplicationContext());
            SelfRunDebugLogArchive.Snapshot state = archive.state(taskId);
            if (state == null || state.uploadedSequence >= state.requestedSequence) return Result.success();
            String token = authorize();
            if (token.isEmpty() || isStopped()) return Result.success();
            return archive.upload(taskId, new SelfRunDebugLogDrive(token, this::isStopped))
                    ? Result.success() : Result.retry();
        } catch (Throwable ignored) {
            // No status/phase/error/notification changes, no auth resolution, no network retry loop.
            return Result.success();
        }
    }
    private String authorize() throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> token = new AtomicReference<>("");
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                DriveAuthorization.requestSilently(getApplicationContext(), new DriveAuthorization.Callback() {
                    public void onAuthorized(AuthorizationResult result) {
                        token.set(DriveAuthorization.accessToken(result)); ready.countDown();
                    }
                    public void onResolutionRequired(PendingIntent ignored) { ready.countDown(); }
                    public void onFailure(Throwable ignored) { ready.countDown(); }
                });
            } catch (Throwable ignored) { ready.countDown(); }
        });
        return ready.await(30, TimeUnit.SECONDS) ? token.get() : "";
    }
}
