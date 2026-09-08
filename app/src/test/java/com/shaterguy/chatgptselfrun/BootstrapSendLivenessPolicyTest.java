package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/** V3 submission liveness: bounded active preparation, passive normal wait, bounded reconciliation. */
public final class BootstrapSendLivenessPolicyTest {
    @Test public void browserCallbacksAreBoundedButNormalResponseWaitDoesNotPoll() {
        assertEquals(5_000L, SelfRun3PowerPolicy.CALLBACK_TIMEOUT_MS);
        assertEquals(90_000L, SelfRun3PowerPolicy.WEB_PREPARATION_MAX_MS);
        assertEquals(60_000L, SelfRun3PowerPolicy.NORMAL_WAIT_POLL_MS);
    }

    @Test public void preparedSendIsClaimedDurablyBeforeTheBrowserClick() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String prepared = between(coordinator, "onPrepared(String task", "onDispatched(String task");
        assertTrue(prepared.contains("SelfRun3Engine.Kind.CLAIM_SEND"));
        assertTrue(prepared.indexOf("ledger.apply") < prepared.indexOf("web.submit(claimed.execution(turn))"));
        assertTrue(prepared.contains("SelfRun3PowerPolicy.maySend(claimed.execution(turn))"));
    }

    @Test public void generationWaitDetachesRasterAndReleasesWakeLock() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String wait = between(coordinator, "case WAIT, READ_RESULT, CHECK_RECEIPT ->", "case COMMIT ->");
        assertTrue(wait.contains("web.detach()"));
        assertTrue(wait.contains("releaseWakeLock()"));
        assertFalse(wait.contains("scheduleMissedProbe"));
        assertTrue(wait.contains("scheduleNext("));
    }

    @Test public void unknownSubmissionOutcomeNeverReopensSend() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(web.contains("SUBMISSION_OUTCOME_UNKNOWN"));
        assertTrue(coordinator.contains("SelfRun3Engine.waitingExecutions(state)"));
        assertFalse(coordinator.contains("SUBMISSION_OUTCOME_UNKNOWN\")" + ", web.submit"));
    }

    private static String between(String source, String start, String end) {
        int a = source.indexOf(start), b = source.indexOf(end, Math.max(0, a));
        return a >= 0 && b > a ? source.substring(a, b) : "";
    }

    private static String source(String name) throws Exception {
        Path path=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(path))path=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
