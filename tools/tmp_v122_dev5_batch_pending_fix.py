from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


store_path = Path("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java")
store = store_path.read_text(encoding="utf-8")

batch_class = '''static final class DriveBatchPendingState{\n private final boolean work;private String priorRaw;private boolean carryNext;\n DriveBatchPendingState(String mode,boolean pendingCompletion,String pendingRaw){work=MODE_WORK.equals(mode);priorRaw=pendingCompletion&&pendingRaw!=null?pendingRaw:"";carryNext=work&&pendingCompletion&&!priorRaw.isEmpty()&&!DriveSignalParser.workProfile(priorRaw).valid;}\n void supersede(){priorRaw="";carryNext=false;}\n String acceptCompletion(String newerRaw){String accepted=work&&carryNext?DriveSignalParser.mergeNextInputIfMissing(newerRaw,priorRaw):newerRaw;priorRaw=accepted==null?"":accepted;carryNext=work&&!priorRaw.isEmpty()&&!DriveSignalParser.workProfile(priorRaw).valid;return accepted;}\n boolean carryNextForTest(){return carryNext;}\n String rawForTest(){return priorRaw;}\n}\n'''
store = replace_once(
    store,
    'void applyDriveSignals(List<DriveSignalParser.Event> events,long detectedAt,long guardMs){',
    batch_class + 'void applyDriveSignals(List<DriveSignalParser.Event> events,long detectedAt,long guardMs){',
    "batch pending state class",
)
store = replace_once(
    store,
    'SharedPreferences.Editor e=prefs.edit();boolean awaiting=awaitingCommandAck();boolean guardArmed=completionGuardArmed();String guardFingerprint=completionGuardFingerprint();int rank=PHASE_DONE.equals(phase())||PHASE_PAUSED.equals(phase())?3:PHASE_DRIVE_COMMIT_GUARD.equals(phase())?2:0;',
    'SharedPreferences.Editor e=prefs.edit();boolean awaiting=awaitingCommandAck();boolean guardArmed=completionGuardArmed();String guardFingerprint=completionGuardFingerprint();DriveBatchPendingState batchPending=new DriveBatchPendingState(mode(),hasPendingDriveCompletion(),pendingDriveSignalRaw());int rank=PHASE_DONE.equals(phase())||PHASE_PAUSED.equals(phase())?3:PHASE_DRIVE_COMMIT_GUARD.equals(phase())?2:0;',
    "batch pending state initialization",
)
store = replace_once(
    store,
    'if(RETRY_CONTINUE.equals(kind)&&!rewrite){invalidateSupersededContinuation(e);clearPauseAnchor(e);guardArmed=false;guardFingerprint="";}else clearCommandWait(e);',
    'if(RETRY_CONTINUE.equals(kind)&&!rewrite){invalidateSupersededContinuation(e);batchPending.supersede();clearPauseAnchor(e);guardArmed=false;guardFingerprint="";}else clearCommandWait(e);',
    "normal continuation ACK local supersede",
)
store = replace_once(
    store,
    'if(awaiting){awaiting=false;protocolPause(e,x,"COMMAND_RECEIVED_REQUIRED");guardArmed=false;guardFingerprint="";rank=3;continue;}',
    'if(awaiting){awaiting=false;protocolPause(e,x,"COMMAND_RECEIVED_REQUIRED");batchPending.supersede();guardArmed=false;guardFingerprint="";rank=3;continue;}',
    "missing ACK local supersede",
)
store = replace_once(
    store,
    'if(x.type==DriveSignalParser.Type.INVALID){protocolPause(e,x,x.protocolError.isEmpty()?"DRIVE_PROTOCOL_INVALID":x.protocolError);guardArmed=false;guardFingerprint="";rank=3;continue;}',
    'if(x.type==DriveSignalParser.Type.INVALID){protocolPause(e,x,x.protocolError.isEmpty()?"DRIVE_PROTOCOL_INVALID":x.protocolError);batchPending.supersede();guardArmed=false;guardFingerprint="";rank=3;continue;}',
    "invalid local supersede",
)
store = replace_once(
    store,
    'case TURN_COMPLETED->{if(rank<2){rank=2;String raw=x.raw;if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion())raw=DriveSignalParser.mergeNextInputIfMissing(raw,pendingDriveSignalRaw());e.putString("pendingDriveSignalRaw",raw)',
    'case TURN_COMPLETED->{if(rank<2){rank=2;String raw=batchPending.acceptCompletion(x.raw);e.putString("pendingDriveSignalRaw",raw)',
    "completion uses batch pending state",
)
store = replace_once(
    store,
    'case USER_ACTION_REQUIRED->{rank=3;pauseEvent(e,x,"사용자 조치 필요");guardArmed=false;guardFingerprint="";}',
    'case USER_ACTION_REQUIRED->{rank=3;pauseEvent(e,x,"사용자 조치 필요");batchPending.supersede();guardArmed=false;guardFingerprint="";}',
    "user action local supersede",
)
store = replace_once(
    store,
    'case PAUSED->{rank=3;pauseEvent(e,x,"SelfRun Drive 일시정지");guardArmed=false;guardFingerprint="";}',
    'case PAUSED->{rank=3;pauseEvent(e,x,"SelfRun Drive 일시정지");batchPending.supersede();guardArmed=false;guardFingerprint="";}',
    "paused local supersede",
)
store = replace_once(
    store,
    'case DONE->{rank=3;invalidateSupersededContinuation(e);guardArmed=false;guardFingerprint="";e.putBoolean("active",false)',
    'case DONE->{rank=3;invalidateSupersededContinuation(e);batchPending.supersede();guardArmed=false;guardFingerprint="";e.putBoolean("active",false)',
    "done local supersede",
)
store_path.write_text(store, encoding="utf-8")

batch_test = Path("app/src/test/java/com/shaterguy/chatgptselfrun/DriveBatchPendingStateTest.java")
batch_test.write_text('''package com.shaterguy.chatgptselfrun;\n\nimport org.junit.Test;\nimport static org.junit.Assert.*;\n\npublic class DriveBatchPendingStateTest {\n    private static final String RUN = "SR-20260816-011429-9SZ8A4";\n\n    @Test public void normalContinueAckDropsPriorNextBeforeSameBatchWorkCompletion() {\n        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("원격 push를 진행해"));\n        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior);\n        assertTrue(state.carryNextForTest());\n        state.supersede();\n        String accepted = state.acceptCompletion(completion("MODEL=sol REASONING=xhigh"));\n        NextInputCodec.Decoded next = DriveSignalParser.nextInput(accepted);\n        assertFalse(next.present);\n        assertFalse(state.carryNextForTest());\n    }\n\n    @Test public void rewriteAckKeepsPriorNextForCorrectedCompletionAcrossSameBatch() {\n        String oldInput = "승인할게";\n        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode(oldInput));\n        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior);\n        String accepted = state.acceptCompletion(completion("MODEL=sol REASONING=xhigh"));\n        NextInputCodec.Decoded next = DriveSignalParser.nextInput(accepted);\n        assertTrue(next.present);\n        assertTrue(next.valid);\n        assertEquals(oldInput, next.text);\n        assertFalse(state.carryNextForTest());\n    }\n\n    @Test public void newerCompletionOwnNextAlwaysWinsOverRewriteCarry() {\n        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));\n        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior);\n        String accepted = state.acceptCompletion(completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("new")));\n        NextInputCodec.Decoded next = DriveSignalParser.nextInput(accepted);\n        assertTrue(next.present);\n        assertTrue(next.valid);\n        assertEquals("new", next.text);\n    }\n\n    @Test public void blockingOrDoneSupersedeMakesLaterCompletionUnableToRecoverOldNext() {\n        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));\n        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior);\n        state.supersede();\n        assertEquals("", state.rawForTest());\n        String accepted = state.acceptCompletion(completion("MODEL=sol REASONING=xhigh"));\n        assertFalse(DriveSignalParser.nextInput(accepted).present);\n    }\n\n    @Test public void chatModeNeverCarriesWorkRewriteNext() {\n        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));\n        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_CHAT, true, prior);\n        String accepted = state.acceptCompletion(completion(""));\n        assertFalse(DriveSignalParser.nextInput(accepted).present);\n    }\n\n    private static String completion(String fields) {\n        return "[2026.08.16 | 06:00:00] [SELF_RUN_TURN_COMPLETED " + RUN\n                + (fields == null || fields.isEmpty() ? "" : " " + fields) + "]";\n    }\n}\n''', encoding="utf-8")

source_test_path = Path("app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunContinuationInvalidationTest.java")
source_test = source_test_path.read_text(encoding="utf-8")
if "sameBatchAckAndCompletionUseTransactionLocalPendingAuthority" not in source_test:
    method = '''\n    @Test public void sameBatchAckAndCompletionUseTransactionLocalPendingAuthority() throws Exception {\n        String store = src("SelfRunStore.java");\n        String apply = between(store, "void applyDriveSignals", "void repairGuard");\n        assertTrue(apply.contains("DriveBatchPendingState batchPending=new DriveBatchPendingState(mode(),hasPendingDriveCompletion(),pendingDriveSignalRaw())"));\n        assertTrue(apply.contains("if(RETRY_CONTINUE.equals(kind)&&!rewrite){invalidateSupersededContinuation(e);batchPending.supersede()"));\n        assertTrue(apply.contains("String raw=batchPending.acceptCompletion(x.raw)"));\n        assertFalse(apply.contains("MODE_WORK.equals(mode())&&hasPendingDriveCompletion())raw=DriveSignalParser.mergeNextInputIfMissing(raw,pendingDriveSignalRaw())"));\n    }\n'''
    marker = '\n    private static String src(String f) throws Exception {'
    if marker not in source_test:
        raise SystemExit("source test helper marker not found")
    source_test = source_test.replace(marker, method + marker, 1)
source_test_path.write_text(source_test, encoding="utf-8")

print("batch pending patch applied")
