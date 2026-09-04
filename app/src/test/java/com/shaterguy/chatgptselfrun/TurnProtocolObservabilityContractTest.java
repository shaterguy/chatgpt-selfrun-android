package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class TurnProtocolObservabilityContractTest {
    @Test public void onlyProtocolDetectorIsInstalledAndReported() throws Exception {
        String config=source("WebViewConfig.java"),bridge=source("TurnProtocolLogBridge.java");
        String ui=source("TurnProtocolUiState.java");
        assertTrue(config.contains("TurnProtocolLogBridge.install(webView)"));
        assertTrue(config.contains("ChatGptTurnProtocolScript.installDocumentStart(webView)"));
        assertTrue(config.contains("WorkTurnProtocolIngressScript.installDocumentStart(webView)"));
        assertTrue(config.contains("WorkProtocolTransportCaptureScript.installDocumentStart(webView)"));
        assertFalse(config.contains("TurnCompletionDomFallbackScript"));
        assertTrue(bridge.contains("path=PROTOCOL"));
        assertTrue(ui.contains("DETECTOR_PROTOCOL = \"PROTOCOL\""));
        assertFalse(ui.contains("DOM_FALLBACK"));
    }
    @Test public void missingRequiredFeatureFailsClosed() throws Exception {
        String config=source("WebViewConfig.java"),bridge=source("TurnProtocolLogBridge.java");
        assertTrue(bridge.contains("WEB_MESSAGE_LISTENER"));
        assertTrue(bridge.contains("DOCUMENT_START_SCRIPT"));
        assertTrue(bridge.contains("return false;"));
        assertTrue(config.contains("if (!protocolAvailable)"));
        assertTrue(config.contains("return false;"));
    }
    private static String source(String name) throws Exception {
        Path path=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(path))path=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
