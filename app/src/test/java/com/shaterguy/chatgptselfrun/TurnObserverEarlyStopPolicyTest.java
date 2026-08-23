package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class TurnObserverEarlyStopPolicyTest {
    @Test public void callbackPhaseFenceAcceptsOnlySubmissionAndWaitPhases() {
        assertTrue(SelfRunStore.isActiveTurnObserverCallbackPhase(SelfRunStore.PHASE_BOOTSTRAP_SEND));
        assertTrue(SelfRunStore.isActiveTurnObserverCallbackPhase(SelfRunStore.PHASE_SEND_CONTINUE));
        assertTrue(SelfRunStore.isActiveTurnObserverCallbackPhase(SelfRunStore.PHASE_WAIT_TURN_COMPLETION));
        assertFalse(SelfRunStore.isActiveTurnObserverCallbackPhase(SelfRunStore.PHASE_IDLE));
        assertFalse(SelfRunStore.isActiveTurnObserverCallbackPhase(SelfRunStore.PHASE_APPLY_PREFS));
        assertFalse(SelfRunStore.isActiveTurnObserverCallbackPhase(SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC));
        assertFalse(SelfRunStore.isActiveTurnObserverCallbackPhase(null));
    }

    @Test public void earlyStopProofIsDurableAndForcesIdleBaselineAfterSubmit() throws Exception {
        String store = src("SelfRunStore.java");
        String service = src("SelfRunService.java");
        assertTrue(store.contains("if(!isActiveTurnObserverCallbackPhase(phase())||token.isEmpty()||!token.equals(turnObserverToken()))return false;"));
        assertTrue(store.contains("putBoolean(\"turnObserverSawStop\",true)"));
        assertTrue(service.contains("SelfRunStore.isActiveTurnObserverCallbackPhase(store.phase())"));
        assertTrue(service.contains("turnObserverNeedsIdleBaseline || store.turnObserverSawStop()"));
        assertTrue(service.contains("store.bootstrapSubmissionConfirmed(token)"));
        assertTrue(service.contains("store.beginTurnCompletionWait(token"));
    }

    @Test public void noStopProofKeepsNormalWaitAndObserverLifecycleFences() throws Exception {
        String store = src("SelfRunStore.java");
        String service = src("SelfRunService.java");
        assertTrue(service.contains("private boolean turnObserverNeedsIdleBaseline = false;"));
        assertTrue(store.contains("void prepareTurnObserver(String token)"));
        assertTrue(store.contains("putBoolean(\"turnObserverSawStop\",false)"));
        assertTrue(store.contains("boolean beginPostDomDriveSync(String observerToken)"));
        assertTrue(service.contains("!launchedRunId.equals(run)||!launchedRunId.equals(store.runId())"));
        assertTrue(service.contains("token==null||!token.equals(store.turnObserverToken())"));
        assertFalse(service.contains("turnObserverNeedsIdleBaseline || true"));
    }

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
