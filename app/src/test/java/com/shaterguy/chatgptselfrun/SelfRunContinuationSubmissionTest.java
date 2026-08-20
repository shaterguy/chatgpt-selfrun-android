package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SelfRunContinuationSubmissionTest {
    @Test public void continuationSubmissionNeverUsesCommandReceivedRetryState() throws Exception {
        String service = src("SelfRunService.java");
        String store = src("SelfRunStore.java");
        String continuationSubmitted = between(service, "private void continuationSubmitted", "private String commandPrompt");
        String retry = between(store, "void prepareCommandRetry", "void applyDriveSignals");
        assertFalse(service.contains("checkDriveTurnSubmitted"));
        assertFalse(service.contains("checkDriveInitialSubmitted"));
        assertFalse(service.contains("SUBMISSION_CONFIRMATION_GRACE_MS"));
        assertTrue(continuationSubmitted.contains("command_received_ack=unused"));
        assertFalse(continuationSubmitted.contains("markCommandSubmitted"));
        assertFalse(continuationSubmitted.contains("BOOTSTRAP_COMMAND_ACK_RETRY_MS"));
        assertTrue(service.contains("BOOTSTRAP_COMMAND_ACK_RETRY_MS = 5 * 60_000L"));
        assertTrue(service.contains("store.prepareCommandRetry()"));
        assertTrue(store.contains("boolean hasSubmissionRetry() { return RETRY_BOOTSTRAP.equals"));
        assertTrue(retry.contains("RETRY_BOOTSTRAP.equals(submissionRetryKind())"));
        assertTrue(retry.contains("PHASE_BOOTSTRAP_SEND"));
        assertFalse(retry.contains("PHASE_SEND_CONTINUE"));
        assertTrue(store.contains("migrateLegacyContinuationAckWait"));
    }

    @Test public void verifiedContinuationUsesNewUserMessageOnlyAsPostClickProof() throws Exception {
        String dom = src("SelfRunContinuationDom.java");
        String prepare = between(dom, "static String prepareDriveTurn", "static String clickPreparedDriveTurn");
        String click = between(dom, "static String clickPreparedDriveTurn", "static String verifyDriveTurnSubmission");
        String verify = between(dom, "static String verifyDriveTurnSubmission", "private static String conversationGuard");
        assertFalse(prepare.contains("SUBMISSION_CONFIRMED"));
        assertFalse(click.contains("SUBMISSION_CONFIRMED"));
        assertTrue(click.contains("baselineUserCount=userMessageCount()"));
        assertTrue(verify.contains("users>baseline&&isEmpty"));
        assertTrue(verify.contains("proof:'USER_MESSAGE'"));
    }

    private static String src(String f) throws Exception { Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f); if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f); return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8); }
    private static String between(String s,String a,String b){ return s.substring(s.indexOf(a),s.indexOf(b)); }
}
