package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/** 3.1 observes only the accepted outgoing request; Drive owns all later completion state. */
public final class SelfRun3EarlyObservationTest {
    @Test public void browserAdapterStopsAtCanonicalDispatch() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("turn_request"));
        assertTrue(web.contains("canonical_post"));
        assertTrue(web.contains("a.quiesce()"));
        assertTrue(web.contains("listener.onStarted"));
        assertFalse(web.contains("onEnded"));
        assertFalse(web.contains("observedEnd"));
        assertFalse(web.contains("completion_dispatch"));
        assertFalse(web.contains("message_stream_complete"));
    }

    @Test public void coordinatorWaitsOnDriveNotBrowserCompletion() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("drive.resultVersion"));
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
