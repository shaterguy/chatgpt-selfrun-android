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
    private static final long POLL_MS = 1_000L;

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
    private long preparationAttempt;
    private long prepareStarted;
    private long prepareTimeoutMs;
    private int generation;
    private String accessToken = "";
    private String dispatchFileId = "";
    private JSONObject dispatchBody;

    SelfRun4DriveWebAdapter(Context context, SelfRun3WebAdapter.Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.runtimeSettings = new SelfRun3RuntimeSettings(this.context);
        this.runLog = new SelfRunRunLog(this.context);
        this.runStore = new SelfRunStore(this.context);
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
        if (state == null || state.stage() != SelfRun3Engine.Stage.READY) {
            fail("SUBMISSION_STATE_INVALID");
            return;
        }
        generation++;
        preparing = true;
        preparedNotified = false;
        submitRequested = false;
        started = false;
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
        trace("V4_SERVER_PREPARATION_WATCHDOG", "status=armed;attempt=" + attempt
                + ";timeoutMs=" + prepareTimeoutMs);
        main.postDelayed(() -> {
            if (!active(expectedGeneration, request) || !preparing || attempt != preparationAttempt) return;
            expireAttempt();
        }, Math.max(1L, prepareTimeoutMs));
        authorizeAndCreate(expectedGeneration, request, attempt);
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
                api.createServerDispatchFile(token, id, name, snapshot.resource("folderId"), props, body);
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
        if ("STARTED".equals(serverStatus) && SelfRun3WebAdapter.trusted(url)
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
        io.shutdownNow();
    }

    private void expireAttempt() {
        requireMain();
        SelfRun3Engine.State timedOut = state;
        boolean successor = SelfRun3SuccessorTransitionPolicy.armed(timedOut);
        trace("V4_SERVER_PREPARATION_WATCHDOG", "status=expired;attempt=" + preparationAttempt
                + ";elapsedMs=" + Math.max(0L, SystemClock.elapsedRealtime() - prepareStarted));
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
        io.execute(() -> {
            try { api.writeServerDispatchFile(token, fileId, cancelled); }
            catch (Throwable ignored) { }
        });
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
