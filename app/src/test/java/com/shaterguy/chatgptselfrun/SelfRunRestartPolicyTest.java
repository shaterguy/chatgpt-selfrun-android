package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class SelfRunRestartPolicyTest {
    @Test public void onlyUserStoppedHistoricalRunIsRestartable() {
        String conversation = "https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc";
        assertTrue(SelfRunRestartPolicy.restartable("SR-1", SelfRunStore.PHASE_IDLE,
                conversation, true, false));
        assertFalse(SelfRunRestartPolicy.restartable("SR-2", SelfRunStore.PHASE_PAUSED,
                conversation, false, true));
        assertFalse(SelfRunRestartPolicy.restartable("SR-3", SelfRunStore.PHASE_DONE,
                conversation, true, false));
        assertFalse(SelfRunRestartPolicy.restartable("SR-4", SelfRunStore.PHASE_IDLE,
                "", true, false));
    }

    @Test public void sameProcessRestartClaimNeverExpiresByElapsedTime() {
        assertTrue(SelfRunRestartPolicy.processClaimConflicts("claim-1", "process-A", "process-A"));
        assertFalse(SelfRunRestartPolicy.processClaimConflicts("claim-1", "process-A", "process-B"));
        assertFalse(SelfRunRestartPolicy.processClaimConflicts("", "process-A", "process-A"));
    }

    @Test public void restartNeverUsesBootstrapPhase() {
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE,
                SelfRunRestartPolicy.restartPhase(SelfRunStore.MODE_CHAT));
        assertEquals(SelfRunStore.PHASE_APPLY_PREFS,
                SelfRunRestartPolicy.restartPhase(SelfRunStore.MODE_WORK));
    }

    @Test public void restartUsesCompletionWrittenAfterStopCursor() throws Exception {
        String runId = "SR-RESTART-NEW";
        String first = "[2026.08.24 | 21:00:00] [SELF_RUN_TURN_COMPLETED " + runId
                + " MODEL=sol REASONING=xhigh]";
        String second = "[2026.08.24 | 21:10:00] [SELF_RUN_TURN_COMPLETED " + runId
                + " MODEL=luna REASONING=max]";
        JSONObject snapshot = new JSONObject().put("driveSignalCursor", 1)
                .put("pendingDriveSignalType", "");
        DriveSignalParser.Scan scan = DriveSignalParser.scan(first + "\n" + second, runId, 1,
                SelfRunStore.MODE_WORK);
        DriveSignalParser.Event recovered = SelfRunRestartPolicy.restartCompletion(scan, snapshot);
        assertNotNull(recovered);
        assertEquals(second, recovered.raw);
        DriveSignalParser.WorkProfile profile = DriveSignalParser.workProfile(recovered.raw);
        assertTrue(profile.valid);
        assertEquals("luna", profile.model);
        assertEquals("max", profile.reasoning);
    }

    @Test public void restartRecoversAlreadyConsumedPendingCompletionFromDocument() throws Exception {
        String runId = "SR-RESTART-PENDING";
        String completion = "[2026.08.24 | 21:10:00] [SELF_RUN_TURN_COMPLETED " + runId
                + " MODEL=terra REASONING=xhigh NEXT_INPUT_B64URL=6rOE7IaN]";
        JSONObject snapshot = new JSONObject().put("driveSignalCursor", 1)
                .put("pendingDriveSignalType", DriveSignalParser.Type.TURN_COMPLETED.name());
        DriveSignalParser.Scan scan = DriveSignalParser.scan(completion, runId, 1, SelfRunStore.MODE_WORK);
        DriveSignalParser.Event recovered = SelfRunRestartPolicy.restartCompletion(scan, snapshot);
        assertNotNull(recovered);
        assertEquals(completion, recovered.raw);
        assertEquals("계속", DriveSignalParser.nextInput(recovered.raw).text);
    }

    @Test public void restartDoesNotInventCompletionWithoutNewOrPendingSignal() throws Exception {
        String runId = "SR-RESTART-NONE";
        String completion = "[2026.08.24 | 21:10:00] [SELF_RUN_TURN_COMPLETED " + runId
                + " MODEL=sol REASONING=xhigh]";
        JSONObject snapshot = new JSONObject().put("driveSignalCursor", 1)
                .put("pendingDriveSignalType", "");
        DriveSignalParser.Scan scan = DriveSignalParser.scan(completion, runId, 1, SelfRunStore.MODE_WORK);
        assertNull(SelfRunRestartPolicy.restartCompletion(scan, snapshot));
    }

    @Test public void reusedDocumentUsesPlainContinuation() {
        String prompt = SelfRunRestartPolicy.continuationPrompt("SR-REUSE", "");
        assertTrue(prompt.matches("^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}] \\[SELF_RUN_CONTINUE SR-REUSE]$"));
        assertFalse(prompt.toLowerCase().contains("command received"));
        assertFalse(prompt.contains("SELF_RUN_BOOTSTRAP"));
        assertFalse(prompt.contains("DRIVE_TURN_DOCUMENT_ID="));
    }

    @Test public void replacementDocumentIsDeclaredInContinuation() {
        String documentId = "1AbCdEfGhIjKlMnOpQrStUvWxYz";
        String prompt = SelfRunRestartPolicy.continuationPrompt("SR-RECOVERY", documentId);
        assertTrue(prompt.contains("[SELF_RUN_CONTINUE SR-RECOVERY]"));
        assertTrue(prompt.contains("DRIVE_TURN_DOCUMENT_ID=" + documentId));
        assertFalse(prompt.toLowerCase().contains("command received"));
        assertTrue(prompt.contains("향후 SelfRun Drive signal"));
        assertTrue(prompt.contains("Bootstrap은 재실행하지 말 것"));
        assertFalse(prompt.contains("SELF_RUN_BOOTSTRAP"));
    }

    @Test public void testApplicationIdentityIsStableAndSeparate() {
        assertEquals("com.shaterguy.chatgptselfrun.drive.test",
                SelfRunRestartPolicy.TEST_APPLICATION_ID);
        assertNotEquals("com.shaterguy.chatgptselfrun.drive",
                SelfRunRestartPolicy.TEST_APPLICATION_ID);
    }
}
