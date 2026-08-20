package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class SelfRunDriveCommandAckPollingTest {
    @Test public void bootstrapSubmissionWaitsForDriveResultsNotCommandReceived() throws Exception {
        String service = src("SelfRunService.java");
        String store = src("SelfRunStore.java");
        String submitted = between(service, "private void bootstrapSubmitted", "private String commandPrompt");

        assertTrue(submitted.contains("store.bootstrapSubmissionConfirmed()"));
        assertTrue(submitted.contains("command_received_ack=unused"));
        assertTrue(store.contains("void bootstrapSubmissionConfirmed"));
        assertTrue(store.contains("첫 요청 제출 확인 · Drive 턴 결과 신호 대기"));
        assertFalse(service.contains("BOOTSTRAP_COMMAND_ACK_RETRY_MS"));
        assertFalse(service.contains("prepareCommandRetry"));
    }

    @Test public void installedDev5AckWaitIsMigratedWithoutResubmitting() throws Exception {
        String store = src("SelfRunStore.java");
        String migration = between(store, "void migrateLegacyBootstrapAckWait", "void migrateLegacyDriveCommitGuard");
        assertTrue(migration.contains("RETRY_BOOTSTRAP"));
        assertTrue(migration.contains("PHASE_WAIT_DRIVE_COMMIT"));
        assertTrue(migration.contains("clearCommandWait"));
        assertTrue(migration.contains("업데이트된 bootstrap · Drive 턴 결과 신호 대기"));
        assertFalse(migration.contains("PHASE_BOOTSTRAP_SEND"));
    }

    @Test public void pollingNeverSchedulesFiveMinuteAckRetry() throws Exception {
        String service = src("SelfRunService.java");
        String poll = between(service, "private void pollDriveNow", "private void replayTerminalSideEffect");
        assertTrue(poll.contains("drive.readDocumentText(accessToken,snapshot.turnDocumentId)"));
        assertFalse(poll.contains("submissionRetryDue"));
        assertFalse(poll.contains("prepareCommandRetry"));
        assertTrue(service.contains("private static final long NORMAL_POLL_MS = 60_000L"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }
}
