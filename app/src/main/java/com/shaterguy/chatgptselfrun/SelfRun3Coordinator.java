package com.shaterguy.chatgptselfrun;

import android.app.PendingIntent;
import android.app.Service;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.google.android.gms.auth.api.identity.AuthorizationResult;

import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ledger-driven SelfRun 3 coordinator. Uncertain sends are reconciled, never blindly repeated. */
final class SelfRun3Coordinator implements SelfRun3WebAdapter.Listener {
    static final String PHASE_SETUP = "V3_SETUP";
    static final String PHASE_PREPARING = "V3_PREPARING";
    static final String PHASE_READY = "V3_READY";
    static final String PHASE_DISPATCHING = "V3_DISPATCHING";
    static final String PHASE_WAITING = "V3_WAITING";
    static final String PHASE_RECONCILING = "V3_RECONCILING";

    private final Service service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SelfRunStore store;
    private final SelfRunRunLog log;
    private final SelfRun3Ledger ledger;
    private final SelfRun3DriveAdapter drive;
    private final SelfRun3WebAdapter web;
    private final PowerManager.WakeLock wakeLock;
    private final Map<String, Long> nextResultPoll = new HashMap<>();
    private final Map<String, String> resultVersions = new HashMap<>();
    private Runnable scheduledNext;
    private String preparingRequest = "";
    private String completedTaskNotification = "";

    private volatile boolean destroyed;
    private volatile int epoch;
    private boolean authorizationInFlight;
    private boolean driveInFlight;
    private String accessToken = "";
    private int networkAttempt;

    SelfRun3Coordinator(Service service, SelfRunStore store, SelfRunRunLog log) {
        this.service = service;
        this.store = store;
        this.log = log;
        ledger = new SelfRun3Ledger(service);
        drive = new SelfRun3DriveAdapter(service, store, ledger, this::operationPermitted);
        web = new SelfRun3WebAdapter(service, this);
        PowerManager power = service.getSystemService(PowerManager.class);
        wakeLock = power == null ? null : power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + ":selfrun3-active");
        if (wakeLock != null) wakeLock.setReferenceCounted(false);
    }

    boolean ownsCurrentRun() {
        return SelfRun3RunMarker.current(service, store.runId());
    }

    int onStart(String action) {
        requireMain();
        if ((BuildConfig.APPLICATION_ID + ".PAUSE").equals(action)) {
            pause("USER_PAUSE");
            return store.active() ? Service.START_STICKY : Service.START_NOT_STICKY;
        }
        if ((BuildConfig.APPLICATION_ID + ".RESUME").equals(action)) {
            resume();
            return store.active() ? Service.START_STICKY : Service.START_NOT_STICKY;
        }
        if ((BuildConfig.APPLICATION_ID + ".STOP").equals(action)) {
            stop();
            return Service.START_NOT_STICKY;
        }
        startOrRecover();
        return store.active() ? Service.START_STICKY : Service.START_NOT_STICKY;
    }

    void destroy() {
        requireMain();
        destroyed = true;
        epoch++;
        main.removeCallbacksAndMessages(null);
        web.close();
        releaseWakeLock();
        io.shutdownNow();
        ledger.close();
    }

    private void startOrRecover() {
        if (!store.active() || store.userStopped() || store.runId().isEmpty() || !ownsCurrentRun()) return;
        int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State current = ledger.load(store.runId());
                if (current == null) current = ledger.ensure(initialState());
                if (store.paused() && current.stage() != SelfRun3Engine.Stage.PAUSED && !current.terminal()) {
                    JSONObject control = new JSONObject();
                    SelfRun3Engine.put(control, "reason", "PRESERVED_LOCAL_PAUSE");
                    current = ledger.apply(event(current, current.taskId() + ":pause:" + UUID.randomUUID(),
                            SelfRun3Engine.Kind.PAUSE, control));
                }
                SelfRun3Engine.State ready = current;
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    syncProjection(ready);
                    if (ready.stage() != SelfRun3Engine.Stage.PAUSED && !store.paused()) scheduleNext(0L);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_LEDGER_OPEN_FAILED", error));
            }
        });
    }

    private SelfRun3Engine.State initialState() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", store.mode());
        SelfRun3Engine.put(config, "taskMode", store.taskMode());
        SelfRun3Engine.put(config, "projectUrl", store.projectUrl());
        SelfRun3Engine.put(config, "requirement", store.requirement());
        SelfRun3Engine.put(config, "accountId", store.runDriveAccountId());
        SelfRun3Engine.put(config, "baseFolderId", store.runBaseFolderId());
        SelfRun3Engine.put(config, "model", store.pendingModel());
        SelfRun3Engine.put(config, "reasoning", SelfRunStore.MODE_CHAT.equals(store.mode())
                ? ChatReasoningPreferenceStore.selectionForRun(service, store.runId()) : store.pendingReasoning());
        SelfRun3Engine.put(config, "chatBootstrap",
                ChatReasoningPreferenceStore.selectionForRun(service, store.runId()));
        return SelfRun3Engine.create(store.runId(), turnId(store.runId(), 1), config);
    }

    private void scheduleNext(long delay) {
        requireMain();
        if (!canRun()) return;
        int expectedEpoch = epoch;
        if (scheduledNext != null) main.removeCallbacks(scheduledNext);
        scheduledNext = () -> {
            if (!validEpoch(expectedEpoch) || !canRun()) return;
            io.execute(() -> {
                try {
                    SelfRun3Engine.State state = ledger.load(store.runId());
                    main.post(() -> {
                        if (!validEpoch(expectedEpoch) || state == null) return;
                        syncProjection(state);
                        handleState(state);
                    });
                } catch (Throwable error) {
                    main.post(() -> hardPause("V3_LEDGER_READ_FAILED", error));
                }
            });
        };
        main.postDelayed(scheduledNext, Math.max(0L, delay));
    }

    private void handleState(SelfRun3Engine.State state) {
        requireMain();
        if (!canRun() || state.terminal() || state.stage() == SelfRun3Engine.Stage.PAUSED) return;
        if (driveInFlight || authorizationInFlight) return;
        // A single WebView dispatches branches sequentially. Result timers never inspect its UI.
        if (!preparingRequest.isEmpty()) {
            scheduleNext(SelfRun3PowerPolicy.NORMAL_WAIT_POLL_MS);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long nextDelay = SelfRun3PowerPolicy.NORMAL_WAIT_POLL_MS;
        for (SelfRun3Engine.State execution : SelfRun3Engine.waitingExecutions(state)) {
            if (execution.resource("resultDocumentId").isEmpty()) continue;
            long due = nextResultPoll.getOrDefault(execution.turnId(), 0L);
            if (due <= now) {
                runDriveStep(execution, DriveStep.READ_RESULT);
                return;
            }
            nextDelay = Math.min(nextDelay, due - now);
        }
        switch (SelfRun3Engine.nextAction(state)) {
            case SETUP -> runDriveStep(state, DriveStep.SETUP);
            case PREPARE_TURN -> runDriveStep(state, DriveStep.PREPARE_TURN);
            case PREPARE_WEB -> {
                preparingRequest = state.requestId();
                acquireWakeLock();
                web.prepare(state);
            }
            case WAIT, READ_RESULT, CHECK_RECEIPT -> {
                web.detach();
                releaseWakeLock();
                scheduleNext(nextDelay);
            }
            case COMMIT -> commitTurn(state);
            case NONE -> {
                releaseWakeLock();
                if (!SelfRun3Engine.waitingExecutions(state).isEmpty()) scheduleNext(nextDelay);
            }
        }
    }

    private enum DriveStep { SETUP, PREPARE_TURN, READ_RESULT }

    private void runDriveStep(SelfRun3Engine.State state, DriveStep step) {
        requireMain();
        if (driveInFlight || authorizationInFlight || !canRun()) return;
        if (!accessToken.isEmpty()) {
            executeDriveStep(state, step, accessToken);
            return;
        }
        int expectedEpoch = epoch;
        authorizationInFlight = true;
        DriveAuthorization.requestSilently(service, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                authorizationInFlight = false;
                if (!validEpoch(expectedEpoch) || !canRun()) return;
                accessToken = DriveAuthorization.accessToken(result);
                if (accessToken.isEmpty()) {
                    scheduleNetworkRetry("V3_DRIVE_TOKEN_EMPTY");
                    return;
                }
                executeDriveStep(state, step, accessToken);
            }
            @Override public void onResolutionRequired(PendingIntent pendingIntent) {
                authorizationInFlight = false;
                if (validEpoch(expectedEpoch)) pause("V3_DRIVE_AUTH_REQUIRED");
            }
            @Override public void onFailure(Throwable error) {
                authorizationInFlight = false;
                if (validEpoch(expectedEpoch)) scheduleNetworkRetry("V3_DRIVE_AUTH_FAILED");
            }
        });
    }

    private void executeDriveStep(SelfRun3Engine.State state, DriveStep step, String token) {
        requireMain();
        if (driveInFlight || !canRun()) return;
        driveInFlight = true;
        if (step != DriveStep.READ_RESULT) acquireWakeLock();
        int expectedEpoch = epoch;
        String expectedTask = state.taskId();
        String expectedTurn = state.turnId();
        io.execute(() -> {
            try {
                SelfRun3Engine.State after;
                if (step == DriveStep.SETUP) {
                    after = drive.setup(token, state);
                    after = ledger.apply(event(after, after.turnId() + ":setup-done",
                            SelfRun3Engine.Kind.SETUP_DONE, new JSONObject()));
                } else if (step == DriveStep.PREPARE_TURN) {
                    after = drive.prepareTurn(token, state);
                    SelfRun3UserInput.Snapshot input = SelfRun3UserInput.snapshot(service, after.taskId());
                    long consumed = after.time("lastConsumedInputRevision");
                    boolean branch = SelfRun3Engine.isBranch(after);
                    boolean repair = "REPAIR".equals(after.text("executionKind"));
                    long inputRevision = branch ? after.time("branchInputRevision") : repair ? consumed : input.revision;
                    String inputText = branch ? after.text("branchInputText") : repair ? "" : (input.revision > consumed ? input.text : "");
                    if (input.revision <= consumed && !input.text.isEmpty()) {
                        SelfRun3UserInput.consumeIfRevision(service, after.taskId(), input.revision);
                    }
                    JSONObject payload = new JSONObject();
                    SelfRun3Engine.put(payload, "prompt", SelfRun3Protocol.prompt(after, inputText));
                    SelfRun3Engine.put(payload, "inputText", inputText);
                    SelfRun3Engine.put(payload, "inputRevision", inputRevision);
                    after = ledger.apply(event(after, after.turnId() + ":turn-ready",
                            SelfRun3Engine.Kind.TURN_READY, payload));
                } else {
                    String version = drive.resultVersion(token, state);
                    if (version.equals(resultVersions.get(state.turnId()))) throw new ResultPendingException();
                    String raw = drive.readResult(token, state);
                    JSONObject parsed = SelfRun3Engine.parseResult(raw, state);
                    if (parsed == null) {
                        resultVersions.put(state.turnId(), version);
                        throw new ResultPendingException();
                    }
                    JSONObject payload = new JSONObject();
                    SelfRun3Engine.put(payload, "text", raw);
                    after = ledger.apply(event(state, state.turnId() + ":result:" + version,
                            SelfRun3Engine.Kind.RESULT, payload));
                    resultVersions.put(state.turnId(), version);
                }
                SelfRun3Engine.State completed = after;
                main.post(() -> {
                    driveInFlight = false;
                    releaseWakeLock();
                    if (!validEpoch(expectedEpoch) || !completed.taskId().equals(expectedTask)
                            || !expectedTask.equals(store.runId())) return;
                    clearRecoveredDriveWarning(store);
                    networkAttempt = 0;
                    if (step == DriveStep.READ_RESULT) nextResultPoll.put(expectedTurn,
                            SystemClock.elapsedRealtime() + SelfRun3PowerPolicy.NORMAL_WAIT_POLL_MS);
                    syncProjection(completed);
                    scheduleNext(0L);
                });
            } catch (SelfRun3DriveAdapter.InvalidCommittedResultException invalid) {
                main.post(() -> {
                    driveInFlight = false;
                    releaseWakeLock();
                    if (validEpoch(expectedEpoch) && expectedTask.equals(store.runId())) {
                        clearRecoveredDriveWarning(store);
                        networkAttempt = 0;
                        repairResult(state);
                    }
                });
            } catch (ResultPendingException pending) {
                main.post(() -> {
                    driveInFlight = false;
                    releaseWakeLock();
                    if (validEpoch(expectedEpoch) && expectedTask.equals(store.runId())) {
                        clearRecoveredDriveWarning(store);
                        networkAttempt = 0;
                        scheduleResultRetry(state);
                    }
                });
            } catch (Throwable error) {
                main.post(() -> {
                    driveInFlight = false;
                    releaseWakeLock();
                    if (validEpoch(expectedEpoch)) handleDriveFailure(state, step, error);
                });
            }
        });
    }

    private void scheduleResultRetry(SelfRun3Engine.State state) {
        nextResultPoll.put(state.turnId(), SystemClock.elapsedRealtime()
                + SelfRun3PowerPolicy.NORMAL_WAIT_POLL_MS);
        scheduleNext(0L);
    }

    private void repairResult(SelfRun3Engine.State original) {
        int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State current = ledger.loadExecution(original.taskId(), original.turnId());
                if (current == null) return;
                if (current.number("repairAttempt") != 0) {
                    main.post(() -> pause("V3_RESULT_REPAIR_EXHAUSTED"));
                    return;
                }
                JSONObject payload = new JSONObject();
                SelfRun3Engine.put(payload, "safeToRepair", true);
                SelfRun3Engine.put(payload, "reason", "EXACT_IDENTITY_INVALID_COMMITTED_RESULT");
                SelfRun3Engine.State after = ledger.apply(event(current, current.turnId() + ":repair-invalid-commit",
                        SelfRun3Engine.Kind.REPAIR, payload));
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    nextResultPoll.remove(original.turnId());
                    syncProjection(after);
                    scheduleNext(0L);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_RESULT_REPAIR_FAILED", error));
            }
        });
    }

    private void commitTurn(SelfRun3Engine.State stale) {
        int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State current = ledger.loadExecution(stale.taskId(), stale.turnId());
                if (current == null) return;
                SelfRun3Engine.State after = SelfRun3UserInput.commit(service, store, ledger, current);
                main.post(() -> {
                    if (!validEpoch(expectedEpoch) || !current.taskId().equals(store.runId())) return;
                    nextResultPoll.remove(current.turnId());
                    syncProjection(after);
                    if (after.stage() == SelfRun3Engine.Stage.DONE) {
                        epoch++;
                        main.removeCallbacksAndMessages(null);
                        preparingRequest = "";
                        nextResultPoll.clear();
                        resultVersions.clear();
                        web.close();
                        releaseWakeLock();
                        log.record(store, "V3_DONE", "turn=" + after.turn() + ";task=" + after.taskId());
                        service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
                        if (!after.taskId().equals(completedTaskNotification)) {
                            completedTaskNotification = after.taskId();
                            NotificationHelper.notifyUser(service, "작업 완료", "SelfRun 작업이 완료되었습니다.");
                        }
                        service.stopSelf();
                    } else scheduleNext(0L);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_COMMIT_FAILED", error));
            }
        });
    }

    private void scheduleNetworkRetry(String code) {
        store.setLastError(code, "SelfRun 3 네트워크 작업을 재확인합니다.");
        scheduleNext(SelfRun3PowerPolicy.networkRetryDelay(networkAttempt++));
    }

    static boolean transientDriveWarning(String code) {
        if (code == null || code.isEmpty()) return false;
        return "V3_DRIVE_TOKEN_EMPTY".equals(code)
                || "V3_DRIVE_AUTH_FAILED".equals(code)
                || "V3_DRIVE_TOKEN_EXPIRED".equals(code)
                || "V3_DRIVE_NETWORK_RETRY".equals(code)
                || code.startsWith("V3_DRIVE_HTTP_RETRY_");
    }

    static void clearRecoveredDriveWarning(SelfRunStore store) {
        if (store != null && transientDriveWarning(store.lastErrorCode())) store.clearLastError();
    }

    private void handleDriveFailure(SelfRun3Engine.State state, DriveStep step, Throwable error) {
        if (error instanceof DriveApiClient.ApiException api && api.status == 401) {
            accessToken = "";
            scheduleNetworkRetry("V3_DRIVE_TOKEN_EXPIRED");
            return;
        }
        if (error instanceof DriveApiClient.ApiException api && api.retryable()) {
            scheduleNetworkRetry("V3_DRIVE_HTTP_RETRY_" + api.status);
            return;
        }
        if (error instanceof IOException) {
            scheduleNetworkRetry("V3_DRIVE_NETWORK_RETRY");
            return;
        }
        hardPause("V3_" + step.name() + "_FAILED", error);
    }

    @Override public void onPrepared(String task, String turn, String request) {
        int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State before = ledger.loadExecution(task, turn);
                if (!callbackMatches(before, task, turn, request) || before.stage() != SelfRun3Engine.Stage.READY) return;
                JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "at", System.currentTimeMillis());
                SelfRun3Engine.State claimed = ledger.apply(event(before, request + ":claim",
                        SelfRun3Engine.Kind.CLAIM_SEND, payload));
                main.post(() -> {
                    if (!validEpoch(expectedEpoch) || !SelfRun3PowerPolicy.maySend(claimed.execution(turn))) return;
                    syncProjection(claimed);
                    web.submit(claimed.execution(turn));
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_SEND_CLAIM_FAILED", error));
            }
        });
    }

    @Override public void onDispatched(String task, String turn, String request) {
        // A click is not proof of acceptance. The persisted send claim already enables Drive readback.
        log.record(store, "V3_DISPATCH", "turn=" + turn + ";request=" + request);
    }

    @Override public void onStarted(String task, String turn, String request) {
        recordCallback(task, turn, request, request + ":started", SelfRun3Engine.Kind.STARTED,
                requestPayload(request));
    }

    @Override public void onAccepted(String task, String turn, String request) {
        recordCallback(task, turn, request, request + ":accepted", SelfRun3Engine.Kind.ACCEPTED,
                requestPayload(request));
    }

    @Override public void onConversation(String task, String turn, String url) {
        if (!SelfRun3WebAdapter.trusted(url) || SelfRunScript.conversationId(url).isEmpty()) return;
        int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State state = ledger.loadExecution(task, turn);
                if (state == null || !state.turnId().equals(turn)) return;
                JSONObject payload = new JSONObject();
                SelfRun3Engine.put(payload, "key", "conversationUrl");
                SelfRun3Engine.put(payload, "value", url);
                SelfRun3Engine.State after = ledger.apply(event(state,
                        turn + ":conversation:" + SelfRunScript.conversationId(url),
                        SelfRun3Engine.Kind.RESOURCE, payload));
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    syncProjection(after);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_CONVERSATION_BIND_FAILED", error));
            }
        });
    }

    @Override public void onUnsent(String task, String turn, String request, String status) {
        JSONObject payload = requestPayload(request); SelfRun3Engine.put(payload, "status", status);
        recordCallback(task, turn, request, request + ":unsent:" + status, SelfRun3Engine.Kind.UNSENT, payload);
    }

    @Override public void onFailure(String task, String turn, String request, String code) {
        if ("AUTH_REQUIRED".equals(code) || "TURN_PROTOCOL_UNAVAILABLE".equals(code)) {
            pause("V3_" + code);
            return;
        }
        int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State state = ledger.loadExecution(task, turn);
                if (!callbackMatches(state, task, turn, request)) return;
                JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "code", safeCode(code));
                SelfRun3Engine.State after = ledger.apply(event(state, request + ":error:" + safeCode(code),
                        SelfRun3Engine.Kind.ERROR, payload));
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    syncProjection(after);
                    if (request.equals(preparingRequest)) {
                        preparingRequest = "";
                        web.quiesce();
                        releaseWakeLock();
                    }
                    scheduleNext(5_000L);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_WEB_FAILURE_RECORD_FAILED", error));
            }
        });
    }

    private void recordCallback(String task, String turn, String request, String eventId,
                                SelfRun3Engine.Kind kind, JSONObject payload) {
        int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State state = ledger.loadExecution(task, turn);
                if (!callbackMatches(state, task, turn, request)) return;
                SelfRun3Engine.State after = ledger.apply(event(state, eventId, kind, payload));
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    if (kind == SelfRun3Engine.Kind.STARTED || kind == SelfRun3Engine.Kind.ACCEPTED
                            || kind == SelfRun3Engine.Kind.UNSENT) {
                        if (request.equals(preparingRequest)) {
                            preparingRequest = "";
                            web.detach();
                            releaseWakeLock();
                        }
                    }
                    syncProjection(after);
                    scheduleNext(0L);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_CALLBACK_COMMIT_FAILED", error));
            }
        });
    }

    private void pause(String reason) {
        requireMain();
        if (store.runId().isEmpty() || destroyed || !ownsCurrentRun()) return;
        epoch++;
        if (scheduledNext != null) main.removeCallbacks(scheduledNext);
        preparingRequest = "";
        web.quiesce();
        releaseWakeLock();
        int expectedEpoch = epoch;
        String task = store.runId();
        io.execute(() -> {
            try {
                SelfRun3Engine.State state = ledger.load(task);
                if (state != null && !state.terminal()) {
                    JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "reason", reason);
                    state = ledger.apply(event(state, task + ":pause:" + UUID.randomUUID(),
                            SelfRun3Engine.Kind.PAUSE, payload));
                }
                SelfRun3Engine.State paused = state;
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    store.setPaused(true);
                    store.setLastError(reason, "SelfRun 3 실행이 상태를 보존한 채 일시정지되었습니다.");
                    if (paused != null) syncProjection(paused);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_PAUSE_FAILED", error));
            }
        });
    }

    private void resume() {
        requireMain();
        if (store.userStopped() || store.runId().isEmpty() || !ownsCurrentRun()) return;
        epoch++;
        store.setPaused(false);
        store.clearLastError();
        int expectedEpoch = epoch;
        String task = store.runId();
        io.execute(() -> {
            try {
                SelfRun3Engine.State state = ledger.load(task);
                if (state == null) throw new IllegalStateException("task not found");
                if (state.stage() == SelfRun3Engine.Stage.PAUSED) {
                    state = ledger.apply(event(state, task + ":resume:" + UUID.randomUUID(),
                            SelfRun3Engine.Kind.RESUME, new JSONObject()));
                }
                SelfRun3Engine.State resumed = state;
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    syncProjection(resumed);
                    scheduleNext(0L);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_RESUME_FAILED", error));
            }
        });
    }

    private void stop() {
        requireMain();
        if (store.runId().isEmpty() || !ownsCurrentRun()) return;
        epoch++;
        main.removeCallbacksAndMessages(null);
        web.close();
        releaseWakeLock();
        int expectedEpoch = epoch;
        String task = store.runId();
        io.execute(() -> {
            try {
                SelfRun3Engine.State state = ledger.load(task);
                if (state != null && !state.terminal()) {
                    ledger.apply(event(state, task + ":stop:" + UUID.randomUUID(),
                            SelfRun3Engine.Kind.STOP, new JSONObject()));
                }
            } catch (Throwable ignored) {
                // A user stop wins even when the preserved ledger cannot be read.
            }
            main.post(() -> {
                if (!validEpoch(expectedEpoch)) return;
                store.stopByUser();
                service.stopSelf();
            });
        });
    }

    private void syncProjection(SelfRun3Engine.State state) {
        requireMain();
        if (state == null || !state.taskId().equals(store.runId())) return;
        String phase = switch (state.stage()) {
            case SETUP -> PHASE_SETUP;
            case PREPARING -> PHASE_PREPARING;
            case READY -> PHASE_READY;
            case DISPATCHING -> PHASE_DISPATCHING;
            case WAITING, WAITING_USER_INTERVENTION, BRANCH_COMPLETE -> PHASE_WAITING;
            case RECONCILING -> PHASE_RECONCILING;
            case PAUSED -> SelfRunStore.PHASE_PAUSED;
            case DONE -> SelfRunStore.PHASE_DONE;
            case STOPPED -> SelfRunStore.PHASE_IDLE;
        };
        if (!phase.equals(store.phase())) store.setPhase(phase);
        if (store.turn() != state.turn()) store.setTurn(state.turn());
        if (state.stage() == SelfRun3Engine.Stage.PAUSED && !store.paused()) store.setPaused(true);
        if (state.stage() != SelfRun3Engine.Stage.PAUSED && store.paused()) store.setPaused(false);
        JSONObject executionConfig = state.config();
        store.setExecutionProjection(executionConfig.optString("mode"),
                executionConfig.optString("model"), executionConfig.optString("reasoning"),
                state.resource("conversationUrl"));
        if (state.stage() == SelfRun3Engine.Stage.DONE) {
            store.setActive(false);
            store.releaseCommittedAttachmentPermissions();
            store.setStatus("작업 완료");
        } else if (state.stage() == SelfRun3Engine.Stage.STOPPED) {
            store.setStatus("사용자 중지");
        } else store.setStatus(statusFor(state));
    }

    private static String statusFor(SelfRun3Engine.State state) {
        return switch (state.stage()) {
            case SETUP -> "SelfRun 3 원장 · Drive 준비";
            case PREPARING -> "SelfRun 3 다음 논리 턴 준비";
            case READY -> "SelfRun 3 ChatGPT 요청 준비";
            case DISPATCHING -> "SelfRun 3 전송 결과 확인";
            case WAITING -> "SelfRun 3 Drive 결과 대기";
            case WAITING_USER_INTERVENTION -> "사용자 조치 완료 checkpoint 대기";
            case BRANCH_COMPLETE -> "다른 병렬 결과 대기";
            case RECONCILING -> "SelfRun 3 Drive 결과 확정";
            case PAUSED -> "SelfRun 3 일시정지 · 상태 보존";
            case DONE -> "작업 완료";
            case STOPPED -> "사용자 중지";
        };
    }

    private void hardPause(String code, Throwable error) {
        requireMain();
        epoch++;
        if (scheduledNext != null) main.removeCallbacks(scheduledNext);
        preparingRequest = "";
        web.quiesce();
        releaseWakeLock();
        store.setPaused(true);
        store.setLastError(code, "SelfRun 3 상태를 안전하게 확정하지 못해 자동 전송을 중지했습니다.");
        log.record(store, "V3_HARD_PAUSE", "code=" + code + ";error="
                + (error == null ? "" : error.getClass().getSimpleName()));
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(SelfRun3PowerPolicy.WAKE_LOCK_MAX_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Throwable ignored) { }
        }
    }

    private boolean operationPermitted() {
        return !destroyed && store.active() && !store.paused() && !store.userStopped() && ownsCurrentRun();
    }

    private boolean canRun() { return operationPermitted(); }
    private boolean validEpoch(int expected) { return !destroyed && expected == epoch; }

    private static boolean callbackMatches(SelfRun3Engine.State state, String task, String turn, String request) {
        return state != null && task.equals(state.taskId()) && turn.equals(state.turnId())
                && request.equals(state.requestId()) && !state.terminal();
    }

    private static SelfRun3Engine.Event event(SelfRun3Engine.State state, String id,
                                              SelfRun3Engine.Kind kind, JSONObject payload) {
        return new SelfRun3Engine.Event(id, kind, state.taskId(), state.turnId(), payload);
    }

    private static JSONObject requestPayload(String request) {
        JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "requestId", request); return payload;
    }

    private static String turnId(String task, int turn) { return task + ":turn:" + turn; }

    private static String safeCode(String raw) {
        String value = raw == null ? "UNKNOWN" : raw.toUpperCase().replaceAll("[^A-Z0-9_:-]", "_");
        return value.isEmpty() ? "UNKNOWN" : value.substring(0, Math.min(100, value.length()));
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("SelfRun3 coordinator requires main thread");
    }

    private static final class ResultPendingException extends Exception { }
}
