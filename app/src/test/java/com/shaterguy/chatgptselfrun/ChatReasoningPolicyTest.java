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

    @Test public void keepSelectionCapturesCurrentPopoverWithoutMovingSlider() {
        assertEquals("", ChatReasoningDom.inline(ChatReasoningPreferenceStore.KEEP, "SR-TEST"));
        String script = ChatReasoningOptionDom.inline(ChatReasoningPreferenceStore.KEEP, "SR-TEST");
        assertFalse(script.isEmpty());
        assertTrue(script.contains("__sroCaptureOnly=true"));
        assertTrue(script.contains("strategy:'slider-model-popover'"));
        assertTrue(script.contains("open-picker-for-capture"));
        assertTrue(script.contains("capture-current"));
        assertTrue(script.contains("wait-capture-readback"));
    }

    @Test public void currentProductionPathDirectlyMutatesSliderAndDoesNotRequireAdvanced() {
        String script = ChatReasoningOptionDom.inline(ChatReasoningPreferenceStore.EXTRA_HIGH, "SR-CURRENT");
        assertTrue(script.contains("strategy:'slider-model-popover'"));
        assertTrue(script.contains("open-reasoning-popover"));
        assertTrue(script.contains("set-slider"));
        assertTrue(script.contains("slider-pointer-fallback"));
        assertTrue(script.contains("ArrowRight"));
        assertTrue(script.contains("[role=\"slider\"]"));
        assertFalse(script.contains("open-advanced-control"));
        assertFalse(script.contains("__sroShowAdvancedLabel"));
        assertFalse(script.contains("CHAT_REASONING_ADVANCED_CONTROL_NOT_FOUND"));
    }

    @Test public void proSelectionsUseModelMenuThenDirectProSlider() {
        String script = ChatReasoningOptionDom.inline(ChatReasoningPreferenceStore.PRO_EXTENDED, "SR-PRO");
        assertTrue(script.contains("pro_standard"));
        assertTrue(script.contains("pro_extended"));
        assertTrue(script.contains("open-model-menu"));
        assertTrue(script.contains("select-model"));
        assertTrue(script.contains("targetModel"));
        assertTrue(script.contains("set-slider"));
    }

    @Test public void legacySliderAdapterRemainsFiniteButIsNotTheProductionPath() {
        String script = ChatReasoningDom.inline(ChatReasoningPreferenceStore.EXTRA_HIGH, "SR-LEGACY");
        assertTrue(script.contains("CHAT_REASONING_SLIDER_NOT_FOUND"));
        assertTrue(script.contains("CHAT_REASONING_READBACK_MISMATCH"));
        assertTrue(script.contains("__srcOverallTimeoutMs=60000"));
    }

    @Test public void menuCloseRecoveryNeverReloadsTheConversation() {
        String script = ChatReasoningOptionDom.inline(ChatReasoningPreferenceStore.EXTRA_HIGH, "SR-CLOSE");
        assertTrue(script.contains("const __sroClose=()=>"));
        assertTrue(script.contains("return'trigger'"));
        assertTrue(script.contains("return'escape'"));
        assertTrue(script.contains("new KeyboardEvent('keydown'"));
        assertTrue(script.contains("new KeyboardEvent('keyup'"));
        assertFalse(script.contains("location.reload"));
        assertFalse(script.contains("window.location"));
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

    @Test public void newTaskBootstrapUsesCurrentPopoverAdapter() throws Exception {
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
        assertTrue(mode.contains("CHAT_BOOTSTRAP_MODE_CONTROL_NOT_FOUND"));
        assertTrue(mode.contains("modeTimeoutMs=20000"));
    }

    private static String read(String first, String second) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
