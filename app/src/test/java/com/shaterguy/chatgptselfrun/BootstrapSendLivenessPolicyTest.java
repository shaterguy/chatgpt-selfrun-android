package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class BootstrapSendLivenessPolicyTest {
    @Test public void submissionCallbacksRemainGuardedButProtocolWaitIsPassive() {
        assertTrue(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_BOOTSTRAP_SEND));
        assertFalse(SelfRunService.shouldGuardContinuationCallback(SelfRunStore.PHASE_WAIT_TURN_COMPLETION));
        assertEquals(5_000L,SelfRunService.CONTINUATION_CALLBACK_TIMEOUT_MS);
    }
    @Test public void bootstrapDeadlineStillFailsClosed() {
        assertFalse(SelfRunService.bootstrapSendTimedOut(1_000L,60_999L));
        assertTrue(SelfRunService.bootstrapSendTimedOut(1_000L,61_000L));
        assertEquals(60_000L,SelfRunService.BOOTSTRAP_SEND_MAX_WAIT_MS);
    }
    @Test public void confirmedSubmissionPersistsWaitThenDetachesThenArms() throws Exception {
        String service=source("SelfRunService.java");
        String method=service.substring(service.indexOf("private void continuationSubmitted"),service.indexOf("private void armProtocolCompletion"));
        assertTrue(method.indexOf("store.beginTurnCompletionWait")<method.indexOf("detachDisplayOutput"));
        assertTrue(method.indexOf("detachDisplayOutput")<method.indexOf("armProtocolCompletion"));
    }
    private static String source(String name) throws Exception {
        Path path=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(path))path=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return Files.readString(path,StandardCharsets.UTF_8);
    }
}
