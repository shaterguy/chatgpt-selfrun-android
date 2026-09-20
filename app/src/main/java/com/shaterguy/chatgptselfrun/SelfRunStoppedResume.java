package com.shaterguy.chatgptselfrun;

import android.app.Service;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import org.json.JSONObject;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit user-only recovery path for a preserved SelfRun 3 task stopped from the UI. */
final class SelfRunStoppedResume {
    private static final String PREFS = "selfrun3_stopped_resume";
    private static final String KEY_TARGET_RUN_ID = "targetRunId";
    private static final String KEY_OPERATION = "operationId";

    private final Service service;
    private final SelfRunStore store;
    private final SelfRunRunLog runLog;
    private final SelfRun3Coordinator coordinator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile boolean closed;

    SelfRunStoppedResume(Service service, SelfRunStore store, SelfRunRunLog runLog,
                         SelfRun3Coordinator coordinator) {
        this.service = service;
        this.store = store;
        this.runLog = runLog;
        this.coordinator = coordinator;
    }

    static boolean isEligible(JSONObject item) {
        return item != null && item.optBoolean("userStopped", false)
                && SelfRunProtocolRules.validRunId(item.optString("runId"));
    }

    static boolean request(Context context, String runId) {
        Context app = context.getApplicationContext();
        if (!SelfRunProtocolRules.validRunId(runId)) return false;
        SelfRunHistoryStore history = new SelfRunHistoryStore(app);
        JSONObject item = history.get(runId);
        if (!isEligible(item)) return false;
        SelfRunStore store = new SelfRunStore(app);
        if (store.active() && (!runId.equals(store.runId()) || !store.userStopped())) {
            Toast.makeText(context, "다른 SelfRun 작업이 실행 중입니다.", Toast.LENGTH_LONG).show();
            return false;
        }
        SharedPreferences prefs = prefs(app);
        synchronized (SelfRunStoppedResume.class) {
            String pending = prefs.getString(KEY_TARGET_RUN_ID, "");
            if (!pending.isEmpty() && !runId.equals(pending)) {
                Toast.makeText(context, "다른 중지 작업의 재개 요청이 처리 중입니다.", Toast.LENGTH_LONG).show();
                return false;
            }
            if (pending.isEmpty() && !prefs.edit().putString(KEY_TARGET_RUN_ID, runId)
                    .putString(KEY_OPERATION, UUID.randomUUID().toString()).commit()) return false;
        }
        Intent intent = new Intent(app, SelfRunService.class).setAction(SelfRunService.ACTION_RESUME_STOPPED);
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent); else app.startService(intent);
        Toast.makeText(context, "중지한 작업을 저장된 상태에서 다시 실행합니다.", Toast.LENGTH_LONG).show();
        return true;
    }

    boolean hasPending() { return !pendingTarget().isEmpty(); }

    void resumePending() {
        final String target = pendingTarget();
        if (closed || target.isEmpty() || !inFlight.compareAndSet(false, true)) return;
        String saved = prefs(service).getString(KEY_OPERATION, "");
        if (saved.isEmpty()) {
            saved = UUID.randomUUID().toString();
            if (!prefs(service).edit().putString(KEY_OPERATION, saved).commit()) {
                fail(target, new IllegalStateException("RESUME_INTENT_WRITE_FAILED"));
                inFlight.set(false);
                return;
            }
        }
        final String operation = saved;
        DriveAuthorization.requestSilently(service, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                String token = DriveAuthorization.accessToken(result);
                if (token.isEmpty()) { rejected(new IllegalStateException("DRIVE_TOKEN_EMPTY")); return; }
                new Thread(() -> resumeInBackground(target, operation, token), "SelfRun3StoppedResume").start();
            }
            @Override public void onResolutionRequired(PendingIntent intent) {
                rejected(new IllegalStateException("DRIVE_AUTH_REQUIRED"));
            }
            @Override public void onFailure(Throwable error) { rejected(error); }
            private void rejected(Throwable error) {
                if (current(target, operation)) fail(target, error);
                inFlight.set(false);
            }
        });
    }

    private void resumeInBackground(String target, String operation, String token) {
        try (SelfRun3Ledger ledger = new SelfRun3Ledger(service)) {
            if (!current(target, operation)) { inFlight.set(false); return; }
            JSONObject historyItem = new SelfRunHistoryStore(service).get(target);
            boolean projectionAlreadyActive = store.active() && target.equals(store.runId()) && !store.userStopped();
            if (!projectionAlreadyActive && !isEligible(historyItem)) throw new IllegalStateException("STOPPED_HISTORY_REQUIRED");
            if (store.active() && !target.equals(store.runId())) throw new IllegalStateException("ANOTHER_RUN_ACTIVE");

            SelfRun3Engine.State state = ledger.load(target);
            if (state == null || !target.equals(state.taskId())) throw new IllegalStateException("STOPPED_LEDGER_MISSING");

            JSONObject config = state.config();
            if (!config.optString("accountId").equals(store.driveAccountId())
                    || !config.optString("baseFolderId").equals(store.driveRunsBaseFolderId())) {
                throw new IllegalStateException("DRIVE_BINDING_MISMATCH");
            }

            DriveApiClient api = new DriveApiClient();
            if (!config.optString("accountId").equals(api.getAccountPermissionId(token)))
                throw new IllegalStateException("DRIVE_BINDING_MISMATCH");
            if (!state.resource("folderId").isEmpty()) {
                DriveApiClient.Metadata folder = api.getMetadata(token, state.resource("folderId"));
                if (folder.trashed || !DriveApiClient.MIME_FOLDER.equals(folder.mimeType)
                        || !config.optString("baseFolderId").equals(folder.parentId))
                    throw new IllegalStateException("DRIVE_BINDING_MISMATCH");
            }
            final String expectedTurn = state.turnId();
            main.post(() -> {
                try (SelfRun3Ledger finalLedger = new SelfRun3Ledger(service)) {
                    synchronized (SelfRunStore.RUN_STATE_LOCK) {
                    if (!current(target, operation)) return;
                    if (store.active() && !target.equals(store.runId())) throw new IllegalStateException("ANOTHER_RUN_ACTIVE");
                    if (!config.optString("accountId").equals(store.driveAccountId())
                            || !config.optString("baseFolderId").equals(store.driveRunsBaseFolderId()))
                        throw new IllegalStateException("DRIVE_BINDING_MISMATCH");
                    SelfRun3Engine.State ready = finalLedger.apply(new SelfRun3Engine.Event(operation,
                            SelfRun3Engine.Kind.RESUME_STOPPED, target, expectedTurn, new JSONObject()));
                    if (ready.flag("taskStopped") || !operation.equals(ready.text("stoppedResumeOperation")))
                        throw new IllegalStateException("STOPPED_LEDGER_NOT_RESUMED");
                    if (!store.active() || store.userStopped()) restoreProjection(target, ready);
                    store.clearLastError();
                    if (!SelfRun3RunMarker.mark(service, target)) throw new IllegalStateException("RUN_MARKER_WRITE_FAILED");
                    store.setTurn(ready.turn());
                    runLog.record(store, "V3_STOPPED_RUN_RESUMED",
                            "turn=" + ready.turn() + ";stage=" + ready.stage().name()
                                    + ";resultDocumentId=" + ready.resource("resultDocumentId"));
                    coordinator.onStart(SelfRunService.ACTION_RUN);
                    clearPending(target);
                    }
                } catch (Throwable error) {
                    // If publication failed after the ledger transaction, preserve a retryable
                    // stop instead of leaving an unstartable, half-restored task.
                    try (SelfRun3Ledger rollback = new SelfRun3Ledger(service)) {
                        SelfRun3Engine.State current = rollback.load(target);
                        if (current != null && operation.equals(current.text("stoppedResumeOperation"))) {
                            rollback.apply(new SelfRun3Engine.Event(UUID.randomUUID().toString(),
                                    SelfRun3Engine.Kind.STOP, target, current.turnId(), new JSONObject()));
                            if (target.equals(store.runId())) store.stopByUser();
                        }
                    } catch (Throwable ignored) { }
                    fail(target, error);
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (Throwable error) {
            main.post(() -> {
                if (current(target, operation)) fail(target, error);
                inFlight.set(false);
            });
        }
    }

    // Called on the service main thread before STOP, and on destruction. A cancelled reader
    // can never publish its snapshot or reactivate the projection.
    void cancelPending() { clearPending(pendingTarget()); }
    void close() { closed = true; }

    private boolean current(String target, String operation) {
        return !closed && target.equals(pendingTarget())
                && operation.equals(prefs(service).getString(KEY_OPERATION, ""));
    }

    private void restoreProjection(String target, SelfRun3Engine.State state) {
        JSONObject config = state.config();
        String mode = config.optString("mode");
        String projectUrl = config.optString("projectUrl");
        String requirement = config.optString("requirement");
        String taskMode = state.taskMode();
        if (SelfRunStore.MODE_WORK.equals(mode)) {
            String reasoning = config.optString("reasoning", config.optString("chatBootstrap"));
            store.startWork(target, projectUrl, requirement, new ArrayList<>(),
                    config.optString("model"), reasoning, taskMode);
        } else if (SelfRunStore.MODE_CHAT.equals(mode)) {
            store.start(target, mode, projectUrl, requirement, new ArrayList<>(), taskMode);
        } else {
            throw new IllegalStateException("EXECUTION_MODE_INVALID");
        }
        store.setTurn(state.turn());
    }

    private void fail(String target, Throwable error) {
        clearPending(target);
        String code = error instanceof IllegalStateException && error.getMessage() != null
                ? error.getMessage() : "STOPPED_RESUME_FAILED";
        if (target.equals(store.runId())) store.setLastError("V3_STOPPED_RESUME_FAILED", code);
        runLog.record(store, "V3_STOPPED_RESUME_FAILED", "run=" + target + ";reason=" + code);
        NotificationHelper.notifyUser(service, "작업 재개 실패",
                "중지된 SelfRun 작업을 안전하게 재개하지 못했습니다: " + code);
        service.stopSelf();
    }

    private String pendingTarget() { return prefs(service).getString(KEY_TARGET_RUN_ID, ""); }

    private void clearPending(String target) {
        SharedPreferences p = prefs(service);
        synchronized (SelfRunStoppedResume.class) {
            if (target.equals(p.getString(KEY_TARGET_RUN_ID, "")))
                p.edit().remove(KEY_TARGET_RUN_ID).remove(KEY_OPERATION).commit();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
