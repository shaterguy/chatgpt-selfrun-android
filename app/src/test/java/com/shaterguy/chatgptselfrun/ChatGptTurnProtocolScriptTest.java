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
    @Test public void canonicalConversationPostOwnsEveryUserTurn() {
        assertEquals("turn-protocol-v4", ChatGptTurnProtocolScript.ENGINE_VERSION);
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("path==='/backend-api/f/conversation'"));
        assertFalse(script.contains("path==='/backend-api/f/responses'"));
        assertFalse(script.contains("/backend-api/f/conversation/prepare"));
        assertFalse(script.contains("/backend-api/conversation/init"));
        assertTrue(script.contains("state.turnSequence=Math.max(1,state.turnSequence+1)"));
        assertTrue(script.contains("state.turnKind=state.turnSequence===1?'FIRST_TURN':'FOLLOWUP_TURN'"));
        assertFalse(script.contains("previous==='COMPLETE'"));
        assertFalse(script.contains("else return false"));
        assertTrue(script.contains("state.phase='THINKING'"));
        assertTrue(script.contains("selfrun-drive:turn-protocol-state:v4"));
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
    }

    @Test public void semanticCompletionDelegatesToExistingObserverOnly() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("const delegateCompletion=source=>"));
        assertTrue(script.contains("window.__selfRunDriveTurnObserver"));
        assertTrue(script.contains("observer.allowIdleBaseline=true"));
        assertTrue(script.contains("observer.protocolComplete=true"));
        assertTrue(script.contains("observer.protocolSource=safe(source)"));
        assertTrue(script.contains("emitLog('completion_delegate',source)"));
        assertTrue(script.contains("observer.evaluate()"));
        assertFalse(script.contains("COMPLETION_SCHEME"));
        assertFalse(script.contains("COMPLETION_HOST"));
        assertFalse(script.contains("location.href=callback"));
        assertFalse(script.contains("cancelDomFallback"));
        assertFalse(script.contains("suspendDomFallback"));
        assertFalse(script.contains("observer.fired=true"));
        assertFalse(script.contains("observer.observer?.disconnect"));
    }

    @Test public void earlySemanticCompleteLeavesFallbackAlive() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("if(!completionEvidence())"));
        assertTrue(script.contains("state.lastError='completion_without_final_answer_evidence'"));
        assertTrue(script.contains("emitLog('completion_ignored',source)"));
        assertFalse(script.contains("suspendDomFallback(window.__selfRunDriveTurnObserver)"));
        assertFalse(script.contains("cancelDomFallback(window.__selfRunDriveTurnObserver)"));
    }

    @Test public void protocolErrorCannotDisableFallback() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("markError('canonical_http_'+safe(response?.status),sequence)"));
        assertTrue(script.contains("markError('canonical_fetch_rejected',sequence)"));
        assertTrue(script.contains("state.phase='ERROR'"));
        assertFalse(script.contains("suspendDomFallback"));
    }

    @Test public void proAndWorkTransportRejectsInnerAndOuterDoneAsCompletion() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("if(!text||text==='[DONE]')return"));
        assertTrue(script.contains("const acceptSocketPayload=payload=>"));
        assertTrue(script.contains("if(type==='done')return"));
        assertTrue(script.contains("type!=='stream-item'||typeof payload.encoded_item!=='string'"));
        assertTrue(script.contains("observeSseText(payload.encoded_item,'chatgpt-websocket',context)"));
        assertTrue(script.contains("observeSocketFrame,observeWorkFrame:observeSocketFrame"));
        assertFalse(script.contains("complete('work_done')"));
    }

    @Test public void directFinalMessageIsVisibleAnswerEvidence() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("sawAssistantFinalText:false"));
        assertTrue(script.contains("completionEvidence=()=>state.sawVisibleAnswer"));
        assertTrue(script.contains("role!=='assistant'||channel!=='final'"));
        assertTrue(script.contains("state.finalMessageActive=true"));
        assertTrue(script.contains("noteAssistantFinalText('visible_answer')"));
        assertTrue(script.contains("path.includes('/message/content/parts')"));
        assertTrue(script.contains("value.v?.message"));
        assertTrue(script.contains("completionDelegated:state.completionDelegated"));
    }

    @Test public void hiddenFinishedMessagesCannotCompleteTheTurn() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("safe(finalMessage.author?.role).toLowerCase()==='assistant'"));
        assertTrue(script.contains("safe(finalMessage.channel).toLowerCase()==='final'"));
        assertTrue(script.contains("finalMessage.status==='finished_successfully'&&finalMessage.end_turn===true"));
        assertFalse(script.contains("message.status==='finished_successfully'&&message.end_turn===true"));
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
