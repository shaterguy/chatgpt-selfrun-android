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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SelfRun 3 runtime coordinator.
 *
 * <p>The durable ledger owns task/turn/request state. Browser and Drive adapters are replaceable
 * ports. An uncertain dispatch is never retried; the runtime reconciles the pinned result document
 * and the existing conversation instead.</p>
 */
final class SelfRun3Runtime implements SelfRun3WebAdapter.Listener {
    private static final long EARLY_RECONCILE_MS = 30_000L;

    private final Service service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SelfRunStore projection;
    private final SelfRunRunLog log;
    private final SelfRun3Ledger ledger;
    private final SelfRun3DriveAdapter drive;
    private final SelfRun3WebAdapter web;
    private final PowerManager.WakeLock wakeLock;
    private final Runnable missedProbeRunnable = this::runMissedProbe;

    private volatile boolean destroyed;
    private volatile int epoch;
    private boolean authorizationInFlight;
    private boolean driveInFlight;
    private String accessToken = "";
    private int networkAttempt;
    private int resultAttempt;
    private int missedProbeCount;
    private String missedProbeRequest = "";
    private String stableReceiptSignature = "";
    private long stableReceiptAt;

    SelfRun3Runtime(Service service, SelfRunStore projection, SelfRunRunLog log) {
        this.service = service;
        this.projection = projection;
        this.log = log;
        this.ledger = new SelfRun3Ledger(service);
        this.drive = new SelfRun3DriveAdapter(service, projection, ledger, this::operationPermitted);
        this.web = new SelfRun3WebAdapter(service, this);
        PowerManager power = service.getSystemService(PowerManager.class);
        this.wakeLock = power == null ? null : power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + ":selfrun3-active");
        if (wakeLock != null) wakeLock.setReferenceCounted(false);
    }

    int onStart(String action) {
        requireMain();
        if ((BuildConfig.APPLICATION_ID + ".STOP").equals(action)) {
            stop();
            return Service.START_NOT_STICKY;
        }
        if ((BuildConfig.APPLICATION_ID + ".PAUSE").equals(action)) {
            pause("USER_PAUSE");
            return projection.active() ? Service.START_STICKY : Service.START_NOT_STICKY;
        }
        if ((BuildConfig.APPLICATION_ID + ".RESUME").equals(action)) {
            resume();
            return projection.active() ? Service.START_STICKY : Service.START_NOT_STICKY;
        }
        startOrRecover();
        return projection.active() ? Service.START_STICKY : Service.START_NOT_STICKY;
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
        if (!projection.active() || projection.userStopped() || projection.runId().isEmpty()) return;
        final int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State current = ledger.load(projection.runId());
                if (current == null) current = ledger.ensure(initialState());
                SelfRun3Engine.State ready = current;
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    syncProjection(ready);
                    if (ready.stage() == SelfRun3Engine.Stage.PAUSED || projection.paused()) return;
                    scheduleNext(0L);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_LEDGER_OPEN_FAILED", error));
            }
        });
    }

    private SelfRun3Engine.State initialState() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", projection.mode());
        SelfRun3Engine.put(config, "projectUrl", projection.projectUrl());
        SelfRun3Engine.put(config, "requirement", projection.requirement());
        SelfRun3Engine.put(config, "accountId", projection.runDriveAccountId());
        SelfRun3Engine.put(config, "baseFolderId", projection.runBaseFolderId());
        SelfRun3Engine.put(config, "model", projection.pendingModel());
        SelfRun3Engine.put(config, "reasoning", projection.pendingReasoning());
        SelfRun3Engine.put(config, "chatBootstrap",
                ChatReasoningPreferenceStore.selectionForRun(service, projection.runId()));
        SelfRun3Engine.put(config, "chatContinuation",
                ChatReasoningPreferenceStore.continuationSelectionForRun(service, projection.runId()));
        return SelfRun3Engine.create(projection.runId(), turnId(projection.runId(), 1), config);
    }

    private void scheduleNext(long delay) {
        requireMain();
        if (!canRun()) return;
        final int expectedEpoch = epoch;
        main.postDelayed(() -> {
            if (!validEpoch(expectedEpoch) || !canRun()) return;
            io.execute(() -> {
                try {
                    SelfRun3Engine.State s = ledger.load(projection.runId());
                    main.post(() -> {
                        if (!validEpoch(expectedEpoch) || s == null) return;
                        syncProjection(s);
                        handleState(s);
                    });
                } catch (Throwable error) {
                    main.post(() -> hardPause("V3_LEDGER_READ_FAILED", error));
                }
            });
        }, Math.max(0L, delay));
    }

    private void handleState(SelfRun3Engine.State s) {
        requireMain();
        if (!canRun() || s.terminal() || s.stage() == SelfRun3Engine.Stage.PAUSED) return;
        switch (SelfRun3Engine.nextAction(s)) {
            case SETUP -> runDriveStep(s, DriveStep.SETUP);
            case PREPARE_TURN -> runDriveStep(s, DriveStep.PREPARE_TURN);
            case PREPARE_WEB -> {
                acquireWakeLock();
                web.prepare(s);
            }
            case WAIT -> {
                web.detach();
                releaseWakeLock();
                scheduleMissedProbe(s, SelfRun3PowerPolicy.MISSED_CALLBACK_PROBE_MS);
            }
            case READ_RESULT -> runDriveStep(s, DriveStep.READ_RESULT);
            case CHECK_RECEIPT -> probeReceipt(s, false);
            case COMMIT -> commitTurn(s);
            case NONE -> releaseWakeLock();
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
        final int expectedEpoch = epoch;
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
                if (!validEpoch(expectedEpoch)) return;
                pause("V3_DRIVE_AUTH_REQUIRED");
            }
            @Override public void onFailure(Throwable error) {
                authorizationInFlight = false;
                if (!validEpoch(expectedEpoch)) return;
                scheduleNetworkRetry("V3_DRIVE_AUTH_FAILED");
            }
        });
    }

    private void executeDriveStep(SelfRun3Engine.State state, DriveStep step, String token) {
        requireMain();
        if (driveInFlight || !canRun()) return;
        driveInFlight = true;
        if (step != DriveStep.READ_RESULT) acquireWakeLock();
        final int expectedEpoch = epoch;
        final String expectedTask = state.taskId();
        final String expectedTurn = state.turnId();
        io.execute(() -> {
            try {
                SelfRun3Engine.State after;
                if (step == DriveStep.SETUP) {
                    after = drive.setup(token, state);
                    after = ledger.apply(event(after, after.turnId() + ":setup-done",
                            SelfRun3Engine.Kind.SETUP_DONE, new JSONObject()));
                } else if (step == DriveStep.PREPARE_TURN) {
                    after = drive.prepareTurn(token, state);
                    UserNextInputStore.V3Snapshot input = UserNextInputStore.snapshotV3(after.taskId());
                    long consumed = after.time("lastConsumedInputRevision");
                    String text = input.revision > consumed ? input.text : "";
                    if (input.revision <= consumed && !input.text.isEmpty()) {
                        UserNextInputStore.consumeV3(after.taskId(), input.revision);
                    }
                    String prompt = SelfRun3Protocol.prompt(after, text);
                    JSONObject payload = new JSONObject();
                    SelfRun3Engine.put(payload, "prompt", prompt);
                    SelfRun3Engine.put(payload, "inputText", text);
                    SelfRun3Engine.put(payload, "inputRevision", input.revision);
                    after = ledger.apply(event(after, after.turnId() + ":turn-ready",
                            SelfRun3Engine.Kind.TURN_READY, payload));
                } else {
                    String raw = drive.readResult(token, state);
                    JSONObject parsed = SelfRun3Engine.parseResult(raw, state);
                    if (parsed == null) throw new ResultPendingException();
                    JSONObject payload = new JSONObject();
                    SelfRun3Engine.put(payload, "text", raw);
                    after = ledger.apply(event(state, state.turnId() + ":result",
                            SelfRun3Engine.Kind.RESULT, payload));
                }
                SelfRun3Engine.State completed = after;
                main.post(() -> {
                    driveInFlight = false;
                    releaseWakeLock();
                    if (!validEpoch(expectedEpoch) || !sameTurn(completed, expectedTask, expectedTurn)) return;
                    networkAttempt = 0;
                    if (step == DriveStep.READ_RESULT) resultAttempt = 0;
                    syncProjection(completed);
                    scheduleNext(0L);
                });
            } catch (ResultPendingException pending) {
                main.post(() -> {
                    driveInFlight = false;
                    releaseWakeLock();
                    if (!validEpoch(expectedEpoch)) return;
                    scheduleResultRetry(state);
                });
            } catch (Throwable error) {
                main.post(() -> {
                    driveInFlight = false;
                    releaseWakeLock();
                    if (!validEpoch(expectedEpoch)) return;
                    handleDriveFailure(state, step, error);
                });
            }
        });
    }

    private void scheduleResultRetry(SelfRun3Engine.State state) {
        long delay = SelfRun3PowerPolicy.resultRetryDelay(resultAttempt++);
        if (delay >= 0L) {
            scheduleNext(delay);
            return;
        }
        resultAttempt = 0;
        repairResult(state);
    }

    private void repairResult(SelfRun3Engine.State stale) {
        final int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State current = ledger.load(stale.taskId());
                if (current == null || !current.turnId().equals(stale.turnId()) || !current.flag("ended")) return;
                if (current.number("repairAttempt") != 0) {
                    main.post(() -> pause("V3_RESULT_REPAIR_EXHAUSTED"));
                    return;
                }
                String nextRequest = current.turnId() + ":repair:" + UUID.randomUUID().toString().replace("-", "");
                JSONObject payload = new JSONObject();
                SelfRun3Engine.put(payload, "requestId", nextRequest);
                SelfRun3Engine.put(payload, "prompt", SelfRun3Protocol.repair(current, nextRequest));
                SelfRun3Engine.State after = ledger.apply(event(current, current.turnId() + ":repair",
                        SelfRun3Engine.Kind.REPAIR, payload));
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    syncProjection(after);
                    scheduleNext(0L);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_RESULT_REPAIR_FAILED", error));
            }
        });
    }

    private void commitTurn(SelfRun3Engine.State stale) {
        final int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State current = ledger.load(stale.taskId());
                if (current == null || !current.turnId().equals(stale.turnId())) return;
                JSONObject result = SelfRun3Engine.object(current.text("result"));
                UserNextInputStore.V3Snapshot latest = UserNextInputStore.snapshotV3(current.taskId());
                boolean lateInput = "DONE".equals(result.optString("status"))
                        && latest.revision > current.time("inputRevision") && !latest.text.isEmpty();
                JSONObject payload = new JSONObject();
                if (!"DONE".equals(result.optString("status")) || lateInput) {
                    SelfRun3Engine.put(payload, "nextTurnId", turnId(current.taskId(), current.turn() + 1));
                }
                if (lateInput) {
                    SelfRun3Engine.put(payload, "lateInput", true);
                    SelfRun3Engine.put(payload, "lateInputRevision", latest.revision);
                }
                SelfRun3Engine.State after = ledger.apply(event(current, current.turnId() + ":commit",
                        SelfRun3Engine.Kind.COMMIT, payload));
                if (current.time("inputRevision") >= 0L) {
                    UserNextInputStore.consumeV3(current.taskId(), current.time("inputRevision"));
                }
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    missedProbeCount = 0;
                    missedProbeRequest = "";
                    stableReceiptSignature = "";
                    stableReceiptAt = 0L;
                    syncProjection(after);
                    if (after.stage() == SelfRun3Engine.Stage.DONE) {
                        web.close();
                        releaseWakeLock();
                        log.record(projection, "V3_DONE", "turn=" + after.turn() + ";task=" + after.taskId());
                    } else {
                        scheduleNext(0L);
                    }
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_COMMIT_FAILED", error));
            }
        });
    }

    private void scheduleNetworkRetry(String code) {
        projection.setLastError(code, "SelfRun 3 네트워크 작업을 재확인합니다.");
        long delay = SelfRun3PowerPolicy.networkRetryDelay(networkAttempt++);
        scheduleNext(delay);
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
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        hardPause("V3_" + step.name() + "_FAILED", new IllegalStateException(detail, error));
    }

    private void scheduleMissedProbe(SelfRun3Engine.State s, long delay) {
        requireMain();
        if (!s.flag("sendClaimed") || s.flag("ended") || s.terminal()) return;
        if (!s.requestId().equals(missedProbeRequest)) {
            missedProbeRequest = s.requestId();
            missedProbeCount = 0;
            stableReceiptSignature = "";
            stableReceiptAt = 0L;
        }
        main.removeCallbacks(missedProbeRunnable);
        main.postDelayed(missedProbeRunnable, Math.max(1L, delay));
    }

    private void runMissedProbe() {
        if (!canRun()) return;
        final int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State s = ledger.load(projection.runId());
                main.post(() -> {
                    if (!validEpoch(expectedEpoch) || s == null || s.flag("ended") || !s.flag("sendClaimed")) return;
                    if (++missedProbeCount > SelfRun3PowerPolicy.MAX_MISSED_CALLBACK_PROBES) {
                        pause("V3_RESPONSE_RECONCILE_EXHAUSTED");
                        return;
                    }
                    probeReceipt(s, true);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_PROBE_STATE_FAILED", error));
            }
        });
    }

    private void probeReceipt(SelfRun3Engine.State s, boolean missedCallbackPath) {
        requireMain();
        web.inspect(s, result -> {
            if (!canRun()) return;
            if (result.optBoolean("complete")) {
                String source = result.optString("source");
                if (!TurnProtocolLogBridge.isAllowedCompletionSource(source)) source = "receipt_readback";
                onEnded(s.taskId(), s.turnId(), s.requestId(), source);
                return;
            }
            String signature = result.optString("signature");
            if (result.optBoolean("ready") && result.optBoolean("receipt") && !signature.isEmpty()) {
                long now = SystemClock.elapsedRealtime();
                if (signature.equals(stableReceiptSignature)
                        && stableReceiptAt > 0L
                        && now - stableReceiptAt >= SelfRun3PowerPolicy.RECEIPT_STABILITY_MS) {
                    onEnded(s.taskId(), s.turnId(), s.requestId(), "receipt_readback");
                    return;
                }
                stableReceiptSignature = signature;
                stableReceiptAt = now;
                main.postDelayed(() -> probeReceipt(s, missedCallbackPath), SelfRun3PowerPolicy.RECEIPT_STABILITY_MS);
                return;
            }
            if (result.optBoolean("accepted")) onAccepted(s.taskId(), s.turnId(), s.requestId());
            scheduleMissedProbe(s, missedCallbackPath ? SelfRun3PowerPolicy.MISSED_CALLBACK_PROBE_MS : EARLY_RECONCILE_MS);
        });
    }

    @Override public void onPrepared(String task, String turn, String request) {
        final int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State before = ledger.load(task);
                if (!callbackMatches(before, task, turn, request) || before.stage() != SelfRun3Engine.Stage.READY) return;
                JSONObject payload = new JSONObject(); SelfRun3Engine.put(payload, "at", System.currentTimeMillis());
                SelfRun3Engine.State claimed = ledger.apply(event(before, request + ":claim", SelfRun3Engine.Kind.CLAIM_SEND, payload));
                main.post(() -> {
                    if (!validEpoch(expectedEpoch) || !SelfRun3PowerPolicy.maySend(claimed)) return;
                    syncProjection(claimed);
                    web.submit(claimed);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_SEND_CLAIM_FAILED", error));
            }
        });
    }

    @Override public void onDispatched(String task, String turn, String request) {
        loadForCallback(task, turn, request, s -> {
            log.record(projection, "V3_DISPATCH", "turn=" + turn + ";request=" + request);
            web.detach();
            releaseWakeLock();
            scheduleMissedProbe(s, SelfRun3PowerPolicy.MISSED_CALLBACK_PROBE_MS);
        });
    }

    @Override public void onStarted(String task, String turn, String request) {
        recordCallback(task, turn, request, request + ":started", SelfRun3Engine.Kind.STARTED,
                requestPayload(request), 0L);
    }

    @Override public void onAccepted(String task, String turn, String request) {
        recordCallback(task, turn, request, request + ":accepted", SelfRun3Engine.Kind.ACCEPTED,
                requestPayload(request), 0L);
    }

    @Override public void onEnded(String task, String turn, String request, String source) {
        JSONObject p = requestPayload(request); SelfRun3Engine.put(p, "source", source);
        recordCallback(task, turn, request, request + ":ended:" + source,
                SelfRun3Engine.Kind.ENDED, p, 0L);
    }

    @Override public void onConversation(String task, String turn, String url) {
        if (!SelfRun3WebAdapter.trusted(url) || SelfRunScript.conversationId(url).isEmpty()) return;
        final int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State s = ledger.load(task);
                if (s == null || !s.turnId().equals(turn)) return;
                JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "key", "conversationUrl"); SelfRun3Engine.put(p, "value", url);
                SelfRun3Engine.State after = ledger.apply(event(s, turn + ":conversation:" + SelfRunScript.conversationId(url),
                        SelfRun3Engine.Kind.RESOURCE, p));
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    if (projection.conversationUrl().isEmpty()) projection.captureConversationUrl(url);
                    syncProjection(after);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_CONVERSATION_BIND_FAILED", error));
            }
        });
    }

    @Override public void onUnsent(String task, String turn, String request, String status) {
        JSONObject p = requestPayload(request); SelfRun3Engine.put(p, "status", status);
        recordCallback(task, turn, request, request + ":unsent:" + status,
                SelfRun3Engine.Kind.UNSENT, p, 0L);
    }

    @Override public void onFailure(String task, String turn, String request, String code) {
        if ("AUTH_REQUIRED".equals(code) || "TURN_PROTOCOL_UNAVAILABLE".equals(code)) {
            pause("V3_" + code);
            return;
        }
        final int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State s = ledger.load(task);
                if (!callbackMatches(s, task, turn, request)) return;
                JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "code", safeCode(code));
                SelfRun3Engine.State after = ledger.apply(event(s, request + ":error:" + safeCode(code), SelfRun3Engine.Kind.ERROR, p));
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    syncProjection(after);
                    web.quiesce();
                    releaseWakeLock();
                    if (after.flag("sendClaimed")) scheduleMissedProbe(after, EARLY_RECONCILE_MS);
                    else scheduleNext(5_000L);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_WEB_FAILURE_RECORD_FAILED", error));
            }
        });
    }

    private void recordCallback(String task, String turn, String request, String eventId,
                                SelfRun3Engine.Kind kind, JSONObject payload, long delay) {
        final int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State s = ledger.load(task);
                if (!callbackMatches(s, task, turn, request)) return;
                SelfRun3Engine.State after = ledger.apply(event(s, eventId, kind, payload));
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    if (kind == SelfRun3Engine.Kind.ENDED) {
                        main.removeCallbacks(missedProbeRunnable);
                        resultAttempt = 0;
                    }
                    syncProjection(after);
                    scheduleNext(delay);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_CALLBACK_COMMIT_FAILED", error));
            }
        });
    }

    private interface CallbackStateConsumer { void accept(SelfRun3Engine.State state); }

    private void loadForCallback(String task, String turn, String request, CallbackStateConsumer callback) {
        final int expectedEpoch = epoch;
        io.execute(() -> {
            try {
                SelfRun3Engine.State s = ledger.load(task);
                main.post(() -> {
                    if (validEpoch(expectedEpoch) && callbackMatches(s, task, turn, request)) callback.accept(s);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_CALLBACK_READ_FAILED", error));
            }
        });
    }

    private void pause(String reason) {
        requireMain();
        if (projection.runId().isEmpty() || destroyed) return;
        epoch++;
        main.removeCallbacks(missedProbeRunnable);
        web.quiesce();
        releaseWakeLock();
        final int expectedEpoch = epoch;
        final String task = projection.runId();
        io.execute(() -> {
            try {
                SelfRun3Engine.State s = ledger.load(task);
                if (s != null && !s.terminal()) {
                    JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "reason", reason);
                    s = ledger.apply(event(s, task + ":pause:" + expectedEpoch, SelfRun3Engine.Kind.PAUSE, p));
                }
                SelfRun3Engine.State paused = s;
                main.post(() -> {
                    if (!validEpoch(expectedEpoch)) return;
                    projection.setPaused(true);
                    projection.setLastError(reason, "SelfRun 3 실행이 상태를 보존한 채 일시정지되었습니다.");
                    if (paused != null) syncProjection(paused);
                });
            } catch (Throwable error) {
                main.post(() -> hardPause("V3_PAUSE_FAILED", error));
            }
        });
    }

    private void resume() {
        requireMain();
        if (projection.userStopped() || projection.runId().isEmpty()) return;
        epoch++;
        projection.setPaused(false);
        projection.clearLastError();
        final int expectedEpoch = epoch;
        final String task = projection.runId();
        io.execute(() -> {
            try {
                SelfRun3Engine.State s = ledger.load(task);
                if (s == null) throw new IllegalStateException("task not found");
                if (s.stage() == SelfRun3Engine.Stage.PAUSED) {
                    s = ledger.apply(event(s, task + ":resume:" + expectedEpoch, SelfRun3Engine.Kind.RESUME, new JSONObject()));
                }
                SelfRun3Engine.State resumed = s;
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
        if (projection.runId().isEmpty()) return;
        epoch++;
        main.removeCallbacksAndMessages(null);
        web.close();
        releaseWakeLock();
        final int expectedEpoch = epoch;
        final String task = projection.runId();
        io.execute(() -> {
            try {
                SelfRun3Engine.State s = ledger.load(task);
                if (s != null && !s.terminal()) {
                    s = ledger.apply(event(s, task + ":stop:" + expectedEpoch, SelfRun3Engine.Kind.STOP, new JSONObject()));
                }
            } catch (Throwable ignored) {
                // The user stop still wins. A damaged ledger is preserved and never recreated here.
            }
            main.post(() -> {
                if (!validEpoch(expectedEpoch)) return;
                projection.stopByUser();
                service.stopSelf();
            });
        });
    }

    private void syncProjection(SelfRun3Engine.State s) {
        requireMain();
        if (s == null || !s.taskId().equals(projection.runId())) return;
        String phase = switch (s.stage()) {
            case SETUP -> SelfRunStore.PHASE_V3_SETUP;
            case PREPARING -> SelfRunStore.PHASE_V3_PREPARING;
            case READY -> SelfRunStore.PHASE_V3_READY;
            case DISPATCHING -> SelfRunStore.PHASE_V3_DISPATCHING;
            case WAITING -> SelfRunStore.PHASE_WAIT_TURN_COMPLETION;
            case RECONCILING -> SelfRunStore.PHASE_V3_RECONCILING;
            case PAUSED -> SelfRunStore.PHASE_PAUSED;
            case DONE -> SelfRunStore.PHASE_DONE;
            case STOPPED -> SelfRunStore.PHASE_IDLE;
        };
        if (!phase.equals(projection.phase())) projection.setPhase(phase);
        if (projection.turn() != s.turn()) projection.setTurn(s.turn());
        if (s.stage() == SelfRun3Engine.Stage.PAUSED && !projection.paused()) projection.setPaused(true);
        if (s.stage() != SelfRun3Engine.Stage.PAUSED && projection.paused()) projection.setPaused(false);
        String conversation = s.resource("conversationUrl");
        if (!conversation.isEmpty() && projection.conversationUrl().isEmpty()) projection.captureConversationUrl(conversation);
        if (SelfRunStore.MODE_WORK.equals(projection.mode())) {
            JSONObject config = s.config();
            projection.setPendingModel(config.optString("model"));
            projection.setPendingReasoning(config.optString("reasoning"));
        }
        if (s.stage() == SelfRun3Engine.Stage.DONE) {
            projection.setActive(false);
            projection.releaseCommittedAttachmentPermissions();
            projection.setStatus("작업 완료");
        } else if (s.stage() == SelfRun3Engine.Stage.STOPPED) {
            projection.setStatus("사용자 중지");
        } else {
            projection.setStatus(statusFor(s));
        }
    }

    private static String statusFor(SelfRun3Engine.State s) {
        return switch (s.stage()) {
            case SETUP -> "SelfRun 3 실행 원장 · Drive 준비";
            case PREPARING -> "SelfRun 3 다음 논리 턴 준비";
            case READY -> "SelfRun 3 ChatGPT 요청 준비";
            case DISPATCHING -> "SelfRun 3 전송 결과 확인";
            case WAITING -> "SelfRun 3 응답 진행 중";
            case RECONCILING -> "SelfRun 3 결과·대화 상태 대조";
            case PAUSED -> "SelfRun 3 일시정지 · 상태 보존";
            case DONE -> "작업 완료";
            case STOPPED -> "사용자 중지";
        };
    }

    private void hardPause(String code, Throwable error) {
        requireMain();
        epoch++;
        main.removeCallbacks(missedProbeRunnable);
        web.quiesce();
        releaseWakeLock();
        projection.setPaused(true);
        projection.setLastError(code, "SelfRun 3 상태를 안전하게 확정하지 못해 자동 전송을 중지했습니다.");
        log.record(projection, "V3_HARD_PAUSE", "code=" + code + ";error="
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
        return !destroyed && projection.active() && !projection.paused() && !projection.userStopped()
                && projection.isV3Run();
    }

    private boolean canRun() { return operationPermitted(); }
    private boolean validEpoch(int expected) { return !destroyed && expected == epoch; }

    private static boolean callbackMatches(SelfRun3Engine.State s, String task, String turn, String request) {
        return s != null && task.equals(s.taskId()) && turn.equals(s.turnId()) && request.equals(s.requestId()) && !s.terminal();
    }

    private static boolean sameTurn(SelfRun3Engine.State s, String task, String turn) {
        return s != null && task.equals(s.taskId()) && turn.equals(s.turnId());
    }

    private static SelfRun3Engine.Event event(SelfRun3Engine.State s, String id,
                                              SelfRun3Engine.Kind kind, JSONObject payload) {
        return new SelfRun3Engine.Event(id, kind, s.taskId(), s.turnId(), payload);
    }

    private static JSONObject requestPayload(String request) {
        JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "requestId", request); return p;
    }

    private static String turnId(String task, int turn) { return task + ":turn:" + turn; }
    private static String safeCode(String raw) {
        String value = raw == null ? "UNKNOWN" : raw.toUpperCase().replaceAll("[^A-Z0-9_:-]", "_");
        return value.isEmpty() ? "UNKNOWN" : value.substring(0, Math.min(100, value.length()));
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("SelfRun3 runtime requires main thread");
    }

    private static final class ResultPendingException extends Exception { }
}
