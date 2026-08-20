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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/** Drive V1 runtime. Drive signals are the sole execution-control source after command submission. */
public final class SelfRunService extends Service {
    static final String ACTION_RUN = BuildConfig.APPLICATION_ID + ".RUN";
    static final String ACTION_PAUSE = BuildConfig.APPLICATION_ID + ".PAUSE";
    static final String ACTION_RESUME = BuildConfig.APPLICATION_ID + ".RESUME";
    private static final int NOTIFICATION_ID = 17021;
    private static final long NORMAL_POLL_MS = 60_000L;
    static final long CONTINUATION_GUARD_MS = 0L;
    static final long BOOTSTRAP_COMMAND_ACK_RETRY_MS = 5 * 60_000L;
    private static final long WEB_RECOVERY_DELAY_MS = 5 * 60_000L;
    static final long CONTINUATION_VERIFY_INTERVAL_MS = 250L;
    static final long CONTINUATION_FAILURE_MS = 2_500L;
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
    private SelfRunRunLog runLog;
    private DriveApiClient drive;
    private HeadlessWebViewHost host;
    private WebView webView;
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
    private String continuationAttemptPrompt = "";
    private String continuationAttemptMarkerId = "";
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

private void resumeStateMachine(){if(!canRun())return;String phase=store.phase();if(drivePhase(phase))authorizeAndRunDrive();else ensureWebView();}

private static boolean drivePhase(String phase){return SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK.equals(phase)||SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK.equals(phase)||SelfRunStore.PHASE_JOB_ID_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD.equals(phase)||SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE.equals(phase)||SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT.equals(phase)||SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK.equals(phase)||SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(phase)||SelfRunStore.PHASE_RESUME_BASELINE.equals(phase);}

static boolean shouldContinueSamePhaseDriveStep(String phase, boolean hasUncommittedAttachment) {
    return SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD.equals(phase) && hasUncommittedAttachment;
}

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
                    if (prior.equals(store.phase())) {
                        if (shouldContinueSamePhaseDriveStep(prior, store.nextUncommittedAttachment() != null)) continue;
                        break;
                    }
                    if (SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())
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

private DriveStateSnapshot driveState(int expectedEpoch){synchronized(automationStateLock){synchronized(SelfRunStore.RUN_STATE_LOCK){if(destroyed||expectedEpoch!=automationEpoch||!canRun()||!driveOperationRunId.equals(store.runId())||!drivePhase(store.phase()))return null;return new DriveStateSnapshot(store.phase(),store.runId(),store.runBaseFolderId(),store.jobFolderId(),store.turnDocumentId(),store.creationStage(),store.driveSignalCursor(),store.mode(),store.lastSeenDriveVersion(),store.lastSeenModifiedTime());}}}

private void runDriveStep(int epoch)throws Exception{if(!canApplyDriveResult(epoch))return;switch(store.phase()){case SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK->applyDriveResult(epoch,()->transition(SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK,"Drive 기준 폴더 확인 중","account_authorized"));case SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK->checkBaseFolder(epoch);case SelfRunStore.PHASE_JOB_ID_CREATE->applyDriveResult(epoch,()->transition(SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE,"Drive Job 폴더 생성 중","job_id_persisted"));case SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE->createOrRecoverJobFolder(epoch);case SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD->uploadNextAttachment(epoch);case SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE->createOrRecoverDocument(epoch);case SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT->initializeDocument(epoch);case SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK->verifyInitialDocument(epoch);case SelfRunStore.PHASE_WAIT_DRIVE_COMMIT,SelfRunStore.PHASE_RESUME_BASELINE->pollDriveNow(epoch);default->scheduleDriveRecovery("DRIVE_STATE_RECHECK","알 수 없는 Drive 단계를 자동 재확인합니다: "+store.phase(),epoch);}}

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
        // Recover URI-grant cleanup if the process died after COMMITTED was persisted.
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
        // Provider metadata is display-only; count only up to the explicit per-file cap.
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

private void initializeDocument(int epoch)throws Exception{DriveStateSnapshot snapshot=driveState(epoch);if(snapshot==null)return;drive.readDocumentText(accessToken,snapshot.turnDocumentId);if(!canApplyDriveResult(epoch))return;applyDriveResult(epoch,()->transition(SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK,"실행턴 signal log readback 검증 중","signal_log_ready"));}

private void verifyInitialDocument(int epoch)throws Exception{DriveStateSnapshot snapshot=driveState(epoch);if(snapshot==null)return;DriveApiClient.Metadata metadata=drive.getMetadata(accessToken,snapshot.turnDocumentId);verifyMetadata(metadata,snapshot.runId,DriveApiClient.MIME_DOCUMENT,snapshot.jobFolderId,"turn_document");String body=drive.readDocumentText(accessToken,snapshot.turnDocumentId);DriveSignalParser.Scan scan=DriveSignalParser.scan(body,snapshot.runId,0,snapshot.mode);applyDriveResult(epoch,()->{store.baselineDriveSignals(scan.totalCount,scan.latest);store.updateDriveSeen(metadata.version,metadata.modifiedTime);transition(SelfRunStore.PHASE_BOOTSTRAP,"Drive 준비 완료 · ChatGPT 새 대화 준비","signal_log_readback_verified");handler.post(()->{if(epoch==automationEpoch&&canRun())ensureWebView();});});}

private void pollDrive(){if((SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())||SelfRunStore.PHASE_RESUME_BASELINE.equals(store.phase()))&&canRun())authorizeAndRunDrive();}

private void pollDriveNow(int epoch)throws Exception{DriveStateSnapshot snapshot=driveState(epoch);if(snapshot==null)return;DriveApiClient.Metadata metadata=drive.getPollMetadata(accessToken,snapshot.turnDocumentId);if(!canApplyDriveResult(epoch))return;if(metadata.trashed||metadata.shared||!DriveApiClient.MIME_DOCUMENT.equals(metadata.mimeType)||!snapshot.jobFolderId.equals(metadata.parentId)){scheduleDriveRecovery("DRIVE_DOCUMENT_RECHECK","실행턴 문서 상태가 기대값과 달라 동일 문서를 다시 확인합니다.",epoch);return;}boolean changed=!metadata.version.equals(snapshot.lastSeenVersion)||!metadata.modifiedTime.equals(snapshot.lastSeenModifiedTime);boolean resume=SelfRunStore.PHASE_RESUME_BASELINE.equals(snapshot.phase),retry=SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(snapshot.phase)&&store.awaitingCommandAck()&&store.submissionRetryDue();if(!changed&&!resume&&!retry){applyDriveResult(epoch,this::scheduleDrivePoll);return;}String text=drive.readDocumentText(accessToken,snapshot.turnDocumentId);if(!canApplyDriveResult(epoch))return;DriveSignalParser.Scan scan=DriveSignalParser.scan(text,snapshot.runId,snapshot.driveSignalCursor,snapshot.mode);DriveSignalParser.Event latestCompletion=DriveSignalParser.latestCompletion(scan.unseen),latestBlocking=DriveSignalParser.latestBlocking(scan.unseen);if(latestCompletion!=null&&!latestCompletion.protocolError.isEmpty()&&(latestBlocking==null||latestCompletion.cursor>latestBlocking.cursor)){pauseError("DRIVE_NEXT_INPUT_PROTOCOL_ERROR",latestCompletion.protocolError,epoch,snapshot.runId,snapshot.phase);return;}if(resume){if(applyDriveResult(epoch,()->{store.baselineManualResume(scan.totalCount,scan.latest,latestCompletion);store.updateDriveSeen(metadata.version,metadata.modifiedTime);}))handler.post(this::ensureWebView);return;}if(!applyDriveResult(epoch,()->{if(scan.cursorRebased)store.baselineDriveSignals(scan.totalCount,scan.latest);else if(!scan.unseen.isEmpty())store.applyDriveSignals(scan.unseen,System.currentTimeMillis(),CONTINUATION_GUARD_MS);store.updateDriveSeen(metadata.version,metadata.modifiedTime);} ))return;handler.post(()->{if(SelfRunStore.PHASE_DRIVE_COMMIT_GUARD.equals(store.phase()))ensureWebView();else if(SelfRunStore.PHASE_PAUSED.equals(store.phase())||SelfRunStore.PHASE_DONE.equals(store.phase())){if(store.terminalSideEffectPending())replayTerminalSideEffect();}else if(SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())){if(store.awaitingCommandAck()&&store.submissionRetryDue()){store.prepareCommandRetry();ensureWebView();}else scheduleDrivePoll();}});}

private void replayTerminalSideEffect(){startForegroundCompat();String owner=store.terminalSideEffectRunId(),raw=store.terminalSideEffectCommitId(),type=store.terminalSideEffectType();handler.post(()->{synchronized(automationStateLock){synchronized(SelfRunStore.RUN_STATE_LOCK){if(!store.terminalSideEffectOwnedBy(owner,raw,type))return;switch(type){case "DONE"->finishDoneSideEffect(owner,raw,type);case "PAUSED"->finishPersistedTerminalPause("DRIVE_PAUSED","일시정지",owner,raw,type);case "USER_ACTION_REQUIRED"->finishPersistedTerminalPause("DRIVE_USER_ACTION_REQUIRED","확인 필요",owner,raw,type);default->{return;}}}}});}

    private void finishDoneSideEffect(String ownerRunId, String commitId, String type) {
        if (!store.terminalSideEffectOwnedBy(ownerRunId, commitId, type)) return;
        runLog.record(store, "TERMINAL", "done_signal"); NotificationHelper.notifyUser(this, "완료", ownerRunId);
        if (!store.acknowledgeTerminalSideEffect(ownerRunId, commitId, type)) return; stopRuntime();
    }

private void finishPersistedTerminalPause(String cause, String alertTitle, String ownerRunId,String commitId, String type) {if (!store.terminalSideEffectOwnedBy(ownerRunId, commitId, type)) return;stopAutomationCallbacks();releaseWakeLock();pauseWebView();runLog.record(store, "PAUSED", cause + ";webview=preserved;drive_ids=preserved");NotificationHelper.notifyUser(this, alertTitle, store.status());store.acknowledgeTerminalSideEffect(ownerRunId, commitId, type);}


private void ensureWebView(){if(!canRun()||!isWebAutomationPhase(store.phase()))return;String target=store.conversationUrl().isEmpty()?store.projectUrl():store.conversationUrl();if(target.isEmpty()||!validAutomationTarget(target)){store.setLastError("TARGET_MISSING_RETRY","ChatGPT 대상 URL을 안전하게 재확인합니다.");handler.postDelayed(this::ensureWebView,WEB_RECOVERY_DELAY_MS);return;}acquireWakeLock();if(webView!=null){maybeCaptureConversationUrl(webView.getUrl());scheduleWeb(250L);return;}launchWebView(target);}

    private void launchWebView(String target) {
        cleanupWebView();
        try {
            String launchedRunId = store.runId();
            host = HeadlessWebViewHost.create(this); webView = host.webView(); WebViewConfig.applyAutomation(webView);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {if (!launchedRunId.equals(store.runId())) return;generation++; domInFlight = false; maybeCaptureConversationUrl(url);}
                @Override public void onPageFinished(WebView view, String url) {if (!launchedRunId.equals(store.runId())) return;maybeCaptureConversationUrl(url);if (isWebAutomationPhase(store.phase())) scheduleWeb(800L);}
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {if (!launchedRunId.equals(store.runId())) return true;if (!request.isForMainFrame()) return false;String requested = String.valueOf(request.getUrl());boolean allowed = store.conversationUrl().isEmpty()? sameProject(store.projectUrl(), requested) : sameConversation(store.conversationUrl(), requested);if (!allowed) { recordContinuationRouteMismatch(requested); postWebCallback(SelfRunService.this::restoreCanonical, 800L); }return !allowed;}
                @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse s) {if (launchedRunId.equals(store.runId()) && r.isForMainFrame() && s.getStatusCode() == 429) scheduleWeb(30_000L);}
                @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {if (launchedRunId.equals(store.runId()) && r.isForMainFrame() && canRun() && isWebAutomationPhase(store.phase())) postWebCallback(() -> { if (v == webView) v.loadUrl(canonicalUrl()); }, 3_000L);}
                @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {h.cancel();if (launchedRunId.equals(store.runId()) && canRun() && isWebAutomationPhase(store.phase())) {runLog.record(store, "WEBVIEW_SSL_RETRY", "cancelled;retry_in=300000");postWebCallback(SelfRunService.this::restoreCanonical, WEB_RECOVERY_DELAY_MS);}}
                @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {cleanupWebView();if (launchedRunId.equals(store.runId()) && !store.paused() && isWebAutomationPhase(store.phase())) postWebCallback(SelfRunService.this::ensureWebView, 2_000L);return true;}
            });
            webView.loadUrl(target);
        } catch (Throwable error) {cleanupWebView(); postWebCallback(this::ensureWebView, 2_500L);}
    }

private void maybeCaptureConversationUrl(String url){if(store.conversationUrl().isEmpty()&&sameProject(store.projectUrl(),url)&&!SelfRunScript.conversationId(url).isEmpty()){store.captureConversationUrl(url);if(sameConversation(store.conversationUrl(),url))runLog.record(store,"CONVERSATION_CAPTURED",SelfRunScript.isGeneralChatUrl(url)?"trusted_general_route":"trusted_project_route");}}

    private void postWebCallback(Runnable callback, long delay) {int epoch = automationEpoch;String runId = store.runId();handler.postDelayed(() -> {if (epoch == automationEpoch && runId.equals(store.runId()) && canRun() && isWebAutomationPhase(store.phase())) callback.run();}, delay);}

private void runWebStep(){if(!canRun()||!isWebAutomationPhase(store.phase())||webView==null||domInFlight)return;maybeCaptureConversationUrl(webView.getUrl());String phase=store.phase();if((SelfRunStore.PHASE_DRIVE_COMMIT_GUARD.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase))&&store.conversationUrl().isEmpty()){scheduleWeb(2000L);return;}if(!routeAcceptable(webView.getUrl())){recordContinuationRouteMismatch(webView.getUrl());scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);return;}String script;switch(phase){case SelfRunStore.PHASE_BOOTSTRAP->script=SelfRunDom.prepareInitialContext(store.projectUrl(),store.mode(),store.runId());case SelfRunStore.PHASE_BOOTSTRAP_MODEL->script=WorkPreferenceDom.modelForProject(store.projectUrl(),"sol");case SelfRunStore.PHASE_BOOTSTRAP_REASONING->script=WorkPreferenceDom.reasoningForProject(store.projectUrl(),"xhigh");case SelfRunStore.PHASE_BOOTSTRAP_SEND->{String prompt=commandPrompt(SelfRunStore.RETRY_BOOTSTRAP);script=SelfRunDom.sendDriveInitial(store.projectUrl(),prompt,store.commandMarkerId());}case SelfRunStore.PHASE_DRIVE_COMMIT_GUARD->script=SelfRunContinuationDom.buttonState(store.conversationUrl());case SelfRunStore.PHASE_APPLY_PREFS->script=WorkPreferenceDom.modelForConversation(store.conversationUrl(),store.pendingModel());case SelfRunStore.PHASE_APPLY_REASONING->script=WorkPreferenceDom.reasoningForConversation(store.conversationUrl(),store.pendingReasoning());case SelfRunStore.PHASE_SEND_CONTINUE->{String prompt=continuationPrompt();script=SelfRunContinuationDom.prepareDriveTurn(store.conversationUrl(),prompt,continuationMarkerId());}default->{store.setLastError("WEB_STATE_RETRY","Drive V1 WebView 단계를 자동 재확인합니다: "+phase);scheduleWeb(2000L);return;}}evaluate(phase,script);}

private void evaluate(String phase,String script){WebView active=webView;int epoch=generation;String runId=store.runId();domInFlight=true;active.evaluateJavascript(script,raw->{if(active!=webView||epoch!=generation||!runId.equals(store.runId()))return;domInFlight=false;if(!canRun())return;JSONObject result=parse(raw);String status=result.optString("status","SCRIPT_ERROR"),detail=result.optString("detail","");if("TARGET_ERROR".equals(status)){recordContinuationTargetError(phase);if(!isContinuationDiagnosticPhase(phase))restoreCanonical();else scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);return;}if("AUTH_REQUIRED".equals(status)){enterPreservedPause("CHATGPT_AUTH_REQUIRED","ChatGPT 로그인 필요 · 사용자 조치 대기",false);NotificationHelper.notifyUser(this,"확인 필요",store.status());return;}if(SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)){if("SUBMISSION_CONFIRMED".equals(status)){continuationSubmitted(detail);return;}if("VERIFY_REQUIRED".equals(status)){String prompt=continuationPrompt();evaluate(phase,SelfRunContinuationDom.verifyDriveTurnSubmission(store.conversationUrl(),prompt,continuationMarkerId(),CONTINUATION_FAILURE_MS));return;}if("CONTINUE_CLICKED".equals(status)||"SUBMISSION_PENDING".equals(status)||"SUBMISSION_FAILED".equals(status)||"COMPOSER_CLEARING".equals(status)||"COMPOSER_INPUTTING".equals(status)||SelfRunContinuationDom.STOP.equals(status)||SelfRunContinuationDom.SEND_DISABLED.equals(status)||SelfRunContinuationDom.UNKNOWN.equals(status)||"SCRIPT_ERROR".equals(status)){recordContinuationWait(phase,status,detail);scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);return;}}if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)&&("MARKER_FAILED".equals(status)||"SCRIPT_ERROR".equals(status)||"BOOTSTRAP_SUBMISSION_AMBIGUOUS".equals(status)||"BOOTSTRAP_SUBMISSION_PENDING".equals(status))){commandSubmitted(SelfRunStore.RETRY_BOOTSTRAP,status);return;}if("BOOTSTRAP_SUBMITTED".equals(status)){commandSubmitted(SelfRunStore.RETRY_BOOTSTRAP,status);return;}if("UI_WAIT".equals(status)||"WAIT".equals(status)){recordContinuationWait(phase,status,detail);scheduleWeb("WAIT".equals(status)?2000L:1200L);return;}handleWebResult(phase,status,result);});}

private void recordContinuationWait(String phase,String status,String detail){if(!isContinuationDiagnosticPhase(phase))return;runLog.record(store,"DOM_RESULT",SelfRunWebDiagnostics.waitDetail(phase,status,detail));}
private void recordContinuationRouteMismatch(String actual){if(!isContinuationDiagnosticPhase(store.phase()))return;runLog.record(store,"DOM_RESULT",SelfRunWebDiagnostics.routeMismatchDetail(canonicalUrl(),actual));}
private void recordContinuationTargetError(String phase){if(!isContinuationDiagnosticPhase(phase))return;runLog.record(store,"DOM_RESULT","status=TARGET_ERROR;reason=target_guard");}
private static boolean isContinuationDiagnosticPhase(String phase){return SelfRunStore.PHASE_DRIVE_COMMIT_GUARD.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}

private void handleWebResult(String phase,String status,JSONObject result){if(SelfRunStore.PHASE_BOOTSTRAP.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.MODE_WORK.equals(store.mode())?SelfRunStore.PHASE_BOOTSTRAP_MODEL:SelfRunStore.PHASE_BOOTSTRAP_SEND,"ChatGPT bootstrap 설정 준비","context_ready");scheduleWeb(250L);return;}if(SelfRunStore.PHASE_BOOTSTRAP_MODEL.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.PHASE_BOOTSTRAP_REASONING,"첫 턴 Work 추론 적용","model_ready");scheduleWeb(250L);return;}if(SelfRunStore.PHASE_BOOTSTRAP_REASONING.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.PHASE_BOOTSTRAP_SEND,"첫 프롬프트 전송 준비","reasoning_ready");scheduleWeb(250L);return;}if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)&&"READY_TO_SUBMIT".equals(status)){String prompt=commandPrompt(SelfRunStore.RETRY_BOOTSTRAP);evaluate(phase,SelfRunDom.clickPreparedDriveInitial(store.projectUrl(),prompt,store.commandMarkerId()));return;}if(SelfRunStore.PHASE_DRIVE_COMMIT_GUARD.equals(phase)){if(SelfRunContinuationDom.SEND_ENABLED.equals(status)){String next=SelfRunStore.MODE_WORK.equals(store.mode())?SelfRunStore.PHASE_APPLY_PREFS:SelfRunStore.PHASE_SEND_CONTINUE;transition(next,"Drive TURN_COMPLETED 확인 · internal WebView SEND 준비","send_ready");scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);return;}scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);return;}if(SelfRunStore.PHASE_APPLY_PREFS.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.PHASE_APPLY_REASONING,"다음 턴 추론 적용","model_ready");scheduleWeb(250L);return;}if(SelfRunStore.PHASE_APPLY_REASONING.equals(phase)&&"READY".equals(status)){transition(SelfRunStore.PHASE_SEND_CONTINUE,"Work MODEL/REASONING 적용 완료 · SEND 재확인 준비","reasoning_ready_for_send_recheck");scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);return;}if(SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)&&"READY_TO_SUBMIT".equals(status)){String prompt=continuationPrompt();evaluate(phase,SelfRunContinuationDom.clickPreparedDriveTurn(store.conversationUrl(),prompt,continuationMarkerId()));return;}scheduleWeb(750L);}

private String driveBootstrap(){return commandPrompt(SelfRunStore.RETRY_BOOTSTRAP);}
private String continuationPrompt(){if(continuationAttemptPrompt.isEmpty())continuationAttemptPrompt=SelfRunProtocol.driveContinuation(store.runId(),store.pendingNextInput());return continuationAttemptPrompt;}
private String continuationMarkerId(){if(continuationAttemptMarkerId.isEmpty())continuationAttemptMarkerId=store.runId()+":continue:"+store.driveSignalCursor()+":"+store.phaseStartedAt();return continuationAttemptMarkerId;}
private void clearContinuationAttempt(){continuationAttemptPrompt="";continuationAttemptMarkerId="";}
private void continuationSubmitted(String detail){if(!canRun())return;runLog.record(store,"CONTINUATION_SUBMISSION_CONFIRMED","detail="+detail+";command_received_ack=unused");clearContinuationAttempt();transition(SelfRunStore.PHASE_WAIT_DRIVE_COMMIT,"Drive 턴 결과 신호 대기","continuation_submission_confirmed");releaseWakeLock();scheduleDrivePoll(0L);}

private String commandPrompt(String kind){if(!kind.equals(store.activeCommandKind())||store.activeCommandPrompt().isEmpty()){String prompt=SelfRunStore.RETRY_BOOTSTRAP.equals(kind)?SelfRunProtocol.bootstrapDrive(store.runId(),store.mode(),store.requirement(),store.turnDocumentId(),store.jobFolderId(),store.hasAttachments()):SelfRunProtocol.driveContinuation(store.runId(),store.pendingNextInput());store.beginCommandAttempt(kind,prompt);}return store.activeCommandPrompt();}
private static String kindForPhase(String phase){return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)?SelfRunStore.RETRY_BOOTSTRAP:SelfRunStore.RETRY_CONTINUE;}
private void commandSubmitted(String kind,String detail){if(!canRun()||!SelfRunStore.RETRY_BOOTSTRAP.equals(kind))return;long due=System.currentTimeMillis()+BOOTSTRAP_COMMAND_ACK_RETRY_MS;store.markCommandSubmitted(kind,due);runLog.record(store,"COMMAND_SUBMITTED_DRIVE_WAIT","kind="+kind+";attempt="+store.submissionRetryAttempt()+";retryDueAt="+due+";detail="+detail);releaseWakeLock();scheduleDrivePoll(0L);}

    private static boolean isSubmissionPhase(String phase) {return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase) || SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}
private void scheduleDrivePoll(){scheduleDrivePoll(NORMAL_POLL_MS);}
private void scheduleDrivePoll(long requestedDelay){handler.removeCallbacks(driveRunnable);releaseWakeLock();if(!canRun())return;long delay=Math.max(0L,requestedDelay);if(SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())&&store.awaitingCommandAck()&&store.retryForBootstrap()&&store.hasSubmissionRetry()){long until=Math.max(0L,store.submissionRetryDueAt()-System.currentTimeMillis());delay=Math.min(delay,until);}handler.postDelayed(driveRunnable,delay);}
private void scheduleWeb(long delay){handler.removeCallbacks(webRunnable);if(webView!=null&&canRun()&&isWebAutomationPhase(store.phase()))handler.postDelayed(webRunnable,delay);}
private static boolean isWebAutomationPhase(String phase){return SelfRunStore.PHASE_BOOTSTRAP.equals(phase)||SelfRunStore.PHASE_BOOTSTRAP_MODEL.equals(phase)||SelfRunStore.PHASE_BOOTSTRAP_REASONING.equals(phase)||SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)||SelfRunStore.PHASE_DRIVE_COMMIT_GUARD.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}

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

private void transition(String next, String status, String reason) {String prior = store.phase(); store.setPhase(next); store.setStatus(status);runLog.record(store, "STATE_TRANSITION", "from=" + prior + ";to=" + next + ";reason=" + reason);}

    private void pauseError(String code, String message) {int epoch = automationEpoch;pauseError(code, message, epoch, store.runId(), store.phase());}
    private void pauseError(String code, String message, int expectedEpoch) {pauseError(code, message, expectedEpoch, driveOperationRunId, store.phase());}
    private void pauseError(String code, String message, int expectedEpoch,String expectedRunId, String expectedPhase) {
        if (Looper.myLooper() != Looper.getMainLooper()) {handler.post(() -> pauseError(code, message, expectedEpoch, expectedRunId, expectedPhase));return;}
        synchronized (automationStateLock) {synchronized (SelfRunStore.RUN_STATE_LOCK) {if (expectedEpoch != automationEpoch || !expectedRunId.equals(store.runId()) || !expectedPhase.equals(store.phase()) || !canRun()) return;store.setLastError(code, message);enterPreservedPause(code, code + " · " + message, false);NotificationHelper.notifyUser(this, "확인 필요", store.status());}}
    }

private void pauseFromUi() {if (!canRun()) return;startForegroundCompat();enterPreservedPause("UI_PAUSE", "사용자 일시정지", false);NotificationHelper.notifyUser(this, "일시정지", store.status());}
private void resumeFromUi(){if(!store.paused()||store.userStopped()||store.runId().isEmpty())return;stopAutomationCallbacks();store.beginManualResumeOverride();store.clearLastError();resumeWebView();startForegroundCompat();resumeStateMachine();}

    private void enterPreservedPause(String cause, String status, boolean needsContinuation) {
        String prior;
        synchronized (automationStateLock) {synchronized (SelfRunStore.RUN_STATE_LOCK) {prior = store.phase();automationEpoch++; generation++; authorizationInFlight = false; domInFlight = false;store.enterPause(prior, needsContinuation); store.setStatus(status);}}
        removeAutomationCallbacks(); releaseWakeLock(); pauseWebView();runLog.record(store, "PAUSED", cause + ";webview=preserved;drive_ids=preserved");
    }

    private void removeAutomationCallbacks() {handler.removeCallbacks(driveRunnable); handler.removeCallbacks(webRunnable);handler.removeCallbacks(driveRetryRunnable);}
    private void stopAutomationCallbacks() {removeAutomationCallbacks();clearContinuationAttempt();synchronized (automationStateLock) {automationEpoch++; generation++; authorizationInFlight = false; domInFlight = false;}}
    private void pauseWebView() { if (webView != null) try { webView.onPause(); } catch (Throwable ignored) {} }
    private void resumeWebView() { if (webView != null) try { webView.onResume(); } catch (Throwable ignored) {} }
    private void restoreCanonical() { String target=canonicalUrl(); if (canRun() && webView != null && validAutomationTarget(target)) webView.loadUrl(target); }
    private String canonicalUrl() { return store.conversationUrl().isEmpty() ? store.projectUrl() : store.conversationUrl(); }
    private boolean routeAcceptable(String actual) { return store.conversationUrl().isEmpty() ? sameProject(store.projectUrl(), actual) : sameConversation(store.conversationUrl(), actual); }
    private static boolean sameProject(String a, String b) { return SelfRunScript.isGeneralChatUrl(a) ? SelfRunScript.isGeneralChatUrl(b) : ProjectUrlPolicy.sameProject(a,b); }
    private static boolean sameConversation(String a, String b) { return ProjectUrlPolicy.sameConversation(a,b); }
    private static boolean validAutomationTarget(String value) { return SelfRunScript.isGeneralChatUrl(value) || ProjectUrlPolicy.parseProject(value)!=null; }

    private JSONObject parse(String raw) {try { Object outer = new JSONTokener(raw == null ? "" : raw).nextValue(); return new JSONObject(outer instanceof String ? (String) outer : String.valueOf(outer)); }catch (Throwable error) { return new JSONObject(); }}
    private void acquireWakeLock() { if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(2 * 60_000L); }
    private void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
    private void cleanupWebView() {handler.removeCallbacks(webRunnable); generation++; domInFlight = false;if (host != null) { host.destroy(); host = null; } webView = null;}
    private void stopRuntime() {stopAutomationCallbacks(); cleanupWebView(); releaseWakeLock();stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();}

    @Override public void onDestroy() {destroyed = true;stopAutomationCallbacks(); cleanupWebView(); releaseWakeLock(); io.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent) { return null; }

private static final class DriveStateSnapshot{final String phase,runId,baseFolderId,jobFolderId,turnDocumentId,creationStage,mode,lastSeenVersion,lastSeenModifiedTime;final int driveSignalCursor;DriveStateSnapshot(String phase,String runId,String baseFolderId,String jobFolderId,String turnDocumentId,String creationStage,int cursor,String mode,String lastSeenVersion,String lastSeenModifiedTime){this.phase=phase;this.runId=runId;this.baseFolderId=baseFolderId;this.jobFolderId=jobFolderId;this.turnDocumentId=turnDocumentId;this.creationStage=creationStage;this.driveSignalCursor=cursor;this.mode=mode;this.lastSeenVersion=lastSeenVersion;this.lastSeenModifiedTime=lastSeenModifiedTime;}}
}
