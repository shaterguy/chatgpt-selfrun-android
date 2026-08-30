package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SelfRunContinuationSubmissionTest {
    @Test public void continuationSubmissionNeverUsesCommandReceivedRetryState() throws Exception {
        String service = src("SelfRunService.java");
        String store = src("SelfRunStore.java");
        String continuationSubmitted = between(service, "private void continuationSubmitted", "private String commandPrompt");
        assertFalse(service.contains("checkDriveTurnSubmitted"));
        assertFalse(service.contains("checkDriveInitialSubmitted"));
        assertFalse(service.contains("SUBMISSION_CONFIRMATION_GRACE_MS"));
        assertFalse(continuationSubmitted.contains("command_received_ack"));
        assertFalse(continuationSubmitted.contains("markCommandSubmitted"));
        assertFalse(continuationSubmitted.contains("BOOTSTRAP_COMMAND_ACK_RETRY_MS"));
        assertFalse(service.contains("BOOTSTRAP_COMMAND_ACK_RETRY_MS"));
        assertFalse(service.contains("store.prepareCommandRetry()"));
        assertFalse(store.contains("void markCommandSubmitted"));
        assertTrue(store.contains("void bootstrapSubmissionConfirmed"));
        assertTrue(store.contains("migrateLegacyContinuationAckWait"));
        assertTrue(store.contains("migrateLegacyBootstrapAckWait"));
    }

    @Test public void runningStopCanStageContinuationAndWaitForSend() {
        String script = SelfRunContinuationDom.prepareDriveTurn(
                "https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc",
                "[SELF_RUN_CONTINUE SR-TEST]", "SR-TEST:continue:1:1");
        assertTrue(script.contains("c0.state!=='STOP'"));
        assertTrue(script.contains("waiting for enabled SEND after exact readback"));
        assertTrue(script.contains("prepared continuation waiting for SEND"));
    }

    @Test public void verifiedContinuationRequiresPositivePostClickEvidence() throws Exception {
        String dom = src("SelfRunContinuationDom.java");
        String prepare = between(dom, "static String prepareDriveTurn", "static String clickPreparedDriveTurn");
        String click = between(dom, "static String clickPreparedDriveTurn", "private static String probeLockedDriveTurn");
        String verification = between(dom, "private static String continuationClickedVerification", "private static String runIdFromContinuationMarker");
        String observer = between(dom, "static String observeTurnCompletion", "static String cancelTurnCompletionObserver");
        String completion = between(dom, "private static String completionObserver", "private static String conversationGuard");
        assertFalse(prepare.contains("users>baseline"));
        assertFalse(click.contains("SUBMISSION_CONFIRMED"));
        assertTrue(click.contains("baselineUserCount=userMessageCount()"));
        assertTrue(click.contains("armCompletionObserver(false)"));
        assertTrue(click.indexOf("c.send.click()") < click.indexOf("requestComposerSubmit()"));
        assertTrue(verification.contains("users>baseline"));
        assertTrue(verification.contains("STOP"));
        assertTrue(verification.contains("state:'confirmed'"));
        assertTrue(observer.contains("completionObserver(runId, observerToken, stabilityMs, staleStopMs)"));
        assertTrue(completion.contains("return completionObserver(runId, observerToken, stabilityMs, STALE_STOP_RESYNC_MS)"));
        assertTrue(completion.contains("new MutationObserver"));
        assertTrue(completion.contains("state.observer?.disconnect()"));
    }

    private static String src(String f) throws Exception { Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f); if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f); return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8); }
    private static String between(String s,String a,String b){ return s.substring(s.indexOf(a),s.indexOf(b)); }
}
