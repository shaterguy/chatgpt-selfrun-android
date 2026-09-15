package com.shaterguy.chatgptselfrun;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import com.google.android.gms.auth.api.identity.AuthorizationResult;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private final SelfRun3RuntimeSettings runtimeSettings;
    private final SelfRunServerWatch serverWatch;
    private final SharedPreferences runtimePrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener runtimeListener;
    private final PowerManager.WakeLock wakeLock;
    private final Map<String, Long> nextResultPoll = new HashMap<>();
    private final Set<String> serverRegisteredTurns = new HashSet<>();
    private final Set<String> serverFallbackTurns = new HashSet<>();
    private final ArrayDeque<SelfRun3Engine.State> recoveryQueue = new ArrayDeque<>();
    private Runnable scheduledNext;
    private String preparingRequest = "";
    private String completedTaskNotification = "";

    private volatile boolean destroyed;
    private volatile int epoch;
    private int serverGeneration;
    private boolean authorizationInFlight;
    private boolean driveInFlight;
    private boolean serverRegistrationInFlight;
    private boolean recoveryCycleInFlight;
    private String accessToken = "";
    private int networkAttempt;

    SelfRun3Coordinator(Service service, SelfRunStore store, SelfRunRunLog log) {
        this.service = service;
        this.store = store;
        this.log = log;
        ledger = new SelfRun3Ledger(service);
        drive = new SelfRun3DriveAdapter(service, store, ledger, this::operationPermitted);
        web = new SelfRun3WebAdapter(service, this);
        runtimeSettings = new SelfRun3RuntimeSettings(service);
        serverWatch = new SelfRunServerWatch(service);
        runtimePrefs = service.getSharedPreferences(SelfRun3RuntimeSettings.PREFS, Context.MODE_PRIVATE);
        runtimeListener = (prefs, key) -> {
            if (SelfRun3RuntimeSettings.KEY_WORK_MODE.equals(key)) main.post(this::onWorkModeChanged);
        };
        runtimePrefs.registerOnSharedPreferenceChangeListener(runtimeListener);
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

    void onPushResult(SelfRunPushEvent push) {
        requireMain();
        if (push == null) return;
        if (!canRun() || runtimeSettings.workMode() != SelfRun3RuntimeSettings.WorkMode.SERVER) {
            acknowledgeProcessed(push);
            return;
        }
        int expectedEpoch = epoch;
        String installationId;
        try {
            installationId = SelfRunInstallationIdentity.id(service);
        } catch (Throwable unavailable) {
            return;
        }
        io.execute(() -> {
            SelfRun3Engine.State current = null;
            try {
                current = ledger.loadExecution(push.taskId, push.turnId);
            } catch (Throwable ignored) { }
            SelfRun3Engine.State exact = current;
            main.post(() -> {
                if (!validEpoch(expectedEpoch)) return;
                if (exact == null || exact.terminal() || exact.flag("superseded")
                        || !waitEligible(exact)
                        || !push.matches(installationId, exact.taskId(), exact.turnId(),
                        exact.resource("resultDocumentId"))) {
                    acknowledgeProcessed(push);
                    return;
                }
                if (serverFallbackTurns.contains(exact.turnId())) {
                    acknowledgeProcessed(push);
                    return;
                }
                if (driveInFlight || authorizationInFlight) return;
                serverRegisteredTurns.remove(exact.turnId());
                nextResultPoll.remove(exact.turnId());
                runDriveStep(exact, DriveStep.READ_RESULT, ReadTrigger.PUSH, push);
            });
        });
    }

    void onServerRecovery() {
        requireMain();
        if (!canRun() || runtimeSettings.workMode() != SelfRun3RuntimeSettings.WorkMode.SERVER) {
            SelfRunServerRecoveryWorker.cancel(service);
            return;
        }
        if (recoveryCycleInFlight || driveInFlight || authorizationInFlight || serverRegistrationInFlight) return;
        int expectedEpoch = epoch;
        String task = store.runId();
        Set<String> fallbackSnapshot = new HashSet<>(serverFallbackTurns);
        io.execute(() -> {
            ArrayDeque<SelfRun3Engine.State> waiting = new ArrayDeque<>();
            try {
                SelfRun3Engine.State root = ledger.load(task);
                if (root != null) {
                    for (SelfRun3Engine.State execution : SelfRun3Engine.waitingExecutions(root)) {
                        if (!fallbackSnapshot.contains(execution.turnId())) waiting.add(execution);
                    }
                }
            } catch (Throwable ignored) { }
            main.post(() -> {
                if (!validEpoch(expectedEpoch) || !task.equals(store.runId())) return;
                recoveryQueue.clear();
                recoveryQueue.addAll(waiting);
                if (recoveryQueue.isEmpty()) {
                    SelfRunServerRecoveryWorker.cancel(service);
                    return;
                }
                recoveryCycleInFlight = true;
                runNextRecoveryRead();
            });
        });
    }

    void destroy() {
        requireMain();
        destroyed = true;
        epoch++;
        serverGeneration++;
        runtimePrefs.unregisterOnSharedPreferenceChangeListener(runtimeListener);
        main.removeCallbacksAndMessages(null);
        recoveryQueue.clear();
        SelfRunFallbackWakeScheduler.cancel(service);
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
        if (!canRun()) {
            SelfRunFallbackWakeScheduler.cancel(service);
            return;
        }
        long safeDelay = Math.max(0L, delay);
        int expectedEpoch = epoch;
        if (scheduledNext != null) main.removeCallbacks(scheduledNext);
        if (safeDelay > 0L) SelfRunFallbackWakeScheduler.schedule(service, safeDelay);
        else SelfRunFallbackWakeScheduler.cancel(service);
        scheduledNext = () -> {
            SelfRunFallbackWakeScheduler.cancel(service);
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
        main.postDelayed(scheduledNext, safeDelay);
    }

    private void handleState(SelfRun3Engine.State state) {
        requireMain();
        if (!canRun()) return;
        if (state.terminal() || state.stage() == SelfRun3Engine.Stage.PAUSED) {
            SelfRunFallbackWakeScheduler.cancel(service);
            SelfRunServerRecoveryWorker.cancel(service);
            return;
        }
        if (driveInFlight || authorizationInFlight || serverRegistrationInFlight || recoveryCycleInFlight) return;
        long resultPollMs = runtimeSettings.resultPollMs();
        // A single WebView dispatches branches sequentially. Result timers never inspect its UI.
        if (!preparingRequest.isEmpty()) {
            scheduleNext(resultPollMs);
            return;
        }

        List<SelfRun3Engine.State> waiting = SelfRun3Engine.waitingExecutions(state);
        Set<String> waitingIds = new HashSet<>();
        for (SelfRun3Engine.State execution : waiting) waitingIds.add(execution.turnId());
        serverRegisteredTurns.retainAll(waitingIds);
        serverFallbackTurns.retainAll(waitingIds);

        SelfRun3RuntimeSettings.WorkMode workMode = runtimeSettings.workMode();
        long now = SystemClock.elapsedRealtime();
        long nextDelay = resultPollMs;
        boolean localPollingWaiting = false;
        boolean serverWaiting = false;

        for (SelfRun3Engine.State execution : waiting) {
            if (execution.resource("resultDocumentId").isEmpty()) continue;
            boolean useServer = SelfRunServerWaitPolicy.useServerPush(
                    workMode, serverFallbackTurns.contains(execution.turnId()));
            if (useServer && !serverRegisteredTurns.contains(execution.turnId())) {
                if (startServerWatchRegistration(execution)) return;
                useServer = SelfRunServerWaitPolicy.useServerPush(
                        workMode, serverFallbackTurns.contains(execution.turnId()));
            }
            if (useServer) {
                serverWaiting = true;
                continue;
            }
            localPollingWaiting = true;
            long due = nextResultPoll.getOrDefault(execution.turnId(), 0L);
            if (due <= now) {
                runDriveStep(execution, DriveStep.READ_RESULT);
                return;
            }
            nextDelay = Math.min(nextDelay, due - now);
        }

        if (serverWaiting) SelfRunServerRecoveryWorker.schedule(service);
        else SelfRunServerRecoveryWorker.cancel(service);

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
                if (localPollingWaiting) scheduleNext(nextDelay);
            }
            case COMMIT -> commitTurn(state);
            case NONE -> {
                releaseWakeLock();
                if (localPollingWaiting) scheduleNext(nextDelay);
            }
        }
    }

    private boolean startServerWatchRegistration(SelfRun3Engine.State execution) {
        requireMain();
        if (serverRegisteredTurns.contains(execution.turnId())
                || serverFallbackTurns.contains(execution.turnId())) return false;
        if (serverRegistrationInFlight) return true;
        if (!SelfRunFirebase.configured()) {
            activateServerFallback(execution, "FIREBASE_NOT_CONFIGURED");
            return false;
        }
        serverRegistrationInFlight = true;
        int expectedEpoch = epoch;
        int expectedGeneration = serverGeneration;
        if (!accessToken.isEmpty()) {
            requestFcmAndRegister(execution, accessToken, expectedEpoch, expectedGeneration);
            return true;
        }
        authorizationInFlight = true;
        DriveAuthorization.requestSilently(service, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                authorizationInFlight = false;
                if (!validServerRegistration(expectedEpoch, expectedGeneration)) return;
                accessToken = DriveAuthorization.accessToken(result);
                if (accessToken.isEmpty()) {
                    finishServerRegistrationFallback(execution, "DRIVE_TOKEN_EMPTY", expectedEpoch, expectedGeneration);
                    return;
                }
                requestFcmAndRegister(execution, accessToken, expectedEpoch, expectedGeneration);
            }
            @Override public void onResolutionRequired(PendingIntent pendingIntent) {
                authorizationInFlight = false;
                serverRegistrationInFlight = false;
                if (validServerRegistration(expectedEpoch, expectedGeneration)) pause("V3_DRIVE_AUTH_REQUIRED");
            }
            @Override public void onFailure(Throwable error) {
                authorizationInFlight = false;
                finishServerRegistrationFallback(execution, "DRIVE_AUTH_FAILED", expectedEpoch, expectedGeneration);
            }
        });
        return true;
    }

    private void requestFcmAndRegister(SelfRun3Engine.State execution, String driveToken,
                                       int expectedEpoch, int expectedGeneration) {
        SelfRunFirebase.requestToken(service, new SelfRunFirebase.TokenCallback() {
            @Override public void onToken(String fcmToken) {
                main.post(() -> {
                    if (!validServerRegistration(expectedEpoch, expectedGeneration)) return;
                    io.execute(() -> {
                        SelfRun3Engine.State current = null;
                        SelfRunServerWatch.Result result = null;
                        try {
                            current = ledger.loadExecution(execution.taskId(), execution.turnId());
                            if (current != null && waitEligible(current)) {
                                result = serverWatch.register(driveToken, current, fcmToken);
                            }
                        } catch (Throwable error) {
                            result = SelfRunServerWatch.Result.fallback(error.getClass().getSimpleName());
                        }
                        SelfRun3Engine.State registeredState = current;
                        SelfRunServerWatch.Result registration = result;
                        main.post(() -> finishServerRegistration(
                                registeredState, registration, expectedEpoch, expectedGeneration));
                    });
                });
            }
            @Override public void onUnavailable(Throwable error) {
                String reason = error == null ? "FCM_UNAVAILABLE" : error.getClass().getSimpleName();
                main.post(() -> finishServerRegistrationFallback(
                        execution, reason, expectedEpoch, expectedGeneration));
            }
        });
    }

    private void finishServerRegistration(SelfRun3Engine.State state, SelfRunServerWatch.Result result,
                                          int expectedEpoch, int expectedGeneration) {
        requireMain();
        if (!validServerRegistration(expectedEpoch, expectedGeneration)) return;
        serverRegistrationInFlight = false;
        if (state == null || !waitEligible(state)) {
            scheduleNext(0L);
            return;
        }
        if (result != null && result.registered) {
            serverFallbackTurns.remove(state.turnId());
            serverRegisteredTurns.add(state.turnId());
            nextResultPoll.remove(state.turnId());
            log.record(store, "V3_SERVER_PUSH_REGISTERED", "turn=" + state.turn());
            SelfRunServerRecoveryWorker.schedule(service);
            scheduleNext(0L);
            return;
        }
        activateServerFallback(state, result == null ? "WATCH_REGISTRATION_FAILED" : result.reason);
        scheduleNext(0L);
    }

    private void finishServerRegistrationFallback(SelfRun3Engine.State state, String reason,
                                                  int expectedEpoch, int expectedGeneration) {
        requireMain();
        if (!validServerRegistration(expectedEpoch, expectedGeneration)) return;
        serverRegistrationInFlight = false;
        activateServerFallback(state, reason);
        scheduleNext(0L);
    }

    private boolean validServerRegistration(int expectedEpoch, int expectedGeneration) {
        return validEpoch(expectedEpoch) && expectedGeneration == serverGeneration && canRun()
                && runtimeSettings.workMode() == SelfRun3RuntimeSettings.WorkMode.SERVER;
    }

    private void activateServerFallback(SelfRun3Engine.State state, String reason) {
        if (state == null) return;
        serverRegisteredTurns.remove(state.turnId());
        serverFallbackTurns.add(state.turnId());
        nextResultPoll.put(state.turnId(), 0L);
        log.record(store, "V3_SERVER_PUSH_FALLBACK",
                "turn=" + state.turn() + ";reason=" + safeCode(reason));
    }

    private void onWorkModeChanged() {
        requireMain();
        if (destroyed) return;
        serverGeneration++;
        serverRegistrationInFlight = false;
        serverRegisteredTurns.clear();
        serverFallbackTurns.clear();
        recoveryQueue.clear();
        recoveryCycleInFlight = false;
        nextResultPoll.clear();
        SelfRunServerRecoveryWorker.cancel(service);
        if (canRun()) scheduleNext(0L);
    }

    private enum DriveStep { SETUP, PREPARE_TURN, READ_RESULT }
    private enum ReadTrigger { NORMAL, PUSH, RECOVERY }

    private void runDriveStep(SelfRun3Engine.State state, DriveStep step) {
        runDriveStep(state, step, ReadTrigger.NORMAL, null);
    }

    private void runDriveStep(SelfRun3Engine.State state, DriveStep step,
                              ReadTrigger trigger, SelfRunPushEvent push) {
        requireMain();
        if (driveInFlight || authorizationInFlight || !canRun()) return;
        if (!accessToken.isEmpty()) {
            executeDriveStep(state, step, accessToken, trigger, push);
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
                    if (trigger == ReadTrigger.RECOVERY) abortRecoveryCycle();
                    scheduleNetworkRetry("V3_DRIVE_TOKEN_EMPTY");
                    return;
                }
                executeDriveStep(state, step, accessToken, trigger, push);
            }
            @Override public void onResolutionRequired(PendingIntent pendingIntent) {
                authorizationInFlight = false;
                if (validEpoch(expectedEpoch)) pause("V3_DRIVE_AUTH_REQUIRED");
            }
            @Override public void onFailure(Throwable error) {
                authorizationInFlight = false;
                if (trigger == ReadTrigger.RECOVERY) abortRecoveryCycle();
                if (validEpoch(expectedEpoch)) scheduleNetworkRetry("V3_DRIVE_AUTH_FAILED");
            }
        });
    }

    private void executeDriveStep(SelfRun3Engine.State state, DriveStep step, String token,
                                  ReadTrigger trigger, SelfRunPushEvent push) {
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
                    SelfRun3Engine.State current = ledger.loadExecution(expectedTask, expectedTurn);
                    if (current == null || current.flag("superseded")) throw new ResultPendingException();
                    SelfRun3DriveAdapter.ResultObservation observation = drive.observeResult(token, current);
                    current = ledger.loadExecution(expectedTask, expectedTurn);
                    if (current == null || current.flag("superseded")) throw new ResultPendingException();
                    JSONObject parsed = SelfRun3Engine.parseResult(observation.candidateBody, current);
                    if (parsed != null) {
                        JSONObject payload = new JSONObject();
                        SelfRun3Engine.put(payload, "text", observation.candidateBody);
                        after = ledger.apply(event(current, current.turnId() + ":result:" + observation.version,
                                SelfRun3Engine.Kind.RESULT, payload));
                    } else if (SelfRun3ResultWatchdog.shouldRepair(current,
                            SystemClock.elapsedRealtime(), currentBootCount(), runtimeSettings.resultRepairMs())) {
                        JSONObject payload = new JSONObject();
                        SelfRun3Engine.put(payload, "safeToRepair", true);
                        SelfRun3Engine.put(payload, "reason", "STALE_RESULT_WATCHDOG");
                        after = ledger.apply(event(current, current.turnId() + ":repair-stale-result",
                                SelfRun3Engine.Kind.REPAIR, payload));
                        log.record(store, "V3_STALE_RESULT_REPAIR",
                                "turn=" + current.turn() + ";document=" + current.resource("resultDocumentId"));
                    } else {
                        throw new ResultPendingException();
                    }
                }
                SelfRun3Engine.State completed = after;
                main.post(() -> {
                    driveInFlight = false;
                    releaseWakeLock();
                    if (!validEpoch(expectedEpoch) || !completed.taskId().equals(expectedTask)
                            || !expectedTask.equals(store.runId())) return;
                    clearRecoveredDriveWarning(store);
                    networkAttempt = 0;
                    if (step == DriveStep.READ_RESULT) {
                        serverRegisteredTurns.remove(expectedTurn);
                        nextResultPoll.remove(expectedTurn);
                        if (!SelfRunServerWaitPolicy.useServerPush(runtimeSettings.workMode(),
                                serverFallbackTurns.contains(expectedTurn))
                                && completed.turnId().equals(expectedTurn)) {
                            nextResultPoll.put(expectedTurn,
                                    SystemClock.elapsedRealtime() + runtimeSettings.resultPollMs());
                        }
                        if (push != null) acknowledgeProcessed(push);
                    }
                    syncProjection(completed);
                    if (trigger == ReadTrigger.RECOVERY) finishRecoveryRead();
                    else scheduleNext(0L);
                });
            } catch (ResultPendingException pending) {
                main.post(() -> {
                    driveInFlight = false;
                    releaseWakeLock();
                    if (validEpoch(expectedEpoch) && expectedTask.equals(store.runId())) {
                        clearRecoveredDriveWarning(store);
                        networkAttempt = 0;
                        if (push != null) acknowledgeProcessed(push);
                        serverRegisteredTurns.remove(expectedTurn);
                        if (trigger == ReadTrigger.RECOVERY) {
                            nextResultPoll.remove(expectedTurn);
                            finishRecoveryRead();
                        } else {
                            scheduleResultRetry(state);
                        }
                    }
                });
            } catch (Throwable error) {
                main.post(() -> {
                    driveInFlight = false;
                    releaseWakeLock();
                    if (!validEpoch(expectedEpoch) || !expectedTask.equals(store.runId())) return;
                    if (push != null && step == DriveStep.READ_RESULT) acknowledgeProcessed(push);
                    if (step == DriveStep.READ_RESULT) serverRegisteredTurns.remove(expectedTurn);
                    if (trigger == ReadTrigger.RECOVERY) abortRecoveryCycle();
                    if (step == DriveStep.READ_RESULT && !readResultTransportFailure(error)) {
                        clearRecoveredDriveWarning(store);
                        networkAttempt = 0;
                        hardPause("V3_READ_RESULT_FAILED", error);
                    } else {
                        handleDriveFailure(state, step, error);
                    }
                });
            }
        });
    }

    private void scheduleResultRetry(SelfRun3Engine.State state) {
        if (SelfRunServerWaitPolicy.useServerPush(runtimeSettings.workMode(),
                serverFallbackTurns.contains(state.turnId()))) {
            serverRegisteredTurns.remove(state.turnId());
            nextResultPoll.remove(state.turnId());
            scheduleNext(0L);
            return;
        }
        nextResultPoll.put(state.turnId(), SystemClock.elapsedRealtime() + runtimeSettings.resultPollMs());
        scheduleNext(0L);
    }

    private void runNextRecoveryRead() {
        requireMain();
        if (!recoveryCycleInFlight || !canRun()) {
            abortRecoveryCycle();
            return;
        }
        SelfRun3Engine.State next = recoveryQueue.pollFirst();
        if (next == null) {
            recoveryCycleInFlight = false;
            scheduleNext(0L);
            return;
        }
        serverRegisteredTurns.remove(next.turnId());
        nextResultPoll.remove(next.turnId());
        runDriveStep(next, DriveStep.READ_RESULT, ReadTrigger.RECOVERY, null);
    }

    private void finishRecoveryRead() {
        requireMain();
        runNextRecoveryRead();
    }

    private void abortRecoveryCycle() {
        recoveryQueue.clear();
        recoveryCycleInFlight = false;
    }

    private void acknowledgeProcessed(SelfRunPushEvent push) {
        if (push == null) return;
        try {
            SelfRunPushAckOutbox.enqueue(service, push, SelfRunPushAckOutbox.AckState.PROCESSED);
        } catch (Throwable error) {
            log.record(store, "V3_PUSH_ACK_PERSIST_FAILED",
                    "error=" + error.getClass().getSimpleName());
        }
    }

    private static boolean waitEligible(SelfRun3Engine.State state) {
        if (state == null || state.terminal() || state.flag("superseded")) return false;
        return (state.stage() == SelfRun3Engine.Stage.DISPATCHING
                && !state.resource("conversationUrl").isEmpty())
                || state.stage() == SelfRun3Engine.Stage.WAITING
                || state.stage() == SelfRun3Engine.Stage.WAITING_USER_INTERVENTION
                || (state.stage() == SelfRun3Engine.Stage.RECONCILING && !state.hasResult());
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
                    serverRegisteredTurns.remove(current.turnId());
                    serverFallbackTurns.remove(current.turnId());
                    syncProjection(after);
                    if (after.stage() == SelfRun3Engine.Stage.WAITING_USER_INTERVENTION) {
                        notifyUserActionRequired();
                    } else if (after.stage() == SelfRun3Engine.Stage.PAUSED) {
                        notifyPaused();
                    }
                    if (after.stage() == SelfRun3Engine.Stage.DONE) {
                        epoch++;
                        cancelServerWaitState();
                        main.removeCallbacksAndMessages(null);
                        preparingRequest = "";
                        nextResultPoll.clear();
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

    static boolean readResultTransportFailure(Throwable error) {
        return error instanceof DriveApiClient.ApiException || error instanceof IOException;
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
                SelfRun3Engine.State claimed = ledger.apply(event(before,
                        request + ":claim:" + UUID.randomUUID(), SelfRun3Engine.Kind.CLAIM_SEND, payload));
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
        JSONObject payload = requestPayload(request);
        SelfRun3Engine.put(payload, "source", "canonical_post");
        SelfRun3Engine.put(payload, "protocolStage", "turn_request");
        SelfRun3Engine.put(payload, "atElapsed", SystemClock.elapsedRealtime());
        SelfRun3Engine.put(payload, "atWall", System.currentTimeMillis());
        SelfRun3Engine.put(payload, "bootCount", currentBootCount());
        recordCallback(task, turn, request, request + ":started", SelfRun3Engine.Kind.STARTED, payload);
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
                JSONObject payload = requestPayload(request); SelfRun3Engine.put(payload, "code", safeCode(code));
                SelfRun3Engine.State after = ledger.apply(event(state, request + ":error:" + safeCode(code),
                        SelfRun3Engine.Kind.ERROR, payload));
                boolean retryingPreparation = state.stage() == SelfRun3Engine.Stage.READY && !state.flag("sendClaimed");
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    syncProjection(after);
                    if (request.equals(preparingRequest)) {
                        preparingRequest = "";
                        web.quiesce();
                        releaseWakeLock();
                    }
                    if (retryingPreparation) {
                        log.record(store, "V3_WEB_PREPARATION_RETRY",
                                "turn=" + turn + ";request=" + request + ";code=" + safeCode(code)
                                        + ";delayMs=5000");
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
        cancelServerWaitState();
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
                    notifyPaused();
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
        cancelServerWaitState();
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

    private void cancelServerWaitState() {
        SelfRunFallbackWakeScheduler.cancel(service);
        serverGeneration++;
        serverRegistrationInFlight = false;
        serverRegisteredTurns.clear();
        serverFallbackTurns.clear();
        recoveryQueue.clear();
        recoveryCycleInFlight = false;
        SelfRunServerRecoveryWorker.cancel(service);
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
            SelfRunServerRecoveryWorker.cancel(service);
            store.setActive(false);
            store.releaseCommittedAttachmentPermissions();
            store.setStatus("작업 완료");
        } else if (state.stage() == SelfRun3Engine.Stage.STOPPED) {
            SelfRunServerRecoveryWorker.cancel(service);
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
        cancelServerWaitState();
        if (scheduledNext != null) main.removeCallbacks(scheduledNext);
        preparingRequest = "";
        web.quiesce();
        releaseWakeLock();
        store.setPaused(true);
        store.setLastError(code, "SelfRun 3 상태를 안전하게 확정하지 못해 자동 전송을 중지했습니다.");
        log.record(store, "V3_HARD_PAUSE", "code=" + code + ";error="
                + (error == null ? "" : error.getClass().getSimpleName()));
        notifyPaused();
    }

    private void notifyUserActionRequired() {
        NotificationHelper.notifyUser(service, "사용자 조치 필요",
                "SelfRun 실행을 계속하려면 사용자 조치가 필요합니다.");
    }

    private void notifyPaused() {
        NotificationHelper.notifyUser(service, "일시정지",
                "SelfRun 실행이 상태를 보존한 채 일시정지되었습니다.");
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
                && request.equals(state.requestId()) && !state.terminal() && !state.flag("superseded");
    }

    private static SelfRun3Engine.Event event(SelfRun3Engine.State state, String id,
                                              SelfRun3Engine.Kind kind, JSONObject payload) {
        return new SelfRun3Engine.Event(id, kind, state.taskId(), state.turnId(), payload);
    }

    private static JSONObject requestPayload(String request) {
        JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "requestId", request); return payload;
    }

    private int currentBootCount() {
        try {
            return Settings.Global.getInt(service.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Throwable unavailable) {
            return -1;
        }
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
