package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class TurnCompletionWatchdogRecoveryIdPolicyTest {
    private static final String RUN = "SR-RECOVERY-TEST";

    @Test public void normalContinuationGrammarIsUnchanged() {
        String normal = SelfRunProtocol.driveContinuation(RUN);
        assertTrue(normal.contains("[SELF_RUN_CONTINUE " + RUN + "]"));
        assertFalse(normal.contains("RECOVERY_ID="));
    }

    @Test public void recoveryContinuationCarriesOnlyDurableRecoveryIdentity() {
        assertEquals("wd.7", SelfRunProtocol.watchdogRecoveryId(7));
        assertNotEquals(SelfRunProtocol.watchdogRecoveryId(7), SelfRunProtocol.watchdogRecoveryId(8));
        String recovery = SelfRunProtocol.driveRecoveryContinuation(RUN, SelfRunProtocol.watchdogRecoveryId(7));
        assertTrue(recovery.contains("[SELF_RUN_CONTINUE " + RUN + " RECOVERY_ID=wd.7]"));
        assertFalse(recovery.contains("NEXT_INPUT_B64URL="));
    }

    @Test public void chatRecoveryCompletionReturnsSameIdentity() {
        String raw = "[2026.08.21 | 12:00:00] [SELF_RUN_TURN_COMPLETED " + RUN + " RECOVERY_ID=wd.7]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(raw, RUN, 0, SelfRunStore.MODE_CHAT);
        assertEquals(1, scan.unseen.size());
        DriveSignalParser.Event event = scan.unseen.get(0);
        assertEquals(DriveSignalParser.Type.TURN_COMPLETED, event.type);
        assertEquals("wd.7", DriveSignalParser.recoveryId(event.raw));
        assertTrue(event.protocolError.isEmpty());
    }

    @Test public void chatRecoveryCompletionMayPreserveNextInput() {
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("다음 작업".getBytes(StandardCharsets.UTF_8));
        String raw = "[2026.08.21 | 12:00:00] [SELF_RUN_TURN_COMPLETED " + RUN
                + " RECOVERY_ID=wd.7 NEXT_INPUT_B64URL=" + encoded + "]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(raw, RUN, 0, SelfRunStore.MODE_CHAT);
        DriveSignalParser.Event event = scan.unseen.get(0);
        assertEquals("wd.7", DriveSignalParser.recoveryId(event.raw));
        assertEquals("다음 작업", DriveSignalParser.nextInput(event.raw).text);
    }

    @Test public void workRecoveryCompletionKeepsProfileAndIdentity() {
        String raw = "[2026.08.21 | 12:00:00] [SELF_RUN_TURN_COMPLETED " + RUN
                + " MODEL=sol REASONING=xhigh RECOVERY_ID=wd.9]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(raw, RUN, 0, SelfRunStore.MODE_WORK);
        assertEquals(1, scan.unseen.size());
        assertEquals("wd.9", DriveSignalParser.recoveryId(scan.unseen.get(0).raw));
        DriveSignalParser.WorkProfile profile = DriveSignalParser.workProfile(raw);
        assertTrue(profile.valid);
        assertEquals("sol", profile.model);
        assertEquals("xhigh", profile.reasoning);
    }

    @Test public void noIdAndMismatchedIdRemainDistinguishable() {
        String normal = "[2026.08.21 | 12:00:00] [SELF_RUN_TURN_COMPLETED " + RUN + "]";
        String oldRecovery = "[2026.08.21 | 12:00:01] [SELF_RUN_TURN_COMPLETED " + RUN + " RECOVERY_ID=wd.6]";
        assertEquals("", DriveSignalParser.recoveryId(normal));
        assertEquals("wd.6", DriveSignalParser.recoveryId(oldRecovery));
        assertNotEquals(SelfRunProtocol.watchdogRecoveryId(7), DriveSignalParser.recoveryId(oldRecovery));
        assertTrue(DriveSignalParser.hasRecoveryIdField(oldRecovery));
    }

    @Test public void malformedRecoveryIdentityCannotMatchOrBecomeLatestNormalCompletion() {
        String normal = "[2026.08.21 | 11:59:59] [SELF_RUN_TURN_COMPLETED " + RUN + "]";
        String bad = "[2026.08.21 | 12:00:00] [SELF_RUN_TURN_COMPLETED " + RUN + " RECOVERY_ID=bad/id]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(normal + "\n" + bad, RUN, 0, SelfRunStore.MODE_CHAT);
        assertEquals(2, scan.unseen.size());
        assertTrue(DriveSignalParser.hasRecoveryIdField(scan.unseen.get(1).raw));
        assertEquals("", DriveSignalParser.recoveryId(scan.unseen.get(1).raw));
        assertFalse(scan.unseen.get(1).protocolError.isEmpty());
        assertEquals(normal, DriveSignalParser.latestCompletion(scan.unseen).raw);
    }

    @Test public void recoveryTaggedEventsStayVisibleForExactRecoveryMatching() {
        String older = "[2026.08.21 | 12:00:00] [SELF_RUN_TURN_COMPLETED " + RUN + " RECOVERY_ID=wd.4]";
        String current = "[2026.08.21 | 12:00:01] [SELF_RUN_TURN_COMPLETED " + RUN + " RECOVERY_ID=wd.5]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(older + "\n" + current, RUN, 0, SelfRunStore.MODE_CHAT);
        assertEquals(2, scan.totalCount);
        assertEquals(2, scan.unseen.size());
        assertEquals("wd.4", DriveSignalParser.recoveryId(scan.unseen.get(0).raw));
        assertEquals("wd.5", DriveSignalParser.recoveryId(scan.unseen.get(1).raw));
        assertNull(DriveSignalParser.latestCompletion(scan.unseen));
    }

    @Test public void sourceQuarantinesEveryRecoveryTaggedCompletionFromNormalApply() throws Exception {
        String service = source("SelfRunService.java");
        String gate = section(service, "private static java.util.List<DriveSignalParser.Event> normalDriveEvents", "private void pollDriveNow");
        assertTrue(gate.contains("DriveSignalParser.hasRecoveryIdField(event.raw)"));
        assertTrue(gate.contains("continue"));

        String poll = section(service, "private void pollDriveNow", "private void replayTerminalSideEffect");
        assertTrue(poll.contains("normalUnseen=normalDriveEvents(scan.unseen)"));
        assertTrue(poll.contains("DriveSignalParser.latestCompletion(normalUnseen)"));
        assertTrue(poll.contains("normalUnseen.size()!=scan.unseen.size()"));
        assertTrue(poll.contains("store.baselineDriveSignals(scan.totalCount,scan.latest)"));
        assertFalse(poll.contains("store.applyDriveSignals(scan.unseen,System.currentTimeMillis())"));
    }

    @Test public void sourceWaitsForMatchingIdWithoutPostSubmitBaseline() throws Exception {
        String service = source("SelfRunService.java");
        String submitted = section(service, "private void continuationSubmitted", "private void bootstrapSubmitted");
        assertTrue(submitted.contains("confirmWatchdogSubmission(SelfRunStore.PHASE_WAIT_DRIVE_COMMIT"));
        assertFalse(submitted.contains("PHASE_WATCHDOG_POST_SUBMIT_BASELINE"));

        String poll = section(service, "private void pollDriveNow", "private void replayTerminalSideEffect");
        assertTrue(poll.contains("store.watchdogClaimSubmitted()"));
        assertTrue(poll.contains("expectedRecoveryId.equals(DriveSignalParser.recoveryId(event.raw))"));
        assertTrue(poll.contains("matching_completion_cursor"));
        assertTrue(poll.contains("stale_completion_count"));
        assertTrue(poll.contains("turnCompletionWatchdogDue(store.phase(),store.phaseStartedAt(),System.currentTimeMillis())"));
    }

    private static String source(String name) throws Exception {
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
