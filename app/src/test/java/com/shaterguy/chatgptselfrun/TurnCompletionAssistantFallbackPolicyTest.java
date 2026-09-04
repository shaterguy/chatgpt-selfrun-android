package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class TurnCompletionAssistantFallbackPolicyTest {
    @Test public void fallbackRequiresCurrentTurnAssistantFinalUiAndIdleStability() throws Exception {
        String script = TurnCompletionDomFallbackScript.documentStartScript();
        assertTrue(script.contains("[data-message-author-role]"));
        assertTrue(script.contains("baselineUser"));
        assertTrue(script.contains("baselineAssistant"));
        assertTrue(script.contains("belongsToCurrentTurn"));
        assertTrue(script.contains("finalActionEvidence"));
        assertTrue(script.contains("composerIdle"));
        assertTrue(script.contains("STABILITY_MS=5000"));
        assertTrue(script.contains("source=dom_assistant_final_ui"));
        assertTrue(script.contains("protocol?.phase==='ERROR'"));
        assertTrue(script.contains("protocolActiveForToken"));
        assertTrue(script.contains("protocol?.phase==='THINKING'||protocol?.phase==='ANSWERING'"));
        assertTrue(script.contains("if(!state.token||state.fired||observerConnected)return"));
        assertTrue(script.contains("EVALUATION_DELAY_MS=250"));
        assertTrue(script.contains("expensiveEvaluations"));
        assertFalse(script.contains("setInterval("));
        assertFalse(script.contains("offsetParent"));
    }

    @Test public void fallbackRejectsGenericCodeCopyAsResponseCompletionEvidence() {
        String script = TurnCompletionDomFallbackScript.documentStartScript();
        assertTrue(script.contains("copy code"));
        assertTrue(script.contains("코드 복사"));
        assertTrue(script.contains("copy-turn"));
        assertTrue(script.contains("good response"));
        assertTrue(script.contains("좋은 답변"));
    }

    @Test public void automationWebViewAlwaysInstallsFallbackForBackgroundRuns() throws Exception {
        String config = source("WebViewConfig.java");
        assertTrue(config.contains("if (plan.domFallback) TurnCompletionDomFallbackScript.installDocumentStart(webView)"));
        int plan = config.indexOf("AutomationPlan plan = automationPlan");
        int fallback = config.indexOf("if (plan.domFallback) TurnCompletionDomFallbackScript.installDocumentStart(webView)");
        int protocolGate = config.indexOf("if (protocolObservable)", fallback);
        assertTrue("background fallback must remain available even when protocol bridge is unavailable",
                plan >= 0 && fallback > plan && protocolGate > fallback);
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
