package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class ChatGptTurnProtocolScriptTest {
    @Test public void canonicalPostAndProtocolSemanticsOwnTurnState() {
        assertEquals("turn-protocol-v11",ChatGptTurnProtocolScript.ENGINE_VERSION);
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("path==='/backend-api/f/conversation'"));
        assertTrue(script.contains("state.phase='THINKING'"));
        assertTrue(script.contains("state.phase='ANSWERING'"));
        assertTrue(script.contains("value.type==='message_stream_complete'"));
        assertTrue(script.contains("const COMPLETE_SOURCES=new Set(['message_stream_complete'])"));
        assertFalse(script.contains("finished_successfully_end_turn"));
        assertTrue(script.contains("role==='assistant'&&(channel===''||channel==='final')"));
        assertTrue(script.contains("if(parts.some(nonEmptyText))"));
        assertFalse(script.contains("turnSequence"));
        assertFalse(script.contains("turnKind"));
    }

    @Test public void nativeBridgeAllowsOnlyAuthoritativeStreamCompletion() {
        assertTrue(TurnProtocolLogBridge.isAllowedCompletionSource("message_stream_complete"));
        assertFalse(TurnProtocolLogBridge.isAllowedCompletionSource("finished_successfully_end_turn"));
        assertFalse(TurnProtocolLogBridge.isAllowedCompletionSource("done"));
    }

    @Test public void protocolOwnsTokenAndArmsOnlyAfterNativeWait() {
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("turnToken:''"));
        assertTrue(script.contains("const bindTurn=(run,token)=>"));
        assertTrue(script.contains("const armCompletion=(run,token)=>"));
        assertTrue(script.contains("completionArmed:false"));
        assertTrue(script.contains("selfrun-drive:response-protocol-state:v11"));
        assertFalse(script.contains("__selfRunDriveTurnObserver"));
        assertFalse(script.contains("DomFallback"));
    }

    @Test public void streamCompletionRequiresFinalAnswerEvidence() {
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("if(value.type==='message_stream_complete')"));
        assertTrue(script.contains("complete('message_stream_complete');return;"));
        assertTrue(script.contains("const completionEvidence=()=>state.sawFinalChannelToken||state.sawAssistantFinalText"));
        assertTrue(script.contains("if(!completionEvidence())"));
        assertTrue(script.contains("state.lastError='completion_without_final_answer_evidence'"));
        assertTrue(script.contains("emitLog('completion_ignored',completionSource)"));
        assertTrue(script.contains("return finalizeComplete(completionSource);"));
        assertFalse(script.contains("completeAfterLateEvidence"));
    }

    @Test public void lateAndStaleFramesRemainFencedByActiveTurnOwnership() {
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("identity&&identity!==state.requestIdentity"));
        assertTrue(script.contains("context.requestIdentity!==state.requestIdentity"));
        assertTrue(script.contains("retiredWorkTurnIds.includes(value)"));
        assertTrue(script.contains("if(sameRun)retireWorkTurn(state.currentWorkTurnId)"));
        assertTrue(script.contains("else retiredWorkTurnIds.length=0"));
        assertTrue(script.contains("if(retiredWorkTurnIds.length>8)retiredWorkTurnIds.shift()"));
        assertTrue(script.contains("if(!identity&&(!safe(context?.conversationId||'')||!safe(context?.workTurnId||'')))return false"));
        assertTrue(script.contains("state.lastError='active_turn_overlap'"));
    }

    @Test public void compactDeltaMetadataDoesNotPersistAnswerText() {
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("lastDeltaPath:''"));
        assertTrue(script.contains("state.currentMessageRole==='assistant'"));
        assertTrue(script.contains("state.sawStreamHandoff=true"));
        assertFalse(script.contains("state.answerText="));
    }

    private static String source(String name) throws Exception {
        Path path=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(path))path=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
