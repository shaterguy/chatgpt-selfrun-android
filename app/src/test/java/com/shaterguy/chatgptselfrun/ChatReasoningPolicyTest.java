package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class ChatReasoningPolicyTest {
    @Test public void fiveChatReasoningSelectionsMapLeftToRight() {
        assertEquals(0, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.INSTANT));
        assertEquals(1, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.MEDIUM));
        assertEquals(2, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.HIGH));
        assertEquals(3, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.EXTRA_HIGH));
        assertEquals(4, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.PRO));
        assertEquals(-1, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.KEEP));
    }

    @Test public void keepSelectionSkipsChatReasoningAutomation() {
        assertEquals("", ChatReasoningDom.inline(ChatReasoningPreferenceStore.KEEP, "SR-TEST"));
        assertEquals("", ChatReasoningOptionDom.inline(ChatReasoningPreferenceStore.KEEP, "SR-TEST"));
    }

    @Test public void legacySliderAdapterRemainsFiniteButIsNotTheProductionPath() {
        String script = ChatReasoningDom.inline(ChatReasoningPreferenceStore.PRO, "SR-LEGACY");
        assertTrue(script.contains("CHAT_REASONING_SLIDER_NOT_FOUND"));
        assertTrue(script.contains("CHAT_REASONING_READBACK_MISMATCH"));
        assertTrue(script.contains("__srcOverallTimeoutMs=60000"));
    }

    @Test public void advancedMenuScriptObservesSheetAndNeverMutatesSlider() {
        String script = ChatReasoningOptionDom.inline(ChatReasoningPreferenceStore.PRO, "SR-ADVANCED");
        assertTrue(script.contains("strategy:'advanced-menu'"));
        assertTrue(script.contains("open-reasoning-sheet"));
        assertTrue(script.contains("open-advanced-control"));
        assertTrue(script.contains("__sroShowAdvancedLabel"));
        assertTrue(script.contains("close-current-match"));
        assertTrue(script.contains("open-reasoning-menu"));
        assertTrue(script.contains("nested-option-click"));
        assertTrue(script.contains("direct-option-click"));
        assertTrue(script.contains("sliderObserved"));
        assertTrue(script.contains("CHAT_REASONING_ADVANCED_CONTROL_NOT_FOUND"));
        assertTrue(script.contains("CHAT_REASONING_OPTION_UNAVAILABLE"));
        assertTrue(script.contains("CHAT_REASONING_READBACK_MISMATCH"));
        assertFalse(script.contains("positive-slider-fallback"));
        assertFalse(script.contains("set-slider"));
        assertFalse(script.contains("ArrowRight"));
        assertFalse(script.contains("new PointerEvent"));
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

    @Test public void newTaskBootstrapUsesOnlyTheAdvancedMenuAdapter() throws Exception {
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
        assertTrue(service.contains("SelfRunStore.MODE_WORK.equals(store.mode())?SelfRunStore.PHASE_APPLY_PREFS:SelfRunStore.PHASE_SEND_CONTINUE"));
        assertFalse(service.contains("ChatReasoningDom"));
        assertTrue(mode.contains("CHAT_BOOTSTRAP_MODE_CONTROL_NOT_FOUND"));
        assertTrue(mode.contains("modeTimeoutMs=20000"));
    }

    private static String read(String first, String second) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
