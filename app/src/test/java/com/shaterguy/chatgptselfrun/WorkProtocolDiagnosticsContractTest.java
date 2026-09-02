package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Static guardrails for the Work-only decoder and privacy-safe diagnostic bridge. */
public final class WorkProtocolDiagnosticsContractTest {
    @Test public void workDecoderKeepsChatStateMachineUntouchedAndUsesBoundedInspectorStructures()
            throws Exception {
        String work = source("WorkTurnProtocolIngressScript.java");
        String protocol = source("ChatGptTurnProtocolScript.java");

        assertTrue(work.contains("work-turn-ingress-v3"));
        assertTrue(work.contains("MAX_ENCODED_ITEMS=6"));
        assertTrue(work.contains("MAX_ENCODED_ITEM_LENGTH=200000"));
        assertTrue(work.contains("MAX_DECODE_DEPTH=8"));
        assertTrue(work.contains("key==='encoded_item'"));
        assertTrue(work.contains("decodeURIComponent(trimmed)"));
        assertTrue(work.contains("decodeBase64Text"));
        assertTrue(work.contains("b64-sse"));
        assertTrue(work.contains("b64-json"));
        assertTrue(work.contains("arraybuffer_view"));
        assertTrue(work.contains("if(text==='[DONE]')"));
        assertTrue(work.contains("semantic:'sse_done_ignored'"));
        assertFalse(work.contains("type==='done')complete"));
        assertTrue(protocol.contains("if(type==='done')return;"));
        assertTrue(protocol.contains("if(!text||text==='[DONE]')return;"));
        assertTrue(protocol.contains("marker==='final_channel_token'&&event==='first'"));
        assertTrue(protocol.contains("value.type==='message_stream_complete'"));
    }

    @Test public void diagnosticsAreSanitizedAndDoNotEnterCanonicalTurnUiStatePath() throws Exception {
        String work = source("WorkTurnProtocolIngressScript.java");
        String bridge = source("TurnProtocolLogBridge.java");

        for (String stage : new String[]{"WORK_PROTOCOL_TRANSPORT", "WORK_PROTOCOL_FRAME",
                "WORK_PROTOCOL_SIGNAL", "WORK_PROTOCOL_TRANSITION", "WORK_PROTOCOL_DECODE_ERROR"}) {
            assertTrue(work.contains("'" + stage + "'"));
            assertTrue(bridge.contains("\"" + stage + "\""));
        }
        assertTrue(work.contains("topKeys"));
        assertTrue(work.contains("encodedItemFound"));
        assertTrue(work.contains("staleRejected"));
        assertTrue(bridge.contains("if (WORK_DIAGNOSTIC_STAGES.contains(stage))"));
        assertTrue(bridge.indexOf("if (WORK_DIAGNOSTIC_STAGES.contains(stage))")
                < bridge.indexOf("TurnProtocolUiState.record(context, eventRunId, stage, phase)"));
        assertFalse(work.contains("requestBody"));
        assertFalse(work.contains("responseBody"));
        assertFalse(work.contains("cookie"));
        assertFalse(work.contains("authorization"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
