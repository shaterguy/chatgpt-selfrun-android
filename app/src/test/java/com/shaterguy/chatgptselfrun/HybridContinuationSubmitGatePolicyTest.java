package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Keeps the HYBRID UI-mode gate ahead of composer mutation and final dispatch. */
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
        assertTrue(service.contains("\"HYBRID_MODE_UNAVAILABLE\".equals(status)"));
        assertTrue(service.contains("\"HYBRID_PROFILE_UNAVAILABLE\".equals(status)"));
        assertTrue(service.contains("pauseError(status"));
    }

    @Test public void gateUsesExactSemanticRadioAndDualSelectedStateReadback() throws Exception {
        String gate = source("HybridRequestProfileScript.java");
        assertTrue(gate.contains("button[role=\"radio\"][data-tpp-toggle-value]"));
        assertTrue(gate.contains("getAttribute('aria-checked')"));
        assertTrue(gate.contains("getAttribute('data-state')"));
        assertTrue(gate.contains("groupRadios.length!==2"));
        assertTrue(gate.contains("MODE_WAIT_MS=10000"));
        assertTrue(gate.contains("state.boundary!==BOUNDARY"));
        assertTrue(gate.contains("unavailableAfterWait('target_obstructed'"));
        assertTrue(gate.contains("if(on(target)&&!off(counterpart))"));
        assertTrue(gate.contains("if(!off(target)||!on(counterpart))"));
        assertTrue(gate.contains("aria-disabled"));
        assertTrue(gate.contains("elementFromPoint"));
        assertTrue(gate.contains("hybridModeOutcome"));
        assertTrue(gate.contains("bridge.selectStage('continuation')"));
        assertTrue(gate.indexOf("if(on(target))")
                < gate.indexOf("bridge.selectStage('continuation')"));
        assertTrue(gate.indexOf("bridge.selectStage('continuation')")
                < gate.indexOf("forwarded=(__ACTION__)"));
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
