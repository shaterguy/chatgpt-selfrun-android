package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class ChatGptTurnProtocolScriptTest {
    @Test public void canonicalPostAndProtocolSemanticsOwnTurnState() {
        assertEquals("turn-protocol-v8",ChatGptTurnProtocolScript.ENGINE_VERSION);
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("path==='/backend-api/f/conversation'"));
        assertTrue(script.contains("state.phase='THINKING'"));
        assertTrue(script.contains("state.phase='ANSWERING'"));
        assertTrue(script.contains("value.type==='message_stream_complete'"));
        assertTrue(script.contains("finished_successfully_end_turn"));
        assertFalse(script.contains("turnSequence"));
        assertFalse(script.contains("turnKind"));
    }
    @Test public void protocolOwnsTokenAndArmsOnlyAfterNativeWait() {
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("turnToken:''"));
        assertTrue(script.contains("const bindTurn=(run,token)=>"));
        assertTrue(script.contains("const armCompletion=(run,token)=>"));
        assertTrue(script.contains("completionArmed:false"));
        assertTrue(script.contains("selfrun-drive:response-protocol-state:v8"));
        assertFalse(script.contains("__selfRunDriveTurnObserver"));
        assertFalse(script.contains("DomFallback"));
    }
    @Test public void lateAndStaleFramesAreFenced() {
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("identity&&identity!==state.requestIdentity"));
        assertTrue(script.contains("context.requestIdentity!==state.requestIdentity"));
        assertTrue(script.contains("retiredWorkTurnIds.includes(value)"));
        assertTrue(script.contains("if(sameRun)retireWorkTurn(state.currentWorkTurnId)"));
        assertTrue(script.contains("else retiredWorkTurnIds.length=0"));
        assertTrue(script.contains("if(retiredWorkTurnIds.length>8)retiredWorkTurnIds.shift()"));
        assertFalse(script.contains("completeAfterLateEvidence"));
        assertTrue(script.contains("state.sawTerminalComplete&&state.sawVisibleAnswer&&!!state.currentFinalMessageId"));
        assertTrue(script.contains("if(!identity&&(!safe(context?.conversationId||'')||!safe(context?.workTurnId||'')))return false"));
        assertTrue(script.contains("state.lastError='active_turn_overlap'"));
    }
    private static String source(String name) throws Exception {
        Path path=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(path))path=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
