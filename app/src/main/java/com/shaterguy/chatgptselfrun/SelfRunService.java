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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/** Drive V1-only runtime. The legacy assistant-DOM state machine is intentionally not reachable. */
public final class SelfRunService extends Service {
    static final String ACTION_RUN = BuildConfig.APPLICATION_ID + ".RUN";
    static final String ACTION_PAUSE = BuildConfig.APPLICATION_ID + ".PAUSE";
    static final String ACTION_RESUME = BuildConfig.APPLICATION_ID + ".RESUME";
    private static final int NOTIFICATION_ID = 17021;
    private static final long NORMAL_POLL_MS = 60_000L;
    static final long CONTINUATION_GUARD_MS = 45_000L;
    static final long SUBMISSION_RETRY_MS = 5 * 60_000L;
    static final long CONTINUE_UI_TIMEOUT_MS = 2 * 60_000L;
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
    private boolean continuationParentGuardAvailable;
    private String lastParentGuardStage = "";
    private int lastParentGuardAttempt = -1;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean driveInFlight;
    private volatile boolean authorizationInFlight;
    private boolean domInFlight;
    private int generation;
    private volatile int automationEpoch;
    private volatile int driveOperationEpoch;
    private volatile String driveOperationRunId = "";
    private volatile String driveOperationAccountId = "";
    private volatile String driveOperationBaseFolderId = "";
    private volatile int retryAttempt;
    private volatile String accessToken = "";
    private volatile String verifiedDriveAccountId = "";
    private volatile String runtimeRunId = "";
    private volatile boolean destroyed;
    /** Serializes pause/stop epoch changes with application of Drive results to durable state. */
    private final Object automationStateLock = new Object();

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
        String currentRunId = store.runId();
        if (!currentRunId.equals(runtimeRunId)) {
            stopAutomationCallbacks();
            runtimeRunId = currentRunId;
            verifiedDriveAccountId = "";
            accessToken = "";
            lastParentGuardStage = "";
            lastParentGuardAttempt = -1;
        }
        if (ACTION_PAUSE.equals(action)) { pauseFromUi(); return store.active() ? START_STICKY : START_NOT_STICKY; }
        if (ACTION_RESUME.equals(action)) { resumeFromUi(); return store.active() ? START_STICKY : START_NOT_STICKY; }
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

private void resumeStateMachine(){if(!canRun())return;String phase=store.phase();if(drivePhase(phase))authorizeAndRunDrive();else if(SelfRunStore.PHASE_DRIVE_COMMIT_GUARD.equals(phase))scheduleGuard();else ensureWebView();}

private static boolean drivePhase(String phase){return SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK.equals(phase)||SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK.equals(phase)||SelfRunStore.PHASE_JOB_ID_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT.equals(phase)||SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK.equals(phase)||SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(phase)||SelfRunStore.PHASE_RESUME_BASELINE.equals(phase);}

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
                if (SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase()) || SelfRunStore.PHASE_RESUME_BASELINE.equals(store.phase())) {
                    pollDriveNow(epoch);
                    retryAttempt = 0;
                    return;
                }
                do {
                    if (!canApplyDriveResult(epoch)) return;
                    String prior = store.phase();
                    runDriveStep(epoch);
                    if (prior.equals(store.phase()) || SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase()) || SelfRunStore.PHASE_RESUME_BASELINE.equals(store.phase())) break;
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

    /**
     * Rechecks the captured automation epoch while holding the same lock used by pause/stop.
     * Every Drive-result mutation is performed through this gate, closing the check-to-write race.
     */
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

private DriveStateSnapshot driveState(int expectedEpoch){synchronized(automationStateLock){synchronized(SelfRunStore.RUN_STATE_LOCK){if(destroyed||expectedEpoch!=automationEpoch||!canRun()||!driveOperationRunId.equals(store.runId())||!drivePhase(store.phase()))return null;return new DriveStateSnapshot(store.phase(),store.runId(),store.runBaseFolderId(),store.jobFolderId(),store.turnDocumentId(),store.creationStage(),store.driveSignalCursor(),store.mode(),store.lastSeenDriveVersion(),store.lastSeenModifiedTime());}}}

private void runDriveStep(int epoch)throws Exception{if(!canApplyDriveResult(epoch))return;switch(store.phase()){case SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK->applyDriveResult(epoch,()->transition(SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK,"Drive 기준 폴더 확인 중","account_authorized"));case SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK->checkBaseFolder(epoch);case SelfRunStore.PHASE_JOB_ID_CREATE->applyDriveResult(epoch,()->transition(SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE,"Drive Job 폴더 생성 중","job_id_persisted"));case SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE->createOrRecoverJobFolder(epoch);case SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE->createOrRecoverDocument(epoch);case SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT->initializeDocument(epoch);case SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK->verifyInitialDocument(epoch);case SelfRunStore.PHASE_WAIT_DRIVE_COMMIT,SelfRunStore.PHASE_RESUME_BASELINE->pollDriveNow(epoch);default->scheduleDriveRecovery("DRIVE_STATE_RECHECK","알 수 없는 Drive 단계를 자동 재확인합니다: "+store.phase(),epoch);}}

    private void checkBaseFolder(int epoch) throws Exception {
        String base = driveOperationBaseFolderId;
        if (!DriveApiClient.validFileId(base)) {
            pauseError("DRIVE_BASE_FOLDER_REBIND_REQUIRED", "Runs 기준 폴더 ID가 없습니다.", epoch);
            return;
        }
        DriveApiClient.Metadata metadata = drive.getMetadata(accessToken, base);
        if (!canApplyDriveResult(epoch)) return;
        if (metadata.trashed || !DriveApiClient.MIME_FOLDER.equals(metadata.mimeType)
                || !metadata.isAppAuthorized || !metadata.canAddChildren || metadata.shared) {
            pauseError("DRIVE_BASE_FOLDER_REBIND_REQUIRED", "Runs 기준 폴더가 접근 불가능합니다.", epoch);
            return;
        }
        applyDriveResult(epoch, () -> transition(SelfRunStore.PHASE_JOB_ID_CREATE,
                "Job ID 확인 완료", "base_folder_verified"));
    }

    private void createOrRecoverJobFolder(int epoch) throws Exception {
        DriveStateSnapshot initial = driveState(epoch);
        if (initial == null) return;
        String base = driveOperationBaseFolderId;
        String folderId = initial.jobFolderId;
        if (folderId.isEmpty()) {
            if (!SelfRunStore.CREATION_NONE.equals(initial.creationStage)) {
                throw new IllegalStateException("folder creation state has no reserved id");
            }
            folderId = drive.generateFolderId(accessToken);
            String reservedId = folderId;
            if (!applyDriveResult(epoch, () -> store.reserveJobFolderId(reservedId))) return;
        }

        DriveApiClient.Metadata metadata = null;
        try {
            metadata = drive.getMetadata(accessToken, folderId);
        } catch (DriveApiClient.ApiException api) {
            if (api.status != 404) throw api;
        }
        if (metadata == null) {
            DriveStateSnapshot current = driveState(epoch);
            if (current == null) return;
            String stage = current.creationStage;
            if (!(SelfRunStore.CREATION_FOLDER_ID_RESERVED.equals(stage)
                    || SelfRunStore.CREATION_FOLDER_CREATING.equals(stage))) {
                throw new IllegalStateException("created job folder disappeared");
            }
            if (SelfRunStore.CREATION_FOLDER_ID_RESERVED.equals(stage)
                    && !applyDriveResult(epoch, store::markJobFolderCreating)) return;
            if (!canApplyDriveResult(epoch)) return;
            try {
                metadata = drive.createJobFolder(accessToken, folderId, driveOperationRunId, base);
            } catch (DriveApiClient.ApiException api) {
                if (api.status != 409) throw api;
                metadata = drive.getMetadata(accessToken, folderId);
            }
        }
        if (!folderId.equals(metadata.id)) {
            throw new IllegalStateException("Drive returned a different reserved folder id");
        }
        verifyMetadata(metadata, driveOperationRunId, DriveApiClient.MIME_FOLDER, base, "job_folder");
        String persistedFolderId = folderId;
        if (!applyDriveResult(epoch, () -> store.saveJobFolder(persistedFolderId))) return;
        DriveApiClient.Metadata readback = drive.getMetadata(accessToken, folderId);
        verifyMetadata(readback, driveOperationRunId,
                DriveApiClient.MIME_FOLDER, base, "job_folder");
        applyDriveResult(epoch, () -> transition(SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE,
                "실행턴 Google Docs 생성 중", "job_folder_ready"));
    }

    private void createOrRecoverDocument(int epoch) throws Exception {
        DriveStateSnapshot initial = driveState(epoch);
        if (initial == null) return;
        String parent = initial.jobFolderId;
        if (!initial.turnDocumentId.isEmpty()) {
            verifyMetadata(drive.getMetadata(accessToken, initial.turnDocumentId), driveOperationRunId,
                    DriveApiClient.MIME_DOCUMENT, parent, "turn_document");
        } else if (SelfRunStore.CREATION_DOCUMENT_CREATING.equals(initial.creationStage)) {
            DriveApiClient.Metadata recovered = drive.findSingleTurnDocument(accessToken, driveOperationRunId, parent);
            if (!canApplyDriveResult(epoch)) return;
            if (recovered == null) {
                scheduleDriveRecovery("DRIVE_DOCUMENT_CREATE_RESULT_PENDING",
                        "네이티브 Google Docs 생성 결과를 동일 Job 폴더에서 재확인 중입니다.", epoch);
                return;
            }
            verifyMetadata(recovered, driveOperationRunId, DriveApiClient.MIME_DOCUMENT, parent, "turn_document");
            if (!applyDriveResult(epoch, () -> store.saveTurnDocument(recovered.id, documentUrl(recovered)))) return;
            DriveApiClient.Metadata readback = drive.getMetadata(accessToken, recovered.id);
            verifyMetadata(readback, driveOperationRunId, DriveApiClient.MIME_DOCUMENT, parent, "turn_document");
        } else {
            if (!applyDriveResult(epoch, () -> store.setCreationStage(SelfRunStore.CREATION_DOCUMENT_CREATING))) return;
            DriveApiClient.Metadata created;
            try {
                if (!canApplyDriveResult(epoch)) return;
                created = drive.createTurnDocument(accessToken, driveOperationRunId, parent);
            } catch (DriveApiClient.OutcomeUnknownException unknown) {
                if (canApplyDriveResult(epoch)) scheduleDriveRecovery("DRIVE_DOCUMENT_CREATE_RESULT_PENDING",
                        "네이티브 Google Docs 생성 응답이 유실되어 동일 Job 폴더를 재확인합니다.", epoch);
                return;
            } catch (DriveApiClient.ApiException definiteFailure) {
                applyDriveResult(epoch, store::resetDocumentCreateAfterDefiniteFailure);
                throw definiteFailure;
            }
            if (!DriveApiClient.validFileId(created.id)) {
                throw new DriveApiClient.OutcomeUnknownException("native document response has no valid id", null);
            }
            // The response documentId is durable before any secondary metadata validation/readback.
            if (!applyDriveResult(epoch, () -> store.saveTurnDocument(created.id, documentUrl(created)))) return;
            verifyMetadata(created, driveOperationRunId, DriveApiClient.MIME_DOCUMENT, parent, "turn_document");
            DriveApiClient.Metadata readback = drive.getMetadata(accessToken, created.id);
            verifyMetadata(readback, driveOperationRunId,
                    DriveApiClient.MIME_DOCUMENT, parent, "turn_document");
        }
        applyDriveResult(epoch, () -> transition(SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT,
                "실행턴 문서 초기화 중", "turn_document_ready"));
    }

private void initializeDocument(int epoch)throws Exception{DriveStateSnapshot snapshot=driveState(epoch);if(snapshot==null)return;drive.readDocumentText(accessToken,snapshot.turnDocumentId);if(!canApplyDriveResult(epoch))return;applyDriveResult(epoch,()->transition(SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK,"실행턴 signal log readback 검증 중","signal_log_ready"));}

private void verifyInitialDocument(int epoch)throws Exception{DriveStateSnapshot snapshot=driveState(epoch);if(snapshot==null)return;DriveApiClient.Metadata metadata=drive.getMetadata(accessToken,snapshot.turnDocumentId);verifyMetadata(metadata,snapshot.runId,DriveApiClient.MIME_DOCUMENT,snapshot.jobFolderId,"turn_document");String body=drive.readDocumentText(accessToken,snapshot.turnDocumentId);DriveSignalParser.Scan scan=DriveSignalParser.scan(body,snapshot.runId,0,snapshot.mode);applyDriveResult(epoch,()->{store.baselineDriveSignals(scan.totalCount,scan.latest);store.updateDriveSeen(metadata.version,metadata.modifiedTime);transition(SelfRunStore.PHASE_BOOTSTRAP,"Drive 준비 완료 · ChatGPT 새 대화 준비","signal_log_readback_verified");handler.post(()->{if(epoch==automationEpoch&&canRun())ensureWebView();});});}

private void pollDrive(){if((SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())||SelfRunStore.PHASE_RESUME_BASELINE.equals(store.phase()))&&canRun())authorizeAndRunDrive();}

private void pollDriveNow(int epoch)throws Exception{DriveStateSnapshot snapshot=driveState(epoch);if(snapshot==null)return;DriveApiClient.Metadata metadata=drive.getPollMetadata(accessToken,snapshot.turnDocumentId);if(!canApplyDriveResult(epoch))return;if(metadata.trashed||metadata.shared||!DriveApiClient.MIME_DOCUMENT.equals(metadata.mimeType)||!snapshot.jobFolderId.equals(metadata.parentId)){scheduleDriveRecovery("DRIVE_DOCUMENT_RECHECK","실행턴 문서 상태가 기대값과 달라 동일 문서를 다시 확인합니다.",epoch);return;}boolean changed=!metadata.version.equals(snapshot.lastSeenVersion)||!metadata.modifiedTime.equals(snapshot.lastSeenModifiedTime);boolean resume=SelfRunStore.PHASE_RESUME_BASELINE.equals(snapshot.phase),retry=SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(snapshot.phase)&&store.awaitingCommandAck()&&store.submissionRetryDue();if(!changed&&!resume&&!retry){applyDriveResult(epoch,this::scheduleDrivePoll);return;}String text=drive.readDocumentText(accessToken,snapshot.turnDocumentId);if(!canApplyDriveResult(epoch))return;DriveSignalParser.Scan scan=DriveSignalParser.scan(text,snapshot.runId,snapshot.driveSignalCursor,snapshot.mode);if(resume){if(applyDriveResult(epoch,()->{store.baselineManualResume(scan.totalCount,scan.latest);store.updateDriveSeen(metadata.version,metadata.modifiedTime);}))handler.post(this::ensureWebView);return;}if(!applyDriveResult(epoch,()->{if(scan.cursorRebased)store.baselineDriveSignals(scan.totalCount,scan.latest);else if(!scan.unseen.isEmpty())store.applyDriveSignals(scan.unseen,System.currentTimeMillis(),CONTINUATION_GUARD_MS);store.updateDriveSeen(metadata.version,metadata.modifiedTime);} ))return;handler.post(()->{if(SelfRunStore.PHASE_DRIVE_COMMIT_GUARD.equals(store.phase()))scheduleGuard();else if(SelfRunStore.PHASE_PAUSED.equals(store.phase())||SelfRunStore.PHASE_DONE.equals(store.phase())){if(store.terminalSideEffectPending())replayTerminalSideEffect();}else if(SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())){if(store.awaitingCommandAck()&&store.submissionRetryDue()){store.prepareCommandRetry();ensureWebView();}else scheduleDrivePoll();}});}



private void replayTerminalSideEffect(){startForegroundCompat();String owner=store.terminalSideEffectRunId(),raw=store.terminalSideEffectCommitId(),type=store.terminalSideEffectType();handler.post(()->{synchronized(automationStateLock){synchronized(SelfRunStore.RUN_STATE_LOCK){if(!store.terminalSideEffectOwnedBy(owner,raw,type))return;switch(type){case "DONE"->finishDoneSideEffect(owner,raw,type);case "PAUSED"->finishPersistedTerminalPause("DRIVE_PAUSED","일시정지",owner,raw,type);case "USER_ACTION_REQUIRED"->finishPersistedTerminalPause("DRIVE_USER_ACTION_REQUIRED","확인 필요",owner,raw,type);default->{return;}}}}});}


    private void finishDoneSideEffect(String ownerRunId, String commitId, String type) {
        if (!store.terminalSideEffectOwnedBy(ownerRunId, commitId, type)) return;
        runLog.record(store, "TERMINAL", "done_signal");
        NotificationHelper.notifyUser(this, "완료", ownerRunId);
        if (!store.acknowledgeTerminalSideEffect(ownerRunId, commitId, type)) return;
        stopRuntime();
    }

private void finishPersistedTerminalPause(String cause, String alertTitle, String ownerRunId,
                                          String commitId, String type) {
    if (!store.terminalSideEffectOwnedBy(ownerRunId, commitId, type)) return;
    stopAutomationCallbacks();
    releaseWakeLock();
    pauseWebView();
    runLog.record(store, "PAUSED", cause + ";webview=preserved;drive_ids=preserved");
    NotificationHelper.notifyUser(this, alertTitle, store.status());
    store.acknowledgeTerminalSideEffect(ownerRunId, commitId, type);
}

private void scheduleGuard(){releaseWakeLock();handler.removeCallbacks(webRunnable);handler.removeCallbacks(guardRunnable);long detected=store.commitDetectedAt(),due=store.guardDueAt();boolean valid=DriveSignalParser.Type.TURN_COMPLETED.name().equals(store.pendingDriveSignalType())&&!store.pendingDriveSignalRaw().isEmpty()&&detected>0&&due-detected==CONTINUATION_GUARD_MS;if(!valid){runLog.record(store,"DRIVE_GUARD_RECOVERY","invalid_guard_state");store.repairGuard(System.currentTimeMillis(),CONTINUATION_GUARD_MS);if(SelfRunStore.PHASE_READ_NEXT_CONTROL.equals(store.phase())){ensureWebView();return;}due=store.guardDueAt();}handler.postDelayed(guardRunnable,Math.max(0,due-System.currentTimeMillis()));}

private void guardElapsed(){if(canRun()&&SelfRunStore.PHASE_DRIVE_COMMIT_GUARD.equals(store.phase())){transition(SelfRunStore.PHASE_READ_NEXT_CONTROL,"Drive TURN_COMPLETED 확인 · conversation 제어신호 1회 확인","guard_elapsed");ensureWebView();}}


private void ensureWebView(){if(!canRun()||!isWebAutomationPhase(store.phase()))return;String target=store.conversationUrl().isEmpty()?store.projectUrl():store.conversationUrl();if(target.isEmpty()||!validAutomationTarget(target)){store.setLastError("TARGET_MISSING_RETRY","ChatGPT 대상 URL을 안전하게 재확인합니다.");handler.postDelayed(this::ensureWebView,SUBMISSION_RETRY_MS);return;}acquireWakeLock();if(webView!=null){maybeCaptureConversationUrl(webView.getUrl());scheduleWeb(250L);return;}launchWebView(target);}

    private void launchWebView(String target) {
        cleanupWebView();
        try {
            String launchedRunId = store.runId();
            host = HeadlessWebViewHost.create(this); webView = host.webView(); continuationParentGuardAvailable = WebViewConfig.applyAutomation(webView);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    if (!launchedRunId.equals(store.runId())) return;
                    generation++; domInFlight = false; maybeCaptureConversationUrl(url);
                }
                @Override public void onPageFinished(WebView view, String url) {
                    if (!launchedRunId.equals(store.runId())) return;
                    maybeCaptureConversationUrl(url);
                    if (isWebAutomationPhase(store.phase())) scheduleWeb(800L);
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (!launchedRunId.equals(store.runId())) return true;
                    if (!request.isForMainFrame()) return false;
                    String requested = String.valueOf(request.getUrl());
                    boolean allowed = store.conversationUrl().isEmpty()
                            ? sameProject(store.projectUrl(), requested) : sameConversation(store.conversationUrl(), requested);
                    if (!allowed) postWebCallback(SelfRunService.this::restoreCanonical, 800L);
                    return !allowed;
                }
                @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse s) {
                    if (launchedRunId.equals(store.runId()) && r.isForMainFrame() && s.getStatusCode() == 429)
                        scheduleWeb(30_000L);
                }
                @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                    if (launchedRunId.equals(store.runId()) && r.isForMainFrame()
                            && canRun() && isWebAutomationPhase(store.phase()))
                        postWebCallback(() -> { if (v == webView) v.loadUrl(canonicalUrl()); }, 3_000L);
                }
                @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                    h.cancel();
                    if (launchedRunId.equals(store.runId()) && canRun()
                            && isWebAutomationPhase(store.phase())) {
                        runLog.record(store, "WEBVIEW_SSL_RETRY", "cancelled;retry_in=300000");
                        postWebCallback(SelfRunService.this::restoreCanonical, SUBMISSION_RETRY_MS);
                    }
                }
                @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                    cleanupWebView();
                    if (launchedRunId.equals(store.runId()) && !store.paused()
                            && isWebAutomationPhase(store.phase()))
                        postWebCallback(SelfRunService.this::ensureWebView, 2_000L);
                    return true;
                }
            });
            webView.loadUrl(target);
        } catch (Throwable error) {
            cleanupWebView(); postWebCallback(this::ensureWebView, 2_500L);
        }
    }

private void maybeCaptureConversationUrl(String url){if(store.conversationUrl().isEmpty()&&sameProject(store.projectUrl(),url)&&!SelfRunScript.conversationId(url).isEmpty()){store.captureConversationUrl(url);runLog.record(store,"CONVERSATION_CAPTURED","trusted_project_route");}}

    private void postWebCallback(Runnable callback, long delay) {
        int epoch = automationEpoch;
        String runId = store.runId();
        handler.postDelayed(() -> {
            if (epoch == automationEpoch && runId.equals(store.runId()) && canRun()
                    && isWebAutomationPhase(store.phase())) callback.run();
        }, delay);
    }

private void runWebStep(){if(!canRun()||!isWebAutomationPhase(store.phase())||webView==null||domInFlight)return;maybeCaptureConversationUrl(webView.getUrl());String phase=store.phase();boolean continuationPhase=SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);if(SelfRunContinuationCapability.requiresUserAction(continuationPhase,continuationParentGuardAvailable)){pauseUnsupportedParentGuard();return;}if((SelfRunStore.PHASE_READ_NEXT_CONTROL.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||continuationPhase)&&store.conversationUrl().isEmpty()){scheduleWeb(2000L);return;}if(!routeAcceptable(webView.getUrl())){restoreCanonical();return;}String script;switch(phase){case SelfRunStore.PHASE_BOOTSTRAP->script=SelfRunDom.prepareInitialContext(store.projectUrl(),store.mode(),store.runId());case SelfRunStore.PHASE_BOOTSTRAP_MODEL->script=WorkPreferenceDom.modelForProject(store.projectUrl(),"sol");case SelfRunStore.PHASE_BOOTSTRAP_REASONING->script=WorkPreferenceDom.reasoningForProject(store.projectUrl(),"xhigh");case SelfRunStore.PHASE_BOOTSTRAP_SEND->{String prompt=commandPrompt(SelfRunStore.RETRY_BOOTSTRAP);script=SelfRunDom.sendDriveInitial(store.projectUrl(),prompt,store.commandMarkerId());}case SelfRunStore.PHASE_READ_NEXT_CONTROL->script=SelfRunDom.readLatestSelfRunControl(store.conversationUrl(),store.runId());case SelfRunStore.PHASE_APPLY_PREFS->script=WorkPreferenceDom.modelForConversation(store.conversationUrl(),store.pendingModel());case SelfRunStore.PHASE_APPLY_REASONING->script=WorkPreferenceDom.reasoningForConversation(store.conversationUrl(),store.pendingReasoning());case SelfRunStore.PHASE_SEND_CONTINUE->{String prompt=commandPrompt(SelfRunStore.RETRY_CONTINUE);script=SelfRunDom.prepareDriveTurn(store.conversationUrl(),prompt,store.commandMarkerId());}default->{store.setLastError("WEB_STATE_RETRY","Drive V1 WebView 단계를 자동 재확인합니다: "+phase);scheduleWeb(2000L);return;}}evaluate(phase,script);}

private void evaluate(String phase,String script){WebView active=webView;int epoch=generation;String runId=store.runId();domInFlight=true;active.evaluateJavascript(script,raw->{if(active!=webView||epoch!=generation||!runId.equals(store.runId()))return;domInFlight=false;if(!canRun())return;JSONObject result=parse(raw);String status=result.optString("status","SCRIPT_ERROR");if("TARGET_ERROR".equals(status)){restoreCanonical();return;}if("AUTH_REQUIRED".equals(status)){enterPreservedPause("CHATGPT_AUTH_REQUIRED","ChatGPT 로그인 필요 · 사용자 조치 대기",false);NotificationHelper.notifyUser(this,"확인 필요",store.status());return;}if("CAPABILITY_UNAVAILABLE".equals(status)){pauseUnsupportedParentGuard();return;}if("PARENT_GUARD_FAILED".equals(status)&&SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)){pauseParentGuardFailure(result.optString("guardCode","GUARD_INTERNAL_FAILURE"));return;}if(SelfRunStore.PHASE_READ_NEXT_CONTROL.equals(phase)&&("CONTROL_FOUND".equals(status)||"CONTROL_MISSING".equals(status)||"SCRIPT_ERROR".equals(status))){applyNextControl("CONTROL_FOUND".equals(status)?result.optString("signal",""):"");return;}if(isSubmissionPhase(phase)&&("MARKER_FAILED".equals(status)||"SCRIPT_ERROR".equals(status)||"SUBMISSION_AMBIGUOUS".equals(status)||"SUBMISSION_PENDING".equals(status)||"BOOTSTRAP_SUBMISSION_AMBIGUOUS".equals(status)||"BOOTSTRAP_SUBMISSION_PENDING".equals(status))){commandSubmitted(kindForPhase(phase),status);return;}if("BOOTSTRAP_SUBMITTED".equals(status)){commandSubmitted(SelfRunStore.RETRY_BOOTSTRAP,status);return;}if("SUBMITTED".equals(status)&&SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)){commandSubmitted(SelfRunStore.RETRY_CONTINUE,status);return;}if("UI_WAIT".equals(status)||"WAIT".equals(status)){if(SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)){String stage=SelfRunParentGuardPolicy.safeStage(result.optString("stage",""));if(continueUiWaitExpired(stage)){pauseParentGuardFailure("COMPOSER_TIMEOUT");return;}recordParentGuardStage(stage);}scheduleWeb("WAIT".equals(status)?2000L:1200L);return;}handleWebResult(phase,status,result);});}

private void handleWebResult(String phase,String status,JSONObject result){if(SelfRunStore.PHASE_BOOTSTRAP.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.MODE_WORK.equals(store.mode())?SelfRunStore.PHASE_BOOTSTRAP_MODEL:SelfRunStore.PHASE_BOOTSTRAP_SEND,"ChatGPT bootstrap 설정 준비","context_ready");scheduleWeb(250L);return;}if(SelfRunStore.PHASE_BOOTSTRAP_MODEL.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.PHASE_BOOTSTRAP_REASONING,"첫 턴 Work 추론 적용","model_ready");scheduleWeb(250L);return;}if(SelfRunStore.PHASE_BOOTSTRAP_REASONING.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.PHASE_BOOTSTRAP_SEND,"첫 프롬프트 전송 준비","reasoning_ready");scheduleWeb(250L);return;}if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)&&"READY_TO_SUBMIT".equals(status)){String prompt=commandPrompt(SelfRunStore.RETRY_BOOTSTRAP);evaluate(phase,SelfRunDom.clickPreparedDriveInitial(store.projectUrl(),prompt,store.commandMarkerId()));return;}if(SelfRunStore.PHASE_APPLY_PREFS.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.PHASE_APPLY_REASONING,"다음 턴 추론 적용","model_ready");scheduleWeb(250L);return;}if(SelfRunStore.PHASE_APPLY_REASONING.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.PHASE_SEND_CONTINUE,"continuation 준비","reasoning_ready");scheduleWeb(250L);return;}if(SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)&&"READY_TO_SUBMIT".equals(status)){recordParentGuardStage("READY_TO_SUBMIT");String prompt=commandPrompt(SelfRunStore.RETRY_CONTINUE);evaluate(phase,SelfRunDom.clickPreparedDriveTurn(store.conversationUrl(),prompt,store.commandMarkerId()));return;}scheduleWeb(750L);}

private boolean continueUiWaitExpired(String stage){if(!SelfRunStore.PHASE_SEND_CONTINUE.equals(store.phase()))return false;if(!("COMPOSER_WAIT".equals(stage)||"COMPOSER_INPUT_WAIT".equals(stage)||"SEND_BUTTON_WAIT".equals(stage)||"HANDSHAKE_WAIT".equals(stage)))return false;long started=store.phaseStartedAt();return started>0L&&System.currentTimeMillis()-started>=CONTINUE_UI_TIMEOUT_MS;}

private void recordParentGuardStage(String rawStage){if(!canRun()||!SelfRunStore.PHASE_SEND_CONTINUE.equals(store.phase()))return;String stage=SelfRunParentGuardPolicy.safeStage(rawStage);int attempt=store.commandAttempt();if(attempt==lastParentGuardAttempt&&stage.equals(lastParentGuardStage))return;lastParentGuardAttempt=attempt;lastParentGuardStage=stage;store.setStatus(SelfRunParentGuardPolicy.waitMessage(stage));runLog.record(store,"WEBVIEW_PARENT_GUARD","stage="+stage+";attempt="+attempt);}

private void pauseParentGuardFailure(String rawCode){if(!canRun()||!SelfRunStore.PHASE_SEND_CONTINUE.equals(store.phase()))return;String safeCode=SelfRunParentGuardPolicy.safeFailureCode(rawCode),errorCode=SelfRunParentGuardPolicy.errorCode(safeCode),message=SelfRunParentGuardPolicy.message(safeCode);runLog.record(store,"WEBVIEW_PARENT_GUARD","stage=FAILED;code="+safeCode+";attempt="+store.commandAttempt());store.setLastError(errorCode,message);enterPreservedPause(errorCode,message+" · 사용자 조치 대기",false);NotificationHelper.notifyUser(this,"CONTINUE 제출 중단",store.status());}

private String driveBootstrap(){return commandPrompt(SelfRunStore.RETRY_BOOTSTRAP);}

private String continuationPrompt(){return commandPrompt(SelfRunStore.RETRY_CONTINUE);}

private String commandPrompt(String kind){if(!kind.equals(store.activeCommandKind())||store.activeCommandPrompt().isEmpty()){String prompt=SelfRunStore.RETRY_BOOTSTRAP.equals(kind)?SelfRunProtocol.bootstrapDrive(store.runId(),store.mode(),store.requirement(),store.turnDocumentId()):SelfRunProtocol.driveContinuation(store.runId());store.beginCommandAttempt(kind,prompt);}return store.activeCommandPrompt();}
private static String kindForPhase(String phase){return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)?SelfRunStore.RETRY_BOOTSTRAP:SelfRunStore.RETRY_CONTINUE;}
private void commandSubmitted(String kind,String detail){if(!canRun())return;int commandAttempt=store.commandAttempt();long due=System.currentTimeMillis()+SUBMISSION_RETRY_MS;store.markCommandSubmitted(kind,due);if(SelfRunStore.RETRY_CONTINUE.equals(kind)){lastParentGuardAttempt=commandAttempt;lastParentGuardStage="SUBMISSION_CONFIRMED";runLog.record(store,"WEBVIEW_PARENT_GUARD","stage=SUBMISSION_CONFIRMED;attempt="+commandAttempt);}runLog.record(store,"COMMAND_SUBMITTED_DRIVE_WAIT","kind="+kind+";attempt="+store.submissionRetryAttempt()+";retryDueAt="+due+";detail="+detail);releaseWakeLock();scheduleDrivePoll(0L);}
private void applyNextControl(String raw){SelfRunProtocol.Signal signal=SelfRunProtocol.parseLatest(raw,store.runId(),store.mode());if(signal.type==SelfRunProtocol.Type.NEXT){store.setRole(signal.role);if(SelfRunStore.MODE_WORK.equals(store.mode())){store.setPendingModel(signal.model);store.setPendingReasoning(signal.reasoning);}}String next=SelfRunStore.MODE_WORK.equals(store.mode())?SelfRunStore.PHASE_APPLY_PREFS:SelfRunStore.PHASE_SEND_CONTINUE;transition(next,raw.isEmpty()?"제어신호 미확인 · Drive 완료 기준으로 CONTINUE 강제 진행":"conversation 제어신호 확인 · continuation 준비",raw.isEmpty()?"control_best_effort_miss":"control_readback");scheduleWeb(100L);}

    private static boolean isSubmissionPhase(String phase) {
        return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)
                || SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);
    }

private void scheduleDrivePoll(){scheduleDrivePoll(NORMAL_POLL_MS);}

private void scheduleDrivePoll(long requestedDelay){handler.removeCallbacks(driveRunnable);releaseWakeLock();if(!canRun())return;long delay=Math.max(0L,requestedDelay);if(SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())&&store.awaitingCommandAck()&&store.hasSubmissionRetry()){long until=Math.max(0L,store.submissionRetryDueAt()-System.currentTimeMillis());delay=Math.min(delay,until);}handler.postDelayed(driveRunnable,delay);}

private void scheduleWeb(long delay){handler.removeCallbacks(webRunnable);if(webView!=null&&canRun()&&isWebAutomationPhase(store.phase()))handler.postDelayed(webRunnable,delay);}

private static boolean isWebAutomationPhase(String phase){return SelfRunStore.PHASE_BOOTSTRAP.equals(phase)||SelfRunStore.PHASE_BOOTSTRAP_MODEL.equals(phase)||SelfRunStore.PHASE_BOOTSTRAP_REASONING.equals(phase)||SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)||SelfRunStore.PHASE_READ_NEXT_CONTROL.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}

    private void handleDriveFailure(Throwable error, int epoch) {
        if (!canApplyDriveResult(epoch)) return;
        String code;
        String message;
        if (error instanceof DriveApiClient.OutcomeUnknownException) {
            code = "DRIVE_DOCUMENT_CREATE_RESULT_PENDING";
            message = "네이티브 Google Docs 생성 결과를 재확인합니다.";
        } else if (error instanceof DriveApiClient.ApiException api) {
            code = "DRIVE_HTTP_RETRY_" + api.status;
            message = "Drive API HTTP " + api.status + " 응답을 자동 재시도합니다.";
        } else if (retryableNetworkError(error)) {
            code = "DRIVE_NETWORK_RETRY";
            message = "Drive 네트워크 오류를 자동 재시도합니다.";
        } else if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            code = "DRIVE_STATE_RECHECK";
            message = "Drive 상태 검증을 다시 수행합니다.";
        } else {
            code = "DRIVE_OPERATION_RETRY";
            message = "Drive 요청을 자동 재시도합니다.";
        }
        scheduleDriveRecovery(code, message, epoch);
    }

    private void scheduleDriveRecovery(String code, String message, int expectedEpoch) {
        if (!canApplyDriveResult(expectedEpoch)) return;
        applyDriveResult(expectedEpoch, () -> {
            long delay = nextDriveRetryDelay();
            store.setLastError(code, message);
            store.setStatus(message + " · 자동 재시도 대기");
            runLog.record(store, "DRIVE_BACKOFF", "kind=" + code + ";attempt=" + retryAttempt + ";delayMs=" + delay);
            handler.removeCallbacks(driveRetryRunnable);
            handler.postDelayed(driveRetryRunnable, delay);
        });
    }

    private void scheduleAuthorizationRetry(String code, String message, int expectedEpoch,
                                            String expectedRunId, String expectedPhase) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> scheduleAuthorizationRetry(code, message, expectedEpoch, expectedRunId, expectedPhase));
            return;
        }
        synchronized (automationStateLock) {
            synchronized (SelfRunStore.RUN_STATE_LOCK) {
                if (expectedEpoch != automationEpoch || !expectedRunId.equals(store.runId())
                        || !expectedPhase.equals(store.phase()) || !canRun() || !drivePhase(store.phase())) return;
                long delay = nextDriveRetryDelay();
                store.setLastError(code, message);
                store.setStatus(message + " · 자동 재시도 대기");
                runLog.record(store, "DRIVE_AUTH_RETRY", "kind=" + code + ";attempt=" + retryAttempt + ";delayMs=" + delay);
                handler.removeCallbacks(driveRetryRunnable);
                handler.postDelayed(driveRetryRunnable, delay);
            }
        }
    }

    private long nextDriveRetryDelay() {
        retryAttempt = retryAttempt == Integer.MAX_VALUE ? Integer.MAX_VALUE : retryAttempt + 1;
        int index = Math.min(Math.max(0, retryAttempt - 1), BACKOFF.length - 1);
        long base = BACKOFF[index];
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 4L));
        return base + jitter;
    }

    private void retryDrive() {
        if (canRun() && drivePhase(store.phase())) authorizeAndRunDrive();
    }

    private static boolean retryableNetworkError(Throwable error) {
        return error instanceof IOException;
    }

private static void verifyMetadata(DriveApiClient.Metadata m,String job,String mime,String parent,String kind){if(m.trashed||m.shared||!job.equals(m.name)||!mime.equals(m.mimeType)||!parent.equals(m.parentId)||!m.isAppAuthorized||(DriveApiClient.MIME_FOLDER.equals(mime)&&!m.canAddChildren)||!job.equals(m.appProperties.optString("job_id"))||!kind.equals(m.appProperties.optString("selfrun_kind")))throw new IllegalStateException("Drive metadata readback mismatch: "+kind);}

    private static String documentUrl(DriveApiClient.Metadata metadata) {
        return new Uri.Builder().scheme("https").authority("docs.google.com")
                .appendPath("document").appendPath("d").appendPath(metadata.id).appendPath("edit")
                .build().toString();
    }

private void transition(String next, String status, String reason) {
    String prior = store.phase(); store.setPhase(next); store.setStatus(status);
    runLog.record(store, "STATE_TRANSITION", "from=" + prior + ";to=" + next + ";reason=" + reason);
}

    private void pauseError(String code, String message) {
        int epoch = automationEpoch;
        pauseError(code, message, epoch, store.runId(), store.phase());
    }

    private void pauseError(String code, String message, int expectedEpoch) {
        pauseError(code, message, expectedEpoch, driveOperationRunId, store.phase());
    }

    private void pauseError(String code, String message, int expectedEpoch,
                            String expectedRunId, String expectedPhase) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> pauseError(code, message, expectedEpoch, expectedRunId, expectedPhase));
            return;
        }
        synchronized (automationStateLock) {
            synchronized (SelfRunStore.RUN_STATE_LOCK) {
                if (expectedEpoch != automationEpoch || !expectedRunId.equals(store.runId())
                        || !expectedPhase.equals(store.phase()) || !canRun()) return;
                store.setLastError(code, message);
                enterPreservedPause(code, code + " · " + message, false);
                NotificationHelper.notifyUser(this, "확인 필요", store.status());
            }
        }
    }

private void pauseUnsupportedParentGuard() {
    if (!canRun() || !SelfRunStore.PHASE_SEND_CONTINUE.equals(store.phase())) return;
    String code = "WEBVIEW_PARENT_GUARD_UNAVAILABLE";
    String message = "Android System WebView 또는 Chrome 업데이트 필요 · 사용자 조치 대기";
    store.setLastError(code, message);
    enterPreservedPause(code, message, false);
    NotificationHelper.notifyUser(this, "확인 필요", store.status());
}

private void pauseFromUi() {
    if (!canRun()) return;
    startForegroundCompat();
    enterPreservedPause("UI_PAUSE", "사용자 일시정지", false);
    NotificationHelper.notifyUser(this, "일시정지", store.status());
}

private void resumeFromUi(){if(!store.paused()||store.userStopped()||store.runId().isEmpty())return;stopAutomationCallbacks();store.beginManualResumeOverride();store.clearLastError();lastParentGuardStage="";lastParentGuardAttempt=-1;resumeWebView();startForegroundCompat();resumeStateMachine();}

    private void enterPreservedPause(String cause, String status, boolean needsContinuation) {
        String prior;
        synchronized (automationStateLock) {
            synchronized (SelfRunStore.RUN_STATE_LOCK) {
                prior = store.phase();
                // Advance the epoch under the same lock before making PAUSED durable.
                automationEpoch++; generation++; authorizationInFlight = false; domInFlight = false;
                store.enterPause(prior, needsContinuation); store.setStatus(status);
            }
        }
        removeAutomationCallbacks(); releaseWakeLock(); pauseWebView();
        runLog.record(store, "PAUSED", cause + ";webview=preserved;drive_ids=preserved");
    }

    private void removeAutomationCallbacks() {
        handler.removeCallbacks(driveRunnable); handler.removeCallbacks(webRunnable);
        handler.removeCallbacks(guardRunnable); handler.removeCallbacks(driveRetryRunnable);
    }

    private void stopAutomationCallbacks() {
        removeAutomationCallbacks();
        synchronized (automationStateLock) {
            automationEpoch++; generation++; authorizationInFlight = false; domInFlight = false;
        }
    }

    private void pauseWebView() { if (webView != null) try { webView.onPause(); } catch (Throwable ignored) {} }
    private void resumeWebView() { if (webView != null) try { webView.onResume(); } catch (Throwable ignored) {} }

    private void restoreCanonical() { String target=canonicalUrl(); if (canRun() && webView != null && validAutomationTarget(target)) webView.loadUrl(target); }
    private String canonicalUrl() { return store.conversationUrl().isEmpty() ? store.projectUrl() : store.conversationUrl(); }
    private boolean routeAcceptable(String actual) { return store.conversationUrl().isEmpty() ? sameProject(store.projectUrl(), actual) : sameConversation(store.conversationUrl(), actual); }
    private static boolean sameProject(String a, String b) { return SelfRunScript.isGeneralChatUrl(a) ? SelfRunScript.isGeneralChatUrl(b) : ProjectUrlPolicy.sameProject(a,b); }
    private static boolean sameConversation(String a, String b) { return ProjectUrlPolicy.sameConversation(a,b); }
    private static boolean validAutomationTarget(String value) { return SelfRunScript.isGeneralChatUrl(value) || ProjectUrlPolicy.parseProject(value)!=null; }

    private JSONObject parse(String raw) {
        try { Object outer = new JSONTokener(raw == null ? "" : raw).nextValue(); return new JSONObject(outer instanceof String ? (String) outer : String.valueOf(outer)); }
        catch (Throwable error) { return new JSONObject(); }
    }

    private void acquireWakeLock() { if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(2 * 60_000L); }
    private void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }

    private void cleanupWebView() {
        handler.removeCallbacks(webRunnable); generation++; domInFlight = false; continuationParentGuardAvailable = false;lastParentGuardStage="";lastParentGuardAttempt=-1;
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

private static final class DriveStateSnapshot{final String phase,runId,baseFolderId,jobFolderId,turnDocumentId,creationStage,mode,lastSeenVersion,lastSeenModifiedTime;final int driveSignalCursor;DriveStateSnapshot(String phase,String runId,String baseFolderId,String jobFolderId,String turnDocumentId,String creationStage,int cursor,String mode,String lastSeenVersion,String lastSeenModifiedTime){this.phase=phase;this.runId=runId;this.baseFolderId=baseFolderId;this.jobFolderId=jobFolderId;this.turnDocumentId=turnDocumentId;this.creationStage=creationStage;this.driveSignalCursor=cursor;this.mode=mode;this.lastSeenVersion=lastSeenVersion;this.lastSeenModifiedTime=lastSeenModifiedTime;}}
}
