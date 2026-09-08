package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Guards the active dispatch-only product contract, independent of the preserved V2 decoder. */
public final class SelfRun3DispatchScriptTest {
    @Test public void dispatchObserverOnlyReadsOneBoundedIdentityFrame() {
        String js = SelfRun3DispatchScript.documentStartScript();
        for (String forbidden : new String[]{"WebSocket", "setInterval",
                "querySelector", "message_stream_complete", "completion"})
            assertFalse(forbidden, js.contains(forbidden));
        assertTrue(js.contains("if(!owner||claimed)reject()"));
        assertTrue(js.contains("metadata?.conversation_id"));
        assertTrue(js.contains("first.value.byteLength>16384"));
        assertTrue(js.contains("reader?.cancel?.()"));
        assertFalse(js.contains("while("));
        assertTrue(js.contains("b?.conversation_id"));
        assertTrue(js.contains("includes(owner.turn)"));
        assertTrue(js.contains("includes(owner.request)"));
    }
    @Test public void activeAdapterOnlyUsesFreshComposerAndStopsAtCanonicalDispatch() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertFalse(web.contains("SelfRunContinuationDom"));
        assertFalse(web.contains("inspectionScript"));
        assertFalse(web.contains("armCompletion"));
        assertTrue(web.contains("a.quiesce();"));
        assertTrue(web.indexOf("a.quiesce();") < web.indexOf("a.listener.onStarted("));
        assertTrue(web.contains("WebViewConfig.applySelfRun3Automation"));
        String config = source("WebViewConfig.java");
        assertTrue(config.indexOf("SelfRun3DispatchScript.install") < config.indexOf("TurnProtocolLogBridge.install"));
    }
    private static String source(String name) throws Exception {
        Path p = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
