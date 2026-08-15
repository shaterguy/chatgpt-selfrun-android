package com.shaterguy.chatgptselfrun;

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
