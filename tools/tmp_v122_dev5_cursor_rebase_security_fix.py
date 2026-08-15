#!/usr/bin/env python3
from pathlib import Path

store_path = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java')
service_path = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java')
test_path = Path('app/src/test/java/com/shaterguy/chatgptselfrun/DriveCursorRebaseSecurityTest.java')
runtime_doc = Path('docs/SELF_RUN_DRIVE_RUNTIME.md')
protocol_doc = Path('docs/SELF_RUN_DRIVE_V1_PROTOCOL.md')

store = store_path.read_text()
service = service_path.read_text()

old = '.putLong("guardDueAt",0L).putString("completionGuardFingerprint","").putBoolean("completionGuardArmed",false).putBoolean("rewriteCarryAuthorized",false)'
new = old + '.putBoolean("driveRebaselineAuthorized",false)'
assert old in store and 'putBoolean("driveRebaselineAuthorized",false)' not in store.split(old,1)[0][-200:]
store = store.replace(old, new, 1)

old = 'boolean rewriteCarryAuthorized() { return prefs.getBoolean("rewriteCarryAuthorized", false); }\n    long guardDueAt()'
new = 'boolean rewriteCarryAuthorized() { return prefs.getBoolean("rewriteCarryAuthorized", false); }\n    boolean driveRebaselineAuthorized() { return prefs.getBoolean("driveRebaselineAuthorized", false) && driveSignalCursor()==Integer.MAX_VALUE; }\n    long guardDueAt()'
assert old in store
store = store.replace(old, new, 1)

old = 'void baselineDriveSignals(int cursor,DriveSignalParser.Event latest){SharedPreferences.Editor e=prefs.edit().putInt("driveSignalCursor",Math.max(0,cursor));putLatest(e,latest);commitOrThrow(e);syncHistory();}\nvoid beginCommandAttempt'
new = '''void baselineDriveSignals(int cursor,DriveSignalParser.Event latest){SharedPreferences.Editor e=prefs.edit().putInt("driveSignalCursor",Math.max(0,cursor)).putBoolean("driveRebaselineAuthorized",false);putLatest(e,latest);commitOrThrow(e);syncHistory();}\nvoid acceptAuthorizedDriveRebaseline(int cursor,DriveSignalParser.Event latest){if(!driveRebaselineAuthorized())throw new IllegalStateException("Drive rebaseline authorization required");SharedPreferences.Editor e=prefs.edit().putBoolean("driveRebaselineAuthorized",false).putInt("driveSignalCursor",Math.max(0,cursor));putLatest(e,latest);commitOrThrow(e);syncHistory();}\nvoid rejectDriveCursorRebase(int observedTotal){int prior=driveSignalCursor();SharedPreferences.Editor e=prefs.edit();invalidateSupersededContinuation(e);e.putBoolean("driveRebaselineAuthorized",false).putInt("driveSignalCursor",prior).putBoolean("paused",true).putBoolean("active",true).putBoolean("resumeNeedsContinuation",true).putString("pausedFromPhase",PHASE_WAIT_DRIVE_COMMIT).putString("phase",PHASE_PAUSED).putString("lastErrorCode","DRIVE_SIGNAL_CURSOR_REBASED").putString("lastErrorMessage","Drive execution signal log shrank below the durable cursor.").putString("status","Drive signal cursor 무결성 오류 · 문서 축소 감지").putLong("phaseStartedAt",System.currentTimeMillis());recordPauseAnchor(e,PAUSE_ORIGIN_AI_PAUSED,"DRIVE_SIGNAL_CURSOR_REBASED",PHASE_WAIT_DRIVE_COMMIT,prior,null);e.putBoolean("terminalSideEffectPending",true).putString("terminalSideEffectType",DriveSignalParser.Type.PAUSED.name()).putString("terminalSideEffectRunId",runId()).putString("terminalSideEffectCommitId","cursor-rebase:"+prior+":"+Math.max(0,observedTotal));commitOrThrow(e);syncHistory();}\nvoid beginCommandAttempt'''
assert old in store
store = store.replace(old, new, 1)

start = store.index('void repairGuard(long now,long guardMs)')
end = store.index('void beginManualResumeOverride()', start)
old_method = store[start:end]
new_method = '''void repairGuard(long now,long guardMs){\n SharedPreferences.Editor e=prefs.edit();String raw=pendingDriveSignalRaw(),ts=pendingDriveSignalTimestamp();int pendingCursor=pendingDriveSignalCursor();\n if(!DriveSignalParser.Type.TURN_COMPLETED.name().equals(pendingDriveSignalType())||raw.isEmpty()){\n  if(DriveSignalParser.Type.TURN_COMPLETED.name().equals(lastDriveSignalType())&&!lastDriveSignalRaw().isEmpty()){raw=lastDriveSignalRaw();ts=lastDriveSignalTimestamp();pendingCursor=driveSignalCursor();}\n  else{int cursor=driveSignalCursor();int recoveryCursor=cursor>0?cursor-1:Integer.MAX_VALUE;invalidateSupersededContinuation(e);e.putBoolean("driveRebaselineAuthorized",cursor==0).putInt("driveSignalCursor",recoveryCursor).putString("lastSeenDriveVersion","").putString("lastSeenModifiedTime","").putString("phase",PHASE_WAIT_DRIVE_COMMIT).putString("status",cursor>0?"Drive 완료 signal guard 손상 · 직전 신호 재검증":"Drive 완료 signal guard 손상 · 현재 문서 baseline 재확인").putLong("phaseStartedAt",System.currentTimeMillis());commitOrThrow(e);syncHistory();return;}\n }\n commitOrThrow(e.putBoolean("driveRebaselineAuthorized",false).putString("pendingDriveSignalRaw",raw).putString("pendingDriveSignalTimestamp",ts).putString("pendingDriveSignalType",DriveSignalParser.Type.TURN_COMPLETED.name()).putInt("pendingDriveSignalCursor",pendingCursor>0?pendingCursor:driveSignalCursor()).putLong("commitDetectedAt",now).putLong("guardDueAt",now+guardMs).putString("completionGuardFingerprint",DriveSignalParser.completionFingerprint(raw)).putBoolean("completionGuardArmed",true).putString("phase",PHASE_DRIVE_COMMIT_GUARD).putString("status","Drive TURN_COMPLETED guard 복구"));syncHistory();\n}\n'''
assert 'Integer.MAX_VALUE' in old_method and 'driveRebaselineAuthorized' not in old_method
store = store[:start] + new_method + store[end:]

old = 'private SharedPreferences.Editor invalidateSupersededContinuation(SharedPreferences.Editor e){SelfRunProtocol.clearPendingContinuation(runId());clearCommandWait(e);clearPendingCompletion(e);resetCompletionGuard(e);e.putBoolean("rewriteCarryAuthorized",false);return e;}'
new = 'private SharedPreferences.Editor invalidateSupersededContinuation(SharedPreferences.Editor e){SelfRunProtocol.clearPendingContinuation(runId());clearCommandWait(e);clearPendingCompletion(e);resetCompletionGuard(e);e.putBoolean("rewriteCarryAuthorized",false).putBoolean("driveRebaselineAuthorized",false);return e;}'
assert old in store
store = store.replace(old, new, 1)

old = 'if(scan.cursorRebased)store.baselineDriveSignals(scan.totalCount,scan.latest);else if(!scan.unseen.isEmpty())store.applyDriveSignals(scan.unseen,System.currentTimeMillis(),CONTINUATION_GUARD_MS);'
new = 'if(scan.cursorRebased){if(store.driveRebaselineAuthorized())store.acceptAuthorizedDriveRebaseline(scan.totalCount,scan.latest);else store.rejectDriveCursorRebase(scan.totalCount);}else if(!scan.unseen.isEmpty())store.applyDriveSignals(scan.unseen,System.currentTimeMillis(),CONTINUATION_GUARD_MS);'
assert old in service
service = service.replace(old, new, 1)

store_path.write_text(store)
service_path.write_text(service)

test_path.write_text(r'''package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class DriveCursorRebaseSecurityTest {
    @Test public void normalPollRequiresExplicitOneShotAuthorizationForAnyRebase() throws Exception {
        String service = src("SelfRunService.java");
        String poll = between(service, "private void pollDriveNow", "private void resumeAfterDriveReconciliation");
        assertTrue(poll.contains("if(scan.cursorRebased){if(store.driveRebaselineAuthorized())store.acceptAuthorizedDriveRebaseline(scan.totalCount,scan.latest);else store.rejectDriveCursorRebase(scan.totalCount);}"));
        assertFalse(poll.contains("if(scan.cursorRebased)store.baselineDriveSignals"));
    }

    @Test public void authorizationIsDurableSentinelBoundAndConsumedOnce() throws Exception {
        String store = src("SelfRunStore.java");
        assertTrue(store.contains("boolean driveRebaselineAuthorized() { return prefs.getBoolean(\"driveRebaselineAuthorized\", false) && driveSignalCursor()==Integer.MAX_VALUE; }"));
        String methods = between(store, "void baselineDriveSignals", "void beginCommandAttempt");
        assertTrue(methods.contains("if(!driveRebaselineAuthorized())throw new IllegalStateException"));
        assertTrue(methods.contains("putBoolean(\"driveRebaselineAuthorized\",false).putInt(\"driveSignalCursor\",Math.max(0,cursor))"));
    }

    @Test public void unauthorizedShrinkPreservesCursorAndInvalidatesContinuationAuthority() throws Exception {
        String store = src("SelfRunStore.java");
        String reject = between(store, "void rejectDriveCursorRebase", "void beginCommandAttempt");
        assertTrue(reject.contains("int prior=driveSignalCursor()"));
        assertTrue(reject.contains("invalidateSupersededContinuation(e)"));
        assertTrue(reject.contains("putInt(\"driveSignalCursor\",prior)"));
        assertTrue(reject.contains("DRIVE_SIGNAL_CURSOR_REBASED"));
        assertTrue(reject.contains("PAUSE_ORIGIN_AI_PAUSED"));
        assertTrue(reject.contains("terminalSideEffectType\",DriveSignalParser.Type.PAUSED.name()"));
    }

    @Test public void guardRecoveryAloneCanMintRebaselineAuthorization() throws Exception {
        String store = src("SelfRunStore.java");
        String repair = between(store, "void repairGuard", "void beginManualResumeOverride");
        assertTrue(repair.contains("invalidateSupersededContinuation(e)"));
        assertTrue(repair.contains("int recoveryCursor=cursor>0?cursor-1:Integer.MAX_VALUE"));
        assertTrue(repair.contains("putBoolean(\"driveRebaselineAuthorized\",cursor==0)"));
        assertTrue(repair.contains("putBoolean(\"driveRebaselineAuthorized\",false)"));
    }

    @Test public void supersedingAuthorityAlsoRevokesRebaselineAuthorization() throws Exception {
        String store = src("SelfRunStore.java");
        String helper = between(store, "private SharedPreferences.Editor invalidateSupersededContinuation", "private static void putLatest");
        assertTrue(helper.contains("putBoolean(\"driveRebaselineAuthorized\",false)"));
    }

    private static String src(String f) throws Exception {
        Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);
        if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);
        return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
    }
    private static String between(String s,String a,String b){return s.substring(s.indexOf(a),s.indexOf(b));}
}
''')

runtime = runtime_doc.read_text()
marker = '### Cursor rebase integrity (1.2.2-dev5)'
if marker not in runtime:
    runtime += '\n\n' + marker + '\n- Normal Drive polling treats a signal-count shrink below the durable cursor as a structural integrity failure and pauses without lowering the cursor.\n- Only guard recovery may mint a durable one-shot rebaseline authorization; it is bound to the Integer.MAX_VALUE recovery sentinel and consumed immediately after a successful baseline.\n- Unauthorized rebase invalidates prepared/pending continuation, completion guard, rewrite carry, and in-memory NEXT reservations before entering AI_PAUSED.\n'
runtime_doc.write_text(runtime)

protocol = protocol_doc.read_text()
marker = '### Append-only cursor integrity (1.2.2-dev5)'
if marker not in protocol:
    protocol += '\n\n' + marker + '\n- The execution-turn signal log is append-only from the client cursor perspective. `totalCount < durableCursor` MUST fail closed during normal polling.\n- A rebaseline is permitted only when app-internal guard recovery has durably authorized one recovery attempt; the authorization MUST be consumed on use and MUST NOT survive superseding authority.\n'
protocol_doc.write_text(protocol)

print('cursor rebase security patch applied')
