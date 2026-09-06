package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class ChatGptTurnProtocolScriptTest {
    @Test public void canonicalPostAndSplitDetectorLanesOwnTurnState() {
        assertEquals("turn-protocol-v13",ChatGptTurnProtocolScript.ENGINE_VERSION);
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("path==='/backend-api/f/conversation'"));
        assertTrue(script.contains("detectorLane:'CHAT'"));
        assertTrue(script.contains("const initialLane=()=>targetMode()==='work'?'WORK':'CHAT'"));
        assertTrue(script.contains("const promoteProLane=()=>"));
        assertTrue(script.contains("state.detectorLane='PRO'"));
        assertTrue(script.contains("state.phase='THINKING'"));
        assertTrue(script.contains("state.phase='ANSWERING'"));
        assertTrue(script.contains("value.type==='message_stream_complete'"));
        assertTrue(script.contains("const COMPLETE_SOURCES=new Set(['message_stream_complete','finished_successfully_end_turn'])"));
        assertFalse(script.contains("turnSequence"));
        assertFalse(script.contains("turnKind"));
    }

    @Test public void normalChatRestoresDev16CompletionPolicy() {
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("const requiresFinalEvidence=()=>state.detectorLane!=='CHAT'"));
        assertTrue(script.contains("if(requiresFinalEvidence()&&!completionEvidence())"));
        assertTrue(script.contains("return finalizeComplete(completionSource);"));
        assertTrue(script.contains("const chatLane=state.detectorLane==='CHAT'"));
        assertTrue(script.contains("chatLane?channel==='final'"));
        assertTrue(script.contains("if(chatLane)noteVisibleAnswer('visible_answer')"));
        assertTrue(script.contains("Compact deltas are used only by Work/Pro"));
        assertTrue(script.contains("if(state.detectorLane==='CHAT'||!value"));
    }

    @Test public void proKeepsEarlyBoundarySeparateFromFinalCompletion() {
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("proBoundarySeen:false"));
        assertTrue(script.contains("state.detectorLane==='PRO'&&completionSource==='message_stream_complete'"));
        assertTrue(script.contains("state.proBoundarySeen=true"));
        assertTrue(script.contains("state.lastError='completion_without_final_answer_evidence'"));
        assertTrue(script.contains("emitLog('completion_ignored',completionSource)"));
        assertTrue(script.contains("if(!identity&&workTurnId)promoteProLane()"));
        assertTrue(script.contains("if(state.detectorLane==='CHAT')promoteProLane()"));
    }

    @Test public void nativeBridgeAllowsOnlyAuthoritativeCompletionSources() {
        assertTrue(TurnProtocolLogBridge.isAllowedCompletionSource("message_stream_complete"));
        assertTrue(TurnProtocolLogBridge.isAllowedCompletionSource("finished_successfully_end_turn"));
        assertFalse(TurnProtocolLogBridge.isAllowedCompletionSource("done"));
    }

    @Test public void protocolOwnsTokenAndArmsOnlyAfterNativeWait() {
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("turnToken:''"));
        assertTrue(script.contains("const bindTurn=(run,token)=>"));
        assertTrue(script.contains("const armCompletion=(run,token)=>"));
        assertTrue(script.contains("completionArmed:false"));
        assertTrue(script.contains("selfrun-drive:response-protocol-state:v12"));
        assertFalse(script.contains("__selfRunDriveTurnObserver"));
        assertFalse(script.contains("DomFallback"));
    }

    @Test public void lateAndStaleFramesRemainFencedByActiveTurnOwnership() {
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("identity&&identity!==state.requestIdentity"));
        assertTrue(script.contains("retiredWorkTurnIds.includes(value)"));
        assertTrue(script.contains("if(sameRun)retireWorkTurn(state.currentWorkTurnId)"));
        assertTrue(script.contains("else retiredWorkTurnIds.length=0"));
        assertTrue(script.contains("if(retiredWorkTurnIds.length>8)retiredWorkTurnIds.shift()"));
        assertTrue(script.contains("if(!identity&&(!conversationId||!workTurnId))return false"));
        assertTrue(script.contains("state.lastError='active_turn_overlap'"));
    }

    @Test public void compactDeltaMetadataDoesNotPersistAnswerText() {
        String script=ChatGptTurnProtocolScript.documentStartScript();
        assertTrue(script.contains("lastDeltaPath:''"));
        assertTrue(script.contains("state.currentMessageRole==='assistant'"));
        assertTrue(script.contains("path==='/message/status'||path==='/message/end_turn'"));
        assertTrue(script.contains("state.sawStreamHandoff=true"));
        assertFalse(script.contains("state.answerText="));
    }

    private static String source(String name) throws Exception {
        Path path=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(path))path=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
