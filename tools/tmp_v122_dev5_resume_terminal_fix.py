from pathlib import Path

STORE = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java')
BATCH_TEST = Path('app/src/test/java/com/shaterguy/chatgptselfrun/DriveBatchPendingStateTest.java')
RESUME_TEST = Path('app/src/test/java/com/shaterguy/chatgptselfrun/ResumeDriveTransactionTest.java')
INVALIDATION_TEST = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunContinuationInvalidationTest.java')
PAUSE_TEST = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunPauseResumeTest.java')

s = STORE.read_text(encoding='utf-8')

old = '.putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","").putInt("pendingDriveSignalCursor",0).putLong("commitDetectedAt",0L).putLong("guardDueAt",0L).putString("completionGuardFingerprint","").putBoolean("completionGuardArmed",false)'
new = old + '.putBoolean("rewriteCarryAuthorized",false)'
if old not in s:
    raise SystemExit('start pending state marker missing')
s = s.replace(old, new, 1)

old = '    boolean completionGuardArmed() { return prefs.getBoolean("completionGuardArmed", false); }\n    long guardDueAt() { return prefs.getLong("guardDueAt", 0L); }'
new = '    boolean completionGuardArmed() { return prefs.getBoolean("completionGuardArmed", false); }\n    boolean rewriteCarryAuthorized() { return prefs.getBoolean("rewriteCarryAuthorized", false); }\n    long guardDueAt() { return prefs.getLong("guardDueAt", 0L); }'
if old not in s:
    raise SystemExit('getter marker missing')
s = s.replace(old, new, 1)

class_start = s.index('static final class DriveBatchPendingState{')
apply_start = s.index('void applyDriveSignals(', class_start)
new_classes = r'''static final class DriveBatchPendingState{
 private final boolean work;private String priorRaw;private boolean carryNext;
 DriveBatchPendingState(String mode,boolean pendingCompletion,String pendingRaw){this(mode,pendingCompletion,pendingRaw,false);}
 DriveBatchPendingState(String mode,boolean pendingCompletion,String pendingRaw,boolean carryAuthorized){work=MODE_WORK.equals(mode);priorRaw=pendingCompletion&&pendingRaw!=null?pendingRaw:"";carryNext=carryAuthorized&&work&&pendingCompletion&&!priorRaw.isEmpty()&&!DriveSignalParser.workProfile(priorRaw).valid;}
 void authorizeCarry(){carryNext=work&&!priorRaw.isEmpty()&&!DriveSignalParser.workProfile(priorRaw).valid;}
 void supersede(){priorRaw="";carryNext=false;}
 String acceptCompletion(String newerRaw){String accepted=work&&carryNext?DriveSignalParser.mergeNextInputIfMissing(newerRaw,priorRaw):newerRaw;priorRaw=accepted==null?"":accepted;carryNext=false;return accepted;}
 boolean carryNextForTest(){return carryNext;}
 String rawForTest(){return priorRaw;}
}
static final class ResumeDriveTransaction{
 private final String kind;private final boolean rewrite;private final DriveBatchPendingState pending;
 private boolean awaiting,acked,normalAck,rewriteAck,guardArmed,structuralError;private String guardFingerprint,error="";private DriveSignalParser.Event errorEvent,material,lastProcessed;private int rank,consumedCursor=-1;
 ResumeDriveTransaction(String mode,boolean awaiting,String kind,String prompt,boolean pendingCompletion,String pendingRaw,boolean carryAuthorized,boolean guardArmed,String guardFingerprint){this.awaiting=awaiting;this.kind=kind==null?"":kind;rewrite=prompt!=null&&prompt.startsWith("[SELF_RUN_TURN_INFO_REWRITE ");pending=new DriveBatchPendingState(mode,pendingCompletion,pendingRaw,carryAuthorized);this.guardArmed=guardArmed;this.guardFingerprint=guardFingerprint==null?"":guardFingerprint;}
 void observe(List<DriveSignalParser.Event> events,int anchorCursor,int totalCount){if(consumedCursor<0)consumedCursor=Math.max(0,anchorCursor);if(events==null)return;for(DriveSignalParser.Event x:events){if(x==null||x.cursor<=anchorCursor||x.cursor>totalCount){error="RESUME_POST_ANCHOR_SIGNAL_INVALID";errorEvent=null;material=null;rank=3;structuralError=true;return;}consumedCursor=x.cursor;lastProcessed=x;if(x.type==DriveSignalParser.Type.TURN_COMPLETED&&guardArmed&&DriveSignalParser.completionFingerprint(x.raw).equals(guardFingerprint))continue;if(awaiting){if(x.type==DriveSignalParser.Type.COMMAND_RECEIVED){awaiting=false;acked=true;if(RETRY_CONTINUE.equals(kind)&&rewrite){rewriteAck=true;pending.authorizeCarry();}else if(RETRY_CONTINUE.equals(kind)){normalAck=true;pending.supersede();guardArmed=false;guardFingerprint="";}continue;}awaiting=false;setProtocolError("COMMAND_RECEIVED_REQUIRED",x);continue;}if(x.type==DriveSignalParser.Type.INVALID){setProtocolError(x.protocolError.isEmpty()?"RESUME_PROTOCOL_INVALID":x.protocolError,x);continue;}switch(x.type){case COMMAND_RECEIVED->{}case TURN_COMPLETED->{if(rank<2){rank=2;material=x;guardArmed=true;guardFingerprint=DriveSignalParser.completionFingerprint(x.raw);}}case USER_ACTION_REQUIRED,PAUSED,DONE->{setTerminal(x);}case INVALID->throw new IllegalStateException("handled above");}}}
 private void setProtocolError(String code,DriveSignalParser.Event x){error=code==null?"":code;errorEvent=x;material=null;rank=3;pending.supersede();guardArmed=false;guardFingerprint="";}
 private void setTerminal(DriveSignalParser.Event x){error="";errorEvent=null;material=x;rank=3;pending.supersede();guardArmed=false;guardFingerprint="";}
 boolean acked(){return acked;}boolean normalContinueAck(){return acked&&normalAck;}boolean rewriteAck(){return acked&&rewriteAck;}boolean awaitingForTest(){return awaiting;}boolean structuralErrorForTest(){return structuralError;}String error(){return error;}DriveSignalParser.Event errorEvent(){return errorEvent;}DriveSignalParser.Event materialForTest(){return material;}DriveSignalParser.Event lastProcessed(){return lastProcessed;}int consumedCursor(){return Math.max(0,consumedCursor);}
 List<DriveSignalParser.Event> policyEvents(){return material==null?java.util.Collections.emptyList():java.util.Collections.singletonList(material);}String acceptCompletion(String raw){return pending.acceptCompletion(raw);}boolean carryAuthorizedForTest(){return pending.carryNextForTest();}
}
'''
s = s[:class_start] + new_classes + s[apply_start:]

apply_start = s.index('void applyDriveSignals(')
apply_end = s.index('void repairGuard(', apply_start)
new_apply = r'''void applyDriveSignals(List<DriveSignalParser.Event> events,long detectedAt,long guardMs){
 if(events==null||events.isEmpty())return;
 SharedPreferences.Editor e=prefs.edit();boolean awaiting=awaitingCommandAck();boolean guardArmed=completionGuardArmed();String guardFingerprint=completionGuardFingerprint();DriveBatchPendingState batchPending=new DriveBatchPendingState(mode(),hasPendingDriveCompletion(),pendingDriveSignalRaw(),rewriteCarryAuthorized());int rank=PHASE_DONE.equals(phase())||PHASE_PAUSED.equals(phase())?3:PHASE_DRIVE_COMMIT_GUARD.equals(phase())?2:0;
 for(DriveSignalParser.Event x:events){
  e.putInt("driveSignalCursor",x.cursor);putLatest(e,x);
  if(x.type==DriveSignalParser.Type.TURN_COMPLETED&&guardArmed&&DriveSignalParser.completionFingerprint(x.raw).equals(guardFingerprint)){e.putString("status","Drive TURN_COMPLETED 중복 확인 · 기존 completion 유지");continue;}
  if(x.type==DriveSignalParser.Type.COMMAND_RECEIVED){
   if(awaiting){String prompt=get("activeCommandPrompt"),kind=get("activeCommandKind");boolean rewrite=prompt.startsWith("[SELF_RUN_TURN_INFO_REWRITE ");awaiting=false;if(RETRY_CONTINUE.equals(kind)&&!rewrite){invalidateSupersededContinuation(e);batchPending.supersede();clearPauseAnchor(e);guardArmed=false;guardFingerprint="";}else{clearCommandWait(e);if(RETRY_CONTINUE.equals(kind)&&rewrite){e.putBoolean("rewriteCarryAuthorized",true);batchPending.authorizeCarry();}}}
   if(rank<2)e.putString("status","Drive COMMAND_RECEIVED 확인 · 작업 진행 중");
   continue;
  }
  if(awaiting){awaiting=false;protocolPause(e,x,"COMMAND_RECEIVED_REQUIRED");batchPending.supersede();guardArmed=false;guardFingerprint="";rank=3;continue;}
  if(x.type==DriveSignalParser.Type.INVALID){protocolPause(e,x,x.protocolError.isEmpty()?"DRIVE_PROTOCOL_INVALID":x.protocolError);batchPending.supersede();guardArmed=false;guardFingerprint="";rank=3;continue;}
  switch(x.type){
   case TURN_COMPLETED->{if(rank<2){rank=2;String raw=batchPending.acceptCompletion(x.raw);e.putBoolean("rewriteCarryAuthorized",false).putString("pendingDriveSignalRaw",raw).putString("pendingDriveSignalTimestamp",x.timestamp).putString("pendingDriveSignalType",x.type.name()).putInt("pendingDriveSignalCursor",x.cursor).putLong("commitDetectedAt",detectedAt).putLong("guardDueAt",detectedAt+guardMs).putString("completionGuardFingerprint",DriveSignalParser.completionFingerprint(x.raw)).putBoolean("completionGuardArmed",true).putString("phase",PHASE_DRIVE_COMMIT_GUARD).putString("status","Drive TURN_COMPLETED 확인 · 안전 지연");guardFingerprint=DriveSignalParser.completionFingerprint(x.raw);guardArmed=true;}}
   case USER_ACTION_REQUIRED->{rank=3;pauseEvent(e,x,"사용자 조치 필요");batchPending.supersede();guardArmed=false;guardFingerprint="";}
   case PAUSED->{rank=3;pauseEvent(e,x,"SelfRun Drive 일시정지");batchPending.supersede();guardArmed=false;guardFingerprint="";}
   case DONE->{rank=3;invalidateSupersededContinuation(e);batchPending.supersede();guardArmed=false;guardFingerprint="";e.putBoolean("active",false).putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_DONE).putString("status","SelfRun Drive 완료");terminal(e,x);}
   case COMMAND_RECEIVED,INVALID->throw new IllegalStateException("handled above");
  }
 }
 e.putBoolean("awaitingCommandAck",awaiting).putLong("phaseStartedAt",System.currentTimeMillis());commitOrThrow(e);syncHistory();
}
'''
s = s[:apply_start] + new_apply + s[apply_end:]

resume_start = s.index('void baselineManualResume(List<DriveSignalParser.Event> postAnchor,int totalCount,DriveSignalParser.Event latest){')
resume_end = s.index('void baselineManualResume(int totalCount,DriveSignalParser.Event latest)', resume_start)
new_resume = r'''void baselineManualResume(List<DriveSignalParser.Event> postAnchor,int totalCount,DriveSignalParser.Event latest){
 DriveResumePolicy.Origin origin=DriveResumePolicy.parseOrigin(pauseAnchorOrigin());ResumeDriveTransaction tx=new ResumeDriveTransaction(mode(),awaitingCommandAck(),get("activeCommandKind"),get("activeCommandPrompt"),hasPendingDriveCompletion(),pendingDriveSignalRaw(),rewriteCarryAuthorized(),completionGuardArmed(),completionGuardFingerprint());tx.observe(postAnchor,pauseAnchorCursor(),totalCount);SharedPreferences.Editor e=prefs.edit().putInt("driveSignalCursor",tx.consumedCursor()).putBoolean("active",true).putBoolean("userStopped",false).putLong("phaseStartedAt",System.currentTimeMillis());if(tx.lastProcessed()!=null)putLatest(e,tx.lastProcessed());
 if(tx.acked()){if(tx.normalContinueAck())invalidateSupersededContinuation(e);else{clearCommandWait(e);if(tx.rewriteAck())e.putBoolean("rewriteCarryAuthorized",tx.carryAuthorizedForTest());}}
 if(!tx.error().isEmpty()){if(tx.errorEvent()!=null)protocolPause(e,tx.errorEvent(),tx.error());else{invalidateSupersededContinuation(e);e.putBoolean("paused",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode",safe(tx.error())).putString("lastErrorMessage","Drive resume ordered reconciliation이 fail-closed 처리되었습니다.").putString("status","재개 차단 · "+safe(tx.error()));}commitOrThrow(e);syncHistory();return;}
 DriveResumePolicy.Decision decision=DriveResumePolicy.decide(origin,resumeNeedsContinuation(),pauseAnchorCursor(),tx.consumedCursor(),tx.policyEvents());
 switch(decision.action){
  case APPLY_COMPLETION->{DriveSignalParser.Event completion=decision.event;String raw=tx.acceptCompletion(completion.raw);invalidateSupersededContinuation(e);e.putBoolean("paused",false).putString("pendingDriveSignalRaw",raw).putString("pendingDriveSignalTimestamp",completion.timestamp).putString("pendingDriveSignalType",DriveSignalParser.Type.TURN_COMPLETED.name()).putInt("pendingDriveSignalCursor",completion.cursor).putLong("commitDetectedAt",System.currentTimeMillis()).putLong("guardDueAt",System.currentTimeMillis()).putString("completionGuardFingerprint",DriveSignalParser.completionFingerprint(completion.raw)).putBoolean("completionGuardArmed",true).putString("phase",PHASE_READ_NEXT_CONTROL).putString("status","재개 조정 완료 · POST_ANCHOR TURN_COMPLETED 적용");}
  case CONTINUE->{invalidateSupersededContinuation(e);e.putBoolean("paused",false).putString("phase",PHASE_SEND_CONTINUE).putString("status","재개 조정 완료 · 외부 수동조치 후 plain CONTINUE 준비");}
  case RESTORE_PHASE->{String restore=pauseAnchorPhase();if(!validRestoredPhase(restore)){e.putBoolean("paused",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode","DRIVE_RESUME_PHASE_INVALID").putString("lastErrorMessage","pausedFromPhase가 유효하지 않습니다.").putString("status","재개 차단 · 복귀 phase 확인 필요");}else{String restoredStatus=origin==DriveResumePolicy.Origin.UI_MANUAL?"UI 일시정지 해제 · 기존 phase 복귀":"외부 수동조치 완료 · 기존 phase 복귀";e.putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",restore).putString("status",restoredStatus);clearPauseAnchor(e);}}
  case KEEP_PAUSED->{DriveSignalParser.Event blocking=decision.event;e.putBoolean("paused",true).putString("phase",PHASE_PAUSED);if(blocking==null){e.putString("status","재개 보류 · 기존 pause latch 유지");}else{invalidateSupersededContinuation(e);e.putBoolean("resumeNeedsContinuation",true).putString("status","재개 보류 · 더 최신 blocking signal 확인");recordPauseAnchor(e,pauseOriginForDriveSignal(blocking.type),blocking.type.name(),pauseAnchorPhase().isEmpty()?PHASE_WAIT_DRIVE_COMMIT:pauseAnchorPhase(),tx.consumedCursor(),blocking);terminal(e,blocking);}}
  case DONE->{DriveSignalParser.Event done=decision.event;invalidateSupersededContinuation(e);e.putBoolean("active",false).putBoolean("paused",false).putBoolean("resumeNeedsContinuation",false).putString("phase",PHASE_DONE).putString("status","SelfRun Drive 완료");terminal(e,done);}
  case PROTOCOL_ERROR->{if(decision.event!=null)protocolPause(e,decision.event,safe(decision.reason));else{invalidateSupersededContinuation(e);e.putBoolean("paused",true).putString("phase",PHASE_PAUSED).putString("lastErrorCode",safe(decision.reason)).putString("lastErrorMessage","Drive resume reconciliation이 fail-closed 처리되었습니다.").putString("status","재개 차단 · "+safe(decision.reason));}}
 }
 commitOrThrow(e);syncHistory();
}
'''
s = s[:resume_start] + new_resume + s[resume_end:]

old = 'private SharedPreferences.Editor invalidateSupersededContinuation(SharedPreferences.Editor e){SelfRunProtocol.clearPendingContinuation(runId());clearCommandWait(e);clearPendingCompletion(e);resetCompletionGuard(e);return e;}'
new = 'private SharedPreferences.Editor invalidateSupersededContinuation(SharedPreferences.Editor e){SelfRunProtocol.clearPendingContinuation(runId());clearCommandWait(e);clearPendingCompletion(e);resetCompletionGuard(e);e.putBoolean("rewriteCarryAuthorized",false);return e;}'
if old not in s:
    raise SystemExit('invalidation helper marker missing')
s = s.replace(old, new, 1)

old = 'private static SharedPreferences.Editor clearPendingCompletion(SharedPreferences.Editor e){return e.putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","").putInt("pendingDriveSignalCursor",0).putLong("commitDetectedAt",0L).putLong("guardDueAt",0L);}'
new = 'private static SharedPreferences.Editor clearPendingCompletion(SharedPreferences.Editor e){return e.putString("pendingDriveSignalRaw","").putString("pendingDriveSignalTimestamp","").putString("pendingDriveSignalType","").putInt("pendingDriveSignalCursor",0).putLong("commitDetectedAt",0L).putLong("guardDueAt",0L).putBoolean("rewriteCarryAuthorized",false);}'
if old not in s:
    raise SystemExit('clear pending marker missing')
s = s.replace(old, new, 1)

STORE.write_text(s, encoding='utf-8')

BATCH_TEST.write_text(r'''package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriveBatchPendingStateTest {
    private static final String RUN = "SR-20260816-011429-9SZ8A4";

    @Test public void invalidPriorDoesNotCarryWithoutRewriteAuthorization() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior);
        assertFalse(state.carryNextForTest());
        assertFalse(DriveSignalParser.nextInput(state.acceptCompletion(completion("MODEL=sol REASONING=xhigh"))).present);
    }

    @Test public void rewriteAuthorizationCarriesPriorNextExactlyOnce() {
        String oldInput = "승인할게";
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode(oldInput));
        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior);
        state.authorizeCarry();
        assertTrue(state.carryNextForTest());
        String accepted = state.acceptCompletion(completion("MODEL=sol REASONING=xhigh"));
        NextInputCodec.Decoded next = DriveSignalParser.nextInput(accepted);
        assertTrue(next.present);
        assertTrue(next.valid);
        assertEquals(oldInput, next.text);
        assertFalse(state.carryNextForTest());
        assertFalse(DriveSignalParser.nextInput(state.acceptCompletion(completion("MODEL=sol REASONING=xhigh"))).present);
    }

    @Test public void normalContinueAckDropsPriorNextBeforeSameBatchWorkCompletion() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("원격 push를 진행해"));
        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior, true);
        assertTrue(state.carryNextForTest());
        state.supersede();
        String accepted = state.acceptCompletion(completion("MODEL=sol REASONING=xhigh"));
        assertFalse(DriveSignalParser.nextInput(accepted).present);
    }

    @Test public void newerCompletionOwnNextAlwaysWinsOverAuthorizedCarry() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior, true);
        String accepted = state.acceptCompletion(completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("new")));
        NextInputCodec.Decoded next = DriveSignalParser.nextInput(accepted);
        assertTrue(next.present);
        assertTrue(next.valid);
        assertEquals("new", next.text);
        assertFalse(state.carryNextForTest());
    }

    @Test public void blockingOrDoneSupersedeMakesLaterCompletionUnableToRecoverOldNext() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_WORK, true, prior, true);
        state.supersede();
        assertEquals("", state.rawForTest());
        assertFalse(DriveSignalParser.nextInput(state.acceptCompletion(completion("MODEL=sol REASONING=xhigh"))).present);
    }

    @Test public void chatModeNeverCarriesWorkRewriteNext() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.DriveBatchPendingState state = new SelfRunStore.DriveBatchPendingState(SelfRunStore.MODE_CHAT, true, prior, true);
        assertFalse(DriveSignalParser.nextInput(state.acceptCompletion(completion(""))).present);
    }

    private static String completion(String fields) {
        return "[2026.08.16 | 06:00:00] [SELF_RUN_TURN_COMPLETED " + RUN
                + (fields == null || fields.isEmpty() ? "" : " " + fields) + "]";
    }
}
''', encoding='utf-8')

RESUME_TEST.write_text(r'''package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class ResumeDriveTransactionTest {
    private static final String RUN = "SR-20260816-011429-9SZ8A4";

    @Test public void normalAckSupersedesValidPriorNextBeforeCompletion() {
        String prior = completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), prior, false, false, "");
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack");
        DriveSignalParser.Event next = event(DriveSignalParser.Type.TURN_COMPLETED, 9, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Arrays.asList(ack, next), 7, 9);
        assertTrue(tx.acked());
        assertTrue(tx.normalContinueAck());
        assertSame(next, tx.materialForTest());
        assertFalse(DriveSignalParser.nextInput(tx.acceptCompletion(next.raw)).present);
        assertEquals(9, tx.consumedCursor());
    }

    @Test public void rewriteAckCarriesInvalidProfileNext() {
        String old = "승인할게";
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode(old));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, rewritePrompt(), prior, false, false, "");
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack");
        DriveSignalParser.Event corrected = event(DriveSignalParser.Type.TURN_COMPLETED, 9, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Arrays.asList(ack, corrected), 7, 9);
        assertTrue(tx.rewriteAck());
        NextInputCodec.Decoded decoded = DriveSignalParser.nextInput(tx.acceptCompletion(corrected.raw));
        assertTrue(decoded.present);
        assertEquals(old, decoded.text);
        assertFalse(tx.carryAuthorizedForTest());
    }

    @Test public void invalidPriorWithoutRewriteAckDoesNotCarry() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", prior, false, false, "");
        DriveSignalParser.Event corrected = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Collections.singletonList(corrected), 7, 8);
        assertFalse(DriveSignalParser.nextInput(tx.acceptCompletion(corrected.raw)).present);
    }

    @Test public void durablePreAnchorRewriteAuthorizationSurvivesPauseUntilCorrection() {
        String prior = completion("NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", prior, true, false, "");
        DriveSignalParser.Event corrected = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Collections.singletonList(corrected), 7, 8);
        NextInputCodec.Decoded next = DriveSignalParser.nextInput(tx.acceptCompletion(corrected.raw));
        assertTrue(next.present);
        assertEquals("old", next.text);
    }

    @Test public void materialBeforeRequiredAckFailsClosedWhenNothingNewerExists() {
        String prior = completion("MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=" + NextInputCodec.encode("old"));
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), prior, false, false, "");
        DriveSignalParser.Event completion = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        tx.observe(Collections.singletonList(completion), 7, 8);
        assertEquals("COMMAND_RECEIVED_REQUIRED", tx.error());
        assertSame(completion, tx.errorEvent());
        assertEquals(8, tx.consumedCursor());
    }

    @Test public void missingAckErrorThenDonePreservesNewestDoneAuthority() {
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), "", false, false, "");
        DriveSignalParser.Event early = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        DriveSignalParser.Event lateAck = event(DriveSignalParser.Type.COMMAND_RECEIVED, 9, "ack");
        DriveSignalParser.Event done = event(DriveSignalParser.Type.DONE, 10, "done");
        tx.observe(Arrays.asList(early, lateAck, done), 7, 10);
        assertEquals("", tx.error());
        assertSame(done, tx.materialForTest());
        assertEquals(10, tx.consumedCursor());
    }

    @Test public void missingAckErrorThenPausedPreservesNewestBlockingAuthority() {
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), "", false, false, "");
        DriveSignalParser.Event early = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        DriveSignalParser.Event paused = event(DriveSignalParser.Type.PAUSED, 9, "paused");
        tx.observe(Arrays.asList(early, paused), 7, 9);
        assertEquals("", tx.error());
        assertSame(paused, tx.materialForTest());
    }

    @Test public void invalidThenDoneDoesNotLoseDone() {
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", "", false, false, "");
        DriveSignalParser.Event invalid = new DriveSignalParser.Event(DriveSignalParser.Type.INVALID, "2026.08.16 | 07:00:00", "bad", 8, false, "", "", "NEXT_INPUT_UTF8_INVALID");
        DriveSignalParser.Event done = event(DriveSignalParser.Type.DONE, 9, "done");
        tx.observe(Arrays.asList(invalid, done), 7, 9);
        assertEquals("", tx.error());
        assertSame(done, tx.materialForTest());
        assertEquals(9, tx.consumedCursor());
    }

    @Test public void ackOnlyConsumesWaitButLeavesNoMaterialDecision() {
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), "", false, false, "");
        tx.observe(Collections.singletonList(event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack")), 7, 8);
        assertTrue(tx.acked());
        assertFalse(tx.awaitingForTest());
        assertTrue(tx.policyEvents().isEmpty());
        assertEquals(8, tx.consumedCursor());
    }

    @Test public void noPostAnchorKeepsInflightAwaitingStateAndAnchorCursor() {
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), "", false, false, "");
        tx.observe(Collections.emptyList(), 7, 7);
        assertTrue(tx.awaitingForTest());
        assertFalse(tx.acked());
        assertTrue(tx.policyEvents().isEmpty());
        assertEquals(7, tx.consumedCursor());
    }

    @Test public void firstCompletionWinsUntilNewerBlockingOverrides() {
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", "", false, false, "");
        DriveSignalParser.Event first = event(DriveSignalParser.Type.TURN_COMPLETED, 8, completion("MODEL=sol REASONING=xhigh"));
        DriveSignalParser.Event second = event(DriveSignalParser.Type.TURN_COMPLETED, 9, completion("MODEL=terra REASONING=high"));
        tx.observe(Arrays.asList(first, second), 7, 9);
        assertSame(first, tx.materialForTest());
        DriveSignalParser.Event blocking = event(DriveSignalParser.Type.USER_ACTION_REQUIRED, 10, "blocking");
        tx.observe(Collections.singletonList(blocking), 7, 10);
        assertSame(blocking, tx.materialForTest());
    }

    @Test public void guardedDuplicateDoesNotSatisfyOrBreakRequiredAck() {
        String dupRaw = completion("MODEL=sol REASONING=xhigh");
        String fp = DriveSignalParser.completionFingerprint(dupRaw);
        SelfRunStore.ResumeDriveTransaction tx = tx(true, normalPrompt(), dupRaw, false, true, fp);
        DriveSignalParser.Event dup = event(DriveSignalParser.Type.TURN_COMPLETED, 8, dupRaw);
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 9, "ack");
        tx.observe(Arrays.asList(dup, ack), 7, 9);
        assertEquals("", tx.error());
        assertTrue(tx.acked());
        assertTrue(tx.policyEvents().isEmpty());
    }

    @Test public void structuralFailureDoesNotAdvancePastLastActuallyProcessedCursor() {
        SelfRunStore.ResumeDriveTransaction tx = tx(false, "", "", false, false, "");
        DriveSignalParser.Event first = event(DriveSignalParser.Type.COMMAND_RECEIVED, 8, "ack");
        DriveSignalParser.Event impossible = event(DriveSignalParser.Type.DONE, 99, "done");
        tx.observe(Arrays.asList(first, impossible), 7, 9);
        assertTrue(tx.structuralErrorForTest());
        assertEquals("RESUME_POST_ANCHOR_SIGNAL_INVALID", tx.error());
        assertEquals(8, tx.consumedCursor());
        assertSame(first, tx.lastProcessed());
    }

    private static SelfRunStore.ResumeDriveTransaction tx(boolean awaiting, String prompt, String prior,
                                                            boolean carryAuthorized, boolean guard, String fp) {
        return new SelfRunStore.ResumeDriveTransaction(SelfRunStore.MODE_WORK, awaiting,
                SelfRunStore.RETRY_CONTINUE, prompt, !prior.isEmpty(), prior, carryAuthorized, guard, fp);
    }
    private static String normalPrompt() { return "[2026.08.16 | 07:00:00] [SELF_RUN_CONTINUE " + RUN + "]\nCommand Recevied Record Required"; }
    private static String rewritePrompt() { return "[SELF_RUN_TURN_INFO_REWRITE " + RUN + "]"; }
    private static DriveSignalParser.Event event(DriveSignalParser.Type type, int cursor, String raw) {
        return new DriveSignalParser.Event(type, "2026.08.16 | 07:00:00", raw, cursor);
    }
    private static String completion(String fields) {
        return "[2026.08.16 | 07:00:00] [SELF_RUN_TURN_COMPLETED " + RUN
                + (fields == null || fields.isEmpty() ? "" : " " + fields) + "]";
    }
}
''', encoding='utf-8')

it = INVALIDATION_TEST.read_text(encoding='utf-8')
it = it.replace('        assertTrue(helper.contains("resetCompletionGuard(e)"));\n', '        assertTrue(helper.contains("resetCompletionGuard(e)"));\n        assertTrue(helper.contains("rewriteCarryAuthorized"));\n')
it = it.replace('DriveBatchPendingState batchPending=new DriveBatchPendingState(mode(),hasPendingDriveCompletion(),pendingDriveSignalRaw())', 'DriveBatchPendingState batchPending=new DriveBatchPendingState(mode(),hasPendingDriveCompletion(),pendingDriveSignalRaw(),rewriteCarryAuthorized())')
it = it.replace('        assertTrue(apply.contains("String raw=batchPending.acceptCompletion(x.raw)"));\n', '        assertTrue(apply.contains("String raw=batchPending.acceptCompletion(x.raw)"));\n        assertTrue(apply.contains("putBoolean(\\\"rewriteCarryAuthorized\\\",true);batchPending.authorizeCarry()"));\n        assertTrue(apply.contains("putBoolean(\\\"rewriteCarryAuthorized\\\",false)"));\n')
INVALIDATION_TEST.write_text(it, encoding='utf-8')

pt = PAUSE_TEST.read_text(encoding='utf-8')
old = '        assertTrue(baseline.contains("tx.observe(postAnchor,pauseAnchorCursor(),totalCount)"));\n'
new = old + '        assertTrue(baseline.contains("putInt(\\\"driveSignalCursor\\\",tx.consumedCursor())"));\n        assertTrue(baseline.contains("if(tx.lastProcessed()!=null)putLatest(e,tx.lastProcessed())"));\n'
if old not in pt:
    raise SystemExit('pause integration marker missing')
pt = pt.replace(old, new, 1)
PAUSE_TEST.write_text(pt, encoding='utf-8')

print('resume terminal authority and rewrite carry patch applied')
