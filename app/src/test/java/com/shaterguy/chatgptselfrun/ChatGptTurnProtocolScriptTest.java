package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Contract tests for the protocol-first ChatGPT turn state detector. */
public final class ChatGptTurnProtocolScriptTest {
    @Test public void canonicalConversationPostOwnsFirstAndFollowupLifecycle() {
        assertEquals("turn-protocol-v3", ChatGptTurnProtocolScript.ENGINE_VERSION);
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("path==='/backend-api/f/conversation'"));
        assertFalse(script.contains("path==='/backend-api/f/responses'"));
        assertFalse(script.contains("/backend-api/f/conversation/prepare"));
        assertFalse(script.contains("/backend-api/conversation/init"));
        assertTrue(script.contains("previous==='IDLE'"));
        assertTrue(script.contains("state.turnKind='FIRST_TURN'"));
        assertTrue(script.contains("previous==='COMPLETE'"));
        assertTrue(script.contains("state.turnKind='FOLLOWUP_TURN'"));
        assertTrue(script.contains("else return false"));
        assertTrue(script.contains("state.phase='THINKING'"));
        assertTrue(script.contains("selfrun-drive:turn-protocol-state:v3"));
    }

    @Test public void finalChannelAndVisibleAnswerOwnAnsweringPhase() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("marker==='user_visible_token'"));
        assertTrue(script.contains("marker==='cot_token'"));
        assertTrue(script.contains("marker==='last_token'"));
        assertTrue(script.contains("marker==='final_channel_token'&&event==='first'"));
        assertTrue(script.contains("sawVisibleAnswer:false"));
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
        assertTrue(script.contains("observeSocketFrame,observeWorkFrame:observeSocketFrame"));
        assertFalse(script.contains("complete('work_done')"));
    }

    @Test public void directFinalMessageIsVisibleAnswerEvidenceWithoutFinalChannelMarker() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("sawAssistantFinalText:false"));
        assertTrue(script.contains("completionEvidence=()=>state.sawVisibleAnswer"));
        assertTrue(script.contains("role!=='assistant'||channel!=='final'"));
        assertTrue(script.contains("state.finalMessageActive=true"));
        assertTrue(script.contains("noteAssistantFinalText('visible_answer')"));
        assertTrue(script.contains("path.includes('/message/content/parts')"));
        assertTrue(script.contains("value.v?.message"));
        assertTrue(script.contains("sawVisibleAnswer:state.sawVisibleAnswer"));
        assertTrue(script.contains("sawAssistantFinalText:state.sawAssistantFinalText"));
    }

    @Test public void emptySemanticCompleteCannotFinishOrFallBackToDom() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("if(!completionEvidence())"));
        assertTrue(script.contains("suspendDomFallback(window.__selfRunDriveTurnObserver)"));
        assertTrue(script.contains("state.lastError='completion_without_final_answer_evidence'"));
        assertTrue(script.contains("emitLog('completion_ignored',source)"));
        assertTrue(script.contains("state.phase!=='COMPLETE'||state.completionDispatched||!completionEvidence()"));
        assertTrue(script.contains("state.phase==='COMPLETE'&&!state.completionDispatched&&completionEvidence()"));
    }

    @Test public void hiddenFinishedMessagesCannotCompleteTheTurn() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("safe(finalMessage.author?.role).toLowerCase()==='assistant'"));
        assertTrue(script.contains("safe(finalMessage.channel).toLowerCase()==='final'"));
        assertTrue(script.contains("finalMessage.status==='finished_successfully'&&finalMessage.end_turn===true"));
        assertFalse(script.contains("message.status==='finished_successfully'&&message.end_turn===true"));
    }

    @Test public void canonicalHttpFailureDoesNotFeedSemanticParserOrDomFallback() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("if(!response?.ok)"));
        assertTrue(script.contains("markError('canonical_http_'+safe(response?.status),sequence)"));
        assertTrue(script.contains("return response"));
        assertTrue(script.contains("markError('canonical_fetch_rejected',sequence)"));
        assertTrue(script.contains("const markError=(reason,sequence)=>"));
        assertTrue(script.contains("suspendDomFallback(window.__selfRunDriveTurnObserver)"));
    }

    @Test public void protocolCompletionCancelsDomFallbackAndUsesVerifiedCallback() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("window.__selfRunDriveTurnObserver"));
        assertTrue(script.contains("const suspendDomFallback=observer=>"));
        assertTrue(script.contains("observer.fired=true"));
        assertTrue(script.contains("observer.observer?.disconnect"));
        assertTrue(script.contains("window.__selfRunDriveTurnObserver=null"));
        assertTrue(script.contains("const COMPLETION_SCHEME=\"selfrun-drive\";"));
        assertTrue(script.contains("const COMPLETION_HOST=\"turn-completed\";"));
        assertTrue(script.contains("+'://'+COMPLETION_HOST"));
        assertTrue(script.contains("response.clone()"));
        assertTrue(script.contains("window.fetch=async function"));
        assertTrue(script.contains("window.WebSocket=WrappedWebSocket"));

        assertFalse(script.contains("document.querySelector"));
        assertFalse(script.contains("MutationObserver"));
        assertFalse(script.contains("prompt-textarea"));
        assertFalse(script.contains("data-message-author-role"));
    }

    @Test public void automationWebViewInstallsProfileAndTurnProtocolAtDocumentStart() throws Exception {
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
