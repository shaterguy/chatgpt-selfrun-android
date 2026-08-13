package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

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
    static final String PHASE_DRIVE_TURN_DOCUMENT_CREATE = "DRIVE_TURN_DOCUMENT_CREATE";
    static final String PHASE_DRIVE_DOCUMENT_INIT = "DRIVE_DOCUMENT_INIT";
    static final String PHASE_DRIVE_DOCUMENT_READBACK = "DRIVE_DOCUMENT_READBACK";
    static final String PHASE_BOOTSTRAP = "BOOTSTRAP";
    static final String PHASE_BOOTSTRAP_MODEL = "BOOTSTRAP_MODEL";
    static final String PHASE_BOOTSTRAP_REASONING = "BOOTSTRAP_REASONING";
    static final String PHASE_BOOTSTRAP_SEND = "BOOTSTRAP_SEND";
    static final String PHASE_WAIT_DRIVE_COMMIT = "WAIT_DRIVE_COMMIT";
    static final String PHASE_DRIVE_COMMIT_GUARD = "DRIVE_COMMIT_GUARD";
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

    static final String EVENT_DETECTED = "EVENT_DETECTED";
    static final String EVENT_GUARDING = "EVENT_GUARDING";
    static final String SUBMISSION_STARTED = "SUBMISSION_STARTED";
    static final String SUBMISSION_CONFIRMED = "SUBMISSION_CONFIRMED";
    static final String EVENT_CONSUMED = "EVENT_CONSUMED";
    static final String BOOTSTRAP_NOT_STARTED = "BOOTSTRAP_NOT_STARTED";
    static final String BOOTSTRAP_SUBMISSION_STARTED = "BOOTSTRAP_SUBMISSION_STARTED";
    static final String BOOTSTRAP_SUBMISSION_CONFIRMED = "BOOTSTRAP_SUBMISSION_CONFIRMED";

    private final SharedPreferences prefs;
    private final SelfRunHistoryStore history;

    SelfRunStore(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE);
        history = new SelfRunHistoryStore(app);
    }

    void start(String runId, String mode, String projectUrl, String requirement) {
        synchronized (RUN_STATE_LOCK) {
            if (!DriveApiClient.validOpaqueAccountId(driveAccountId())
                    || !DriveApiClient.validFileId(driveRunsBaseFolderId())) {
                throw new IllegalStateException("Drive base binding required before a run starts");
            }
            startLocked(runId, mode, projectUrl, requirement);
        }
    }

    private void startLocked(String runId, String mode, String projectUrl, String requirement) {
        long now = System.currentTimeMillis();
        commitOrThrow(prefs.edit()
                .putString("runId", safe(runId)).putLong("createdAt", now).putLong("phaseStartedAt", now)
                .putString("mode", safe(mode)).putString("projectUrl", safe(projectUrl))
                .putString("requirement", safe(requirement)).putString("conversationUrl", "")
                .putString("phase", PHASE_DRIVE_ACCOUNT_CHECK).putString("status", "Drive 계정 확인 준비")
                .putString("role", "PLANNER").putString("pendingModel", MODE_WORK.equals(mode) ? "sol" : "")
                .putString("pendingReasoning", MODE_WORK.equals(mode) ? "xhigh" : "")
                .putString("lastSignal", "").putString("lastErrorCode", "").putString("lastErrorMessage", "")
                .putString("runDriveAccountId", driveAccountId())
                .putString("runBaseFolderId", driveRunsBaseFolderId())
                .putString("jobFolderId", "").putString("turnDocumentId", "").putString("turnDocumentUrl", "")
                .putInt("driveProtocolVersion", 1).putInt("expectedTurn", 1).putInt("turn", 0)
                .putString("lastSeenDriveVersion", "").putString("lastSeenModifiedTime", "")
                .putLong("lastConsumedEventSeq", 0L).putString("lastCommittedAt", "")
                .putLong("pendingEventSeq", 0L).putInt("pendingTurn", 0).putString("pendingSignalRaw", "")
                .putString("pendingCommitId", "").putLong("commitDetectedAt", 0L).putLong("guardDueAt", 0L)
                .putString("submissionState", EVENT_CONSUMED).putLong("submissionStartedAt", 0L)
                .putString("lastSubmittedCommitId", "")
                .putString("creationStage", CREATION_NONE).putLong("bootstrapSubmittedAt", 0L)
                .putString("bootstrapSubmissionState", BOOTSTRAP_NOT_STARTED)
                .putString("pausedFromPhase", "")
                .putBoolean("terminalSideEffectPending", false).putString("terminalSideEffectType", "")
                .putString("terminalSideEffectRunId", "").putString("terminalSideEffectCommitId", "")
                .putBoolean("resumeNeedsContinuation", false).putBoolean("active", true)
                .putBoolean("paused", false).putBoolean("userStopped", false));
        syncHistory();
    }

    void stopByUser() {
        synchronized (RUN_STATE_LOCK) {
            commitOrThrow(prefs.edit().putBoolean("active", false).putBoolean("paused", false)
                    .putBoolean("userStopped", true).putString("phase", PHASE_IDLE)
                    .putString("status", "사용자 중지").putLong("phaseStartedAt", System.currentTimeMillis()));
            syncHistory();
        }
    }

    void clear() {
        String defaultProject = defaultProjectUrl(), account = driveAccountId();
        String id = driveRunsBaseFolderId(), name = driveRunsBaseFolderName(), url = driveRunsBaseFolderUrl();
        long boundAt = driveRunsBaseFolderBoundAt();
        commitOrThrow(prefs.edit().clear().putString("defaultProjectUrl", defaultProject).putString("driveAccountId", account)
                .putString("driveRunsBaseFolderId", id).putString("driveRunsBaseFolderName", name)
                .putString("driveRunsBaseFolderUrl", url).putLong("driveRunsBaseFolderBoundAt", boundAt));
    }

    void bindBaseFolder(String accountId, String id, String name, String url, long boundAt) {
        DriveApiClient.requireParent(id);
        if (!DriveApiClient.validOpaqueAccountId(accountId)) {
            throw new IllegalArgumentException("Drive account permissionId required");
        }
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
    String defaultProjectUrl() { return get("defaultProjectUrl"); }
    String requirement() { return get("requirement"); }
    String conversationUrl() { return get("conversationUrl"); }
    String phase() { return getOr("phase", PHASE_IDLE); }
    String status() { return getOr("status", "대기"); }
    String role() { return get("role"); }
    String pendingModel() { return get("pendingModel"); }
    String pendingReasoning() { return get("pendingReasoning"); }
    String lastSignal() { return get("lastSignal"); }
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
    int driveProtocolVersion() { return prefs.getInt("driveProtocolVersion", 1); }
    int expectedTurn() { return prefs.getInt("expectedTurn", 1); }
    String lastSeenDriveVersion() { return get("lastSeenDriveVersion"); }
    String lastSeenModifiedTime() { return get("lastSeenModifiedTime"); }
    long lastConsumedEventSeq() { return prefs.getLong("lastConsumedEventSeq", 0L); }
    String lastCommittedAt() { return get("lastCommittedAt"); }
    long pendingEventSeq() { return prefs.getLong("pendingEventSeq", 0L); }
    int pendingTurn() { return prefs.getInt("pendingTurn", 0); }
    String pendingSignalRaw() { return get("pendingSignalRaw"); }
    String pendingCommitId() { return get("pendingCommitId"); }
    long commitDetectedAt() { return prefs.getLong("commitDetectedAt", 0L); }
    long guardDueAt() { return prefs.getLong("guardDueAt", 0L); }
    String submissionState() { return get("submissionState"); }
    long submissionStartedAt() { return prefs.getLong("submissionStartedAt", 0L); }
    String lastSubmittedCommitId() { return get("lastSubmittedCommitId"); }
    String creationStage() { return getOr("creationStage", CREATION_NONE); }
    long bootstrapSubmittedAt() { return prefs.getLong("bootstrapSubmittedAt", 0L); }
    String bootstrapSubmissionState() { return getOr("bootstrapSubmissionState", BOOTSTRAP_NOT_STARTED); }
    String pausedFromPhase() { return get("pausedFromPhase"); }
    boolean resumeNeedsContinuation() { return prefs.getBoolean("resumeNeedsContinuation", false); }
    boolean terminalSideEffectPending() { return prefs.getBoolean("terminalSideEffectPending", false); }
    String terminalSideEffectType() { return get("terminalSideEffectType"); }
    String terminalSideEffectRunId() { return get("terminalSideEffectRunId"); }
    String terminalSideEffectCommitId() { return get("terminalSideEffectCommitId"); }

    void setDefaultProjectUrl(String value) { put("defaultProjectUrl", value); }
    void setPhase(String value) { commitOrThrow(prefs.edit().putString("phase", safe(value)).putLong("phaseStartedAt", System.currentTimeMillis())); syncHistory(); }
    void setStatus(String value) { put("status", value); }
    void setRole(String value) { put("role", value); }
    void setPendingModel(String value) { put("pendingModel", value); }
    void setPendingReasoning(String value) { put("pendingReasoning", value); }
    void setLastSignal(String value) { put("lastSignal", value); }
    void setLastError(String code, String message) { commitOrThrow(prefs.edit().putString("lastErrorCode", safe(code)).putString("lastErrorMessage", safe(message))); syncHistory(); }
    void clearLastError() { setLastError("", ""); }
    void setTurn(int value) { commitOrThrow(prefs.edit().putInt("turn", value)); syncHistory(); }
    void setPaused(boolean value) { commitOrThrow(prefs.edit().putBoolean("paused", value)); syncHistory(); }
    void setActive(boolean value) { commitOrThrow(prefs.edit().putBoolean("active", value)); syncHistory(); }
    void setUserStopped(boolean value) { commitOrThrow(prefs.edit().putBoolean("userStopped", value)); syncHistory(); }
    void setCreationStage(String value) { commitOrThrow(prefs.edit().putString("creationStage", value)); }

    void markBootstrapSubmissionStarted() {
        if (!BOOTSTRAP_NOT_STARTED.equals(bootstrapSubmissionState())) {
            throw new IllegalStateException("bootstrap submission already started");
        }
        commitOrThrow(prefs.edit().putString("bootstrapSubmissionState", BOOTSTRAP_SUBMISSION_STARTED)
                .putLong("bootstrapSubmittedAt", System.currentTimeMillis()));
    }

    void confirmBootstrap(String conversationUrl) {
        if (!BOOTSTRAP_SUBMISSION_STARTED.equals(bootstrapSubmissionState())
                || SelfRunScript.conversationId(conversationUrl).isEmpty()) {
            throw new IllegalStateException("bootstrap confirmation invariant failed");
        }
        commitOrThrow(prefs.edit().putString("conversationUrl", safe(conversationUrl))
                .putString("bootstrapSubmissionState", BOOTSTRAP_SUBMISSION_CONFIRMED)
                .putString("phase", PHASE_WAIT_DRIVE_COMMIT)
                .putString("status", "Drive 턴 완료 기록 대기")
                .putLong("phaseStartedAt", System.currentTimeMillis()));
        syncHistory();
    }

    void reserveJobFolderId(String id) {
        DriveApiClient.requireParent(id);
        commitOrThrow(prefs.edit().putString("jobFolderId", id)
                .putString("creationStage", CREATION_FOLDER_ID_RESERVED));
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
    void updateDriveSeen(String version, String modifiedTime) { commitOrThrow(prefs.edit().putString("lastSeenDriveVersion", safe(version)).putString("lastSeenModifiedTime", safe(modifiedTime))); }

    void detectEvent(DriveCommitParser.Commit commit, long detectedAt, long dueAt) {
        commitOrThrow(prefs.edit().putLong("pendingEventSeq", commit.eventSeq).putInt("pendingTurn", commit.turn)
                .putString("pendingSignalRaw", commit.signalRaw).putString("pendingCommitId", commit.id())
                .putString("lastCommittedAt", commit.committedAt).putLong("commitDetectedAt", detectedAt)
                .putLong("guardDueAt", dueAt).putString("submissionState", EVENT_DETECTED));
        syncHistory();
    }

    void markGuarding() { commitOrThrow(prefs.edit().putString("submissionState", EVENT_GUARDING)); }
    void markSubmissionStarted() { commitOrThrow(prefs.edit().putString("submissionState", SUBMISSION_STARTED)
            .putLong("submissionStartedAt", System.currentTimeMillis())); }

    void markSubmissionConfirmed(String commitId) {
        if (!safe(commitId).equals(pendingCommitId())) {
            throw new IllegalStateException("confirmed commit does not match pending event");
        }
        commitOrThrow(prefs.edit().putString("submissionState", SUBMISSION_CONFIRMED)
                .putString("lastSubmittedCommitId", safe(commitId)));
    }

    void consumeContinuation(String commitId) {
        if (!SUBMISSION_CONFIRMED.equals(submissionState())
                || !safe(commitId).equals(lastSubmittedCommitId())) {
            throw new IllegalStateException("continuation must be confirmed before consumption");
        }
        int completedTurn = pendingTurn();
        commitOrThrow(prefs.edit().putLong("lastConsumedEventSeq", pendingEventSeq())
                .putInt("expectedTurn", completedTurn + 1).putInt("turn", completedTurn)
                .putLong("pendingEventSeq", 0L).putInt("pendingTurn", 0).putString("pendingSignalRaw", "")
                .putString("pendingCommitId", "").putLong("commitDetectedAt", 0L).putLong("guardDueAt", 0L)
                .putString("submissionState", EVENT_CONSUMED).putLong("submissionStartedAt", 0L)
                .putBoolean("resumeNeedsContinuation", false).putString("phase", PHASE_WAIT_DRIVE_COMMIT)
                .putString("status", "Drive commit 대기 · 턴 " + (completedTurn + 1))
                .putLong("phaseStartedAt", System.currentTimeMillis()));
        syncHistory();
    }

    void consumeTerminal(DriveCommitParser.Commit commit) {
        SharedPreferences.Editor editor = prefs.edit().putLong("lastConsumedEventSeq", commit.eventSeq)
                .putString("lastCommittedAt", commit.committedAt)
                .putInt("turn", commit.turn).putString("lastSignal", commit.signalRaw)
                .putLong("pendingEventSeq", 0L).putInt("pendingTurn", 0).putString("pendingSignalRaw", "")
                .putString("pendingCommitId", "").putString("submissionState", EVENT_CONSUMED)
                .putLong("submissionStartedAt", 0L).putLong("phaseStartedAt", System.currentTimeMillis());
        switch (commit.signal.type) {
            case DONE -> editor.putString("phase", PHASE_DONE).putString("status", "SelfRun Drive 완료")
                    .putBoolean("active", false).putBoolean("paused", false);
            case PAUSE -> editor.putString("pausedFromPhase", PHASE_WAIT_DRIVE_COMMIT)
                    .putBoolean("resumeNeedsContinuation", true).putBoolean("paused", true)
                    .putString("phase", PHASE_PAUSED).putString("status", "SelfRun Drive 일시정지");
            case USER_ACTION -> editor.putString("pausedFromPhase", PHASE_WAIT_DRIVE_COMMIT)
                    .putBoolean("resumeNeedsContinuation", true).putBoolean("paused", true)
                    .putString("phase", PHASE_PAUSED)
                    .putString("status", "사용자 조치 필요 · " + safe(commit.signal.actionId));
            default -> throw new IllegalArgumentException("terminal signal required");
        }
        editor.putBoolean("terminalSideEffectPending", true)
                .putString("terminalSideEffectType", commit.signal.type.name())
                .putString("terminalSideEffectRunId", runId())
                .putString("terminalSideEffectCommitId", commit.id());
        commitOrThrow(editor);
        syncHistory();
    }

    void resumeTerminalWithContinuation() {
        long sequence = lastConsumedEventSeq();
        int completedTurn = turn();
        String id = runId() + ":" + completedTurn + ":" + sequence + ":resume";
        commitOrThrow(prefs.edit().putLong("pendingEventSeq", sequence).putInt("pendingTurn", completedTurn)
                .putString("pendingSignalRaw", SelfRunProtocol.continuation(runId()))
                .putString("pendingCommitId", id).putLong("commitDetectedAt", System.currentTimeMillis())
                .putLong("guardDueAt", System.currentTimeMillis()).putString("submissionState", EVENT_GUARDING)
                .putBoolean("resumeNeedsContinuation", false).putBoolean("paused", false)
                .putBoolean("active", true).putBoolean("userStopped", false)
                .putString("phase", PHASE_SEND_CONTINUE)
                .putLong("phaseStartedAt", System.currentTimeMillis()));
        syncHistory();
    }

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
                    .putString("terminalSideEffectType", "")
                    .putString("terminalSideEffectRunId", "")
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

    void syncHistory() { history.sync(this); }
    private void put(String key, String value) { commitOrThrow(prefs.edit().putString(key, safe(value))); syncHistory(); }
    private String get(String key) { return prefs.getString(key, ""); }
    private String getOr(String key, String fallback) { return prefs.getString(key, fallback); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static void commitOrThrow(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("durable SelfRun Drive state write failed");
    }
}
