package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/** V3 submission liveness: one bounded conversation-creation stage, passive normal wait, bounded reconciliation. */
public final class BootstrapSendLivenessPolicyTest {
    @Test public void callbacksStayFixedWhileOperatorTimingsKeepLegacyDefaults() throws Exception {
        assertEquals(5_000L, SelfRun3PowerPolicy.CALLBACK_TIMEOUT_MS);
        assertEquals(90L, SelfRun3RuntimeSettings.DEFAULT_WEB_PREPARATION_SECONDS);
        assertEquals(30L, SelfRun3RuntimeSettings.DEFAULT_RESULT_POLL_SECONDS);
        String web = source("SelfRun3WebAdapter.java");
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(web.contains("prepareTimeoutMs = runtimeSettings.webPreparationMs()"));
        assertTrue(web.contains("SystemClock.elapsedRealtime() - prepareStarted >= prepareTimeoutMs"));
        assertTrue(web.contains("scope=conversation-create"));
        assertTrue(coordinator.contains("runtimeSettings.resultPollMs()"));
        assertFalse(coordinator.contains("SelfRun3PowerPolicy.NORMAL_WAIT_POLL_MS"));
        assertFalse(web.contains("SelfRun3PowerPolicy.WEB_PREPARATION_MAX_MS"));
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

    @Test public void ambiguousSubmissionCallbackCannotEndConversationCreationBeforeConfiguredTimeout() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        String submit = between(web, "void submit(SelfRun3Engine.State claimed)", "private static String profileScript");
        assertTrue(submit.contains("SUBMISSION_OUTCOME_UNKNOWN"));
        assertTrue(submit.contains("scope=conversation-create"));
        assertFalse(submit.contains("fail(\"SUBMISSION_OUTCOME_UNKNOWN\")"));
        assertTrue(submit.contains("preparing = true;"));
        assertTrue(web.contains("expireConversationCreation(attempt)"));
    }

    @Test public void intermediateDomFailuresStayInsideOneConversationCreationAttempt() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        String advance = between(web, "private void advance()", "private boolean prepareProjectEntryIfNeeded");
        assertTrue(advance.contains("status.endsWith(\"_FAILED\")"));
        assertTrue(advance.contains("status.endsWith(\"_UNAVAILABLE\")"));
        assertTrue(advance.contains("CONVERSATION_CREATE_PENDING"));
        assertTrue(advance.contains("later(this::advance, SelfRun3PowerPolicy.WEB_STEP_RETRY_MS)"));
        assertFalse(advance.contains("status.endsWith(\"_FAILED\") || status.endsWith(\"_UNAVAILABLE\")\n                    || \"PROFILE_ERROR\".equals(status)) {\n                fail(status)"));
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
