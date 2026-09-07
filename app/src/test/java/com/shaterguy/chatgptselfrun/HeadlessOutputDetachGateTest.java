package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source contract for deferring generation-time VirtualDisplay pause until the next composer is ready. */
public final class HeadlessOutputDetachGateTest {
    @Test public void generationDetachWaitsForActiveProtocolAndRealComposer() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        String gated = host.substring(host.indexOf("boolean detachOutputWhenComposerReady()"),
                host.indexOf("boolean attachOutput()"));

        assertTrue(gated.contains("OUTPUT_DETACH_SETTLE_MS"));
        assertTrue(gated.contains("probeDetachReadiness"));
        assertTrue(gated.contains("window.__selfRunTurnProtocol?.snapshot?.()"));
        assertTrue(gated.contains("SelfRun3ComposerTransport.composerReadyExpression()"));
        assertTrue(gated.contains("\"THINKING\".equals(phase)"));
        assertTrue(gated.contains("\"ANSWERING\".equals(phase)"));
        assertTrue(gated.contains("\"COMPLETE\".equals(phase)"));
        assertTrue(gated.contains("composerReady"));
    }

    @Test public void immediateDetachRemainsAvailableForPauseAndFailurePaths() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        String immediate = host.substring(host.indexOf("boolean detachOutput()"),
                host.indexOf("boolean detachOutputWhenComposerReady()"));
        String gatedEntry = host.substring(host.indexOf("boolean detachOutputWhenComposerReady()"),
                host.indexOf("private void probeDetachReadiness"));

        assertTrue(immediate.contains("virtualDisplay.setSurface(null)"));
        assertFalse(gatedEntry.contains("virtualDisplay.setSurface(null)"));
        assertFalse(gatedEntry.contains("getUrl()"));
        assertFalse(gatedEntry.contains("isChatGptPage"));
    }

    @Test public void failedComposerGateKeepsSamePageAttachedWithoutReconnect() throws Exception {
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

    @Test public void webAdapterRoutesActiveWaitsThroughComposerGate() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("state.flag(\"sendClaimed\") && !state.flag(\"ended\")"));
        assertTrue(web.contains("host.detachOutputWhenComposerReady()"));
        assertTrue(web.contains("detachImmediately()"));
        assertTrue(web.contains("observedStart()"));
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
