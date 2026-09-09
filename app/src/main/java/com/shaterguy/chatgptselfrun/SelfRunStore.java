package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable user-visible projection for the single active SelfRun 3 task.
 * The authoritative execution state lives in SelfRun3Ledger; this store only keeps
 * launch configuration, UI projection, Drive binding, attachment ownership and diagnostics.
 */
final class SelfRunStore {
    static final Object RUN_STATE_LOCK = new Object();

    static final String MODE_CHAT = "CHAT";
    static final String MODE_WORK = "WORK";
    static final String MODE_HYBRID = "HYBRID";

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
    static final String PHASE_WAIT_TURN_COMPLETION = "WAIT_TURN_COMPLETION";
    static final String PHASE_POST_PROTOCOL_DRIVE_SYNC = "POST_PROTOCOL_DRIVE_SYNC";
    static final String PHASE_RESUME_BASELINE = "RESUME_BASELINE";
    static final String PHASE_APPLY_PREFS = "APPLY_PREFS";
    static final String PHASE_APPLY_REASONING = "APPLY_REASONING";
    static final String PHASE_SEND_CONTINUE = "SEND_CONTINUE";
    static final String PHASE_PAUSED = "PAUSED";
    static final String PHASE_DONE = "DONE";

    static final String ATTACHMENT_PENDING = "PENDING";
    static final String ATTACHMENT_ID_RESERVED = "ID_RESERVED";
    static final String ATTACHMENT_UPLOADING = "UPLOADING";
    static final String ATTACHMENT_COMMITTED = "COMMITTED";
    static final int MAX_ATTACHMENTS_PER_RUN = 10;
    static final long MAX_ATTACHMENT_BYTES = 100L * 1024L * 1024L;
    static final int MAX_ATTACHMENT_UPLOAD_ATTEMPTS = 3;

    private static final String PREFS = "selfrun_drive";
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

    private final Context app;
    private final SharedPreferences prefs;
    private final SelfRunHistoryStore history;

    SelfRunStore(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        history = new SelfRunHistoryStore(app);
        drainAttachmentGrantCleanupJournal();
    }

    void start(String runId, String mode, String projectUrl, String requirement) {
        start(runId, mode, projectUrl, requirement, new ArrayList<>());
    }

    void start(String runId, String mode, String projectUrl, String requirement, List<Attachment> attachments) {
        start(runId, mode, projectUrl, requirement, attachments, mode);
    }

    void start(String runId, String mode, String projectUrl, String requirement,
               List<Attachment> attachments, String taskMode) {
        startInternal(runId, mode, projectUrl, requirement, attachments, "", "", taskMode);
    }

    void startWork(String runId, String projectUrl, String requirement, String model, String reasoning) {
        startWork(runId, projectUrl, requirement, new ArrayList<>(), model, reasoning);
    }

    void startWork(String runId, String projectUrl, String requirement, List<Attachment> attachments,
                   String model, String reasoning) {
        startWork(runId, projectUrl, requirement, attachments, model, reasoning, MODE_WORK);
    }

    void startWork(String runId, String projectUrl, String requirement, List<Attachment> attachments,
                   String model, String reasoning, String taskMode) {
        if (!SelfRunProtocol.validWorkProfile(model, reasoning)) {
            throw new IllegalArgumentException("registered Work profile required");
        }
        startInternal(runId, MODE_WORK, projectUrl, requirement, attachments, model, reasoning, taskMode);
    }

    private void startInternal(String runId, String mode, String projectUrl, String requirement,
                               List<Attachment> attachments, String model, String reasoning, String taskMode) {
        if (!SelfRunProtocolRules.validRunId(runId)) throw new IllegalArgumentException("valid V3 run id required");
        if (!MODE_CHAT.equals(mode) && !MODE_WORK.equals(mode)) throw new IllegalArgumentException("CHAT or WORK mode required");
        if (!MODE_HYBRID.equals(taskMode) && !mode.equals(taskMode)) {
            throw new IllegalArgumentException("task mode must match actual mode or be HYBRID");
        }
        if (!DriveApiClient.validOpaqueAccountId(driveAccountId()) || !DriveApiClient.validFileId(driveRunsBaseFolderId())) {
            throw new IllegalStateException("Drive base binding required before a run starts");
        }
        String target = SelfRunScript.GENERAL_CHAT_URL;
        if (!SelfRunScript.isGeneralChatUrl(projectUrl)) {
            ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(projectUrl);
            if (ref == null) throw new IllegalArgumentException("trusted ChatGPT project URL required");
            target = ref.canonicalUrl;
        }
        List<Attachment> drafts = normalizeDrafts(attachments);
        synchronized (RUN_STATE_LOCK) {
            long now = System.currentTimeMillis();
            commitOrThrow(prefs.edit()
                    .putString("runId", runId)
                    .putLong("createdAt", now)
                    .putLong("phaseStartedAt", now)
                    .putString("taskMode", taskMode)
                    .putString("mode", mode)
                    .putString("projectUrl", target)
                    .putString("requirement", safe(requirement))
                    .putString("conversationUrl", "")
                    .putString("phase", SelfRun3Coordinator.PHASE_SETUP)
                    .putString("status", "SelfRun 3 원장 · Drive 준비")
                    .putString("pendingModel", safe(model))
                    .putString("pendingReasoning", safe(reasoning))
                    .putString("lastErrorCode", "")
                    .putString("lastErrorMessage", "")
                    .putString("runDriveAccountId", driveAccountId())
                    .putString("runBaseFolderId", driveRunsBaseFolderId())
                    .putString("jobFolderId", "")
                    .putString("turnDocumentId", "")
                    .putString("turnDocumentUrl", "")
                    .putString(KEY_ATTACHMENTS, encodeAttachments(drafts))
                    .putString(KEY_ATTACHMENT_GRANT_CLEANUP, "[]")
                    .putInt("turn", 0)
                    .putBoolean("active", true)
                    .putBoolean("paused", false)
                    .putBoolean("userStopped", false)
                    .remove("driveSignalCursor")
                    .remove("driveSignalCursorSchemaVersion")
                    .remove("lastDriveSignalRaw")
                    .remove("lastDriveSignalTimestamp")
                    .remove("lastDriveSignalType")
                    .remove("pendingDriveSignalRaw")
                    .remove("pendingDriveSignalTimestamp")
                    .remove("pendingDriveSignalType")
                    .remove("submissionRetryKind")
                    .remove("submissionRetryReason")
                    .remove("submissionRetryDueAt")
                    .remove("submissionRetryAttempt")
                    .remove("submissionRetryReady")
                    .remove("activeCommandPrompt")
                    .remove("activeCommandKind")
                    .remove("commandAttempt")
                    .remove("awaitingCommandAck")
                    .remove("turnProtocolToken")
                    .remove("turnProtocolCompletionConsumed")
                    .remove("turnProtocolCompletionSource")
                    .remove("postProtocolDriveSyncStartedAt")
                    .remove("watchdogClaimName")
                    .remove("watchdogClaimState")
                    .remove("watchdogClaimCursor")
                    .remove("watchdogClaimAttempt")
                    .remove("terminalSideEffectPending")
                    .remove("terminalSideEffectType")
                    .remove("terminalSideEffectRunId")
                    .remove("terminalSideEffectCommitId"));
            syncHistory();
        }
    }

    void stopByUser() {
        synchronized (RUN_STATE_LOCK) {
            List<Attachment> prior = attachments();
            commitOrThrow(prefs.edit()
                    .putString(KEY_ATTACHMENTS, "[]")
                    .putString(KEY_ATTACHMENT_GRANT_CLEANUP, encodeAttachmentUris(prior))
                    .putBoolean("active", false)
                    .putBoolean("paused", false)
                    .putBoolean("userStopped", true)
                    .putString("phase", PHASE_IDLE)
                    .putString("status", "사용자 중지")
                    .putLong("phaseStartedAt", System.currentTimeMillis()));
            drainAttachmentGrantCleanupJournal();
            syncHistory();
        }
    }

    void clear() {
        synchronized (RUN_STATE_LOCK) {
            List<Attachment> prior = attachments();
            String account = driveAccountId();
            String id = driveRunsBaseFolderId();
            String name = driveRunsBaseFolderName();
            String url = driveRunsBaseFolderUrl();
            long boundAt = driveRunsBaseFolderBoundAt();
            new ProjectCatalog(app).clear();
            commitOrThrow(prefs.edit().clear()
                    .putString("driveAccountId", account)
                    .putString("driveRunsBaseFolderId", id)
                    .putString("driveRunsBaseFolderName", name)
                    .putString("driveRunsBaseFolderUrl", url)
                    .putLong("driveRunsBaseFolderBoundAt", boundAt)
                    .putString(KEY_ATTACHMENT_GRANT_CLEANUP, encodeAttachmentUris(prior)));
            drainAttachmentGrantCleanupJournal();
        }
    }

    void bindBaseFolder(String accountId, String id, String name, String url, long boundAt) {
        DriveApiClient.requireParent(id);
        if (!DriveApiClient.validOpaqueAccountId(accountId)) throw new IllegalArgumentException("Drive account permissionId required");
        commitOrThrow(prefs.edit()
                .putString("driveAccountId", accountId)
                .putString("driveRunsBaseFolderId", id)
                .putString("driveRunsBaseFolderName", safe(name))
                .putString("driveRunsBaseFolderUrl", safe(url))
                .putLong("driveRunsBaseFolderBoundAt", Math.max(0L, boundAt)));
    }

    void clearBaseFolderBinding() {
        commitOrThrow(prefs.edit().remove("driveAccountId").remove("driveRunsBaseFolderId")
                .remove("driveRunsBaseFolderName").remove("driveRunsBaseFolderUrl")
                .remove("driveRunsBaseFolderBoundAt"));
    }

    String runId() { return get("runId"); }
    long createdAt() { return prefs.getLong("createdAt", 0L); }
    long phaseStartedAt() { return prefs.getLong("phaseStartedAt", createdAt()); }
    String mode() { return getOr("mode", MODE_CHAT); }
    String taskMode() { return getOr("taskMode", mode()); }
    String projectUrl() { return get("projectUrl"); }
    String requirement() { return get("requirement"); }
    String conversationUrl() { return get("conversationUrl"); }
    String phase() { return getOr("phase", PHASE_IDLE); }
    String status() { return getOr("status", "대기"); }
    String pendingModel() { return get("pendingModel"); }
    String pendingReasoning() { return get("pendingReasoning"); }
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

    // V3 has no Drive title-signal protocol. These accessors remain empty for health/history compatibility.
    String lastDriveSignalRaw() { return ""; }
    String lastDriveSignalTimestamp() { return ""; }
    String lastDriveSignalType() { return ""; }
    String pendingDriveSignalRaw() { return ""; }
    String pendingDriveSignalTimestamp() { return ""; }
    String pendingDriveSignalType() { return ""; }
    String pendingNextInput() { return ""; }
    String submissionRetryKind() { return ""; }
    String submissionRetryReason() { return ""; }
    long submissionRetryDueAt() { return 0L; }
    int submissionRetryAttempt() { return 0; }
    boolean submissionRetryReady() { return false; }
    String activeCommandPrompt() { return ""; }
    String activeCommandKind() { return ""; }
    int commandAttempt() { return 0; }
    boolean awaitingCommandAck() { return false; }

    String defaultProjectUrl() { return canonicalStoredProjectUrl(get("defaultProjectUrl")); }
    static String canonicalStoredProjectUrl(String value) {
        if (value == null || value.isEmpty()) return "";
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(value);
        return ref == null ? value : ref.canonicalUrl;
    }

    void setDefaultProjectUrl(String value) {
        if (value == null || value.trim().isEmpty() || SelfRunScript.isGeneralChatUrl(value)) {
            put("defaultProjectUrl", "");
            return;
        }
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(value);
        if (ref == null) throw new IllegalArgumentException("trusted ChatGPT project URL required");
        put("defaultProjectUrl", ref.canonicalUrl);
    }

    void setTaskMode(String value) {
        if (!MODE_CHAT.equals(value) && !MODE_WORK.equals(value) && !MODE_HYBRID.equals(value)) {
            throw new IllegalArgumentException("unsupported task mode");
        }
        put("taskMode", value);
    }

    void setExecutionProjection(String executionMode, String model, String reasoning, String executionConversationUrl) {
        if (!MODE_CHAT.equals(executionMode) && !MODE_WORK.equals(executionMode)) {
            throw new IllegalArgumentException("CHAT or WORK execution mode required");
        }
        String url = safe(executionConversationUrl);
        if (!url.isEmpty()) {
            Uri uri = Uri.parse(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !("chatgpt.com".equalsIgnoreCase(uri.getHost()) || "www.chatgpt.com".equalsIgnoreCase(uri.getHost()))
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException("trusted HTTPS conversation URL required");
            }
            String conversationId = SelfRunScript.conversationId(url);
            if (conversationId.isEmpty()) throw new IllegalArgumentException("conversation identity required");
            url = "https://chatgpt.com/c/" + conversationId;
        }
        synchronized (RUN_STATE_LOCK) {
            if (executionMode.equals(mode()) && safe(model).equals(pendingModel())
                    && safe(reasoning).equals(pendingReasoning()) && url.equals(conversationUrl())) return;
            commitOrThrow(prefs.edit().putString("mode", executionMode)
                    .putString("pendingModel", safe(model))
                    .putString("pendingReasoning", safe(reasoning))
                    .putString("conversationUrl", url));
            syncHistory();
        }
    }

    void setPhase(String value) {
        commitOrThrow(prefs.edit().putString("phase", safe(value))
                .putLong("phaseStartedAt", System.currentTimeMillis()));
        syncHistory();
    }
    void setStatus(String value) { put("status", value); }
    void setPendingModel(String value) { put("pendingModel", value); }
    void setPendingReasoning(String value) { put("pendingReasoning", value); }
    void setLastError(String code, String message) {
        commitOrThrow(prefs.edit().putString("lastErrorCode", safe(code)).putString("lastErrorMessage", safe(message)));
        syncHistory();
    }
    void clearLastError() { setLastError("", ""); }
    void setTurn(int value) { commitOrThrow(prefs.edit().putInt("turn", Math.max(0, value))); syncHistory(); }
    void setPaused(boolean value) { commitOrThrow(prefs.edit().putBoolean("paused", value)); syncHistory(); }
    void setActive(boolean value) { commitOrThrow(prefs.edit().putBoolean("active", value)); syncHistory(); }
    void setUserStopped(boolean value) { commitOrThrow(prefs.edit().putBoolean("userStopped", value)); syncHistory(); }

    static boolean canCaptureConversationUrl(String projectUrl, String value) {
        if (SelfRunScript.isGeneralChatUrl(projectUrl)) {
            return SelfRunScript.isGeneralChatUrl(value) && !SelfRunScript.conversationId(value).isEmpty();
        }
        ProjectUrlPolicy.ProjectRef expected = ProjectUrlPolicy.parseProject(projectUrl);
        ProjectUrlPolicy.ProjectRef actual = ProjectUrlPolicy.parseProject(value);
        return expected != null && actual != null && !actual.conversationId.isEmpty()
                && expected.projectId.equals(actual.projectId);
    }

    void captureConversationUrl(String value) {
        if (!conversationUrl().isEmpty() || !canCaptureConversationUrl(projectUrl(), value)) return;
        commitOrThrow(prefs.edit().putString("conversationUrl", safe(value)));
        syncHistory();
    }

    List<Attachment> attachments() { return decodeAttachments(get(KEY_ATTACHMENTS)); }
    int attachmentCount() { return attachments().size(); }
    boolean hasAttachments() { return attachmentCount() > 0; }
    boolean allAttachmentsCommitted() {
        for (Attachment item : attachments()) if (!item.committed()) return false;
        return true;
    }
    Attachment nextUncommittedAttachment() {
        for (Attachment item : attachments()) if (!item.committed()) return item;
        return null;
    }

    void reserveAttachmentFileId(int index, String fileId) {
        DriveApiClient.requireParent(fileId);
        synchronized (RUN_STATE_LOCK) {
            Attachment item = requireAttachment(index);
            if (item.committed()) return;
            if (!item.driveFileId.isEmpty() && !item.driveFileId.equals(fileId)) {
                throw new IllegalStateException("attachment already owns a different Drive id");
            }
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
                replaceAttachment(new Attachment(item.index, item.uri, item.name, item.mimeType, item.size,
                        item.driveFileId, ATTACHMENT_COMMITTED, item.uploadAttempts));
            }
            releaseCommittedAttachmentPermissions();
            syncHistory();
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

    void prepareAttachmentGrantHandoff(List<Attachment> attachments) {
        List<Attachment> drafts = normalizeDrafts(attachments);
        commitOrThrow(prefs.edit().putString(KEY_ATTACHMENT_GRANT_CLEANUP, encodeAttachmentUris(drafts)));
    }

    void cancelAttachmentGrantHandoff() { drainAttachmentGrantCleanupJournal(); }

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
            if (attachments != null) for (Attachment item : attachments) {
                array.put(new JSONObject().put("index", item.index).put("uri", item.uri)
                        .put("name", item.name).put("mimeType", item.mimeType).put("size", item.size)
                        .put("driveFileId", item.driveFileId).put("stage", item.stage)
                        .put("uploadAttempts", item.uploadAttempts));
            }
            return array.toString();
        } catch (Throwable error) {
            throw new IllegalStateException("attachment state encode failed", error);
        }
    }

    private static List<Attachment> decodeAttachments(String raw) {
        ArrayList<Attachment> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(raw);
            if (array.length() > MAX_ATTACHMENTS_PER_RUN) throw new IllegalStateException("too many attachments");
            Set<Integer> indexes = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.getJSONObject(i);
                Attachment item = new Attachment(json.getInt("index"), json.optString("uri", ""),
                        json.optString("name", ""), json.optString("mimeType", ""), json.optLong("size", -1L),
                        json.optString("driveFileId", ""), json.optString("stage", ATTACHMENT_PENDING),
                        json.optInt("uploadAttempts", 0));
                if (item.index < 0 || !indexes.add(item.index) || item.name.isEmpty() || item.name.length() > 180
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
        } catch (IllegalStateException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("attachment state decode failed", error);
        }
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
            if (items.get(i).index == updated.index) {
                items.set(i, updated);
                replaced = true;
                break;
            }
        }
        if (!replaced) throw new IllegalStateException("attachment index not found");
        commitOrThrow(prefs.edit().putString(KEY_ATTACHMENTS, encodeAttachments(items)));
    }

    private static String encodeAttachmentUris(List<Attachment> attachments) {
        JSONArray array = new JSONArray();
        if (attachments != null) for (Attachment item : attachments) {
            if (item != null && !item.uri.isEmpty()) array.put(item.uri);
        }
        return array.toString();
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
        } catch (Throwable ignored) { }
        return result;
    }

    private void drainAttachmentGrantCleanupJournal() {
        synchronized (RUN_STATE_LOCK) {
            List<String> pending = decodeAttachmentGrantCleanupJournal();
            if (pending.isEmpty()) {
                if (!"[]".equals(get(KEY_ATTACHMENT_GRANT_CLEANUP))) {
                    commitOrThrow(prefs.edit().putString(KEY_ATTACHMENT_GRANT_CLEANUP, "[]"));
                }
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
        } catch (Throwable ignored) { }
        return false;
    }

    private boolean releaseAttachmentPermission(String value) {
        if (value == null || value.isEmpty()) return true;
        try {
            Uri uri = Uri.parse(value);
            if (!"content".equals(uri.getScheme())) return true;
            try {
                app.getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) { }
            return !hasPersistedReadGrant(uri);
        } catch (Throwable ignored) {
            return false;
        }
    }

    void syncHistory() { history.sync(this); }

    private void put(String key, String value) {
        commitOrThrow(prefs.edit().putString(key, safe(value)));
        syncHistory();
    }

    private String get(String key) { return prefs.getString(key, ""); }
    private String getOr(String key, String fallback) { return prefs.getString(key, fallback); }
    private static String safe(String value) { return value == null ? "" : value; }

    private static void commitOrThrow(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("durable SelfRun 3 projection write failed");
    }
}
