package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Dev12 regression contract: dev9 runtime plus observer-token-correlated generation guard only. */
public final class TurnProtocolGenerationGuardPolicyTest {
    @Test public void tokenCorrelatedGenerationBlocksNoStartRolloverWithoutFakingStop() {
        long started = 10_000L;
        long deadline = started + SelfRunRolloverPolicy.CONTINUATION_NO_START_MAX_WAIT_MS;
        assertEquals(SelfRunRolloverPolicy.NO_START_WAIT,
                SelfRunRolloverPolicy.postDispatchNoStartAction(
                        started, false, started, deadline + 120_000L, false, true));
        assertEquals(SelfRunRolloverPolicy.NO_START_ROLLOVER,
                SelfRunRolloverPolicy.postDispatchNoStartAction(
                        started, false, started, deadline, false, false));
        assertEquals(SelfRunRolloverPolicy.NO_START_WAIT,
                SelfRunRolloverPolicy.postDispatchNoStartAction(
                        started, true, started, deadline, false, false));
        assertEquals(SelfRunRolloverPolicy.NO_START_WAIT,
                SelfRunRolloverPolicy.postDispatchNoStartAction(
                        started, false, started, deadline, true, false));
    }

    @Test public void activeGenerationRequiresExactObserverTokenAndActivePhase() {
        String token = "observer-current";
        TurnProtocolUiState.Snapshot thinking = new TurnProtocolUiState.Snapshot(
                true, "turn_request", "THINKING", TurnProtocolUiState.DETECTOR_PROTOCOL_PRIMARY,
                token, token, 1L);
        TurnProtocolUiState.Snapshot answering = new TurnProtocolUiState.Snapshot(
                true, "answering_started", "ANSWERING", TurnProtocolUiState.DETECTOR_PROTOCOL_PRIMARY,
                token, token, 1L);
        TurnProtocolUiState.Snapshot mismatch = new TurnProtocolUiState.Snapshot(
                true, "turn_request", "THINKING", TurnProtocolUiState.DETECTOR_PROTOCOL_PRIMARY,
                token, "observer-stale", 1L);
        TurnProtocolUiState.Snapshot idle = new TurnProtocolUiState.Snapshot(
                true, "", "IDLE", TurnProtocolUiState.DETECTOR_PROTOCOL_PRIMARY,
                token, token, 1L);

        assertTrue(thinking.activeGenerationFor(token));
        assertTrue(answering.activeGenerationFor(token));
        assertFalse(thinking.activeGenerationFor("observer-stale"));
        assertFalse(mismatch.activeGenerationFor(token));
        assertFalse(idle.activeGenerationFor(token));
    }

    @Test public void protocolAndDomScriptsCarryOnlyCurrentObserverCorrelationGuard() {
        String protocol = ChatGptTurnProtocolScript.documentStartScript();
        String fallback = TurnCompletionDomFallbackScript.documentStartScript(10L);
        assertTrue(protocol.contains("observerToken:currentObserverToken()"));
        assertTrue(fallback.contains("stage:'observer_bound'"));
        assertTrue(fallback.contains("protocolActiveForToken"));
        assertTrue(fallback.contains("protocol?.phase==='THINKING'||protocol?.phase==='ANSWERING'"));
        assertFalse(fallback.contains("REBIND_MS"));
        assertFalse(fallback.contains("DRIVE_PROBE_MS"));
        assertFalse(fallback.contains("RECOVERY_MS"));
        assertFalse(fallback.contains("turn-watchdog-rebind"));
        assertFalse(fallback.contains("turn-watchdog-probe"));
        assertFalse(fallback.contains("turn-watchdog-recover"));
        assertFalse(fallback.contains("setInterval("));
    }

    @Test public void bridgeMaintainsCorrelationWithoutReusingStopState() throws Exception {
        String bridge = source("TurnProtocolLogBridge.java");
        String ui = source("TurnProtocolUiState.java");
        String store = source("SelfRunStore.java");
        assertTrue(bridge.contains("\"observer_bound\".equals(stage)"));
        assertTrue(bridge.contains("TurnProtocolUiState.recordObserver"));
        assertTrue(bridge.contains("TurnProtocolUiState.record(context, eventRunId, stage, phase, observerToken)"));
        assertTrue(bridge.contains("eventRunId.equals(store.runId())"));
        assertTrue(ui.contains("activeGenerationForCurrentObserver"));
        assertTrue(ui.contains("activeGenerationFor(String observerToken)"));
        assertTrue(store.contains("boolean turnObserverSawStop()"));
        assertFalse(bridge.contains("markTurnObserverStopSeen(observerToken)"));
    }

    @Test public void dev10RecoveryLayerAndTimedSurfaceWakeupsAreAbsent() throws Exception {
        Path main = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun");
        if (!Files.exists(main)) main = Paths.get("src/main/java/com/shaterguy/chatgptselfrun");
        assertFalse(Files.exists(main.resolve("TurnCompletionRecoveryCoordinator.java")));
        assertFalse(Files.exists(main.resolve("TurnCompletionRecoveryPolicy.java")));

        String fallback = source("TurnCompletionDomFallbackScript.java");
        String host = source("HeadlessWebViewHost.java");
        String service = source("SelfRunService.java");
        String wrapper = source("WorkProtocolObservingWebViewClient.java");
        String combined = fallback + host + service + wrapper;
        for (String forbidden : new String[]{"turn-watchdog-rebind", "turn-watchdog-probe",
                "turn-watchdog-recover", "DRIVE_PROBE_MS", "RECOVERY_MS", "attachOutputFor("}) {
            assertFalse("forbidden dev10 recovery symbol: " + forbidden, combined.contains(forbidden));
        }
        assertTrue(service.contains("detachDisplayOutput(\"observer_armed\")"));
        assertTrue(service.contains("recoverDetachedObserverOutput(\"observer_unavailable\")"));
        assertTrue(service.contains("TURN_OBSERVER_HEALTHCHECK_MS = 15_000L"));
        assertTrue(host.contains("virtualDisplay.setSurface(null)"));
        assertTrue(host.contains("virtualDisplay.setSurface(surface)"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
