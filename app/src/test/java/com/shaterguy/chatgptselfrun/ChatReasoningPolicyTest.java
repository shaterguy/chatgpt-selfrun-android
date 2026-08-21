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

    @Test public void keepSelectionSkipsChatSliderAutomation() {
        assertEquals("", ChatReasoningDom.inline(ChatReasoningPreferenceStore.KEEP, "SR-TEST"));
    }

    @Test public void chatSliderScriptUsesSemanticReadbackAndFiniteFallbacks() {
        String script = ChatReasoningDom.inline(ChatReasoningPreferenceStore.PRO, "SR-TEST");
        assertTrue(script.contains("aria-valuetext"));
        assertTrue(script.contains("pendingReadback"));
        assertTrue(script.contains("CHAT_REASONING_TRIGGER_NOT_FOUND"));
        assertTrue(script.contains("CHAT_REASONING_SLIDER_NOT_FOUND"));
        assertTrue(script.contains("CHAT_REASONING_OPTION_UNAVAILABLE"));
        assertTrue(script.contains("CHAT_REASONING_READBACK_MISMATCH"));
        assertTrue(script.contains("CHAT_REASONING_MENU_CLOSE_FAILED"));
        assertTrue(script.contains("triggerAttempts"));
        assertTrue(script.contains("sliderAttempts"));
        assertTrue(script.contains("sliderWaitStartedAt"));
        assertTrue(script.contains("menuClickAttempts<2"));
        assertTrue(script.contains("__srcSliderTimeoutMs=24000"));
        assertTrue(script.contains("__srcOverallTimeoutMs=60000"));
        assertTrue(script.contains("__srcMenuRetryMs=4800"));
        assertTrue(script.contains("localStorage.setItem(__srcStateKey"));
        assertTrue(script.contains("action:'script-exception'"));
        assertTrue(script.contains("action:'target-option'"));
        assertTrue(script.contains("action:'option-fallback'"));
        assertTrue(script.contains("aria-keyboard"));
        assertFalse(script.contains("menuClicks<1"));
        assertFalse(script.contains("__srcWantedOrdinal/4"));
        assertFalse(script.contains("open-advanced"));
    }

    @Test public void bootstrapFailureStatusesMapToPreservedPauseMessages() {
        String[] statuses = {
                "CHAT_REASONING_TRIGGER_NOT_FOUND", "CHAT_REASONING_SLIDER_NOT_FOUND",
                "CHAT_REASONING_OPTION_UNAVAILABLE", "CHAT_REASONING_READBACK_MISMATCH",
                "CHAT_REASONING_MENU_CLOSE_FAILED"
        };
        for (String status : statuses) {
            assertTrue(SelfRunService.isChatReasoningFailureStatus(status));
            assertFalse(SelfRunService.chatReasoningFailureMessage(status).isEmpty());
        }
        assertFalse(SelfRunService.isChatReasoningFailureStatus("UI_WAIT"));
    }

    @Test public void newTaskBootstrapAndStatusUiAreWiredWithoutChangingWorkContinuation() throws Exception {
        String activity = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunNewActivity.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunNewActivity.java");
        String dom = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java");
        String service = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        String main = read("app/src/main/java/com/shaterguy/chatgptselfrun/MainActivity.java",
                "src/main/java/com/shaterguy/chatgptselfrun/MainActivity.java");
        String detail = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunDetailActivity.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunDetailActivity.java");
        assertTrue(activity.contains("일반 Chat 추론 정도"));
        assertTrue(activity.contains("ChatReasoningPreferenceStore.save"));
        assertTrue(dom.contains("ChatReasoningPreferenceStore.selectionForRun"));
        assertTrue(dom.contains("ChatReasoningDom.inline"));
        assertTrue(service.contains("SelfRunStore.PHASE_BOOTSTRAP.equals(phase)&&isChatReasoningFailureStatus(status)"));
        assertTrue(service.contains("runLog.record(store,\"DOM_RESULT\",\"phase=BOOTSTRAP;status=\"+status)"));
        assertTrue(service.contains("enterPreservedPause(status,status+\" · \"+message,false)"));
        assertTrue(service.contains("SelfRunStore.MODE_WORK.equals(store.mode())?SelfRunStore.PHASE_APPLY_PREFS:SelfRunStore.PHASE_SEND_CONTINUE"));
        assertTrue(main.contains("ChatReasoningPreferenceStore.summary"));
        assertTrue(detail.contains("ChatReasoningPreferenceStore.summary"));
        assertFalse(service.contains("ChatReasoningDom"));
    }

    private static String read(String first, String second) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
