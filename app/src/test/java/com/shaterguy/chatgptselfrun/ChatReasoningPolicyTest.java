package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class ChatReasoningPolicyTest {
    @Test public void sevenChatReasoningSelectionsMapLeftToRight() {
        assertEquals(0, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.INSTANT));
        assertEquals(1, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.MEDIUM));
        assertEquals(2, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.HIGH));
        assertEquals(3, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.EXTRA_HIGH));
        assertEquals(4, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.PRO));
        assertEquals(5, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.PRO_STANDARD));
        assertEquals(6, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.PRO_EXTENDED));
        assertEquals(ChatReasoningPreferenceStore.PRO, ChatReasoningPreferenceStore.normalize("pro"));
        assertEquals(-1, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.KEEP));
    }

    @Test public void keepSelectionIsNotSilentlyGuessedForChat() {
        assertEquals("", ChatReasoningDom.inline(ChatReasoningPreferenceStore.KEEP, "SR-TEST"));
        String script = ChatReasoningOptionDom.inline(ChatReasoningPreferenceStore.KEEP, "SR-TEST");
        assertFalse(script.isEmpty());
        assertTrue(script.contains("CHAT_REASONING_OPTION_UNAVAILABLE"));
        assertTrue(script.contains("explicit captured reasoning profile"));
        assertFalse(script.contains("click()"));
    }

    @Test public void legacySliderAdapterRemainsFiniteButIsNotTheProductionPath() {
        String script = ChatReasoningDom.inline(ChatReasoningPreferenceStore.EXTRA_HIGH, "SR-LEGACY");
        assertTrue(script.contains("CHAT_REASONING_SLIDER_NOT_FOUND"));
        assertTrue(script.contains("CHAT_REASONING_READBACK_MISMATCH"));
        assertTrue(script.contains("__srcOverallTimeoutMs=60000"));
    }

    @Test public void capturedReasoningStagesProfileWithoutMenuInteraction() {
        String script = ChatReasoningOptionDom.inline(ChatReasoningPreferenceStore.EXTRA_HIGH, "SR-PROFILE");
        assertTrue(script.contains("__selfRunRequestProfileEngine"));
        assertTrue(script.contains("setChatReasoning"));
        assertTrue(script.contains("profile-ready"));
        assertTrue(script.contains("uiClicks:0"));
        assertFalse(script.contains("open-reasoning-sheet"));
        assertFalse(script.contains("open-advanced-control"));
        assertFalse(script.contains("nested-option-click"));
        assertFalse(script.contains("querySelectorAll"));
        assertFalse(script.contains("new KeyboardEvent"));
    }

    @Test public void uncapturedChatProFailsClosed() {
        String script = ChatReasoningOptionDom.inline(ChatReasoningPreferenceStore.PRO_EXTENDED, "SR-PRO");
        assertTrue(script.contains("CHAT_REASONING_OPTION_UNAVAILABLE"));
        assertTrue(script.contains("uncaptured in 2.0.0-dev1"));
        assertTrue(script.contains("proCaptured:false"));
        assertFalse(script.contains("setChatReasoning"));
    }

    @Test public void bootstrapFailureStatusesMapToPreservedPauseMessages() {
        String[] statuses = {
                "CHAT_REASONING_TRIGGER_NOT_FOUND", "CHAT_REASONING_SLIDER_NOT_FOUND",
                "CHAT_REASONING_ADVANCED_CONTROL_NOT_FOUND", "CHAT_REASONING_OPTION_UNAVAILABLE",
                "CHAT_REASONING_READBACK_MISMATCH", "CHAT_REASONING_MENU_CLOSE_FAILED"
        };
        for (String status : statuses) {
            assertTrue(SelfRunService.isChatReasoningFailureStatus(status));
            assertFalse(SelfRunService.chatReasoningFailureMessage(status).isEmpty());
        }
        assertFalse(SelfRunService.isChatReasoningFailureStatus("UI_WAIT"));
    }

    @Test public void newTaskBootstrapUsesOnlyRequestProfileBridges() throws Exception {
        String dom = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java");
        String mode = read("app/src/main/java/com/shaterguy/chatgptselfrun/BootstrapModeDom.java",
                "src/main/java/com/shaterguy/chatgptselfrun/BootstrapModeDom.java");
        String service = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        assertTrue(dom.contains("BootstrapModeDom.inline(requested, runId)"));
        assertTrue(dom.contains("ChatReasoningOptionDom.inline(chatReasoning, runId)"));
        assertFalse(dom.contains("ChatReasoningDom.inline(chatReasoning, runId)"));
        assertTrue(service.contains("BootstrapRunStateStore.touchBootstrap"));
        assertTrue(service.contains("BootstrapResultPolicy.fatalStatus"));
        assertTrue(service.contains("SelfRunStore.MODE_WORK.equals(store.mode())?SelfRunStore.PHASE_BOOTSTRAP_MODEL:SelfRunStore.PHASE_BOOTSTRAP_SEND"));
        assertFalse(service.contains("ChatReasoningDom"));
        assertTrue(mode.contains("__selfRunRequestProfileEngine"));
        assertTrue(mode.contains("CHAT_BOOTSTRAP_PROFILE_ENGINE_UNAVAILABLE"));
        assertFalse(mode.contains("dispatchModeMouse"));
    }

    private static String read(String first, String second) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
