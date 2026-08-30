package com.shaterguy.chatgptselfrun;

import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/** Drive V1 runtime. STOP/SEND mutations detect completion; Drive synchronizes the completed turn payload. */
public final class SelfRunService extends Service {
    static final String ACTION_RUN = BuildConfig.APPLICATION_ID + ".RUN";
    static final String ACTION_PAUSE = BuildConfig.APPLICATION_ID + ".PAUSE";
    static final String ACTION_RESUME = BuildConfig.APPLICATION_ID + ".RESUME";
    private static final int NOTIFICATION_ID = 17021;
    static final long TURN_COMPLETION_STABILITY_MS = 5_000L;
    /** MutationObserver is immediate; this low-frequency pass only repairs a detached DOM binding. */
    static final long TURN_OBSERVER_HEALTHCHECK_MS = 15_000L;
    static final long POST_DOM_DRIVE_RETRY_MS = 5_000L;
    static final long POST_DOM_DRIVE_MAX_WAIT_MS = 3 * 60_000L;
    private static final long WEB_RECOVERY_DELAY_MS = 5 * 60_000L;
    static final long CONTINUATION_VERIFY_INTERVAL_MS = 250L;
    static final long CONTINUATION_CALLBACK_TIMEOUT_MS = 5_000L;
    static final long BOOTSTRAP_SEND_MAX_WAIT_MS = 60_000L;
    static final long BOOTSTRAP_SEND_POLL_MS = 1_000L;
    static final int BOOTSTRAP_SEND_MAX_CALLBACK_RECOVERIES = 3;
    static final int TURN_COMPLETION_CALLBACK_TIMEOUT_RESYNC_THRESHOLD = 3;
    static final int MAX_TURN_COMPLETION_RESYNCS = 2;
    static final String CHAT_BOOTSTRAP_SUBMISSION_TIMEOUT = "CHAT_BOOTSTRAP_SUBMISSION_TIMEOUT";
    private static final long[] BACKOFF = {15_000L, 30_000L, 60_000L, 120_000L, 240_000L};
    private static final int ATTACHMENT_COUNT_BUFFER = 64 * 1024;

    private static final class AttachmentLimitException extends IOException {
        AttachmentLimitException(String message) { super(message); }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable driveRunnable = this::pollDrive;
    private final Runnable driveRetryRunnable = this::retryDrive;
    private final Runnable webRunnable = this::runWebStep;
    private SelfRunStore store;
    private SelfRunRolloverCoordinator rollover;
    private SelfRunNetworkState networkState;
    private SelfRunRunLog runLog;
    private DriveApiClient drive;
    private HeadlessWebViewHost host;
    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean driveInFlight;
    private volatile boolean authorizationInFlight;
    private boolean domInFlight;
    private boolean webViewPaused;
    private int generation;
    private int webEvaluationId;
    private volatile int automationEpoch;
    private volatile int driveOperationEpoch;
    private volatile String driveOperationRunId = "";
    private volatile String driveOperationAccountId = "";
    private volatile String driveOperationBaseFolderId = "";
    private volatile int retryAttempt;
    private volatile String accessToken = "";
    private volatile String verifiedDriveAccountId = "";
    private volatile String runtimeRunId = "";
    private String continuationAttemptPrompt = "";
    private String continuationAttemptMarkerId = "";
    private long postDispatchStartedElapsed;
    private String postDispatchRunId = "";
    private boolean postDispatchTransientSeen;
    private String postDispatchTransientKind = "";
    private boolean turnObserverNeedsIdleBaseline = false;
    private String loggedTurnObserverToken = "";
    private int bootstrapSendCallbackRecoveries;
    private int turnCompletionCallbackTimeouts;
    private int turnCompletionResyncAttempts;
    private volatile boolean destroyed;
    /** Serializes pause/stop epoch changes with application of Drive results to durable state. */
    private final Object automationStateLock = new Object();

    @Override public void onCreate() {
        super.onCreate();
        store = new SelfRunStore(this);
        rollover = new SelfRunRolloverCoordinator(this);
        networkState = new SelfRunNetworkState(this);
        networkState.start();
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
        if (ACTION_PAUSE.equals(action)) {
            pauseFromUi();
            return store.active() ? START_STICKY : START_NOT_STICKY;
        }
        boolean resumePendingRollover = ACTION_RESUME.equals(action) && rollover.hasPendingClaim();
        if (resumePendingRollover && store.paused()) {
            stopAutomationCallbacks();
            store.beginManualResumeOverride();
            store.clearLastError();
        }
        if (rollover.hasPendingClaim()) {
            if (store.paused()) { stopAutomationCallbacks(); releaseWakeLock(); return START_STICKY; }
            startForegroundCompat();
            stopAutomationCallbacks();
            cleanupWebView();
            SelfRunRolloverCoordinator.Result resumed = rollover.resumePending(store);
            if (resumed.started()) adoptSuccessorRuntime();
            else if (rollover.hasPendingClaim()) {
                handler.postDelayed(this::resumePendingRollover, 5_000L);
                return START_STICKY;
            }
        }
        String currentRunId = store.runId();
        if (!currentRunId.equals(runtimeRunId)) {
            stopAutomationCallbacks();
            runtimeRunId = currentRunId;
            verifiedDriveAccountId = "";
            accessToken = "";
            bootstrapSendCallbackRecoveries = 0;
            turnCompletionCallbackTimeouts = 0;
            turnCompletionResyncAttempts = 0;
        }
        if (ACTION_RESUME.equals(action)) {
            if (resumePendingRollover) { if (canRun()) handler.post(this::resumeStateMachine); return store.active() ? START_STICKY : START_NOT_STICKY; }
            resumeFromUi(); return store.active() ? START_STICKY : START_NOT_STICKY;
        }
        if (store.terminalSideEffectPending()) {
            replayTerminalSideEffect();
            return store.active() ? START_STICKY : START_NOT_STICKY;
        }
        if (intent != null && !ACTION_RUN.equals(action)) { stopSelf(startId); return START_NOT_STICKY; }
        if (!store.active()) { stopRuntime(); return START_NOT_STICKY; }
        startForegroundCompat();
        if (store.paused()) { stopAutomationCallbacks(); releaseWakeLock(); return START_STICKY; }
        runLog.record(store, "SERVICE_START", intent == null ? "sticky_recreate" : "explicit_start");
        handler.post(this::resumeStateMachine);
        return START_STICKY;
    }

    private void adoptSuccessorRuntime() {
        runtimeRunId = store.runId();
        verifiedDriveAccountId = "";
        accessToken = "";
        retryAttempt = 0;
        bootstrapSendCallbackRecoveries = 0;
        turnCompletionCallbackTimeouts = 0;
        turnCompletionResyncAttempts = 0;
        clearContinuationAttempt();
        resetPostDispatchNoStartState();
    }

    private void resumePendingRollover() {
        if (!rollover.hasPendingClaim()) { if (canRun()) resumeStateMachine(); return; }
        SelfRunRolloverCoordinator.Result resumed = rollover.resumePending(store);
        if (resumed.started()) { adoptSuccessorRuntime(); resumeStateMachine(); }
        else if (rollover.hasPendingClaim()) handler.postDelayed(this::resumePendingRollover, 5_000L);
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, NotificationHelper.active(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else startForeground(NOTIFICATION_ID, NotificationHelper.active(this));
    }

    private boolean canRun() {
        return store.active() && !store.paused() && !store.userStopped()
                && !SelfRunStore.PHASE_DONE.equals(store.phase())
                && !SelfRunStore.PHASE_IDLE.equals(store.phase());
    }

static boolean ownsRendererCallback(Object callbackView, Object currentView,
                                     String launchedRunId, String currentRunId) {
    return callbackView != null && callbackView == currentView
            && launchedRunId != null && launchedRunId.equals(currentRunId);
}

static boolean shouldCleanupCompletedRun(String ownerRunId, String currentRunId, String phase,
                                         boolean terminalSideEffectPending, boolean ownsActiveWebView) {
    return ownerRunId != null && !ownerRunId.isEmpty() && ownerRunId.equals(currentRunId)
            && SelfRunStore.PHASE_DONE.equals(phase) && !terminalSideEffectPending && ownsActiveWebView;
}

private void resumeStateMachine(){if(!canRun())return;String phase=store.phase();if(drivePhase(phase))authorizeAndRunDrive();else ensureWebView();}

private static boolean drivePhase(String phase){return SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK.equals(phase)||SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK.equals(phase)||SelfRunStore.PHASE_JOB_ID_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD.equals(phase)||SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT.equals(phase)||SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK.equals(phase)||SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC.equals(phase)||SelfRunStore.PHASE_RESUME_BASELINE.equals(phase);}

static boolean shouldContinueSamePhaseDriveStep(String phase, boolean hasUncommittedAttachment) {
    return SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD.equals(phase) && hasUncommittedAttachment;
}

static boolean shouldGuardContinuationCallback(String phase){return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)||SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}
static boolean bootstrapSendTimedOut(long startedAt,long now){return startedAt<=0L||now<=0L||now<startedAt||now-startedAt>=BOOTSTRAP_SEND_MAX_WAIT_MS;}
static boolean bootstrapSendCallbackRecoveryExhausted(int recoveries){return recoveries>=BOOTSTRAP_SEND_MAX_CALLBACK_RECOVERIES;}
static boolean postDomDriveSyncTimedOut(long startedAt,long now){return startedAt>0L&&now>=startedAt&&now-startedAt>=POST_DOM_DRIVE_MAX_WAIT_MS;}
static boolean isChatReasoningFailureStatus(String status){return switch(status){case "CHAT_REASONING_TRIGGER_NOT_FOUND","CHAT_REASONING_SLIDER_NOT_FOUND","CHAT_REASONING_ADVANCED_CONTROL_NOT_FOUND","CHAT_REASONING_OPTION_UNAVAILABLE","CHAT_REASONING_READBACK_MISMATCH","CHAT_REASONING_MENU_CLOSE_FAILED"->true;default->false;};}
static String chatReasoningFailureMessage(String status){return switch(status){case "CHAT_REASONING_TRIGGER_NOT_FOUND"->"Chat 추론 선택기를 찾지 못했습니다. ChatGPT 화면 구조를 확인하세요.";case "CHAT_REASONING_SLIDER_NOT_FOUND"->"구버전 Chat 추론 슬라이더를 찾지 못했습니다.";case "CHAT_REASONING_ADVANCED_CONTROL_NOT_FOUND"->"Chat 추론 슬라이드에서 고급 버튼을 찾지 못했습니다.";case "CHAT_REASONING_OPTION_UNAVAILABLE"->"선택한 Chat 추론 단계가 현재 계정 또는 워크스페이스에서 제공되지 않습니다.";case "CHAT_REASONING_READBACK_MISMATCH"->"Chat 추론 적용 결과가 요청한 의미값과 일치하지 않습니다.";case "CHAT_REASONING_MENU_CLOSE_FAILED"->"Chat 추론 적용 후 메뉴를 닫지 못했습니다.";default->"Chat 추론 설정을 적용하지 못했습니다.";};}
static boolean isWorkPreferenceFailureStatus(String status){return switch(status){case "WORK_MODEL_SELECTION_TIMEOUT","WORK_MODEL_READBACK_MISMATCH","WORK_REASONING_SELECTION_TIMEOUT","WORK_REASONING_READBACK_MISMATCH"->true;default->false;};}
static String workPreferenceFailureMessage(String status){return switch(status){case "WORK_MODEL_SELECTION_TIMEOUT"->"다음 턴 WORK 모델 선택 요소를 제한시간 안에 찾지 못했습니다.";case "WORK_MODEL_READBACK_MISMATCH"->"다음 턴 WORK 모델 적용 결과를 확인하지 못했습니다.";case "WORK_REASONING_SELECTION_TIMEOUT"->"다음 턴 WORK 추론 선택 요소를 제한시간 안에 찾지 못했습니다.";case "WORK_REASONING_READBACK_MISMATCH"->"다음 턴 WORK 추론 적용 결과를 확인하지 못했습니다.";default->"다음 턴 WORK 모델·추론 설정을 적용하지 못했습니다.";};}
static boolean isBootstrapFailureStatus(String status){return BootstrapResultPolicy.isExplicitFailure(status);}
static String bootstrapFailureMessage(String status){if(isChatReasoningFailureStatus(status))return chatReasoningFailureMessage(status);return switch(status){case BootstrapResultPolicy.CALLBACK_INVALID->"ChatGPT bootstrap 콜백을 해석하지 못했습니다.";case BootstrapResultPolicy.SCRIPT_ERROR->"ChatGPT bootstrap 스크립트 실행에 실패했습니다.";case BootstrapResultPolicy.UNKNOWN_STATUS->"ChatGPT bootstrap이 알 수 없는 상태를 반환했습니다.";case BootstrapResultPolicy.TIMEOUT->"ChatGPT bootstrap 전체 제한시간을 초과했습니다.";case BootstrapResultPolicy.STATE_PERSIST_FAILED->"ChatGPT bootstrap 상태를 기기에 저장하지 못했습니다.";case BootstrapResultPolicy.READBACK_MISSING->"Chat 추론 적용 확인값을 얻지 못했습니다.";case "CHAT_BOOTSTRAP_NEW_CHAT_FAILED"->"ChatGPT 새 대화 화면으로 전환하지 못했습니다.";case "CHAT_BOOTSTRAP_MODE_CONTROL_NOT_FOUND"->"Chat·Work 모드 선택기를 찾지 못했습니다.";case "CHAT_BOOTSTRAP_MODE_READBACK_FAILED"->"Chat·Work 모드 전환 결과를 확인하지 못했습니다.";case "CHAT_BOOTSTRAP_COMPOSER_NOT_FOUND"->"ChatGPT 새 대화 입력창을 찾지 못했습니다.";default->"ChatGPT bootstrap을 완료하지 못했습니다.";};}

    private void authorizeAndRunDrive() {
        if (!canRun() || !drivePhase(store.phase()) || driveInFlight || authorizationInFlight) return;
        final int epoch = automationEpoch;
        final String requestedRunId = store.runId();
        final String requestedPhase = store.phase();
        authorizationInFlight = true;
        DriveAuthorization.requestSilently(this, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                authorizationInFlight = false;
                if (!canRun() || epoch != automationEpoch || !requestedRunId.equals(store.runId())
                        || !requestedPhase.equals(store.phase()) || !drivePhase(store.phase())) return;
                accessToken = DriveAuthorization.accessToken(result);
                if (accessToken.isEmpty()) {
                    scheduleAuthorizationRetry("DRIVE_ACCESS_TOKEN_EMPTY",
                            "Drive 액세스 토큰을 얻지 못했습니다.", epoch, requestedRunId, requestedPhase);
                    return;
                }
                executeDriveStep(epoch);
            }
            @Override public void onResolutionRequired(PendingIntent ignored) {
                authorizationInFlight = false;
                if (!canRun() || epoch != automationEpoch || !requestedRunId.equals(store.runId())) return;
                if (SelfRunStore.PHASE_RESUME_BASELINE.equals(requestedPhase)) scheduleAuthorizationRetry("DRIVE_RESUME_BASELINE_AUTH_RETRY", "사용자 재개 baseline의 Drive 승인을 자동 재시도합니다.", epoch, requestedRunId, requestedPhase);
                else pauseError("DRIVE_ACCOUNT_REAUTHORIZE_REQUIRED", "앱 설정에서 Drive 저장 위치를 다시 연결하세요.", epoch, requestedRunId, requestedPhase);
            }
            @Override public void onFailure(Throwable error) {
                authorizationInFlight = false;
                if (!canRun() || epoch != automationEpoch || !requestedRunId.equals(store.runId())) return;
                scheduleAuthorizationRetry("DRIVE_ACCOUNT_CHECK_RETRY",
                        "Drive 계정 확인에 실패했습니다.", epoch, requestedRunId, requestedPhase);
            }
        });
    }

    private void executeDriveStep(int epoch) {
        if (!canRun() || epoch != automationEpoch || !drivePhase(store.phase()) || driveInFlight) return;
        synchronized (automationStateLock) {
            synchronized (SelfRunStore.RUN_STATE_LOCK) {
                if (!canRun() || epoch != automationEpoch || !drivePhase(store.phase()) || driveInFlight) return;
                driveInFlight = true;
                driveOperationEpoch = epoch;
                driveOperationRunId = store.runId();
                driveOperationAccountId = store.runDriveAccountId();
                driveOperationBaseFolderId = store.runBaseFolderId();
            }
        }
        acquireWakeLock();
        io.execute(() -> {
            try {
                if (!driveOperationAccountId.equals(verifiedDriveAccountId)) {
                    String accountId = drive.getAccountPermissionId(accessToken);
                    if (!canApplyDriveResult(epoch)) return;
                    if (!accountId.equals(driveOperationAccountId)) {
                        if (SelfRunStore.PHASE_RESUME_BASELINE.equals(store.phase())) scheduleDriveRecovery("DRIVE_RESUME_ACCOUNT_RECHECK", "사용자 재개 baseline의 Drive 계정을 자동 재확인합니다.", epoch);
                        else pauseError("DRIVE_ACCOUNT_MISMATCH", "바인딩한 Drive 계정과 현재 승인 계정이 다릅니다.", epoch);
                        return;
                    }
                    if (!applyDriveResult(epoch, () -> verifiedDriveAccountId = accountId)) return;
                }
                if (SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC.equals(store.phase()) || SelfRunStore.PHASE_RESUME_BASELINE.equals(store.phase())) {
                    pollDriveNow(epoch);
                    retryAttempt = 0;
                    return;
                }
                do {
                    if (!canApplyDriveResult(epoch)) return;
                    String prior = store.phase();
                    runDriveStep(epoch);
                    if (prior.equals(store.phase())) {
                        if (shouldContinueSamePhaseDriveStep(prior, store.nextUncommittedAttachment() != null)) continue;
                        break;
                    }
                    if (SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC.equals(store.phase())
                            || SelfRunStore.PHASE_RESUME_BASELINE.equals(store.phase())) break;
                } while (canApplyDriveResult(epoch) && drivePhase(store.phase()));
                retryAttempt = 0;
            } catch (Throwable error) {
                handleDriveFailure(error, epoch);
            } finally {
                driveInFlight = false;
                accessToken = "";
                releaseWakeLock();
                handler.post(() -> {
                    if (!destroyed && epoch != automationEpoch && canRun() && !driveInFlight) {
                        resumeStateMachine();
                    }
                });
            }
        });
    }

    private boolean canApplyDriveResult(int expectedEpoch) {
        synchronized (automationStateLock) {
            synchronized (SelfRunStore.RUN_STATE_LOCK) {
                return !destroyed && canRun() && expectedEpoch == automationEpoch
                        && driveOperationEpoch == expectedEpoch
                        && driveOperationRunId.equals(store.runId()) && drivePhase(store.phase());
            }
        }
    }

    /** Rechecks the captured automation epoch while holding the same lock used by pause/stop. */
    private boolean applyDriveResult(int expectedEpoch, Runnable mutation) {
        synchronized (automationStateLock) {
            synchronized (SelfRunStore.RUN_STATE_LOCK) {
                if (destroyed || expectedEpoch != automationEpoch || !canRun()
                        || !driveOperationRunId.equals(store.runId())
                        || !drivePhase(store.phase())) return false;
                mutation.run();
                return true;
            }
        }
    }

private DriveStateSnapshot driveState(int expectedEpoch){synchronized(automationStateLock){synchronized(SelfRunStore.RUN_STATE_LOCK){if(destroyed||expectedEpoch!=automationEpoch||!canRun()||!driveOperationRunId.equals(store.runId())||!drivePhase(store.phase()))return null;return new DriveStateSnapshot(store.phase(),store.runId(),store.runBaseFolderId(),store.jobFolderId(),store.turnDocumentId(),store.creationStage(),store.driveSignalCursor(),store.driveSignalCursorSchemaVersion(),store.lastDriveSignalRaw(),store.lastDriveSignalTimestamp(),store.lastDriveSignalType(),store.mode(),store.lastSeenDriveVersion(),store.lastSeenModifiedTime());}}}

private void runDriveStep(int epoch)throws Exception{if(!canApplyDriveResult(epoch))return;switch(store.phase()){case SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK->applyDriveResult(epoch,()->transition(SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK,"Drive 기준 폴더 확인 중","account_authorized"));case SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK->checkBaseFolder(epoch);case SelfRunStore.PHASE_JOB_ID_CREATE->applyDriveResult(epoch,()->transition(SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE,"Drive Job 폴더 생성 중","job_id_persisted"));case SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE->createOrRecoverJobFolder(epoch);case SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD->uploadNextAttachment(epoch);case SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE->createOrRecoverDocument(epoch);case SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT->initializeDocument(epoch);case SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK->verifyInitialDocument(epoch);case SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC,SelfRunStore.PHASE_RESUME_BASELINE->pollDriveNow(epoch);default->scheduleDriveRecovery("DRIVE_STATE_RECHECK","알 수 없는 Drive 단계를 자동 재확인합니다: "+store.phase(),epoch);}}

    private void checkBaseFolder(int epoch) throws Exception {
        String base = driveOperationBaseFolderId;
        if (!DriveApiClient.validFileId(base)) { pauseError("DRIVE_BASE_FOLDER_REBIND_REQUIRED", "Runs 기준 폴더 ID가 없습니다.", epoch); return; }
        DriveApiClient.Metadata metadata = drive.getMetadata(accessToken, base);
        if (!canApplyDriveResult(epoch)) return;
        if (metadata.trashed || !DriveApiClient.MIME_FOLDER.equals(metadata.mimeType)
                || !metadata.isAppAuthorized || !metadata.canAddChildren || metadata.shared) {
            pauseError("DRIVE_BASE_FOLDER_REBIND_REQUIRED", "Runs 기준 폴더가 접근 불가능합니다.", epoch); return;
        }
        applyDriveResult(epoch, () -> transition(SelfRunStore.PHASE_JOB_ID_CREATE,
                "Job ID 확인 완료", "base_folder_verified"));
    }

    private void createOrRecoverJobFolder(int epoch) throws Exception {
        DriveStateSnapshot initial = driveState(epoch); if (initial == null) return;
        String base = driveOperationBaseFolderId; String folderId = initial.jobFolderId;
        if (folderId.isEmpty()) {
            if (!SelfRunStore.CREATION_NONE.equals(initial.creationStage)) throw new IllegalStateException("folder creation state has no reserved id");
            folderId = drive.generateFolderId(accessToken); String reservedId = folderId;
            if (!applyDriveResult(epoch, () -> store.reserveJobFolderId(reservedId))) return;
        }
        DriveApiClient.Metadata metadata = null;
        try { metadata = drive.getMetadata(accessToken, folderId); }
        catch (DriveApiClient.ApiException api) { if (api.status != 404) throw api; }
        if (metadata == null) {
            DriveStateSnapshot current = driveState(epoch); if (current == null) return; String stage = current.creationStage;
            if (!(SelfRunStore.CREATION_FOLDER_ID_RESERVED.equals(stage) || SelfRunStore.CREATION_FOLDER_CREATING.equals(stage))) throw new IllegalStateException("created job folder disappeared");
            if (SelfRunStore.CREATION_FOLDER_ID_RESERVED.equals(stage) && !applyDriveResult(epoch, store::markJobFolderCreating)) return;
            if (!canApplyDriveResult(epoch)) return;
            try { metadata = drive.createJobFolder(accessToken, folderId, driveOperationRunId, base); }
            catch (DriveApiClient.ApiException api) { if (api.status != 409) throw api; metadata = drive.getMetadata(accessToken, folderId); }
        }
        if (!folderId.equals(metadata.id)) throw new IllegalStateException("Drive returned a different reserved folder id");
        verifyMetadata(metadata, driveOperationRunId, DriveApiClient.MIME_FOLDER, base, "job_folder");
        String persistedFolderId = folderId;
        if (!applyDriveResult(epoch, () -> store.saveJobFolder(persistedFolderId))) return;
        DriveApiClient.Metadata readback = drive.getMetadata(accessToken, folderId);
        verifyMetadata(readback, driveOperationRunId, DriveApiClient.MIME_FOLDER, base, "job_folder");
        applyDriveResult(epoch, () -> {
            if (store.hasAttachments()) transition(SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD,
                    "첨부파일 Drive 업로드 준비", "job_folder_ready_with_attachments");
            else transition(SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE,
                    "실행턴 Google Docs 생성 중", "job_folder_ready");
        });
    }

    private void uploadNextAttachment(int epoch) throws Exception {
        store.releaseCommittedAttachmentPermissions();
        SelfRunStore.Attachment attachment = store.nextUncommittedAttachment();
        if (attachment == null) {
            applyDriveResult(epoch, () -> transition(SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE,
                    "첨부파일 업로드 완료 · 실행턴 Google Docs 생성 중", "attachments_committed"));
            return;
        }
        String fileId = attachment.driveFileId;
        if (fileId.isEmpty()) {
            fileId = drive.generateFileId(accessToken);
            String reserved = fileId;
            int reserveIndex = attachment.index;
            if (!applyDriveResult(epoch, () -> store.reserveAttachmentFileId(reserveIndex, reserved))) return;
            attachment = store.nextUncommittedAttachment();
            if (attachment == null || attachment.index < 0) throw new IllegalStateException("attachment state disappeared after id reservation");
        }

        DriveApiClient.Metadata existing = null;
        try { existing = drive.getMetadata(accessToken, fileId); }
        catch (DriveApiClient.ApiException api) { if (api.status != 404) throw api; }
        if (existing != null) {
            verifyAttachmentMetadata(existing, attachment, driveOperationRunId, store.jobFolderId());
            int committedIndex = attachment.index;
            if (!applyDriveResult(epoch, () -> store.markAttachmentCommitted(committedIndex))) return;
            if (store.allAttachmentsCommitted()) applyDriveResult(epoch, () -> transition(SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE,
                    "첨부파일 업로드 완료 · 실행턴 Google Docs 생성 중", "attachments_readback_complete"));
            return;
        }

        Uri uri = Uri.parse(attachment.uri);
        if (!"content".equals(uri.getScheme())) {
            pauseError("DRIVE_ATTACHMENT_RESELECT_REQUIRED", "첨부파일 접근 정보가 유효하지 않습니다. 작업을 중지한 뒤 새 작업에서 파일을 다시 선택하세요.", epoch);
            return;
        }
        try {
            long resolvedSize = resolveAttachmentSize(uri, attachment.size);
            if (resolvedSize != attachment.size) {
                int index = attachment.index;
                if (!applyDriveResult(epoch, () -> store.updateAttachmentSize(index, resolvedSize))) return;
                attachment = store.nextUncommittedAttachment();
                if (attachment == null) throw new IllegalStateException("attachment state disappeared after size update");
            }
            if (attachment.uploadAttempts >= SelfRunStore.MAX_ATTACHMENT_UPLOAD_ATTEMPTS) {
                pauseError("DRIVE_ATTACHMENT_RETRY_LIMIT", "첨부파일 업로드 재시도 한도에 도달했습니다. 작업을 중지한 뒤 새 작업에서 다시 시도하세요.", epoch);
                return;
            }
            int uploadingIndex = attachment.index;
            if (!applyDriveResult(epoch, () -> store.markAttachmentUploading(uploadingIndex))) return;
            try (InputStream input = openAttachmentInput(uri)) {
                DriveApiClient.Metadata uploaded = drive.uploadAttachmentResumable(accessToken, fileId,
                        driveOperationRunId, store.jobFolderId(), attachment.index, attachment.name,
                        attachment.mimeType, resolvedSize, input);
                verifyAttachmentMetadata(uploaded, attachment, driveOperationRunId, store.jobFolderId());
            }
            DriveApiClient.Metadata readback = drive.getMetadata(accessToken, fileId);
            verifyAttachmentMetadata(readback, attachment, driveOperationRunId, store.jobFolderId());
            int committedIndex = attachment.index;
            if (!applyDriveResult(epoch, () -> store.markAttachmentCommitted(committedIndex))) return;
            if (store.allAttachmentsCommitted()) applyDriveResult(epoch, () -> transition(SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE,
                    "첨부파일 업로드 완료 · 실행턴 Google Docs 생성 중", "attachments_readback_complete"));
        } catch (AttachmentLimitException limit) {
            pauseError("DRIVE_ATTACHMENT_LIMIT_EXCEEDED", "첨부파일은 최대 "+SelfRunStore.MAX_ATTACHMENTS_PER_RUN+"개, 파일당 최대 100 MB까지 업로드할 수 있습니다. 작업을 중지한 뒤 파일을 다시 선택하세요.", epoch);
        } catch (SecurityException | FileNotFoundException unavailable) {
            pauseError("DRIVE_ATTACHMENT_RESELECT_REQUIRED", "첨부파일을 더 이상 읽을 수 없습니다. 작업을 중지한 뒤 새 작업에서 파일을 다시 선택하세요.", epoch);
        }
    }

    private long resolveAttachmentSize(Uri uri, long reportedSize) throws IOException {
        if (reportedSize > SelfRunStore.MAX_ATTACHMENT_BYTES) throw new AttachmentLimitException("attachment too large");
        ContentResolver resolver = getContentResolver();
        try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r")) {
            if (descriptor != null && descriptor.getLength() >= 0) {
                long length = descriptor.getLength();
                if (length > SelfRunStore.MAX_ATTACHMENT_BYTES) throw new AttachmentLimitException("attachment too large");
                return length;
            }
        } catch (SecurityException | FileNotFoundException error) { throw error; }
        long total = 0L; byte[] buffer = new byte[ATTACHMENT_COUNT_BUFFER];
        try (InputStream input = openAttachmentInput(uri)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (Long.MAX_VALUE - total < read) throw new IOException("attachment length overflow");
                total += read;
                if (total > SelfRunStore.MAX_ATTACHMENT_BYTES) throw new AttachmentLimitException("attachment too large");
            }
        }
        return total;
    }

    private InputStream openAttachmentInput(Uri uri) throws FileNotFoundException {
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new FileNotFoundException("attachment stream unavailable");
        return input;
    }

    private static void verifyAttachmentMetadata(DriveApiClient.Metadata metadata, SelfRunStore.Attachment attachment,
                                                 String jobId, String parentId) {
        if (metadata.trashed || metadata.shared || !metadata.isAppAuthorized
                || !attachment.driveFileId.equals(metadata.id)
                || !attachment.name.equals(metadata.name)
                || !attachment.mimeType.equals(metadata.mimeType)
                || !parentId.equals(metadata.parentId)
                || !jobId.equals(metadata.appProperties.optString("job_id"))
                || !"attachment".equals(metadata.appProperties.optString("selfrun_kind"))
                || !String.valueOf(attachment.index).equals(metadata.appProperties.optString("attachment_index"))
                || (attachment.size >= 0 && metadata.size >= 0 && attachment.size != metadata.size)) {
            throw new IllegalStateException("Drive attachment metadata readback mismatch");
        }
    }

    private void createOrRecoverDocument(int epoch) throws Exception {
        DriveStateSnapshot initial = driveState(epoch); if (initial == null) return; String parent = initial.jobFolderId;
        if (!initial.turnDocumentId.isEmpty()) {
            verifyMetadata(drive.getMetadata(accessToken, initial.turnDocumentId), driveOperationRunId,
                    DriveApiClient.MIME_DOCUMENT, parent, "turn_document");
        } else if (SelfRunStore.CREATION_DOCUMENT_CREATING.equals(initial.creationStage)) {
            DriveApiClient.Metadata recovered = drive.findSingleTurnDocument(accessToken, driveOperationRunId, parent);
            if (!canApplyDriveResult(epoch)) return;
            if (recovered == null) { scheduleDriveRecovery("DRIVE_DOCUMENT_CREATE_RESULT_PENDING", "네이티브 Google Docs 생성 결과를 동일 Job 폴더에서 재확인 중입니다.", epoch); return; }
            verifyMetadata(recovered, driveOperationRunId, DriveApiClient.MIME_DOCUMENT, parent, "turn_document");
            if (!applyDriveResult(epoch, () -> store.saveTurnDocument(recovered.id, documentUrl(recovered)))) return;
            DriveApiClient.Metadata readback = drive.getMetadata(accessToken, recovered.id);
            verifyMetadata(readback, driveOperationRunId, DriveApiClient.MIME_DOCUMENT, parent, "turn_document");
        } else {
            if (!applyDriveResult(epoch, () -> store.setCreationStage(SelfRunStore.CREATION_DOCUMENT_CREATING))) return;
            DriveApiClient.Metadata created;
            try { if (!canApplyDriveResult(epoch)) return; created = drive.createTurnDocument(accessToken, driveOperationRunId, parent); }
            catch (DriveApiClient.OutcomeUnknownException unknown) { if (canApplyDriveResult(epoch)) scheduleDriveRecovery("DRIVE_DOCUMENT_CREATE_RESULT_PENDING", "네이티브 Google Docs 생성 응답이 유실되어 동일 Job 폴더를 재확인합니다.", epoch); return; }
            catch (DriveApiClient.ApiException definiteFailure) { applyDriveResult(epoch, store::resetDocumentCreateAfterDefiniteFailure); throw definiteFailure; }
            if (!DriveApiClient.validFileId(created.id)) throw new DriveApiClient.OutcomeUnknownException("native document response has no valid id", null);
            if (!applyDriveResult(epoch, () -> store.saveTurnDocument(created.id, documentUrl(created)))) return;
            verifyMetadata(created, driveOperationRunId, DriveApiClient.MIME_DOCUMENT, parent, "turn_document");
            DriveApiClient.Metadata readback = drive.getMetadata(accessToken, created.id);
            verifyMetadata(readback, driveOperationRunId, DriveApiClient.MIME_DOCUMENT, parent, "turn_document");
        }
        applyDriveResult(epoch, () -> transition(SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT,
                "실행턴 문서 초기화 중", "turn_document_ready"));
    }

private void initializeDocument(int epoch)throws Exception{
    DriveStateSnapshot snapshot=driveState(epoch);if(snapshot==null)return;
    String expected=store.requirement();
    if(SelfRunSignalTransport.isSignalDocumentRun(this,snapshot.runId)){
        String invalid=SelfRunOriginalRequirement.validationError(expected);
        if(!invalid.isEmpty()){pauseError("ORIGINAL_REQUIREMENT_INVALID",invalid,epoch,snapshot.runId,snapshot.phase);return;}
        DriveApiClient.DocumentSnapshot current=drive.readTurnDocumentSnapshot(accessToken,snapshot.turnDocumentId);
        if(!canApplyDriveResult(epoch))return;
        if(!SelfRunOriginalRequirement.exactDocumentMatch(current.text,expected)){
            if(!SelfRunOriginalRequirement.logicalDocumentText(current.text).isEmpty()){
                pauseError("ORIGINAL_REQUIREMENT_READBACK_MISMATCH","실행턴 문서에 기대하지 않은 기존 본문이 있어 원문을 덮어쓰지 않습니다.",epoch,snapshot.runId,snapshot.phase);return;
            }
            try{drive.initializeDocument(accessToken,snapshot.turnDocumentId,expected,current.revisionId);}
            catch(DriveApiClient.OutcomeUnknownException unknown){scheduleDriveRecovery("ORIGINAL_REQUIREMENT_WRITE_RECHECK","원문 요구사항 기록 결과를 동일 문서에서 재확인합니다.",epoch);return;}
            catch(DriveApiClient.ApiException api){if(api.status==400){scheduleDriveRecovery("ORIGINAL_REQUIREMENT_REVISION_RECHECK","원문 요구사항 기록 전 문서 revision을 다시 확인합니다.",epoch);return;}throw api;}
            DriveApiClient.DocumentSnapshot readback=drive.readTurnDocumentSnapshot(accessToken,snapshot.turnDocumentId);
            if(!canApplyDriveResult(epoch))return;
            if(!SelfRunOriginalRequirement.exactDocumentMatch(readback.text,expected)){
                pauseError("ORIGINAL_REQUIREMENT_READBACK_MISMATCH","원문 요구사항 Drive readback이 입력과 정확히 일치하지 않습니다.",epoch,snapshot.runId,snapshot.phase);return;
            }
        }
    }else drive.readDocumentText(accessToken,snapshot.turnDocumentId);
    if(!canApplyDriveResult(epoch))return;
    applyDriveResult(epoch,()->transition(SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK,"실행턴 문서 readback 검증 중","turn_document_initialized"));
}

private void verifyInitialDocument(int epoch)throws Exception{
    DriveStateSnapshot snapshot=driveState(epoch);if(snapshot==null)return;
    DriveApiClient.Metadata metadata=drive.getMetadata(accessToken,snapshot.turnDocumentId);
    verifyMetadata(metadata,snapshot.runId,DriveApiClient.MIME_DOCUMENT,snapshot.jobFolderId,"turn_document");
    if(SelfRunSignalTransport.isSignalDocumentRun(this,snapshot.runId)){
        DriveApiClient.DocumentSnapshot body=drive.readTurnDocumentSnapshot(accessToken,snapshot.turnDocumentId);
        if(!SelfRunOriginalRequirement.exactDocumentMatch(body.text,store.requirement())){
            pauseError("ORIGINAL_REQUIREMENT_READBACK_MISMATCH","원문 요구사항 Drive readback이 입력과 정확히 일치하지 않습니다.",epoch,snapshot.runId,snapshot.phase);return;
        }
        applyDriveResult(epoch,()->{store.baselineDriveSignals(0,null);store.updateDriveSeen(metadata.version,metadata.modifiedTime);transition(SelfRunStore.PHASE_BOOTSTRAP,"Drive 준비 완료 · ChatGPT 새 대화 준비","original_requirement_readback_verified");handler.post(()->{if(epoch==automationEpoch&&canRun())ensureWebView();});});
        return;
    }
    String body=drive.readDocumentText(accessToken,snapshot.turnDocumentId);
    DriveSignalParser.Scan scan=DriveSignalParser.scan(body,snapshot.runId,0,snapshot.mode);
    applyDriveResult(epoch,()->{store.baselineDriveSignals(scan.totalCount,scan.latest);store.updateDriveSeen(metadata.version,metadata.modifiedTime);transition(SelfRunStore.PHASE_BOOTSTRAP,"Drive 준비 완료 · ChatGPT 새 대화 준비","legacy_signal_log_readback_verified");handler.post(()->{if(epoch==automationEpoch&&canRun())ensureWebView();});});
}

private void pollDrive(){if(SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC.equals(store.phase())&&canRun())authorizeAndRunDrive();}

private void postDriveOutcome(){handler.post(()->{String phase=store.phase();if(SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase))ensureWebView();else if(SelfRunStore.PHASE_PAUSED.equals(phase)||SelfRunStore.PHASE_DONE.equals(phase)){if(store.terminalSideEffectPending())replayTerminalSideEffect();}});}

private static java.util.List<DriveSignalParser.Event> normalDriveEvents(java.util.List<DriveSignalParser.Event> events){java.util.ArrayList<DriveSignalParser.Event> result=new java.util.ArrayList<>();if(events!=null)for(DriveSignalParser.Event event:events){if(event.type==DriveSignalParser.Type.TURN_COMPLETED&&DriveSignalParser.hasRecoveryIdField(event.raw))continue;result.add(event);}return result;}

static boolean shouldReadDriveSnapshot(boolean postDom,boolean resume,String lastSeenVersion,String currentVersion){if(resume||!postDom)return true;return lastSeenVersion==null||lastSeenVersion.isEmpty()||currentVersion==null||currentVersion.isEmpty()||!currentVersion.equals(lastSeenVersion);}
static boolean shouldReadDriveSnapshot(boolean postDom,boolean resume,String lastSeenVersion,String currentVersion,boolean cursorMigrationRequired){return cursorMigrationRequired||shouldReadDriveSnapshot(postDom,resume,lastSeenVersion,currentVersion);}
static boolean isDominantCanonicalControl(DriveSignalParser.Event event){return event!=null&&(event.type==DriveSignalParser.Type.DONE||event.type==DriveSignalParser.Type.PAUSED||event.type==DriveSignalParser.Type.USER_ACTION_REQUIRED);}

private void pollDriveNow(int epoch)throws Exception{
    DriveStateSnapshot snapshot=driveState(epoch);if(snapshot==null)return;
    DriveApiClient.Metadata metadata=drive.getPollMetadata(accessToken,snapshot.turnDocumentId,SelfRunSignalTransport.isSignalDocumentRun(this,snapshot.runId));if(!canApplyDriveResult(epoch))return;
    if(metadata.trashed||metadata.shared||!DriveApiClient.MIME_DOCUMENT.equals(metadata.mimeType)||!snapshot.jobFolderId.equals(metadata.parentId)){scheduleDriveRecovery("DRIVE_DOCUMENT_RECHECK","실행턴 문서 상태가 기대값과 달라 동일 문서를 다시 확인합니다.",epoch);return;}
    boolean postDom=SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC.equals(snapshot.phase);
    boolean resume=SelfRunStore.PHASE_RESUME_BASELINE.equals(snapshot.phase);
    if(!postDom&&!resume)return;
    boolean migrationRequired=snapshot.driveSignalCursorSchemaVersion!=SelfRunStore.DRIVE_SIGNAL_CURSOR_SCHEMA_PHYSICAL;
    if(migrationRequired&&snapshot.driveSignalCursorSchemaVersion!=SelfRunStore.DRIVE_SIGNAL_CURSOR_SCHEMA_LEGACY){pauseError("DRIVE_SIGNAL_CURSOR_SCHEMA_UNSUPPORTED","저장된 Drive signal cursor schema를 안전하게 해석할 수 없습니다.",epoch,snapshot.runId,snapshot.phase);return;}
    if(!shouldReadDriveSnapshot(postDom,resume,snapshot.lastSeenVersion,metadata.version,migrationRequired)){
        if(!applyDriveResult(epoch,()->store.updateDriveSeen(metadata.version,metadata.modifiedTime)))return;
        long now=System.currentTimeMillis();
        if(postDomDriveSyncTimedOut(store.postDomDriveSyncStartedAt(),now)){
        handlePostDomDriveTimeout(epoch,snapshot);return;
        }
        applyDriveResult(epoch,()->store.setStatus("답변 완료 확인 · Drive TURN_COMPLETED 재확인 대기"));
        runLog.record(store,"POST_DOM_DRIVE_SYNC","result=version_unchanged;retryMs="+POST_DOM_DRIVE_RETRY_MS);
        schedulePostDomDriveSync(POST_DOM_DRIVE_RETRY_MS);return;
    }
    DriveApiClient.DocumentSnapshot document=drive.readDocumentSnapshot(accessToken,snapshot.turnDocumentId);if(!canApplyDriveResult(epoch))return;
    DriveSignalParser.Scan documentScan=DriveSignalParser.scan(document.text,snapshot.runId,0,snapshot.mode);
    DriveSignalParser.Event latestCanonical=documentScan.latestCanonical;
    if(isDominantCanonicalControl(latestCanonical)&&(postDom||latestCanonical.type==DriveSignalParser.Type.DONE)){
        DriveSignalParser.Event dominant=latestCanonical;
        if(!applyDriveResult(epoch,()->{store.applyDriveSignals(Collections.singletonList(dominant),System.currentTimeMillis());store.updateDriveSeen(metadata.version,metadata.modifiedTime);} ))return;
        runLog.record(store,"DRIVE_SIGNAL_DOMINANCE","type="+dominant.type.name()+";cursor="+dominant.cursor+";consumed="+snapshot.driveSignalCursor);
        postDriveOutcome();return;
    }
    int consumed=snapshot.driveSignalCursor;
    if(migrationRequired){
        DriveSignalParser.CursorMigration migration=DriveSignalParser.migrateCursor(document.text,snapshot.runId,snapshot.driveSignalCursor,snapshot.lastDriveSignalRaw,snapshot.lastDriveSignalTimestamp,snapshot.lastDriveSignalType);
        if(!migration.resolved){
            pauseError("DRIVE_SIGNAL_CURSOR_MIGRATION_UNRESOLVED","기존 Drive signal cursor를 현재 문서의 물리적 위치에 안전하게 연결할 수 없습니다.",epoch,snapshot.runId,snapshot.phase);return;
        }
        int migrated=migration.cursor;
        if(!applyDriveResult(epoch,()->store.migrateDriveSignalCursor(migrated)))return;
        consumed=migrated;
        runLog.record(store,"DRIVE_SIGNAL_CURSOR_MIGRATION","from="+snapshot.driveSignalCursor+";to="+migrated+";method="+migration.method);
    }
    DriveSignalParser.Scan scan=DriveSignalParser.scan(document.text,snapshot.runId,consumed,snapshot.mode);
    if(scan.cursorRebased){pauseError("DRIVE_SIGNAL_CURSOR_OUT_OF_RANGE","저장된 Drive signal cursor가 현재 실행턴 문서의 물리적 범위를 벗어났습니다.",epoch,snapshot.runId,snapshot.phase);return;}
    java.util.List<DriveSignalParser.Event> normalUnseen=normalDriveEvents(scan.unseen);
    DriveSignalParser.Event latestCompletion=DriveSignalParser.latestCompletion(normalUnseen),latestBlocking=DriveSignalParser.latestBlocking(scan.unseen);
    if(latestCompletion!=null&&!latestCompletion.protocolError.isEmpty()&&(latestBlocking==null||latestCompletion.cursor>latestBlocking.cursor)){pauseError("DRIVE_NEXT_INPUT_PROTOCOL_ERROR",latestCompletion.protocolError,epoch,snapshot.runId,snapshot.phase);return;}
    if(resume){if(applyDriveResult(epoch,()->{store.baselineManualResume(scan.totalCount,scan.latest,latestCompletion);store.updateDriveSeen(metadata.version,metadata.modifiedTime);}))handler.post(this::ensureWebView);return;}
    if(!applyDriveResult(epoch,()->{if(!normalUnseen.isEmpty())store.applyDriveSignals(normalUnseen,System.currentTimeMillis());if(normalUnseen.size()!=scan.unseen.size())store.baselineDriveSignals(scan.totalCount,scan.latest);store.updateDriveSeen(metadata.version,metadata.modifiedTime);} ))return;
    if(!SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC.equals(store.phase())){postDriveOutcome();return;}
    long now=System.currentTimeMillis();
    if(postDomDriveSyncTimedOut(store.postDomDriveSyncStartedAt(),now)){
        handlePostDomDriveTimeout(epoch,snapshot);return;
    }
    applyDriveResult(epoch,()->store.setStatus("답변 완료 확인 · Drive TURN_COMPLETED 재확인 대기"));
    runLog.record(store,"POST_DOM_DRIVE_SYNC","result=signal_missing;retryMs="+POST_DOM_DRIVE_RETRY_MS);
    schedulePostDomDriveSync(POST_DOM_DRIVE_RETRY_MS);
}

private void handlePostDomDriveTimeout(int epoch,DriveStateSnapshot snapshot){
    if(SelfRunSignalTransport.isSignalDocumentRun(this,snapshot.runId)&&SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){
        runLog.record(store,"POST_DOM_DRIVE_SYNC","result=timeout;maxWaitMs="+POST_DOM_DRIVE_MAX_WAIT_MS+";action=rollover");
        handler.post(()->rolloverConversation(SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT));
    }else{
        runLog.record(store,"POST_DOM_DRIVE_SYNC","result=timeout;maxWaitMs="+POST_DOM_DRIVE_MAX_WAIT_MS+";action=pause_fail_closed");
        pauseError("POST_DOM_DRIVE_SYNC_TIMEOUT","Drive 완료 신호를 제한시간 내 확정하지 못해 자동 CONTINUE를 차단했습니다.",epoch,snapshot.runId,snapshot.phase);
    }
}

private void replayTerminalSideEffect(){startForegroundCompat();String owner=store.terminalSideEffectRunId(),raw=store.terminalSideEffectCommitId(),type=store.terminalSideEffectType();handler.post(()->{synchronized(automationStateLock){synchronized(SelfRunStore.RUN_STATE_LOCK){if(!store.terminalSideEffectOwnedBy(owner,raw,type))return;switch(type){case "DONE"->finishDoneSideEffect(owner,raw,type);case "PAUSED"->finishPersistedTerminalPause("DRIVE_PAUSED","일시정지",owner,raw,type);case "USER_ACTION_REQUIRED"->finishPersistedTerminalPause("DRIVE_USER_ACTION_REQUIRED","확인 필요",owner,raw,type);default->{return;}}}}});}

    private void finishDoneSideEffect(String ownerRunId, String commitId, String type) {
        if (!store.terminalSideEffectOwnedBy(ownerRunId, commitId, type)) return;
        runLog.record(store, "TERMINAL", "done_signal"); NotificationHelper.notifyUser(this, "완료", ownerRunId);
        if (!store.acknowledgeTerminalSideEffect(ownerRunId, commitId, type)) return;
        cleanupAfterCompletedRun(ownerRunId);
        stopRuntime();
    }

private void cleanupAfterCompletedRun(String ownerRunId) {
    String detail = "";
    try {
        synchronized (automationStateLock) {
            synchronized (SelfRunStore.RUN_STATE_LOCK) {
                HeadlessWebViewHost completedHost = host;
                WebView completedWebView = webView;
                boolean ownsActiveWebView = completedHost != null && completedWebView != null
                        && completedHost.webView() == completedWebView
                        && HeadlessWebViewHost.activeWebView() == completedWebView;
                if (!shouldCleanupCompletedRun(ownerRunId, store.runId(), store.phase(),
                        store.terminalSideEffectPending(), ownsActiveWebView)) return;
                boolean cleared = completedHost.clearResourceCacheAfterCompletedRun();
                detail = "webview_resource_cache=" + (cleared ? "cleared" : "already_cleared")
                        + ";disposable_cache=none";
            }
        }
    } catch (Throwable error) {
        detail = "webview_resource_cache=failed;error=" + error.getClass().getSimpleName()
                + ";disposable_cache=none";
    }
    if (detail.isEmpty()) return;
    try { if (runLog != null) runLog.record(store, "COMPLETED_RUN_CACHE_CLEANUP", detail); }
    catch (Throwable ignored) {}
}

private void finishPersistedTerminalPause(String cause, String alertTitle, String ownerRunId,String commitId, String type) {if (!store.terminalSideEffectOwnedBy(ownerRunId, commitId, type)) return;stopAutomationCallbacks();releaseWakeLock();pauseWebView();runLog.record(store, "PAUSED", cause + ";webview=preserved;drive_ids=preserved");NotificationHelper.notifyUser(this, alertTitle, store.status());store.acknowledgeTerminalSideEffect(ownerRunId,commitId,type);}

private void ensureWebView(){if(!canRun()||!isWebAutomationPhase(store.phase()))return;String target=store.conversationUrl().isEmpty()?store.projectUrl():store.conversationUrl();if(target.isEmpty()||!validAutomationTarget(target)){store.setLastError("TARGET_MISSING_RETRY","ChatGPT 대상 URL을 안전하게 재확인합니다.");handler.postDelayed(this::ensureWebView,WEB_RECOVERY_DELAY_MS);return;}acquireWakeLock();if(webView!=null){if(!SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase()))resumeWebView();maybeCaptureConversationUrl(webView.getUrl());scheduleWeb(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())?0L:250L);return;}launchWebView(target);}

    private void launchWebView(String target) {
        cleanupWebView();
        try {
            String launchedRunId = store.runId();
            host = HeadlessWebViewHost.create(this); webView = host.webView(); WebViewConfig.applyAutomation(webView);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {if (!launchedRunId.equals(store.runId())) return;generation++;domInFlight=false;maybeCaptureConversationUrl(url);}
                @Override public void onPageFinished(WebView view, String url) {if (!launchedRunId.equals(store.runId())) return;maybeCaptureConversationUrl(url);if (isWebAutomationPhase(store.phase())) scheduleWeb(800L);}
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (!launchedRunId.equals(store.runId())) return true;
                    if (!request.isForMainFrame()) return false;
                    String requested=String.valueOf(request.getUrl());
                    if(isTurnCompletionCallback(requested,launchedRunId))return true;
                    boolean allowed=store.conversationUrl().isEmpty()?sameProject(store.projectUrl(),requested):sameConversation(store.conversationUrl(),requested);
                    if(!allowed){
                        recordContinuationRouteMismatch(requested);
                        if(SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){
                            if(networkState.isValidated()) rolloverConversation(SelfRunRolloverPolicy.ROUTE_MISMATCH); else scheduleWeb(5_000L);
                        }else postWebCallback(SelfRunService.this::restoreCanonical,800L);
                    }
                    return !allowed;
                }
                @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse response) {
                    if(!launchedRunId.equals(store.runId())||!canRun()||!isWebAutomationPhase(store.phase()))return;
                    int status=response.getStatusCode();
                    if(SelfRunRolloverPolicy.retryHttpStatus(status)&&trustedChatgptServiceResource(r)&&postDispatchWindowActive()) markPostDispatchTransient("HTTP_"+status);
                    if(!r.isForMainFrame())return;
                    if(SelfRunRolloverPolicy.rolloverHttpStatus(store.conversationUrl(),networkState.isValidated(),status)){
                        rolloverConversation(SelfRunRolloverPolicy.WEBVIEW_HTTP_GONE);return;
                    }
                    if(SelfRunRolloverPolicy.retryHttpStatus(status)) scheduleWeb(30_000L);
                }
                @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                    if(!launchedRunId.equals(store.runId())||!canRun()||!isWebAutomationPhase(store.phase()))return;
                    int code=e.getErrorCode();
                    if(SelfRunRolloverPolicy.transientWebError(code)&&trustedChatgptServiceResource(r)&&postDispatchWindowActive()) markPostDispatchTransient("WEB_"+code);
                    if(!r.isForMainFrame())return;
                    if(SelfRunRolloverPolicy.rolloverMainFrameError(store.conversationUrl(),networkState.isValidated(),code)){
                        rolloverConversation(SelfRunRolloverPolicy.WEBVIEW_MAIN_FRAME_LOCAL_ERROR);return;
                    }
                    postWebCallback(()->{
                        if(v!=webView)return;
                        if(!SelfRunRolloverPolicy.transientWebError(code)&&SelfRunRolloverPolicy.rolloverMainFrameError(store.conversationUrl(),networkState.isValidated(),code)) rolloverConversation(SelfRunRolloverPolicy.WEBVIEW_MAIN_FRAME_LOCAL_ERROR);
                        else v.loadUrl(canonicalUrl());
                    },3_000L);
                }
                @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {h.cancel();if (launchedRunId.equals(store.runId()) && canRun() && isWebAutomationPhase(store.phase())) {if(postDispatchWindowActive()&&trustedChatgptServiceUrl(e==null?"":e.getUrl()))markPostDispatchTransient("SSL");runLog.record(store, "WEBVIEW_SSL_RETRY", "cancelled;retry_in=300000");postWebCallback(SelfRunService.this::restoreCanonical, WEB_RECOVERY_DELAY_MS);}}
                @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                    if(!ownsRendererCallback(v,webView,launchedRunId,store.runId()))return true;
                    if(!detail.didCrash()&&postDispatchWindowActive())markPostDispatchTransient("RENDERER_KILLED");
                    cleanupWebView();
                    if(!store.paused()&&isWebAutomationPhase(store.phase())){
                        if(SelfRunRolloverPolicy.rolloverRenderer(store.conversationUrl(),detail.didCrash())) rolloverConversation(SelfRunRolloverPolicy.RENDERER_CRASH);
                        else postWebCallback(SelfRunService.this::ensureWebView,2_000L);
                    }
                    return true;
                }
            });
            webView.loadUrl(target);
        } catch (Throwable error) {
            cleanupWebView();
            int failures=rollover.incrementLocalFailure(store.runId());
            if(SelfRunRolloverPolicy.knownConversation(store.conversationUrl())&&networkState.isValidated()&&SelfRunRolloverPolicy.localFailureBudgetExhausted(failures)) rolloverConversation(SelfRunRolloverPolicy.WEBVIEW_CREATE_FAILURE);
            else postWebCallback(this::ensureWebView,2_500L);
        }
    }

private boolean isTurnCompletionCallback(String requested,String launchedRunId){
    Uri uri;try{uri=Uri.parse(requested);}catch(Throwable ignored){return false;}
    if(!SelfRunContinuationDom.TURN_COMPLETION_SCHEME.equals(uri.getScheme()))return false;
    String host=uri.getHost(),run=uri.getQueryParameter("run"),token=uri.getQueryParameter("token");
    if(SelfRunContinuationDom.TURN_STOP_SEEN_HOST.equals(host)){
        if(!launchedRunId.equals(run)||!launchedRunId.equals(store.runId())||!SelfRunStore.isActiveTurnObserverCallbackPhase(store.phase())||token==null||!token.equals(store.turnObserverToken())){
            runLog.record(store,"TURN_COMPLETION_OBSERVER","result=stop_callback_rejected");return true;
        }
        if(store.markTurnObserverStopSeen(token))runLog.record(store,"TURN_COMPLETION_OBSERVER","result=stop_seen");
        return true;
    }
    if(SelfRunContinuationDom.TURN_RESYNC_HOST.equals(host)){
        if(!launchedRunId.equals(run)||!launchedRunId.equals(store.runId())||!SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())||token==null||!token.equals(store.turnObserverToken())){
  runLog.record(store,"TURN_COMPLETION_RESYNC","result=callback_rejected");return true;
        }
        requestTurnCompletionResync(uri.getQueryParameter("reason"));
        return true;
    }
    if(!SelfRunContinuationDom.TURN_COMPLETION_HOST.equals(host))return false;
    if(!launchedRunId.equals(run)||!launchedRunId.equals(store.runId())||!SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())||token==null||!token.equals(store.turnObserverToken())){
        runLog.record(store,"TURN_COMPLETION_OBSERVER","result=callback_rejected");return true;
    }
    maybeCaptureConversationUrl(webView==null?"":webView.getUrl());
    if(!store.beginPostDomDriveSync(token))return true;
    turnCompletionCallbackTimeouts=0;turnCompletionResyncAttempts=0;
    resetPostDispatchNoStartState();
    turnObserverNeedsIdleBaseline=false;
    runLog.record(store,"TURN_COMPLETION_OBSERVER","result=stable_idle;stabilityMs="+TURN_COMPLETION_STABILITY_MS+";action=drive_immediate");
    releaseWakeLock();handler.post(this::authorizeAndRunDrive);return true;
}

private void requestTurnCompletionResync(String reason){
    if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(()->requestTurnCompletionResync(reason));return;}
    if(!canRun()||!SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase()))return;
    String safeReason=BootstrapResultPolicy.safe(reason==null?"unknown":reason,80);
    if(turnCompletionResyncAttempts>=MAX_TURN_COMPLETION_RESYNCS){
        runLog.record(store,"TURN_COMPLETION_RESYNC","result=exhausted;attempts="+turnCompletionResyncAttempts+";reason="+safeReason);
        enterPreservedPause("CHATGPT_TURN_RESYNC_EXHAUSTED","내부 WebView를 동일 대화와 재동기화했지만 응답 상태를 확정하지 못했습니다.",false);
        NotificationHelper.notifyUser(this,"확인 필요",store.status());return;
    }
    turnCompletionResyncAttempts++;
    turnCompletionCallbackTimeouts=0;
    runLog.record(store,"TURN_COMPLETION_RESYNC","result=rebuild_same_conversation;attempt="+turnCompletionResyncAttempts+";reason="+safeReason);
    store.setStatus("내부 WebView 상태를 동일 대화에서 재동기화 중");
    handler.removeCallbacks(webRunnable);
    cleanupWebView();
    handler.postDelayed(this::ensureWebView,800L);
}

private void maybeCaptureConversationUrl(String url){if(store.conversationUrl().isEmpty()&&sameProject(store.projectUrl(),url)&&!SelfRunScript.conversationId(url).isEmpty()){store.captureConversationUrl(url);if(sameConversation(store.conversationUrl(),url))runLog.record(store,"CONVERSATION_CAPTURED",SelfRunScript.isGeneralChatUrl(url)?"trusted_general_route":"trusted_project_route");}}

private void beginPostDispatchNoStartWindow(){postDispatchRunId=store.runId();postDispatchStartedElapsed=SystemClock.elapsedRealtime();postDispatchTransientSeen=false;postDispatchTransientKind="";}
private boolean postDispatchWindowActive(){return postDispatchStartedElapsed>0L&&store.runId().equals(postDispatchRunId);}
private long ensurePostDispatchNoStartWindow(){if(!postDispatchWindowActive())beginPostDispatchNoStartWindow();return postDispatchStartedElapsed;}
private void resetPostDispatchNoStartState(){postDispatchStartedElapsed=0L;postDispatchRunId="";postDispatchTransientSeen=false;postDispatchTransientKind="";}
private boolean trustedChatgptServiceResource(WebResourceRequest request){if(request==null||request.getUrl()==null)return false;return "https".equalsIgnoreCase(request.getUrl().getScheme())&&SelfRunRolloverPolicy.trustedChatgptServiceHost(request.getUrl().getHost());}
private boolean trustedChatgptServiceUrl(String raw){try{Uri uri=Uri.parse(raw);return "https".equalsIgnoreCase(uri.getScheme())&&SelfRunRolloverPolicy.trustedChatgptServiceHost(uri.getHost());}catch(Throwable ignored){return false;}}
private void markPostDispatchTransient(String kind){if(!postDispatchWindowActive())return;postDispatchTransientSeen=true;postDispatchTransientKind=BootstrapResultPolicy.safe(kind,48);runLog.record(store,"POST_DISPATCH_TRANSIENT","kind="+postDispatchTransientKind);}

    private void postWebCallback(Runnable callback, long delay) {int epoch = automationEpoch;String runId=store.runId();handler.postDelayed(() -> {if (epoch == automationEpoch && runId.equals(store.runId()) && canRun() && isWebAutomationPhase(store.phase())) callback.run();}, delay);}

private void runWebStep(){
    if(!canRun()||!isWebAutomationPhase(store.phase())||webView==null||domInFlight)return;
    String phase=store.phase();
    if(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)
            && SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){
        long nowElapsed=SystemClock.elapsedRealtime();
        int noStartAction=SelfRunRolloverPolicy.postDispatchNoStartAction(ensurePostDispatchNoStartWindow(),
                store.turnObserverSawStop(),networkState.validatedSinceElapsed(),nowElapsed,postDispatchTransientSeen);
        if(noStartAction==SelfRunRolloverPolicy.NO_START_PAUSE_TRANSIENT){
            String kind=postDispatchTransientKind.isEmpty()?"UNKNOWN":postDispatchTransientKind;
            runLog.record(store,"POST_DISPATCH_NO_START","action=pause_transient;kind="+kind);
            enterPreservedPause("CHATGPT_POST_DISPATCH_TRANSIENT","ChatGPT 일시적 서비스 오류가 관찰되어 자동 승계를 보류했습니다.",false);
            NotificationHelper.notifyUser(this,"일시정지",store.status());return;
        }
        if(noStartAction==SelfRunRolloverPolicy.NO_START_ROLLOVER){rolloverConversation(SelfRunRolloverPolicy.CONTINUATION_NO_START_TIMEOUT);return;}
    }
    if(!SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase))resumeWebView();
    maybeCaptureConversationUrl(webView.getUrl());
    if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)&&bootstrapSendTimedOut(store.phaseStartedAt(),System.currentTimeMillis())){failBootstrapSubmissionTimeout("deadline_invalid_or_elapsed");return;}
    if((SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase))&&store.conversationUrl().isEmpty()){scheduleWeb(2000L);return;}
    if(!routeAcceptable(webView.getUrl())){
        recordContinuationRouteMismatch(webView.getUrl());
        if(SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){
            if(networkState.isValidated())rolloverConversation(SelfRunRolloverPolicy.ROUTE_MISMATCH);else scheduleWeb(5_000L);
        }else scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);
        return;
    }
    String script;
    switch(phase){
        case SelfRunStore.PHASE_BOOTSTRAP->script=SelfRunDom.prepareInitialContext(store.projectUrl(),store.mode(),store.runId());
        case SelfRunStore.PHASE_BOOTSTRAP_MODEL->script=WorkPreferenceDom.modelForProject(store.projectUrl(),store.pendingModel());
        case SelfRunStore.PHASE_BOOTSTRAP_REASONING->script=WorkPreferenceDom.reasoningForProject(store.projectUrl(),store.pendingReasoning());
        case SelfRunStore.PHASE_BOOTSTRAP_SEND->{String prompt=commandPrompt(SelfRunStore.RETRY_BOOTSTRAP);script=SelfRunContinuationDom.prepareBootstrap(store.projectUrl(),prompt,store.commandMarkerId());}
        case SelfRunStore.PHASE_WAIT_TURN_COMPLETION->{String token=ensureTurnObserverToken();script=SelfRunContinuationDom.observeTurnCompletion(store.conversationUrl(),store.runId(),token,TURN_COMPLETION_STABILITY_MS,turnObserverNeedsIdleBaseline || store.turnObserverSawStop());}
        case SelfRunStore.PHASE_APPLY_PREFS->script=WorkPreferenceDom.modelForConversation(store.conversationUrl(),store.pendingModel());
        case SelfRunStore.PHASE_APPLY_REASONING->script=WorkPreferenceDom.reasoningForConversation(store.conversationUrl(),store.pendingReasoning());
        case SelfRunStore.PHASE_SEND_CONTINUE->{String prompt=continuationPrompt();script=SelfRunContinuationDom.prepareDriveTurn(store.conversationUrl(),prompt,continuationMarkerId());}
        default->{store.setLastError("WEB_STATE_RETRY","Drive V1 WebView 단계를 자동 재확인합니다: "+phase);scheduleWeb(2000L);return;}
    }
    evaluate(phase,script);
}

private String ensureTurnObserverToken(){String token=store.turnObserverToken();if(token.isEmpty()){token=UUID.randomUUID().toString().replace("-","");store.prepareTurnObserver(token);}return token;}

private void evaluate(String phase,String script){
    WebView active=webView;int webGeneration=generation;String runId=store.runId();int evaluationId=++webEvaluationId;
    BootstrapRunStateStore.Window initialWindow=null;
    if(SelfRunStore.PHASE_BOOTSTRAP.equals(phase)){
        long now=System.currentTimeMillis();
        initialWindow=BootstrapRunStateStore.touchBootstrap(this,runId,ChatReasoningPreferenceStore.selectionForRun(runId),now);
        if(!initialWindow.persisted){failBootstrap(BootstrapResultPolicy.STATE_PERSIST_FAILED,"bootstrap window persistence failed",null);return;}
        if(initialWindow.expired(now)){failBootstrap(BootstrapResultPolicy.TIMEOUT,"bootstrap deadline reached before evaluation",null);return;}
    }
    domInFlight=true;
    if(initialWindow!=null)scheduleBootstrapCallbackDeadline(active,webGeneration,runId,evaluationId,initialWindow.deadlineAt);
    else if(shouldGuardContinuationCallback(phase))scheduleContinuationCallbackDeadline(active,webGeneration,runId,evaluationId,phase);
    try{
        active.evaluateJavascript(script,raw->{
  if(active!=webView||webGeneration!=generation||!runId.equals(store.runId())||evaluationId!=webEvaluationId)return;
  domInFlight=false;if(!canRun())return;
  if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)&&bootstrapSendTimedOut(store.phaseStartedAt(),System.currentTimeMillis())){failBootstrapSubmissionTimeout("deadline_invalid_or_elapsed");return;}
  BootstrapResultPolicy.Parsed parsed=BootstrapResultPolicy.parse(raw);
  JSONObject result=parsed.result;String status=parsed.status,detail=parsed.detail;
  if(isContinuationDiagnosticPhase(phase)&&SelfRunRolloverPolicy.continuationProgressStatus(status))rollover.clearLocalFailures(runId);
  if(SelfRunStore.PHASE_BOOTSTRAP.equals(phase)){
      BootstrapRunStateStore.Window current=BootstrapRunStateStore.recordBootstrapResult(this,runId,status,detail,System.currentTimeMillis());
      runLog.record(store,"DOM_RESULT",BootstrapResultPolicy.logDetail(parsed,current,webGeneration,bootstrapScope()));
      if(!current.persisted){failBootstrap(BootstrapResultPolicy.STATE_PERSIST_FAILED,"bootstrap result persistence failed",result.optJSONObject("diagnostics"));return;}
      String fatal=BootstrapResultPolicy.fatalStatus(parsed,current.deadlineAt,System.currentTimeMillis());
      if(!fatal.isEmpty()){failBootstrap(fatal,detail,result.optJSONObject("diagnostics"));return;}
  }
  if(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)){
      if("OBSERVER_ARMED".equals(status)){turnObserverNeedsIdleBaseline=false;store.setStatus("STOP/SEND 영역 이벤트 관찰 중 · 답변 완료 후 5초 재확인");String observerToken=store.turnObserverToken();boolean firstArm=!observerToken.isEmpty()&&!observerToken.equals(loggedTurnObserverToken);boolean rebound=detail.contains("bindingChanged=1");if(firstArm||rebound){runLog.record(store,"TURN_COMPLETION_OBSERVER","result="+(rebound&&!firstArm?"rebound":"armed")+";detail="+BootstrapResultPolicy.safe(detail,180));loggedTurnObserverToken=observerToken;}releaseWakeLock();scheduleWeb(TURN_OBSERVER_HEALTHCHECK_MS);return;}
      if("OBSERVER_UNAVAILABLE".equals(status)){runLog.record(store,"TURN_COMPLETION_OBSERVER","result=arm_retry");scheduleWeb(1200L);return;}
  }
  if("TARGET_ERROR".equals(status)){
      recordContinuationTargetError(phase,detail);
      if(SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){if(networkState.isValidated())rolloverConversation(SelfRunRolloverPolicy.TARGET_ERROR);else scheduleWeb(5_000L);}
      else if(!isContinuationDiagnosticPhase(phase))restoreCanonical();else scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);
      return;
  }
  if("AUTH_REQUIRED".equals(status)){enterPreservedPause("CHATGPT_AUTH_REQUIRED","ChatGPT 로그인 필요 · 사용자 조치 대기",false);NotificationHelper.notifyUser(this,"확인 필요",store.status());return;}
  if(isWorkPreferenceFailureStatus(status)){runLog.record(store,"WORK_PREFERENCE_FAILURE","status="+BootstrapResultPolicy.safe(status,80)+";detail="+BootstrapResultPolicy.safe(detail,180)+BootstrapResultPolicy.compactDiagnostics(result.optJSONObject("diagnostics")));pauseError(status,workPreferenceFailureMessage(status));return;}
  if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)){
      if("BOOTSTRAP_CLICKED".equals(status)||"SUBMISSION_CONFIRMED".equals(status)||"VERIFY_REQUIRED".equals(status)){bootstrapSubmitted(detail);return;}
      if("SUBMISSION_FAILED".equals(status)||SelfRunContinuationDom.SUBMISSION_PENDING.equals(status)||"COMPOSER_CLEARING".equals(status)||"COMPOSER_INPUTTING".equals(status)||SelfRunContinuationDom.STOP.equals(status)||SelfRunContinuationDom.SEND_DISABLED.equals(status)||SelfRunContinuationDom.UNKNOWN.equals(status)||"SCRIPT_ERROR".equals(status)){recordContinuationWait(phase,status,detail);scheduleWeb(BOOTSTRAP_SEND_POLL_MS);return;}
  }
  if(SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)){
      if("CONTINUE_CLICKED".equals(status)||"SUBMISSION_CONFIRMED".equals(status)||"VERIFY_REQUIRED".equals(status)){rollover.clearLocalFailures(runId);continuationSubmitted(detail);return;}
      if(SelfRunRolloverPolicy.shouldCountContinuationFailure(status,store.phaseStartedAt(),System.currentTimeMillis())&&SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){
          recordContinuationWait(phase,status,detail);
          if(!networkState.isValidated()){rollover.clearLocalFailures(runId);scheduleWeb(1200L);return;}
          int failures=rollover.recordLocalFailure(runId,status);
          if(SelfRunRolloverPolicy.localFailureBudgetExhausted(failures)){rolloverConversation(SelfRunRolloverPolicy.CONTINUATION_NO_PROGRESS);return;}
          scheduleWeb(1200L);return;
      }
      if("SUBMISSION_FAILED".equals(status)||SelfRunContinuationDom.SUBMISSION_PENDING.equals(status)||"COMPOSER_CLEARING".equals(status)||"COMPOSER_INPUTTING".equals(status)||SelfRunContinuationDom.STOP.equals(status)||SelfRunContinuationDom.SEND_DISABLED.equals(status)||SelfRunContinuationDom.UNKNOWN.equals(status)||"SCRIPT_ERROR".equals(status)){recordContinuationWait(phase,status,detail);scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);return;}
  }
  if("UI_WAIT".equals(status)||"WAIT".equals(status)){recordContinuationWait(phase,status,detail);scheduleWeb(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)?BOOTSTRAP_SEND_POLL_MS:("WAIT".equals(status)?2000L:1200L));return;}
  if(isConversationLocalFailureStatus(status)&&SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){
      int failures=rollover.recordLocalFailure(runId,status);
      if(networkState.isValidated()&&SelfRunRolloverPolicy.localFailureBudgetExhausted(failures)){rolloverConversation(SelfRunRolloverPolicy.CONTINUATION_CALLBACK_TIMEOUT);return;}
      scheduleWeb(1200L);return;
  }
  handleWebResult(phase,status,result);
        });
    }catch(Throwable error){
        if(active!=webView||webGeneration!=generation||!runId.equals(store.runId())||evaluationId!=webEvaluationId)return;
        domInFlight=false;
        if(SelfRunStore.PHASE_BOOTSTRAP.equals(phase))failBootstrap(BootstrapResultPolicy.SCRIPT_ERROR,error.getClass().getSimpleName(),null);
        else if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)){if(bootstrapSendTimedOut(store.phaseStartedAt(),System.currentTimeMillis()))failBootstrapSubmissionTimeout("deadline_invalid_or_elapsed");else scheduleWeb(BOOTSTRAP_SEND_POLL_MS);}
        else if(SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){int failures=rollover.incrementLocalFailure(runId);if(networkState.isValidated()&&SelfRunRolloverPolicy.localFailureBudgetExhausted(failures))rolloverConversation(SelfRunRolloverPolicy.CONTINUATION_CALLBACK_TIMEOUT);else scheduleWeb(2000L);}
        else scheduleWeb(2000L);
    }
}

private void scheduleBootstrapCallbackDeadline(WebView active,int webGeneration,String runId,int evaluationId,long deadlineAt){
    long delay=Math.max(1L,deadlineAt-System.currentTimeMillis());
    handler.postDelayed(()->{
        if(active!=webView||webGeneration!=generation||!runId.equals(store.runId())||evaluationId!=webEvaluationId||!domInFlight||!canRun()||!SelfRunStore.PHASE_BOOTSTRAP.equals(store.phase()))return;
        domInFlight=false;webEvaluationId++;
        failBootstrap(BootstrapResultPolicy.TIMEOUT,"evaluateJavascript callback did not return before bootstrap deadline",null);
    },delay);
}

private void scheduleContinuationCallbackDeadline(WebView active,int webGeneration,String runId,int evaluationId,String phase){
    handler.postDelayed(()->{
        if(active!=webView||webGeneration!=generation||!runId.equals(store.runId())||evaluationId!=webEvaluationId||!domInFlight||!canRun()||!phase.equals(store.phase())||!shouldGuardContinuationCallback(phase))return;
        domInFlight=false;webEvaluationId++;
        if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)){recoverBootstrapSendCallback();return;}
        runLog.record(store,"DOM_RESULT",SelfRunWebDiagnostics.callbackTimeoutDetail(phase));
        if(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)){
  turnCompletionCallbackTimeouts++;
  runLog.record(store,"TURN_COMPLETION_RESYNC","result=callback_timeout;count="+turnCompletionCallbackTimeouts);
  if(turnCompletionCallbackTimeouts>=TURN_COMPLETION_CALLBACK_TIMEOUT_RESYNC_THRESHOLD){requestTurnCompletionResync("evaluate_javascript_timeout");return;}
  releaseWakeLock();scheduleWeb(TURN_OBSERVER_HEALTHCHECK_MS);return;
        }
        int failures=rollover.incrementLocalFailure(runId);
        if(SelfRunRolloverPolicy.knownConversation(store.conversationUrl())&&networkState.isValidated()&&SelfRunRolloverPolicy.localFailureBudgetExhausted(failures)){
  rolloverConversation(SelfRunRolloverPolicy.CONTINUATION_CALLBACK_TIMEOUT);return;
        }
        releaseWakeLock();scheduleWeb(1200L);
    },CONTINUATION_CALLBACK_TIMEOUT_MS);
}

private void recoverBootstrapSendCallback(){
    runLog.record(store,"DOM_RESULT",SelfRunWebDiagnostics.callbackTimeoutDetail(SelfRunStore.PHASE_BOOTSTRAP_SEND));
    long now=System.currentTimeMillis();
    if(bootstrapSendTimedOut(store.phaseStartedAt(),now)){failBootstrapSubmissionTimeout("deadline_invalid_or_elapsed");return;}
    if(bootstrapSendCallbackRecoveryExhausted(bootstrapSendCallbackRecoveries)){failBootstrapSubmissionTimeout("callback_recovery_exhausted");return;}
    bootstrapSendCallbackRecoveries++;
    scheduleWeb(BOOTSTRAP_SEND_POLL_MS);
}

private void failBootstrapSubmissionTimeout(String reason){
    if(!canRun()||!SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(store.phase()))return;
    String message="첫 요청 제출 확인 제한시간을 초과했습니다.";
    store.setLastError(CHAT_BOOTSTRAP_SUBMISSION_TIMEOUT,message);
    runLog.record(store,"BOOTSTRAP_FAILURE","code="+CHAT_BOOTSTRAP_SUBMISSION_TIMEOUT+";phase=bootstrap_send;reason="+reason);
    if(SelfRunRolloverPolicy.knownConversation(store.conversationUrl())&&networkState.isValidated()){
        rolloverConversation(SelfRunRolloverPolicy.BOOTSTRAP_SUBMISSION_TIMEOUT);return;
    }
    enterPreservedPause(CHAT_BOOTSTRAP_SUBMISSION_TIMEOUT,CHAT_BOOTSTRAP_SUBMISSION_TIMEOUT+" · "+message,false);
    NotificationHelper.notifyUser(this,"확인 필요",store.status());
}

private void failBootstrap(String code,String detail,JSONObject diagnostics){
    if(!canRun()||!SelfRunStore.PHASE_BOOTSTRAP.equals(store.phase()))return;
    String message=bootstrapFailureMessage(code);
    BootstrapRunStateStore.markBootstrapFailed(this,store.runId(),code,detail);
    store.setLastError(code,message);
    runLog.record(store,"BOOTSTRAP_FAILURE","code="+BootstrapResultPolicy.safe(code,80)+";detail="+BootstrapResultPolicy.safe(detail,180)+BootstrapResultPolicy.compactDiagnostics(diagnostics));
    enterPreservedPause(code,code+" · "+message,false);
    NotificationHelper.notifyUser(this,"확인 필요",store.status());
}

private String bootstrapScope(){return SelfRunScript.isGeneralChatUrl(store.projectUrl())?"general":"project";}


private static boolean isConversationLocalFailureStatus(String status){return SelfRunRolloverPolicy.hardContinuationFailureStatus(status);}

private void recordContinuationWait(String phase,String status,String detail){if(!isContinuationDiagnosticPhase(phase))return;runLog.record(store,"DOM_RESULT",SelfRunWebDiagnostics.waitDetail(phase,status,detail));}
private void recordContinuationState(String phase,String status){if(!isContinuationDiagnosticPhase(phase))return;runLog.record(store,"DOM_RESULT",SelfRunWebDiagnostics.stateDetail(phase,status));}
private void recordContinuationRouteMismatch(String actual){String phase=store.phase();if(!isContinuationDiagnosticPhase(phase))return;runLog.record(store,"DOM_RESULT",SelfRunWebDiagnostics.routeMismatchDetail(phase,canonicalUrl(),actual));}
private void recordContinuationTargetError(String phase,String detail){if(!isContinuationDiagnosticPhase(phase))return;runLog.record(store,"DOM_RESULT",SelfRunWebDiagnostics.targetErrorDetail(phase,detail));}
private static boolean isContinuationDiagnosticPhase(String phase){return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)||SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}

private boolean completeBootstrap(JSONObject result){
    String runId=store.runId();String requested=ChatReasoningPreferenceStore.selectionForRun(runId);
    if(SelfRunStore.MODE_CHAT.equals(store.mode())){
        String observed=BootstrapResultPolicy.observedReasoning(result);
        String normalizedObserved=ChatReasoningPreferenceStore.normalize(observed);
        if(!ChatReasoningPreferenceStore.shouldApply(normalizedObserved)){failBootstrap(BootstrapResultPolicy.READBACK_MISSING,"effective Chat picker readback missing",result==null?null:result.optJSONObject("diagnostics"));return false;}
        if(ChatReasoningPreferenceStore.shouldApply(requested)){
            if(!requested.equals(normalizedObserved)){failBootstrap(BootstrapResultPolicy.READBACK_MISSING,"explicit Chat picker readback mismatch",result==null?null:result.optJSONObject("diagnostics"));return false;}
            if(!BootstrapRunStateStore.markReasoningApplied(this,runId,normalizedObserved)){failBootstrap(BootstrapResultPolicy.STATE_PERSIST_FAILED,"reasoning applied state persistence failed",result==null?null:result.optJSONObject("diagnostics"));return false;}
        }
        if(!ChatPickerStateStore.saveObserved(this,runId,normalizedObserved)){failBootstrap(BootstrapResultPolicy.STATE_PERSIST_FAILED,"effective Chat picker state persistence failed",result==null?null:result.optJSONObject("diagnostics"));return false;}
    }
    if(!BootstrapRunStateStore.markBootstrapCompleted(this,runId,"READY")){failBootstrap(BootstrapResultPolicy.STATE_PERSIST_FAILED,"bootstrap completion persistence failed",result==null?null:result.optJSONObject("diagnostics"));return false;}
    new SelfRunHistoryStore(this).sync(store);
    return true;
}

private void handleWebResult(String phase,String status,JSONObject result){
    if(SelfRunStore.PHASE_BOOTSTRAP.equals(phase)&&"READY".equals(status)){if(!completeBootstrap(result))return;transition(SelfRunStore.MODE_WORK.equals(store.mode())?SelfRunStore.PHASE_BOOTSTRAP_MODEL:SelfRunStore.PHASE_BOOTSTRAP_SEND,"ChatGPT bootstrap 설정 준비","context_ready");scheduleWeb(250L);return;}
    if(SelfRunStore.PHASE_BOOTSTRAP_MODEL.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.PHASE_BOOTSTRAP_REASONING,"첫 턴 Work 추론 적용","model_ready");scheduleWeb(250L);return;}
    if(SelfRunStore.PHASE_BOOTSTRAP_REASONING.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.PHASE_BOOTSTRAP_SEND,"첫 프롬프트 전송 준비","reasoning_ready");scheduleWeb(250L);return;}
    if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)&&"READY_TO_SUBMIT".equals(status)){String prompt=commandPrompt(SelfRunStore.RETRY_BOOTSTRAP),token=ensureTurnObserverToken();beginPostDispatchNoStartWindow();evaluate(phase,SelfRunContinuationDom.clickPreparedBootstrap(store.projectUrl(),prompt,store.commandMarkerId(),store.runId(),token,TURN_COMPLETION_STABILITY_MS));return;}
    if(SelfRunStore.PHASE_APPLY_PREFS.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.PHASE_APPLY_REASONING,"다음 턴 추론 적용","model_ready");scheduleWeb(250L);return;}
    if(SelfRunStore.PHASE_APPLY_REASONING.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.PHASE_SEND_CONTINUE,"Work MODEL/REASONING 적용 완료 · 다음 턴 전송 준비","reasoning_ready_for_send");scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);return;}
    if(SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)&&"READY_TO_SUBMIT".equals(status)){String prompt=continuationPrompt(),token=ensureTurnObserverToken();beginPostDispatchNoStartWindow();evaluate(phase,SelfRunContinuationDom.clickPreparedDriveTurn(store.conversationUrl(),prompt,continuationMarkerId(),store.runId(),token,TURN_COMPLETION_STABILITY_MS));return;}
    scheduleWeb(750L);
}

private String driveBootstrap(){return commandPrompt(SelfRunStore.RETRY_BOOTSTRAP);}
private String continuationPrompt(){if(continuationAttemptPrompt.isEmpty())continuationAttemptPrompt=SelfRunProtocol.driveContinuation(store.runId(),store.pendingNextInput());return continuationAttemptPrompt;}
private String continuationMarkerId(){if(continuationAttemptMarkerId.isEmpty())continuationAttemptMarkerId=store.runId()+":continue:"+store.driveSignalCursor()+":"+store.phaseStartedAt();return continuationAttemptMarkerId;}
private void clearContinuationAttempt(){continuationAttemptPrompt="";continuationAttemptMarkerId="";}
private void continuationSubmitted(String detail){if(!canRun())return;if(!postDispatchWindowActive())beginPostDispatchNoStartWindow();turnCompletionCallbackTimeouts=0;turnCompletionResyncAttempts=0;String token=ensureTurnObserverToken();runLog.record(store,"CONTINUATION_SUBMISSION_DISPATCHED","detail="+detail);clearContinuationAttempt();store.beginTurnCompletionWait(token,"다음 턴 제출 확인 · 답변 완료 감지 중");turnObserverNeedsIdleBaseline=false;releaseWakeLock();scheduleWeb(0L);}
private void bootstrapSubmitted(String detail){if(!canRun())return;if(!postDispatchWindowActive())beginPostDispatchNoStartWindow();turnCompletionCallbackTimeouts=0;turnCompletionResyncAttempts=0;String token=ensureTurnObserverToken();store.bootstrapSubmissionConfirmed(token);runLog.record(store,"BOOTSTRAP_SUBMISSION_DISPATCHED","detail="+detail);turnObserverNeedsIdleBaseline=false;releaseWakeLock();scheduleWeb(0L);}

private String commandPrompt(String kind){if(!kind.equals(store.activeCommandKind())||store.activeCommandPrompt().isEmpty()){String prompt=SelfRunStore.RETRY_BOOTSTRAP.equals(kind)?rollover.bootstrapPrompt(store):SelfRunProtocol.driveContinuation(store.runId(),store.pendingNextInput());store.beginCommandAttempt(kind,prompt);}return store.activeCommandPrompt();}
private static String kindForPhase(String phase){return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)?SelfRunStore.RETRY_BOOTSTRAP:SelfRunStore.RETRY_CONTINUE;}

    private static boolean isSubmissionPhase(String phase) {return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase) || SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}
private void schedulePostDomDriveSync(long requestedDelay){handler.removeCallbacks(driveRunnable);releaseWakeLock();if(!canRun()||!SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC.equals(store.phase()))return;handler.postDelayed(driveRunnable,Math.max(0L,requestedDelay));}
private void scheduleWeb(long delay){handler.removeCallbacks(webRunnable);if(webView!=null&&canRun()&&isWebAutomationPhase(store.phase()))handler.postDelayed(webRunnable,delay);}
private static boolean isWebAutomationPhase(String phase){return SelfRunStore.PHASE_BOOTSTRAP.equals(phase)||SelfRunStore.PHASE_BOOTSTRAP_MODEL.equals(phase)||SelfRunStore.PHASE_BOOTSTRAP_REASONING.equals(phase)||SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)||SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}

    private void handleDriveFailure(Throwable error, int epoch) {
        if (!canApplyDriveResult(epoch)) return;
        String code; String message;
        if (error instanceof DriveApiClient.OutcomeUnknownException) {
            if (SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD.equals(store.phase())) {
                code = "DRIVE_ATTACHMENT_UPLOAD_RESULT_PENDING";
                message = "첨부파일 업로드 결과를 예약된 Drive 파일 ID로 재확인합니다.";
            } else {
                code = "DRIVE_DOCUMENT_CREATE_RESULT_PENDING";
                message = "네이티브 Google Docs 생성 결과를 재확인합니다.";
            }
        } else if (error instanceof DriveApiClient.ApiException api) {
            code = "DRIVE_HTTP_RETRY_" + api.status; message = "Drive API HTTP " + api.status + " 응답을 자동 재시도합니다.";
        } else if (retryableNetworkError(error)) {
            code = "DRIVE_NETWORK_RETRY"; message = "Drive 네트워크 오류를 자동 재시도합니다.";
        } else if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            code = "DRIVE_STATE_RECHECK"; message = "Drive 상태 검증을 다시 수행합니다.";
        } else {
            code = "DRIVE_OPERATION_RETRY"; message = "Drive 요청을 자동 재시도합니다.";
        }
        scheduleDriveRecovery(code, message, epoch);
    }

    private void scheduleDriveRecovery(String code, String message, int expectedEpoch) {
        if (!canApplyDriveResult(expectedEpoch)) return;
        applyDriveResult(expectedEpoch, () -> {long delay = nextDriveRetryDelay();store.setLastError(code, message);store.setStatus(message + " · 자동 재시도 대기");runLog.record(store, "DRIVE_BACKOFF", "kind=" + code + ";attempt=" + retryAttempt + ";delayMs=" + delay);handler.removeCallbacks(driveRetryRunnable);handler.postDelayed(driveRetryRunnable, delay);});
    }

    private void scheduleAuthorizationRetry(String code, String message, int expectedEpoch,String expectedRunId, String expectedPhase) {
        if (Looper.myLooper() != Looper.getMainLooper()) {handler.post(() -> scheduleAuthorizationRetry(code, message, expectedEpoch, expectedRunId, expectedPhase));return;}
        synchronized (automationStateLock) {synchronized (SelfRunStore.RUN_STATE_LOCK) {if (expectedEpoch != automationEpoch || !expectedRunId.equals(store.runId()) || !expectedPhase.equals(store.phase()) || !canRun() || !drivePhase(store.phase())) return;long delay = nextDriveRetryDelay();store.setLastError(code, message);store.setStatus(message + " · 자동 재시도 대기");runLog.record(store, "DRIVE_AUTH_RETRY", "kind=" + code + ";attempt=" + retryAttempt + ";delayMs=" + delay);handler.removeCallbacks(driveRetryRunnable);handler.postDelayed(driveRetryRunnable, delay);}}
    }

    private long nextDriveRetryDelay() {retryAttempt = retryAttempt == Integer.MAX_VALUE ? Integer.MAX_VALUE : retryAttempt + 1;int index = Math.min(Math.max(0, retryAttempt - 1), BACKOFF.length - 1);long base = BACKOFF[index];long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 4L));return base + jitter;}
    private void retryDrive() {if (canRun() && drivePhase(store.phase())) authorizeAndRunDrive();}
    private static boolean retryableNetworkError(Throwable error) {return error instanceof IOException;}

private static void verifyMetadata(DriveApiClient.Metadata m,String job,String mime,String parent,String kind){if(m.trashed||m.shared||!job.equals(m.name)||!mime.equals(m.mimeType)||!parent.equals(m.parentId)||!m.isAppAuthorized||(DriveApiClient.MIME_FOLDER.equals(mime)&&!m.canAddChildren)||!job.equals(m.appProperties.optString("job_id"))||!kind.equals(m.appProperties.optString("selfrun_kind")))throw new IllegalStateException("Drive metadata readback mismatch: "+kind);}

    private static String documentUrl(DriveApiClient.Metadata metadata) {return new Uri.Builder().scheme("https").authority("docs.google.com").appendPath("document").appendPath("d").appendPath(metadata.id).appendPath("edit").build().toString();}

private void transition(String next, String status, String reason) {String prior = store.phase();if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(next))bootstrapSendCallbackRecoveries=0; store.setPhase(next); store.setStatus(status);runLog.record(store, "STATE_TRANSITION", "from=" + prior + ";to=" + next + ";reason=" + reason);}

    private void rolloverConversation(String cause) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post(() -> rolloverConversation(cause)); return; }
        if (!canRun()) return;
        String predecessor = store.runId();
        stopAutomationCallbacks();
        cleanupWebView();
        releaseWakeLock();
        SelfRunRolloverCoordinator.Result result = rollover.beginOrResume(store, cause);
        runLog.record(store, "ROLLOVER", "predecessor=" + predecessor + ";cause=" + SelfRunRolloverPolicy.normalizeCause(cause) + ";result=" + result.status + ";successor=" + result.successorRunId);
        if (SelfRunRolloverCoordinator.RESULT_LOOP_GUARD.equals(result.status)) {
            enterPreservedPause("ROLLOVER_LOOP_GUARD", "동일 원인의 연속 자동 승계를 차단했습니다: " + result.cause, false);
            NotificationHelper.notifyUser(this, "확인 필요", store.status());
            return;
        }
        if (result.started()) {
            adoptSuccessorRuntime();
            handler.post(this::resumeStateMachine);
            return;
        }
        if (rollover.hasPendingClaim()) {
            handler.postDelayed(this::resumePendingRollover, 5_000L);
            return;
        }
        enterPreservedPause("ROLLOVER_FAILED", "자동 승계 상태를 안전하게 확정하지 못했습니다.", false);
        NotificationHelper.notifyUser(this, "확인 필요", store.status());
    }

    private void pauseError(String code, String message) {int epoch = automationEpoch;pauseError(code, message, epoch, store.runId(), store.phase());}
    private void pauseError(String code, String message, int expectedEpoch) {pauseError(code, message, expectedEpoch, driveOperationRunId, store.phase());}
    private void pauseError(String code, String message, int expectedEpoch,String expectedRunId, String expectedPhase) {
        if (Looper.myLooper() != Looper.getMainLooper()) {handler.post(() -> pauseError(code, message, expectedEpoch, expectedRunId, expectedPhase));return;}
        synchronized (automationStateLock) {synchronized (SelfRunStore.RUN_STATE_LOCK) {if (expectedEpoch != automationEpoch || !expectedRunId.equals(store.runId()) || !expectedPhase.equals(store.phase()) || !canRun()) return;store.setLastError(code, message);enterPreservedPause(code, code + " · " + message, false);NotificationHelper.notifyUser(this, "확인 필요", store.status());}}
    }

private void pauseFromUi() {if (!canRun()) return;startForegroundCompat();enterPreservedPause("UI_PAUSE", "사용자 일시정지", false);NotificationHelper.notifyUser(this, "일시정지", store.status());}
private void resumeFromUi(){if(!store.paused()||store.userStopped()||store.runId().isEmpty())return;stopAutomationCallbacks();store.beginManualResumeOverride();store.clearLastError();boolean reconcile=SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())&&store.turnObserverSawStop();resumeWebView();startForegroundCompat();if(reconcile){requestTurnCompletionResync("manual_resume");return;}resumeStateMachine();}

    private void enterPreservedPause(String cause, String status, boolean needsContinuation) {
        String prior;
        synchronized (automationStateLock) {synchronized (SelfRunStore.RUN_STATE_LOCK) {prior = store.phase();automationEpoch++; generation++; authorizationInFlight = false; domInFlight = false;store.enterPause(prior, needsContinuation); store.setStatus(status);}}
        removeAutomationCallbacks(); resetPostDispatchNoStartState(); releaseWakeLock(); pauseWebView();runLog.record(store, "PAUSED", cause + ";webview=preserved;drive_ids=preserved");
    }

    private void removeAutomationCallbacks() {handler.removeCallbacks(driveRunnable); handler.removeCallbacks(webRunnable);handler.removeCallbacks(driveRetryRunnable);}
    private void stopAutomationCallbacks() {disconnectTurnObserver();removeAutomationCallbacks();clearContinuationAttempt();resetPostDispatchNoStartState();turnObserverNeedsIdleBaseline=false;synchronized (automationStateLock) {automationEpoch++; generation++; webEvaluationId++; authorizationInFlight = false; domInFlight = false;}}
    private void disconnectTurnObserver(){String token=store==null?"":store.turnObserverToken();WebView active=webView;if(active==null||token.isEmpty())return;try{active.evaluateJavascript(SelfRunContinuationDom.cancelTurnCompletionObserver(token),null);}catch(Throwable ignored){}}
    private void pauseWebView() { if (webView==null||webViewPaused)return;try{webView.onPause();webViewPaused=true;}catch(Throwable ignored){} }
    private void resumeWebView() { if (webView==null||!webViewPaused)return;try{webView.onResume();}catch(Throwable ignored){}finally{webViewPaused=false;} }
    private void restoreCanonical() { String target=canonicalUrl(); if (canRun() && webView != null && validAutomationTarget(target)) { resumeWebView(); webView.loadUrl(target); } }
    private String canonicalUrl() { return store.conversationUrl().isEmpty() ? store.projectUrl() : store.conversationUrl(); }
    private boolean routeAcceptable(String actual) { return store.conversationUrl().isEmpty() ? sameProject(store.projectUrl(), actual) : sameConversation(store.conversationUrl(), actual); }
    private static boolean sameProject(String a, String b) { return SelfRunScript.isGeneralChatUrl(a) ? SelfRunScript.isGeneralChatUrl(b) : ProjectUrlPolicy.sameProject(a,b); }
    private static boolean sameConversation(String a, String b) { return ProjectUrlPolicy.sameConversation(a,b); }
    private static boolean validAutomationTarget(String value) { return SelfRunScript.isGeneralChatUrl(value) || ProjectUrlPolicy.parseProject(value)!=null; }

    private JSONObject parse(String raw) {try { Object outer = new JSONTokener(raw == null ? "" : raw).nextValue(); return new JSONObject(outer instanceof String ? (String) outer : String.valueOf(outer)); }catch (Throwable error) { return new JSONObject(); }}
    private void acquireWakeLock() { if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(2 * 60_000L); }
    private void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
    private void cleanupWebView() {handler.removeCallbacks(webRunnable);turnObserverNeedsIdleBaseline=store!=null&&SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())&&store.turnObserverSawStop();generation++; webEvaluationId++; domInFlight = false;webViewPaused=false;if (host != null) { host.destroy(); host = null; } webView = null;}
    private void stopRuntime() {stopAutomationCallbacks(); cleanupWebView(); releaseWakeLock();stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();}

    @Override public void onDestroy() {destroyed = true;stopAutomationCallbacks(); cleanupWebView(); releaseWakeLock(); if(networkState!=null)networkState.stop(); io.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent) { return null; }

private static final class DriveStateSnapshot{final String phase,runId,baseFolderId,jobFolderId,turnDocumentId,creationStage,lastDriveSignalRaw,lastDriveSignalTimestamp,lastDriveSignalType,mode,lastSeenVersion,lastSeenModifiedTime;final int driveSignalCursor,driveSignalCursorSchemaVersion;DriveStateSnapshot(String phase,String runId,String baseFolderId,String jobFolderId,String turnDocumentId,String creationStage,int cursor,int cursorSchemaVersion,String lastRaw,String lastTimestamp,String lastType,String mode,String lastSeenVersion,String lastSeenModifiedTime){this.phase=phase;this.runId=runId;this.baseFolderId=baseFolderId;this.jobFolderId=jobFolderId;this.turnDocumentId=turnDocumentId;this.creationStage=creationStage;this.driveSignalCursor=cursor;this.driveSignalCursorSchemaVersion=cursorSchemaVersion;this.lastDriveSignalRaw=lastRaw;this.lastDriveSignalTimestamp=lastTimestamp;this.lastDriveSignalType=lastType;this.mode=mode;this.lastSeenVersion=lastSeenVersion;this.lastSeenModifiedTime=lastSeenModifiedTime;}}
}
