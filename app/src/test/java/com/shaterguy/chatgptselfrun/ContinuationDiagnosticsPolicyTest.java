package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class ContinuationDiagnosticsPolicyTest {
    @Test public void allWebWaitAndRouteFailuresReachDeduplicatedRunLog() throws Exception {
        String service = src("SelfRunService.java");
        String log = src("SelfRunRunLog.java");
        assertTrue(service.contains("recordWebRouteMismatch(webView.getUrl())"));
        assertTrue(service.contains("recordWebRouteMismatch(requested)"));
        assertTrue(service.contains("recordWebWait(phase,status,result.optString(\"detail\",\"\"))"));
        assertTrue(service.contains("recordWebTargetError(phase)"));
        assertTrue(service.contains("isWebAutomationPhase(phase)"));
        assertTrue(service.contains("runLog.record(store,\"DOM_RESULT\""));
        assertTrue(log.contains("if (\"DOM_RESULT\".equals(event))"));
        assertTrue(log.contains("event.equals(\"DOM_RESULT\")"));
        assertTrue(log.contains("case \"DOM_RESULT\" -> \"WebView 대기/진단\""));
        assertTrue(log.contains("NOISY_HEARTBEAT_MS = 30_000L"));
    }

    @Test public void webViewLaunchAndPageFailuresAreNoLongerSilent() throws Exception {
        String service = src("SelfRunService.java");
        String log = src("SelfRunRunLog.java");
        assertTrue(service.contains("runLog.record(store, \"WEBVIEW_LAUNCH\""));
        assertTrue(service.contains("runLog.record(store,\"WEBVIEW_LAUNCH_RETRY\""));
        assertTrue(service.contains("SelfRunWebDiagnostics.targetRetryDetail(store.phase(),SUBMISSION_RETRY_MS)"));
        assertTrue(service.contains("runLog.record(store,\"RENDERER_GONE\",SelfRunWebDiagnostics.rendererRetryDetail(2_000L))"));
        assertTrue(service.contains("runLog.record(store,\"WEBVIEW_PAGE_START\""));
        assertTrue(service.contains("runLog.record(store,\"WEBVIEW_PAGE_FINISH\""));
        assertTrue(service.contains("runLog.record(store,\"WEBVIEW_ERROR\""));
        assertTrue(service.contains("SelfRunWebDiagnostics.launchRetryDetail(error,2_500L)"));
        assertTrue(log.contains("case \"WEBVIEW_LAUNCH_RETRY\""));
        assertTrue(log.contains("case \"RENDERER_GONE\" -> \"WebView 렌더러 종료 재시도\""));
    }

    @Test public void applyModelAndReasoningWaitsUsePrivacySafePhaseCategories() {
        assertEquals("status=UI_WAIT;phase=apply_model;reason=model_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_APPLY_PREFS, "UI_WAIT", "private detail"));
        assertEquals("status=WAIT;phase=apply_reasoning;reason=reasoning_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_APPLY_REASONING, "WAIT", "private detail"));
        assertEquals("status=UI_WAIT;phase=send_continue;reason=composer_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_SEND_CONTINUE, "UI_WAIT", "continuation 입력창 대기"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
