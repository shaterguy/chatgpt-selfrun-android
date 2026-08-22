package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Recovery-tag grammar remains parser-compatible but is not part of the live completion path. */
public final class TurnCompletionWatchdogRecoveryIdPolicyTest {
    private static final String RUN = "SR-RECOVERY-TEST";

    @Test public void normalContinuationGrammarIsUnchanged() {
        String normal = SelfRunProtocol.driveContinuation(RUN);
        assertTrue(normal.contains("[SELF_RUN_CONTINUE " + RUN + "]"));
        assertFalse(normal.contains("RECOVERY_ID="));
    }

    @Test public void recoveryTaggedCompletionsRemainQuarantinedFromNormalApply() throws Exception {
        String older = "[2026.08.21 | 12:00:00] [SELF_RUN_TURN_COMPLETED " + RUN + " RECOVERY_ID=wd.4]";
        DriveSignalParser.Scan scan = DriveSignalParser.scan(older, RUN, 0, SelfRunStore.MODE_CHAT);
        assertEquals(1, scan.unseen.size());
        assertTrue(DriveSignalParser.hasRecoveryIdField(scan.unseen.get(0).raw));
        assertNull(DriveSignalParser.latestCompletion(scan.unseen));

        String service = source("SelfRunService.java");
        String gate = section(service, "private static java.util.List<DriveSignalParser.Event> normalDriveEvents", "private void pollDriveNow");
        assertTrue(gate.contains("DriveSignalParser.hasRecoveryIdField(event.raw)"));
        assertTrue(gate.contains("continue"));
    }

    @Test public void liveCompletionPathDoesNotCreateRecoveryContinuationsOrClaims() throws Exception {
        String service = source("SelfRunService.java");
        assertFalse(service.contains("driveRecoveryContinuation"));
        assertFalse(service.contains("createNamedRangeClaim"));
        assertFalse(service.contains("watchdogClaimSubmitted"));
        assertTrue(service.contains("observeTurnCompletion"));
        assertTrue(service.contains("POST_DOM_DRIVE_SYNC"));
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
