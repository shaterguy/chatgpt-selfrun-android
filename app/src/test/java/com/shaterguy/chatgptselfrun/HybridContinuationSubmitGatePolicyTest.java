package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Keeps API-profile activation ahead of composer mutation and final dispatch. */
public final class HybridContinuationSubmitGatePolicyTest {
    @Test public void serviceGatesBothPrepareAndSubmitBoundaries() throws Exception {
        String service = source("SelfRunService.java");
        assertEquals(1, occurrences(service,
                "HybridRequestProfileScript.prepareContinuationAndThen(store.runId(),script)"));
        assertEquals(1, occurrences(service,
                "HybridRequestProfileScript.selectContinuationAndThen(store.runId(),action)"));
        assertTrue(service.indexOf("prepareContinuationAndThen(store.runId(),script)")
                < service.indexOf("evaluate(phase,script)"));
        assertTrue(service.contains(
                "action=HybridRequestProfileScript.selectContinuationAndThen(store.runId(),action);"
                        + "beginPostDispatchNoStartWindow();evaluate(phase"));
        assertTrue(service.contains("\"HYBRID_PROFILE_UNAVAILABLE\".equals(status)"));
        assertTrue(service.contains("pauseError(status"));
    }

    @Test public void gateUsesApiProfileReadbackWithoutAnyModeUiDependency() throws Exception {
        String gate = source("HybridRequestProfileScript.java");
        assertTrue(gate.contains("hybrid-request-profile-v6"));
        assertTrue(gate.contains("bridge.selectStage('continuation')"));
        assertTrue(gate.contains("bridge.target()"));
        assertTrue(gate.contains("profileMatches(target,endpoint)"));
        assertTrue(gate.contains("target.bootstrapReasoning"));
        assertTrue(gate.contains("target.continuationReasoning"));
        assertTrue(gate.contains("hybridProfileOutcome"));
        assertTrue(gate.indexOf("bridge.selectStage('continuation')")
                < gate.indexOf("forwarded=(__ACTION__)"));
        assertFalse(gate.contains("button[role=\"radio\"]"));
        assertFalse(gate.contains("MODE_WAIT_MS"));
        assertFalse(gate.contains("__CALIBRATION_PRELUDE__"));
        assertFalse(gate.contains("target.click()"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int at = value.indexOf(needle); at >= 0;
             at = value.indexOf(needle, at + needle.length())) count++;
        return count;
    }
}
