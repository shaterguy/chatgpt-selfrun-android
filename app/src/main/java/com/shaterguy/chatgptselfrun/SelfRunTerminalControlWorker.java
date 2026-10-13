package com.shaterguy.chatgptselfrun;

import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A durable STOP/DONE control sender independent of the foreground service lifetime. */
public final class SelfRunTerminalControlWorker extends Worker {
    private static final String PREFS = BuildConfig.APPLICATION_ID + ".terminal-controls";
    private static final String PREFIX = "terminal:";
    private static final String WORK = "selfrun-terminal-control:";
    private static final String KEY_TASK = "taskId";

    public SelfRunTerminalControlWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasTerminalFence(Context context, String taskId) {
        return taskId != null && !taskId.isEmpty() && prefs(context).contains(PREFIX + taskId);
    }

    static void terminate(Context context, String taskId, SelfRun3Engine.State snapshot,
                          String state, String reason) {
        if (taskId == null || taskId.isEmpty()) return;
        if (!"STOPPED".equals(state) && !"DONE".equals(state))
            throw new IllegalArgumentException("terminal state required");
        try {
            JSONObject record = new JSONObject()
                    .put("schema", "selfrun-terminal-control-v1")
                    .put("task_id", taskId)
                    .put("state", state)
                    .put("reason", reason == null ? "" : reason)
                    .put("event_id", UUID.randomUUID().toString())
                    .put("delivered", false);
            if (snapshot != null && taskId.equals(snapshot.taskId())) {
                record.put("turn_id", snapshot.turnId())
                      .put("request_id", snapshot.requestId())
                      .put("folder_id", snapshot.resource("folderId"))
                      .put("conversation_url", snapshot.resource("conversationUrl"));
            }
            if (!prefs(context).edit().putString(PREFIX + taskId, record.toString()).commit())
                throw new IllegalStateException("terminal intent persistence failed");
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("terminal intent encoding failed", e);
        }
        schedule(context, taskId, ExistingWorkPolicy.REPLACE);
    }

    static void recoverPending(Context context) {
        for (Map.Entry<String, ?> item : prefs(context).getAll().entrySet()) {
            if (!item.getKey().startsWith(PREFIX) || !(item.getValue() instanceof String)) continue;
            try {
                JSONObject entry = new JSONObject((String) item.getValue());
                if (!entry.optBoolean("delivered", false))
                    schedule(context, item.getKey().substring(PREFIX.length()), ExistingWorkPolicy.KEEP);
            } catch (Throwable ignored) { /* Keep the forensic record intact. */ }
        }
    }

    static void explicitResume(Context context, String taskId) {
        if (taskId == null || taskId.isEmpty()) return;
        String original = prefs(context).getString(PREFIX + taskId, "");
        if (!original.isEmpty()) {
            try {
                if (!"STOPPED".equals(new JSONObject(original).optString("state")))
                    throw new IllegalStateException("COMPLETED_TASK_CANNOT_RESUME");
            } catch (org.json.JSONException error) {
                throw new IllegalStateException("TERMINAL_FENCE_INVALID", error);
            }
        }
        if (!prefs(context).edit().remove(PREFIX + taskId).commit())
            throw new IllegalStateException("terminal fence could not be cleared");
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(WORK + taskId);
    }

    private static void schedule(Context context, String taskId, ExistingWorkPolicy policy) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SelfRunTerminalControlWorker.class)
                .setInputData(new Data.Builder().putString(KEY_TASK, taskId).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(WORK + taskId, policy, request);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        String taskId = getInputData().getString(KEY_TASK);
        if (taskId == null || taskId.isEmpty()) return Result.success();
        String original = prefs(context).getString(PREFIX + taskId, "");
        if (original.isEmpty()) return Result.success();
        try {
            JSONObject record = new JSONObject(original);
            if (record.optBoolean("delivered", false)) return Result.success();
            if (!"selfrun-terminal-control-v1".equals(record.optString("schema"))
                    || !taskId.equals(record.optString("task_id"))) return Result.retry();

            // Ledger lookup is for addressing only; never an eligibility check.
            String folder = record.optString("folder_id");
            String turn = record.optString("turn_id");
            String request = record.optString("request_id");
            String conversation = record.optString("conversation_url");
            if (folder.isEmpty() || turn.isEmpty() || request.isEmpty()) {
                try (SelfRun3Ledger ledger = new SelfRun3Ledger(context)) {
                    SelfRun3Engine.State snapshot = ledger.load(taskId);
                    if (snapshot != null) {
                        if (folder.isEmpty()) folder = snapshot.resource("folderId");
                        if (turn.isEmpty()) turn = snapshot.turnId();
                        if (request.isEmpty()) request = snapshot.requestId();
                        if (conversation.isEmpty()) conversation = snapshot.resource("conversationUrl");
                    }
                }
            }
            if (folder.isEmpty()) {
                // No task folder exists, so no Drive control could have been created.
                // The STOP/DONE fence persists, but the network worker may exit.
                markDelivered(context, taskId, original);
                return Result.success();
            }
            if (turn.isEmpty() || request.isEmpty()) return Result.retry();

            String token = authorize();
            if (token.isEmpty()) return Result.retry();
            if (isStopped() || !isCurrent(context, taskId, original)) return Result.success();
            DriveApiClient api = new DriveApiClient();
            DriveApiClient.Metadata found = api.findTaskControlFile(token, taskId, folder);
            boolean create = found == null;
            String id = create ? api.generateFileId(token) : found.id;
            JSONObject prior = create ? null : api.readServerDispatchFile(token, id);
            if (prior != null && (!"selfrun-task-control-v1".equals(prior.optString("schema"))
                    || !taskId.equals(prior.optString("task_id")))) return Result.retry();
            if (isStopped() || !isCurrent(context, taskId, original)) return Result.success();

            String terminalState = record.getString("state");
            if (prior != null && terminalState.equals(prior.optString("state"))
                    && turn.equals(prior.optString("turn_id"))
                    && request.equals(prior.optString("request_id"))) {
                markDelivered(context, taskId, original);
                return Result.success();
            }
            long nextEpoch = prior == null ? 1L : Math.max(0L, prior.optLong("control_epoch")) + 1L;
            JSONObject body = new JSONObject()
                    .put("schema", "selfrun-task-control-v1")
                    .put("task_id", taskId)
                    .put("control_epoch", nextEpoch)
                    .put("state", terminalState)
                    .put("turn_id", turn)
                    .put("request_id", request)
                    .put("reason", record.optString("reason"))
                    .put("conversation_url", conversation)
                    .put("updated_at_ms", System.currentTimeMillis());
            if (create) api.createTaskControlFile(token, id, taskId, folder, body);
            else api.writeServerDispatchFile(token, id, body);
            JSONObject readback = api.readServerDispatchFile(token, id);
            if (!"selfrun-task-control-v1".equals(readback.optString("schema"))
                    || !taskId.equals(readback.optString("task_id"))
                    || !terminalState.equals(readback.optString("state"))
                    || readback.optLong("control_epoch", -1L) != nextEpoch
                    || !turn.equals(readback.optString("turn_id"))
                    || !request.equals(readback.optString("request_id"))) return Result.retry();
            markDelivered(context, taskId, original);
            return Result.success();
        } catch (Throwable error) {
            return Result.retry();
        }
    }

    private static boolean isCurrent(Context context, String taskId, String original) {
        return original.equals(prefs(context).getString(PREFIX + taskId, ""));
    }

    private static void markDelivered(Context context, String taskId, String original) throws Exception {
        if (!isCurrent(context, taskId, original)) return;
        JSONObject updated = new JSONObject(original).put("delivered", true);
        if (!prefs(context).edit().putString(PREFIX + taskId, updated.toString()).commit())
            throw new IllegalStateException("terminal acknowledgement persistence failed");
        // The fence remains until the user explicitly resumes this exact task.
    }

    private String authorize() throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> token = new AtomicReference<>("");
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                DriveAuthorization.requestSilently(getApplicationContext(), new DriveAuthorization.Callback() {
                    @Override public void onAuthorized(AuthorizationResult result) {
                        token.set(DriveAuthorization.accessToken(result));
                        ready.countDown();
                    }
                    @Override public void onResolutionRequired(PendingIntent ignored) { ready.countDown(); }
                    @Override public void onFailure(Throwable ignored) { ready.countDown(); }
                });
            } catch (Throwable ignored) { ready.countDown(); }
        });
        return ready.await(30, TimeUnit.SECONDS) ? token.get() : "";
    }
}
