from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


store_path = Path("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java")
store = store_path.read_text(encoding="utf-8")

store = replace_once(
    store,
    'if(awaiting){String prompt=get("activeCommandPrompt"),kind=get("activeCommandKind");boolean rewrite=prompt.startsWith("[SELF_RUN_TURN_INFO_REWRITE ");awaiting=false;clearCommandWait(e);if(RETRY_CONTINUE.equals(kind)&&!rewrite){clearPendingCompletion(e);clearPauseAnchor(e);resetCompletionGuard(e);guardArmed=false;guardFingerprint="";}}',
    'if(awaiting){String prompt=get("activeCommandPrompt"),kind=get("activeCommandKind");boolean rewrite=prompt.startsWith("[SELF_RUN_TURN_INFO_REWRITE ");awaiting=false;if(RETRY_CONTINUE.equals(kind)&&!rewrite){invalidateSupersededContinuation(e);clearPauseAnchor(e);guardArmed=false;guardFingerprint="";}else clearCommandWait(e);}',
    "COMMAND_RECEIVED continuation invalidation",
)
store = replace_once(
    store,
    'if(awaiting){awaiting=false;clearCommandWait(e);protocolPause(e,x,"COMMAND_RECEIVED_REQUIRED");rank=3;continue;}',
    'if(awaiting){awaiting=false;protocolPause(e,x,"COMMAND_RECEIVED_REQUIRED");guardArmed=false;guardFingerprint="";rank=3;continue;}',
    "missing ACK protocol pause invalidation",
)
store = replace_once(
    store,
    'if(x.type==DriveSignalParser.Type.INVALID){protocolPause(e,x,x.protocolError.isEmpty()?"DRIVE_PROTOCOL_INVALID":x.protocolError);rank=3;continue;}',
    'if(x.type==DriveSignalParser.Type.INVALID){protocolPause(e,x,x.protocolError.isEmpty()?"DRIVE_PROTOCOL_INVALID":x.protocolError);guardArmed=false;guardFingerprint="";rank=3;continue;}',
    "invalid signal guard invalidation",
)
store = replace_once(
    store,
    'case USER_ACTION_REQUIRED->{rank=3;clearCommandWait(e);pauseEvent(e,x,"사용자 조치 필요");}',
    'case USER_ACTION_REQUIRED->{rank=3;pauseEvent(e,x,"사용자 조치 필요");guardArmed=false;guardFingerprint="";}',
    "USER_ACTION_REQUIRED invalidation",
)
store = replace_once(
    store,
    'case PAUSED->{rank=3;clearCommandWait(e);pauseEvent(e,x,"SelfRun Drive 일시정지");}',
    'case PAUSED->{rank=3;pauseEvent(e,x,"SelfRun Drive 일시정지");guardArmed=false;guardFingerprint="";}',
    "PAUSED invalidation",
)
store = replace_once(
    store,
    'case DONE->{rank=3;clearCommandWait(e);clearPendingCompletion(e);e.putBoolean("active",false).putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_DONE).putString("status","SelfRun Drive 완료");terminal(e,x);}',
    'case DONE->{rank=3;invalidateSupersededContinuation(e);guardArmed=false;guardFingerprint="";e.putBoolean("active",false).putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_DONE).putString("status","SelfRun Drive 완료");terminal(e,x);}',
    "DONE invalidation",
)
store = replace_once(
    store,
    'case APPLY_COMPLETION->{DriveSignalParser.Event completion=decision.event;String raw=completion.raw;if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion())raw=DriveSignalParser.mergeNextInputIfMissing(raw,pendingDriveSignalRaw());e.putBoolean("paused",false)',
    'case APPLY_COMPLETION->{DriveSignalParser.Event completion=decision.event;String raw=completion.raw;if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion())raw=DriveSignalParser.mergeNextInputIfMissing(raw,pendingDriveSignalRaw());invalidateSupersededContinuation(e);e.putBoolean("paused",false)',
    "resume completion invalidation",
)
store = replace_once(
    store,
    'case CONTINUE->{clearPendingCompletion(e);resetCompletionGuard(e);e.putBoolean("paused",false)',
    'case CONTINUE->{invalidateSupersededContinuation(e);e.putBoolean("paused",false)',
    "resume plain continuation invalidation",
)
store = replace_once(
    store,
    'if(blocking==null){e.putString("status","재개 보류 · 기존 pause latch 유지");}else{e.putBoolean("resumeNeedsContinuation",true)',
    'if(blocking==null){e.putString("status","재개 보류 · 기존 pause latch 유지");}else{invalidateSupersededContinuation(e);e.putBoolean("resumeNeedsContinuation",true)',
    "resume blocking invalidation",
)
store = replace_once(
    store,
    'case DONE->{DriveSignalParser.Event done=decision.event;clearPendingCompletion(e);resetCompletionGuard(e);e.putBoolean("active",false)',
    'case DONE->{DriveSignalParser.Event done=decision.event;invalidateSupersededContinuation(e);e.putBoolean("active",false)',
    "resume DONE invalidation",
)
store = replace_once(
    store,
    'case PROTOCOL_ERROR->{e.putBoolean("paused",true)',
    'case PROTOCOL_ERROR->{invalidateSupersededContinuation(e);e.putBoolean("paused",true)',
    "resume protocol error invalidation",
)
store = replace_once(
    store,
    'private static SharedPreferences.Editor clearCommandWait(SharedPreferences.Editor e){return e.putBoolean("awaitingCommandAck",false).putString("activeCommandPrompt","").putString("activeCommandKind","").putString("submissionRetryKind","").putString("submissionRetryReason","").putLong("submissionRetryDueAt",0L).putInt("submissionRetryAttempt",0).putBoolean("submissionRetryReady",false);}\nprivate static void putLatest',
    'private static SharedPreferences.Editor clearCommandWait(SharedPreferences.Editor e){return e.putBoolean("awaitingCommandAck",false).putString("activeCommandPrompt","").putString("activeCommandKind","").putString("submissionRetryKind","").putString("submissionRetryReason","").putLong("submissionRetryDueAt",0L).putInt("submissionRetryAttempt",0).putBoolean("submissionRetryReady",false);}\nprivate SharedPreferences.Editor invalidateSupersededContinuation(SharedPreferences.Editor e){SelfRunProtocol.clearPendingContinuation(runId());clearCommandWait(e);clearPendingCompletion(e);resetCompletionGuard(e);return e;}\nprivate static void putLatest',
    "invalidation helper",
)
store = replace_once(
    store,
    'private void pauseEvent(SharedPreferences.Editor e,DriveSignalParser.Event x,String status){String origin=pauseOriginForDriveSignal(x.type);',
    'private void pauseEvent(SharedPreferences.Editor e,DriveSignalParser.Event x,String status){invalidateSupersededContinuation(e);String origin=pauseOriginForDriveSignal(x.type);',
    "blocking pause invalidation",
)
store = replace_once(
    store,
    'private void protocolPause(SharedPreferences.Editor e,DriveSignalParser.Event x,String code){e.putBoolean("paused",true)',
    'private void protocolPause(SharedPreferences.Editor e,DriveSignalParser.Event x,String code){invalidateSupersededContinuation(e);e.putBoolean("paused",true)',
    "protocol pause invalidation",
)
store_path.write_text(store, encoding="utf-8")

protocol_path = Path("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunProtocol.java")
protocol = protocol_path.read_text(encoding="utf-8")
protocol = replace_once(
    protocol,
    '    static synchronized void requestNextInput(String runId,String nextInput){if(!safeCode(runId))throw new IllegalArgumentException("valid run id required");NextInputCodec.encode(nextInput);if(runId.equals(turnInfoRewriteRunId))turnInfoRewriteRunId="";nextInputRunId=runId;nextInputText=nextInput;}\n    private static synchronized String consumeNextInput',
    '    static synchronized void requestNextInput(String runId,String nextInput){if(!safeCode(runId))throw new IllegalArgumentException("valid run id required");NextInputCodec.encode(nextInput);if(runId.equals(turnInfoRewriteRunId))turnInfoRewriteRunId="";nextInputRunId=runId;nextInputText=nextInput;}\n    static synchronized void clearPendingContinuation(String runId){if(!safeCode(runId))return;if(runId.equals(turnInfoRewriteRunId))turnInfoRewriteRunId="";if(runId.equals(nextInputRunId)){nextInputRunId="";nextInputText="";}}\n    private static synchronized String consumeNextInput',
    "protocol in-memory reservation invalidation",
)
protocol_path.write_text(protocol, encoding="utf-8")

protocol_test_path = Path("app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunNextInputProtocolTest.java")
protocol_test = protocol_test_path.read_text(encoding="utf-8")
if "clearingSupersededContinuationDropsQueuedReservations" not in protocol_test:
    insert = '''\n    @Test public void clearingSupersededContinuationDropsQueuedReservations() {\n        String oldInput = "원격 push를 진행해";\n        SelfRunProtocol.requestNextInput(RUN, oldInput);\n        SelfRunProtocol.clearPendingContinuation(RUN);\n        String plain = SelfRunProtocol.driveContinuation(RUN);\n        assertEquals(2, plain.split("\\\\n", -1).length);\n        assertFalse(plain.endsWith("\\n" + oldInput));\n\n        SelfRunProtocol.requestTurnInfoRewrite(RUN);\n        SelfRunProtocol.clearPendingContinuation(RUN);\n        String afterRewriteClear = SelfRunProtocol.driveContinuation(RUN);\n        assertFalse(afterRewriteClear.startsWith("[SELF_RUN_TURN_INFO_REWRITE "));\n        assertEquals(2, afterRewriteClear.split("\\\\n", -1).length);\n    }\n'''
    pos = protocol_test.rfind("\n}")
    if pos < 0:
        raise SystemExit("protocol test closing brace not found")
    protocol_test = protocol_test[:pos] + insert + protocol_test[pos:]
protocol_test_path.write_text(protocol_test, encoding="utf-8")

new_test = Path("app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunContinuationInvalidationTest.java")
new_test.write_text('''package com.shaterguy.chatgptselfrun;\n\nimport org.junit.Test;\nimport java.nio.file.*;\nimport static org.junit.Assert.*;\n\npublic class SelfRunContinuationInvalidationTest {\n    @Test public void supersedingDriveSignalsClearPreparedAndPendingContinuationState() throws Exception {\n        String store = src("SelfRunStore.java");\n        String helper = between(store, "private SharedPreferences.Editor invalidateSupersededContinuation", "private static void putLatest");\n        assertTrue(helper.contains("SelfRunProtocol.clearPendingContinuation(runId())"));\n        assertTrue(helper.contains("clearCommandWait(e)"));\n        assertTrue(helper.contains("clearPendingCompletion(e)"));\n        assertTrue(helper.contains("resetCompletionGuard(e)"));\n\n        String pause = between(store, "private void pauseEvent", "private void terminal");\n        assertTrue(pause.contains("invalidateSupersededContinuation(e)"));\n        String protocolPause = between(store, "private void protocolPause", "private static boolean validRestoredPhase");\n        assertTrue(protocolPause.contains("invalidateSupersededContinuation(e)"));\n\n        String apply = between(store, "void applyDriveSignals", "void repairGuard");\n        assertTrue(apply.contains("case USER_ACTION_REQUIRED->{rank=3;pauseEvent"));\n        assertTrue(apply.contains("case PAUSED->{rank=3;pauseEvent"));\n        assertTrue(apply.contains("case DONE->{rank=3;invalidateSupersededContinuation(e)"));\n        assertTrue(apply.contains("guardArmed=false;guardFingerprint=\\\"\\\""));\n    }\n\n    @Test public void resumeInvalidatesOnlyWhenAuthorityChangesAndPreservesNoMaterialRestore() throws Exception {\n        String store = src("SelfRunStore.java");\n        String resume = between(store, "void baselineManualResume", "void captureConversationUrl");\n        assertTrue(segment(resume, "case APPLY_COMPLETION", "case CONTINUE").contains("invalidateSupersededContinuation(e)"));\n        assertTrue(segment(resume, "case CONTINUE", "case RESTORE_PHASE").contains("invalidateSupersededContinuation(e)"));\n        assertFalse(segment(resume, "case RESTORE_PHASE", "case KEEP_PAUSED").contains("invalidateSupersededContinuation(e)"));\n\n        String keep = segment(resume, "case KEEP_PAUSED", "case DONE");\n        int nullStart = keep.indexOf("if(blocking==null){");\n        int elseStart = nullStart < 0 ? -1 : keep.indexOf("}else{", nullStart);\n        assertTrue(nullStart >= 0);\n        assertTrue(elseStart > nullStart);\n        assertFalse(keep.substring(nullStart, elseStart).contains("invalidateSupersededContinuation"));\n        assertTrue(keep.substring(elseStart).contains("invalidateSupersededContinuation(e)"));\n\n        assertTrue(segment(resume, "case DONE", "case PROTOCOL_ERROR").contains("invalidateSupersededContinuation(e)"));\n        assertTrue(resume.substring(resume.indexOf("case PROTOCOL_ERROR")).contains("invalidateSupersededContinuation(e)"));\n    }\n\n    private static String src(String f) throws Exception {\n        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + f);\n        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + f);\n        return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);\n    }\n    private static String between(String s, String a, String b) { return s.substring(s.indexOf(a), s.indexOf(b)); }\n    private static String segment(String s, String a, String b) { return between(s, a, b); }\n}\n''', encoding="utf-8")

runtime_path = Path("docs/SELF_RUN_DRIVE_RUNTIME.md")
runtime = runtime_path.read_text(encoding="utf-8")
runtime_note = '''\n\n### Superseded continuation state invalidation\n\nDrive의 post-anchor material signal이 기존 continuation의 authority를 대체하면 이전 `pendingDriveSignalRaw`/completion guard, prepared `activeCommandPrompt`/kind/retry 상태와 메모리 내 NEXT_INPUT·TURN_INFO_REWRITE 예약을 함께 폐기합니다. `USER_ACTION_REQUIRED`, `PAUSED`, protocol error, `DONE`, 새 completion 적용과 plain CONTINUE 결정은 이전 NEXT_INPUT을 재사용하지 않습니다. 반대로 UI 수동 pause 또는 앱 내부 prerequisite에서 새 material signal이 전혀 없어 `pausedFromPhase`를 복구하는 경우에는 동일 in-flight 작업을 계속하기 위해 기존 상태를 보존합니다.\n'''
if "### Superseded continuation state invalidation" not in runtime:
    runtime += runtime_note
runtime_path.write_text(runtime, encoding="utf-8")

proto_doc_path = Path("docs/SELF_RUN_DRIVE_V1_PROTOCOL.md")
proto_doc = proto_doc_path.read_text(encoding="utf-8")
needle = "Drive 문서의 현재 전체 line을 resume baseline으로 덮고 무조건 continuation을 만드는 1.2.1 방식은 사용하지 않습니다."
addition = needle + "\n\npost-anchor `TURN_COMPLETED`, newer blocking signal, protocol error, `DONE` 또는 plain CONTINUE 결정이 기존 continuation을 supersede하면 앱은 이전 pending completion/guard와 prepared command/NEXT_INPUT 예약을 폐기한 뒤 새 authoritative 상태에서만 continuation을 다시 구성합니다. 새 material signal이 없는 `RESTORE_PHASE`는 이 invalidation을 수행하지 않습니다."
proto_doc = replace_once(proto_doc, needle, addition, "protocol supersede documentation")
proto_doc_path.write_text(proto_doc, encoding="utf-8")

print("superseded continuation patch applied")
