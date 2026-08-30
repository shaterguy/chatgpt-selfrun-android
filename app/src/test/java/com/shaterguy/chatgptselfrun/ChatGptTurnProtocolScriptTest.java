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
    @Test public void canonicalRequestOwnsFirstAndFollowupLifecycle() {
        assertEquals("turn-protocol-v2", ChatGptTurnProtocolScript.ENGINE_VERSION);
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("path==='/backend-api/f/conversation'"));
        assertTrue(script.contains("path==='/backend-api/f/responses'"));
        assertFalse(script.contains("/backend-api/f/conversation/prepare"));
        assertTrue(script.contains("previous==='IDLE'"));
        assertTrue(script.contains("state.turnKind='FIRST_TURN'"));
        assertTrue(script.contains("previous==='COMPLETE'"));
        assertTrue(script.contains("state.turnKind='FOLLOWUP_TURN'"));
        assertTrue(script.contains("else return false"));
        assertTrue(script.contains("state.phase='THINKING'"));
        assertTrue(script.contains("selfrun-drive:turn-protocol-state:v2"));
    }

    @Test public void finalChannelAndSemanticCompletionOwnAnswerPhases() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("value.marker==='user_visible_token'"));
        assertTrue(script.contains("value.marker==='final_channel_token'"));
        assertTrue(script.contains("state.phase='ANSWERING'"));
        assertTrue(script.contains("value.type==='message_stream_complete'"));
        assertTrue(script.contains("message.status==='finished_successfully'&&message.end_turn===true"));
        assertTrue(script.contains("text==='[DONE]'"));
        assertTrue(script.contains("if(!text||text==='[DONE]')return"));
        assertTrue(script.contains("type==='done'"));
        assertTrue(script.contains("payload.encoded_item"));
        assertTrue(script.contains("complete('work_done')"));
    }

    @Test public void semanticCompletionCannotFinishBeforeFinalChannelEvidence() {
        String script = ChatGptTurnProtocolScript.documentStartScript();

        assertTrue(script.contains("if(!state.sawFinalChannelToken)"));
        assertTrue(script.contains("suspendDomFallback(window.__selfRunDriveTurnObserver)"));
        assertTrue(script.contains("state.lastError='completion_before_final_channel'"));
        assertTrue(script.contains("emitLog('completion_ignored',source)"));
        assertTrue(script.contains("state.phase!=='COMPLETE'||state.completionDispatched||!state.sawFinalChannelToken"));
        assertTrue(script.contains("state.phase==='COMPLETE'&&!state.completionDispatched&&state.sawFinalChannelToken"));
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
