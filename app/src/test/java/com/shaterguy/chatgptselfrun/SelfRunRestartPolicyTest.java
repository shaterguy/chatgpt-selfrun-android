package com.shaterguy.chatgptselfrun;

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
