package com.shaterguy.chatgptselfrun;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit user-only recovery path for a preserved SelfRun 3 task stopped from the UI. */
final class SelfRunStoppedResume {
    private static final String PREFS = "selfrun3_stopped_resume";
    private static final String KEY_TARGET_RUN_ID = "targetRunId";

    private final Service service;
    private final SelfRunStore store;
    private final SelfRunRunLog runLog;
    private final SelfRun3Coordinator coordinator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

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
            if (!prefs.edit().putString(KEY_TARGET_RUN_ID, runId).commit()) return false;
        }
        Intent intent = new Intent(app, SelfRunService.class).setAction(SelfRunService.ACTION_RESUME_STOPPED);
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent); else app.startService(intent);
        Toast.makeText(context, "중지된 작업을 기존 진행 지점에서 재개합니다.", Toast.LENGTH_SHORT).show();
        return true;
    }

    boolean hasPending() { return !pendingTarget().isEmpty(); }

    void resumePending() {
        final String target = pendingTarget();
        if (target.isEmpty() || !inFlight.compareAndSet(false, true)) return;
        new Thread(() -> resumeInBackground(target), "SelfRun3StoppedResume").start();
    }

    private void resumeInBackground(String target) {
        try {
            JSONObject historyItem = new SelfRunHistoryStore(service).get(target);
            boolean projectionAlreadyActive = store.active() && target.equals(store.runId()) && !store.userStopped();
            if (!projectionAlreadyActive && !isEligible(historyItem)) throw new IllegalStateException("STOPPED_HISTORY_REQUIRED");
            if (store.active() && !target.equals(store.runId())) throw new IllegalStateException("ANOTHER_RUN_ACTIVE");

            SelfRun3Ledger ledger = new SelfRun3Ledger(service);
            SelfRun3Engine.State state = ledger.load(target);
            if (state == null || !target.equals(state.taskId())) throw new IllegalStateException("STOPPED_LEDGER_MISSING");
            if (SelfRun3Engine.Stage.DONE.name().equals(state.text("stage"))) throw new IllegalStateException("TASK_ALREADY_DONE");

            JSONObject config = state.config();
            if (!config.optString("accountId").equals(store.driveAccountId())
                    || !config.optString("baseFolderId").equals(store.driveRunsBaseFolderId())) {
                throw new IllegalStateException("DRIVE_BINDING_MISMATCH");
            }

            SelfRun3Engine.State resumed = state;
            if (state.flag("taskStopped")) {
                String eventId = "resume-stopped:" + UUID.nameUUIDFromBytes(
                        target.getBytes(StandardCharsets.UTF_8));
                resumed = ledger.apply(new SelfRun3Engine.Event(eventId,
                        SelfRun3Engine.Kind.RESUME_STOPPED, target, state.turnId(), new JSONObject()));
            }
            if (resumed.flag("taskStopped") || resumed.stage() == SelfRun3Engine.Stage.STOPPED) {
                throw new IllegalStateException("STOPPED_LEDGER_NOT_RESUMED");
            }

            if (!projectionAlreadyActive) restoreProjection(target, resumed);
            if (!SelfRun3RunMarker.mark(service, target)) throw new IllegalStateException("RUN_MARKER_WRITE_FAILED");
            final SelfRun3Engine.State ready = resumed;
            main.post(() -> {
                try {
                    store.setTurn(ready.turn());
                    runLog.record(store, "V3_STOPPED_RUN_RESUMED",
                            "turn=" + ready.turn() + ";stage=" + ready.stage().name()
                                    + ";resultDocumentId=" + ready.resource("resultDocumentId"));
                    coordinator.onStart(SelfRunService.ACTION_RUN);
                    clearPending(target);
                } catch (Throwable error) {
                    fail(target, error);
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (Throwable error) {
            main.post(() -> {
                fail(target, error);
                inFlight.set(false);
            });
        }
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

    private static void clearPending(String target) {
        SharedPreferences prefs = prefsHolder(target);
    }

    private static SharedPreferences prefsHolder(String ignored) { return null; }

    private void clearPending(String target) {
        SharedPreferences p = prefs(service);
        synchronized (SelfRunStoppedResume.class) {
            if (target.equals(p.getString(KEY_TARGET_RUN_ID, ""))) p.edit().remove(KEY_TARGET_RUN_ID).commit();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
