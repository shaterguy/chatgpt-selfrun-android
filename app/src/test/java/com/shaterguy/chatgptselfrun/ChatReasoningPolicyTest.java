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

    @Test public void chatSliderScriptUsesOneMenuOpenAndNeverTraversesAdvanced() {
        String script = ChatReasoningDom.inline(ChatReasoningPreferenceStore.PRO, "SR-TEST");
        assertTrue(script.contains("[role=\"slider\"]"));
        assertTrue(script.contains("input[type=\"range\"]"));
        assertTrue(script.contains("aria-valuemin"));
        assertTrue(script.contains("aria-valuemax"));
        assertTrue(script.contains("aria-valuenow"));
        assertTrue(script.contains("menuClicks<1"));
        assertTrue(script.contains("전송 차단"));
        assertFalse(script.contains("open-advanced"));
        assertFalse(script.contains("select-advanced"));
    }

    @Test public void newTaskAndBootstrapAreWiredWithoutChangingWorkContinuation() throws Exception {
        String activity = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunNewActivity.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunNewActivity.java");
        String dom = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java");
        String service = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        assertTrue(activity.contains("일반 Chat 추론 정도"));
        assertTrue(activity.contains("ChatReasoningPreferenceStore.save"));
        assertTrue(dom.contains("ChatReasoningPreferenceStore.selectionForRun"));
        assertTrue(dom.contains("ChatReasoningDom.inline"));
        assertTrue(service.contains("SelfRunStore.MODE_WORK.equals(store.mode())?SelfRunStore.PHASE_APPLY_PREFS:SelfRunStore.PHASE_SEND_CONTINUE"));
        assertFalse(service.contains("ChatReasoningDom"));
    }

    private static String read(String first, String second) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
