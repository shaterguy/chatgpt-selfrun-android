package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Regression coverage for the dev3 observer-to-Drive continuation path. */
public final class SelfRunDriveDev3PolicyTest {
    @Test public void driveIsSynchronizerAfterDomCompletionNotCompletionClock() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("PHASE_WAIT_TURN_COMPLETION"));
        assertTrue(service.contains("PHASE_POST_DOM_DRIVE_SYNC"));
        assertTrue(service.contains("observeTurnCompletion"));
        assertFalse(service.contains("PHASE_WAIT_DRIVE_COMMIT"));
        assertFalse(service.contains("PHASE_WAIT_INTERNAL_SEND"));
    }

    @Test public void noSignalTimeoutKeepsCurrentProfileAndContinues() throws Exception {
        String store = source("SelfRunStore.java");
        String begin = section(store, "void beginTurnCompletionWait", "boolean beginPostDomDriveSync");
        String timeout = section(store, "void continueAfterPostDomDriveTimeout", "void applyDriveSignals");
        assertTrue(begin.contains("String appliedModel=pendingModel(),appliedReasoning=pendingReasoning()"));
        assertTrue(begin.contains("putString(\"pendingModel\",appliedModel)"));
        assertTrue(begin.contains("putString(\"pendingReasoning\",appliedReasoning)"));
        assertTrue(timeout.contains("MODE_WORK.equals(mode())?PHASE_APPLY_PREFS:PHASE_SEND_CONTINUE"));
        assertTrue(timeout.contains("현재 설정으로 다음 턴 전송"));
    }

    @Test public void driveCompletionPayloadCanOverrideProfileAndNextInput() throws Exception {
        String store = source("SelfRunStore.java");
        String apply = section(store, "void applyDriveSignals", "void beginManualResumeOverride");
        assertTrue(apply.contains("pendingDriveSignalRaw"));
        assertTrue(apply.contains("PHASE_APPLY_PREFS"));
        assertTrue(apply.contains("PHASE_SEND_CONTINUE"));
        assertTrue(store.contains("pendingNextInput()"));
        assertTrue(store.contains("pendingDriveWorkProfile()"));
    }

    @Test public void resumeDecisionNeverUsesLatestSignalType() throws Exception {
        String store = source("SelfRunStore.java");
        String resume = section(store, "void baselineManualResume", "static boolean canCaptureConversationUrl");
        assertTrue(resume.contains("driveSignalCursor"));
        assertTrue(resume.contains("PHASE_SEND_CONTINUE"));
        assertFalse(resume.contains("event.type"));
    }

    @Test public void recoveryIdleBaselineNeedsStopEvidenceForThisRunAndToken() throws Exception {
        String service = source("SelfRunService.java");
        String store = source("SelfRunStore.java");
        assertTrue(service.contains("turnObserverNeedsIdleBaseline=store!=null"));
        assertTrue(service.contains("store.turnObserverSawStop()"));
        String dom = source("SelfRunContinuationDom.java");
        assertTrue(dom.contains("stopSeenCallback"));
        assertTrue(store.contains("PHASE_WAIT_TURN_COMPLETION.equals(phase())"));
        assertTrue(store.contains("token.equals(turnObserverToken())"));
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
