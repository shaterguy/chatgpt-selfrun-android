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

    @Test public void automationWebViewAlwaysInstallsFallback() throws Exception {
        String config = source("WebViewConfig.java");
        assertTrue(config.contains("TurnCompletionDomFallbackScript.installDocumentStart(webView)"));
        int fallback = config.indexOf("TurnCompletionDomFallbackScript.installDocumentStart(webView)");
        int protocolGate = config.indexOf("if (protocolObservable)");
        assertTrue("fallback must remain available even when protocol bridge is unavailable",
                fallback >= 0 && protocolGate >= 0 && fallback < protocolGate);
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
