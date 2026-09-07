package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class SelfRun3ArchitectureTest {
    @Test public void foregroundServiceIsOnlyV3Shell() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("SelfRun3Coordinator"));
        assertTrue(service.contains("V3_STALE_RUN_RETIRED"));
        assertFalse(service.contains("V3_LEGACY_RUN_PRESERVED"));
        assertFalse(service.contains("DriveSignalParser.scan"));
        assertFalse(service.contains("SelfRunRolloverCoordinator"));
        assertFalse(service.contains("rolloverConversation"));
        assertFalse(service.contains("PHASE_POST_PROTOCOL_DRIVE_SYNC"));
        assertTrue(service.length() < 12_000);
    }

    @Test public void normalGenerationHasNoPollingAndDetachesRasterOutput() throws Exception {
        assertEquals(0L, SelfRun3PowerPolicy.NORMAL_WAIT_POLL_MS);
        String coordinator = source("SelfRun3Coordinator.java");
        String host = source("HeadlessWebViewHost.java");
        assertTrue(coordinator.contains("SelfRun3PowerPolicy.MISSED_CALLBACK_PROBE_MS"));
        assertTrue(coordinator.contains("web.detach()"));
        assertTrue(coordinator.contains("releaseWakeLock()"));
        assertTrue(host.contains("virtualDisplay.setSurface(null)"));
    }

    @Test public void chatBootstrapAndContinuationProfilesRemainDistinct() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("RequestProfileScript.setChatProfiles"));
        assertTrue(web.contains("c.optString(\"chatBootstrap\"), c.optString(\"chatContinuation\")"));
        assertTrue(web.contains("RequestProfileScript.setChatReasoning(c.optString(\"chatContinuation\"))"));
    }

    @Test public void firstSendDiagnosticTraceCoversPrepareSubmitAndFailureWithoutPayloads() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        for (String stage : new String[]{"PREPARE_START", "ON_PREPARED", "SUBMIT_START", "SUBMIT_EVAL", "ON_UNSENT", "FAILURE", "PROTOCOL_ACCEPT"}) {
            assertTrue("missing diagnostic stage " + stage, web.contains("trace(\"" + stage + "\""));
        }
        assertTrue(web.contains("tracePrepare(result)"));
        String prepareTrace = web.substring(web.indexOf("private void tracePrepare("), web.indexOf("private void trace(String stage"));
        assertTrue(prepareTrace.contains("stage=PREPARE_EVAL;status="));
        assertTrue(prepareTrace.contains("V3_WEB_TRACE"));
        assertTrue(prepareTrace.contains("key.equals(lastPrepareTrace)"));
        String trace = web.substring(web.indexOf("private void trace(String stage"), web.indexOf("private void captureConversation()"));
        assertTrue(trace.contains("V3_WEB_TRACE"));
        for (String logged : new String[]{prepareTrace, trace}) {
            assertFalse(logged.contains("text(\"prompt\")"));
            assertFalse(logged.contains("conversationUrl"));
            assertFalse(logged.contains("web.getUrl"));
            assertFalse(logged.contains("result.toString()"));
        }
    }

    @Test public void promptDefersFixedSemanticsToCanonicalSkillAndHasNoSelfPatchPath() throws Exception {
        String protocol = source("SelfRun3Protocol.java");
        assertTrue(protocol.contains("SELF_RUN_SKILL_DOCUMENT_ID"));
        assertFalse(protocol.contains("static final String CONTRACT ="));
        assertFalse(protocol.contains("RESULT_IDENTITY_TEMPLATE"));
        assertFalse(source("SelfRun3Coordinator.java").contains("downloadCode"));
        assertFalse(source("SelfRun3Coordinator.java").contains("applyPatch"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
