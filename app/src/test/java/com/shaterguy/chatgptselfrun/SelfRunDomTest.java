package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class SelfRunDomTest {
    @Test public void runtimeDomContainsNoAssistantResponseObservation() throws Exception {
        String dom = src("SelfRunDom.java");
        assertFalse(dom.contains("readLatestSelfRunControl"));
        assertFalse(dom.contains("observeAssistant"));
        assertFalse(dom.contains("assistantSnapshot"));
        assertFalse(dom.contains("assistantBaselineKey"));
        assertFalse(dom.contains("SELF_RUN_NEXT"));
        assertFalse(dom.contains("data-message-author-role=\\\"assistant\\\""));
        assertFalse(dom.contains("article[data-turn=\\\"assistant\\\"]"));
    }

    @Test public void continuationOnlyStagesComposerAndMarker() {
        String script = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/g/g-p-test/c/conversation123",
                "[2026.08.13 | 22:03:15] [SELF_RUN_CONTINUE SR-20260813-220315-A1B2C3]", "m1");
        assertTrue(script.contains("READY_TO_SUBMIT"));
        assertFalse(script.contains("CONFIRMED"));
        assertFalse(script.contains("assistant"));
    }

    @Test public void generalChatScopeSupportsBootstrapAndAbsoluteWorkPreferences() {
        String initial = SelfRunDom.prepareInitialContext(SelfRunScript.GENERAL_CHAT_URL, SelfRunStore.MODE_CHAT, "SR-20260814-TEST00");
        String model = WorkPreferenceDom.modelForProject(SelfRunScript.GENERAL_CHAT_URL, "sol");
        assertTrue(initial.contains("__GENERAL_CHAT__"));
        assertTrue(initial.contains("일반 Chat 범위 이탈"));
        assertTrue(initial.contains("__selfRunRequestProfileEngine"));
        assertTrue(model.contains("__selfRunRequestProfileEngine"));
        assertTrue(model.contains("setWorkModel"));
        assertTrue(model.contains("uiClicks:0"));
        assertTrue(model.contains("호스트 불일치"));
        assertFalse(model.contains("open-work-mode-fallback"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
