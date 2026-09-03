package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Regression contract for the false successor rollover while the current response is active. */
public final class TurnProtocolGenerationGuardPolicyTest {
    @Test public void tokenCorrelatedProtocolGenerationBlocksSixtySecondNoStartRollover() {
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

    @Test public void protocolAndDomScriptsCarryCurrentObserverToken() {
        String protocol = ChatGptTurnProtocolScript.documentStartScript();
        String fallback = TurnCompletionDomFallbackScript.documentStartScript(10L, 20L, 30L, 40L);
        assertTrue(protocol.contains("observerToken:currentObserverToken()"));
        assertTrue(fallback.contains("stage:'observer_bound'"));
        assertTrue(fallback.contains("protocolActiveForToken"));
        assertTrue(fallback.contains("protocol?.phase==='THINKING'||protocol?.phase==='ANSWERING'"));
    }

    @Test public void nativeRecoveryDefersBeforeCancellingObserverOrCreatingSuccessor() throws Exception {
        String coordinator = source("TurnCompletionRecoveryCoordinator.java");
        int guard = coordinator.indexOf("TurnProtocolUiState.activeGenerationFor(token)");
        int cancel = coordinator.indexOf("cancelTurnCompletionObserver(token)");
        int rollover = coordinator.indexOf("beginOrResume(store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT)");
        assertTrue(guard >= 0);
        assertTrue(cancel > guard);
        assertTrue(rollover > guard);
        assertTrue(coordinator.contains("result=deferred_active_protocol"));
    }

    @Test public void bridgeMaintainsObserverAndProtocolCorrelationWithoutReusingStopState() throws Exception {
        String bridge = source("TurnProtocolLogBridge.java");
        String ui = source("TurnProtocolUiState.java");
        String store = source("SelfRunStore.java");
        assertTrue(bridge.contains("\"observer_bound\".equals(stage)"));
        assertTrue(bridge.contains("TurnProtocolUiState.recordObserver"));
        assertTrue(bridge.contains("TurnProtocolUiState.record(context, eventRunId, stage, phase, observerToken)"));
        assertTrue(ui.contains("activeGenerationForCurrentObserver"));
        assertTrue(ui.contains("activeGenerationFor(String observerToken)"));
        assertTrue(store.contains("boolean turnObserverSawStop()"));
        assertFalse(bridge.contains("markTurnObserverStopSeen(observerToken)"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
