package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** The old watchdog fence is retired in favor of one run/token-scoped observer callback. */
public final class TurnCompletionWatchdogFencePolicyTest {
    @Test public void serviceContainsNoWatchdogExecutionPhase() throws Exception {
        String service = source("SelfRunService.java");
        assertFalse(service.contains("PHASE_WATCHDOG_"));
        assertFalse(service.contains("isWatchdogRecoverySubmissionPhase"));
        assertFalse(service.contains("createNamedRangeClaim"));
        assertFalse(service.contains("driveRecoveryContinuation"));
    }

    @Test public void observerCallbackIsFencedByRunPhaseAndToken() throws Exception {
        String callback = section(source("SelfRunService.java"),
                "private boolean isTurnCompletionCallback", "private void maybeCaptureConversationUrl");
        int run = callback.indexOf("launchedRunId.equals(run)");
        int activeRun = callback.indexOf("launchedRunId.equals(store.runId())");
        int activePhase = callback.indexOf("isActiveTurnObserverCallbackPhase(store.phase())");
        int stopToken = callback.indexOf("token.equals(store.turnObserverToken())", activePhase);
        int waitPhase = callback.indexOf("PHASE_WAIT_TURN_COMPLETION.equals(store.phase())", stopToken);
        int completionToken = callback.indexOf("token.equals(store.turnObserverToken())", waitPhase);
        int transition = callback.indexOf("store.beginPostDomDriveSync(token)");
        assertTrue(run >= 0 && activeRun > run && activePhase > activeRun && stopToken > activePhase);
        assertTrue(waitPhase > stopToken && completionToken > waitPhase && transition > completionToken);
    }

    @Test public void observerDisconnectPrecedesNativeCompletionCallback() throws Exception {
        String observer = section(source("SelfRunContinuationDom.java"),
                "private static String completionObserver", "private static String conversationGuard");
        String complete = section(observer, "const complete=", "const fireStable=");
        int disconnect = complete.indexOf("cancelObserverState(state)");
        int clearState = complete.indexOf("window.__selfRunDriveTurnObserver=null", disconnect);
        int callback = complete.indexOf("location.href=observerCallback", clearState);
        assertTrue(disconnect >= 0 && clearState > disconnect && callback > clearState);
    }

    @Test public void recoveryIdleBaselineRequiresDurableStopProof() throws Exception {
        String service = source("SelfRunService.java");
        String store = source("SelfRunStore.java");
        assertTrue(service.contains("private boolean turnObserverNeedsIdleBaseline = false;"));
        assertTrue(service.contains("store.turnObserverSawStop()"));
        assertTrue(service.contains("TURN_STOP_SEEN_HOST"));
        assertTrue(service.contains("store.markTurnObserverStopSeen(token)"));
        assertTrue(store.contains("boolean turnObserverSawStop()"));
        assertTrue(store.contains("boolean markTurnObserverStopSeen(String observerToken)"));
        assertTrue(store.contains("putBoolean(\"turnObserverSawStop\",false)"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String section(String text, String start, String end) {
        int a = text.indexOf(start);
        int b = text.indexOf(end, a + start.length());
        assertTrue("missing start: " + start, a >= 0);
        assertTrue("missing end: " + end, b > a);
        return text.substring(a, b);
    }
}
