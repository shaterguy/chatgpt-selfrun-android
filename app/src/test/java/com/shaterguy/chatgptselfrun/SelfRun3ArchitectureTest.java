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
    @Test public void promptIsEnvelopeWhileEngineOwnsOnlyMachineIntegrityAndSafeRouting() throws Exception {
        String protocol=source("SelfRun3Protocol.java");
        String engine=source("SelfRun3Engine.java");
        assertTrue(protocol.contains("Dynamic SelfRun 3 envelope"));
        assertTrue(protocol.contains("compactProfileChoices"));
        assertFalse(protocol.matches("(?s).*static\\s+final\\s+String\\s+CONTRACT\\s*=.*"));
        assertFalse(protocol.contains("RESULT_IDENTITY_TEMPLATE"));
        assertFalse(protocol.contains("ProfileRegistry.export"));
        assertFalse(protocol.contains("full checkpoint"));
        assertFalse(engine.contains("full handoff missing"));
        assertFalse(engine.contains("verification required for DONE"));
        assertFalse(engine.contains("branch identity mismatch"));
        assertTrue(engine.contains("routingPhase"));
        assertTrue(engine.contains("usableParallelPlan"));
        assertTrue(engine.contains("case REPAIR"));
        assertTrue(engine.contains("maybeMerge"));
        assertTrue(engine.contains("RESULT_SCHEMA"));
    }
    @Test public void coordinatorDoesNotDownloadExecutablePatches() throws Exception {
        String c=source("SelfRun3Coordinator.java");
        assertFalse(c.contains("downloadCode")); assertFalse(c.contains("applyPatch"));
    }
    @Test public void interventionAndPauseAlertsAreEventDriven() throws Exception {
        String c=source("SelfRun3Coordinator.java");
        String commit=section(c,"private void commitTurn","private void scheduleNetworkRetry");
        String pause=section(c,"private void pause(String reason)","private void resume()");
        String hardPause=section(c,"private void hardPause","private void notifyUserActionRequired");
        String helper=source("NotificationHelper.java");
        assertTrue(helper.contains("ALERT_CHANNEL = \"selfrun-drive-alerts-v2\""));
        assertTrue(helper.contains("NotificationManager.IMPORTANCE_HIGH"));
        assertTrue(commit.contains("Stage.WAITING_USER_INTERVENTION"));
        assertTrue(commit.contains("notifyUserActionRequired();"));
        assertTrue(commit.contains("Stage.PAUSED"));
        assertTrue(commit.contains("notifyPaused();"));
        assertTrue(pause.contains("notifyPaused();"));
        assertTrue(hardPause.contains("notifyPaused();"));
        assertTrue(c.contains("NotificationHelper.notifyUser(service, \"사용자 조치 필요\""));
        assertTrue(c.contains("NotificationHelper.notifyUser(service, \"일시정지\""));
    }
    private static String section(String text,String start,String end) {
        int from=text.indexOf(start), to=text.indexOf(end,from+start.length());
        assertTrue("missing section start: "+start,from>=0);
        assertTrue("missing section end: "+end,to>from);
        return text.substring(from,to);
    }
    private static String source(String name) throws Exception {
        Path p=Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(p)) p=Path.of("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);
    }
}
