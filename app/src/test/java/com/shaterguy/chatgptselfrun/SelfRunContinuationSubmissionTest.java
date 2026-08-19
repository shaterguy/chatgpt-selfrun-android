package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SelfRunContinuationSubmissionTest {
    @Test public void clickImmediatelyMovesToDriveAckWait() throws Exception {
        String service = src("SelfRunService.java");
        String compact = service.replaceAll("\\s+", "");
        assertFalse(service.contains("checkDriveTurnSubmitted"));
        assertFalse(service.contains("checkDriveInitialSubmitted"));
        assertFalse(service.contains("SUBMISSION_CONFIRMATION_GRACE_MS"));
        assertTrue(compact.contains("store.markCommandSubmitted(kind,due)"));
        assertTrue(compact.contains("scheduleDrivePoll(0L)"));
        assertTrue(service.contains("SUBMISSION_RETRY_MS = 5 * 60_000L"));
        assertTrue(compact.contains("store.prepareCommandRetry()"));
    }

    @Test public void driveDomNeverConfirmsByUserMessage() throws Exception {
        String dom = src("SelfRunDom.java");
        String prepare = between(dom, "static String prepareDriveTurn", "static String clickPreparedDriveTurn");
        String click = between(dom, "static String clickPreparedDriveTurn", "private static String projectGuard");
        assertFalse(prepare.contains("data-message-author-role=\\\"user\\\""));
        assertFalse(click.contains("data-message-author-role=\\\"user\\\""));
        assertFalse(prepare.contains("CONFIRMED"));
        assertFalse(click.contains("CONFIRMED"));
    }

    private static String src(String f) throws Exception { Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f); if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f); return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8); }
    private static String between(String s,String a,String b){ return s.substring(s.indexOf(a),s.indexOf(b)); }
}
