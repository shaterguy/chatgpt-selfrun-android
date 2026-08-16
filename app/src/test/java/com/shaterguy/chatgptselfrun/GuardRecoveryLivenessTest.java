package com.shaterguy.chatgptselfrun;

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

    @Test public void busyPollAdmissionRequeuesWithoutReleasingInflightWakeLock() throws Exception {
        String service = src("SelfRunService.java");
        String poll = between(service, "private void pollDrive(){", "private void pollDriveNow");
        assertTrue(poll.contains("DrivePollAdmission.delayMs(eligible,canRun(),driveInFlight,authorizationInFlight)"));
        assertTrue(poll.contains("if(delay>0L){handler.removeCallbacks(driveRunnable);handler.postDelayed(driveRunnable,delay);return;}"));
        assertFalse(poll.contains("scheduleDrivePoll"));
        assertTrue(poll.indexOf("handler.postDelayed(driveRunnable,delay)") < poll.indexOf("authorizeAndRunDrive()"));
    }

    private static String src(String f) throws Exception {
        Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);
        if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);
        return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
    }
    private static String between(String s,String a,String b){return s.substring(s.indexOf(a),s.indexOf(b));}
}
