package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public final class TurnProtocolObservabilityContractTest {
    @Test public void protocolTransitionsAreExportedToRunLogBridge() throws Exception {
        String webConfig = source("WebViewConfig.java");
        String protocol = source("ChatGptTurnProtocolScript.java");
        String bridge = source("TurnProtocolLogBridge.java");

        assertTrue(webConfig.indexOf("TurnProtocolLogBridge.install")
                < webConfig.indexOf("ChatGptTurnProtocolScript.installDocumentStart"));
        assertTrue(protocol.contains("emitLog('turn_request','canonical_post')"));
        assertTrue(protocol.contains("emitLog('answering_started','final_channel')"));
        assertTrue(protocol.contains("emitLog('complete',source)"));
        assertTrue(protocol.contains("emitLog('completion_dispatch',source)"));
        assertTrue(protocol.contains("complete('message_stream_complete')"));
        assertTrue(protocol.contains("complete('work_done')"));
        assertTrue(bridge.contains("WEB_MESSAGE_LISTENER"));
        assertTrue(bridge.contains("TURN_PROTOCOL"));
        assertTrue(bridge.contains("stage=\" + stage"));
        assertTrue(bridge.contains("source=\" + source"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
