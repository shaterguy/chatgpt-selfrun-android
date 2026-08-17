package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class SelfRunDriveCommandAckPollingTest {
    @Test public void commandAckWaitForcesBodyReadDespiteStaleDriveMetadata() throws Exception {
        String store = src("SelfRunStore.java");
        String submitted = between(store, "void markCommandSubmitted", "void prepareCommandRetry");
        String seen = between(store, "void updateDriveSeen", "void baselineDriveSignals");

        assertTrue(store.contains("COMMAND_ACK_FORCE_BODY_VERSION"));
        assertTrue(submitted.contains("putBoolean(\"awaitingCommandAck\",true)"));
        assertTrue(submitted.contains("putString(\"lastSeenDriveVersion\",COMMAND_ACK_FORCE_BODY_VERSION)"));
        assertTrue(seen.contains("awaitingCommandAck() ? COMMAND_ACK_FORCE_BODY_VERSION : safe(version)"));
    }

    @Test public void turnCompletionWaitKeepsMetadataOptimizationAfterAck() throws Exception {
        String store = src("SelfRunStore.java");
        String service = src("SelfRunService.java");
        String apply = between(store, "void applyDriveSignals", "void repairGuard");
        String seen = between(store, "void updateDriveSeen", "void baselineDriveSignals");
        String poll = between(service, "private void pollDriveNow", "private void replayTerminalSideEffect");

        assertTrue(apply.contains("if(awaiting){awaiting=false;clearCommandWait(e);}"));
        assertTrue(seen.contains(": safe(version)"));
        assertTrue(poll.contains("if(!changed&&!resume&&!retry){applyDriveResult(epoch,this::scheduleDrivePoll);return;}"));
        assertTrue(poll.indexOf("store.applyDriveSignals") < poll.indexOf("store.updateDriveSeen"));
    }

    @Test public void fiveMinuteRetryCanOnlyHappenAfterLatestBodyRead() throws Exception {
        String service = src("SelfRunService.java");
        String poll = between(service, "private void pollDriveNow", "private void replayTerminalSideEffect");

        int bodyRead = poll.indexOf("drive.readDocumentText(accessToken,snapshot.turnDocumentId)");
        int prepareRetry = poll.indexOf("store.prepareCommandRetry()");
        assertTrue(bodyRead >= 0);
        assertTrue(prepareRetry > bodyRead);
        assertTrue(service.contains("private static final long NORMAL_POLL_MS = 60_000L"));
        assertTrue(service.contains("SUBMISSION_RETRY_MS = 5 * 60_000L"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end);
        if (from < 0 || to < 0 || from >= to) throw new IllegalArgumentException("source markers not found");
        return source.substring(from, to);
    }
}
