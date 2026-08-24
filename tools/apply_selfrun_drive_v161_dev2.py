from pathlib import Path
import re


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)


def regex_once(text, pattern, replacement, label):
    updated, count = re.subn(pattern, lambda _: replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected one regex match, got {count}")
    return updated


# AC-01 / NR-01: one common Chat/Work STOP -> SEND/idle observer. Work can render
# a strongly identified SEND control beside the composer form, so search only the
# immediate composer scope instead of broadening to arbitrary page controls.
path = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunContinuationDom.java"
text = read(path)
new_controls = r'''    private static String controls(String sendKey) {
        return "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const label=e=>String((e?.getAttribute?.('aria-label')||'')+' '+(e?.title||'')+' '+(e?.innerText||e?.textContent||'')).replace(/\\s+/g,' ').trim().toLowerCase();"
                + "const testid=e=>String(e?.dataset?.testid||'').replace(/\\s+/g,' ').trim().toLowerCase();"
                + "const buttonLike=e=>!!e&&e.matches?.('button,[role=\"button\"]');"
                + "const composerRoot=composer?.closest?.('form')||composer?.closest?.('[data-type=\"unified-composer\"]')||composer?.closest?.('[class*=\"composer\"]')||composer?.parentElement;"
                + "const composerScope=composerRoot?.parentElement||composerRoot;"
                + "const inComposer=e=>!!e&&!!composerRoot&&composerRoot.contains(e);"
                + "const inComposerScope=e=>!!e&&!!composerScope&&composerScope.contains(e);"
                + "const composerEditable=()=>visible(composer)&&composer.getAttribute?.('aria-disabled')!=='true'&&!composer.disabled&&!composer.readOnly&&(('value'in composer)||composer.isContentEditable);"
                + "const stopSemantic=e=>{const id=testid(e),text=label(e);return /(^|[-_:])(?:composer-)?stop(?:[-_:]|$)/.test(id)||/\\bstop(?:\\s+(?:generating|streaming|responding))?\\b/.test(text)||/(?:생성|응답)?\\s*(?:중지|정지)/.test(text);};"
                + "const voiceSemantic=e=>{const id=testid(e),text=label(e);return /(^|[-_:])(?:composer-)?(?:speech|voice|mic|microphone|dictation)(?:-mode|-button)?(?:[-_:]|$)/.test(id)||/\\b(?:start\\s+)?(?:voice(?:\\s+(?:mode|input))?|dictat(?:e|ion)|microphone|mic)\\b/.test(text)||/(?:음성\\s*(?:모드|입력)?|받아쓰기|마이크)/.test(text);};"
                + "const sendSemantic=e=>{const id=testid(e),text=label(e);return /(^|[-_:])(?:send-button|composer-submit-button)(?:[-_:]|$)/.test(id)||/\\b(?:send|submit)(?:\\s+(?:message|prompt))?\\b|보내기/.test(text);};"
                + "const isStop=e=>!!e&&buttonLike(e)&&inComposer(e)&&stopSemantic(e);"
                + "const isVoice=e=>!!e&&buttonLike(e)&&inComposer(e)&&voiceSemantic(e);"
                + "const isSend=e=>!!e&&buttonLike(e)&&inComposer(e)&&!stopSemantic(e)&&!voiceSemantic(e)&&(sendSemantic(e)||e.matches?.('button[type=\"submit\"]'));"
                + "const isAdjacentSend=e=>!!e&&buttonLike(e)&&!inComposer(e)&&inComposerScope(e)&&!voiceSemantic(e)&&sendSemantic(e);"
                + "const userMessageCount=()=>document.querySelectorAll('[data-message-author-role=\"user\"]').length;"
                + "const controlState=()=>{const calibrated=__srFind(" + q(sendKey) + ");const controls=composerRoot?[...composerRoot.querySelectorAll('button,[role=\"button\"]')].filter(visible):[];const adjacentControls=composerScope&&composerScope!==composerRoot?[...composerScope.querySelectorAll('button,[role=\"button\"]')].filter(visible).filter(e=>!inComposer(e)):[];if(calibrated&&visible(calibrated)&&!controls.includes(calibrated)&&!adjacentControls.includes(calibrated))adjacentControls.unshift(calibrated);const stop=controls.find(isStop);if(stop)return{state:'" + STOP + "',send:null};const send=calibrated&&visible(calibrated)&&(isSend(calibrated)||isAdjacentSend(calibrated))?calibrated:(controls.find(isSend)||adjacentControls.find(isAdjacentSend));if(send){if(send.disabled||send.getAttribute('aria-disabled')==='true')return{state:'" + SEND_DISABLED + "',send};return{state:'" + SEND_ENABLED + "',send};}if(composerEditable())return{state:'" + COMPOSER_IDLE + "',send:null};return{state:'" + UNKNOWN + "',send:null};};";
    }
'''
text = regex_once(
    text,
    r"    private static String controls\(String sendKey\) \{.*?\n    \}\n\n    private static String composerOps\(\)",
    new_controls + "\n    private static String composerOps()",
    "common completion controls",
)
write(path, text)


# AC-01 / AC-02: WAIT healthchecks must not become permanently stuck when a heavy
# Work page loses an evaluateJavascript callback. Retry the same common event observer
# in-place. Also avoid navigation/reload after callback loss in preference/submission
# phases because the prior script may already have mutated or submitted state.
path = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java"
text = read(path)
text = replace_once(
    text,
    'static boolean shouldGuardContinuationCallback(String phase){return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}',
    'static boolean shouldGuardContinuationCallback(String phase){return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)||SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}',
    "guard wait callback",
)
new_deadline = r'''private void scheduleContinuationCallbackDeadline(WebView active,int webGeneration,String runId,int evaluationId,String phase){
    handler.postDelayed(()->{
        if(active!=webView||webGeneration!=generation||!runId.equals(store.runId())||evaluationId!=webEvaluationId||!domInFlight||!canRun()||!phase.equals(store.phase())||!shouldGuardContinuationCallback(phase))return;
        domInFlight=false;webEvaluationId++;
        if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)){recoverBootstrapSendCallback();return;}
        runLog.record(store,"DOM_RESULT",SelfRunWebDiagnostics.callbackTimeoutDetail(phase));
        releaseWakeLock();
        scheduleWeb(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)?TURN_OBSERVER_HEALTHCHECK_MS:1200L);
    },CONTINUATION_CALLBACK_TIMEOUT_MS);
}
'''
text = regex_once(
    text,
    r"private void scheduleContinuationCallbackDeadline\(WebView active,int webGeneration,String runId,int evaluationId,String phase\)\{.*?\n\}\n\nprivate void recoverBootstrapSendCallback\(\)",
    new_deadline + "\nprivate void recoverBootstrapSendCallback()",
    "callback recovery",
)
write(path, text)


# AC-02: UI pause/resume restores the exact Web phase and its original phase identity.
# This is common Chat/Work behavior; Work simply has additional preference phases.
path = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java"
text = read(path)
text = replace_once(
    text,
    '.putString("creationStage",CREATION_NONE).putString("pausedFromPhase","").putBoolean("resumeNeedsContinuation",false)',
    '.putString("creationStage",CREATION_NONE).putString("pausedFromPhase","").putLong("pausedFromPhaseStartedAt",0L).putBoolean("resumeNeedsContinuation",false)',
    "init pause timestamp",
)
text = replace_once(
    text,
    '    String pausedFromPhase() { return get("pausedFromPhase"); }\n    boolean resumeNeedsContinuation() { return prefs.getBoolean("resumeNeedsContinuation", false); }',
    '    String pausedFromPhase() { return get("pausedFromPhase"); }\n    long pausedFromPhaseStartedAt() { return prefs.getLong("pausedFromPhaseStartedAt", 0L); }\n    boolean resumeNeedsContinuation() { return prefs.getBoolean("resumeNeedsContinuation", false); }',
    "pause timestamp getter",
)
old_enter = '''    void enterPause(String priorPhase, boolean needsContinuation) {
        commitOrThrow(prefs.edit().putString("pausedFromPhase", safe(priorPhase)).putBoolean("resumeNeedsContinuation", needsContinuation)
                .putBoolean("paused", true).putString("phase", PHASE_PAUSED)
                .putLong("phaseStartedAt", System.currentTimeMillis())); syncHistory();
    }
'''
new_enter = '''    void enterPause(String priorPhase, boolean needsContinuation) {
        long priorStartedAt = phaseStartedAt();
        commitOrThrow(prefs.edit().putString("pausedFromPhase", safe(priorPhase)).putLong("pausedFromPhaseStartedAt", priorStartedAt)
                .putBoolean("resumeNeedsContinuation", needsContinuation).putBoolean("paused", true).putString("phase", PHASE_PAUSED)
                .putLong("phaseStartedAt", System.currentTimeMillis())); syncHistory();
    }
'''
text = replace_once(text, old_enter, new_enter, "preserve phase identity")
match = re.search(r"void beginManualResumeOverride\(\)\{.*?\}\nvoid baselineManualResume", text, flags=re.S)
if not match:
    raise SystemExit("manual resume method not found")
new_resume = '''static boolean isManualResumeWebPhase(String phase){return PHASE_BOOTSTRAP.equals(phase)||PHASE_BOOTSTRAP_MODEL.equals(phase)||PHASE_BOOTSTRAP_REASONING.equals(phase)||PHASE_BOOTSTRAP_SEND.equals(phase)||PHASE_WAIT_TURN_COMPLETION.equals(phase)||PHASE_APPLY_PREFS.equals(phase)||PHASE_APPLY_REASONING.equals(phase)||PHASE_SEND_CONTINUE.equals(phase);}
void beginManualResumeOverride(){
 String prior=pausedFromPhase();
 if(isManualResumeWebPhase(prior)){
  long priorStarted=pausedFromPhaseStartedAt(),restoredStarted=priorStarted>0L?priorStarted:System.currentTimeMillis();
  commitOrThrow(clearWatchdogClaimFields(prefs.edit()).putBoolean("terminalSideEffectPending",false).putString("terminalSideEffectType","").putString("terminalSideEffectRunId","").putString("terminalSideEffectCommitId","").putBoolean("paused",false).putBoolean("active",true).putBoolean("userStopped",false).putBoolean("resumeNeedsContinuation",false).putString("phase",prior).putString("status","사용자 재개 · 이전 Web 단계 복원").putLong("phaseStartedAt",restoredStarted).putString("pausedFromPhase","").putLong("pausedFromPhaseStartedAt",0L));syncHistory();return;
 }
 if(PHASE_DRIVE_ATTACHMENT_UPLOAD.equals(prior)&&turnDocumentId().isEmpty()){commitOrThrow(clearWatchdogClaimFields(clearCommandWait(prefs.edit())).putBoolean("terminalSideEffectPending",false).putString("terminalSideEffectType","").putString("terminalSideEffectRunId","").putString("terminalSideEffectCommitId","").putBoolean("paused",false).putBoolean("active",true).putBoolean("userStopped",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_DRIVE_ATTACHMENT_UPLOAD).putString("status","사용자 재개 · 첨부파일 Drive 업로드 재확인").putLong("phaseStartedAt",System.currentTimeMillis()).putString("pausedFromPhase","").putLong("pausedFromPhaseStartedAt",0L));syncHistory();return;}
 commitOrThrow(clearWatchdogClaimFields(clearCommandWait(prefs.edit())).putBoolean("terminalSideEffectPending",false).putString("terminalSideEffectType","").putString("terminalSideEffectRunId","").putString("terminalSideEffectCommitId","").putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","").putLong("commitDetectedAt",0L).putBoolean("paused",false).putBoolean("active",true).putBoolean("userStopped",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_RESUME_BASELINE).putString("status","사용자 재개 override · Drive 최신 신호 baseline 확인").putLong("phaseStartedAt",System.currentTimeMillis()).putString("pausedFromPhase","").putLong("pausedFromPhaseStartedAt",0L));syncHistory();
}
void baselineManualResume'''
text = text[: match.start()] + new_resume + text[match.end() :]
write(path, text)


# AC-03: when the durable NEXT TURN reservation is actually consumed, clear the
# visible EditText even if it still has focus. Do not overwrite unrelated fresh typing.
path = "app/src/main/java/com/shaterguy/chatgptselfrun/MainActivity.java"
text = read(path)
text = replace_once(
    text,
    '    private Button stopButton;\n    private Button currentLogsButton;',
    '    private Button stopButton;\n    private Button currentLogsButton;\n    private String lastNextInputRunId = "";\n    private String lastNextInputStored = "";',
    "next input render state",
)
text = replace_once(
    text,
    '        if (!nextInputEditor.hasFocus()) nextInputEditor.setText(stored);',
    '        boolean runChanged = !runId.equals(lastNextInputRunId);\n        boolean reservationConsumed = runId.equals(lastNextInputRunId) && !lastNextInputStored.isEmpty() && stored.isEmpty();\n        if (runChanged || !nextInputEditor.hasFocus() || reservationConsumed) nextInputEditor.setText(stored);\n        lastNextInputRunId = runId;\n        lastNextInputStored = stored;',
    "visible next input clear",
)
write(path, text)


# Unit contract updates for the common completion engine.
path = "app/src/test/java/com/shaterguy/chatgptselfrun/ChatWorkContinuationContractTest.java"
text = read(path)
text = replace_once(
    text,
    "        assertFalse(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_WAIT_TURN_COMPLETION));",
    "        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_WAIT_TURN_COMPLETION));",
    "WAIT callback contract",
)
marker = '''        assertTrue(service.contains("\\\"CONTINUE_CLICKED\\\".equals(status)"));
        assertTrue(service.contains("store.beginTurnCompletionWait"));
'''
if marker in text:
    text = text.replace(marker, marker + '''        String callbackRecovery = section(service, "private void scheduleContinuationCallbackDeadline", "private void recoverBootstrapSendCallback");
        assertTrue(callbackRecovery.contains("PHASE_WAIT_TURN_COMPLETION"));
        assertTrue(callbackRecovery.contains("TURN_OBSERVER_HEALTHCHECK_MS"));
        assertFalse(callbackRecovery.contains("restoreCanonical()"));
''', 1)
write(path, text)


# Android WebView regression for the Work layout difference, still using the common
# observer. Global STOP buttons remain ignored; only a strong adjacent SEND is added.
path = "app/src/androidTest/java/com/shaterguy/chatgptselfrun/WorkPreferenceDomWebViewTest.java"
text = read(path)
insertion_point = "    @Test public void turnObserverRebindsInPlaceAfterComposerDomReplacement() throws Exception {"
test_case = '''    @Test public void turnObserverCompletesWhenSendMovesBesideComposerForm() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            AtomicReference<String> callbackUrl = new AtomicReference<>();
            CountDownLatch completionSeen = new CountDownLatch(1);
            loadContinuationFixture(scenario, web,
                    "<div id='stop' data-selfrun-scope='composer' role='button' data-testid='stop-stream-action' aria-label='Stop streaming'>Stop</div>",
                    callbackUrl, completionSeen);

            JSONObject armed = evaluate(scenario, web,
                    SelfRunContinuationDom.observeTurnCompletion(
                            CONVERSATION_URL, OBSERVER_RUN_ID, OBSERVER_TOKEN, 200L, false));
            assertEquals("OBSERVER_ARMED", armed.getString("status"));

            evaluate(scenario, web, "(()=>{document.getElementById('stop').remove();"
                    + "const composer=document.getElementById('prompt-textarea');composer.setAttribute('aria-disabled','true');"
                    + "const send=document.createElement('button');send.id='adjacent-send';send.type='button';send.dataset.testid='send-button';send.setAttribute('aria-label','Send message');send.textContent='Send';"
                    + "document.getElementById('continuation-controls').appendChild(send);return JSON.stringify({status:'IDLE_READY'});})()");

            assertTrue("Adjacent SEND did not complete the common turn observer",
                    completionSeen.await(5, TimeUnit.SECONDS));
            assertTrue(callbackUrl.get().contains("selfrun-drive://turn-completed"));
        }
    }

'''
text = replace_once(text, insertion_point, test_case + insertion_point, "adjacent send observer test")
old_callback = '''                    if (callbackUrl != null) callbackUrl.updateAndGet(previous -> previous == null || previous.isEmpty() ? requested : previous + "\\n" + requested);
                    if (callbackSeen != null) callbackSeen.countDown();
                    return true;
'''
new_callback = '''                    if (callbackUrl != null) callbackUrl.updateAndGet(previous -> previous == null || previous.isEmpty() ? requested : previous + "\\n" + requested);
                    if (callbackSeen != null && requested.startsWith("selfrun-drive://turn-completed")) callbackSeen.countDown();
                    return true;
'''
text = replace_once(text, old_callback, new_callback, "completion-only latch")
write(path, text)


# DEV identity and the two stale prerelease assertions found by the existing canonical CI.
path = "app/build.gradle"
text = read(path)
text = replace_once(text, "selfRunDriveVersionCode = 1000091", "selfRunDriveVersionCode = 1000092", "dev2 version code")
text = replace_once(text, "selfRunDriveVersionName = '1.6.1-dev1'", "selfRunDriveVersionName = '1.6.1-dev2'", "dev2 version name")
write(path, text)

for path in [
    "app/src/test/java/com/shaterguy/chatgptselfrun/AttachmentUploadPolicyTest.java",
    "app/src/test/java/com/shaterguy/chatgptselfrun/BootstrapStageAndDirectPickerPolicyTest.java",
]:
    text = read(path)
    text = text.replace("selfRunDriveVersionCode = 1000091", "selfRunDriveVersionCode = 1000092")
    text = text.replace("selfRunDriveVersionName = '1.6.1-dev1'", "selfRunDriveVersionName = '1.6.1-dev2'")
    text = text.replace("implementation 'com.google.android.gms:play-services-auth:21.6.1-dev1'", "implementation 'com.google.android.gms:play-services-auth:21.6.0'")
    write(path, text)

print("SelfRun Drive v1.6.1-dev2 patch applied")
