package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class SelfRun3ArchitectureTest {
    @Test public void foregroundServiceRemainsLedgerCoordinatorShell() throws Exception {
        String service=source("SelfRunService.java");
        assertTrue(service.contains("SelfRun3Coordinator"));
        assertFalse(service.contains("DriveSignalParser.scan"));
        assertFalse(service.contains("rolloverConversation"));
    }
    @Test public void resultWaitHasNoAssistantOrComposerObservation() throws Exception {
        String c=source("SelfRun3Coordinator.java");
        assertFalse(c.contains("probeReceipt("));
        assertFalse(c.contains("runMissedProbe("));
        assertFalse(c.contains("web.inspect("));
        assertTrue(c.contains("web.detach()"));
        assertTrue(c.contains("releaseWakeLock()"));
        assertTrue(source("HeadlessWebViewHost.java").contains("virtualDisplay.setSurface(null)"));
    }
    @Test public void promptIsEnvelopeWhileEngineRetainsExecutionContract() throws Exception {
        String protocol=source("SelfRun3Protocol.java");
        String engine=source("SelfRun3Engine.java");
        assertTrue(protocol.contains("Dynamic SelfRun 3 envelope"));
        assertTrue(protocol.contains("compactProfileChoices"));
        assertFalse(protocol.matches("(?s).*static\\s+final\\s+String\\s+CONTRACT\\s*=.*"));
        assertFalse(protocol.contains("RESULT_IDENTITY_TEMPLATE"));
        assertFalse(protocol.contains("ProfileRegistry.export"));
        assertFalse(protocol.contains("full checkpoint"));
        assertTrue(engine.contains("full handoff missing"));
        assertTrue(engine.contains("case REPAIR"));
        assertTrue(engine.contains("maybeMerge"));
        assertTrue(engine.contains("RESULT_SCHEMA"));
    }
    @Test public void coordinatorDoesNotDownloadExecutablePatches() throws Exception {
        String c=source("SelfRun3Coordinator.java");
        assertFalse(c.contains("downloadCode")); assertFalse(c.contains("applyPatch"));
    }
    private static String source(String name) throws Exception {
        Path p=Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(p)) p=Path.of("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);
    }
}
