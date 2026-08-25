#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')

def replace_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise SystemExit(f'{label}: expected 1 match, got {n}')
    return text.replace(old, new, 1)

def sub_once(text, pattern, repl, label, flags=0):
    out, n = re.subn(pattern, repl, text, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f'{label}: expected 1 match, got {n}')
    return out

# Version identity: first v1.7.0 development candidate, preserving TEST application-id lineage.
p = 'app/build.gradle'
s = read(p)
s = replace_once(s, 'def selfRunDriveVersionCode = 1000096', 'def selfRunDriveVersionCode = 1000097', 'versionCode')
s = replace_once(s, "def selfRunDriveVersionName = '1.6.1'", "def selfRunDriveVersionName = '1.7.0-dev1'", 'versionName')
write(p, s)

# New-run UI: preserve exact requirement text and opt new runs into signal-document transport.
p = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunNewActivity.java'
s = read(p)
for line in [
    'import java.security.SecureRandom;\n',
    'import java.text.SimpleDateFormat;\n',
    'import java.util.Date;\n',
    'import java.util.TimeZone;\n',
]:
    s = s.replace(line, '')
for line in [
    '    private static final SecureRandom RUN_RANDOM = new SecureRandom();\n',
    '    private static final char[] RUN_SUFFIX_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();\n',
    '    private static final int RUN_SUFFIX_LENGTH = 6;\n',
]:
    s = s.replace(line, '')
s = replace_once(s,
'''        String project = selectedProjectUrl();
        String request = requirement.getText().toString().trim();
        if (request.isEmpty()) {
            Toast.makeText(this, "셀프런 명령을 입력하세요.", Toast.LENGTH_LONG).show();
            return;
        }
''',
'''        String project = selectedProjectUrl();
        String request = requirement.getText().toString();
        String requirementError = SelfRunOriginalRequirement.validationError(request);
        if (!requirementError.isEmpty()) {
            Toast.makeText(this, requirementError, Toast.LENGTH_LONG).show();
            return;
        }
''', 'raw requirement validation')
s = replace_once(s, '        String runId = newRunId();\n', '        String runId = SelfRunRunId.create();\n', 'shared run id')
s = replace_once(s,
'''        stopService(new Intent(this, SelfRunService.class));
        try {
            store.start(runId, selectedMode, project, request, new ArrayList<>(selectedAttachments));
''',
'''        stopService(new Intent(this, SelfRunService.class));
        if (!SelfRunSignalTransport.mark(this, runId)) {
            store.cancelAttachmentGrantHandoff();
            Toast.makeText(this, "SelfRun signal transport 상태를 저장하지 못했습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            store.start(runId, selectedMode, project, request, new ArrayList<>(selectedAttachments));
''', 'signal transport marker')
s = sub_once(s,
    r'\n    private static String newRunId\(\) \{.*?\n    \}\n\}',
    '\n}', 'remove duplicate run id generator', re.S)
write(p, s)

# History treats an auto-rolled predecessor as terminal history, not a restartable active run.
p = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunHistoryStore.java'
s = read(p)
s = replace_once(s,
    '            item.put("terminal", SelfRunStore.PHASE_DONE.equals(store.phase()) || store.userStopped());',
    '            item.put("terminal", SelfRunStore.PHASE_DONE.equals(store.phase()) || SelfRunRolloverCoordinator.PHASE_ROLLED_OVER.equals(store.phase()) || store.userStopped());',
    'rolled over history terminal')
write(p, s)

# Drive transport: staged-empty means empty signal log; legacy unmarked runs retain turn-doc parsing.
p = 'app/src/main/java/com/shaterguy/chatgptselfrun/DriveApiClient.java'
s = read(p)
s = replace_once(s,
'''    private static final class PendingSignalBatch {
        final String runId;
        final List<Metadata> documents;
        PendingSignalBatch(String runId, List<Metadata> documents) {
            this.runId = runId == null ? "" : runId;
            this.documents = documents == null ? Collections.emptyList() : documents;
        }
    }
''',
'''    private static final class PendingSignalBatch {
        final String runId;
        final List<Metadata> documents;
        final boolean staged;
        PendingSignalBatch(String runId, List<Metadata> documents, boolean staged) {
            this.runId = runId == null ? "" : runId;
            this.documents = documents == null ? Collections.emptyList() : documents;
            this.staged = staged;
        }
    }
''', 'pending signal staged marker')

s = sub_once(s,
    r'    Metadata getPollMetadata\(String accessToken, String fileId\) throws Exception \{.*?\n    \}\n\n    private List<Metadata> listSignalDocuments',
'''    Metadata getPollMetadata(String accessToken, String fileId) throws Exception {
        return getPollMetadata(accessToken, fileId, false);
    }

    Metadata getPollMetadata(String accessToken, String fileId, boolean signalDocumentTransport) throws Exception {
        requireFileId(fileId);
        String endpoint = "https://www.googleapis.com/drive/v3/files/" + fileId + "?supportsAllDrives=true&fields=" + POLL_FIELDS;
        Metadata turnDocument = new Metadata(request("GET", endpoint, accessToken, null));
        if (turnDocument.trashed || turnDocument.shared || !MIME_DOCUMENT.equals(turnDocument.mimeType)
                || !validFileId(turnDocument.parentId) || !SelfRunProtocolRules.validRunId(turnDocument.name)) {
            if (signalDocumentTransport) stageSignalDocuments(fileId, "", Collections.emptyList());
            return turnDocument;
        }
        if (!signalDocumentTransport) return turnDocument;
        List<Metadata> signals = listSignalDocuments(accessToken, turnDocument.name, turnDocument.parentId);
        stageSignalDocuments(fileId, turnDocument.name, signals);
        if (signals.isEmpty()) return turnDocument;
        Metadata latest = signals.get(signals.size() - 1);
        return new Metadata(turnDocument,
                "signal:" + latest.id + ":" + latest.modifiedTime,
                latest.modifiedTime);
    }

    private List<Metadata> listSignalDocuments''', 'getPollMetadata transport split', re.S)

s = sub_once(s,
    r'    private PendingSignalBatch consumeSignalDocuments\(String turnDocumentId\) \{.*?\n    \}\n\n    Metadata findSingleTurnDocument',
'''    private PendingSignalBatch consumeSignalDocuments(String turnDocumentId) {
        synchronized (signalSnapshotLock) {
            if (!turnDocumentId.equals(pendingSignalTurnDocumentId)) {
                return new PendingSignalBatch("", Collections.emptyList(), false);
            }
            PendingSignalBatch batch = new PendingSignalBatch(pendingSignalRunId, pendingSignalDocuments, true);
            pendingSignalTurnDocumentId = "";
            pendingSignalRunId = "";
            pendingSignalDocuments = Collections.emptyList();
            return batch;
        }
    }

    Metadata findSingleTurnDocument''', 'consume staged-empty signal snapshot', re.S)

s = sub_once(s,
    r'    void initializeDocument\(String accessToken, String documentId, String initialText\) throws Exception \{.*?\n    \}\n\n    DocumentSnapshot readDocumentSnapshot',
'''    void initializeDocument(String accessToken, String documentId, String initialText) throws Exception {
        initializeDocument(accessToken, documentId, initialText, "");
    }

    void initializeDocument(String accessToken, String documentId, String initialText,
                            String requiredRevisionId) throws Exception {
        requireFileId(documentId);
        if (initialText == null || initialText.isEmpty()) throw new IllegalArgumentException("initial text required");
        JSONObject location = new JSONObject().put("index", 1);
        JSONObject insert = new JSONObject().put("location", location).put("text", initialText);
        JSONObject body = new JSONObject().put("requests", new JSONArray().put(new JSONObject().put("insertText", insert)));
        if (requiredRevisionId != null && !requiredRevisionId.isEmpty()) {
            body.put("writeControl", new JSONObject().put("requiredRevisionId", requiredRevisionId));
        }
        request("POST", "https://docs.googleapis.com/v1/documents/" + documentId + ":batchUpdate",
                accessToken, body, true, "original requirement write result unknown");
    }

    DocumentSnapshot readDocumentSnapshot''', 'conditional original requirement write', re.S)

s = replace_once(s,
'''        PendingSignalBatch batch = consumeSignalDocuments(documentId);
        if (!batch.documents.isEmpty()) return readSignalDocumentSnapshot(accessToken, batch);
        return readNativeDocumentSnapshot(accessToken, documentId);
''',
'''        PendingSignalBatch batch = consumeSignalDocuments(documentId);
        if (batch.staged) return readSignalDocumentSnapshot(accessToken, batch);
        return readNativeDocumentSnapshot(accessToken, documentId);
''', 'staged signal snapshot decision')
s = replace_once(s,
'''    private DocumentSnapshot readNativeDocumentSnapshot(String accessToken, String documentId) throws Exception {
''',
'''    DocumentSnapshot readTurnDocumentSnapshot(String accessToken, String documentId) throws Exception {
        return readNativeDocumentSnapshot(accessToken, documentId);
    }

    private DocumentSnapshot readNativeDocumentSnapshot(String accessToken, String documentId) throws Exception {
''', 'explicit turn document read')
write(p, s)

# SelfRunService: bind new contracts into the state machine.
p = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java'
s = read(p)
s = replace_once(s,
'''    private SelfRunStore store;
    private SelfRunRunLog runLog;
''',
'''    private SelfRunStore store;
    private SelfRunRolloverCoordinator rollover;
    private SelfRunNetworkState networkState;
    private SelfRunRunLog runLog;
''', 'service coordinator fields')
s = replace_once(s,
'''        store = new SelfRunStore(this);
        runLog = new SelfRunRunLog(this);
''',
'''        store = new SelfRunStore(this);
        rollover = new SelfRunRolloverCoordinator(this);
        networkState = new SelfRunNetworkState(this);
        networkState.start();
        runLog = new SelfRunRunLog(this);
''', 'service coordinator init')

s = sub_once(s,
    r'    @Override public int onStartCommand\(Intent intent, int flags, int startId\) \{.*?\n    \}\n\n    private void startForegroundCompat',
'''    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RUN : intent.getAction();
        if (rollover.hasPendingClaim()) {
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

    private void adoptSuccessorRuntime() {
        runtimeRunId = store.runId();
        verifiedDriveAccountId = "";
        accessToken = "";
        retryAttempt = 0;
        bootstrapSendCallbackRecoveries = 0;
        clearContinuationAttempt();
    }

    private void resumePendingRollover() {
        if (!rollover.hasPendingClaim()) { if (canRun()) resumeStateMachine(); return; }
        SelfRunRolloverCoordinator.Result resumed = rollover.resumePending(store);
        if (resumed.started()) { adoptSuccessorRuntime(); startForegroundCompat(); resumeStateMachine(); }
        else if (rollover.hasPendingClaim()) handler.postDelayed(this::resumePendingRollover, 5_000L);
    }

    private void startForegroundCompat''', 'sticky rollover claim recovery', re.S)

s = sub_once(s,
    r'private void initializeDocument\(int epoch\)throws Exception\{.*?\n\nprivate void verifyInitialDocument\(int epoch\)throws Exception\{.*?\n\nprivate void pollDrive\(\)',
'''private void initializeDocument(int epoch)throws Exception{
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

private void pollDrive()''', 'original requirement init and readback', re.S)

s = replace_once(s,
    'DriveApiClient.Metadata metadata=drive.getPollMetadata(accessToken,snapshot.turnDocumentId);if(!canApplyDriveResult(epoch))return;',
    'DriveApiClient.Metadata metadata=drive.getPollMetadata(accessToken,snapshot.turnDocumentId,SelfRunSignalTransport.isSignalDocumentRun(this,snapshot.runId));if(!canApplyDriveResult(epoch))return;',
    'signal transport poll metadata')

s = s.replace(
'''            runLog.record(store,"POST_DOM_DRIVE_SYNC","result=timeout;maxWaitMs="+POST_DOM_DRIVE_MAX_WAIT_MS+";action=pause_fail_closed");
            pauseError("POST_DOM_DRIVE_SYNC_TIMEOUT","Drive 완료 신호를 제한시간 내 확정하지 못해 자동 CONTINUE를 차단했습니다.",epoch,snapshot.runId,snapshot.phase);return;
''',
'''            handlePostDomDriveTimeout(epoch,snapshot);return;
''')
if s.count('handlePostDomDriveTimeout(epoch,snapshot);return;') != 2:
    raise SystemExit('post-dom timeout replacement expected twice')
insert_marker = '\nprivate void replayTerminalSideEffect()'
if insert_marker not in s:
    raise SystemExit('replay marker missing')
s = s.replace(insert_marker,
'''\nprivate void handlePostDomDriveTimeout(int epoch,DriveStateSnapshot snapshot){
    if(SelfRunSignalTransport.isSignalDocumentRun(this,snapshot.runId)&&SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){
        runLog.record(store,"POST_DOM_DRIVE_SYNC","result=timeout;maxWaitMs="+POST_DOM_DRIVE_MAX_WAIT_MS+";action=rollover");
        handler.post(()->rolloverConversation(SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT));
    }else{
        runLog.record(store,"POST_DOM_DRIVE_SYNC","result=timeout;maxWaitMs="+POST_DOM_DRIVE_MAX_WAIT_MS+";action=pause_fail_closed");
        pauseError("POST_DOM_DRIVE_SYNC_TIMEOUT","Drive 완료 신호를 제한시간 내 확정하지 못해 자동 CONTINUE를 차단했습니다.",epoch,snapshot.runId,snapshot.phase);
    }
}
''' + insert_marker, 1)

s = sub_once(s,
    r'    private void launchWebView\(String target\) \{.*?\n    \}\n\nprivate boolean isTurnCompletionCallback',
'''    private void launchWebView(String target) {
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
                    if(!launchedRunId.equals(store.runId())||!r.isForMainFrame())return;
                    int status=response.getStatusCode();
                    if(SelfRunRolloverPolicy.rolloverHttpStatus(store.conversationUrl(),networkState.isValidated(),status)){
                        rolloverConversation(SelfRunRolloverPolicy.WEBVIEW_HTTP_GONE);return;
                    }
                    if(SelfRunRolloverPolicy.retryHttpStatus(status)) scheduleWeb(30_000L);
                }
                @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                    if(!launchedRunId.equals(store.runId())||!r.isForMainFrame()||!canRun()||!isWebAutomationPhase(store.phase()))return;
                    int code=e.getErrorCode();
                    if(SelfRunRolloverPolicy.rolloverMainFrameError(store.conversationUrl(),networkState.isValidated(),code)){
                        rolloverConversation(SelfRunRolloverPolicy.WEBVIEW_MAIN_FRAME_LOCAL_ERROR);return;
                    }
                    postWebCallback(()->{
                        if(v!=webView)return;
                        if(!SelfRunRolloverPolicy.transientWebError(code)&&SelfRunRolloverPolicy.rolloverMainFrameError(store.conversationUrl(),networkState.isValidated(),code)) rolloverConversation(SelfRunRolloverPolicy.WEBVIEW_MAIN_FRAME_LOCAL_ERROR);
                        else v.loadUrl(canonicalUrl());
                    },3_000L);
                }
                @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {h.cancel();if (launchedRunId.equals(store.runId()) && canRun() && isWebAutomationPhase(store.phase())) {runLog.record(store, "WEBVIEW_SSL_RETRY", "cancelled;retry_in=300000");postWebCallback(SelfRunService.this::restoreCanonical, WEB_RECOVERY_DELAY_MS);}}
                @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                    cleanupWebView();
                    if(launchedRunId.equals(store.runId())&&!store.paused()&&isWebAutomationPhase(store.phase())){
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

private boolean isTurnCompletionCallback''', 'event driven web rollover', re.S)

s = sub_once(s,
    r'private void runWebStep\(\)\{.*?\n\nprivate String ensureTurnObserverToken',
'''private void runWebStep(){
    if(!canRun()||!isWebAutomationPhase(store.phase())||webView==null||domInFlight)return;
    String phase=store.phase();
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

private String ensureTurnObserverToken''', 'web step rollover routing', re.S)

s = replace_once(s,
    '  JSONObject result=parsed.result;String status=parsed.status,detail=parsed.detail;\n',
    '  JSONObject result=parsed.result;String status=parsed.status,detail=parsed.detail;\n  if(isContinuationDiagnosticPhase(phase))rollover.clearLocalFailures(runId);\n',
    'clear local failures on callback')
s = replace_once(s,
'''  if("TARGET_ERROR".equals(status)){recordContinuationTargetError(phase);if(!isContinuationDiagnosticPhase(phase))restoreCanonical();else scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);return;}
''',
'''  if("TARGET_ERROR".equals(status)){
      recordContinuationTargetError(phase);
      if(SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){if(networkState.isValidated())rolloverConversation(SelfRunRolloverPolicy.TARGET_ERROR);else scheduleWeb(5_000L);}
      else if(!isContinuationDiagnosticPhase(phase))restoreCanonical();else scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);
      return;
  }
''', 'TARGET_ERROR rollover')
s = replace_once(s,
'''  handleWebResult(phase,status,result);
''',
'''  if(isConversationLocalFailureStatus(status)&&SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){
      int failures=rollover.incrementLocalFailure(runId);
      if(networkState.isValidated()&&SelfRunRolloverPolicy.localFailureBudgetExhausted(failures)){rolloverConversation(SelfRunRolloverPolicy.CONTINUATION_CALLBACK_TIMEOUT);return;}
      scheduleWeb(1200L);return;
  }
  handleWebResult(phase,status,result);
''', 'explicit local failure status')
s = replace_once(s,
'''        if(SelfRunStore.PHASE_BOOTSTRAP.equals(phase))failBootstrap(BootstrapResultPolicy.SCRIPT_ERROR,error.getClass().getSimpleName(),null);
        else if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)){if(bootstrapSendTimedOut(store.phaseStartedAt(),System.currentTimeMillis()))failBootstrapSubmissionTimeout("deadline_invalid_or_elapsed");else scheduleWeb(BOOTSTRAP_SEND_POLL_MS);}
        else scheduleWeb(2000L);
''',
'''        if(SelfRunStore.PHASE_BOOTSTRAP.equals(phase))failBootstrap(BootstrapResultPolicy.SCRIPT_ERROR,error.getClass().getSimpleName(),null);
        else if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)){if(bootstrapSendTimedOut(store.phaseStartedAt(),System.currentTimeMillis()))failBootstrapSubmissionTimeout("deadline_invalid_or_elapsed");else scheduleWeb(BOOTSTRAP_SEND_POLL_MS);}
        else if(SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){int failures=rollover.incrementLocalFailure(runId);if(networkState.isValidated()&&SelfRunRolloverPolicy.localFailureBudgetExhausted(failures))rolloverConversation(SelfRunRolloverPolicy.CONTINUATION_CALLBACK_TIMEOUT);else scheduleWeb(2000L);}
        else scheduleWeb(2000L);
''', 'evaluate exception rollover')

s = sub_once(s,
    r'private void scheduleContinuationCallbackDeadline\(WebView active,int webGeneration,String runId,int evaluationId,String phase\)\{.*?\n\}\n\nprivate void recoverBootstrapSendCallback',
'''private void scheduleContinuationCallbackDeadline(WebView active,int webGeneration,String runId,int evaluationId,String phase){
    handler.postDelayed(()->{
        if(active!=webView||webGeneration!=generation||!runId.equals(store.runId())||evaluationId!=webEvaluationId||!domInFlight||!canRun()||!phase.equals(store.phase())||!shouldGuardContinuationCallback(phase))return;
        domInFlight=false;webEvaluationId++;
        if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)){recoverBootstrapSendCallback();return;}
        runLog.record(store,"DOM_RESULT",SelfRunWebDiagnostics.callbackTimeoutDetail(phase));
        int failures=rollover.incrementLocalFailure(runId);
        if(SelfRunRolloverPolicy.knownConversation(store.conversationUrl())&&networkState.isValidated()&&SelfRunRolloverPolicy.localFailureBudgetExhausted(failures)){
            rolloverConversation(SelfRunRolloverPolicy.CONTINUATION_CALLBACK_TIMEOUT);return;
        }
        releaseWakeLock();
        scheduleWeb(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)?TURN_OBSERVER_HEALTHCHECK_MS:1200L);
    },CONTINUATION_CALLBACK_TIMEOUT_MS);
}

private void recoverBootstrapSendCallback''', 'bounded continuation callback rollover', re.S)

s = sub_once(s,
    r'private void failBootstrapSubmissionTimeout\(String reason\)\{.*?\n\}\n\nprivate void failBootstrap\(',
'''private void failBootstrapSubmissionTimeout(String reason){
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

private void failBootstrap(''', 'bootstrap submission rollover', re.S)

s = replace_once(s,
'''private String commandPrompt(String kind){if(!kind.equals(store.activeCommandKind())||store.activeCommandPrompt().isEmpty()){String prompt=SelfRunStore.RETRY_BOOTSTRAP.equals(kind)?SelfRunProtocol.bootstrapDrive(store.runId(),store.mode(),store.requirement(),store.turnDocumentId(),store.jobFolderId(),store.hasAttachments()):SelfRunProtocol.driveContinuation(store.runId(),store.pendingNextInput());store.beginCommandAttempt(kind,prompt);}return store.activeCommandPrompt();}
''',
'''private String commandPrompt(String kind){if(!kind.equals(store.activeCommandKind())||store.activeCommandPrompt().isEmpty()){String prompt=SelfRunStore.RETRY_BOOTSTRAP.equals(kind)?rollover.bootstrapPrompt(store):SelfRunProtocol.driveContinuation(store.runId(),store.pendingNextInput());store.beginCommandAttempt(kind,prompt);}return store.activeCommandPrompt();}
''', 'rollover bootstrap prompt')

marker='\nprivate void recordContinuationWait('
if marker not in s: raise SystemExit('diagnostic marker missing')
s=s.replace(marker,
'''\nprivate static boolean isConversationLocalFailureStatus(String status){return "SUBMISSION_AMBIGUOUS".equals(status)||"MARKER_FAILED".equals(status)||"SUBMISSION_PENDING".equals(status);}
''' + marker,1)

marker='\n    private void pauseError(String code, String message)'
if marker not in s: raise SystemExit('pause marker missing')
s=s.replace(marker,
'''\n    private void rolloverConversation(String cause) {
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
            startForegroundCompat();
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
''' + marker,1)

s = replace_once(s,
'''    @Override public void onDestroy() {destroyed = true;stopAutomationCallbacks(); cleanupWebView(); releaseWakeLock(); io.shutdownNow();super.onDestroy();}
''',
'''    @Override public void onDestroy() {destroyed = true;stopAutomationCallbacks(); cleanupWebView(); releaseWakeLock(); if(networkState!=null)networkState.stop(); io.shutdownNow();super.onDestroy();}
''', 'network callback cleanup')
write(p, s)

# Replace the old blank-document regression test with the original-requirement and signal-isolation contract.
p = 'app/src/test/java/com/shaterguy/chatgptselfrun/DriveInitializationPolicyTest.java'
write(p, '''package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class DriveInitializationPolicyTest {
    @Test public void newExecutionDocStoresExactOriginalRequirement() throws Exception {
        String service = src("SelfRunService.java");
        String init = between(service, "private void initializeDocument", "private void verifyInitialDocument");
        assertTrue(init.contains("SelfRunOriginalRequirement.validationError"));
        assertTrue(init.contains("readTurnDocumentSnapshot"));
        assertTrue(init.contains("current.revisionId"));
        assertTrue(init.contains("exactDocumentMatch"));
        assertTrue(init.contains("ORIGINAL_REQUIREMENT_READBACK_MISMATCH"));
    }

    @Test public void signalDocumentTransportNeverFallsBackToRequirementBody() throws Exception {
        String drive = src("DriveApiClient.java");
        assertTrue(drive.contains("final boolean staged"));
        assertTrue(drive.contains("if (batch.staged) return readSignalDocumentSnapshot"));
        assertTrue(drive.contains("getPollMetadata(String accessToken, String fileId, boolean signalDocumentTransport)"));
        String service = src("SelfRunService.java");
        assertTrue(service.contains("SelfRunSignalTransport.isSignalDocumentRun"));
    }

    @Test public void newRunUiPreservesRawRequirementAndMarksTransport() throws Exception {
        String activity = src("SelfRunNewActivity.java");
        assertTrue(activity.contains("String request = requirement.getText().toString();"));
        assertFalse(activity.contains("String request = requirement.getText().toString().trim();"));
        assertTrue(activity.contains("SelfRunOriginalRequirement.validationError(request)"));
        assertTrue(activity.contains("SelfRunSignalTransport.mark(this, runId)"));
        assertTrue(activity.contains("SelfRunRunId.create()"));
    }

    private static String src(String f) throws Exception {
        Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);
        if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
    private static String between(String s,String a,String b){int x=s.indexOf(a),y=s.indexOf(b,x+a.length());assertTrue(x>=0);assertTrue(y>x);return s.substring(x,y);}
}
''')

# New pure policy tests.
p = 'app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunOriginalRequirementTest.java'
write(p, '''package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SelfRunOriginalRequirementTest {
    @Test public void meaningfulWhitespaceIsPreserved() {
        String raw="  first\\nsecond  ";
        assertTrue(SelfRunOriginalRequirement.valid(raw));
        assertTrue(SelfRunOriginalRequirement.exactDocumentMatch(raw+"\\n",raw));
        assertEquals(raw,SelfRunOriginalRequirement.logicalDocumentText(raw+"\\n"));
    }
    @Test public void docsStrippedCharactersAreRejected() {
        assertFalse(SelfRunOriginalRequirement.valid("a\\u0001b"));
        assertFalse(SelfRunOriginalRequirement.valid("a\\uE000b"));
        assertFalse(SelfRunOriginalRequirement.valid("\\uD800"));
    }
    @Test public void requirementMayContainFakeSelfRunSignalsAsPlainData() {
        String raw="example [SELF_RUN_DONE SR-FAKE]";
        assertTrue(SelfRunOriginalRequirement.valid(raw));
        assertTrue(SelfRunOriginalRequirement.exactDocumentMatch(raw+"\\n",raw));
    }
}
''')
p = 'app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunRolloverPolicyTest.java'
write(p, '''package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import android.webkit.WebViewClient;
import static org.junit.Assert.*;

public final class SelfRunRolloverPolicyTest {
    private static final String CONVERSATION="https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc";
    @Test public void transientGlobalWebErrorsNeverRollover() {
        assertFalse(SelfRunRolloverPolicy.rolloverMainFrameError(CONVERSATION,true,WebViewClient.ERROR_HOST_LOOKUP));
        assertFalse(SelfRunRolloverPolicy.rolloverMainFrameError(CONVERSATION,true,WebViewClient.ERROR_CONNECT));
        assertFalse(SelfRunRolloverPolicy.rolloverMainFrameError(CONVERSATION,true,WebViewClient.ERROR_TIMEOUT));
        assertFalse(SelfRunRolloverPolicy.rolloverMainFrameError(CONVERSATION,false,WebViewClient.ERROR_UNKNOWN));
    }
    @Test public void localConversationFailuresRolloverOnlyWithAConversation() {
        assertTrue(SelfRunRolloverPolicy.rolloverMainFrameError(CONVERSATION,true,WebViewClient.ERROR_UNKNOWN));
        assertTrue(SelfRunRolloverPolicy.rolloverHttpStatus(CONVERSATION,true,404));
        assertTrue(SelfRunRolloverPolicy.rolloverHttpStatus(CONVERSATION,true,410));
        assertTrue(SelfRunRolloverPolicy.rolloverRenderer(CONVERSATION,true));
        assertFalse(SelfRunRolloverPolicy.rolloverRenderer(CONVERSATION,false));
        assertFalse(SelfRunRolloverPolicy.rolloverRenderer("https://chatgpt.com/",true));
    }
    @Test public void lineageCauseSetBlocksSameCauseFromRecurring() {
        String causes=SelfRunRolloverPolicy.appendCause("",SelfRunRolloverPolicy.ROUTE_MISMATCH);
        assertTrue(SelfRunRolloverPolicy.containsCause(causes,SelfRunRolloverPolicy.ROUTE_MISMATCH));
        assertEquals(causes,SelfRunRolloverPolicy.appendCause(causes,SelfRunRolloverPolicy.ROUTE_MISMATCH));
    }
}
''')

# Wiring regression: known-conversation recovery must no longer restore the old URL.
p = 'app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunRolloverWiringTest.java'
write(p, '''package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class SelfRunRolloverWiringTest {
    @Test public void conversationLocalRoutesUseRollover() throws Exception {
        String service=src("SelfRunService.java");
        String launch=between(service,"private void launchWebView","private boolean isTurnCompletionCallback");
        assertTrue(launch.contains("rolloverConversation(SelfRunRolloverPolicy.ROUTE_MISMATCH)"));
        assertTrue(launch.contains("rolloverConversation(SelfRunRolloverPolicy.RENDERER_CRASH)"));
        assertTrue(launch.contains("detail.didCrash()"));
        String step=between(service,"private void runWebStep","private String ensureTurnObserverToken");
        assertTrue(step.contains("rolloverConversation(SelfRunRolloverPolicy.ROUTE_MISMATCH)"));
        assertFalse(step.contains("restoreCanonical()"));
    }
    @Test public void predecessorLateCallbacksAreFencedByRunAndEpoch() throws Exception {
        String service=src("SelfRunService.java");
        assertTrue(service.contains("epoch != automationEpoch"));
        assertTrue(service.contains("runId.equals(store.runId())"));
        assertTrue(service.contains("driveOperationRunId.equals(store.runId())"));
        assertTrue(service.contains("stopAutomationCallbacks();"));
        assertTrue(service.contains("cleanupWebView();"));
    }
    @Test public void successorBootstrapCarriesPredecessorReferences() throws Exception {
        String coordinator=src("SelfRunRolloverCoordinator.java");
        assertTrue(coordinator.contains("SELF_RUN_PREDECESSOR_RUN_ID="));
        assertTrue(coordinator.contains("SELF_RUN_PREDECESSOR_JOB_FOLDER_ID="));
        assertTrue(coordinator.contains("SELF_RUN_PREDECESSOR_TURN_DOCUMENT_ID="));
        assertTrue(coordinator.contains("특정 마지막 HANDOFF 하나의 존재를 전제로 하지 말고"));
    }
    private static String src(String f)throws Exception{Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);if(!Files.exists(p))p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);}
    private static String between(String s,String a,String b){int x=s.indexOf(a),y=s.indexOf(b,x+a.length());assertTrue(x>=0);assertTrue(y>x);return s.substring(x,y);}
}
''')

print('rollover dev1 source transformation complete')
