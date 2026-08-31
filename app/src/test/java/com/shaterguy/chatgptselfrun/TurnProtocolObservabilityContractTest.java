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

        assertTrue(webConfig.indexOf("TurnProtocolLogBridge.install")
                < webConfig.indexOf("ChatGptTurnProtocolScript.installDocumentStart"));
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
        assertTrue(bridge.contains("WEB_MESSAGE_LISTENER"));
        assertTrue(bridge.contains("TURN_PROTOCOL"));
        assertTrue(bridge.contains("eventRunId.equals(store.runId())"));
        assertTrue(bridge.contains("TurnProtocolUiState.record(context, eventRunId, stage, phase)"));
        assertFalse(bridge.contains("optInt(\"sequence\""));
        assertFalse(bridge.contains("FIRST_TURN"));
        assertFalse(ui.contains("KEY_SEQUENCE"));
        assertFalse(ui.contains("int sequence"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
