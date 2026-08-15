package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

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
    static final String PHASE_RESUME_BASELINE = "RESUME_BASELINE";
    static final String PHASE_READ_NEXT_CONTROL = "READ_NEXT_CONTROL";
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
    static final String RETRY_BOOTSTRAP = "BOOTSTRAP";
    static final String RETRY_CONTINUE = "CONTINUE";

    private final SharedPreferences prefs;
    private final SelfRunHistoryStore history;
    private final Context app;

    SelfRunStore(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE);
        history = new SelfRunHistoryStore(app);
    }

    void start(String runId, String mode, String projectUrl, String requirement) {
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
            startLocked(runId, mode, target, requirement);
        }
    }

private void startLocked(String runId,String mode,String projectUrl,String requirement){
 long now=System.currentTimeMillis();
 commitOrThrow(prefs.edit().putString("runId",safe(runId)).putLong("createdAt",now).putLong("phaseStartedAt",now)
  .putString("mode",safe(mode)).putString("projectUrl",safe(projectUrl)).putString("requirement",safe(requirement)).putString("conversationUrl","")
  .putString("phase",PHASE_DRIVE_ACCOUNT_CHECK).putString("status","Drive 계정 확인 준비").putString("role","PLANNER")
  .putString("pendingModel",MODE_WORK.equals(mode)?"sol":"").putString("pendingReasoning",MODE_WORK.equals(mode)?"xhigh":"")
  .putString("lastSignal","").putString("lastErrorCode","").putString("lastErrorMessage","").putString("runDriveAccountId",driveAccountId()).putString("runBaseFolderId",driveRunsBaseFolderId())
  .putString("jobFolderId","").putString("turnDocumentId","").putString("turnDocumentUrl","").putInt("turn",0).putString("lastSeenDriveVersion","").putString("lastSeenModifiedTime","")
  .putInt("driveSignalCursor",0).putString("lastDriveSignalRaw","").putString("lastDriveSignalTimestamp","").putString("lastDriveSignalType","")
  .putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","").putLong("commitDetectedAt",0L).putLong("guardDueAt",0L)
  .putString("activeCommandPrompt","").putString("activeCommandKind","").putInt("commandAttempt",0).putBoolean("awaitingCommandAck",false)
  .putString("submissionRetryKind","").putString("submissionRetryReason","").putLong("submissionRetryDueAt",0L).putInt("submissionRetryAttempt",0).putBoolean("submissionRetryReady",false)
  .putString("creationStage",CREATION_NONE).putString("pausedFromPhase","").putBoolean("resumeNeedsContinuation",false)
  .putBoolean("terminalSideEffectPending",false).putString("terminalSideEffectType","").putString("terminalSideEffectRunId","").putString("terminalSideEffectCommitId","")
  .putBoolean("active",true).putBoolean("paused",false).putBoolean("userStopped",false)
  .remove("driveProtocolVersion").remove("expectedTurn").remove("lastConsumedEventSeq").remove("lastCommittedAt").remove("pendingEventSeq").remove("pendingTurn").remove("pendingSignalRaw").remove("pendingCommitId")
  .remove("submissionState").remove("submissionStartedAt").remove("submissionBaselineCount").remove("lastSubmittedCommitId").remove("bootstrapSubmittedAt").remove("bootstrapSubmissionState"));
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
        String account = driveAccountId();
        String id = driveRunsBaseFolderId(), name = driveRunsBaseFolderName(), url = driveRunsBaseFolderUrl();
        long boundAt = driveRunsBaseFolderBoundAt();
        new ProjectCatalog(app).clear();
        commitOrThrow(prefs.edit().clear().putString("driveAccountId", account)
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
    String lastSeenDriveVersion() { return get("lastSeenDriveVersion"); }
    String lastSeenModifiedTime() { return get("lastSeenModifiedTime"); }
    int driveSignalCursor() { return prefs.getInt("driveSignalCursor", 0); }
    String lastDriveSignalRaw() { return get("lastDriveSignalRaw"); }
    String lastDriveSignalTimestamp() { return get("lastDriveSignalTimestamp"); }
    String lastDriveSignalType() { return get("lastDriveSignalType"); }
    String pendingDriveSignalRaw() { return get("pendingDriveSignalRaw"); }
    String pendingDriveSignalTimestamp() { return get("pendingDriveSignalTimestamp"); }
    String pendingDriveSignalType() { return get("pendingDriveSignalType"); }
    long commitDetectedAt() { return prefs.getLong("commitDetectedAt", 0L); }
    long guardDueAt() { return prefs.getLong("guardDueAt", 0L); }
    String activeCommandPrompt() { return get("activeCommandPrompt"); }
    String activeCommandKind() { return get("activeCommandKind"); }
    int commandAttempt() { return prefs.getInt("commandAttempt", 0); }
    boolean awaitingCommandAck() { return prefs.getBoolean("awaitingCommandAck", false); }
    String submissionRetryKind() { return get("submissionRetryKind"); }
    String submissionRetryReason() { return get("submissionRetryReason"); }
    long submissionRetryDueAt() { return prefs.getLong("submissionRetryDueAt", 0L); }
    int submissionRetryAttempt() { return prefs.getInt("submissionRetryAttempt", 0); }
    boolean submissionRetryReady() { return prefs.getBoolean("submissionRetryReady", false); }
    boolean hasSubmissionRetry() {
        return (RETRY_BOOTSTRAP.equals(submissionRetryKind()) || RETRY_CONTINUE.equals(submissionRetryKind()))
                && submissionRetryDueAt() > 0L;
    }
    boolean submissionRetryDue() {
        return hasSubmissionRetry() && System.currentTimeMillis() >= submissionRetryDueAt();
    }
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

    void setDefaultProjectUrl(String value) {
        if (value == null || value.trim().isEmpty() || SelfRunScript.isGeneralChatUrl(value)) { put("defaultProjectUrl", ""); return; }
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(value);
        if (ref == null) throw new IllegalArgumentException("trusted ChatGPT project URL required");
        put("defaultProjectUrl", ref.canonicalUrl);
    }
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

void baselineDriveSignals(int cursor,DriveSignalParser.Event latest){SharedPreferences.Editor e=prefs.edit().putInt("driveSignalCursor",Math.max(0,cursor));putLatest(e,latest);commitOrThrow(e);syncHistory();}
void beginCommandAttempt(String kind,String prompt){if(!(RETRY_BOOTSTRAP.equals(kind)||RETRY_CONTINUE.equals(kind))||safe(prompt).isEmpty())throw new IllegalArgumentException("valid command attempt required");int n=commandAttempt()==Integer.MAX_VALUE?Integer.MAX_VALUE:commandAttempt()+1;commitOrThrow(prefs.edit().putString("activeCommandKind",kind).putString("activeCommandPrompt",prompt).putInt("commandAttempt",n).putBoolean("awaitingCommandAck",false));syncHistory();}
String commandMarkerId(){return runId()+":"+commandAttempt();}
void markCommandSubmitted(String kind,long due){if(!kind.equals(activeCommandKind())||activeCommandPrompt().isEmpty()||due<=0)throw new IllegalStateException("prepared command required");int n=submissionRetryAttempt()==Integer.MAX_VALUE?Integer.MAX_VALUE:submissionRetryAttempt()+1;commitOrThrow(prefs.edit().putBoolean("awaitingCommandAck",true).putString("submissionRetryKind",kind).putString("submissionRetryReason","COMMAND_RECEIVED_PENDING").putLong("submissionRetryDueAt",due).putInt("submissionRetryAttempt",n).putBoolean("submissionRetryReady",false).putString("phase",PHASE_WAIT_DRIVE_COMMIT).putString("status","Drive COMMAND_RECEIVED 대기 · 5분 후 미수신 시 재제출").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();}
void prepareCommandRetry(){if(!awaitingCommandAck()||!hasSubmissionRetry()||!submissionRetryDue())throw new IllegalStateException("command ACK retry is not due");String k=submissionRetryKind();String ph=RETRY_BOOTSTRAP.equals(k)?PHASE_BOOTSTRAP_SEND:PHASE_SEND_CONTINUE;commitOrThrow(prefs.edit().putBoolean("awaitingCommandAck",false).putString("activeCommandPrompt","").putString("activeCommandKind","").putString("submissionRetryKind","").putString("submissionRetryReason","").putLong("submissionRetryDueAt",0L).putBoolean("submissionRetryReady",false).putString("phase",ph).putString("status","COMMAND_RECEIVED 미수신 · 동일 명령 재제출 준비").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();}
void applyDriveSignals(List<DriveSignalParser.Event> events,long detectedAt,long guardMs){if(events==null||events.isEmpty())return;SharedPreferences.Editor e=prefs.edit();boolean awaiting=awaitingCommandAck();int rank=PHASE_DONE.equals(phase())||PHASE_PAUSED.equals(phase())?3:PHASE_DRIVE_COMMIT_GUARD.equals(phase())?2:0;for(DriveSignalParser.Event x:events){e.putInt("driveSignalCursor",x.cursor);putLatest(e,x);if(awaiting){awaiting=false;clearCommandWait(e);}switch(x.type){case COMMAND_RECEIVED->{if(rank<2)e.putString("status","Drive COMMAND_RECEIVED 확인 · 작업 진행 중");}case TURN_COMPLETED->{if(rank<2){rank=2;e.putString("pendingDriveSignalRaw",x.raw).putString("pendingDriveSignalTimestamp",x.timestamp).putString("pendingDriveSignalType",x.type.name()).putLong("commitDetectedAt",detectedAt).putLong("guardDueAt",detectedAt+guardMs).putString("phase",PHASE_DRIVE_COMMIT_GUARD).putString("status","Drive TURN_COMPLETED 확인 · 안전 지연");}}case USER_ACTION_REQUIRED->{rank=3;clearCommandWait(e);pauseEvent(e,x,"사용자 조치 필요");}case PAUSED->{rank=3;clearCommandWait(e);pauseEvent(e,x,"SelfRun Drive 일시정지");}case DONE->{rank=3;clearCommandWait(e);e.putBoolean("active",false).putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_DONE).putString("status","SelfRun Drive 완료");terminal(e,x);}}}e.putBoolean("awaitingCommandAck",awaiting).putLong("phaseStartedAt",System.currentTimeMillis());commitOrThrow(e);syncHistory();}
void repairGuard(long now,long guardMs){SharedPreferences.Editor e=prefs.edit();String raw=pendingDriveSignalRaw(),ts=pendingDriveSignalTimestamp();if(!DriveSignalParser.Type.TURN_COMPLETED.name().equals(pendingDriveSignalType())||raw.isEmpty()){if(DriveSignalParser.Type.TURN_COMPLETED.name().equals(lastDriveSignalType())&&!lastDriveSignalRaw().isEmpty()){raw=lastDriveSignalRaw();ts=lastDriveSignalTimestamp();}else{int cursor=driveSignalCursor();int recoveryCursor=cursor>0?driveSignalCursor()-1:Integer.MAX_VALUE;commitOrThrow(e.putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","").putLong("commitDetectedAt",0L).putLong("guardDueAt",0L).putInt("driveSignalCursor",recoveryCursor).putString("lastSeenDriveVersion","").putString("lastSeenModifiedTime","").putString("phase",PHASE_WAIT_DRIVE_COMMIT).putString("status",cursor>0?"Drive 완료 signal guard 손상 · 직전 신호 재검증":"Drive 완료 signal guard 손상 · 현재 문서 baseline 재확인").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();return;}}commitOrThrow(e.putString("pendingDriveSignalRaw",raw).putString("pendingDriveSignalTimestamp",ts).putString("pendingDriveSignalType",DriveSignalParser.Type.TURN_COMPLETED.name()).putLong("commitDetectedAt",now).putLong("guardDueAt",now+guardMs).putString("phase",PHASE_DRIVE_COMMIT_GUARD).putString("status","Drive TURN_COMPLETED guard 복구"));syncHistory();}
void beginManualResumeOverride(){commitOrThrow(clearCommandWait(prefs.edit()).putBoolean("terminalSideEffectPending",false).putString("terminalSideEffectType","").putString("terminalSideEffectRunId","").putString("terminalSideEffectCommitId","").putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","").putLong("commitDetectedAt",0L).putLong("guardDueAt",0L).putBoolean("paused",false).putBoolean("active",true).putBoolean("userStopped",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_RESUME_BASELINE).putString("status","사용자 재개 override · Drive 최신 신호 baseline 확인").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();}
void baselineManualResume(int cursor,DriveSignalParser.Event latest){SharedPreferences.Editor e=clearCommandWait(prefs.edit()).putInt("driveSignalCursor",Math.max(0,cursor)).putBoolean("paused",false).putBoolean("active",true).putBoolean("userStopped",false).putString("phase",PHASE_SEND_CONTINUE).putString("status","사용자 재개 override · CONTINUE 강제 제출 준비").putLong("phaseStartedAt",System.currentTimeMillis());putLatest(e,latest);commitOrThrow(e);syncHistory();}
void captureConversationUrl(String value){
    ProjectUrlPolicy.ProjectRef expected=ProjectUrlPolicy.parseProject(projectUrl()),actual=ProjectUrlPolicy.parseProject(value);
    if(!conversationUrl().isEmpty()||expected==null||actual==null||actual.conversationId.isEmpty()||!expected.projectId.equals(actual.projectId))return;
    commitOrThrow(prefs.edit().putString("conversationUrl",safe(value)));syncHistory();
}
private static SharedPreferences.Editor clearCommandWait(SharedPreferences.Editor e){return e.putBoolean("awaitingCommandAck",false).putString("activeCommandPrompt","").putString("activeCommandKind","").putString("submissionRetryKind","").putString("submissionRetryReason","").putLong("submissionRetryDueAt",0L).putInt("submissionRetryAttempt",0).putBoolean("submissionRetryReady",false);}
private static void putLatest(SharedPreferences.Editor e,DriveSignalParser.Event x){if(x==null)e.putString("lastDriveSignalRaw","").putString("lastDriveSignalTimestamp","").putString("lastDriveSignalType","");else e.putString("lastDriveSignalRaw",x.raw).putString("lastDriveSignalTimestamp",x.timestamp).putString("lastDriveSignalType",x.type.name());}
private void pauseEvent(SharedPreferences.Editor e,DriveSignalParser.Event x,String status){e.putBoolean("paused",true).putBoolean("active",true).putBoolean("resumeNeedsContinuation",true).putString("pausedFromPhase",PHASE_WAIT_DRIVE_COMMIT).putString("phase",PHASE_PAUSED).putString("status",status);terminal(e,x);}
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
