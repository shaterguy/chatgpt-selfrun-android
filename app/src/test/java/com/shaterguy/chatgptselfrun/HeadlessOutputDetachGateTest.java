package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source contract for low-power generation detach with a pinned continuation composer. */
public final class HeadlessOutputDetachGateTest {
    @Test public void generationDetachPinsActiveComposerBeforeSurfaceRemoval() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        String transport = source("SelfRun3ComposerTransport.java");
        String gated = host.substring(host.indexOf("boolean detachOutputWhenComposerReady()"),
                host.indexOf("boolean attachOutput()"));

        assertTrue(gated.contains("OUTPUT_DETACH_SETTLE_MS"));
        assertTrue(gated.contains("probeDetachReadiness"));
        assertTrue(gated.contains("window.__selfRunTurnProtocol?.snapshot?.()"));
        assertTrue(gated.contains("SelfRun3ComposerTransport.pinComposerReadyExpression()"));
        assertTrue(gated.contains("virtualDisplay.setSurface(null)") || host.contains("virtualDisplay.setSurface(null)"));
        assertTrue(gated.contains("\"THINKING\".equals(phase)"));
        assertTrue(gated.contains("\"ANSWERING\".equals(phase)"));
        assertTrue(gated.contains("\"COMPLETE\".equals(phase)"));
        assertTrue(transport.contains("window.__selfRunV3PinnedComposer"));
        assertTrue(transport.contains("const composer=resolveComposer();"));
        assertTrue(transport.contains("state.path!==location.pathname"));
        assertTrue(transport.contains("e.ownerDocument===document"));
        assertTrue(transport.contains("e.isConnected"));
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

    @Test public void slowComposerStillDetachesEventuallyWithoutReconnect() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        String gate = host.substring(host.indexOf("private void probeDetachReadiness"),
                host.indexOf("boolean attachOutput()"));

        assertTrue(gate.contains("scheduleNextDetachProbe"));
        assertTrue(gate.contains("OUTPUT_DETACH_FAST_WINDOW_MS"));
        assertTrue(gate.contains("OUTPUT_DETACH_SLOW_PROBE_MS"));
        assertFalse(gate.contains("OUTPUT_DETACH_MAX_WAIT_MS"));
        assertFalse(gate.contains("finishDetachProbeKeepingOutput"));
        assertFalse(gate.contains("loadUrl("));
        assertFalse(gate.contains("reload("));
        assertFalse(gate.contains("destroy("));
        assertFalse(gate.contains("new WebView"));
    }

    @Test public void pinnedComposerFallsBackToFreshDiscoveryWhenStale() throws Exception {
        String transport = source("SelfRun3ComposerTransport.java");
        assertTrue(transport.contains("const pinned=pinnedComposer();if(pinned)return pinned;"));
        assertTrue(transport.contains("const discovered=findComposer();"));
        assertTrue(transport.contains("if(discovered)pinComposer(discovered);"));
        assertTrue(transport.contains("clearPinnedComposer()"));
        assertFalse(transport.contains("setInterval("));
        assertFalse(transport.contains("setTimeout("));
    }

    @Test public void webAdapterRoutesActiveWaitsThroughComposerGate() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("state.flag(\"sendClaimed\") && !state.flag(\"ended\")"));
        assertTrue(web.contains("host.detachOutputWhenComposerReady()"));
        assertTrue(web.contains("detachImmediately()"));
        assertTrue(web.contains("observedStart()"));
    }

    @Test public void completionReattachesOutputBeforeNextTurnTransition() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        String end = web.substring(web.indexOf("private void observedEnd"),
                web.indexOf("void prepare("));
        String attach = web.substring(web.indexOf("void attachForCompletionTransition()"),
                web.indexOf("private void detachImmediately()"));
        String inspect = web.substring(web.indexOf("void inspect("),
                web.indexOf("static String inspectionScript"));

        assertTrue(end.contains("attachForCompletionTransition()"));
        assertFalse(end.contains("detachAfterComposerReady()"));
        assertTrue(attach.contains("host.attachOutput()"));
        assertTrue(inspect.contains("result.optBoolean(\"complete\") || result.optBoolean(\"receipt\")"));
        assertTrue(inspect.contains("attachForCompletionTransition()"));
    }

    @Test public void preparationRetryGetsFreshTimeoutBudgetWithoutResettingTurnIdentity() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        String prepare = web.substring(web.indexOf("void prepare("),
                web.indexOf("private void ensureWeb"));

        assertTrue(prepare.contains("boolean restartPreparation = newAttempt || !preparing"));
        assertTrue(prepare.contains("else if (restartPreparation)"));
        assertTrue(prepare.contains("prepareStarted = SystemClock.elapsedRealtime()"));
        assertFalse(prepare.contains("state = null"));
    }

    @Test public void gateUsesFastStartupThenSparseLongWaitProbe() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        assertTrue(host.contains("OUTPUT_DETACH_SETTLE_MS = 1_000L"));
        assertTrue(host.contains("OUTPUT_DETACH_FAST_PROBE_MS = 250L"));
        assertTrue(host.contains("OUTPUT_DETACH_FAST_WINDOW_MS = 5_000L"));
        assertTrue(host.contains("OUTPUT_DETACH_SLOW_PROBE_MS = 2_000L"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
