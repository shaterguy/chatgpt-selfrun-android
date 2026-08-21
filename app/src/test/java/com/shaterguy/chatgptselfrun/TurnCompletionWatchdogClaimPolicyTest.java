package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class TurnCompletionWatchdogClaimPolicyTest {
    @Test public void claimNameIsDeterministicPerPersistedAttemptAndSafeForDocs() {
        String first = SelfRunStore.watchdogClaimNameFor("SR-20260821-090006-GFSVZG", 7);
        String same = SelfRunStore.watchdogClaimNameFor("SR-20260821-090006-GFSVZG", 7);
        String next = SelfRunStore.watchdogClaimNameFor("SR-20260821-090006-GFSVZG", 8);
        assertEquals(first, same);
        assertNotEquals(first, next);
        assertTrue(first.matches("[A-Za-z0-9._-]{1,256}"));
    }

    @Test public void docsClaimUsesSnapshotRevisionAndNamedRangeCas() throws Exception {
        String drive = sourceMain("DriveApiClient.java");
        String snapshot = section(drive, "static final class DocumentSnapshot", "String getAccountPermissionId");
        assertTrue(snapshot.contains("revisionId"));
        assertTrue(snapshot.contains("namedRanges"));
        assertTrue(snapshot.contains("hasNamedRange"));

        String claim = section(drive, "boolean createNamedRangeClaim", "private Metadata create");
        assertTrue(claim.contains("createNamedRange"));
        assertTrue(claim.contains("requiredRevisionId"));
        assertTrue(claim.contains("snapshot.revisionId"));
        assertTrue(claim.contains("current.hasNamedRange(claimName)"));
        assertTrue(claim.contains("!snapshot.revisionId.equals(current.revisionId)"));
        assertTrue(claim.contains("return false"));
    }

    @Test public void recoveryClaimIsParserInvisibleAndDoesNotExpandClaimGrammar() throws Exception {
        String parser = sourceMain("DriveCommitParser.java");
        String protocol = sourceMain("SelfRunProtocol.java");
        assertFalse(parser.contains("selfrun_watchdog_"));
        assertFalse(protocol.contains("selfrun_watchdog_"));
        assertFalse(parser.contains("WATCHDOG_CLAIM"));
    }

    @Test public void finalFenceClaimsBeforeOpeningClickPhase() throws Exception {
        String service = sourceMain("SelfRunService.java");
        String poll = section(service, "private void pollDriveNow", "private void replayTerminalSideEffect");
        int fence = poll.indexOf("if(watchdogFinalRecheck)");
        int applySignals = poll.indexOf("store.applyDriveSignals(normalUnseen,System.currentTimeMillis())", fence);
        int quarantine = poll.indexOf("normalUnseen.size()!=scan.unseen.size()", fence);
        int claimStart = poll.indexOf("store.beginWatchdogClaim(scan.totalCount)", fence);
        int cas = poll.indexOf("drive.createNamedRangeClaim", fence);
        int openClick = poll.indexOf("store.ownWatchdogClaimAndEnterClick", fence);
        assertTrue(fence >= 0);
        assertTrue(applySignals > fence);
        assertTrue(quarantine > applySignals);
        assertTrue(claimStart > quarantine);
        assertTrue(cas > claimStart);
        assertTrue(openClick > cas);
        assertFalse(section(poll, "if(watchdogFinalRecheck)", "if(!applyDriveResult(epoch,()->{if(scan.cursorRebased)")
                .contains("clickPreparedDriveTurn"));
    }

    @Test public void revisionConflictCannotOpenClickPhase() throws Exception {
        String service = sourceMain("SelfRunService.java");
        String poll = section(service, "private void pollDriveNow", "private void replayTerminalSideEffect");
        String fence = section(poll, "boolean acquired=drive.createNamedRangeClaim", "postDriveOutcome();return;");
        assertTrue(fence.contains("if(!acquired)"));
        assertTrue(fence.contains("WATCHDOG_CLAIM_REVISION_CONFLICT"));
        assertTrue(fence.indexOf("if(!acquired)") < fence.indexOf("ownWatchdogClaimAndEnterClick"));
    }

    @Test public void ownedClaimSurvivesComposerReprepareAndDoesNotClaimTwice() throws Exception {
        String service = sourceMain("SelfRunService.java");
        String handler = section(service, "private void handleWebResult", "private String driveBootstrap");
        String ready = section(handler,
                "if(PHASE_WATCHDOG_SEND_CONTINUE.equals(phase)&&\"READY_TO_SUBMIT\".equals(status))",
                "scheduleWeb(750L)");
        assertTrue(ready.contains("store.watchdogClaimOwned()"));
        assertTrue(ready.contains("transition(PHASE_WATCHDOG_CLICK_CONTINUE"));
        assertTrue(ready.indexOf("store.watchdogClaimOwned()") < ready.indexOf("transition(PHASE_WATCHDOG_FINAL_RECHECK"));
    }

    @Test public void stopAfterClaimAtomicallyAbandonsRecoveryAndRestartsWindow() throws Exception {
        String service = sourceMain("SelfRunService.java");
        String evaluate = section(service, "private void evaluate", "private void recordContinuationWait");
        assertTrue(evaluate.contains("store.abandonWatchdogClaimAndWait"));
        assertTrue(evaluate.contains("watchdog_final_click_stop"));
        String store = sourceMain("SelfRunStore.java");
        String method = section(store, "void abandonWatchdogClaimAndWait", "void finishWatchdogRecoveryBaseline");
        assertTrue(method.contains("clearWatchdogClaimFields"));
        assertTrue(method.contains("PHASE_WAIT_DRIVE_COMMIT"));
        assertTrue(method.contains("phaseStartedAt"));
    }

    @Test public void submissionConfirmationStartsCorrelatedThirtyMinuteWaitImmediately() throws Exception {
        String service = sourceMain("SelfRunService.java");
        String submitted = section(service, "private void continuationSubmitted", "private void bootstrapSubmitted");
        assertTrue(submitted.contains("store.watchdogClaimOwned()"));
        assertTrue(submitted.contains("SelfRunProtocol.watchdogRecoveryId(store.watchdogClaimAttempt())"));
        assertTrue(submitted.contains("store.confirmWatchdogSubmission(SelfRunStore.PHASE_WAIT_DRIVE_COMMIT"));
        assertTrue(submitted.contains("scheduleDrivePoll(0L)"));
        assertFalse(submitted.contains("PHASE_WATCHDOG_POST_SUBMIT_BASELINE"));
        assertFalse(submitted.contains("authorizeAndRunDrive"));

        String poll = section(service, "private void pollDriveNow", "private void replayTerminalSideEffect");
        assertTrue(poll.contains("watchdogRecoveryAwaiting=SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(snapshot.phase)&&store.watchdogClaimSubmitted()"));
        assertTrue(poll.contains("expectedRecoveryId=SelfRunProtocol.watchdogRecoveryId(store.watchdogClaimAttempt())"));
        assertTrue(poll.contains("DriveSignalParser.recoveryId(event.raw)"));
        assertTrue(poll.contains("matching_completion_cursor"));
        assertTrue(poll.contains("stale_completion_count"));
        assertFalse(section(poll, "if(watchdogRecoveryAwaiting)", "if(latestCompletion!=null")
                .contains("finishWatchdogRecoveryBaseline"));
    }

    @Test public void claimOutcomeUnknownIsReadbackRetryNotSecondSubmit() throws Exception {
        String service = sourceMain("SelfRunService.java");
        String failures = section(service, "private void handleDriveFailure", "private void scheduleDriveRecovery");
        assertTrue(failures.contains("PHASE_WATCHDOG_FINAL_RECHECK.equals(store.phase())"));
        assertTrue(failures.contains("WATCHDOG_CLAIM_RESULT_PENDING"));
        String drive = sourceMain("DriveApiClient.java");
        String claim = section(drive, "boolean createNamedRangeClaim", "private Metadata create");
        assertTrue(claim.contains("snapshot.hasNamedRange(claimName)"));
        assertTrue(claim.contains("watchdog recovery claim result unknown"));
    }

    @Test public void watchdogMarkerIncludesPersistentAttemptSoLaterRecoveriesDoNotReuseConfirmedMarker() throws Exception {
        String service = sourceMain("SelfRunService.java");
        String marker = section(service, "private String continuationMarkerId", "private void clearContinuationAttempt");
        assertTrue(marker.contains("store.watchdogClaimAttempt()"));
        assertTrue(marker.contains("store.driveSignalCursor()"));
    }

    private static String sourceMain(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String section(String text, String start, String end) {
        int a = text.indexOf(start);
        int b = text.indexOf(end, a + start.length());
        assertTrue("missing start: " + start, a >= 0);
        assertTrue("missing end: " + end, b > a);
        return text.substring(a, b);
    }
}
