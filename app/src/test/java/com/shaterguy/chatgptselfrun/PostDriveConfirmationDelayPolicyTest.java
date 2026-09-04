package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class PostDriveConfirmationDelayPolicyTest {
    @Test public void confirmedDriveOutcomeWaitsFiveSecondsBeforeExistingProgression() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("POST_PROTOCOL_DRIVE_RETRY_MS = 5_000L"));
        assertTrue(service.contains("POST_DRIVE_CONFIRMATION_DELAY_MS = 5_000L"));
        String outcome = section(service, "private void postDriveOutcome()", "private static java.util.List<DriveSignalParser.Event> normalDriveEvents");
        assertTrue(outcome.contains("handler.postDelayed"));
        assertTrue(outcome.contains("POST_DRIVE_CONFIRMATION_DELAY_MS"));
        assertTrue(outcome.contains("SelfRunStore.PHASE_APPLY_PREFS"));
        assertTrue(outcome.contains("SelfRunStore.PHASE_SEND_CONTINUE"));
        assertTrue(outcome.contains("SelfRunStore.PHASE_PAUSED"));
        assertTrue(outcome.contains("SelfRunStore.PHASE_DONE"));
        assertFalse(outcome.contains("Thread.sleep"));
    }

    @Test public void bothDriveSuccessPathsUseTheSameDelayedOutcome() throws Exception {
        String service = source("SelfRunService.java");
        String polling = section(service, "private void pollDriveNow(int epoch)", "private void handlePostProtocolDriveTimeout");
        assertEquals(2, count(polling, "postDriveOutcome();return;"));
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

    private static int count(String text, String needle) {
        int count = 0, from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }
}
