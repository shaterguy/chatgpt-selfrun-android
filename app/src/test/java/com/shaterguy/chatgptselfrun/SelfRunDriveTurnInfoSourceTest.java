package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class SelfRunDriveTurnInfoSourceTest {
    @Test public void workProfileIsReadFromPendingDriveCompletion() throws Exception {
        String store = src("SelfRunStore.java");
        String service = src("SelfRunService.java");
        assertTrue(store.contains("DriveSignalParser.workProfile(pendingDriveSignalRaw())"));
        assertTrue(store.contains("return p.valid?p.model:WorkPreferenceDom.TURN_INFO_REWRITE_SENTINEL"));
        assertTrue(store.contains("return p.valid?p.reasoning:WorkPreferenceDom.TURN_INFO_REWRITE_SENTINEL"));
        assertTrue(store.contains("if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion())return;put(\"pendingModel\", value)"));
        assertTrue(store.contains("if(MODE_WORK.equals(mode())&&hasPendingDriveCompletion())return;put(\"pendingReasoning\", value)"));
        assertTrue(service.contains("DriveSignalParser.scan(body,snapshot.runId,0,snapshot.mode)"));
        assertTrue(service.contains("DriveSignalParser.scan(text,snapshot.runId,snapshot.driveSignalCursor,snapshot.mode)"));
    }

    @Test public void invalidDriveProfileUsesRewriteWithoutAssistantControlState() throws Exception {
        String store = src("SelfRunStore.java");
        String work = src("WorkPreferenceDom.java");
        String service = src("SelfRunService.java");
        assertTrue(store.contains("SelfRunProtocol.requestTurnInfoRewrite(runId())"));
        assertTrue(work.contains("TURN_INFO_REWRITE_SENTINEL"));
        assertTrue(work.contains("preferenceBypass"));
        assertFalse(service.contains("PHASE_READ_NEXT_CONTROL"));
        assertTrue(service.contains("PHASE_APPLY_PREFS"));
        assertTrue(service.contains("WorkPreferenceDom.modelForConversation(store.conversationUrl(),store.pendingModel())"));
        assertTrue(service.contains("WorkPreferenceDom.reasoningForConversation(store.conversationUrl(),store.pendingReasoning())"));
        assertTrue(service.contains("SelfRunDom.prepareDriveTurn(store.conversationUrl(),prompt,store.commandMarkerId(),conversationFreshnessToken)"));
        assertFalse(service.contains("TURN_INFO_REWRITE_SENTINEL"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
