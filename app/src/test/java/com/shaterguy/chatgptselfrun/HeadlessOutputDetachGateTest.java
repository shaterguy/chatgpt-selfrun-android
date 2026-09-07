package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source contract for deferring VirtualDisplay pause until the next-turn composer is available. */
public final class HeadlessOutputDetachGateTest {
    @Test public void chatGptOutputDetachWaitsForActiveProtocolAndRealComposer() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        String detach = host.substring(host.indexOf("boolean detachOutput()"),
                host.indexOf("boolean attachOutput()"));

        assertTrue(detach.contains("OUTPUT_DETACH_SETTLE_MS"));
        assertTrue(detach.contains("probeDetachReadiness"));
        assertTrue(detach.contains("window.__selfRunTurnProtocol?.snapshot?.()"));
        assertTrue(detach.contains("SelfRun3ComposerTransport.composerReadyExpression()"));
        assertTrue(detach.contains("\"THINKING\".equals(phase)"));
        assertTrue(detach.contains("\"ANSWERING\".equals(phase)"));
        assertTrue(detach.contains("composerReady"));
        assertTrue(detach.contains("detachOutputNow()"));
    }

    @Test public void liveChatGptDetachDoesNotPauseSurfaceBeforeGatePasses() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        String publicDetach = host.substring(host.indexOf("boolean detachOutput()"),
                host.indexOf("private void probeDetachReadiness"));
        String immediateDetach = host.substring(host.indexOf("private boolean detachOutputNow()"),
                host.indexOf("private static boolean isChatGptPage"));

        assertFalse(publicDetach.contains("virtualDisplay.setSurface(null)"));
        assertTrue(immediateDetach.contains("virtualDisplay.setSurface(null)"));
        assertTrue(publicDetach.contains("isChatGptPage(webView.getUrl())"));
    }

    @Test public void failedEarlyComposerGateKeepsSamePageAttachedWithoutReconnect() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        String gate = host.substring(host.indexOf("private void probeDetachReadiness"),
                host.indexOf("boolean attachOutput()"));

        assertTrue(gate.contains("OUTPUT_DETACH_MAX_WAIT_MS"));
        assertTrue(gate.contains("finishDetachProbeKeepingOutput()"));
        assertFalse(gate.contains("loadUrl("));
        assertFalse(gate.contains("reload("));
        assertFalse(gate.contains("destroy("));
        assertFalse(gate.contains("new WebView"));
    }

    @Test public void gateUsesShortSettlingWindowThenLowCostBoundedProbe() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        assertTrue(host.contains("OUTPUT_DETACH_SETTLE_MS = 1_000L"));
        assertTrue(host.contains("OUTPUT_DETACH_PROBE_MS = 250L"));
        assertTrue(host.contains("OUTPUT_DETACH_MAX_WAIT_MS = 5_000L"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
