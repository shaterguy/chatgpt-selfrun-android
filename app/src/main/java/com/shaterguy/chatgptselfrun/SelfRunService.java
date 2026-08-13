package com.shaterguy.chatgptselfrun;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.android.gms.auth.api.identity.AuthorizationResult;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.net.ProtocolException;
import javax.net.ssl.SSLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/** Drive V1-only runtime. The legacy assistant-DOM state machine is intentionally not reachable. */
public final class SelfRunService extends Service {
    static final String ACTION_RUN = BuildConfig.APPLICATION_ID + ".RUN";
    static final String ACTION_PAUSE = BuildConfig.APPLICATION_ID + ".PAUSE";
    static final String ACTION_RESUME = BuildConfig.APPLICATION_ID + ".RESUME";
    private static final int NOTIFICATION_ID = 17021;
    private static final long SESSION_BOUND_POLL_MS = 15_000L;
    private static final long NORMAL_POLL_MS = 60_000L;
    private static final long SESSION_BIND_TIMEOUT_MS = 5 * 60_000L;
    static final long CONTINUATION_GUARD_MS = 120_000L;
    private static final long[] BACKOFF = {15_000L, 30_000L, 60_000L, 120_000L, 240_000L};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable driveRunnable = this::pollDrive;
    private final Runnable driveRetryRunnable = this::retryDrive;
    private final Runnable webRunnable = this::runWebStep;
    private final Runnable guardRunnable = this::guardElapsed;
    private SelfRunStore store;
    private SelfRunRunLog runLog;
    private DriveApiClient drive;
    private HeadlessWebViewHost host;
    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private boolean driveInFlight;
    private boolean authorizationInFlight;
    private boolean domInFlight;
    private int generation;
    private int automationEpoch;
    private int driveOperationEpoch;
    private int retryAttempt;
    private String accessToken = "";
    private String verifiedDriveAccountId = "";
    private boolean destroyed;

    @Override public void onCreate() {
        super.onCreate();
        store = new SelfRunStore(this);
        runLog = new SelfRunRunLog(this);
        drive = new DriveApiClient();
        NotificationHelper.ensureChannel(this);
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                BuildConfig.APPLICATION_ID + ":selfrun-drive-active");
        wakeLock.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RUN : intent.getAction();
        if (store.terminalSideEffectPending()) {
            replayTerminalSideEffect();
            return store.active() ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) { pauseFromUi(); return store.active() ? START_STICKY : START_NOT_STICKY; }
        if (ACTION_RESUME.equals(action)) { resumeFromUi(); return store.active() ? START_STICKY : START_NOT_STICKY; }
        if (intent != null && !ACTION_RUN.equals(action)) { stopSelf(startId); return START_NOT_STICKY; }
        if (!store.active()) { stopRuntime(); return START_NOT_STICKY; }
        startForegroundCompat();
        if (store.paused()) { stopAutomationCallbacks(); releaseWakeLock(); return START_STICKY; }
        runLog.record(store, "SERVICE_START", intent == null ? "sticky_recreate" : "explicit_start");
        handler.post(this::resumeStateMachine);
        return START_STICKY;
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, NotificationHelper.active(this, store.status()),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else startForeground(NOTIFICATION_ID, NotificationHelper.active(this, store.status()));
    }

    private boolean canRun() {
        return store.active() && !store.paused() && !store.userStopped()
                && !SelfRunStore.PHASE_DONE.equals(store.phase())
                && !SelfRunStore.PHASE_IDLE.equals(store.phase());
    }

    private void resumeStateMachine() {
        if (!canRun()) return;
        String phase = store.phase();
        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)
                && SelfRunStore.SUBMISSION_CONFIRMED.equals(store.submissionState())) {
            finalizeConfirmedContinuation();
        } else if (drivePhase(phase)) authorizeAndRunDrive();
        else if (SelfRunStore.PHASE_DRIVE_COMMIT_GUARD.equals(phase)) scheduleGuard();
        else ensureWebView();
    }

    private static boolean drivePhase(String phase) {
        return SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK.equals(phase)
                || SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK.equals(phase)
                || SelfRunStore.PHASE_JOB_ID_CREATE.equals(phase)
                || SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE.equals(phase)
                || SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE.equals(phase)
                || SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT.equals(phase)
                || SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK.equals(phase)
                || SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(phase);
    }

    private void authorizeAndRunDrive() {
        if (!canRun() || !drivePhase(store.phase()) || driveInFlight || authorizationInFlight) return;
        final int epoch = automationEpoch;
        authorizationInFlight = true;
        DriveAuthorization.requestSilently(this, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                authorizationInFlight = false;
                if (!canRun() || epoch != automationEpoch || !drivePhase(store.phase())) return;
                accessToken = DriveAuthorization.accessToken(result);
                if (accessToken.isEmpty()) { pauseError("DRIVE_ACCOUNT_REAUTHORIZE_REQUIRED", "Drive 액세스 토큰을 얻지 못했습니다."); return; }
                executeDriveStep(epoch);
            }
            @Override public void onResolutionRequired(PendingIntent ignored) {
                authorizationInFlight = false;
                if (!canRun() || epoch != automationEpoch) return;
                pauseError("DRIVE_ACCOUNT_REAUTHORIZE_REQUIRED", "앱 설정에서 Drive 저장 위치를 다시 연결하세요.");
            }
            @Override public void onFailure(Throwable error) {
                authorizationInFlight = false;
                if (!canRun() || epoch != automationEpoch) return;
                pauseError("DRIVE_ACCOUNT_CHECK_FAILED", "Drive 계정 확인에 실패했습니다.");
            }
        });
    }

    private void executeDriveStep(int epoch) {
        if (!canRun() || epoch != automationEpoch || !drivePhase(store.phase()) || driveInFlight) return;
        driveInFlight = true;
        driveOperationEpoch = epoch;
        acquireWakeLock();
        io.execute(() -> {
            try {
                if (!store.runDriveAccountId().equals(verifiedDriveAccountId)) {
                    String accountId = drive.getAccountPermissionId(accessToken);
                    if (!canApplyDriveResult()) return;
                    if (!accountId.equals(store.runDriveAccountId())) {
                        pauseError("DRIVE_ACCOUNT_MISMATCH", "바인딩한 Drive 계정과 현재 승인 계정이 다릅니다.");
                        return;
                    }
                    verifiedDriveAccountId = accountId;
                }
                if (SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())) {
                    pollDriveNow();
                    retryAttempt = 0;
                    return;
                }
                do {
                    if (!canApplyDriveResult()) return;
                    String prior = store.phase();
                    runDriveStep();
                    if (prior.equals(store.phase()) || SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())) break;
                } while (canApplyDriveResult() && drivePhase(store.phase()));
                retryAttempt = 0;
            } catch (Throwable error) {
                handleDriveFailure(error);
            } finally {
                driveInFlight = false;
                accessToken = "";
                releaseWakeLock();
                if (!destroyed && epoch != automationEpoch) handler.post(this::resumeStateMachine);
            }
        });
    }

    private boolean canApplyDriveResult() {
        return canRun() && driveOperationEpoch == automationEpoch && drivePhase(store.phase());
    }

    private void runDriveStep() throws Exception {
        if (!canRun()) return;
        switch (store.phase()) {
            case SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK -> {
                transition(SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK, "Drive 기준 폴더 확인 중", "account_authorized");
            }
            case SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK -> checkBaseFolder();
            case SelfRunStore.PHASE_JOB_ID_CREATE -> {
                // Job ID was issued by the UI and persisted before service start.
                transition(SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE, "Drive Job 폴더 생성 중", "job_id_persisted");
            }
            case SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE -> createOrRecoverJobFolder();
            case SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE -> createOrRecoverDocument();
            case SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT -> initializeDocument();
            case SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK -> verifyInitialDocument();
            case SelfRunStore.PHASE_WAIT_DRIVE_COMMIT -> pollDriveNow();
            default -> pauseError("DRIVE_STATE_INVALID", "알 수 없는 Drive 단계: " + store.phase());
        }
    }

    private void checkBaseFolder() throws Exception {
        String base = store.runBaseFolderId();
        if (!DriveApiClient.validFileId(base)) {
            pauseError("DRIVE_BASE_FOLDER_REBIND_REQUIRED", "Runs 기준 폴더 ID가 없습니다.");
            return;
        }
        DriveApiClient.Metadata metadata = drive.getMetadata(accessToken, base);
        if (!canApplyDriveResult()) return;
        if (metadata.trashed || !DriveApiClient.MIME_FOLDER.equals(metadata.mimeType)
                || !metadata.isAppAuthorized || !metadata.canAddChildren || metadata.shared) {
            pauseError("DRIVE_BASE_FOLDER_REBIND_REQUIRED", "Runs 기준 폴더가 접근 불가능합니다.");
            return;
        }
        transition(SelfRunStore.PHASE_JOB_ID_CREATE, "Job ID 확인 완료", "base_folder_verified");
    }

    private void createOrRecoverJobFolder() throws Exception {
        String base = store.runBaseFolderId();
        String folderId = store.jobFolderId();
        if (folderId.isEmpty()) {
            if (!SelfRunStore.CREATION_NONE.equals(store.creationStage())) {
                throw new IllegalStateException("folder creation state has no reserved id");
            }
            folderId = drive.generateFolderId(accessToken);
            if (!canApplyDriveResult()) return;
            store.reserveJobFolderId(folderId);
        }

        DriveApiClient.Metadata metadata = null;
        try {
            metadata = drive.getMetadata(accessToken, folderId);
        } catch (DriveApiClient.ApiException api) {
            if (api.status != 404) throw api;
        }
        if (metadata == null) {
            String stage = store.creationStage();
            if (!(SelfRunStore.CREATION_FOLDER_ID_RESERVED.equals(stage)
                    || SelfRunStore.CREATION_FOLDER_CREATING.equals(stage))) {
                throw new IllegalStateException("created job folder disappeared");
            }
            if (SelfRunStore.CREATION_FOLDER_ID_RESERVED.equals(stage)) store.markJobFolderCreating();
            try {
                metadata = drive.createJobFolder(accessToken, folderId, store.runId(), base);
            } catch (DriveApiClient.ApiException api) {
                if (api.status != 409) throw api;
                metadata = drive.getMetadata(accessToken, folderId);
            }
        }
        if (!folderId.equals(metadata.id)) {
            throw new IllegalStateException("Drive returned a different reserved folder id");
        }
        store.saveJobFolder(folderId);
        if (!canApplyDriveResult()) return;
        verifyMetadata(drive.getMetadata(accessToken, folderId), store.runId(),
                DriveApiClient.MIME_FOLDER, base, "job_folder");
        transition(SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE, "실행턴 Google Docs 생성 중", "job_folder_ready");
    }

    private void createOrRecoverDocument() throws Exception {
        String parent = store.jobFolderId();
        if (!store.turnDocumentId().isEmpty()) {
            verifyMetadata(drive.getMetadata(accessToken, store.turnDocumentId()), store.runId(),
                    DriveApiClient.MIME_DOCUMENT, parent, "turn_document");
        } else if (SelfRunStore.CREATION_DOCUMENT_CREATING.equals(store.creationStage())) {
            if (canApplyDriveResult()) pauseError("DRIVE_DOCUMENT_CREATE_RESULT_UNKNOWN",
                    "네이티브 Google Docs 생성 결과를 확정할 수 없어 검색·재생성 없이 중단했습니다.");
            return;
        } else {
            store.setCreationStage(SelfRunStore.CREATION_DOCUMENT_CREATING);
            DriveApiClient.Metadata created;
            try {
                created = drive.createTurnDocument(accessToken, store.runId(), parent);
            } catch (DriveApiClient.OutcomeUnknownException unknown) {
                if (canApplyDriveResult()) pauseError("DRIVE_DOCUMENT_CREATE_RESULT_UNKNOWN",
                        "네이티브 Google Docs 생성 응답이 유실되어 검색·재생성 없이 중단했습니다.");
                return;
            } catch (DriveApiClient.ApiException definiteFailure) {
                store.resetDocumentCreateAfterDefiniteFailure();
                throw definiteFailure;
            }
            store.saveTurnDocument(created.id, documentUrl(created));
            if (!canApplyDriveResult()) return;
            verifyMetadata(drive.getMetadata(accessToken, created.id), store.runId(),
                    DriveApiClient.MIME_DOCUMENT, parent, "turn_document");
        }
        transition(SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT, "실행턴 문서 초기화 중", "turn_document_ready");
    }

    private void initializeDocument() throws Exception {
        String existing = drive.readDocumentText(accessToken, store.turnDocumentId());
        if (!canApplyDriveResult()) return;
        if (existing.trim().isEmpty()) {
            drive.initializeDocument(accessToken, store.turnDocumentId(), DriveInitialDocument.create(
                    store.runId(), store.turnDocumentId(), store.jobFolderId(), store.runBaseFolderId()));
        } else if (!DriveInitialDocument.verifies(existing, store.runId(), store.turnDocumentId(),
                store.jobFolderId(), store.runBaseFolderId())) {
            throw new IllegalStateException("nonempty execution document has invalid initial block");
        }
        if (!canApplyDriveResult()) return;
        transition(SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK, "실행턴 문서 readback 검증 중", "document_initialized");
    }

    private void verifyInitialDocument() throws Exception {
        DriveApiClient.Metadata metadata = drive.getMetadata(accessToken, store.turnDocumentId());
        verifyMetadata(metadata, store.runId(), DriveApiClient.MIME_DOCUMENT, store.jobFolderId(), "turn_document");
        String body = drive.readDocumentText(accessToken, store.turnDocumentId());
        if (!DriveInitialDocument.verifies(body, store.runId(), store.turnDocumentId(),
                store.jobFolderId(), store.runBaseFolderId())) throw new IllegalStateException("Drive document readback mismatch");
        if (!canApplyDriveResult()) return;
        store.updateDriveSeen(metadata.version, metadata.modifiedTime);
        transition(SelfRunStore.PHASE_BOOTSTRAP, "Drive 준비 완료 · ChatGPT 새 대화 준비", "drive_readback_verified");
        handler.post(this::ensureWebView);
    }

    private void pollDrive() {
        if (SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase()) && canRun()) authorizeAndRunDrive();
    }

    private void pollDriveNow() throws Exception {
        DriveApiClient.Metadata metadata = drive.getPollMetadata(accessToken, store.turnDocumentId());
        if (!canApplyDriveResult()) return;
        if (metadata.trashed || metadata.shared || !DriveApiClient.MIME_DOCUMENT.equals(metadata.mimeType)
                || !store.jobFolderId().equals(metadata.parentId)) {
            pauseError("DRIVE_DOCUMENT_INVALID", "실행턴 문서가 삭제되었거나 parent가 변경되었습니다.");
            return;
        }
        boolean changed = !metadata.version.equals(store.lastSeenDriveVersion())
                || !metadata.modifiedTime.equals(store.lastSeenModifiedTime());
        if (!changed) {
            if (sessionBindTimedOut()) {
                pauseError("DRIVE_SESSION_BIND_TIMEOUT", "5분 안에 SESSION_BOUND를 확인하지 못했습니다.");
            } else scheduleDrivePoll();
            return;
        }
        String text = drive.readDocumentText(accessToken, store.turnDocumentId());
        if (!canApplyDriveResult()) return;
        if (!store.sessionBound() && DriveCommitParser.hasSessionBound(text, store.runId(), store.expectedTurn())) {
            store.setSessionBound(true);
            runLog.record(store, "DRIVE_SESSION_BOUND", "turn=" + store.expectedTurn());
        }
        DriveCommitParser.Result result = DriveCommitParser.latest(text, store.runId(),
                store.expectedTurn(), store.lastConsumedEventSeq(), store.mode());
        if (result.status == DriveCommitParser.Status.FUTURE_TURN) {
            pauseError("DRIVE_PROTOCOL_TURN_MISMATCH", result.reason); return;
        }
        if (result.status == DriveCommitParser.Status.MALFORMED) {
            pauseError("DRIVE_COMMIT_MALFORMED", result.reason); return;
        }
        // For commits, pending/terminal state must be durable before advancing the Drive cursor.
        if (result.status == DriveCommitParser.Status.ACCEPTED) {
            acceptCommit(result.commit);
            store.updateDriveSeen(metadata.version, metadata.modifiedTime);
            return;
        }
        // Non-commit body changes advance only after every parser guard succeeded.
        store.updateDriveSeen(metadata.version, metadata.modifiedTime);
        if (sessionBindTimedOut()) {
            pauseError("DRIVE_SESSION_BIND_TIMEOUT", "5분 안에 SESSION_BOUND를 확인하지 못했습니다."); return;
        }
        scheduleDrivePoll();
    }

    private void acceptCommit(DriveCommitParser.Commit commit) {
        runLog.record(store, "DRIVE_COMMIT_ACCEPTED", "turn=" + commit.turn + ";seq=" + commit.eventSeq + ";kind=" + commit.kind);
        if (commit.signal.type == SelfRunProtocol.Type.NEXT) {
            boolean restoring = commit.id().equals(store.pendingCommitId())
                    && commit.eventSeq == store.pendingEventSeq() && commit.turn == store.pendingTurn();
            if (!restoring) {
                long now = System.currentTimeMillis();
                store.detectEvent(commit, now, now + CONTINUATION_GUARD_MS);
            }
            if (SelfRunStore.EVENT_DETECTED.equals(store.submissionState())) store.markGuarding();
            transition(SelfRunStore.PHASE_DRIVE_COMMIT_GUARD, "Drive commit 안전 지연 · 120초", "continue_commit");
            scheduleGuard();
        } else handleTerminal(commit);
    }

    private void handleTerminal(DriveCommitParser.Commit commit) {
        store.consumeTerminal(commit);
        handler.post(() -> applyTerminalSideEffects(commit));
    }

    private void replayTerminalSideEffect() {
        startForegroundCompat();
        String type = store.terminalSideEffectType();
        handler.post(() -> {
            switch (type) {
                case "DONE" -> finishDoneSideEffect();
                case "PAUSE" -> finishPersistedTerminalPause("DRIVE_PAUSE", false);
                case "USER_ACTION" -> finishPersistedTerminalPause("DRIVE_USER_ACTION", true);
                case "ERROR" -> finishPersistedTerminalPause("DRIVE_RUN_ERROR", true);
                default -> {
                    store.setLastError("TERMINAL_SIDE_EFFECT_INVALID", "terminal 후속 상태가 손상되었습니다.");
                    store.acknowledgeTerminalSideEffect();
                    stopRuntime();
                }
            }
        });
    }

    private void applyTerminalSideEffects(DriveCommitParser.Commit commit) {
        switch (commit.signal.type) {
            case DONE -> {
                finishDoneSideEffect();
            }
            case PAUSE -> finishPersistedTerminalPause("DRIVE_PAUSE", false);
            case USER_ACTION -> finishPersistedTerminalPause("DRIVE_USER_ACTION", true);
            case ERROR -> finishPersistedTerminalPause("DRIVE_RUN_ERROR", true);
            default -> pauseError("DRIVE_COMMIT_INVALID", "terminal 처리 불가");
        }
    }

    private void finishDoneSideEffect() {
        runLog.record(store, "TERMINAL", "done_commit");
        NotificationHelper.notifyUser(this, "완료", store.runId());
        store.acknowledgeTerminalSideEffect();
        stopRuntime();
    }

    private void finishPersistedTerminalPause(String cause, boolean notify) {
        stopAutomationCallbacks();
        releaseWakeLock();
        pauseWebView();
        runLog.record(store, "PAUSED", cause + ";webview=preserved;drive_ids=preserved");
        startForegroundCompat();
        if (notify) NotificationHelper.notifyUser(this, "확인 필요", store.status());
        store.acknowledgeTerminalSideEffect();
    }

    private void scheduleGuard() {
        releaseWakeLock();
        handler.removeCallbacks(webRunnable);
        handler.removeCallbacks(guardRunnable);
        long detectedAt = store.commitDetectedAt(), dueAt = store.guardDueAt();
        long delay = Math.max(0L, dueAt - System.currentTimeMillis());
        if (store.pendingEventSeq() < 1 || store.pendingCommitId().isEmpty()
                || detectedAt <= 0 || dueAt - detectedAt < CONTINUATION_GUARD_MS
                || dueAt - detectedAt > 180_000L) {
            pauseError("DRIVE_PENDING_EVENT_INVALID", "guard 복원 정보가 불완전합니다.");
            return;
        }
        handler.postDelayed(guardRunnable, delay);
    }

    private void guardElapsed() {
        if (canRun() && SelfRunStore.PHASE_DRIVE_COMMIT_GUARD.equals(store.phase())) {
            transition(nextAfterGuard(), "continuation 설정 준비", "guard_elapsed");
            ensureWebView();
        }
    }

    private String nextAfterGuard() {
        SelfRunProtocol.Signal signal = SelfRunProtocol.parseLatest(store.pendingSignalRaw(), store.runId(), store.mode());
        if (SelfRunStore.MODE_WORK.equals(store.mode()) && signal.type == SelfRunProtocol.Type.NEXT) {
            store.setRole(signal.role); store.setPendingModel(signal.model); store.setPendingReasoning(signal.reasoning);
            return SelfRunStore.PHASE_APPLY_PREFS;
        }
        if (signal.type == SelfRunProtocol.Type.NEXT) store.setRole(signal.role);
        return SelfRunStore.PHASE_SEND_CONTINUE;
    }

    private void ensureWebView() {
        if (!canRun() || !isWebAutomationPhase(store.phase())) return;
        String target = store.conversationUrl().isEmpty() ? store.projectUrl() : store.conversationUrl();
        if (target.isEmpty()) { pauseError("TARGET_MISSING", "ChatGPT 대상 URL이 없습니다."); return; }
        acquireWakeLock();
        if (webView != null) { scheduleWeb(250L); return; }
        launchWebView(target);
    }

    private void launchWebView(String target) {
        cleanupWebView();
        try {
            host = HeadlessWebViewHost.create(this); webView = host.webView(); WebViewConfig.applyAutomation(webView);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, Bitmap favicon) { generation++; domInFlight = false; }
                @Override public void onPageFinished(WebView view, String url) { scheduleWeb(800L); }
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (!request.isForMainFrame()) return false;
                    String requested = String.valueOf(request.getUrl());
                    boolean allowed = store.conversationUrl().isEmpty()
                            ? sameProject(store.projectUrl(), requested) : sameConversation(store.conversationUrl(), requested);
                    if (!allowed) postWebCallback(SelfRunService.this::restoreCanonical, 800L);
                    return !allowed;
                }
                @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse s) {
                    if (r.isForMainFrame() && s.getStatusCode() == 429) scheduleWeb(30_000L);
                }
                @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                    if (r.isForMainFrame() && canRun() && isWebAutomationPhase(store.phase()))
                        postWebCallback(() -> { if (v == webView) v.loadUrl(canonicalUrl()); }, 3_000L);
                }
                @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) { h.cancel(); pauseError("WEBVIEW_SSL", "SSL 오류"); }
                @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                    cleanupWebView();
                    if (!store.paused() && isWebAutomationPhase(store.phase()))
                        postWebCallback(SelfRunService.this::ensureWebView, 2_000L);
                    return true;
                }
            });
            webView.loadUrl(target);
        } catch (Throwable error) {
            cleanupWebView(); postWebCallback(this::ensureWebView, 2_500L);
        }
    }

    private void postWebCallback(Runnable callback, long delay) {
        int epoch = automationEpoch;
        handler.postDelayed(() -> {
            if (epoch == automationEpoch && canRun() && isWebAutomationPhase(store.phase())) callback.run();
        }, delay);
    }

    private void runWebStep() {
        if (!canRun() || !isWebAutomationPhase(store.phase()) || webView == null || domInFlight) return;
        if (!routeAcceptable(webView.getUrl())) { restoreCanonical(); return; }
        String phase = store.phase();
        String script;
        switch (phase) {
            case SelfRunStore.PHASE_BOOTSTRAP -> script = SelfRunDom.prepareInitialContext(store.projectUrl(), store.mode(), store.runId());
            case SelfRunStore.PHASE_BOOTSTRAP_MODEL -> script = WorkPreferenceDom.modelForProject(store.projectUrl(), "sol");
            case SelfRunStore.PHASE_BOOTSTRAP_REASONING -> script = WorkPreferenceDom.reasoningForProject(store.projectUrl(), "xhigh");
            case SelfRunStore.PHASE_BOOTSTRAP_SEND -> script = SelfRunStore.BOOTSTRAP_SUBMISSION_STARTED
                    .equals(store.bootstrapSubmissionState())
                    ? SelfRunDom.checkDriveInitialSubmitted(store.projectUrl(), driveBootstrap(), store.runId())
                    : SelfRunDom.sendDriveInitial(store.projectUrl(), driveBootstrap(), store.runId());
            case SelfRunStore.PHASE_APPLY_PREFS -> script = WorkPreferenceDom.modelForConversation(store.conversationUrl(), store.pendingModel());
            case SelfRunStore.PHASE_APPLY_REASONING -> script = WorkPreferenceDom.reasoningForConversation(store.conversationUrl(), store.pendingReasoning());
            case SelfRunStore.PHASE_SEND_CONTINUE -> {
                String prompt = continuationPrompt();
                if (SelfRunStore.SUBMISSION_STARTED.equals(store.submissionState())) {
                    script = SelfRunDom.checkDriveTurnSubmitted(store.conversationUrl(), store.pendingCommitId());
                } else script = SelfRunDom.prepareDriveTurn(store.conversationUrl(), prompt, store.pendingCommitId());
            }
            default -> { pauseError("WEB_STATE_INVALID", "Drive V1 WebView 단계 오류: " + phase); return; }
        }
        evaluate(phase, script);
    }

    private void evaluate(String phase, String script) {
        WebView active = webView; int epoch = generation; domInFlight = true;
        active.evaluateJavascript(script, raw -> {
            if (active != webView || epoch != generation) return;
            domInFlight = false; if (!canRun()) return;
            JSONObject result = parse(raw); String status = result.optString("status", "SCRIPT_ERROR");
            if ("TARGET_ERROR".equals(status)) { restoreCanonical(); return; }
            if ("AUTH_REQUIRED".equals(status)) { pauseError("CHATGPT_AUTH_REQUIRED", "SelfRun Drive에서 ChatGPT 로그인이 필요합니다."); return; }
            if ("MARKER_FAILED".equals(status)) { pauseError("SUBMISSION_MARKER_FAILED", result.optString("detail")); return; }
            if ("SUBMISSION_AMBIGUOUS".equals(status) || "SUBMISSION_PENDING".equals(status)) {
                enterPreservedPause("SUBMISSION_AMBIGUOUS", "continuation 제출 결과 확인 필요 · 자동 재전송 차단", false); return;
            }
            if ("BOOTSTRAP_SUBMISSION_AMBIGUOUS".equals(status)
                    || "BOOTSTRAP_SUBMISSION_PENDING".equals(status)) {
                enterPreservedPause("BOOTSTRAP_SUBMISSION_RESULT_UNKNOWN",
                        "첫 요청 제출 결과가 불명확해 자동 재전송을 차단했습니다.", false);
                return;
            }
            if ("BOOTSTRAP_SUBMITTED".equals(status)) {
                if (SelfRunStore.BOOTSTRAP_SUBMISSION_STARTED.equals(store.bootstrapSubmissionState())
                        && store.bootstrapSubmittedAt() > 0
                        && System.currentTimeMillis() - store.bootstrapSubmittedAt() >= 120_000L) {
                    enterPreservedPause("BOOTSTRAP_SUBMISSION_RESULT_UNKNOWN",
                            "첫 요청은 클릭됐지만 conversation URL을 확정하지 못해 자동 재전송을 차단했습니다.", false);
                } else scheduleWeb(1_200L);
                return;
            }
            if ("UI_WAIT".equals(status) || "WAIT".equals(status) || "SUBMITTED".equals(status)) {
                if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)
                        && SelfRunStore.SUBMISSION_STARTED.equals(store.submissionState())
                        && store.submissionStartedAt() > 0
                        && System.currentTimeMillis() - store.submissionStartedAt() >= 120_000L) {
                    enterPreservedPause("SUBMISSION_CONFIRMATION_TIMEOUT",
                            "continuation 사용자 메시지 확인 시간 초과 · 자동 재전송 차단", false);
                    return;
                }
                scheduleWeb("WAIT".equals(status) ? 2_000L : 1_200L); return;
            }
            handleWebResult(phase, status, result);
        });
    }

    private void handleWebResult(String phase, String status, JSONObject result) {
        if (SelfRunStore.PHASE_BOOTSTRAP.equals(phase) && "READY".equals(status)) {
            transition(SelfRunStore.MODE_WORK.equals(store.mode()) ? SelfRunStore.PHASE_BOOTSTRAP_MODEL : SelfRunStore.PHASE_BOOTSTRAP_SEND,
                    "ChatGPT bootstrap 설정 준비", "context_ready"); scheduleWeb(250L); return;
        }
        if (SelfRunStore.PHASE_BOOTSTRAP_MODEL.equals(phase) && "READY".equals(status)) {
            transition(SelfRunStore.PHASE_BOOTSTRAP_REASONING, "첫 턴 Work 추론 적용", "model_ready"); scheduleWeb(250L); return;
        }
        if (SelfRunStore.PHASE_BOOTSTRAP_REASONING.equals(phase) && "READY".equals(status)) {
            transition(SelfRunStore.PHASE_BOOTSTRAP_SEND, "첫 프롬프트 전송 준비", "reasoning_ready"); scheduleWeb(250L); return;
        }
        if (SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase) && "CONFIRMED".equals(status)) {
            String url = result.optString("conversationUrl", result.optString("url", ""));
            if (SelfRunScript.conversationId(url).isEmpty()) { scheduleWeb(1_000L); return; }
            if (SelfRunStore.BOOTSTRAP_NOT_STARTED.equals(store.bootstrapSubmissionState())) {
                store.markBootstrapSubmissionStarted();
            }
            store.confirmBootstrap(url);
            runLog.record(store, "STATE_TRANSITION", "to=WAIT_DRIVE_COMMIT;reason=bootstrap_confirmed");
            startForegroundCompat();
            releaseWakeLock(); scheduleDrivePoll(); return;
        }
        if (SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase) && "READY_TO_SUBMIT".equals(status)) {
            store.markBootstrapSubmissionStarted();
            evaluate(SelfRunStore.PHASE_BOOTSTRAP_SEND,
                    SelfRunDom.clickPreparedDriveInitial(store.projectUrl(), driveBootstrap(), store.runId()));
            return;
        }
        if (SelfRunStore.PHASE_APPLY_PREFS.equals(phase) && "READY".equals(status)) {
            transition(SelfRunStore.PHASE_APPLY_REASONING, "다음 턴 추론 적용", "model_ready"); scheduleWeb(250L); return;
        }
        if (SelfRunStore.PHASE_APPLY_REASONING.equals(phase) && "READY".equals(status)) {
            transition(SelfRunStore.PHASE_SEND_CONTINUE, "continuation 준비", "reasoning_ready"); scheduleWeb(250L); return;
        }
        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)) {
            if ("CONFIRMED".equals(status)) { confirmContinuation(); return; }
            if ("READY_TO_SUBMIT".equals(status)) { startAndClickContinuation(); return; }
        }
        scheduleWeb(750L);
    }

    private void startAndClickContinuation() {
        // Must be durable before the click. A crash from this point is resolved by user-message marker only.
        store.markSubmissionStarted();
        String script = SelfRunDom.clickPreparedDriveTurn(store.conversationUrl(), continuationPrompt(), store.pendingCommitId());
        evaluate(SelfRunStore.PHASE_SEND_CONTINUE, script);
    }

    private void confirmContinuation() {
        String commitId = store.pendingCommitId();
        store.markSubmissionConfirmed(commitId);
        finalizeConfirmedContinuation();
    }

    private void finalizeConfirmedContinuation() {
        String commitId = store.pendingCommitId();
        store.consumeContinuation(commitId);
        runLog.record(store, "STATE_TRANSITION", "to=WAIT_DRIVE_COMMIT;reason=continuation_confirmed");
        startForegroundCompat();
        releaseWakeLock(); scheduleDrivePoll();
    }

    private String driveBootstrap() {
        return SelfRunProtocol.bootstrapDrive(store.runId(), store.mode(), store.requirement(), store.runBaseFolderId(),
                store.jobFolderId(), store.turnDocumentId(), store.turnDocumentUrl(), store.expectedTurn());
    }

    private String continuationPrompt() {
        return SelfRunProtocol.continuation(store.runId()) + "\nSELF_RUN_DRIVE_COMMIT_ID=" + store.pendingCommitId()
                + "\nDRIVE_EXPECTED_TURN=" + (store.pendingTurn() + 1);
    }

    private void scheduleDrivePoll() {
        handler.removeCallbacks(driveRunnable); releaseWakeLock();
        long delay = store.sessionBound() ? NORMAL_POLL_MS : SESSION_BOUND_POLL_MS;
        if (canRun()) handler.postDelayed(driveRunnable, delay);
    }

    private void scheduleWeb(long delay) {
        handler.removeCallbacks(webRunnable);
        if (webView != null && canRun() && isWebAutomationPhase(store.phase()))
            handler.postDelayed(webRunnable, delay);
    }

    private static boolean isWebAutomationPhase(String phase) {
        return SelfRunStore.PHASE_BOOTSTRAP.equals(phase)
                || SelfRunStore.PHASE_BOOTSTRAP_MODEL.equals(phase)
                || SelfRunStore.PHASE_BOOTSTRAP_REASONING.equals(phase)
                || SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)
                || SelfRunStore.PHASE_APPLY_PREFS.equals(phase)
                || SelfRunStore.PHASE_APPLY_REASONING.equals(phase)
                || SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);
    }

    private void handleDriveFailure(Throwable error) {
        if (!canApplyDriveResult()) return;
        if (error instanceof DriveApiClient.OutcomeUnknownException) {
            pauseError("DRIVE_DOCUMENT_CREATE_RESULT_UNKNOWN",
                    "네이티브 Google Docs 생성 결과가 불명확해 자동 재시도를 차단했습니다.");
            return;
        }
        if ((error instanceof DriveApiClient.ApiException api && api.retryable())
                || retryableNetworkError(error)) {
            int index = Math.min(retryAttempt++, BACKOFF.length - 1);
            long base = BACKOFF[index], jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 4L));
            String kind = error instanceof DriveApiClient.ApiException api ? "http_" + api.status : "network";
            runLog.record(store, "DRIVE_BACKOFF", "kind=" + kind + ";attempt=" + retryAttempt);
            handler.removeCallbacks(driveRetryRunnable);
            handler.postDelayed(driveRetryRunnable, base + jitter); return;
        }
        if (error instanceof DriveApiClient.ApiException api && api.status == 401) {
            pauseError("DRIVE_ACCOUNT_REAUTHORIZE_REQUIRED", "Drive 계정 승인이 만료되었습니다.");
        } else if (error instanceof DriveApiClient.ApiException api && (api.status == 403 || api.status == 404)) {
            pauseError("DRIVE_BASE_FOLDER_REBIND_REQUIRED", "Drive 항목 접근 권한 또는 바인딩을 확인하세요.");
        } else if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            pauseError("DRIVE_VALIDATION_FAILED", "Drive 응답 또는 영속 상태 검증에 실패했습니다.");
        } else {
            pauseError("DRIVE_OPERATION_FAILED", "Drive 요청에 실패했습니다. 네트워크 상태를 확인하세요.");
        }
    }

    private void retryDrive() {
        if (canRun() && drivePhase(store.phase())) authorizeAndRunDrive();
    }

    private static boolean retryableNetworkError(Throwable error) {
        return error instanceof IOException
                && !(error instanceof SSLException)
                && !(error instanceof ProtocolException);
    }

    private static void verifyMetadata(DriveApiClient.Metadata m, String job, String mime, String parent, String kind) {
        if (m.trashed || m.shared || !job.equals(m.name) || !mime.equals(m.mimeType) || !parent.equals(m.parentId)
                || !m.isAppAuthorized
                || (DriveApiClient.MIME_FOLDER.equals(mime) && !m.canAddChildren)
                || !job.equals(m.appProperties.optString("job_id"))
                || !kind.equals(m.appProperties.optString("selfrun_kind"))
                || !"1".equals(m.appProperties.optString("protocol_version"))
                || !"selfrun_drive_android".equals(m.appProperties.optString("client_id"))
                || !"selfrun_drive_android".equals(m.appProperties.optString("created_by")))
            throw new IllegalStateException("Drive metadata readback mismatch: " + kind);
    }

    private static String documentUrl(DriveApiClient.Metadata metadata) {
        return new Uri.Builder().scheme("https").authority("docs.google.com")
                .appendPath("document").appendPath("d").appendPath(metadata.id).appendPath("edit")
                .build().toString();
    }

    private void transition(String next, String status, String reason) {
        String prior = store.phase(); store.setPhase(next); store.setStatus(status);
        runLog.record(store, "STATE_TRANSITION", "from=" + prior + ";to=" + next + ";reason=" + reason);
        startForegroundCompat();
    }

    private void pauseError(String code, String message) {
        int epoch = automationEpoch;
        pauseError(code, message, epoch);
    }

    private void pauseError(String code, String message, int expectedEpoch) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> pauseError(code, message, expectedEpoch));
            return;
        }
        if (expectedEpoch != automationEpoch || !canRun()) return;
        store.setLastError(code, message); enterPreservedPause(code, code + " · " + message, false);
        NotificationHelper.notifyUser(this, "확인 필요", store.status());
    }

    private void pauseFromUi() {
        if (canRun()) enterPreservedPause("UI_PAUSE", "사용자 일시정지", false);
    }

    private void resumeFromUi() {
        if (!store.paused() || store.userStopped() || store.runId().isEmpty()) return;
        String next = store.pausedFromPhase();
        if (store.resumeNeedsContinuation()) {
            store.resumeTerminalWithContinuation();
        } else {
            if (next.isEmpty() || SelfRunStore.PHASE_PAUSED.equals(next)) next = SelfRunStore.PHASE_WAIT_DRIVE_COMMIT;
            store.leavePause(next);
        }
        store.clearLastError(); resumeWebView(); startForegroundCompat(); resumeStateMachine();
    }

    private void enterPreservedPause(String cause, String status, boolean needsContinuation) {
        String prior = store.phase(); store.enterPause(prior, needsContinuation); store.setStatus(status);
        stopAutomationCallbacks(); releaseWakeLock(); pauseWebView();
        runLog.record(store, "PAUSED", cause + ";webview=preserved;drive_ids=preserved"); startForegroundCompat();
    }

    private void stopAutomationCallbacks() {
        handler.removeCallbacks(driveRunnable); handler.removeCallbacks(webRunnable);
        handler.removeCallbacks(guardRunnable); handler.removeCallbacks(driveRetryRunnable);
        automationEpoch++; generation++; authorizationInFlight = false; domInFlight = false;
    }

    private void pauseWebView() { if (webView != null) try { webView.onPause(); } catch (Throwable ignored) {} }
    private void resumeWebView() { if (webView != null) try { webView.onResume(); } catch (Throwable ignored) {} }

    private void restoreCanonical() { if (canRun() && webView != null) webView.loadUrl(canonicalUrl()); }
    private String canonicalUrl() { return store.conversationUrl().isEmpty() ? store.projectUrl() : store.conversationUrl(); }
    private boolean routeAcceptable(String actual) { return store.conversationUrl().isEmpty() ? sameProject(store.projectUrl(), actual) : sameConversation(store.conversationUrl(), actual); }
    private static boolean sameProject(String a, String b) { return !SelfRunScript.projectId(a).isEmpty() && SelfRunScript.projectId(a).equals(SelfRunScript.projectId(b)); }
    private static boolean sameConversation(String a, String b) { return !SelfRunScript.conversationId(a).isEmpty() && SelfRunScript.conversationId(a).equals(SelfRunScript.conversationId(b)); }

    private JSONObject parse(String raw) {
        try { Object outer = new JSONTokener(raw == null ? "" : raw).nextValue(); return new JSONObject(outer instanceof String ? (String) outer : String.valueOf(outer)); }
        catch (Throwable error) { return new JSONObject(); }
    }

    private void acquireWakeLock() { if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(2 * 60_000L); }
    private void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }

    private void cleanupWebView() {
        handler.removeCallbacks(webRunnable); generation++; domInFlight = false;
        if (host != null) { host.destroy(); host = null; } webView = null;
    }

    private void stopRuntime() {
        stopAutomationCallbacks(); cleanupWebView(); releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopAutomationCallbacks(); cleanupWebView(); releaseWakeLock(); io.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private boolean sessionBindTimedOut() {
        return !store.sessionBound() && store.bootstrapSubmittedAt() > 0
                && System.currentTimeMillis() - store.bootstrapSubmittedAt() >= SESSION_BIND_TIMEOUT_MS;
    }
}
