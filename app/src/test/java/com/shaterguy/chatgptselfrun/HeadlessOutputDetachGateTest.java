package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source contract for pinning the continuation composer before generation-time output detach. */
public final class HeadlessOutputDetachGateTest {
    @Test public void generationDetachPinsActiveProtocolComposerBeforeSurfaceRemoval() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        String gated = host.substring(host.indexOf("boolean detachOutputWhenComposerReady()"),
                host.indexOf("boolean attachOutput()"));

        assertTrue(gated.contains("OUTPUT_DETACH_SETTLE_MS"));
        assertTrue(gated.contains("probeDetachReadiness"));
        assertTrue(gated.contains("window.__selfRunTurnProtocol?.snapshot?.()"));
        assertTrue(gated.contains("SelfRun3ComposerTransport.pinComposerExpression()"));
        assertTrue(gated.contains("\"THINKING\".equals(phase)"));
        assertTrue(gated.contains("\"ANSWERING\".equals(phase)"));
        assertTrue(gated.contains("\"COMPLETE\".equals(phase)"));
        assertTrue(gated.contains("composerPinned"));
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

    @Test public void webAdapterRoutesActiveWaitsThroughComposerGate() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("state.flag(\"sendClaimed\") && !state.flag(\"ended\")"));
        assertTrue(web.contains("host.detachOutputWhenComposerReady()"));
        assertTrue(web.contains("detachImmediately()"));
        assertTrue(web.contains("observedStart()"));
    }

    @Test public void completionKeepsDetachedPinnedOutputAndContinuationUsesOneShotRecoveryOnly() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        String end = web.substring(web.indexOf("private void observedEnd"),
                web.indexOf("void prepare("));
        String prepare = web.substring(web.indexOf("void prepare("),
                web.indexOf("private void ensureWeb"));
        String advance = web.substring(web.indexOf("private void advance()"),
                web.indexOf("void submit("));
        String inspect = web.substring(web.indexOf("void inspect("),
                web.indexOf("static String inspectionScript"));

        assertTrue(end.contains("traceCompletionOutputState()"));
        assertFalse(end.contains("attachForCompletionTransition()"));
        assertTrue(prepare.contains("if (host != null && initial) host.attachOutput()"));
        assertFalse(prepare.contains("if (host != null) host.attachOutput()"));
        assertTrue(advance.contains("continuationRecoveryAttached"));
        assertTrue(advance.contains("COMPOSER_RECOVERY_OUTPUT"));
        assertTrue(advance.contains("!host.isOutputAttached()"));
        assertTrue(inspect.contains("attachForCompletionTransition()"));
    }

    @Test public void diagnosticsExposePinnedComposerWhenSelectorsDisappear() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("window.__selfRunV3PinnedComposer&&window.__selfRunV3PinnedComposer.isConnected"));
        assertTrue(web.contains(";pinned="));
        assertTrue(web.contains("KEPT_DETACHED"));
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
