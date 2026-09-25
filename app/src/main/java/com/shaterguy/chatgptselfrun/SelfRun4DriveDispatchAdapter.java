package com.shaterguy.chatgptselfrun;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import com.google.android.gms.auth.api.identity.AuthorizationResult;

import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 4.0 browser-dispatch port. Android owns state; Drive carries commands to the Termux executor. */
final class SelfRun4DriveDispatchAdapter {
    private static final long TOKEN_REUSE_MS = 45L * 60L * 1000L;
    private static final Set<String> RESUMABLE = Set.of(
            SelfRun4DispatchFile.PREPARE_REQUESTED,
            SelfRun4DispatchFile.READY_TO_SUBMIT,
            SelfRun4DispatchFile.SEND_REQUESTED,
            SelfRun4DispatchFile.SUBMITTED,
            SelfRun4DispatchFile.STARTED);

    private final Context context;
    private final SelfRun3WebAdapter.Listener listener;
    private final SelfRun3Ledger ledger;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SelfRun4DispatchDriveClient drive = new SelfRun4DispatchDriveClient();
    private final SelfRun3RuntimeSettings runtimeSettings;
    private final SelfRunStore store;
    private final SelfRunRunLog log;

    private SelfRun3Engine.State state;
    private boolean preparing;
    private boolean closed;
    private boolean pollInFlight;
    private boolean dispatchedNotified;
    private long preparationAttempt;
    private long prepareStarted;
    private long prepareTimeoutMs;
    private int generation;
    private String accessToken = "";
    private long tokenIssuedElapsed = -1L;

    SelfRun4DriveDispatchAdapter(Context context, SelfRun3WebAdapter.Listener listener,
                                 SelfRun3Ledger ledger) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.ledger = ledger;
        this.runtimeSettings = new SelfRun3RuntimeSettings(this.context);
        this.store = new SelfRunStore(this.context);
        this.log = new SelfRunRunLog(this.context);
    }

    void prepare(SelfRun3Engine.State next) {
        requireMain();
        boolean newRequest = state == null || !state.requestId().equals(next.requestId());
        boolean sameRequestRetry = !newRequest && !preparing;
        state = next;
        closed = false;
        dispatchedNotified = false;
        if (newRequest) preparationAttempt = 0L;
        generation++;
        preparing = true;
        startPreparationTimer();

        int expectedGeneration = generation;
        if (sameRequestRetry && preparationAttempt > 0L) {
            issueFreshPrepare(preparationAttempt + 1L, expectedGeneration);
        } else {
            inspectOrPrepare(expectedGeneration);
        }
    }

    void submit(SelfRun3Engine.State claimed) {
        requireMain();
        if (closed || state == null || !state.requestId().equals(claimed.requestId())
                || !SelfRun3PowerPolicy.maySend(claimed) || preparationAttempt <= 0L) {
            fail("SUBMISSION_STATE_INVALID");
            return;
        }
        state = claimed;
        int expectedGeneration = generation;
        withToken(expectedGeneration, token -> io.execute(() -> {
            try {
                JSONObject current = drive.read(token, dispatchFileId(claimed));
                if (!SelfRun4DispatchFile.matches(current, claimed, preparationAttempt)) {
                    throw new IllegalStateException("DISPATCH_IDENTITY_CHANGED");
                }
                String status = SelfRun4DispatchFile.status(current);
                if (SelfRun4DispatchFile.STARTED.equals(status)) {
                    postStarted(current, expectedGeneration);
                    return;
                }
                if (SelfRun4DispatchFile.READY_TO_SUBMIT.equals(status)) {
                    drive.write(token, dispatchFileId(claimed),
                            SelfRun4DispatchFile.sendRequested(current, claimed, preparationAttempt));
                } else if (!SelfRun4DispatchFile.SEND_REQUESTED.equals(status)
                        && !SelfRun4DispatchFile.SUBMITTED.equals(status)) {
                    throw new IllegalStateException("DISPATCH_NOT_READY");
                }
                schedulePoll(expectedGeneration, true);
            } catch (Throwable error) {
                postFailure(expectedGeneration, "REMOTE_DISPATCH_SEND_FAILED");
            }
        }));
    }

    boolean disposeForConfirmedResultWait(SelfRun3Engine.State ignored) {
        detach();
        return true;
    }

    void detach() {
        requireMain();
        preparing = false;
        pollInFlight = false;
        generation++;
    }

    void quiesce() {
        requireMain();
        preparing = false;
        pollInFlight = false;
        generation++;
    }

    void close() {
        requireMain();
        closed = true;
        preparing = false;
        pollInFlight = false;
        generation++;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    private void startPreparationTimer() {
        prepareStarted = SystemClock.elapsedRealtime();
        long successorRemaining = SelfRun3SuccessorTransitionPolicy.remainingMs(
                state, prepareStarted, System.currentTimeMillis(), currentBootCount());
        prepareTimeoutMs = successorRemaining >= 0L
                ? Math.max(1L, successorRemaining) : runtimeSettings.webPreparationMs();
        final int timerGeneration = generation;
        final String request = state.requestId();
        main.postDelayed(() -> {
            if (closed || !preparing || state == null || timerGeneration != generation
                    || !request.equals(state.requestId())) return;
            SelfRun3Engine.State timedOut = state;
            boolean successor = SelfRun3SuccessorTransitionPolicy.armed(timedOut);
            trace("V4_DISPATCH_TIMEOUT", "turn=" + timedOut.turnId()
                    + ";attempt=" + preparationAttempt + ";timeoutMs=" + prepareTimeoutMs);
            quiesce();
            listener.onFailure(timedOut.taskId(), timedOut.turnId(), timedOut.requestId(),
                    successor ? "SUCCESSOR_TRANSITION_TIMEOUT" : "WEB_PREPARATION_TIMEOUT");
        }, Math.max(1L, prepareTimeoutMs));
    }

    private void inspectOrPrepare(int expectedGeneration) {
        withToken(expectedGeneration, token -> io.execute(() -> {
            try {
                SelfRun3Engine.State current = currentState();
                String fileId = dispatchFileId(current);
                drive.ensureFile(token, fileId, current);
                JSONObject existing = drive.read(token, fileId);
                long attempt = existing.optLong("attempt", -1L);
                boolean sameIdentity = attempt > 0L
                        && SelfRun4DispatchFile.matches(existing, current, attempt)
                        && RESUMABLE.contains(SelfRun4DispatchFile.status(existing));
                if (!sameIdentity) {
                    main.post(() -> {
                        if (!valid(expectedGeneration)) return;
                        preparationAttempt = 1L;
                        issueFreshPrepare(1L, expectedGeneration);
                    });
                    return;
                }
                main.post(() -> {
                    if (!valid(expectedGeneration)) return;
                    preparationAttempt = attempt;
                    handleObserved(existing, expectedGeneration);
                });
            } catch (Throwable error) {
                postFailure(expectedGeneration, "REMOTE_DISPATCH_PREPARE_FAILED");
            }
        }));
    }

    private void issueFreshPrepare(long attempt, int expectedGeneration) {
        if (!valid(expectedGeneration) || attempt <= 0L) return;
        preparationAttempt = attempt;
        withToken(expectedGeneration, token -> io.execute(() -> {
            try {
                SelfRun3Engine.State current = currentState();
                String fileId = dispatchFileId(current);
                drive.ensureFile(token, fileId, current);
                JSONObject body = SelfRun4DispatchFile.prepare(current, fileId, attempt);
                drive.write(token, fileId, body);
                JSONObject readback = drive.read(token, fileId);
                if (!SelfRun4DispatchFile.matches(readback, current, attempt)
                        || !SelfRun4DispatchFile.PREPARE_REQUESTED.equals(
                        SelfRun4DispatchFile.status(readback))) {
                    throw new IllegalStateException("DISPATCH_PREPARE_READBACK_FAILED");
                }
                trace("V4_DISPATCH_PREPARE", "turn=" + current.turnId() + ";attempt=" + attempt);
                schedulePoll(expectedGeneration, false);
            } catch (Throwable error) {
                postFailure(expectedGeneration, "REMOTE_DISPATCH_PREPARE_FAILED");
            }
        }));
    }

    private void schedulePoll(int expectedGeneration, boolean sending) {
        main.post(() -> {
            if (!valid(expectedGeneration)) return;
            main.postDelayed(() -> poll(expectedGeneration, sending),
                    SelfRun3PowerPolicy.WEB_STEP_RETRY_MS);
        });
    }

    private void poll(int expectedGeneration, boolean sending) {
        requireMain();
        if (!valid(expectedGeneration) || pollInFlight) return;
        pollInFlight = true;
        withToken(expectedGeneration, token -> io.execute(() -> {
            try {
                SelfRun3Engine.State current = currentState();
                JSONObject observed = drive.read(token, dispatchFileId(current));
                main.post(() -> {
                    pollInFlight = false;
                    if (!valid(expectedGeneration)) return;
                    handleObserved(observed, expectedGeneration);
                });
            } catch (Throwable error) {
                main.post(() -> {
                    pollInFlight = false;
                    if (!valid(expectedGeneration)) return;
                    schedulePoll(expectedGeneration, sending);
                });
            }
        }));
    }

    private void handleObserved(JSONObject observed, int expectedGeneration) {
        requireMain();
        if (!valid(expectedGeneration) || state == null
                || !SelfRun4DispatchFile.matches(observed, state, preparationAttempt)) {
            schedulePoll(expectedGeneration, state != null && state.flag("sendClaimed"));
            return;
        }
        String status = SelfRun4DispatchFile.status(observed);
        if (SelfRun4DispatchFile.ERROR.equals(status)) {
            String code = SelfRun4DispatchFile.errorCode(observed);
            fail(code.isEmpty() ? "REMOTE_DISPATCH_SERVER_ERROR" : code);
            return;
        }
        if (SelfRun4DispatchFile.STARTED.equals(status)) {
            postStarted(observed, expectedGeneration);
            return;
        }
        if (SelfRun4DispatchFile.SUBMITTED.equals(status) && !dispatchedNotified) {
            dispatchedNotified = true;
            listener.onDispatched(state.taskId(), state.turnId(), state.requestId());
        }
        if (SelfRun4DispatchFile.READY_TO_SUBMIT.equals(status)) {
            if (state.flag("sendClaimed")) {
                submit(state);
            } else {
                listener.onPrepared(state.taskId(), state.turnId(), state.requestId());
            }
            return;
        }
        schedulePoll(expectedGeneration, state.flag("sendClaimed"));
    }

    private void postStarted(JSONObject observed, int expectedGeneration) {
        main.post(() -> {
            if (!valid(expectedGeneration) || state == null) return;
            String url = SelfRun4DispatchFile.conversationUrl(observed);
            if (!SelfRun3WebAdapter.trusted(url) || SelfRunScript.conversationId(url).isEmpty()) {
                fail("REMOTE_CONVERSATION_URL_INVALID");
                return;
            }
            if (!dispatchedNotified) {
                dispatchedNotified = true;
                listener.onDispatched(state.taskId(), state.turnId(), state.requestId());
            }
            trace("V4_CONVERSATION_CREATE", "turn=" + state.turnId()
                    + ";attempt=" + preparationAttempt + ";status=confirmed");
            listener.onConversation(state.taskId(), state.turnId(), url);
            listener.onStarted(state.taskId(), state.turnId(), state.requestId());
            quiesce();
        });
    }

    private void withToken(int expectedGeneration, java.util.function.Consumer<String> action) {
        if (!valid(expectedGeneration)) return;
        long age = tokenIssuedElapsed < 0L ? Long.MAX_VALUE
                : SystemClock.elapsedRealtime() - tokenIssuedElapsed;
        if (!accessToken.isEmpty() && age >= 0L && age < TOKEN_REUSE_MS) {
            action.accept(accessToken);
            return;
        }
        DriveAuthorization.requestSilently(context, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                if (!valid(expectedGeneration)) return;
                accessToken = DriveAuthorization.accessToken(result);
                tokenIssuedElapsed = accessToken.isEmpty() ? -1L : SystemClock.elapsedRealtime();
                if (accessToken.isEmpty()) {
                    fail("REMOTE_DISPATCH_AUTH_FAILED");
                    return;
                }
                action.accept(accessToken);
            }

            @Override public void onResolutionRequired(PendingIntent pendingIntent) {
                if (valid(expectedGeneration)) fail("AUTH_REQUIRED");
            }

            @Override public void onFailure(Throwable error) {
                if (valid(expectedGeneration)) fail("REMOTE_DISPATCH_AUTH_FAILED");
            }
        });
    }

    private SelfRun3Engine.State currentState() {
        SelfRun3Engine.State current = state == null ? null
                : ledger.loadExecution(state.taskId(), state.turnId());
        if (current == null || state == null || !state.requestId().equals(current.requestId())) {
            throw new IllegalStateException("STALE_DISPATCH_TURN");
        }
        return current;
    }

    private static String dispatchFileId(SelfRun3Engine.State value) {
        String id = value == null ? "" : value.resource("dispatchFileId");
        if (!DriveApiClient.validFileId(id)) throw new IllegalStateException("dispatch file not prepared");
        return id;
    }

    private void postFailure(int expectedGeneration, String code) {
        main.post(() -> {
            if (valid(expectedGeneration)) fail(code);
        });
    }

    private void fail(String code) {
        if (closed || state == null) return;
        SelfRun3Engine.State failed = state;
        trace("V4_DISPATCH_FAILURE", "turn=" + failed.turnId() + ";code=" + code);
        quiesce();
        listener.onFailure(failed.taskId(), failed.turnId(), failed.requestId(), code);
    }

    private boolean valid(int expectedGeneration) {
        return !closed && preparing && state != null && expectedGeneration == generation;
    }

    private int currentBootCount() {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Throwable unavailable) {
            return -1;
        }
    }

    private void trace(String event, String detail) {
        try { log.record(store, event, detail); } catch (Throwable ignored) { }
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("SelfRun4 dispatch adapter requires main thread");
        }
    }
}
