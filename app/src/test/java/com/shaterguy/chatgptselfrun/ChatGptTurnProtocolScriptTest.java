package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Contract tests for the protocol-first ChatGPT response-state detector. */
public final class ChatGptTurnProtocolScriptTest {
    @Test public void canonicalConversationPostAlwaysOwnsLatestResponseWithoutTurnCounters() {
        assertEquals("turn-protocol-v6", ChatGptTurnProtocolScript.ENGINE_VERSION);
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("path==='/backend-api/f/conversation'"));
        assertFalse(script.contains("path==='/backend-api/f/responses'"));
        assertFalse(script.contains("/backend-api/f/conversation/prepare"));
        assertFalse(script.contains("/backend-api/conversation/init"));
        assertTrue(script.contains("const startRequest=meta=>"));
        assertTrue(script.contains("state.requestIdentity=requestIdentity()"));
        assertTrue(script.contains("retireWorkTurn(state.currentWorkTurnId)"));
        assertTrue(script.contains("state.phase='THINKING'"));
        assertTrue(script.contains("selfrun-drive:response-protocol-state:v6"));
        assertFalse(script.contains("turnSequence"));
        assertFalse(script.contains("turnKind"));
        assertFalse(script.contains("FIRST_TURN"));
        assertFalse(script.contains("FOLLOWUP_TURN"));
    }

    @Test public void supersededFetchAndSocketDataAreIdentityFenced() {
        String script = ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("identity&&identity!==state.requestIdentity"));
        assertTrue(script.contains("if(identity!==state.requestIdentity)return response"));
        assertTrue(script.contains("if(context.requestIdentity!==state.requestIdentity)return"));
        assertTrue(script.contains("retiredWorkTurnIds.includes(value)"));
        assertTrue(script.contains("requestIdentity:context?.requestIdentity"));
    }

    @Test public void finalChannelAndVisibleAnswerOwnAnsweringPhase() {
        String script = ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("marker==='user_visible_token'"));
        assertTrue(script.contains("marker==='cot_token'"));
        assertTrue(script.contains("marker==='last_token'"));
        assertTrue(script.contains("marker==='final_channel_token'&&event==='first'"));
        assertTrue(script.contains("noteVisibleAnswer('final_channel')"));
        assertTrue(script.contains("noteVisibleAnswer('visible_answer')"));
        assertTrue(script.contains("state.phase='ANSWERING'"));
        assertTrue(script.contains("value.type==='stream_handoff'"));
        assertTrue(script.contains("value.type==='message_stream_complete'"));
        assertTrue(script.contains("finalMessage.status==='finished_successfully'&&finalMessage.end_turn===true"));
    }

    @Test public void proAndWorkSocketTransportRejectsInternalAndOuterDoneAsCompletion() {
        String script = ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("if(!text||text==='[DONE]')return"));
        assertTrue(script.contains("const acceptSocketPayload=payload=>"));
        assertTrue(script.contains("if(type==='done')return"));
        assertTrue(script.contains("type!=='stream-item'||typeof payload.encoded_item!=='string'"));
        assertTrue(script.contains("observeSseText(payload.encoded_item,'chatgpt-websocket',context)"));
        assertFalse(script.contains("complete('work_done')"));
    }

    @Test public void earlySemanticCompleteCannotFinishAndKeepsDomFallbackAvailable() {
        String script = ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("if(!completionEvidence())"));
        assertTrue(script.contains("state.lastError='completion_without_final_answer_evidence'"));
        assertTrue(script.contains("emitLog('completion_ignored',source)"));
        assertFalse(script.contains("if(!completionEvidence()){\n                      suspendDomFallback(window.__selfRunDriveTurnObserver);"));
    }

    @Test public void protocolEventsCarryRunIdentityButNoTurnOrdinal() {
        String script = ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("runId:safe(state.runId)"));
        assertTrue(script.contains("stage:safe(stage)"));
        assertTrue(script.contains("phase:state.phase"));
        assertFalse(script.contains("sequence:state"));
        assertFalse(script.contains("kind:state"));
    }

    @Test public void automationWebViewInstallsProfileAndResponseProtocolAtDocumentStart() throws Exception {
        String source = source("WebViewConfig.java");
        int profile = source.indexOf("RequestProfileScript.installDocumentStart(webView)");
        int protocol = source.indexOf("ChatGptTurnProtocolScript.installDocumentStart(webView)");
        assertTrue(profile >= 0);
        assertTrue(protocol > profile);
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
