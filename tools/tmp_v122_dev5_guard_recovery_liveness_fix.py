#!/usr/bin/env python3
from pathlib import Path

service_path = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java')
test_path = Path('app/src/test/java/com/shaterguy/chatgptselfrun/GuardRecoveryLivenessTest.java')
runtime_doc = Path('docs/SELF_RUN_DRIVE_RUNTIME.md')

service = service_path.read_text()
old = '''private void scheduleGuard(){releaseWakeLock();handler.removeCallbacks(webRunnable);handler.removeCallbacks(guardRunnable);long detected=store.commitDetectedAt(),due=store.guardDueAt();boolean valid=DriveSignalParser.Type.TURN_COMPLETED.name().equals(store.pendingDriveSignalType())&&!store.pendingDriveSignalRaw().isEmpty()&&detected>0&&due-detected==CONTINUATION_GUARD_MS;if(!valid){runLog.record(store,"DRIVE_GUARD_RECOVERY","invalid_guard_state");store.repairGuard(System.currentTimeMillis(),CONTINUATION_GUARD_MS);if(SelfRunStore.PHASE_READ_NEXT_CONTROL.equals(store.phase())){ensureWebView();return;}due=store.guardDueAt();}handler.postDelayed(guardRunnable,Math.max(0,due-System.currentTimeMillis()));}'''
new = '''private void scheduleGuard(){releaseWakeLock();handler.removeCallbacks(webRunnable);handler.removeCallbacks(guardRunnable);long detected=store.commitDetectedAt(),due=store.guardDueAt();boolean valid=DriveSignalParser.Type.TURN_COMPLETED.name().equals(store.pendingDriveSignalType())&&!store.pendingDriveSignalRaw().isEmpty()&&detected>0&&due-detected==CONTINUATION_GUARD_MS;if(!valid){runLog.record(store,"DRIVE_GUARD_RECOVERY","invalid_guard_state");store.repairGuard(System.currentTimeMillis(),CONTINUATION_GUARD_MS);if(SelfRunStore.PHASE_READ_NEXT_CONTROL.equals(store.phase())){ensureWebView();return;}if(SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())){scheduleDrivePoll(0L);return;}due=store.guardDueAt();}handler.postDelayed(guardRunnable,Math.max(0,due-System.currentTimeMillis()));}'''
assert old in service, 'scheduleGuard baseline not found'
service = service.replace(old, new, 1)
service_path.write_text(service)

test_path.write_text(r'''package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class GuardRecoveryLivenessTest {
    @Test public void noEvidenceRecoveryReentersDrivePollingInsteadOfDeadGuardRunnable() throws Exception {
        String service = src("SelfRunService.java");
        String guard = between(service, "private void scheduleGuard", "private void guardElapsed");
        int repair = guard.indexOf("store.repairGuard(System.currentTimeMillis(),CONTINUATION_GUARD_MS)");
        int wait = guard.indexOf("if(SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())){scheduleDrivePoll(0L);return;}");
        assertTrue(repair >= 0);
        assertTrue(wait > repair);
        assertTrue(guard.indexOf("handler.postDelayed(guardRunnable", wait) > wait);
    }

    @Test public void validCompletionEvidenceStillUsesGuardTimer() throws Exception {
        String service = src("SelfRunService.java");
        String guard = between(service, "private void scheduleGuard", "private void guardElapsed");
        assertTrue(guard.contains("boolean valid=DriveSignalParser.Type.TURN_COMPLETED.name().equals(store.pendingDriveSignalType())"));
        assertTrue(guard.contains("if(!valid){"));
        assertTrue(guard.contains("handler.postDelayed(guardRunnable,Math.max(0,due-System.currentTimeMillis()))"));
    }

    @Test public void bothFullRebaselineAndPreviousSignalRepairProduceWaitDriveCommit() throws Exception {
        String store = src("SelfRunStore.java");
        String repair = between(store, "void repairGuard", "void beginManualResumeOverride");
        assertTrue(repair.contains("int recoveryCursor=cursor>0?cursor-1:Integer.MAX_VALUE"));
        assertTrue(repair.contains("putBoolean(\"driveRebaselineAuthorized\",cursor==0)"));
        assertTrue(repair.contains("putString(\"phase\",PHASE_WAIT_DRIVE_COMMIT)"));
    }

    @Test public void drivePollSchedulerActuallyPostsDriveRunnable() throws Exception {
        String service = src("SelfRunService.java");
        String scheduler = between(service, "private void scheduleDrivePoll(){", "private void scheduleWeb");
        assertTrue(scheduler.contains("handler.postDelayed(driveRunnable,delay)"));
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
line = '- Guard recovery that moves back to `WAIT_DRIVE_COMMIT` immediately schedules a Drive poll; it never leaves a one-shot rebaseline or previous-signal revalidation waiting on an inactive guard callback.'
if line not in runtime:
    runtime += '\n' + line + '\n'
runtime_doc.write_text(runtime)

print('guard recovery liveness patch applied')
