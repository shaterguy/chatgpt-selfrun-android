package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.UriPermission;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SelfRunStore {
    /** Shared by Activity run replacement and Service result application. */
    static final Object RUN_STATE_LOCK = new Object();
    static final String MODE_CHAT = "CHAT";
    static final String MODE_WORK = "WORK";

    static final String PHASE_IDLE = "IDLE";
    static final String PHASE_DRIVE_ACCOUNT_CHECK = "DRIVE_ACCOUNT_CHECK";
    static final String PHASE_DRIVE_BASE_FOLDER_CHECK = "DRIVE_BASE_FOLDER_CHECK";
    static final String PHASE_JOB_ID_CREATE = "JOB_ID_CREATE";
    static final String PHASE_DRIVE_JOB_FOLDER_CREATE = "DRIVE_JOB_FOLDER_CREATE";
    static final String PHASE_DRIVE_ATTACHMENT_UPLOAD = "DRIVE_ATTACHMENT_UPLOAD";
    static final String PHASE_DRIVE_TURN_DOCUMENT_CREATE = "DRIVE_TURN_DOCUMENT_CREATE";
    static final String PHASE_DRIVE_DOCUMENT_INIT = "DRIVE_DOCUMENT_INIT";
    static final String PHASE_DRIVE_DOCUMENT_READBACK = "DRIVE_DOCUMENT_READBACK";
    static final String PHASE_BOOTSTRAP = "BOOTSTRAP";
    static final String PHASE_BOOTSTRAP_MODEL = "BOOTSTRAP_MODEL";
    static final String PHASE_BOOTSTRAP_REASONING = "BOOTSTRAP_REASONING";
    static final String PHASE_BOOTSTRAP_SEND = "BOOTSTRAP_SEND";
    private static final String LEGACY_PHASE_WAIT_DRIVE_COMMIT = "WAIT_DRIVE_COMMIT";
    static final String PHASE_WAIT_TURN_COMPLETION = "WAIT_TURN_COMPLETION";
    static final String PHASE_POST_DOM_DRIVE_SYNC = "POST_DOM_DRIVE_SYNC";
    static final String PHASE_WAIT_INTERNAL_SEND = "WAIT_INTERNAL_SEND"; // migration-only
    static final String PHASE_RESUME_BASELINE = "RESUME_BASELINE";
    static final String PHASE_APPLY_PREFS = "APPLY_PREFS";
    static final String PHASE_APPLY_REASONING = "APPLY_REASONING";
    static final String PHASE_SEND_CONTINUE = "SEND_CONTINUE";
    static final String PHASE_PAUSED = "PAUSED";
    static final String PHASE_DONE = "DONE";

    static final String CREATION_NONE = "NONE";
    static final String CREATION_FOLDER_ID_RESERVED = "FOLDER_ID_RESERVED";
    static final String CREATION_FOLDER_CREATING = "FOLDER_CREATING";
    static final String CREATION_FOLDER_CREATED = "FOLDER_CREATED";
    static final String CREATION_DOCUMENT_CREATING = "DOCUMENT_CREATING";
    static final String CREATION_DOCUMENT_CREATED = "DOCUMENT_CREATED";

    static final String ATTACHMENT_PENDING = "PENDING";
    static final String ATTACHMENT_ID_RESERVED = "ID_RESERVED";
    static final String ATTACHMENT_UPLOADING = "UPLOADING";
    static final String ATTACHMENT_COMMITTED = "COMMITTED";
    static final int MAX_ATTACHMENTS_PER_RUN = 10;
    static final long MAX_ATTACHMENT_BYTES = 100L * 1024L * 1024L;
    static final int MAX_ATTACHMENT_UPLOAD_ATTEMPTS = 3;

    static final String EVENT_DETECTED = "EVENT_DETECTED";
    static final String SUBMISSION_STARTED = "SUBMISSION_STARTED";
    static final String SUBMISSION_CONFIRMED = "SUBMISSION_CONFIRMED";
    static final String EVENT_CONSUMED = "EVENT_CONSUMED";
    static final String BOOTSTRAP_NOT_STARTED = "BOOTSTRAP_NOT_STARTED";
    static final String BOOTSTRAP_SUBMISSION_STARTED = "BOOTSTRAP_SUBMISSION_STARTED";
    static final String BOOTSTRAP_SUBMISSION_CONFIRMED = "BOOTSTRAP_SUBMISSION_CONFIRMED";
    static final String RETRY_BOOTSTRAP = "BOOTSTRAP";
    static final String RETRY_CONTINUE = "CONTINUE";
    static final String WATCHDOG_CLAIM_NONE = "NONE";
    static final String WATCHDOG_CLAIMING = "CLAIMING";
    static final String WATCHDOG_CLAIM_OWNED = "OWNED";
    static final String WATCHDOG_CLAIM_SUBMITTED = "SUBMITTED";
    private static final String KEY_ATTACHMENTS = "attachmentsJson";
    private static final String KEY_ATTACHMENT_GRANT_CLEANUP = "attachmentGrantCleanupJson";

    static final class Attachment {
        final int index;
        final String uri;
        final String name;
        final String mimeType;
        final long size;
        final String driveFileId;
        final String stage;
        final int uploadAttempts;

        Attachment(int index, String uri, String name, String mimeType, long size,
                   String driveFileId, String stage, int uploadAttempts) {
            this.index = index;
            this.uri = safe(uri);
            this.name = safe(name);
            this.mimeType = safe(mimeType);
            this.size = size;
            this.driveFileId = safe(driveFileId);
            this.stage = safe(stage);
            this.uploadAttempts = Math.max(0, uploadAttempts);
        }

        static Attachment draft(int index, String uri, String name, String mimeType, long size) {
            return new Attachment(index, uri, name, mimeType, size, "", ATTACHMENT_PENDING, 0);
        }

        boolean committed() { return ATTACHMENT_COMMITTED.equals(stage); }
    }

    private final SharedPreferences prefs;
    private final SelfRunHistoryStore history;
    private final Context app;

    SelfRunStore(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE);
        history = new SelfRunHistoryStore(app);
        migrateLegacyContinuationAckWait();
        migrateLegacyBootstrapAckWait();
        migrateLegacyDriveCommitGuard();
        migrateRetiredSignalDisplay();
        migrateLegacyTurnCompletionFlow();
        drainAttachmentGrantCleanupJournal();
    }

    void start(String runId, String mode, String projectUrl, String requirement) {
        start(runId, mode, projectUrl, requirement, new ArrayList<>());
    }

    void start(String runId, String mode, String projectUrl, String requirement, List<Attachment> attachments) {
        synchronized (RUN_STATE_LOCK) {
            if (!DriveApiClient.validOpaqueAccountId(driveAccountId())
                    || !DriveApiClient.validFileId(driveRunsBaseFolderId())) {
                throw new IllegalStateException("Drive base binding required before a run starts");
            }
            String target = SelfRunScript.GENERAL_CHAT_URL;
            if (!SelfRunScript.isGeneralChatUrl(projectUrl)) {
                ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(projectUrl);
                if (ref == null) throw new IllegalArgumentException("trusted ChatGPT project URL required");
                target = ref.canonicalUrl;
            }
            startLocked(runId, mode, target, requirement, normalizeDrafts(attachments));
        }
    }

private void startLocked(String runId,String mode,String projectUrl,String requirement,List<Attachment> attachments){
 long now=System.currentTimeMillis();
 commitOrThrow(prefs.edit().putString("runId",safe(runId)).putLong("createdAt",now).putLong("phaseStartedAt",now)
  .putString("mode",safe(mode)).putString("projectUrl",safe(projectUrl)).putString("requirement",safe(requirement)).putString("conversationUrl","")
  .putString("phase",PHASE_DRIVE_ACCOUNT_CHECK).putString("status","Drive 계정 확인 준비")
  .putString("pendingModel",MODE_WORK.equals(mode)?"sol":"").putString("pendingReasoning",MODE_WORK.equals(mode)?"xhigh":"")
  .putString("lastErrorCode","").putString("lastErrorMessage","").putString("runDriveAccountId",driveAccountId()).putString("runBaseFolderId",driveRunsBaseFolderId())
  .putString("jobFolderId","").putString("turnDocumentId","").putString("turnDocumentUrl","").putString(KEY_ATTACHMENTS,encodeAttachments(attachments)).putString(KEY_ATTACHMENT_GRANT_CLEANUP,"[]").putInt("turn",0).putString("lastSeenDriveVersion","").putString("lastSeenModifiedTime","")
  .putInt("driveSignalCursor",0).putString("turnObserverToken","").putLong("postDomDriveSyncStartedAt",0L).putString("lastDriveSignalRaw","").putString("lastDriveSignalTimestamp","").putString("lastDriveSignalType","")
  .putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","").putLong("commitDetectedAt",0L)
  .putInt("watchdogClaimAttempt",0).putString("watchdogClaimName","").putString("watchdogClaimState",WATCHDOG_CLAIM_NONE).putInt("watchdogClaimCursor",0)
  .putString("activeCommandPrompt","").putString("activeCommandKind","").putInt("commandAttempt",0).putBoolean("awaitingCommandAck",false)
  .putString("submissionRetryKind","").putString("submissionRetryReason","").putLong("submissionRetryDueAt",0L).putInt("submissionRetryAttempt",0).putBoolean("submissionRetryReady",false)
  .putString("creationStage",CREATION_NONE).putString("pausedFromPhase","").putBoolean("resumeNeedsContinuation",false)
  .putBoolean("terminalSideEffectPending",false).putString("terminalSideEffectType","").putString("terminalSideEffectRunId","").putString("terminalSideEffectCommitId","")
  .putBoolean("active",true).putBoolean("paused",false).putBoolean("userStopped",false)
  .remove("role").remove("lastSignal")
  .remove("driveProtocolVersion").remove("expectedTurn").remove("lastConsumedEventSeq").remove("lastCommittedAt").remove("pendingEventSeq").remove("pendingTurn").remove("pendingSignalRaw").remove("pendingCommitId")
  .remove("submissionState").remove("submissionStartedAt").remove("submissionBaselineCount").remove("lastSubmittedCommitId").remove("bootstrapSubmittedAt").remove("bootstrapSubmissionState"));
 syncHistory();
}

    void stopByUser() {
        synchronized (RUN_STATE_LOCK) {
            List<Attachment> priorAttachments = attachments();
            commitOrThrow(clearWatchdogClaimFields(prefs.edit().putString(KEY_ATTACHMENTS, "[]")
                    .putString(KEY_ATTACHMENT_GRANT_CLEANUP, encodeAttachmentUris(priorAttachments)))
                    .putBoolean("active", false).putBoolean("paused", false)
                    .putBoolean("userStopped", true).putString("phase", PHASE_IDLE)
                    .putString("status", "사용자 중지").putLong("phaseStartedAt", System.currentTimeMillis()));
            drainAttachmentGrantCleanupJournal();
            syncHistory();
        }
    }

    void clear() {
        synchronized (RUN_STATE_LOCK) {
            List<Attachment> priorAttachments = attachments();
            String account = driveAccountId();
            String id = driveRunsBaseFolderId(), name = driveRunsBaseFolderName(), url = driveRunsBaseFolderUrl();
            long boundAt = driveRunsBaseFolderBoundAt();
            new ProjectCatalog(app).clear();
            commitOrThrow(prefs.edit().clear().putString("driveAccountId", account)
                    .putString("driveRunsBaseFolderId", id).putString("driveRunsBaseFolderName", name)
                    .putString("driveRunsBaseFolderUrl", url).putLong("driveRunsBaseFolderBoundAt", boundAt)
                    .putString(KEY_ATTACHMENT_GRANT_CLEANUP, encodeAttachmentUris(priorAttachments)));
            drainAttachmentGrantCleanupJournal();
        }
    }

    void bindBaseFolder(String accountId, String id, String name, String url, long boundAt) {
        DriveApiClient.requireParent(id);
        if (!DriveApiClient.validOpaqueAccountId(accountId)) throw new IllegalArgumentException("Drive account permissionId required");
        commitOrThrow(prefs.edit().putString("driveAccountId", safe(accountId)).putString("driveRunsBaseFolderId", id)
                .putString("driveRunsBaseFolderName", safe(name)).putString("driveRunsBaseFolderUrl", safe(url))
                .putLong("driveRunsBaseFolderBoundAt", boundAt));
    }

    void clearBaseFolderBinding() {
        commitOrThrow(prefs.edit().remove("driveAccountId").remove("driveRunsBaseFolderId")
                .remove("driveRunsBaseFolderName").remove("driveRunsBaseFolderUrl")
                .remove("driveRunsBaseFolderBoundAt"));
    }

    String runId() { return get("runId"); }
    long createdAt() { return prefs.getLong("createdAt", 0L); }
    long phaseStartedAt() { return prefs.getLong("phaseStartedAt", createdAt()); }
    String mode() { return getOr("mode", MODE_WORK); }
    String projectUrl() { return get("projectUrl"); }
    String defaultProjectUrl() { return canonicalStoredProjectUrl(get("defaultProjectUrl")); }
    static String canonicalStoredProjectUrl(String value) {
        if (value == null || value.isEmpty()) return "";
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(value);
        return ref == null ? value : ref.canonicalUrl;
    }
    String requirement() { return get("requirement"); }
    String conversationUrl() { return get("conversationUrl"); }
    String phase() { return getOr("phase", PHASE_IDLE); }
    String status() { return getOr("status", "대기"); }
    String pendingModel() { if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion()){DriveSignalParser.WorkProfile p=pendingDriveWorkProfile();return p.valid?p.model:WorkPreferenceDom.TURN_INFO_REWRITE_SENTINEL;}return get("pendingModel"); }
    String pendingReasoning() { if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion()){DriveSignalParser.WorkProfile p=pendingDriveWorkProfile();return p.valid?p.reasoning:WorkPreferenceDom.TURN_INFO_REWRITE_SENTINEL;}return get("pendingReasoning"); }
    String lastErrorCode() { return get("lastErrorCode"); }
    String lastErrorMessage() { return get("lastErrorMessage"); }
    int turn() { return prefs.getInt("turn", 0); }
    boolean active() { return prefs.getBoolean("active", false); }
    boolean paused() { return prefs.getBoolean("paused", false); }
    boolean userStopped() { return prefs.getBoolean("userStopped", false); }

    String driveAccountId() { return get("driveAccountId"); }
    String driveRunsBaseFolderId() { return get("driveRunsBaseFolderId"); }
    String driveRunsBaseFolderName() { return get("driveRunsBaseFolderName"); }
    String driveRunsBaseFolderUrl() { return get("driveRunsBaseFolderUrl"); }
    long driveRunsBaseFolderBoundAt() { return prefs.getLong("driveRunsBaseFolderBoundAt", 0L); }
    String runBaseFolderId() { return get("runBaseFolderId"); }
    String runDriveAccountId() { return get("runDriveAccountId"); }
    String jobFolderId() { return get("jobFolderId"); }
    String turnDocumentId() { return get("turnDocumentId"); }
    String turnDocumentUrl() { return get("turnDocumentUrl"); }
    String lastSeenDriveVersion() { return get("lastSeenDriveVersion"); }
    String lastSeenModifiedTime() { return get("lastSeenModifiedTime"); }
    int driveSignalCursor() { return prefs.getInt("driveSignalCursor", 0); }
    String lastDriveSignalRaw() { return get("lastDriveSignalRaw"); }
    String lastDriveSignalTimestamp() { return get("lastDriveSignalTimestamp"); }
    String lastDriveSignalType() { return get("lastDriveSignalType"); }
    String pendingDriveSignalRaw() { return get("pendingDriveSignalRaw"); }
    String pendingDriveSignalTimestamp() { return get("pendingDriveSignalTimestamp"); }
    String pendingDriveSignalType() { return get("pendingDriveSignalType"); }
    String pendingNextInput() { NextInputCodec.Decoded next=DriveSignalParser.nextInput(pendingDriveSignalRaw());return next.present&&next.valid?next.text:""; }
    long commitDetectedAt() { return prefs.getLong("commitDetectedAt", 0L); }
    String turnObserverToken() { return get("turnObserverToken"); }
    long postDomDriveSyncStartedAt() { return prefs.getLong("postDomDriveSyncStartedAt", 0L); }
    int watchdogClaimAttempt() { return prefs.getInt("watchdogClaimAttempt", 0); }
    String watchdogClaimName() { return get("watchdogClaimName"); }
    String watchdogClaimState() { return getOr("watchdogClaimState", WATCHDOG_CLAIM_NONE); }
    int watchdogClaimCursor() { return prefs.getInt("watchdogClaimCursor", 0); }
    boolean watchdogClaimOwned() { return WATCHDOG_CLAIM_OWNED.equals(watchdogClaimState()) && !watchdogClaimName().isEmpty(); }
    boolean watchdogClaimSubmitted() { return WATCHDOG_CLAIM_SUBMITTED.equals(watchdogClaimState()) && !watchdogClaimName().isEmpty(); }
    String activeCommandPrompt() { return get("activeCommandPrompt"); }
    String activeCommandKind() { if(turnInfoRewriteRequired()&&PHASE_SEND_CONTINUE.equals(phase())&&get("activeCommandPrompt").isEmpty())SelfRunProtocol.requestTurnInfoRewrite(runId());return get("activeCommandKind"); }
    int commandAttempt() { return prefs.getInt("commandAttempt", 0); }
    boolean awaitingCommandAck() { return prefs.getBoolean("awaitingCommandAck", false); }
    String submissionRetryKind() { return get("submissionRetryKind"); }
    String submissionRetryReason() { return get("submissionRetryReason"); }
    long submissionRetryDueAt() { return prefs.getLong("submissionRetryDueAt", 0L); }
    int submissionRetryAttempt() { return prefs.getInt("submissionRetryAttempt", 0); }
    boolean submissionRetryReady() { return prefs.getBoolean("submissionRetryReady", false); }
    boolean hasSubmissionRetry() { return RETRY_BOOTSTRAP.equals(submissionRetryKind()) && submissionRetryDueAt() > 0L; }
    boolean submissionRetryDue() { return hasSubmissionRetry() && System.currentTimeMillis() >= submissionRetryDueAt(); }
    boolean retryForBootstrap() { return RETRY_BOOTSTRAP.equals(submissionRetryKind()); }
    boolean retryForContinue() { return RETRY_CONTINUE.equals(submissionRetryKind()); }
    String creationStage() { return getOr("creationStage", CREATION_NONE); }
    long bootstrapSubmittedAt() { return prefs.getLong("bootstrapSubmittedAt", 0L); }
    String bootstrapSubmissionState() { return getOr("bootstrapSubmissionState", BOOTSTRAP_NOT_STARTED); }
    String pausedFromPhase() { return get("pausedFromPhase"); }
    boolean resumeNeedsContinuation() { return prefs.getBoolean("resumeNeedsContinuation", false); }
    boolean terminalSideEffectPending() { return prefs.getBoolean("terminalSideEffectPending", false); }
    String terminalSideEffectType() { return get("terminalSideEffectType"); }
    String terminalSideEffectRunId() { return get("terminalSideEffectRunId"); }
    String terminalSideEffectCommitId() { return get("terminalSideEffectCommitId"); }

    List<Attachment> attachments() { return decodeAttachments(get(KEY_ATTACHMENTS)); }
    int attachmentCount() { return attachments().size(); }
    boolean hasAttachments() { return attachmentCount() > 0; }
    boolean allAttachmentsCommitted() {
        List<Attachment> items = attachments();
        if (items.isEmpty()) return true;
        for (Attachment item : items) if (!item.committed()) return false;
        return true;
    }
    Attachment nextUncommittedAttachment() {
        for (Attachment item : attachments()) if (!item.committed()) return item;
        return null;
    }

    private boolean hasPendingDriveCompletion() { return DriveSignalParser.Type.TURN_COMPLETED.name().equals(pendingDriveSignalType())&&!pendingDriveSignalRaw().isEmpty(); }
    private DriveSignalParser.WorkProfile pendingDriveWorkProfile() { return DriveSignalParser.workProfile(pendingDriveSignalRaw()); }
    private boolean turnInfoRewriteRequired() { return MODE_WORK.equals(mode())&&hasPendingDriveCompletion()&&!pendingDriveWorkProfile().valid; }

    void setDefaultProjectUrl(String value) {
        if (value == null || value.trim().isEmpty() || SelfRunScript.isGeneralChatUrl(value)) { put("defaultProjectUrl", ""); return; }
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(value);
        if (ref == null) throw new IllegalArgumentException("trusted ChatGPT project URL required");
        put("defaultProjectUrl", ref.canonicalUrl);
    }
    void setPhase(String value) { commitOrThrow(prefs.edit().putString("phase", safe(value)).putLong("phaseStartedAt", System.currentTimeMillis())); syncHistory(); }
    void setStatus(String value) { put("status", value); }
    void setPendingModel(String value) { if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion())return;put("pendingModel", value); }
    void setPendingReasoning(String value) { if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion())return;put("pendingReasoning", value); }
    void setLastError(String code, String message) { commitOrThrow(prefs.edit().putString("lastErrorCode", safe(code)).putString("lastErrorMessage", safe(message))); syncHistory(); }
    void clearLastError() { setLastError("", ""); }
    void setTurn(int value) { commitOrThrow(prefs.edit().putInt("turn", value)); syncHistory(); }
    void setPaused(boolean value) { commitOrThrow(prefs.edit().putBoolean("paused", value)); syncHistory(); }
    void setActive(boolean value) { commitOrThrow(prefs.edit().putBoolean("active", value)); syncHistory(); }
    void setUserStopped(boolean value) { commitOrThrow(prefs.edit().putBoolean("userStopped", value)); syncHistory(); }
    void setCreationStage(String value) { commitOrThrow(prefs.edit().putString("creationStage", value)); }

    void reserveJobFolderId(String id) {
        DriveApiClient.requireParent(id);
        commitOrThrow(prefs.edit().putString("jobFolderId", id).putString("creationStage", CREATION_FOLDER_ID_RESERVED));
    }
    void markJobFolderCreating() {
        if (jobFolderId().isEmpty()) throw new IllegalStateException("reserved folder id required");
        commitOrThrow(prefs.edit().putString("creationStage", CREATION_FOLDER_CREATING));
    }
    void saveJobFolder(String id) { DriveApiClient.requireParent(id); commitOrThrow(prefs.edit().putString("jobFolderId", id).putString("creationStage", CREATION_FOLDER_CREATED)); }
    void saveTurnDocument(String id, String url) { DriveApiClient.requireParent(id); commitOrThrow(prefs.edit().putString("turnDocumentId", id).putString("turnDocumentUrl", safe(url)).putString("creationStage", CREATION_DOCUMENT_CREATED)); }
    void resetDocumentCreateAfterDefiniteFailure() {
        if (!turnDocumentId().isEmpty()) throw new IllegalStateException("created document cannot be reset");
        commitOrThrow(prefs.edit().putString("creationStage", CREATION_FOLDER_CREATED));
    }

    void reserveAttachmentFileId(int index, String fileId) {
        DriveApiClient.requireParent(fileId);
        synchronized (RUN_STATE_LOCK) {
            Attachment item = requireAttachment(index);
            if (item.committed()) return;
            if (!item.driveFileId.isEmpty() && !item.driveFileId.equals(fileId)) throw new IllegalStateException("attachment already owns a different Drive id");
            replaceAttachment(new Attachment(item.index, item.uri, item.name, item.mimeType, item.size,
                    fileId, ATTACHMENT_ID_RESERVED, item.uploadAttempts));
        }
    }

    void updateAttachmentSize(int index, long size) {
        if (size < 0 || size > MAX_ATTACHMENT_BYTES) throw new IllegalArgumentException("known attachment size required");
        synchronized (RUN_STATE_LOCK) {
            Attachment item = requireAttachment(index);
            if (item.committed()) return;
            replaceAttachment(new Attachment(item.index, item.uri, item.name, item.mimeType, size,
                    item.driveFileId, item.stage, item.uploadAttempts));
        }
    }

    void markAttachmentUploading(int index) {
        synchronized (RUN_STATE_LOCK) {
            Attachment item = requireAttachment(index);
            if (item.committed()) return;
            if (!DriveApiClient.validFileId(item.driveFileId)) throw new IllegalStateException("reserved Drive id required before upload");
            if (item.uploadAttempts >= MAX_ATTACHMENT_UPLOAD_ATTEMPTS) throw new IllegalStateException("attachment upload retry budget exhausted");
            replaceAttachment(new Attachment(item.index, item.uri, item.name, item.mimeType, item.size,
                    item.driveFileId, ATTACHMENT_UPLOADING, item.uploadAttempts + 1));
        }
    }

    void markAttachmentCommitted(int index) {
        synchronized (RUN_STATE_LOCK) {
            Attachment item = requireAttachment(index);
            if (!item.committed()) {
                if (!DriveApiClient.validFileId(item.driveFileId)) throw new IllegalStateException("committed attachment Drive id required");
                // Persist COMMITTED before releasing the URI grant so a process death cannot lose both
                // the source permission and the durable upload result. URI cleanup is recoverable below.
                replaceAttachment(new Attachment(item.index, item.uri, item.name, item.mimeType, item.size,
                        item.driveFileId, ATTACHMENT_COMMITTED, item.uploadAttempts));
            }
            releaseCommittedAttachmentPermissions();
        }
    }

    void releaseCommittedAttachmentPermissions() {
        synchronized (RUN_STATE_LOCK) {
            List<Attachment> items = attachments();
            boolean changed = false;
            for (int i = 0; i < items.size(); i++) {
                Attachment item = items.get(i);
                if (!item.committed() || item.uri.isEmpty()) continue;
                if (releaseAttachmentPermission(item.uri)) {
                    items.set(i, new Attachment(item.index, "", item.name, item.mimeType, item.size,
                            item.driveFileId, ATTACHMENT_COMMITTED, item.uploadAttempts));
                    changed = true;
                }
            }
            if (changed) commitOrThrow(prefs.edit().putString(KEY_ATTACHMENTS, encodeAttachments(items)));
        }
    }

    void updateDriveSeen(String version, String modifiedTime) {
        commitOrThrow(prefs.edit().putString("lastSeenDriveVersion", safe(version)).putString("lastSeenModifiedTime", safe(modifiedTime)));
    }

    String beginWatchdogClaim(int cursor) {
        synchronized (RUN_STATE_LOCK) {
            int normalizedCursor = Math.max(0, cursor);
            String state = watchdogClaimState(), existing = watchdogClaimName();
            if ((WATCHDOG_CLAIMING.equals(state) || WATCHDOG_CLAIM_OWNED.equals(state))
                    && !existing.isEmpty()) {
                if (watchdogClaimCursor() != normalizedCursor) {
                    commitOrThrow(prefs.edit().putInt("watchdogClaimCursor", normalizedCursor));
                    syncHistory();
                }
                return existing;
            }
            int next = watchdogClaimAttempt() == Integer.MAX_VALUE ? Integer.MAX_VALUE : watchdogClaimAttempt() + 1;
            String name = watchdogClaimNameFor(runId(), next);
            commitOrThrow(prefs.edit().putInt("watchdogClaimAttempt", next).putString("watchdogClaimName", name)
                    .putString("watchdogClaimState", WATCHDOG_CLAIMING).putInt("watchdogClaimCursor", normalizedCursor));
            syncHistory();
            return name;
        }
    }

    static String watchdogClaimNameFor(String runId, int attempt) {
        String normalized = safe(runId).replaceAll("[^A-Za-z0-9._-]", "_");
        if (normalized.isEmpty()) throw new IllegalArgumentException("run id required for watchdog claim");
        return "selfrun_watchdog_" + normalized + "_" + Math.max(1, attempt);
    }

    void ownWatchdogClaimAndEnterClick(String clickPhase, String status) {
        synchronized (RUN_STATE_LOCK) {
            if (watchdogClaimName().isEmpty() || !(WATCHDOG_CLAIMING.equals(watchdogClaimState()) || WATCHDOG_CLAIM_OWNED.equals(watchdogClaimState()))) {
                throw new IllegalStateException("persisted watchdog claim required before click");
            }
            commitOrThrow(prefs.edit().putString("watchdogClaimState", WATCHDOG_CLAIM_OWNED)
                    .putString("phase", safe(clickPhase)).putString("status", safe(status)).putLong("phaseStartedAt", System.currentTimeMillis()));
            syncHistory();
        }
    }

    void confirmWatchdogSubmission(String postSubmitPhase, String status) {
        synchronized (RUN_STATE_LOCK) {
            if (!watchdogClaimOwned()) throw new IllegalStateException("owned watchdog claim required before submission confirmation");
            commitOrThrow(prefs.edit().putString("watchdogClaimState", WATCHDOG_CLAIM_SUBMITTED)
                    .putString("phase", safe(postSubmitPhase)).putString("status", safe(status)).putLong("phaseStartedAt", System.currentTimeMillis()));
            syncHistory();
        }
    }

    void abandonWatchdogClaim() {
        synchronized (RUN_STATE_LOCK) {
            commitOrThrow(clearWatchdogClaimFields(prefs.edit()));
            syncHistory();
        }
    }

    void abandonWatchdogClaimAndWait(String status) {
        synchronized (RUN_STATE_LOCK) {
            SharedPreferences.Editor e = clearWatchdogClaimFields(prefs.edit())
                    .putString("phase", LEGACY_PHASE_WAIT_DRIVE_COMMIT).putString("status", safe(status))
                    .putLong("phaseStartedAt", System.currentTimeMillis());
            commitOrThrow(e); syncHistory();
        }
    }

    void finishWatchdogRecoveryBaseline(int cursor, DriveSignalParser.Event latest) {
        synchronized (RUN_STATE_LOCK) {
            if (!watchdogClaimSubmitted()) throw new IllegalStateException("submitted watchdog claim required for recovery baseline");
            SharedPreferences.Editor e = clearWatchdogClaimFields(prefs.edit())
                    .putInt("driveSignalCursor", Math.max(0, cursor))
                    .putString("pendingDriveSignalRaw", "").putString("pendingDriveSignalTimestamp", "")
                    .putString("pendingDriveSignalType", "").putLong("commitDetectedAt", 0L)
                    .putString("phase", LEGACY_PHASE_WAIT_DRIVE_COMMIT).putString("status", "Drive 턴 결과 신호 대기")
                    .putLong("phaseStartedAt", System.currentTimeMillis());
            putLatest(e, latest); commitOrThrow(e); syncHistory();
        }
    }

void baselineDriveSignals(int cursor,DriveSignalParser.Event latest){SharedPreferences.Editor e=prefs.edit().putInt("driveSignalCursor",Math.max(0,cursor));putLatest(e,latest);commitOrThrow(e);syncHistory();}
void beginCommandAttempt(String kind,String prompt){if(!(RETRY_BOOTSTRAP.equals(kind)||RETRY_CONTINUE.equals(kind))||safe(prompt).isEmpty())throw new IllegalArgumentException("valid command attempt required");int n=commandAttempt()==Integer.MAX_VALUE?Integer.MAX_VALUE:commandAttempt()+1;commitOrThrow(prefs.edit().putString("activeCommandKind",kind).putString("activeCommandPrompt",prompt).putInt("commandAttempt",n).putBoolean("awaitingCommandAck",false));syncHistory();}
String commandMarkerId(){return runId()+":"+commandAttempt();}
void prepareTurnObserver(String token){String value=safe(token);if(value.isEmpty())throw new IllegalArgumentException("turn observer token required");commitOrThrow(prefs.edit().putString("turnObserverToken",value));}
void bootstrapSubmissionConfirmed(String observerToken){if(!RETRY_BOOTSTRAP.equals(activeCommandKind())||activeCommandPrompt().isEmpty())throw new IllegalStateException("prepared bootstrap command required");beginTurnCompletionWait(observerToken,"첫 요청 제출 확인 · 답변 완료 감지 중");}
void beginTurnCompletionWait(String observerToken,String waitingStatus){String token=safe(observerToken);if(token.isEmpty()||!token.equals(turnObserverToken()))throw new IllegalStateException("prepared turn observer required");String appliedModel=pendingModel(),appliedReasoning=pendingReasoning();SharedPreferences.Editor e=clearWatchdogClaimFields(clearCommandWait(prefs.edit())).putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","").putLong("commitDetectedAt",0L).putString("pendingModel",appliedModel).putString("pendingReasoning",appliedReasoning).putString("phase",PHASE_WAIT_TURN_COMPLETION).putString("status",safe(waitingStatus)).putLong("phaseStartedAt",System.currentTimeMillis()).putLong("postDomDriveSyncStartedAt",0L);commitOrThrow(e);syncHistory();}
boolean beginPostDomDriveSync(String observerToken){String token=safe(observerToken);if(!PHASE_WAIT_TURN_COMPLETION.equals(phase())||token.isEmpty()||!token.equals(turnObserverToken()))return false;long now=System.currentTimeMillis();commitOrThrow(prefs.edit().putString("turnObserverToken","").putString("phase",PHASE_POST_DOM_DRIVE_SYNC).putString("status","답변 완료 5초 재확인 · Drive 신호 즉시 확인").putLong("phaseStartedAt",now).putLong("postDomDriveSyncStartedAt",now));syncHistory();return true;}
void continueAfterPostDomDriveTimeout(){if(!PHASE_POST_DOM_DRIVE_SYNC.equals(phase()))return;String next=MODE_WORK.equals(mode())?PHASE_APPLY_PREFS:PHASE_SEND_CONTINUE;commitOrThrow(prefs.edit().putString("phase",next).putString("status","Drive 완료 신호 제한시간 만료 · 현재 설정으로 다음 턴 전송").putLong("phaseStartedAt",System.currentTimeMillis()).putLong("postDomDriveSyncStartedAt",0L));syncHistory();}

void applyDriveSignals(List<DriveSignalParser.Event> events,long detectedAt){if(events==null||events.isEmpty())return;SharedPreferences.Editor e=prefs.edit();int rank=PHASE_DONE.equals(phase())||PHASE_PAUSED.equals(phase())?3:0;for(DriveSignalParser.Event x:events){e.putInt("driveSignalCursor",x.cursor);putLatest(e,x);switch(x.type){case TURN_COMPLETED->{if(!x.protocolError.isEmpty())continue;if(rank<2){rank=2;String next=MODE_WORK.equals(mode())?PHASE_APPLY_PREFS:PHASE_SEND_CONTINUE;e.putString("pendingDriveSignalRaw",x.raw).putString("pendingDriveSignalTimestamp",x.timestamp).putString("pendingDriveSignalType",x.type.name()).putLong("commitDetectedAt",detectedAt).putLong("postDomDriveSyncStartedAt",0L).putString("phase",next).putString("status","Drive TURN_COMPLETED·NEXT_INPUT 확인 · 다음 턴 전송 준비");}}case USER_ACTION_REQUIRED->{rank=3;clearCommandWait(e);pauseEvent(e,x,"사용자 조치 필요");}case PAUSED->{rank=3;clearCommandWait(e);pauseEvent(e,x,"SelfRun Drive 일시정지");}case DONE->{rank=3;clearCommandWait(e);e.putBoolean("active",false).putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_DONE).putString("status","SelfRun Drive 완료");terminal(e,x);}}}e.putString("turnObserverToken","").putBoolean("awaitingCommandAck",false).putLong("phaseStartedAt",System.currentTimeMillis());commitOrThrow(e);syncHistory();}
void beginManualResumeOverride(){if(PHASE_DRIVE_ATTACHMENT_UPLOAD.equals(pausedFromPhase())&&turnDocumentId().isEmpty()){commitOrThrow(clearWatchdogClaimFields(clearCommandWait(prefs.edit())).putBoolean("terminalSideEffectPending",false).putString("terminalSideEffectType","").putString("terminalSideEffectRunId","").putString("terminalSideEffectCommitId","").putBoolean("paused",false).putBoolean("active",true).putBoolean("userStopped",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_DRIVE_ATTACHMENT_UPLOAD).putString("status","사용자 재개 · 첨부파일 Drive 업로드 재확인").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();return;}commitOrThrow(clearWatchdogClaimFields(clearCommandWait(prefs.edit())).putBoolean("terminalSideEffectPending",false).putString("terminalSideEffectType","").putString("terminalSideEffectRunId","").putString("terminalSideEffectCommitId","").putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","").putLong("commitDetectedAt",0L).putBoolean("paused",false).putBoolean("active",true).putBoolean("userStopped",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_RESUME_BASELINE).putString("status","사용자 재개 override · Drive 최신 신호 baseline 확인").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();}
void baselineManualResume(int cursor,DriveSignalParser.Event latest,DriveSignalParser.Event latestUnseenCompletion){SharedPreferences.Editor e=clearCommandWait(prefs.edit()).putInt("driveSignalCursor",Math.max(0,cursor)).putBoolean("paused",false).putBoolean("active",true).putBoolean("userStopped",false).putString("phase",PHASE_SEND_CONTINUE).putString("status","사용자 재개 override · CONTINUE 강제 제출 준비").putLong("phaseStartedAt",System.currentTimeMillis());if(latestUnseenCompletion!=null&&latestUnseenCompletion.protocolError.isEmpty()){e.putString("pendingDriveSignalRaw",latestUnseenCompletion.raw).putString("pendingDriveSignalTimestamp",latestUnseenCompletion.timestamp).putString("pendingDriveSignalType",DriveSignalParser.Type.TURN_COMPLETED.name());}else{e.putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","");}putLatest(e,latest);commitOrThrow(e);syncHistory();}
static boolean canCaptureConversationUrl(String projectUrl,String value){if(SelfRunScript.isGeneralChatUrl(projectUrl)){return SelfRunScript.isGeneralChatUrl(value)&&!SelfRunScript.conversationId(value).isEmpty();}ProjectUrlPolicy.ProjectRef expected=ProjectUrlPolicy.parseProject(projectUrl),actual=ProjectUrlPolicy.parseProject(value);return expected!=null&&actual!=null&&!actual.conversationId.isEmpty()&&expected.projectId.equals(actual.projectId);}
void captureConversationUrl(String value){if(!conversationUrl().isEmpty()||!canCaptureConversationUrl(projectUrl(),value))return;commitOrThrow(prefs.edit().putString("conversationUrl",safe(value)));syncHistory();}
private static SharedPreferences.Editor clearCommandWait(SharedPreferences.Editor e){return e.putBoolean("awaitingCommandAck",false).putString("activeCommandPrompt","").putString("activeCommandKind","").putString("submissionRetryKind","").putString("submissionRetryReason","").putLong("submissionRetryDueAt",0L).putInt("submissionRetryAttempt",0).putBoolean("submissionRetryReady",false);}
private static SharedPreferences.Editor clearWatchdogClaimFields(SharedPreferences.Editor e){return e.putString("watchdogClaimName","").putString("watchdogClaimState",WATCHDOG_CLAIM_NONE).putInt("watchdogClaimCursor",0);}
private void migrateLegacyContinuationAckWait(){synchronized(RUN_STATE_LOCK){if(!RETRY_CONTINUE.equals(get("submissionRetryKind")))return;SharedPreferences.Editor e=clearCommandWait(prefs.edit());if(LEGACY_PHASE_WAIT_DRIVE_COMMIT.equals(phase()))e.putString("status","업데이트된 continuation · Drive 결과 신호 대기").putLong("phaseStartedAt",System.currentTimeMillis());commitOrThrow(e);syncHistory();}}
private void migrateLegacyBootstrapAckWait(){synchronized(RUN_STATE_LOCK){if(!(awaitingCommandAck()&&RETRY_BOOTSTRAP.equals(get("submissionRetryKind"))&&LEGACY_PHASE_WAIT_DRIVE_COMMIT.equals(phase())))return;commitOrThrow(clearCommandWait(prefs.edit()).putString("status","업데이트된 bootstrap · Drive 턴 결과 신호 대기").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();}}
private void migrateLegacyDriveCommitGuard(){synchronized(RUN_STATE_LOCK){if(!"DRIVE_COMMIT_GUARD".equals(phase()))return;commitOrThrow(prefs.edit().putString("phase",PHASE_WAIT_INTERNAL_SEND).putString("status","업데이트된 TURN_COMPLETED · 내부 WebView 입력 대기 상태 확인").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();}}
private void migrateLegacyTurnCompletionFlow(){synchronized(RUN_STATE_LOCK){String current=phase();boolean legacy=LEGACY_PHASE_WAIT_DRIVE_COMMIT.equals(current)||PHASE_WAIT_INTERNAL_SEND.equals(current)||current.startsWith("WATCHDOG_");if(!legacy)return;commitOrThrow(clearWatchdogClaimFields(prefs.edit()).putString("phase",PHASE_WAIT_TURN_COMPLETION).putString("turnObserverToken","").putLong("postDomDriveSyncStartedAt",0L).putString("status","업데이트된 실행 · 답변 완료 Observer 재연결").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();}}
private void migrateRetiredSignalDisplay(){synchronized(RUN_STATE_LOCK){String type=lastDriveSignalType();boolean current=DriveSignalParser.Type.TURN_COMPLETED.name().equals(type)||DriveSignalParser.Type.USER_ACTION_REQUIRED.name().equals(type)||DriveSignalParser.Type.PAUSED.name().equals(type)||DriveSignalParser.Type.DONE.name().equals(type);SharedPreferences.Editor e=prefs.edit();boolean changed=false;if(!type.isEmpty()&&!current){e.putString("lastDriveSignalRaw","").putString("lastDriveSignalTimestamp","").putString("lastDriveSignalType","");changed=true;}if(LEGACY_PHASE_WAIT_DRIVE_COMMIT.equals(phase())){e.putString("status","Drive 턴 결과 신호 대기");changed=true;}if(changed){commitOrThrow(e);syncHistory();}}}
private static void putLatest(SharedPreferences.Editor e,DriveSignalParser.Event x){if(x==null)e.putString("lastDriveSignalRaw","").putString("lastDriveSignalTimestamp","").putString("lastDriveSignalType","");else e.putString("lastDriveSignalRaw",x.raw).putString("lastDriveSignalTimestamp",x.timestamp).putString("lastDriveSignalType",x.type.name());}
private void pauseEvent(SharedPreferences.Editor e,DriveSignalParser.Event x,String status){e.putBoolean("paused",true).putBoolean("active",true).putBoolean("resumeNeedsContinuation",true).putString("pausedFromPhase",phase()).putString("phase",PHASE_PAUSED).putString("status",status);terminal(e,x);}
private void terminal(SharedPreferences.Editor e,DriveSignalParser.Event x){e.putBoolean("terminalSideEffectPending",true).putString("terminalSideEffectType",x.type.name()).putString("terminalSideEffectRunId",runId()).putString("terminalSideEffectCommitId",x.raw);}

    boolean terminalSideEffectOwnedBy(String ownerRunId, String commitId, String type) {
        synchronized (RUN_STATE_LOCK) {
            return terminalSideEffectPending() && runId().equals(safe(ownerRunId))
                    && terminalSideEffectRunId().equals(safe(ownerRunId))
                    && terminalSideEffectCommitId().equals(safe(commitId))
                    && terminalSideEffectType().equals(safe(type));
        }
    }

    boolean acknowledgeTerminalSideEffect(String ownerRunId, String commitId, String type) {
        synchronized (RUN_STATE_LOCK) {
            if (!terminalSideEffectOwnedBy(ownerRunId, commitId, type)) return false;
            commitOrThrow(prefs.edit().putBoolean("terminalSideEffectPending", false)
                    .putString("terminalSideEffectType", "").putString("terminalSideEffectRunId", "")
                    .putString("terminalSideEffectCommitId", ""));
            return true;
        }
    }

    void enterPause(String priorPhase, boolean needsContinuation) {
        commitOrThrow(prefs.edit().putString("pausedFromPhase", safe(priorPhase)).putBoolean("resumeNeedsContinuation", needsContinuation)
                .putBoolean("paused", true).putString("phase", PHASE_PAUSED)
                .putLong("phaseStartedAt", System.currentTimeMillis())); syncHistory();
    }

    void leavePause(String nextPhase) {
        commitOrThrow(prefs.edit().putBoolean("paused", false).putBoolean("active", true).putBoolean("userStopped", false)
                .putString("phase", safe(nextPhase)).putLong("phaseStartedAt", System.currentTimeMillis())); syncHistory();
    }

    static String encodeAttachmentDrafts(List<Attachment> attachments) { return encodeAttachments(normalizeDrafts(attachments)); }
    static List<Attachment> decodeAttachmentDrafts(String raw) { return decodeAttachments(raw); }

    private static List<Attachment> normalizeDrafts(List<Attachment> source) {
        ArrayList<Attachment> result = new ArrayList<>();
        Set<Integer> indexes = new HashSet<>();
        Set<String> uris = new HashSet<>();
        if (source == null) return result;
        if (source.size() > MAX_ATTACHMENTS_PER_RUN) throw new IllegalArgumentException("too many attachments");
        for (Attachment item : source) {
            if (item == null || item.index < 0 || !indexes.add(item.index)) throw new IllegalArgumentException("unique attachment index required");
            Uri parsed = Uri.parse(item.uri);
            if (!"content".equals(parsed.getScheme()) || item.uri.isEmpty() || !uris.add(item.uri)) throw new IllegalArgumentException("unique content attachment URI required");
            if (item.name.isEmpty() || item.name.length() > 180) throw new IllegalArgumentException("safe attachment name required");
            if (!DriveApiClient.validAttachmentMimeType(item.mimeType)) throw new IllegalArgumentException("safe attachment MIME type required");
            if (item.size < -1 || item.size > MAX_ATTACHMENT_BYTES) throw new IllegalArgumentException("attachment size invalid");
            result.add(Attachment.draft(item.index, item.uri, item.name, item.mimeType, item.size));
        }
        return result;
    }

    private static String encodeAttachments(List<Attachment> attachments) {
        try {
            JSONArray array = new JSONArray();
            if (attachments != null) {
                for (Attachment item : attachments) {
                    array.put(new JSONObject().put("index", item.index).put("uri", item.uri)
                            .put("name", item.name).put("mimeType", item.mimeType).put("size", item.size)
                            .put("driveFileId", item.driveFileId).put("stage", item.stage).put("uploadAttempts", item.uploadAttempts));
                }
            }
            return array.toString();
        } catch (Throwable error) { throw new IllegalStateException("attachment state encode failed", error); }
    }

    private static List<Attachment> decodeAttachments(String raw) {
        ArrayList<Attachment> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(raw);
            Set<Integer> indexes = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.getJSONObject(i);
                Attachment item = new Attachment(json.getInt("index"), json.optString("uri", ""),
                        json.optString("name", ""), json.optString("mimeType", ""), json.optLong("size", -1L),
                        json.optString("driveFileId", ""), json.optString("stage", ATTACHMENT_PENDING),
                        json.optInt("uploadAttempts", 0));
                if (array.length() > MAX_ATTACHMENTS_PER_RUN || item.index < 0 || !indexes.add(item.index) || item.name.isEmpty() || item.name.length() > 180
                        || !DriveApiClient.validAttachmentMimeType(item.mimeType) || item.size < -1 || item.size > MAX_ATTACHMENT_BYTES
                        || item.uploadAttempts < 0 || item.uploadAttempts > MAX_ATTACHMENT_UPLOAD_ATTEMPTS
                        || !validAttachmentStage(item.stage)
                        || (!item.driveFileId.isEmpty() && !DriveApiClient.validFileId(item.driveFileId))) {
                    throw new IllegalStateException("attachment state invalid");
                }
                if (!item.committed()) {
                    Uri parsed = Uri.parse(item.uri);
                    if (!"content".equals(parsed.getScheme()) || item.uri.isEmpty()) throw new IllegalStateException("active attachment URI invalid");
                }
                result.add(item);
            }
            return result;
        } catch (IllegalStateException error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("attachment state decode failed", error); }
    }

    private static boolean validAttachmentStage(String stage) {
        return ATTACHMENT_PENDING.equals(stage) || ATTACHMENT_ID_RESERVED.equals(stage)
                || ATTACHMENT_UPLOADING.equals(stage) || ATTACHMENT_COMMITTED.equals(stage);
    }

    private Attachment requireAttachment(int index) {
        for (Attachment item : attachments()) if (item.index == index) return item;
        throw new IllegalStateException("attachment index not found");
    }

    private void replaceAttachment(Attachment updated) {
        List<Attachment> items = attachments();
        boolean replaced = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).index == updated.index) { items.set(i, updated); replaced = true; break; }
        }
        if (!replaced) throw new IllegalStateException("attachment index not found");
        commitOrThrow(prefs.edit().putString(KEY_ATTACHMENTS, encodeAttachments(items)));
    }


    void prepareAttachmentGrantHandoff(List<Attachment> attachments) {
        List<Attachment> drafts = normalizeDrafts(attachments);
        commitOrThrow(prefs.edit().putString(KEY_ATTACHMENT_GRANT_CLEANUP, encodeAttachmentUris(drafts)));
    }

    void cancelAttachmentGrantHandoff() { drainAttachmentGrantCleanupJournal(); }

    private static String encodeAttachmentUris(List<Attachment> attachments) {
        try {
            JSONArray array = new JSONArray();
            if (attachments != null) for (Attachment item : attachments) if (item != null && !item.uri.isEmpty()) array.put(item.uri);
            return array.toString();
        } catch (Throwable error) { throw new IllegalStateException("attachment grant journal encode failed", error); }
    }

    private List<String> decodeAttachmentGrantCleanupJournal() {
        ArrayList<String> result = new ArrayList<>();
        String raw = get(KEY_ATTACHMENT_GRANT_CLEANUP);
        if (raw.isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                Uri uri = Uri.parse(value);
                if (!value.isEmpty() && "content".equals(uri.getScheme())) result.add(value);
            }
            return result;
        } catch (Throwable ignored) { return result; }
    }

    private void drainAttachmentGrantCleanupJournal() {
        synchronized (RUN_STATE_LOCK) {
            List<String> pending = decodeAttachmentGrantCleanupJournal();
            if (pending.isEmpty()) {
                if (!"[]".equals(get(KEY_ATTACHMENT_GRANT_CLEANUP))) commitOrThrow(prefs.edit().putString(KEY_ATTACHMENT_GRANT_CLEANUP, "[]"));
                return;
            }
            JSONArray remaining = new JSONArray();
            for (String value : pending) if (!releaseAttachmentPermission(value)) remaining.put(value);
            commitOrThrow(prefs.edit().putString(KEY_ATTACHMENT_GRANT_CLEANUP, remaining.toString()));
        }
    }

    private boolean hasPersistedReadGrant(Uri uri) {
        if (uri == null) return false;
        try {
            for (UriPermission permission : app.getContentResolver().getPersistedUriPermissions()) {
                if (permission != null && permission.isReadPermission() && uri.equals(permission.getUri())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean releaseAttachmentPermission(String value) {
        if (value == null || value.isEmpty()) return true;
        try {
            Uri uri = Uri.parse(value);
            if (!"content".equals(uri.getScheme())) return true;
            try { app.getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Throwable ignored) {}
            return !hasPersistedReadGrant(uri);
        } catch (Throwable ignored) { return false; }
    }


    void syncHistory() { history.sync(this); }
    private void put(String key, String value) { commitOrThrow(prefs.edit().putString(key, safe(value))); syncHistory(); }
    private String get(String key) { return prefs.getString(key, ""); }
    private String getOr(String key, String fallback) { return prefs.getString(key, fallback); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static void commitOrThrow(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("durable SelfRun Drive state write failed");
    }
}
