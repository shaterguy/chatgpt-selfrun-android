package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Process restart and the first recoverable Drive 401 must preserve pinned Result authority. */
public final class SelfRun3AuthorityRestartReadTest {
    @Test public void recoveredPreDispatchStateReadsPinnedResultBeforeWebDispatch() throws Exception {
        String source = coordinatorSource();
        assertTrue(source.contains("shouldReadAuthoritativeResultBeforeDispatch(state)"));
        assertTrue(source.contains("ReadTrigger.AUTHORITY"));
        assertTrue(source.contains("authoritativeReadCheckedTurns.add(expectedTurn)"));
        assertFalse(source.contains("RESULT_EXISTS_BEFORE_DISPATCH"));
    }

    @Test public void firstUnauthorizedReadRefreshesWithoutTerminalAck() throws Exception {
        String source = coordinatorSource();
        int catchBlock = source.indexOf("error instanceof DriveApiClient.ApiException api && api.status == 401");
        int refresh = source.indexOf("retryDriveStepAfterUnauthorized(", catchBlock);
        int handlerEnd = source.indexOf("if (step == DriveStep.READ_RESULT) serverRegisteredTurns.remove(expectedTurn);", refresh);
        assertTrue(catchBlock >= 0);
        assertTrue(refresh > catchBlock);
        assertTrue(handlerEnd > refresh);
        String unauthorizedHandler = source.substring(catchBlock, handlerEnd);
        assertFalse(unauthorizedHandler.contains("acknowledgeProcessed(push)"));
    }

    private static String coordinatorSource() throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3Coordinator.java");
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRun3Coordinator.java");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
