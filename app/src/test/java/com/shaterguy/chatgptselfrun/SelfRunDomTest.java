package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class SelfRunDomTest {
    @Test public void driveDomNeverReadsAssistantProgress() throws Exception {
        String dom = src("SelfRunDom.java");
        assertFalse(dom.contains("SELF_RUN_NEXT"));
        assertFalse(dom.contains("readLatestSelfRunControl"));
        assertFalse(dom.contains("observeAssistant"));
        assertFalse(dom.contains("data-message-author-role=\"assistant\""));
        assertFalse(dom.contains("article[data-turn=\"assistant\"]"));
    }

    @Test public void continuationOnlyStagesComposerAndMarker() {
        String script = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/g/g-p-test/c/conversation123",
                "[2026.08.13 | 22:03:15] [SELF_RUN_CONTINUE SR-20260813-220315-A1B2C3]", "m1");
        assertTrue(script.contains("READY_TO_SUBMIT"));
        assertFalse(script.contains("CONFIRMED"));
        assertFalse(script.contains("assistant"));
    }

    @Test public void generalChatScopeSupportsBootstrapAndWorkPreferences() {
        String initial = SelfRunDom.prepareInitialContext(SelfRunScript.GENERAL_CHAT_URL, SelfRunStore.MODE_CHAT, "SR-20260814-TEST00");
        String model = WorkPreferenceDom.modelForProject(SelfRunScript.GENERAL_CHAT_URL, "sol");
        assertTrue(initial.contains("__GENERAL_CHAT__"));
        assertTrue(initial.contains("일반 Chat 범위 이탈"));
        assertTrue(model.contains("__GENERAL_CHAT__"));
        assertTrue(model.contains("일반 Chat 범위 이탈"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
