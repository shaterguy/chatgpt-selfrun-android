package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class ContinuationDiagnosticsPolicyTest {
    @Test public void sendContinueWaitAndRouteFailuresReachDeduplicatedRunLog() throws Exception {
        String service = compact(src("SelfRunService.java"));
        String log = compact(src("SelfRunRunLog.java"));
        assertTrue(service.contains("recordContinuationRouteMismatch(webView.getUrl());"));
        assertTrue(service.contains("recordContinuationRouteMismatch(requested);"));
        assertTrue(service.contains("recordContinuationWait(phase,status,result.optString(\"detail\",\"\"));"));
        assertTrue(service.contains("recordContinuationTargetError(phase);"));
        assertTrue(service.contains("isContinuationDiagnosticPhase(phase)"));
        assertTrue(service.contains("runLog.record(store,\"DOM_RESULT\""));
        assertTrue(log.contains("if(\"DOM_RESULT\".equals(event))"));
        assertTrue(log.contains("event.equals(\"DOM_RESULT\")"));
        assertTrue(log.contains("case\"DOM_RESULT\"->\"WebView대기/진단\""));
        assertTrue(log.contains("NOISY_HEARTBEAT_MS=30_000L"));
    }

    @Test public void applyModelAndReasoningWaitsUsePrivacySafePhaseCategories() {
        assertEquals("status=UI_WAIT;phase=apply_model;reason=model_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_APPLY_PREFS, "UI_WAIT", "private detail"));
        assertEquals("status=WAIT;phase=apply_reasoning;reason=reasoning_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_APPLY_REASONING, "WAIT", "private detail"));
        assertEquals("status=UI_WAIT;phase=send_continue;reason=composer_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_SEND_CONTINUE, "UI_WAIT", "continuation 입력창 대기"));
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
