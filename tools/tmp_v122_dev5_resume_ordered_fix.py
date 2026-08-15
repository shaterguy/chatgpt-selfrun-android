from pathlib import Path

STORE = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java')
PAUSE_TEST = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunPauseResumeTest.java')
TX_TEST = Path('app/src/test/java/com/shaterguy/chatgptselfrun/ResumeDriveTransactionTest.java')

s = STORE.read_text(encoding='utf-8')

resume_class = r'''static final class ResumeDriveTransaction{
 private final String kind;private final boolean rewrite;private final DriveBatchPendingState pending;
 private boolean awaiting,acked,normalAck,guardArmed;private String guardFingerprint,error="";private DriveSignalParser.Event errorEvent,material;private int rank;
 ResumeDriveTransaction(String mode,boolean awaiting,String kind,String prompt,boolean pendingCompletion,String pendingRaw,boolean guardArmed,String guardFingerprint){this.awaiting=awaiting;this.kind=kind==null?"":kind;rewrite=prompt!=null&&prompt.startsWith("[SELF_RUN_TURN_INFO_REWRITE ");pending=new DriveBatchPendingState(mode,pendingCompletion,pendingRaw);this.guardArmed=guardArmed;this.guardFingerprint=guardFingerprint==null?"":guardFingerprint;}
 void observe(List<DriveSignalParser.Event> events,int anchorCursor,int totalCount){if(events==null)return;for(DriveSignalParser.Event x:events){if(!error.isEmpty())return;if(x==null||x.cursor<=anchorCursor||x.cursor>totalCount){error="RESUME_POST_ANCHOR_SIGNAL_INVALID";errorEvent=x;return;}if(x.type==DriveSignalParser.Type.TURN_COMPLETED&&guardArmed&&DriveSignalParser.completionFingerprint(x.raw).equals(guardFingerprint))continue;if(awaiting){if(x.type==DriveSignalParser.Type.COMMAND_RECEIVED){awaiting=false;acked=true;normalAck=RETRY_CONTINUE.equals(kind)&&!rewrite;if(normalAck){pending.supersede();guardArmed=false;guardFingerprint="";}continue;}error="COMMAND_RECEIVED_REQUIRED";errorEvent=x;return;}if(x.type==DriveSignalParser.Type.INVALID){error=x.protocolError.isEmpty()?"RESUME_PROTOCOL_INVALID":x.protocolError;errorEvent=x;return;}switch(x.type){case COMMAND_RECEIVED->{}case TURN_COMPLETED->{if(rank<2){rank=2;material=x;guardArmed=true;guardFingerprint=DriveSignalParser.completionFingerprint(x.raw);}}case USER_ACTION_REQUIRED,PAUSED,DONE->{rank=3;material=x;guardArmed=false;guardFingerprint="";}case INVALID->throw new IllegalStateException("handled above");}}}
 boolean acked(){return acked;}boolean normalContinueAck(){return acked&&normalAck;}boolean rewriteAck(){return acked&&RETRY_CONTINUE.equals(kind)&&rewrite;}boolean awaitingForTest(){return awaiting;}String error(){return error;}DriveSignalParser.Event errorEvent(){return errorEvent;}DriveSignalParser.Event materialForTest(){return material;}
 List<DriveSignalParser.Event> policyEvents(){return material==null?java.util.Collections.emptyList():java.util.Collections.singletonList(material);}String acceptCompletion(String raw){return pending.acceptCompletion(raw);}
}
'''

if 'static final class ResumeDriveTransaction{' not in s:
    insert_at = s.index('void applyDriveSignals(', s.index('static final class DriveBatchPendingState{'))
    s = s[:insert_at] + resume_class + s[insert_at:]

start = s.index('void baselineManualResume(List<DriveSignalParser.Event> postAnchor,int totalCount,DriveSignalParser.Event latest){')
end = s.index('void baselineManualResume(int totalCount,DriveSignalParser.Event latest)', start)
new_baseline = r'''void baselineManualResume(List<DriveSignalParser.Event> postAnchor,int totalCount,DriveSignalParser.Event latest){
 DriveResumePolicy.Origin origin=DriveResumePolicy.parseOrigin(pauseAnchorOrigin());ResumeDriveTransaction tx=new ResumeDriveTransaction(mode(),awaitingCommandAck(),get("activeCommandKind"),get("activeCommandPrompt"),hasPendingDriveCompletion(),pendingDriveSignalRaw(),completionGuardArmed(),completionGuardFingerprint());tx.observe(postAnchor,pauseAnchorCursor(),totalCount);SharedPreferences.Editor e=prefs.edit().putInt("driveSignalCursor",Math.max(0,totalCount)).putBoolean("active",true).putBoolean("userStopped",false).putLong("phaseStartedAt",System.currentTimeMillis());putLatest(e,latest);
 if(tx.acked()){if(tx.normalContinueAck())invalidateSupersededContinuation(e);else clearCommandWait(e);}
 if(!tx.error().isEmpty()){if(tx.errorEvent()!=null)protocolPause(e,tx.errorEvent(),tx.error());else{invalidateSupersededContinuation(e);e.putBoolean("paused",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode",safe(tx.error())).putString("lastErrorMessage","Drive resume ordered reconciliation이 fail-closed 처리되었습니다.").putString("status","재개 차단 · "+safe(tx.error()));}commitOrThrow(e);syncHistory();return;}
 DriveResumePolicy.Decision decision=DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),totalCount,tx.policyEvents());
 switch(decision.action){
  case APPLY_COMPLETION->{DriveSignalParser.Event completion=decision.event;String raw=tx.acceptCompletion(completion.raw);invalidateSupersededContinuation(e);e.putBoolean("paused",false).putString("pendingDriveSignalRaw",raw).putString("pendingDriveSignalTimestamp",completion.timestamp).putString("pendingDriveSignalType",DriveSignalParser.Type.TURN_COMPLETED.name()).putInt("pendingDriveSignalCursor",completion.cursor).putLong("commitDetectedAt",System.currentTimeMillis()).putLong("guardDueAt",System.currentTimeMillis()).putString("completionGuardFingerprint",DriveSignalParser.completionFingerprint(completion.raw)).putBoolean("completionGuardArmed",true).putString("phase",PHASE_READ_NEXT_CONTROL).putString("status","재개 조정 완료 · POST_ANCHOR TURN_COMPLETED 적용");}
  case CONTINUE->{invalidateSupersededContinuation(e);e.putBoolean("paused",false).putString("phase",PHASE_SEND_CONTINUE).putString("status","재개 조정 완료 · 외부 수동조치 후 plain CONTINUE 준비");}
  case RESTORE_PHASE->{String restore=pauseAnchorPhase();if(!validRestoredPhase(restore)){e.putBoolean("paused",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode","DRIVE_RESUME_PHASE_INVALID").putString("lastErrorMessage","pausedFromPhase가 유효하지 않습니다.").putString("status","재개 차단 · 복귀 phase 확인 필요");}else{String restoredStatus=origin==DriveResumePolicy.Origin.UI_MANUAL?"UI 일시정지 해제 · 기존 phase 복귀":"외부 수동조치 완료 · 기존 phase 복귀";e.putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",restore).putString("status",restoredStatus);clearPauseAnchor(e);}}
  case KEEP_PAUSED->{DriveSignalParser.Event blocking=decision.event;e.putBoolean("paused",true).putString("phase",PHASE_PAUSED);if(blocking==null){e.putString("status","재개 보류 · 기존 pause latch 유지");}else{invalidateSupersededContinuation(e);e.putBoolean("resumeNeedsContinuation",true).putString("status","재개 보류 · 더 최신 blocking signal 확인");recordPauseAnchor(e,pauseOriginForDriveSignal(blocking.type),blocking.type.name(),pauseAnchorPhase().isEmpty()?PHASE_WAIT_DRIVE_COMMIT:pauseAnchorPhase(),totalCount,blocking);terminal(e,blocking);}}
  case DONE->{DriveSignalParser.Event done=decision.event;invalidateSupersededContinuation(e);e.putBoolean("active",false).putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_DONE).putString("status","SelfRun Drive 완료");terminal(e,done);}
  case PROTOCOL_ERROR->{if(decision.event!=null)protocolPause(e,decision.event,safe(decision.reason));else{invalidateSupersededContinuation(e);e.putBoolean("paused",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode",safe(decision.reason)).putString("lastErrorMessage","Drive resume reconciliation이 fail-closed 처리되었습니다.").putString("status","재개 차단 · "+safe(decision.reason));}}
 }
 commitOrThrow(e);syncHistory();
}
'''
s = s[:start] + new_baseline + s[end:]
STORE.write_text(s, encoding='utf-8')

TX_TEST.write_text(r'''package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class ResumeDriveTransactionTest {
    private static final String RUN = "SR-20260816-011429-9SZ8A4";

    @Test public void normalAckSupersedesValidPriorNextBeforeCompletion() {
        String prior = completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, "[2026.08.16 | 07:00:00] [SELF_RUN_CONTINUE " + RUN + "]\nCommand Recevied Record Required", prior, false, "");
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack");
        DriveSignalParser.Event next = event(DriveSignalParser.Type.TURN_COMPLETED, 9, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Arrays.asList(ack, next), 7, 9);
        assertTrue(tx.acked());
        assertTrue(tx.normalContinueAck());
        assertEquals(next, tx.materialForTest());
        assertFalse(DriveSignalParser.nextInput(tx.acceptCompletion(next.raw)).present);
    }

    @Test public void rewriteAckCarriesOnlyInvalidProfileNext() {
        String old = "승인할게";
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode(old));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, "[SELF_RUN_TURN_INFO_REWRITE " + RUN + "]", prior, false, "");
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack");
        DriveSignalParser.Event corrected = event(DriveSignalParser.Type.TURN_COMPLETED, 9, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Arrays.asList(ack, corrected), 7, 9);
        assertTrue(tx.rewriteAck());
        NextInputCodec.Decoded decoded = DriveSignalParser.nextInput(tx.acceptCompletion(corrected.raw));
        assertTrue(decoded.present);
        assertTrue(decoded.valid);
        assertEquals(old, decoded.text);
    }

    @Test public void materialBeforeRequiredAckFailsClosed() {
        String prior = completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, "normal", prior, false, "");
        DriveSignalParser.Event completion = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Collections.singletonList(completion), 7, 8);
        assertEquals("COMMAND_RECEIVED_REQUIRED", tx.error());
        assertSame(completion, tx.errorEvent());
    }

    @Test public void ackOnlyConsumesWaitButLeavesNoMaterialDecision() {
        String prior = completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, "normal", prior, false, "");
        tx.observe(Collections.singletonList(event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack")), 7, 8);
        assertTrue(tx.acked());
        assertFalse(tx.awaitingForTest());
        assertTrue(tx.policyEvents().isEmpty());
        assertEquals("", tx.error());
    }

    @Test public void noPostAnchorKeepsInflightAwaitingState() {
        SelfRunStore.ResumeDriveTransaction tx = tx(true, "normal", "", false, "");
        tx.observe(Collections.emptyList(), 7, 7);
        assertTrue(tx.awaitingForTest());
        assertFalse(tx.acked());
        assertTrue(tx.policyEvents().isEmpty());
    }

    @Test public void firstCompletionWinsUntilNewerBlockingOverrides() {
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", "", false, "");
        DriveSignalParser.Event first = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("first")));
        DriveSignalParser.Event second = event(DriveSignalParser.Type.TURN_COMPLETED, 9, completion("MODEL=terra REASONING=high NEXT_INPUT_B64URL=" + NextInputCodec.encode("second")));
        tx.observe(Arrays.asList(first, second), 7, 9);
        assertSame(first, tx.materialForTest());
        DriveSignalParser.Event blocking = event(DriveSignalParser.Type.PAUSED, 10, "paused");
        tx.observe(Collections.singletonList(blocking), 7, 10);
        assertSame(blocking, tx.materialForTest());
    }

    @Test public void guardedDuplicateDoesNotSatisfyOrBreakRequiredAck() {
        String dupRaw = completion("MODEL=sol REASONING=xhigh");
        String fp = DriveSignalParser.completionFingerprint(dupRaw);
        SelfRunStore.ResumeDriveTransaction tx = tx(true, "normal", dupRaw, true, fp);
        DriveSignalParser.Event dup = event(DriveSignalParser.Type.TURN_COMPLETED, 8, dupRaw);
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 9, "ack");
        tx.observe(Arrays.asList(dup, ack), 7, 9);
        assertEquals("", tx.error());
        assertTrue(tx.acked());
        assertTrue(tx.policyEvents().isEmpty());
    }

    private static SelfRunStore.ResumeDriveTransaction tx(boolean awaiting, String prompt, String prior, boolean guard, String fp) {
        return new SelfRunStore.ResumeDriveTransaction(SelfRunStore.MODE_WORK, awaiting,
                SelfRunStore.RETRY_CONTINUE, prompt, !prior.isEmpty(), prior, guard, fp);
    }

    private static DriveSignalParser.Event event(DriveSignalParser.Type type, int cursor, String raw) {
        return new DriveSignalParser.Event(type, "2026.08.16 | 07:00:00", raw, cursor);
    }

    private static String completion(String fields) {
        return "[2026.08.16 | 07:00:00] [SELF_RUN_TURN_COMPLETED " + RUN
                + (fields == null || fields.isEmpty() ? "" : " " + fields) + "]";
    }
}
''', encoding='utf-8')

pt = PAUSE_TEST.read_text(encoding='utf-8')
if 'resumeBaselineUsesOrderedCommandTransaction' not in pt:
    marker = '    private static String src(String f) throws Exception {'
    test_method = r'''    @Test public void resumeBaselineUsesOrderedCommandTransaction() throws Exception {
        String store = src("SelfRunStore.java");
        String baseline = between(store, "void baselineManualResume", "void captureConversationUrl");
        assertTrue(baseline.contains("ResumeDriveTransaction tx=new ResumeDriveTransaction"));
        assertTrue(baseline.contains("tx.observe(postAnchor,pauseAnchorCursor(),totalCount)"));
        assertTrue(baseline.contains("if(tx.acked())"));
        assertTrue(baseline.contains("tx.normalContinueAck()"));
        assertTrue(baseline.contains("DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),totalCount,tx.policyEvents())"));
        assertTrue(baseline.contains("String raw=tx.acceptCompletion(completion.raw)"));
        assertFalse(baseline.contains("hasPendingDriveCompletion())raw=DriveSignalParser.mergeNextInputIfMissing"));
    }

'''
    if marker not in pt:
        raise SystemExit('pause test insertion marker missing')
    pt = pt.replace(marker, test_method + marker, 1)
    PAUSE_TEST.write_text(pt, encoding='utf-8')

print('resume ordered transaction patch applied')
