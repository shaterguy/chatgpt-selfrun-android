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
        assertTrue(service.contains("DriveSignalParser.scan(body,snapshot.runId,0,snapshot.mode)"));
        assertTrue(service.contains("DriveSignalParser.scan(text,snapshot.runId,snapshot.driveSignalCursor,snapshot.mode)"));
    }

    @Test public void serviceHasNoConversationControlHop() throws Exception {
        String service = src("SelfRunService.java");
        assertFalse(service.contains("PHASE_READ_NEXT_CONTROL"));
        assertFalse(service.contains("readLatestSelfRunControl"));
        assertFalse(service.contains("applyNextControl"));
        assertFalse(service.contains("SELF_RUN_NEXT"));
        assertTrue(service.contains("MODE_WORK.equals(store.mode())?SelfRunStore.PHASE_APPLY_PREFS:SelfRunStore.PHASE_SEND_CONTINUE"));
        assertTrue(service.contains("WorkPreferenceDom.modelForConversation(store.conversationUrl(),store.pendingModel())"));
        assertTrue(service.contains("WorkPreferenceDom.reasoningForConversation(store.conversationUrl(),store.pendingReasoning())"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
