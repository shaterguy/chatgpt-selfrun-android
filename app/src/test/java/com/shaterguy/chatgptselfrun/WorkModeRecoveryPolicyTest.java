package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class WorkModeRecoveryPolicyTest {
    @Test public void manualPauseResumesTheSameWebPhaseInsteadOfDriveBaseline() {
        assertTrue(SelfRunStore.isManualResumeWebPhase(SelfRunStore.PHASE_WAIT_TURN_COMPLETION));
        assertTrue(SelfRunStore.isManualResumeWebPhase(SelfRunStore.PHASE_APPLY_PREFS));
        assertTrue(SelfRunStore.isManualResumeWebPhase(SelfRunStore.PHASE_APPLY_REASONING));
        assertTrue(SelfRunStore.isManualResumeWebPhase(SelfRunStore.PHASE_SEND_CONTINUE));
        assertFalse(SelfRunStore.isManualResumeWebPhase(SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC));
        assertFalse(SelfRunStore.isManualResumeWebPhase(SelfRunStore.PHASE_RESUME_BASELINE));
    }

    @Test public void commonCompletionPathHasNoWorkOnlyDriveFallback() throws Exception {
        String service = source("SelfRunService.java");
        String dom = source("SelfRunContinuationDom.java");
        String wait = section(service,
                "case SelfRunStore.PHASE_WAIT_TURN_COMPLETION",
                "case SelfRunStore.PHASE_APPLY_PREFS");
        assertTrue(wait.contains("SelfRunContinuationDom.observeTurnCompletion"));
        assertFalse(wait.contains("authorizeAndRunDrive"));
        String observer = section(dom,
                "static String observeTurnCompletion",
                "static String cancelTurnCompletionObserver");
        assertFalse(observer.contains("MODE_WORK"));
    }

    @Test public void consumedNextTurnReservationForcesVisibleEditorSync() throws Exception {
        String activity = source("MainActivity.java");
        assertTrue(activity.contains("boolean reservationConsumed"));
        assertTrue(activity.contains("runChanged || !nextInputEditor.hasFocus() || reservationConsumed"));
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
