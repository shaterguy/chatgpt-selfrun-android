package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class TurnCompletionRecoveryWiringTest {
    @Test public void watchdogUsesWrapperAndLeavesMainServiceStateMachineUntouched() throws Exception {
        String wrapper = source("WorkProtocolObservingWebViewClient.java");
        String coordinator = source("TurnCompletionRecoveryCoordinator.java");
        String service = source("SelfRunService.java");
        assertTrue(wrapper.contains("TurnCompletionRecoveryCoordinator.handleNavigation"));
        assertTrue(coordinator.contains("SelfRunContinuationDom.observeTurnCompletion"));
        assertTrue(coordinator.contains("HeadlessWebViewHost.attachOutputFor"));
        assertTrue(coordinator.contains("client.getPollMetadata"));
        assertTrue(coordinator.contains("client.readDocumentSnapshot"));
        assertTrue(coordinator.contains("current.beginPostDomDriveSync(token)"));
        assertTrue(coordinator.contains("beginOrResume(store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT)"));
        assertFalse(service.contains(TurnCompletionRecoveryPolicy.DRIVE_PROBE_HOST));
        assertFalse(service.contains(TurnCompletionRecoveryPolicy.RECOVER_HOST));
    }

    @Test public void driveProbeIsOneShotAndReadOnlyBeforeNormalDriveSync() throws Exception {
        String coordinator = source("TurnCompletionRecoveryCoordinator.java");
        assertTrue(coordinator.contains("activeProbeKey"));
        assertTrue(coordinator.contains("driveSignalCursorSchemaVersion"));
        assertFalse(coordinator.contains("updateDriveSeen("));
        assertFalse(coordinator.contains("applyDriveSignals("));
        assertFalse(coordinator.contains("schedulePostDomDriveSync"));
        assertTrue(coordinator.contains("beginPostDomDriveSync(token)"));
    }

    @Test public void headlessOutputHasScopedAttachAndDetachHooks() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        assertTrue(host.contains("activeHost"));
        assertTrue(host.contains("static boolean attachOutputFor(WebView view)"));
        assertTrue(host.contains("static boolean detachOutputFor(WebView view)"));
        assertTrue(host.contains("host.webView != view"));
    }

    @Test public void domWatchdogIsBoundedAndDurableAcrossDocumentReloads() throws Exception {
        String fallback = source("TurnCompletionDomFallbackScript.java");
        assertTrue(fallback.contains("REBIND_MS = 30_000L"));
        assertTrue(fallback.contains("DRIVE_PROBE_MS = 60_000L"));
        assertTrue(fallback.contains("RECOVERY_MS = 120_000L"));
        assertTrue(fallback.contains("dom-fallback-baseline:v2"));
        assertTrue(fallback.contains("dom-fallback-watch:v2"));
        assertTrue(fallback.contains("driveProbeResult"));
        assertFalse(fallback.contains("setInterval("));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
