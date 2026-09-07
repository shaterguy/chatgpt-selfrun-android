package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class SelfRunHealthStaleErrorContractTest {
    @Test public void staleRetryCodeCannotOverrideNewV3Phase() throws Exception {
        SelfRunHealthInput in = base(SelfRun3Coordinator.PHASE_WAITING);
        in.lastErrorCode = "WEB_STATE_RETRY";
        JSONObject record = new JSONObject()
                .put("lastErrorCodeSeen", "WEB_STATE_RETRY")
                .put("lastErrorPhase", SelfRun3Coordinator.PHASE_RECONCILING);
        SelfRunHealthObservationStore.suppressStaleError(in, record);
        SelfRunHealthSnapshot h = SelfRunHealthEvaluator.evaluate(in, 10_000L);
        assertEquals(SelfRunHealthSnapshot.WAITING, h.level);
        assertEquals("WAITING_CHATGPT", h.category);
    }

    @Test public void freshRetryCodeStillShowsRecovery() throws Exception {
        SelfRunHealthInput in = base(SelfRun3Coordinator.PHASE_RECONCILING);
        in.lastErrorCode = "WEB_STATE_RETRY";
        JSONObject record = new JSONObject()
                .put("lastErrorCodeSeen", "WEB_STATE_RETRY")
                .put("lastErrorPhase", SelfRun3Coordinator.PHASE_RECONCILING);
        SelfRunHealthObservationStore.suppressStaleError(in, record);
        SelfRunHealthSnapshot h = SelfRunHealthEvaluator.evaluate(in, 10_000L);
        assertEquals(SelfRunHealthSnapshot.RECOVERING, h.level);
    }

    @Test public void unknownPreUpgradeErrorIsNotReinterpretedAsCurrent() throws Exception {
        SelfRunHealthInput in = base(SelfRun3Coordinator.PHASE_WAITING);
        in.lastErrorCode = "WEB_STATE_RETRY";
        JSONObject record = new JSONObject()
                .put("lastErrorCodeSeen", "WEB_STATE_RETRY")
                .put("lastErrorPhase", "");
        SelfRunHealthObservationStore.suppressStaleError(in, record);
        assertEquals("", in.lastErrorCode);
    }

    @Test public void observationStoreTracksErrorPhaseWithoutRuntimeMutation() throws Exception {
        String store = source("SelfRunHealthObservationStore.java");
        assertTrue(store.contains("errorObservationInitialized"));
        assertTrue(store.contains("lastErrorCodeSeen"));
        assertTrue(store.contains("lastErrorPhase"));
        assertTrue(store.contains("observeErrorState"));
        assertTrue(store.contains("suppressStaleError"));
        assertTrue(store.contains("v3_reconciling"));
        assertFalse(store.contains("store.clearLastError"));
        assertFalse(store.contains("store.setLastError"));
    }

    private static SelfRunHealthInput base(String phase) {
        SelfRunHealthInput in = new SelfRunHealthInput();
        in.runId = "run-test";
        in.phase = phase;
        in.mode = SelfRunStore.MODE_CHAT;
        in.status = "정상";
        in.createdAt = 1_000L;
        in.phaseStartedAt = 2_000L;
        in.updatedAt = 1_500L;
        in.active = true;
        return in;
    }

    private static String source(String name) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
