package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class SelfRunDriveCommandAckPollingTest {
    @Test public void bootstrapSubmissionStartsDomCompletionObservation() throws Exception {
        String service = source("SelfRunService.java");
        String store = source("SelfRunStore.java");
        String submitted = section(service, "private void bootstrapSubmitted", "private String commandPrompt");
        assertTrue(submitted.contains("store.bootstrapSubmissionConfirmed(token)"));
        assertTrue(store.contains("void bootstrapSubmissionConfirmed(String observerToken)"));
        assertTrue(store.contains("첫 요청 제출 확인 · 답변 완료 감지 중"));
        assertFalse(submitted.contains("authorizeAndRunDrive"));
        assertFalse(submitted.contains("command_received_ack"));
    }

    @Test public void legacyDriveWaitMigratesToObserverWaitWithoutResubmitting() throws Exception {
        String store = source("SelfRunStore.java");
        String migration = section(store, "private void migrateLegacyTurnCompletionFlow", "private void migrateRetiredSignalDisplay");
        assertTrue(migration.contains("LEGACY_PHASE_WAIT_DRIVE_COMMIT"));
        assertTrue(migration.contains("PHASE_WAIT_INTERNAL_SEND"));
        assertTrue(migration.contains("PHASE_WAIT_TURN_COMPLETION"));
        assertFalse(migration.contains("PHASE_BOOTSTRAP_SEND"));
    }

    @Test public void normalCompletionHasNoDriveOrButtonPollingClock() throws Exception {
        String service = source("SelfRunService.java");
        assertFalse(service.contains("NORMAL_POLL_MS"));
        assertFalse(service.contains("scheduleDrivePoll"));
        assertFalse(service.contains("SelfRunContinuationDom.buttonState("));
        assertTrue(service.contains("POST_PROTOCOL_DRIVE_RETRY_MS = 5_000L"));
        assertTrue(service.contains("POST_PROTOCOL_DRIVE_MAX_WAIT_MS = 3 * 60_000L"));
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
