package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Static guardrails for separated Work and Pro transport adapters. */
public final class WorkProtocolDiagnosticsContractTest {
    @Test public void workDecoderIsWorkOnlyAndKeepsBoundedInspectorStructures() throws Exception {
        String work = source("WorkTurnProtocolIngressScript.java");
        String protocol = source("ChatGptTurnProtocolScript.java");

        assertTrue(work.contains("work-turn-ingress-v5"));
        assertTrue(work.contains("const handlesTransport=()=>workMode()"));
        assertFalse(work.contains("chat-decoder-"));
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

    @Test public void proDecoderIsDedicatedToActiveChatAndPreclassifiesCanonicalPost() throws Exception {
        String pro = source("ProTurnProtocolIngressScript.java");
        String config = source("WebViewConfig.java");

        assertTrue(pro.contains("pro-turn-ingress-v2"));
        assertTrue(pro.contains("safe(t?.mode).toLowerCase()==='chat'"));
        assertTrue(pro.contains("detectorLane)==='PRO'"));
        assertTrue(pro.contains("MAX_ENCODED_ITEMS=6"));
        assertTrue(pro.contains("MAX_ENCODED_ITEM_LENGTH=200000"));
        assertTrue(pro.contains("MAX_DECODE_DEPTH=8"));
        assertTrue(pro.contains("'pro-decoder-'+decoder"));
        assertTrue(pro.contains("stream_handoff"));
        assertTrue(pro.contains("workTurnId"));
        assertTrue(pro.contains("navigator.serviceWorker.addEventListener('message'"));
        assertTrue(pro.contains("new Uint8Array(data.buffer,data.byteOffset,data.byteLength)"));
        assertTrue(pro.contains("path==='/backend-api/f/conversation'"));
        assertTrue(pro.contains("const proRequestSelected=(input,init)=>"));
        assertTrue(pro.contains("const promoteFromRequestProfile=()=>"));
        assertTrue(pro.contains("window.fetch=function(input,init)"));
        assertTrue(pro.contains("requestProfileHints"));
        assertTrue(config.contains("ProTurnProtocolIngressScript.installDocumentStart(webView)"));
    }

    @Test public void workDiagnosticsCanSeeSubframesWithoutPromotingThemToTurnState() throws Exception {
        String work = source("WorkTurnProtocolIngressScript.java");
        String bridge = source("TurnProtocolLogBridge.java");

        for (String stage : new String[]{"WORK_PROTOCOL_TRANSPORT", "WORK_PROTOCOL_FRAME",
                "WORK_PROTOCOL_SIGNAL", "WORK_PROTOCOL_TRANSITION", "WORK_PROTOCOL_DECODE_ERROR"}) {
            assertTrue(work.contains("'" + stage + "'"));
            assertTrue(bridge.contains("\"" + stage + "\""));
        }
        assertTrue(bridge.contains("\"WORK_PROTOCOL_ENV\""));
        assertTrue(bridge.contains("\"WORK_PROTOCOL_COVERAGE\""));
        assertTrue(bridge.contains("item.put(\"frame\", isMainFrame ? \"main\" : \"subframe\")"));
        assertFalse(bridge.contains("if (!isMainFrame || message.getType()"));
        int diagnostic = bridge.indexOf("if (WORK_DIAGNOSTIC_STAGES.contains(stage))");
        int mainFrameGate = bridge.indexOf("if (!isMainFrame) return;", diagnostic);
        int tokenExtraction = bridge.indexOf("String turnToken = item.optString(\"turnToken\", \"\")", mainFrameGate);
        int exactTokenGate = bridge.indexOf("!turnToken.equals(store.turnProtocolToken())", tokenExtraction);
        int stateMutation = bridge.indexOf("TurnProtocolUiState.record(context, eventRunId, turnToken, stage, phase)");
        assertTrue(diagnostic >= 0);
        assertTrue(mainFrameGate > diagnostic);
        assertTrue(tokenExtraction > mainFrameGate);
        assertTrue(exactTokenGate > tokenExtraction);
        assertTrue(stateMutation > exactTokenGate);
        assertFalse(bridge.contains("\"observer_bound\""));
        assertTrue(work.contains("topKeys"));
        assertTrue(work.contains("encodedItemFound"));
        assertTrue(work.contains("staleRejected"));
    }

    @Test public void diagnosticsAreSanitizedAndNativeObservationDoesNotPersistSensitiveTraffic() throws Exception {
        String work = source("WorkTurnProtocolIngressScript.java");
        String pro = source("ProTurnProtocolIngressScript.java");
        String observer = source("WorkProtocolNativeObserver.java");
        String bridge = source("TurnProtocolLogBridge.java");

        assertTrue(bridge.contains("createdCount"));
        assertTrue(bridge.contains("messageReceivedCount"));
        assertTrue(bridge.contains("frameDecodedCount"));
        assertTrue(bridge.contains("semanticCandidateCount"));
        assertFalse(work.contains("requestBody"));
        assertFalse(work.contains("responseBody"));
        assertFalse(work.contains("cookie"));
        assertFalse(work.contains("authorization"));
        assertFalse(pro.contains("requestBody"));
        assertFalse(pro.contains("responseBody"));
        assertFalse(pro.contains("answerText"));
        assertFalse(observer.contains("getRequestHeaders"));
        assertFalse(observer.contains("Cookie"));
        assertFalse(observer.contains("Authorization"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
