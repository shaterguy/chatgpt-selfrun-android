package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Regression coverage for the dev6 signal-to-live-WebView continuation path. */
public class SelfRunDriveDev3PolicyTest {
    @Test public void modifiedTimeIsOnlyReadOptimization() throws Exception {
        String poll = between(src("SelfRunService.java"), "private void pollDriveNow", "private void replayTerminalSideEffect");
        assertTrue(poll.contains("DriveSignalParser.scan"));
        assertTrue(poll.contains("scan.unseen"));
        assertTrue(poll.contains("cursorRebased"));
        assertFalse(poll.contains("FUTURE_TURN"));
    }

    @Test public void commandReceivedIsNeverAnApplicationSubmissionGate() throws Exception {
        String service = src("SelfRunService.java");
        String store = src("SelfRunStore.java");
        assertFalse(service.contains("BOOTSTRAP_COMMAND_ACK_RETRY_MS"));
        assertFalse(service.contains("prepareCommandRetry"));
        assertFalse(store.contains("void markCommandSubmitted"));
        assertFalse(store.contains("void prepareCommandRetry"));
        assertFalse(service.contains("command_received_ack"));
    }

    @Test public void completionGoesDirectlyToInternalSendStateWithoutGuard() throws Exception {
        String apply = between(src("SelfRunStore.java"), "void applyDriveSignals", "void beginManualResumeOverride");
        assertTrue(apply.contains("PHASE_WAIT_INTERNAL_SEND"));
        assertTrue(apply.contains("내부 WebView SEND 상태 확인"));
        assertFalse(apply.contains("안전 지연"));
        assertFalse(apply.contains("PHASE_DRIVE_COMMIT_GUARD"));
    }

    @Test public void resumeDecisionNeverUsesLatestSignalType() throws Exception {
        String st = src("SelfRunStore.java"), b = between(st, "void baselineManualResume", "void captureConversationUrl");
        assertTrue(b.contains("driveSignalCursor"));
        assertTrue(b.contains("PHASE_SEND_CONTINUE"));
        assertFalse(b.contains("event.type"));
    }

    private static String src(String f) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + f);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + f);
        return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String between(String s, String a, String b) {
        int from = s.indexOf(a), to = s.indexOf(b, from);
        assertTrue(from >= 0 && to > from);
        return s.substring(from, to);
    }
}
