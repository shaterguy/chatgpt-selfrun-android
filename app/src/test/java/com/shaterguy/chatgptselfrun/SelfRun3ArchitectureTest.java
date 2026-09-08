package com.shaterguy.chatgptselfrun;

import org.junit.Test;
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
    @Test public void contractCarriesCheckpointProfilesAndMergeInputs() {
        assertTrue(SelfRun3Protocol.CONTRACT.contains("full checkpoint"));
        assertTrue(SelfRun3Protocol.CONTRACT.contains("USER_ACTION_RESOLVED"));
        assertTrue(SelfRun3Protocol.CONTRACT.contains("PARALLEL_MERGE"));
        assertTrue(SelfRun3Protocol.CONTRACT.contains("mutation_boundary"));
    }
    @Test public void coordinatorDoesNotDownloadExecutablePatches() throws Exception {
        String c=source("SelfRun3Coordinator.java");
        assertFalse(c.contains("downloadCode")); assertFalse(c.contains("applyPatch"));
    }
    private static String source(String name) throws Exception {
        Path p=Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(p)) p=Path.of("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return Files.readString(p);
    }
}
