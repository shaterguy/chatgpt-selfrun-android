package com.shaterguy.chatgptselfrun;

import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;

/** Durable one-shot outbox. Only DONE/STOP events enqueue work; no polling service. */
public final class SelfRunTerminalControlWorker extends Worker {
    private static final String PREFS = "selfrun_terminal_control_outbox";
    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static String workName(String task) { return "selfrun-terminal-control:" + task; }

    public SelfRunTerminalControlWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }
    static void enqueue(Context context, SelfRun3Engine.State snapshot, String state, String reason) {
        enqueueAfter(context, snapshot, state, reason, Runnable::run);
    }
    static void enqueueAfter(Context context, SelfRun3Engine.State snapshot, String state, String reason, Executor pendingWrites) {
        if (snapshot == null || snapshot.resource("folderId").isEmpty()) return;
        try {
            var entry = new SelfRunTerminalControlDelivery.Entry(snapshot.taskId(), snapshot.turnId(),
                    snapshot.requestId(), snapshot.resource("folderId"), snapshot.resource("conversationUrl"), state, reason);
            JSONObject body = entry.json();
            SharedPreferences prefs = preferences(context);
            String prior = prefs.getString(snapshot.taskId(), "");
            if (!prior.isEmpty()) {
                JSONObject pending = new JSONObject(prior);
                if (pending.getJSONObject("entry").toString().equals(body.toString())) {
                    scheduleAfter(pendingWrites, () -> schedule(context, snapshot.taskId(), prior, false)); return;
                }
            }
            String value = new JSONObject().put("id", UUID.randomUUID().toString()).put("entry", body).toString();
            // Persist before closing the adapter/service. WorkManager's own durable
            // request also carries the body, so it can deliver after process death.
            prefs.edit().putString(snapshot.taskId(), value).commit();
            scheduleAfter(pendingWrites, () -> schedule(context, snapshot.taskId(), value, true));
        } catch (Exception error) {
            android.util.Log.w("SelfRunTerminalControl", "terminal delivery scheduling will be recovered", error);
        }
    }
    private static void scheduleAfter(Executor executor, Runnable schedule) {
        try { executor.execute(schedule); }
        catch (java.util.concurrent.RejectedExecutionException closed) { schedule.run(); }
    }
    private static void schedule(Context context, String task, String value, boolean replace) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SelfRunTerminalControlWorker.class)
                .setInputData(new Data.Builder().putString("task", task).putString("body", value).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(workName(task), replace ? ExistingWorkPolicy.REPLACE : ExistingWorkPolicy.KEEP, request);
    }
    static void recover(Context context) {
        for (var entry : preferences(context).getAll().entrySet()) {
            if (entry.getValue() instanceof String value) schedule(context, entry.getKey(), value, true);
        }
    }
    static void cancel(Context context, String task) {
        preferences(context).edit().remove(task).commit();
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(workName(task));
    }
    @NonNull @Override public Result doWork() {
        Context app = getApplicationContext();
        String task = getInputData().getString("task"), value = getInputData().getString("body");
        if (task == null || value == null) return Result.success();
        SharedPreferences prefs = preferences(app);
        try {
            String pending = prefs.getString(task, "");
            if (!value.equals(pending)) return Result.success();
            var entry = SelfRunTerminalControlDelivery.Entry.from(new JSONObject(value).getJSONObject("entry"));
            DriveApiClient api = new DriveApiClient();
            SelfRunTerminalControlDelivery.deliver(entry, new SelfRunTerminalControlDelivery.Remote() {
                String fileId = "";
                boolean create;
                public String authorize() throws Exception { return freshToken(app); }
                public JSONObject read(SelfRunTerminalControlDelivery.Entry e, String token) throws Exception {
                    if (fileId.isEmpty()) {
                        DriveApiClient.Metadata found = api.findTaskControlFile(token, e.taskId(), e.folderId());
                        if (found == null) { fileId = api.generateFileId(token); create = true; return null; }
                        fileId = found.id;
                    }
                    return api.readServerDispatchFile(token, fileId);
                }
                public void write(SelfRunTerminalControlDelivery.Entry e, String token, JSONObject body) throws Exception {
                    if (create) { api.createTaskControlFile(token, fileId, e.taskId(), e.folderId(), body); create = false; }
                    else api.writeServerDispatchFile(token, fileId, body);
                }
            }, () -> !isStopped() && value.equals(prefs.getString(task, "")));
            if (!isStopped() && value.equals(prefs.getString(task, ""))) prefs.edit().remove(task).commit();
            return Result.success();
        } catch (Exception unavailable) {
            return isStopped() ? Result.success() : Result.retry();
        }
    }
    private static String freshToken(Context app) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> token = new AtomicReference<>("");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        DriveAuthorization.requestSilently(app, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) { token.set(DriveAuthorization.accessToken(result)); completed.countDown(); }
            @Override public void onResolutionRequired(PendingIntent intent) { failure.set(new IllegalStateException("authorization pending")); completed.countDown(); }
            @Override public void onFailure(Throwable error) { failure.set(error); completed.countDown(); }
        });
        if (!completed.await(45, TimeUnit.SECONDS)) throw new java.io.IOException("authorization retry pending");
        if (failure.get() != null || token.get().isEmpty()) throw new java.io.IOException("authorization retry pending", failure.get());
        return token.get();
    }
}
