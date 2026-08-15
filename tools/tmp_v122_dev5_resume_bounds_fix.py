from pathlib import Path

STORE = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java')
RESUME_TEST = Path('app/src/test/java/com/shaterguy/chatgptselfrun/ResumeDriveTransactionTest.java')
BATCH_TEST = Path('app/src/test/java/com/shaterguy/chatgptselfrun/DriveBatchPendingStateTest.java')
INVALIDATION_TEST = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunContinuationInvalidationTest.java')
PAUSE_TEST = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunPauseResumeTest.java')

s = STORE.read_text(encoding='utf-8')
old = ' ResumeDriveTransaction(String mode,boolean awaiting,String kind,String prompt,boolean pendingCompletion,String pendingRaw,boolean carryAuthorized,boolean guardArmed,String guardFingerprint){this.awaiting=awaiting;this.kind=kind==null?"":kind;rewrite=prompt!=null&&prompt.startsWith("[SELF_RUN_TURN_INFO_REWRITE ");pending=new DriveBatchPendingState(mode,pendingCompletion,pendingRaw,carryAuthorized);this.guardArmed=guardArmed;this.guardFingerprint=guardFingerprint==null?"":guardFingerprint;}\n void observe(List<DriveSignalParser.Event> events,int anchorCursor,int totalCount){if(consumedCursor<0)consumedCursor=Math.max(0,anchorCursor);if(events==null)return;'
new = ' ResumeDriveTransaction(String mode,boolean awaiting,String kind,String prompt,boolean pendingCompletion,String pendingRaw,boolean carryAuthorized,boolean guardArmed,String guardFingerprint){this.awaiting=awaiting;this.kind=kind==null?"":kind;rewrite=prompt!=null&&prompt.startsWith("[SELF_RUN_TURN_INFO_REWRITE ");pending=new DriveBatchPendingState(mode,pendingCompletion,pendingRaw,carryAuthorized);this.guardArmed=guardArmed;this.guardFingerprint=guardFingerprint==null?"":guardFingerprint;}\n void validateBounds(int anchorCursor,int alreadyConsumed,int totalCount){if(anchorCursor<0||alreadyConsumed<0||totalCount<anchorCursor||totalCount<alreadyConsumed){structuralError=true;error="RESUME_ANCHOR_CURSOR_INVALID";errorEvent=null;material=null;rank=3;consumedCursor=Math.max(0,alreadyConsumed);}}\n void observe(List<DriveSignalParser.Event> events,int anchorCursor,int totalCount){if(structuralError)return;if(consumedCursor<0)consumedCursor=Math.max(0,anchorCursor);if(events==null)return;'
if old not in s:
    raise SystemExit('transaction constructor/observe marker missing')
s = s.replace(old, new, 1)

old = 'if(RETRY_CONTINUE.equals(kind)&&rewrite){e.putBoolean("rewriteCarryAuthorized",true);batchPending.authorizeCarry();}'
new = 'if(RETRY_CONTINUE.equals(kind)&&rewrite){batchPending.authorizeCarry();e.putBoolean("rewriteCarryAuthorized",batchPending.carryNextForTest());}'
if old not in s:
    raise SystemExit('normal rewrite authorization marker missing')
s = s.replace(old, new, 1)

old = 'DriveResumePolicy.Origin origin=DriveResumePolicy.parseOrigin(pauseAnchorOrigin());ResumeDriveTransaction tx=new ResumeDriveTransaction(mode(),awaitingCommandAck(),get("activeCommandKind"),get("activeCommandPrompt"),hasPendingDriveCompletion(),pendingDriveSignalRaw(),rewriteCarryAuthorized(),completionGuardArmed(),completionGuardFingerprint());tx.observe(postAnchor,pauseAnchorCursor(),totalCount);int resumeCursor=tx.committedCursor(driveSignalCursor());'
new = 'DriveResumePolicy.Origin origin=DriveResumePolicy.parseOrigin(pauseAnchorOrigin());int priorCursor=driveSignalCursor();ResumeDriveTransaction tx=new ResumeDriveTransaction(mode(),awaitingCommandAck(),get("activeCommandKind"),get("activeCommandPrompt"),hasPendingDriveCompletion(),pendingDriveSignalRaw(),rewriteCarryAuthorized(),completionGuardArmed(),completionGuardFingerprint());tx.validateBounds(pauseAnchorCursor(),priorCursor,totalCount);tx.observe(postAnchor,pauseAnchorCursor(),totalCount);int resumeCursor=tx.committedCursor(priorCursor);'
if old not in s:
    raise SystemExit('baseline transaction marker missing')
s = s.replace(old, new, 1)

old = 'if(!tx.error().isEmpty()){if(tx.errorEvent()!=null)protocolPause(e,tx.errorEvent(),tx.error());else{invalidateSupersededContinuation(e);e.putBoolean("paused",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode",safe(tx.error())).putString("lastErrorMessage","Drive resume ordered reconciliation이 fail-closed 처리되었습니다.").putString("status","재개 차단 · "+safe(tx.error()));}commitOrThrow(e);syncHistory();return;}'
new = 'if(!tx.error().isEmpty()){if(tx.errorEvent()!=null)protocolPause(e,tx.errorEvent(),tx.error());else{invalidateSupersededContinuation(e);e.putBoolean("paused",true).putBoolean("active",true).putBoolean("resumeNeedsContinuation",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode",safe(tx.error())).putString("lastErrorMessage","Drive resume ordered reconciliation이 fail-closed 처리되었습니다.").putString("status","재개 차단 · "+safe(tx.error()));recordPauseAnchor(e,PAUSE_ORIGIN_AI_PAUSED,tx.error(),pauseAnchorPhase().isEmpty()?PHASE_WAIT_DRIVE_COMMIT:pauseAnchorPhase(),resumeCursor,tx.lastProcessed());}commitOrThrow(e);syncHistory();return;}'
if old not in s:
    raise SystemExit('resume structural error marker missing')
s = s.replace(old, new, 1)
STORE.write_text(s, encoding='utf-8')

rt = RESUME_TEST.read_text(encoding='utf-8')
marker = '    @Test public void structuralFailureDoesNotAdvancePastLastActuallyProcessedCursor() {'
methods = '''    @Test public void documentShrinkBelowDurableCursorFailsClosedWithoutCursorRegression() {\n        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", "", false, false, "");\n        tx.validateBounds(7, 9, 8);\n        tx.observe(Collections.emptyList(), 7, 8);\n        assertTrue(tx.structuralErrorForTest());\n        assertEquals("RESUME_ANCHOR_CURSOR_INVALID", tx.error());\n        assertEquals(9, tx.consumedCursor());\n        assertEquals(9, tx.committedCursor(9));\n        assertNull(tx.lastProcessed());\n    }\n\n    @Test public void documentShrinkBelowAnchorFailsClosed() {\n        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", "", false, false, "");\n        tx.validateBounds(9, 9, 8);\n        assertTrue(tx.structuralErrorForTest());\n        assertEquals("RESUME_ANCHOR_CURSOR_INVALID", tx.error());\n    }\n\n'''
if marker not in rt:
    raise SystemExit('resume structural test marker missing')
rt = rt.replace(marker, methods + marker, 1)
RESUME_TEST.write_text(rt, encoding='utf-8')

bt = BATCH_TEST.read_text(encoding='utf-8')
marker = '    @Test public void normalContinueAckDropsPriorNextBeforeSameBatchWorkCompletion() {'
method = '''    @Test public void validWorkProfileCannotReceiveRewriteCarryAuthorization() {\n        String prior = completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));\n        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior);\n        state.authorizeCarry();\n        assertFalse(state.carryNextForTest());\n    }\n\n'''
if marker not in bt:
    raise SystemExit('batch auth test marker missing')
bt = bt.replace(marker, method + marker, 1)
BATCH_TEST.write_text(bt, encoding='utf-8')

it = INVALIDATION_TEST.read_text(encoding='utf-8')
it = it.replace('assertTrue(apply.contains("putBoolean(\\\"rewriteCarryAuthorized\\\",true);batchPending.authorizeCarry()"));', 'assertTrue(apply.contains("batchPending.authorizeCarry();e.putBoolean(\\\"rewriteCarryAuthorized\\\",batchPending.carryNextForTest())"));')
INVALIDATION_TEST.write_text(it, encoding='utf-8')

pt = PAUSE_TEST.read_text(encoding='utf-8')
old = '        assertTrue(baseline.contains("int resumeCursor=tx.committedCursor(driveSignalCursor())"));\n'
new = '        assertTrue(baseline.contains("int priorCursor=driveSignalCursor()"));\n        assertTrue(baseline.contains("tx.validateBounds(pauseAnchorCursor(),priorCursor,totalCount)"));\n        assertTrue(baseline.contains("int resumeCursor=tx.committedCursor(priorCursor)"));\n'
if old not in pt:
    raise SystemExit('pause cursor source assertion marker missing')
pt = pt.replace(old, new, 1)
old = '        assertTrue(baseline.contains("if(tx.lastProcessed()!=null)putLatest(e,tx.lastProcessed())"));\n'
new = old + '        assertTrue(baseline.contains("recordPauseAnchor(e,PAUSE_ORIGIN_AI_PAUSED,tx.error()"));\n'
pt = pt.replace(old, new, 1)
PAUSE_TEST.write_text(pt, encoding='utf-8')

print('resume bounds fail-closed patch applied')
