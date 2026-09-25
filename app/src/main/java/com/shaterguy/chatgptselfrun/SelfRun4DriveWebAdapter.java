package com.shaterguy.chatgptselfrun;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import com.google.android.gms.auth.api.identity.AuthorizationResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SelfRun 4 browser transport. The app keeps the V3 task/turn state machine and
 * delegates only ChatGPT conversation execution to the Termux server through Drive.
 */
final class SelfRun4DriveWebAdapter {
    private static final String SCHEMA = "selfrun-server-dispatch-v1";
    private static final String CONTROL_SCHEMA = "selfrun-task-control-v1";
    private static final long POLL_MS = 1_000L;
    private static final long LATE_START_GRACE_MS = 30_000L;

    private final Context context;
    private final SelfRun3WebAdapter.Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final DriveApiClient api = new DriveApiClient();
    private final SelfRun3RuntimeSettings runtimeSettings;
    private final SelfRunRunLog runLog;
    private final SelfRunStore runStore;

    private SelfRun3Engine.State state;
    private boolean closed;
    private boolean preparing;
    private boolean preparedNotified;
    private boolean submitRequested;
    private boolean started;
    private boolean lateStartGraceArmed;
    private long preparationAttempt;
    private long prepareStarted;
    private long prepareTimeoutMs;
    private int generation;
    private String accessToken = "";
    private String dispatchFileId = "";
    private JSONObject dispatchBody;
    private String controlTaskId = "";
    private String controlFileId = "";
    private long controlEpoch;
    private String lastControlPublishedKey = "";

    SelfRun4DriveWebAdapter(Context context, SelfRun3WebAdapter.Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.runtimeSettings = new SelfRun3RuntimeSettings(this.context);
        this.runLog = new SelfRunRunLog(this.context);
        this.runStore = new SelfRunStore(this.context);
    }

    void syncControlState(SelfRun3Engine.State snapshot) {
        requireMain();
        if (snapshot == null) return;
        String control = switch (snapshot.stage()) {
            case WAITING_USER_INTERVENTION -> "WAITING_USER_INTERVENTION";
            case PAUSED -> "PAUSED";
            case STOPPED -> "STOPPED";
            case DONE -> "DONE";
            default -> "RUNNING";
        };
        publishControlState(snapshot, control, "STAGE_" + snapshot.stage().name());
    }

    void publishControlState(String control, String reason) {
        requireMain();
        publishControlState(state, control, reason);
    }

    private void publishControlState(SelfRun3Engine.State snapshot, String control, String reason) {
        if (snapshot == null || accessToken.isEmpty() || io.isShutdown()) return;
        String token = accessToken;
        try {
            io.execute(() -> {
                try {
                    writeTaskControl(token, snapshot, control, reason);
                } catch (Throwable error) {
                    trace("V4_TASK_CONTROL_WRITE_FAILED", "state=" + control
                            + ";error=" + error.getClass().getSimpleName());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    private static String controlKey(SelfRun3Engine.State snapshot, String control) {
        return control + "|" + snapshot.turnId() + "|" + snapshot.requestId()
                + "|" + snapshot.resource("conversationUrl");
    }

    private void writeTaskControl(String token, SelfRun3Engine.State snapshot,
                                  String control, String reason) throws Exception {
        String taskId = snapshot.taskId();
        String parentId = snapshot.config().optString("baseFolderId");
        if (taskId.isEmpty() || parentId.isEmpty()) return;
        if (!taskId.equals(controlTaskId)) {
            controlTaskId = taskId;
            controlFileId = "";
            controlEpoch = 0L;
            lastControlPublishedKey = "";
        }
        String key = controlKey(snapshot, control);
        if (key.equals(lastControlPublishedKey)) return;

        boolean create = false;
        if (controlFileId.isEmpty()) {
            DriveApiClient.Metadata found = api.findTaskControlFile(token, taskId, parentId);
            if (found == null) {
                controlFileId = api.generateFileId(token);
                create = true;
            } else {
                controlFileId = found.id;
                JSONObject current = api.readServerDispatchFile(token, controlFileId);
                if (!CONTROL_SCHEMA.equals(current.optString("schema"))
                        || !taskId.equals(current.optString("task_id"))) {
                    throw new IllegalStateException("task control content mismatch");
                }
                controlEpoch = Math.max(controlEpoch, current.optLong("control_epoch", 0L));
            }
        }

        long nextEpoch = controlEpoch + 1L;
        JSONObject body = new JSONObject()
                .put("schema", CONTROL_SCHEMA)
                .put("task_id", taskId)
                .put("control_epoch", nextEpoch)
                .put("state", control)
                .put("turn_id", snapshot.turnId())
                .put("request_id", snapshot.requestId())
                .put("reason", reason == null ? "" : reason)
                .put("conversation_url", snapshot.resource("conversationUrl"))
                .put("updated_at_ms", System.currentTimeMillis());
        if (create) api.createTaskControlFile(token, controlFileId, taskId, parentId, body);
        else api.writeServerDispatchFile(token, controlFileId, body);

        JSONObject readback = api.readServerDispatchFile(token, controlFileId);
        if (!CONTROL_SCHEMA.equals(readback.optString("schema"))
                || !taskId.equals(readback.optString("task_id"))
                || readback.optLong("control_epoch", -1L) != nextEpoch
                || !control.equals(readback.optString("state"))) {
            throw new IllegalStateException("task control readback mismatch");
        }
        controlEpoch = nextEpoch;
        lastControlPublishedKey = key;
        trace("V4_TASK_CONTROL", "state=" + control + ";epoch=" + nextEpoch);
    }

    void prepare(SelfRun3Engine.State next) {
        requireMain();
        boolean newRequest = state == null || !state.requestId().equals(next.requestId());
        state = next;
        closed = false;
        if (newRequest) preparationAttempt = 0L;
        beginAttempt();
    }

    private void beginAttempt() {
        requireMain();
        cancelLocalAttempt(false);
        if (state == null) {
            fail("SUBMISSION_STATE_INVALID");
            return;
        }
        boolean recoveringExisting = state.stage() == SelfRun3Engine.Stage.DISPATCHING
                && state.flag("sendClaimed") && state.resource("conversationUrl").isEmpty();
        if (!recoveringExisting && state.stage() != SelfRun3Engine.Stage.READY) {
            fail("SUBMISSION_STATE_INVALID");
            return;
        }
        generation++;
        preparing = true;
        preparedNotified = recoveringExisting;
        submitRequested = recoveringExisting;
        started = false;
        lateStartGraceArmed = false;
        dispatchFileId = "";
        dispatchBody = null;
        prepareStarted = SystemClock.elapsedRealtime();
        long successorRemaining = SelfRun3SuccessorTransitionPolicy.remainingMs(
                state, prepareStarted, System.currentTimeMillis(), currentBootCount());
        prepareTimeoutMs = successorRemaining >= 0L
                ? Math.max(1L, successorRemaining) : runtimeSettings.webPreparationMs();
        long attempt = ++preparationAttempt;
        int expectedGeneration = generation;
        String request = state.requestId();
        trace("V4_SERVER_PREPARATION_WATCHDOG",
                "status=" + (recoveringExisting ? "reconcile" : "armed")
                        + ";attempt=" + attempt + ";timeoutMs=" + prepareTimeoutMs);
        main.postDelayed(() -> {
            if (!active(expectedGeneration, request) || !preparing || attempt != preparationAttempt) return;
            expireAttempt();
        }, Math.max(1L, prepareTimeoutMs));
        if (recoveringExisting) authorizeAndRecover(expectedGeneration, request);
        else authorizeAndCreate(expectedGeneration, request, attempt);
    }

    private void authorizeAndRecover(int expectedGeneration, String request) {
        DriveAuthorization.requestSilently(context, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                if (!active(expectedGeneration, request)) return;
                String token = DriveAuthorization.accessToken(result);
                if (token.isEmpty()) {
                    fail("DRIVE_TOKEN_EMPTY");
                    return;
                }
                accessToken = token;
                recoverDispatch(expectedGeneration, request, token);
            }

            @Override public void onResolutionRequired(PendingIntent pendingIntent) {
                if (active(expectedGeneration, request)) fail("AUTH_REQUIRED");
            }

            @Override public void onFailure(Throwable error) {
                if (active(expectedGeneration, request)) fail("DRIVE_AUTH_FAILED");
            }
        });
    }

    private void recoverDispatch(int expectedGeneration, String request, String token) {
        SelfRun3Engine.State snapshot = state;
        io.execute(() -> {
            try {
                writeTaskControl(token, snapshot, "RUNNING", "DISPATCH_RECOVERY");
                DriveApiClient.Metadata found = api.findLatestServerDispatch(
                        token, request, snapshot.config().optString("baseFolderId"));
                if (found == null) throw new IllegalStateException("server dispatch not found");
                JSONObject body = api.readServerDispatchFile(token, found.id);
                if (!SCHEMA.equals(body.optString("schema"))
                        || !request.equals(body.optString("request_id"))) {
                    throw new IllegalStateException("server dispatch recovery mismatch");
                }
                main.post(() -> {
                    if (!active(expectedGeneration, request)) return;
                    dispatchFileId = found.id;
                    dispatchBody = body;
                    long recoveredAttempt = body.optLong("dispatch_attempt", preparationAttempt);
                    preparationAttempt = Math.max(preparationAttempt, recoveredAttempt);
                    trace("V4_SERVER_DISPATCH_RECOVERED",
                            "turn=" + snapshot.turn() + ";attempt=" + recoveredAttempt
                                    + ";file=" + found.id);
                    handleRemote(expectedGeneration, request, body);
                });
            } catch (Throwable error) {
                main.post(() -> {
                    if (active(expectedGeneration, request)) fail("DRIVE_DISPATCH_RECOVERY_FAILED");
                });
            }
        });
    }

    private void authorizeAndCreate(int expectedGeneration, String request, long attempt) {
        DriveAuthorization.requestSilently(context, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                if (!active(expectedGeneration, request)) return;
                String token = DriveAuthorization.accessToken(result);
                if (token.isEmpty()) {
                    fail("DRIVE_TOKEN_EMPTY");
                    return;
                }
                accessToken = token;
                createDispatch(expectedGeneration, request, attempt, token);
            }

            @Override public void onResolutionRequired(PendingIntent pendingIntent) {
                if (active(expectedGeneration, request)) fail("AUTH_REQUIRED");
            }

            @Override public void onFailure(Throwable error) {
                if (active(expectedGeneration, request)) fail("DRIVE_AUTH_FAILED");
            }
        });
    }

    private void createDispatch(int expectedGeneration, String request, long attempt, String token) {
        SelfRun3Engine.State snapshot = state;
        io.execute(() -> {
            try {
                writeTaskControl(token, snapshot, "RUNNING", "DISPATCH_CREATE");
                String id = api.generateFileId(token);
                JSONObject body = buildDispatch(snapshot, attempt);
                JSONObject props = new JSONObject()
                        .put("job_id", snapshot.taskId())
                        .put("task_id", snapshot.taskId())
                        .put("turn_id", snapshot.turnId())
                        .put("request_id", snapshot.requestId())
                        .put("dispatch_attempt", String.valueOf(attempt));
                String name = "__SELFRUN_DISPATCH__" + safeName(snapshot.requestId())
                        + "__A" + attempt + ".json";
                api.createServerDispatchFile(token, id, name, snapshot.config().optString("baseFolderId"), props, body);
                main.post(() -> {
                    if (!active(expectedGeneration, request)) {
                        cancelRemoteBestEffort(token, id, body, "SUPERSEDED");
                        return;
                    }
                    dispatchFileId = id;
                    dispatchBody = body;
                    trace("V4_SERVER_DISPATCH_CREATED", "turn=" + snapshot.turn()
                            + ";attempt=" + attempt + ";file=" + id);
                    poll(expectedGeneration, request);
                });
            } catch (Throwable error) {
                main.post(() -> {
                    if (active(expectedGeneration, request)) fail("DRIVE_DISPATCH_CREATE_FAILED");
                });
            }
        });
    }

    private JSONObject buildDispatch(SelfRun3Engine.State snapshot, long attempt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("schema", SCHEMA);
        body.put("client_status", "CREATE_REQUESTED");
        body.put("server_status", "PENDING");
        body.put("task_id", snapshot.taskId());
        body.put("turn_id", snapshot.turnId());
        body.put("request_id", snapshot.requestId());
        body.put("dispatch_attempt", attempt);
        body.put("project_url", snapshot.config().optString("projectUrl"));
        body.put("prompt", snapshot.text("prompt"));
        body.put("task_mode", snapshot.taskMode());
        body.put("mode", snapshot.config().optString("mode"));
        body.put("model", snapshot.config().optString("model"));
        body.put("reasoning", snapshot.config().optString("reasoning"));
        body.put("result_document_id", snapshot.resource("resultDocumentId"));
        body.put("requirement_document_id", snapshot.resource("requirementDocumentId"));
        body.put("task_folder_id", snapshot.resource("folderId"));
        body.put("previous_result_document_id", snapshot.text("previousResultDocumentId"));
        body.put("profile_operations", profileOperations(snapshot));
        body.put("created_at_ms", System.currentTimeMillis());
        return body;
    }

    private static JSONArray profileOperations(SelfRun3Engine.State snapshot) {
        String mode = snapshot.config().optString("mode");
        String model = snapshot.config().optString("model");
        String reasoning = snapshot.config().optString("reasoning");
        ProfileRegistry.Profile profile;
        if (SelfRunStore.MODE_WORK.equals(mode)) {
            profile = ProfileRegistry.resolveWork(model, reasoning);
        } else {
            profile = model.isEmpty()
                    ? ProfileRegistry.resolveChat(reasoning)
                    : ProfileRegistry.resolveChat(model, reasoning);
        }
        if (profile == null) throw new IllegalStateException("request profile unavailable");
        JSONArray out = new JSONArray();
        for (ProfileRegistry.Operation operation : profile.operations) out.put(operation.toJson());
        return out;
    }

    private void poll(int expectedGeneration, String request) {
        if (!active(expectedGeneration, request) || dispatchFileId.isEmpty() || accessToken.isEmpty()) return;
        String token = accessToken;
        String fileId = dispatchFileId;
        io.execute(() -> {
            try {
                JSONObject body = api.readServerDispatchFile(token, fileId);
                main.post(() -> handleRemote(expectedGeneration, request, body));
            } catch (Throwable error) {
                main.post(() -> {
                    if (active(expectedGeneration, request)) later(
                            () -> poll(expectedGeneration, request), POLL_MS);
                });
            }
        });
    }

    private void handleRemote(int expectedGeneration, String request, JSONObject body) {
        requireMain();
        if (!active(expectedGeneration, request)) return;
        dispatchBody = body;
        String serverStatus = body.optString("server_status", "PENDING");
        if ("ERROR".equals(serverStatus)) {
            fail("SERVER_DISPATCH_ERROR");
            return;
        }
        if ("CANCELLED".equals(serverStatus) || "SUPERSEDED".equals(serverStatus)) {
            fail("SERVER_DISPATCH_CANCELLED");
            return;
        }
        if ("READY_TO_SUBMIT".equals(serverStatus) && !preparedNotified) {
            preparedNotified = true;
            listener.onPrepared(state.taskId(), state.turnId(), state.requestId());
        }
        String url = body.optString("conversation_url", "");
        boolean conversationReady = "STARTED".equals(serverStatus)
                || "COMPLETED".equals(serverStatus)
                || "RECOVERY_SENDING".equals(serverStatus)
                || "RECOVERY_SENT".equals(serverStatus)
                || "PAGE_ERROR".equals(serverStatus);
        if (conversationReady && SelfRun3WebAdapter.trusted(url)
                && !SelfRunScript.conversationId(url).isEmpty()) {
            if (!started) {
                started = true;
                listener.onConversation(state.taskId(), state.turnId(), url);
                listener.onDispatched(state.taskId(), state.turnId(), state.requestId());
                listener.onStarted(state.taskId(), state.turnId(), state.requestId());
                trace("V4_SERVER_CONVERSATION_CONFIRMED", "turn=" + state.turn()
                        + ";attempt=" + preparationAttempt);
            }
            cancelLocalAttempt(false);
            return;
        }
        later(() -> poll(expectedGeneration, request), POLL_MS);
    }

    void submit(SelfRun3Engine.State claimed) {
        requireMain();
        if (closed || state == null || !state.requestId().equals(claimed.requestId())
                || dispatchFileId.isEmpty() || accessToken.isEmpty()
                || !SelfRun3PowerPolicy.maySend(claimed)) {
            fail("SUBMISSION_STATE_INVALID");
            return;
        }
        state = claimed;
        if (submitRequested) return;
        submitRequested = true;
        String token = accessToken;
        String fileId = dispatchFileId;
        JSONObject body;
        try {
            body = dispatchBody == null ? new JSONObject() : new JSONObject(dispatchBody.toString());
            body.put("client_status", "SEND_REQUESTED");
            body.put("send_requested_at_ms", System.currentTimeMillis());
        } catch (Exception error) {
            fail("DISPATCH_JSON_INVALID");
            return;
        }
        dispatchBody = body;
        int expectedGeneration = generation;
        String request = state.requestId();
        io.execute(() -> {
            try {
                api.writeServerDispatchFile(token, fileId, body);
                main.post(() -> {
                    if (active(expectedGeneration, request)) poll(expectedGeneration, request);
                });
            } catch (Throwable error) {
                main.post(() -> {
                    if (active(expectedGeneration, request)) fail("DRIVE_DISPATCH_WRITE_FAILED");
                });
            }
        });
    }

    void detach() {
        // No in-app browser exists in V4. Drive polling is retained until STARTED or timeout.
    }

    void quiesce() {
        requireMain();
        cancelLocalAttempt(true);
    }

    boolean disposeForConfirmedResultWait(SelfRun3Engine.State persisted) {
        requireMain();
        if (persisted == null || !started || state == null
                || !state.taskId().equals(persisted.taskId())
                || !state.turnId().equals(persisted.turnId())
                || !state.requestId().equals(persisted.requestId())
                || !persisted.flag("sendClaimed")
                || !persisted.flag("dispatchObserved")
                || !persisted.flag("accepted")) return false;
        cancelLocalAttempt(false);
        return true;
    }

    void close() {
        requireMain();
        closed = true;
        cancelLocalAttempt(true);
        io.shutdown();
    }

    private void expireAttempt() {
        requireMain();
        SelfRun3Engine.State timedOut = state;
        boolean successor = SelfRun3SuccessorTransitionPolicy.armed(timedOut);
        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - prepareStarted);
        if (timedOut != null && submitRequested && !started && !successor) {
            if (!lateStartGraceArmed) {
                lateStartGraceArmed = true;
                long attempt = preparationAttempt;
                int expectedGeneration = generation;
                String request = timedOut.requestId();
                trace("V4_SERVER_PREPARATION_WATCHDOG",
                        "status=late-start-grace;attempt=" + attempt
                                + ";elapsedMs=" + elapsed + ";graceMs=" + LATE_START_GRACE_MS);
                main.postDelayed(() -> {
                    if (!active(expectedGeneration, request) || !preparing
                            || attempt != preparationAttempt || started) return;
                    expireAttempt();
                }, LATE_START_GRACE_MS);
                return;
            }
            trace("V4_SERVER_PREPARATION_WATCHDOG",
                    "status=uncertain-send-reconcile;attempt=" + preparationAttempt
                            + ";elapsedMs=" + elapsed);
            listener.onFailure(timedOut.taskId(), timedOut.turnId(), timedOut.requestId(),
                    "WEB_START_CONFIRMATION_TIMEOUT");
            return;
        }
        trace("V4_SERVER_PREPARATION_WATCHDOG", "status=expired;attempt=" + preparationAttempt
                + ";elapsedMs=" + elapsed);
        cancelLocalAttempt(true);
        if (timedOut != null) {
            listener.onFailure(timedOut.taskId(), timedOut.turnId(), timedOut.requestId(),
                    successor ? "SUCCESSOR_TRANSITION_TIMEOUT" : "WEB_PREPARATION_TIMEOUT");
        }
    }

    private void cancelLocalAttempt(boolean cancelRemote) {
        boolean wasPreparing = preparing;
        preparing = false;
        generation++;
        main.removeCallbacksAndMessages(null);
        if (cancelRemote && wasPreparing && !started && !dispatchFileId.isEmpty()
                && !accessToken.isEmpty() && dispatchBody != null) {
            cancelRemoteBestEffort(accessToken, dispatchFileId, dispatchBody, "CANCELLED");
        }
    }

    private void cancelRemoteBestEffort(String token, String fileId, JSONObject source, String status) {
        JSONObject cancelled;
        try {
            cancelled = new JSONObject(source.toString());
            cancelled.put("client_status", status);
            cancelled.put("cancelled_at_ms", System.currentTimeMillis());
        } catch (Exception ignored) {
            return;
        }
        if (io.isShutdown()) return;
        try {
            io.execute(() -> {
                try { api.writeServerDispatchFile(token, fileId, cancelled); }
                catch (Throwable ignored) { }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    private boolean active(int expectedGeneration, String request) {
        return !closed && generation == expectedGeneration && state != null
                && request.equals(state.requestId());
    }

    private void fail(String code) {
        SelfRun3Engine.State failed = state;
        cancelLocalAttempt(true);
        if (failed != null) listener.onFailure(
                failed.taskId(), failed.turnId(), failed.requestId(), code);
    }

    private void later(Runnable action, long delay) {
        if (state == null) return;
        int expectedGeneration = generation;
        String request = state.requestId();
        main.postDelayed(() -> {
            if (active(expectedGeneration, request)) action.run();
        }, Math.max(0L, delay));
    }

    private void trace(String event, String detail) {
        if (state == null || !state.taskId().equals(runStore.runId())) return;
        runLog.record(runStore, event, detail);
    }

    private static String safeName(String value) {
        String raw = value == null ? "" : value.replaceAll("[^A-Za-z0-9._:-]", "_");
        return raw.substring(0, Math.min(160, raw.length()));
    }

    private int currentBootCount() {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT); }
        catch (Throwable unavailable) { return -1; }
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("SelfRun4 Drive web adapter requires main thread");
        }
    }
}
