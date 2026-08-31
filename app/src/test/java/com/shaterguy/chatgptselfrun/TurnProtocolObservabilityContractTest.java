package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TurnProtocolObservabilityContractTest {
    @Test public void protocolTransitionsAreExportedWithoutTurnOrdinalDependency() throws Exception {
        String webConfig = source("WebViewConfig.java");
        String protocol = source("ChatGptTurnProtocolScript.java");
        String bridge = source("TurnProtocolLogBridge.java");
        String ui = source("TurnProtocolUiState.java");

        assertTrue(webConfig.contains("boolean protocolObservable = TurnProtocolLogBridge.install(webView)"));
        assertTrue(webConfig.indexOf("TurnProtocolLogBridge.install")
                < webConfig.indexOf("ChatGptTurnProtocolScript.installDocumentStart"));
        assertTrue(webConfig.contains("if (protocolObservable) {"));
        assertTrue(webConfig.contains("ChatGptTurnProtocolScript.installDocumentStart(webView);"));
        assertTrue(webConfig.contains("WorkTurnProtocolIngressScript.installDocumentStart(webView);"));
        assertTrue(protocol.contains("emitLog('turn_request','canonical_post')"));
        assertTrue(protocol.contains("noteVisibleAnswer('final_channel')"));
        assertTrue(protocol.contains("noteVisibleAnswer('visible_answer')"));
        assertTrue(protocol.contains("emitLog('completion_ignored',source)"));
        assertTrue(protocol.contains("emitLog('error',reason)"));
        assertTrue(protocol.contains("emitLog('complete',source)"));
        assertTrue(protocol.contains("emitLog('completion_dispatch',source)"));
        assertTrue(protocol.contains("runId:safe(state.runId)"));
        assertFalse(protocol.contains("turnSequence"));
        assertFalse(protocol.contains("turnKind"));
        assertTrue(bridge.contains("static boolean install(WebView webView)"));
        assertTrue(bridge.contains("WEB_MESSAGE_LISTENER"));
        assertTrue(bridge.contains("DOCUMENT_START_SCRIPT"));
        assertTrue(bridge.contains("TURN_PROTOCOL"));
        assertTrue(bridge.contains("TURN_DETECTOR"));
        assertTrue(bridge.contains("DETECTOR_PROTOCOL_PRIMARY"));
        assertTrue(bridge.contains("DETECTOR_DOM_FALLBACK_ONLY"));
        assertTrue(bridge.contains("eventRunId.equals(store.runId())"));
        assertTrue(bridge.contains("TurnProtocolUiState.record(context, eventRunId, stage, phase)"));
        assertFalse(bridge.contains("optInt(\"sequence\""));
        assertFalse(bridge.contains("FIRST_TURN"));
        assertTrue(ui.contains("프로토콜 우선 / DOM fallback 병행"));
        assertTrue(ui.contains("응답 감지 중 · DOM fallback"));
        assertFalse(ui.contains("KEY_SEQUENCE"));
        assertFalse(ui.contains("int sequence"));
    }

    @Test public void missingProtocolFeatureMeansProtocolScriptIsNotInstalledAndDomFallbackRemainsOwner() throws Exception {
        String webConfig = source("WebViewConfig.java");
        String bridge = source("TurnProtocolLogBridge.java");

        assertTrue(bridge.contains("boolean messageBridge = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)"));
        assertTrue(bridge.contains("boolean documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)"));
        assertTrue(bridge.contains("if (!messageBridge || !documentStart)"));
        assertTrue(bridge.contains("return false;"));
        assertTrue(webConfig.contains("if (protocolObservable) {"));
        assertTrue(webConfig.contains("ChatGptTurnProtocolScript.installDocumentStart(webView);"));
        assertTrue(webConfig.contains("WorkTurnProtocolIngressScript.installDocumentStart(webView);"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
