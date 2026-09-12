package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/** 3.1 observes the accepted outgoing request but binds the canonical conversation before Drive wait. */
public final class SelfRun3EarlyObservationTest {
    @Test public void browserAdapterStopsOnlyAfterCanonicalConversationIsBound() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("turn_request"));
        assertTrue(web.contains("canonical_post"));
        String protocol = web.substring(web.indexOf("static boolean protocolEvent"), web.indexOf("void prepare("));
        assertFalse(protocol.contains("quiesce()"));
        assertFalse(protocol.contains("listener.onStarted"));
        String capture = web.substring(web.indexOf("private void captureConversation()"),
                web.indexOf("private boolean allowedPreparationRoute"));
        assertTrue(capture.contains("listener.onConversation"));
        assertTrue(capture.contains("listener.onStarted"));
        assertTrue(capture.contains("quiesce();"));
        assertTrue(capture.indexOf("listener.onConversation") < capture.indexOf("listener.onStarted"));
        assertFalse(web.contains("onEnded"));
        assertFalse(web.contains("observedEnd"));
        assertFalse(web.contains("completion_dispatch"));
        assertFalse(web.contains("message_stream_complete"));
    }

    @Test public void coordinatorFreshReadsDriveResultNotBrowserCompletion() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        int current = coordinator.indexOf("SelfRun3Engine.State current = ledger.loadExecution(expectedTask, expectedTurn);");
        int observe = coordinator.indexOf("drive.observeResult(token, current)", current);
        int parse = coordinator.indexOf("SelfRun3Engine.parseResult(observation.candidateBody, current)", observe);
        assertTrue(current >= 0 && observe > current && parse > observe);
        assertFalse(coordinator.contains("drive.resultVersion(token, state)"));
        assertFalse(coordinator.contains("resultVersions"));
        assertTrue(coordinator.contains("DriveStep.READ_RESULT"));
        assertTrue(coordinator.contains("web.detach()"));
        assertTrue(coordinator.contains("releaseWakeLock()"));
        assertFalse(coordinator.contains("scheduleMissedProbe"));
        assertFalse(coordinator.contains("web.receipt"));
    }

    @Test public void engineIgnoresRetiredTransportEndEvents() throws Exception {
        String engine = source("SelfRun3Engine.java");
        assertTrue(engine.contains("case ENDED"));
        assertTrue(engine.contains("return original"));
        assertTrue(engine.contains("dispatchObserved"));
        assertTrue(engine.contains("sendClaimed"));
    }

    private static String source(String name) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}