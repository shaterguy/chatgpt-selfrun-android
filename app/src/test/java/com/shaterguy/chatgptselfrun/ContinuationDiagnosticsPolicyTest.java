package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class ContinuationDiagnosticsPolicyTest {
    @Test public void continuationFailuresReachDeduplicatedRunLog() throws Exception {
        String service = source("SelfRunService.java");
        String log = source("SelfRunRunLog.java");
        assertTrue(service.contains("recordContinuationRouteMismatch(webView.getUrl())"));
        assertTrue(service.contains("recordContinuationRouteMismatch(requested)"));
        assertTrue(service.contains("recordContinuationWait(phase,status,detail)"));
        assertTrue(service.contains("isContinuationDiagnosticPhase(phase)"));
        assertTrue(service.contains("PHASE_WAIT_TURN_COMPLETION.equals(phase)"));
        assertTrue(log.contains("NOISY_HEARTBEAT_MS = 30_000L"));
    }

    @Test public void phaseCategoriesArePrivacySafe() {
        assertEquals("status=UI_WAIT;phase=apply_model;reason=model_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_APPLY_PREFS, "UI_WAIT", "private detail"));
        assertEquals("status=WAIT;phase=apply_reasoning;reason=reasoning_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_APPLY_REASONING, "WAIT", "private detail"));
        assertEquals("status=UNKNOWN;phase=wait_turn_completion;reason=state_wait",
                SelfRunWebDiagnostics.stateDetail(SelfRunStore.PHASE_WAIT_TURN_COMPLETION,
                        SelfRunContinuationDom.UNKNOWN));
        assertEquals("status=CALLBACK_TIMEOUT;phase=send_continue;reason=evaluate_javascript",
                SelfRunWebDiagnostics.callbackTimeoutDetail(SelfRunStore.PHASE_SEND_CONTINUE));
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
