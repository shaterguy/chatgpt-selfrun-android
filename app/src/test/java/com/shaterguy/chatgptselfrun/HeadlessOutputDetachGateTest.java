package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression contract: an active SelfRun page stays attached to its VirtualDisplay for the whole turn. */
public final class HeadlessOutputDetachGateTest {
    @Test public void runtimeSurfaceDetachIsDisabled() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        String runtime = host.substring(host.indexOf("boolean detachOutput()"),
                host.indexOf("boolean attachOutput()"));

        assertTrue(runtime.contains("boolean detachOutput()"));
        assertTrue(runtime.contains("boolean detachOutputWhenComposerReady()"));
        assertFalse(runtime.contains("virtualDisplay.setSurface(null)"));
        assertFalse(runtime.contains("probeDetachReadiness"));
        assertFalse(runtime.contains("scheduleNextDetachProbe"));
        assertFalse(runtime.contains("OUTPUT_DETACH_"));
    }

    @Test public void waitAndInspectionCannotSuspendTheHeadlessPage() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        String host = source("HeadlessWebViewHost.java");
        String runtime = host.substring(host.indexOf("boolean detachOutput()"),
                host.indexOf("boolean attachOutput()"));

        // Existing callers may still request a detach, but the host contract makes every runtime request a no-op.
        assertTrue(web.contains("host.detachOutput()") || web.contains("host.detachOutputWhenComposerReady()"));
        assertTrue(runtime.contains("return false"));
        assertFalse(runtime.contains("setSurface(null)"));
    }

    @Test public void destroyStillReleasesVirtualDisplayResources() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        String destroy = host.substring(host.indexOf("void destroy()"));

        assertTrue(destroy.contains("virtualDisplay.setSurface(null)"));
        assertTrue(destroy.contains("virtualDisplay.release()"));
        assertTrue(destroy.contains("surface.release()"));
        assertTrue(destroy.contains("imageReader.close()"));
        assertTrue(destroy.contains("drainThread.quitSafely()"));
    }

    @Test public void rasterAndDrainPowerOptimizationsRemainWithoutLifecycleSuspension() throws Exception {
        String host = source("HeadlessWebViewHost.java");

        assertTrue(host.contains("HeadlessWebViewPowerPolicy.capRasterDensity"));
        assertTrue(host.contains("ImageReader.newInstance"));
        assertTrue(host.contains("setOnImageAvailableListener"));
        assertTrue(host.contains("drainLatestImage"));
    }

    @Test public void completionCanKeepUsingTheAlreadyAttachedOutput() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        String end = web.substring(web.indexOf("private void observedEnd"),
                web.indexOf("void prepare("));
        String attach = web.substring(web.indexOf("void attachForCompletionTransition()"),
                web.indexOf("private void detachImmediately()"));

        assertTrue(end.contains("attachForCompletionTransition()"));
        assertTrue(attach.contains("host.attachOutput()"));
        assertFalse(attach.contains("host.detachOutput"));
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

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
