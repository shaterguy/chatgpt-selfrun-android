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
    static final String PAUSE_ORIGIN_AI_USER_ACTION_REQUIRED = "AI_USER_ACTION_REQUIRED";
    static final String PAUSE_ORIGIN_AI_PAUSED = "AI_PAUSED";
    static final String PAUSE_ORIGIN_UI_MANUAL = "UI_MANUAL";
    static final String PAUSE_ORIGIN_EXTERNAL_MANUAL = "EXTERNAL_MANUAL";
    static final String PAUSE_ORIGIN_UNKNOWN = "UNKNOWN";

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
  .putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","").putInt("pendingDriveSignalCursor",0).putLong("commitDetectedAt",0L).putLong("guardDueAt",0L).putString("completionGuardFingerprint","").putBoolean("completionGuardArmed",false)
  .putString("activeCommandPrompt","").putString("activeCommandKind","").putInt("commandAttempt",0).putBoolean("awaitingCommandAck",false)
  .putString("submissionRetryKind","").putString("submissionRetryReason","").putLong("submissionRetryDueAt",0L).putInt("submissionRetryAttempt",0).putBoolean("submissionRetryReady",false)
  .putString("creationStage",CREATION_NONE).putString("pausedFromPhase","").putBoolean("resumeNeedsContinuation",false)
  .putString("pauseAnchorRunId","").putString("pauseAnchorOrigin","").putString("pauseAnchorCause","").putString("pauseAnchorPhase","").putInt("pauseAnchorCursor",0).putString("pauseAnchorDriveVersion","").putString("pauseAnchorModifiedTime","").putString("pauseAnchorSignalRaw","").putString("pauseAnchorSignalType","").putString("pauseAnchorId","")
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
    String pendingModel() { if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion()){DriveSignalParser.WorkProfile p=pendingDriveWorkProfile();return p.valid?p.model:WorkPreferenceDom.TURN_INFO_REWRITE_SENTINEL;}return get("pendingModel"); }
    String pendingReasoning() { if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion()){DriveSignalParser.WorkProfile p=pendingDriveWorkProfile();return p.valid?p.reasoning:WorkPreferenceDom.TURN_INFO_REWRITE_SENTINEL;}return get("pendingReasoning"); }
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
    int pendingDriveSignalCursor() { return prefs.getInt("pendingDriveSignalCursor", 0); }
    long commitDetectedAt() { return prefs.getLong("commitDetectedAt", 0L); }
    String completionGuardFingerprint() { return get("completionGuardFingerprint"); }
    boolean completionGuardArmed() { return prefs.getBoolean("completionGuardArmed", false); }
    long guardDueAt() { return prefs.getLong("guardDueAt", 0L); }
    String activeCommandPrompt() { return get("activeCommandPrompt"); }
    String activeCommandKind() { if(PHASE_SEND_CONTINUE.equals(phase())&&get("activeCommandPrompt").isEmpty()){if(turnInfoRewriteRequired())SelfRunProtocol.requestTurnInfoRewrite(runId());else{NextInputCodec.Decoded next=DriveSignalParser.nextInput(pendingDriveSignalRaw());if(next.present&&next.valid)SelfRunProtocol.requestNextInput(runId(),next.text);}}return get("activeCommandKind"); }
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
    String pauseAnchorRunId() { return get("pauseAnchorRunId"); }
    String pauseAnchorOrigin() { return getOr("pauseAnchorOrigin", PAUSE_ORIGIN_UNKNOWN); }
    String pauseAnchorCause() { return get("pauseAnchorCause"); }
    String pauseAnchorPhase() { return get("pauseAnchorPhase"); }
    int pauseAnchorCursor() { return prefs.getInt("pauseAnchorCursor", 0); }
    String pauseAnchorDriveVersion() { return get("pauseAnchorDriveVersion"); }
    String pauseAnchorModifiedTime() { return get("pauseAnchorModifiedTime"); }
    String pauseAnchorSignalRaw() { return get("pauseAnchorSignalRaw"); }
    String pauseAnchorSignalType() { return get("pauseAnchorSignalType"); }
    String pauseAnchorId() { return get("pauseAnchorId"); }
    boolean terminalSideEffectPending() { return prefs.getBoolean("terminalSideEffectPending", false); }
    String terminalSideEffectType() { return get("terminalSideEffectType"); }
    String terminalSideEffectRunId() { return get("terminalSideEffectRunId"); }
    String terminalSideEffectCommitId() { return get("terminalSideEffectCommitId"); }

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
    void setRole(String value) { put("role", value); }
    void setPendingModel(String value) { if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion())return;put("pendingModel", value); }
    void setPendingReasoning(String value) { if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion())return;put("pendingReasoning", value); }
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
    void updateDriveSeen(String version, String modifiedTime) { SharedPreferences.Editor e=prefs.edit().putString("lastSeenDriveVersion",safe(version)).putString("lastSeenModifiedTime",safe(modifiedTime));if(paused()&&runId().equals(pauseAnchorRunId()))e.putString("pauseAnchorDriveVersion",safe(version)).putString("pauseAnchorModifiedTime",safe(modifiedTime));commitOrThrow(e); }

void baselineDriveSignals(int cursor,DriveSignalParser.Event latest){SharedPreferences.Editor e=prefs.edit().putInt("driveSignalCursor",Math.max(0,cursor));putLatest(e,latest);commitOrThrow(e);syncHistory();}
void beginCommandAttempt(String kind,String prompt){if(!(RETRY_BOOTSTRAP.equals(kind)||RETRY_CONTINUE.equals(kind))||safe(prompt).isEmpty())throw new IllegalArgumentException("valid command attempt required");int n=commandAttempt()==Integer.MAX_VALUE?Integer.MAX_VALUE:commandAttempt()+1;commitOrThrow(prefs.edit().putString("activeCommandKind",kind).putString("activeCommandPrompt",prompt).putInt("commandAttempt",n).putBoolean("awaitingCommandAck",false));syncHistory();}
String commandMarkerId(){if(RETRY_BOOTSTRAP.equals(get("activeCommandKind")))return runId()+":bootstrap:"+commandAttempt();String fp=DriveSignalParser.nextInputFingerprint(pendingDriveSignalRaw());String next=fp.isEmpty()?"none":fp.substring(0,Math.min(12,fp.length()));String anchor=pauseAnchorId().isEmpty()?"none":pauseAnchorId();return runId()+":continue:"+(pendingDriveSignalCursor()>0?pendingDriveSignalCursor():0)+":"+anchor+":"+next;}
void markCommandSubmitted(String kind,long due){if(!kind.equals(activeCommandKind())||activeCommandPrompt().isEmpty()||due<=0)throw new IllegalStateException("prepared command required");int n=submissionRetryAttempt()==Integer.MAX_VALUE?Integer.MAX_VALUE:submissionRetryAttempt()+1;commitOrThrow(prefs.edit().putBoolean("awaitingCommandAck",true).putString("submissionRetryKind",kind).putString("submissionRetryReason","COMMAND_RECEIVED_PENDING").putLong("submissionRetryDueAt",due).putInt("submissionRetryAttempt",n).putBoolean("submissionRetryReady",false).putString("phase",PHASE_WAIT_DRIVE_COMMIT).putString("status","Drive COMMAND_RECEIVED 대기 · 5분 후 미수신 시 재제출").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();}
void prepareCommandRetry(){if(!awaitingCommandAck()||!hasSubmissionRetry()||!submissionRetryDue())throw new IllegalStateException("command ACK retry is not due");String k=submissionRetryKind();String ph=RETRY_BOOTSTRAP.equals(k)?PHASE_BOOTSTRAP_SEND:PHASE_SEND_CONTINUE;commitOrThrow(prefs.edit().putBoolean("awaitingCommandAck",false).putString("activeCommandPrompt","").putString("activeCommandKind","").putString("submissionRetryKind","").putString("submissionRetryReason","").putLong("submissionRetryDueAt",0L).putBoolean("submissionRetryReady",false).putString("phase",ph).putString("status","COMMAND_RECEIVED 미수신 · 동일 명령 재제출 준비").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();}
static final class DriveBatchPendingState{
 private final boolean work;private String priorRaw;private boolean carryNext;
 DriveBatchPendingState(String mode,boolean pendingCompletion,String pendingRaw){work=MODE_WORK.equals(mode);priorRaw=pendingCompletion&&pendingRaw!=null?pendingRaw:"";carryNext=work&&pendingCompletion&&!priorRaw.isEmpty()&&!DriveSignalParser.workProfile(priorRaw).valid;}
 void supersede(){priorRaw="";carryNext=false;}
 String acceptCompletion(String newerRaw){String accepted=work&&carryNext?DriveSignalParser.mergeNextInputIfMissing(newerRaw,priorRaw):newerRaw;priorRaw=accepted==null?"":accepted;carryNext=work&&!priorRaw.isEmpty()&&!DriveSignalParser.workProfile(priorRaw).valid;return accepted;}
 boolean carryNextForTest(){return carryNext;}
 String rawForTest(){return priorRaw;}
}
void applyDriveSignals(List<DriveSignalParser.Event> events,long detectedAt,long guardMs){
 if(events==null||events.isEmpty())return;
 SharedPreferences.Editor e=prefs.edit();boolean awaiting=awaitingCommandAck();boolean guardArmed=completionGuardArmed();String guardFingerprint=completionGuardFingerprint();DriveBatchPendingState batchPending=new DriveBatchPendingState(mode(),hasPendingDriveCompletion(),pendingDriveSignalRaw());int rank=PHASE_DONE.equals(phase())||PHASE_PAUSED.equals(phase())?3:PHASE_DRIVE_COMMIT_GUARD.equals(phase())?2:0;
 for(DriveSignalParser.Event x:events){
  e.putInt("driveSignalCursor",x.cursor);putLatest(e,x);
  if(x.type==DriveSignalParser.Type.TURN_COMPLETED&&guardArmed&&DriveSignalParser.completionFingerprint(x.raw).equals(guardFingerprint)){e.putString("status","Drive TURN_COMPLETED 중복 확인 · 기존 completion 유지");continue;}
  if(x.type==DriveSignalParser.Type.COMMAND_RECEIVED){
   if(awaiting){String prompt=get("activeCommandPrompt"),kind=get("activeCommandKind");boolean rewrite=prompt.startsWith("[SELF_RUN_TURN_INFO_REWRITE ");awaiting=false;if(RETRY_CONTINUE.equals(kind)&&!rewrite){invalidateSupersededContinuation(e);batchPending.supersede();clearPauseAnchor(e);guardArmed=false;guardFingerprint="";}else clearCommandWait(e);}
   if(rank<2)e.putString("status","Drive COMMAND_RECEIVED 확인 · 작업 진행 중");
   continue;
  }
  if(awaiting){awaiting=false;protocolPause(e,x,"COMMAND_RECEIVED_REQUIRED");batchPending.supersede();guardArmed=false;guardFingerprint="";rank=3;continue;}
  if(x.type==DriveSignalParser.Type.INVALID){protocolPause(e,x,x.protocolError.isEmpty()?"DRIVE_PROTOCOL_INVALID":x.protocolError);batchPending.supersede();guardArmed=false;guardFingerprint="";rank=3;continue;}
  switch(x.type){
   case TURN_COMPLETED->{if(rank<2){rank=2;String raw=batchPending.acceptCompletion(x.raw);e.putString("pendingDriveSignalRaw",raw).putString("pendingDriveSignalTimestamp",x.timestamp).putString("pendingDriveSignalType",x.type.name()).putInt("pendingDriveSignalCursor",x.cursor).putLong("commitDetectedAt",detectedAt).putLong("guardDueAt",detectedAt+guardMs).putString("completionGuardFingerprint",DriveSignalParser.completionFingerprint(x.raw)).putBoolean("completionGuardArmed",true).putString("phase",PHASE_DRIVE_COMMIT_GUARD).putString("status","Drive TURN_COMPLETED 확인 · 안전 지연");guardFingerprint=DriveSignalParser.completionFingerprint(x.raw);guardArmed=true;}}
   case USER_ACTION_REQUIRED->{rank=3;pauseEvent(e,x,"사용자 조치 필요");batchPending.supersede();guardArmed=false;guardFingerprint="";}
   case PAUSED->{rank=3;pauseEvent(e,x,"SelfRun Drive 일시정지");batchPending.supersede();guardArmed=false;guardFingerprint="";}
   case DONE->{rank=3;invalidateSupersededContinuation(e);batchPending.supersede();guardArmed=false;guardFingerprint="";e.putBoolean("active",false).putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_DONE).putString("status","SelfRun Drive 완료");terminal(e,x);}
   case COMMAND_RECEIVED,INVALID->throw new IllegalStateException("handled above");
  }
 }
 e.putBoolean("awaitingCommandAck",awaiting).putLong("phaseStartedAt",System.currentTimeMillis());commitOrThrow(e);syncHistory();
}
void repairGuard(long now,long guardMs){SharedPreferences.Editor e=prefs.edit();String raw=pendingDriveSignalRaw(),ts=pendingDriveSignalTimestamp();int pendingCursor=pendingDriveSignalCursor();if(!DriveSignalParser.Type.TURN_COMPLETED.name().equals(pendingDriveSignalType())||raw.isEmpty()){if(DriveSignalParser.Type.TURN_COMPLETED.name().equals(lastDriveSignalType())&&!lastDriveSignalRaw().isEmpty()){raw=lastDriveSignalRaw();ts=lastDriveSignalTimestamp();pendingCursor=driveSignalCursor();}else{int cursor=driveSignalCursor();int recoveryCursor=cursor>0?driveSignalCursor()-1:Integer.MAX_VALUE;commitOrThrow(clearPendingCompletion(e).putInt("driveSignalCursor",recoveryCursor).putString("lastSeenDriveVersion","").putString("lastSeenModifiedTime","").putString("phase",PHASE_WAIT_DRIVE_COMMIT).putString("status",cursor>0?"Drive 완료 signal guard 손상 · 직전 신호 재검증":"Drive 완료 signal guard 손상 · 현재 문서 baseline 재확인").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();return;}}commitOrThrow(e.putString("pendingDriveSignalRaw",raw).putString("pendingDriveSignalTimestamp",ts).putString("pendingDriveSignalType",DriveSignalParser.Type.TURN_COMPLETED.name()).putInt("pendingDriveSignalCursor",pendingCursor>0?pendingCursor:driveSignalCursor()).putLong("commitDetectedAt",now).putLong("guardDueAt",now+guardMs).putString("completionGuardFingerprint",DriveSignalParser.completionFingerprint(raw)).putBoolean("completionGuardArmed",true).putString("phase",PHASE_DRIVE_COMMIT_GUARD).putString("status","Drive TURN_COMPLETED guard 복구"));syncHistory();}
void beginManualResumeOverride(){
 if(!ensurePauseAnchor()){commitOrThrow(prefs.edit().putBoolean("paused",true).putBoolean("active",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode","DRIVE_RESUME_ANCHOR_INVALID").putString("lastErrorMessage","pause anchor를 안전하게 복원할 수 없습니다.").putString("status","재개 차단 · pause anchor 확인 필요").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();return;}
 if(restorePauseWithoutDrive(pauseAnchorOrigin(),resumeNeedsContinuation(),!turnDocumentId().isEmpty())){String restore=pauseAnchorPhase();if(!validRestoredPhase(restore)){commitOrThrow(prefs.edit().putBoolean("paused",true).putBoolean("active",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode","DRIVE_RESUME_PHASE_INVALID").putString("lastErrorMessage","pausedFromPhase가 유효하지 않습니다.").putString("status","재개 차단 · 복귀 phase 확인 필요").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();return;}SharedPreferences.Editor e=prefs.edit().putBoolean("terminalSideEffectPending",false).putString("terminalSideEffectType","").putString("terminalSideEffectRunId","").putString("terminalSideEffectCommitId","").putBoolean("paused",false).putBoolean("active",true).putBoolean("userStopped",false).putBoolean("resumeNeedsContinuation",false).putString("phase",restore).putString("status","외부 수동조치 완료 · 기존 phase 복귀").putLong("phaseStartedAt",System.currentTimeMillis());clearPauseAnchor(e);commitOrThrow(e);syncHistory();return;}
 commitOrThrow(prefs.edit().putBoolean("terminalSideEffectPending",false).putString("terminalSideEffectType","").putString("terminalSideEffectRunId","").putString("terminalSideEffectCommitId","").putBoolean("paused",false).putBoolean("active",true).putBoolean("userStopped",false).putString("phase",PHASE_RESUME_BASELINE).putString("status","사용자 재개 · Drive post-anchor signal 조정 중").putLong("phaseStartedAt",System.currentTimeMillis()));syncHistory();
}
void baselineManualResume(List<DriveSignalParser.Event> postAnchor,int totalCount,DriveSignalParser.Event latest){
 DriveResumePolicy.Origin origin=DriveResumePolicy.parseOrigin(pauseAnchorOrigin());DriveResumePolicy.Decision decision=DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),totalCount,postAnchor);SharedPreferences.Editor e=prefs.edit().putInt("driveSignalCursor",Math.max(0,totalCount)).putBoolean("active",true).putBoolean("userStopped",false).putLong("phaseStartedAt",System.currentTimeMillis());putLatest(e,latest);
 switch(decision.action){
  case APPLY_COMPLETION->{DriveSignalParser.Event completion=decision.event;String raw=completion.raw;if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion())raw=DriveSignalParser.mergeNextInputIfMissing(raw,pendingDriveSignalRaw());invalidateSupersededContinuation(e);e.putBoolean("paused",false).putString("pendingDriveSignalRaw",raw).putString("pendingDriveSignalTimestamp",completion.timestamp).putString("pendingDriveSignalType",DriveSignalParser.Type.TURN_COMPLETED.name()).putInt("pendingDriveSignalCursor",completion.cursor).putLong("commitDetectedAt",System.currentTimeMillis()).putLong("guardDueAt",System.currentTimeMillis()).putString("completionGuardFingerprint",DriveSignalParser.completionFingerprint(completion.raw)).putBoolean("completionGuardArmed",true).putString("phase",PHASE_READ_NEXT_CONTROL).putString("status","재개 조정 완료 · POST_ANCHOR TURN_COMPLETED 적용");}
  case CONTINUE->{invalidateSupersededContinuation(e);e.putBoolean("paused",false).putString("phase",PHASE_SEND_CONTINUE).putString("status","재개 조정 완료 · 외부 수동조치 후 plain CONTINUE 준비");}
  case RESTORE_PHASE->{String restore=pauseAnchorPhase();if(!validRestoredPhase(restore)){e.putBoolean("paused",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode","DRIVE_RESUME_PHASE_INVALID").putString("lastErrorMessage","pausedFromPhase가 유효하지 않습니다.").putString("status","재개 차단 · 복귀 phase 확인 필요");}else{String restoredStatus=origin==DriveResumePolicy.Origin.UI_MANUAL?"UI 일시정지 해제 · 기존 phase 복귀":"외부 수동조치 완료 · 기존 phase 복귀";e.putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",restore).putString("status",restoredStatus);clearPauseAnchor(e);}}
  case KEEP_PAUSED->{DriveSignalParser.Event blocking=decision.event;e.putBoolean("paused",true).putString("phase",PHASE_PAUSED);if(blocking==null){e.putString("status","재개 보류 · 기존 pause latch 유지");}else{invalidateSupersededContinuation(e);e.putBoolean("resumeNeedsContinuation",true).putString("status","재개 보류 · 더 최신 blocking signal 확인");recordPauseAnchor(e,pauseOriginForDriveSignal(blocking.type),blocking.type.name(),pauseAnchorPhase().isEmpty()?PHASE_WAIT_DRIVE_COMMIT:pauseAnchorPhase(),totalCount,blocking);terminal(e,blocking);}}
  case DONE->{DriveSignalParser.Event done=decision.event;invalidateSupersededContinuation(e);e.putBoolean("active",false).putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_DONE).putString("status","SelfRun Drive 완료");terminal(e,done);}
  case PROTOCOL_ERROR->{invalidateSupersededContinuation(e);e.putBoolean("paused",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode",safe(decision.reason)).putString("lastErrorMessage","Drive resume reconciliation이 fail-closed 처리되었습니다.").putString("status","재개 차단 · "+safe(decision.reason));}
 }
 commitOrThrow(e);syncHistory();
}
void baselineManualResume(int totalCount,DriveSignalParser.Event latest){baselineManualResume(latest==null?java.util.Collections.emptyList():java.util.Collections.singletonList(latest),totalCount,latest);}
void captureConversationUrl(String value){
    ProjectUrlPolicy.ProjectRef expected=ProjectUrlPolicy.parseProject(projectUrl()),actual=ProjectUrlPolicy.parseProject(value);
    if(!conversationUrl().isEmpty()||expected==null||actual==null||actual.conversationId.isEmpty()||!expected.projectId.equals(actual.projectId))return;
    commitOrThrow(prefs.edit().putString("conversationUrl",safe(value)));syncHistory();
}
private static SharedPreferences.Editor clearCommandWait(SharedPreferences.Editor e){return e.putBoolean("awaitingCommandAck",false).putString("activeCommandPrompt","").putString("activeCommandKind","").putString("submissionRetryKind","").putString("submissionRetryReason","").putLong("submissionRetryDueAt",0L).putInt("submissionRetryAttempt",0).putBoolean("submissionRetryReady",false);}
private SharedPreferences.Editor invalidateSupersededContinuation(SharedPreferences.Editor e){SelfRunProtocol.clearPendingContinuation(runId());clearCommandWait(e);clearPendingCompletion(e);resetCompletionGuard(e);return e;}
private static void putLatest(SharedPreferences.Editor e,DriveSignalParser.Event x){if(x==null)e.putString("lastDriveSignalRaw","").putString("lastDriveSignalTimestamp","").putString("lastDriveSignalType","");else e.putString("lastDriveSignalRaw",x.raw).putString("lastDriveSignalTimestamp",x.timestamp).putString("lastDriveSignalType",x.type.name());}
private void pauseEvent(SharedPreferences.Editor e,DriveSignalParser.Event x,String status){invalidateSupersededContinuation(e);String origin=pauseOriginForDriveSignal(x.type);e.putBoolean("paused",true).putBoolean("active",true).putBoolean("resumeNeedsContinuation",true).putString("pausedFromPhase",PHASE_WAIT_DRIVE_COMMIT).putString("phase",PHASE_PAUSED).putString("status",status);recordPauseAnchor(e,origin,x.type.name(),PHASE_WAIT_DRIVE_COMMIT,x.cursor,x);terminal(e,x);}
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

    void enterPause(String priorPhase, boolean needsContinuation) { enterPause(priorPhase, needsContinuation, ""); }
    void enterPause(String priorPhase, boolean needsContinuation, String cause) {
        String origin = pauseOriginForCause(cause);
        SharedPreferences.Editor e=prefs.edit().putString("pausedFromPhase",safe(priorPhase)).putBoolean("resumeNeedsContinuation",needsContinuation)
                .putBoolean("paused",true).putString("phase",PHASE_PAUSED).putLong("phaseStartedAt",System.currentTimeMillis());
        recordPauseAnchor(e,origin,cause,priorPhase,driveSignalCursor(),null);commitOrThrow(e);syncHistory();
    }

    void leavePause(String nextPhase) {
        commitOrThrow(prefs.edit().putBoolean("paused", false).putBoolean("active", true).putBoolean("userStopped", false)
                .putString("phase", safe(nextPhase)).putLong("phaseStartedAt", System.currentTimeMillis())); syncHistory();
    }

    private boolean ensurePauseAnchor() {
        if (runId().equals(pauseAnchorRunId()) && pauseAnchorCursor() >= 0
                && DriveResumePolicy.parseOrigin(pauseAnchorOrigin()) != DriveResumePolicy.Origin.UNKNOWN
                && !pauseAnchorId().isEmpty()) return true;
        String origin = inferLegacyPauseOrigin();
        if (PAUSE_ORIGIN_UNKNOWN.equals(origin)) return false;
        String prior = pausedFromPhase().isEmpty() ? PHASE_WAIT_DRIVE_COMMIT : pausedFromPhase();
        SharedPreferences.Editor e=prefs.edit();
        DriveSignalParser.Event latest=null;
        if(!lastDriveSignalRaw().isEmpty()) latest=new DriveSignalParser.Event(parseStoredSignalType(lastDriveSignalType()),lastDriveSignalTimestamp(),lastDriveSignalRaw(),driveSignalCursor());
        recordPauseAnchor(e,origin,"LEGACY_PAUSE_MIGRATION",prior,driveSignalCursor(),latest);commitOrThrow(e);syncHistory();return true;
    }
    private String inferLegacyPauseOrigin(){String st=status();if(st.contains("사용자 조치 필요"))return PAUSE_ORIGIN_AI_USER_ACTION_REQUIRED;if(st.contains("SelfRun Drive 일시정지"))return PAUSE_ORIGIN_EXTERNAL_MANUAL;if(st.contains("사용자 일시정지"))return PAUSE_ORIGIN_UI_MANUAL;if(!lastErrorCode().isEmpty()||st.contains("로그인")||st.contains("OAuth")||st.contains("권한"))return PAUSE_ORIGIN_EXTERNAL_MANUAL;return PAUSE_ORIGIN_UNKNOWN;}
    private static String pauseOriginForCause(String cause){String c=safe(cause);if("UI_PAUSE".equals(c))return PAUSE_ORIGIN_UI_MANUAL;if(c.isEmpty())return PAUSE_ORIGIN_UNKNOWN;return PAUSE_ORIGIN_EXTERNAL_MANUAL;}
    static String pauseOriginForDriveSignal(DriveSignalParser.Type type){if(type==DriveSignalParser.Type.USER_ACTION_REQUIRED)return PAUSE_ORIGIN_AI_USER_ACTION_REQUIRED;if(type==DriveSignalParser.Type.PAUSED)return PAUSE_ORIGIN_EXTERNAL_MANUAL;return PAUSE_ORIGIN_AI_PAUSED;}
    static boolean restorePauseWithoutDrive(String origin,boolean needsContinuation,boolean hasTurnDocument){if(hasTurnDocument)return false;if(PAUSE_ORIGIN_EXTERNAL_MANUAL.equals(origin)&&!needsContinuation)return true;return PAUSE_ORIGIN_UI_MANUAL.equals(origin);}
    private static DriveSignalParser.Type parseStoredSignalType(String value){try{return DriveSignalParser.Type.valueOf(value);}catch(Exception ignored){return DriveSignalParser.Type.COMMAND_RECEIVED;}}
    private void recordPauseAnchor(SharedPreferences.Editor e,String origin,String cause,String priorPhase,int cursor,DriveSignalParser.Event signal){String raw=signal==null?"":DriveSignalParser.historySafeRaw(signal.raw);String type=signal==null?"":signal.type.name();String seed=runId()+"|"+origin+"|"+safe(cause)+"|"+safe(priorPhase)+"|"+Math.max(0,cursor)+"|"+raw;String id=NextInputCodec.fingerprintText(seed).substring(0,24);e.putString("pauseAnchorRunId",runId()).putString("pauseAnchorOrigin",safe(origin)).putString("pauseAnchorCause",safe(cause)).putString("pauseAnchorPhase",safe(priorPhase)).putInt("pauseAnchorCursor",Math.max(0,cursor)).putString("pauseAnchorDriveVersion",lastSeenDriveVersion()).putString("pauseAnchorModifiedTime",lastSeenModifiedTime()).putString("pauseAnchorSignalRaw",raw).putString("pauseAnchorSignalType",type).putString("pauseAnchorId",id);}
    private static SharedPreferences.Editor clearPendingCompletion(SharedPreferences.Editor e){return e.putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","").putInt("pendingDriveSignalCursor",0).putLong("commitDetectedAt",0L).putLong("guardDueAt",0L);}
    private static SharedPreferences.Editor resetCompletionGuard(SharedPreferences.Editor e){return e.putString("completionGuardFingerprint","").putBoolean("completionGuardArmed",false);}
    private static SharedPreferences.Editor clearPauseAnchor(SharedPreferences.Editor e){return e.putString("pauseAnchorRunId","").putString("pauseAnchorOrigin","").putString("pauseAnchorCause","").putString("pauseAnchorPhase","").putInt("pauseAnchorCursor",0).putString("pauseAnchorDriveVersion","").putString("pauseAnchorModifiedTime","").putString("pauseAnchorSignalRaw","").putString("pauseAnchorSignalType","").putString("pauseAnchorId","");}
    private void protocolPause(SharedPreferences.Editor e,DriveSignalParser.Event x,String code){invalidateSupersededContinuation(e);e.putBoolean("paused",true).putBoolean("active",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode",safe(code)).putString("lastErrorMessage","Drive protocol violation").putString("status","Drive protocol 오류 · "+safe(code));recordPauseAnchor(e,PAUSE_ORIGIN_AI_PAUSED,code,PHASE_WAIT_DRIVE_COMMIT,x.cursor,x);e.putBoolean("terminalSideEffectPending",true).putString("terminalSideEffectType",DriveSignalParser.Type.PAUSED.name()).putString("terminalSideEffectRunId",runId()).putString("terminalSideEffectCommitId","protocol:"+DriveSignalParser.completionFingerprint(x.raw));}
    private static boolean validRestoredPhase(String phase){return phase!=null&&!phase.isEmpty()&&!PHASE_PAUSED.equals(phase)&&!PHASE_DONE.equals(phase)&&!PHASE_IDLE.equals(phase);}

    void syncHistory() { history.sync(this); }
    private void put(String key, String value) { commitOrThrow(prefs.edit().putString(key, safe(value))); syncHistory(); }
    private String get(String key) { return prefs.getString(key, ""); }
    private String getOr(String key, String fallback) { return prefs.getString(key, fallback); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static void commitOrThrow(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("durable SelfRun Drive state write failed");
    }
}
