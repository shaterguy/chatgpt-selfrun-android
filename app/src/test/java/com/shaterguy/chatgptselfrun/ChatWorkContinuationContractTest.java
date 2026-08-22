package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChatWorkContinuationContractTest {
    @Test public void productionChatUsesAdvancedMenuWithoutSliderMutation() throws Exception {
        String dom = src("SelfRunDom.java");
        String menu = src("ChatReasoningOptionDom.java");
        assertTrue(dom.contains("ChatReasoningOptionDom.inline(chatReasoning, runId)"));
        assertFalse(dom.contains("ChatReasoningDom.inline(chatReasoning, runId)"));
        assertTrue(menu.contains("open-reasoning-sheet"));
        assertTrue(menu.contains("open-advanced-control"));
        assertTrue(menu.contains("open-reasoning-menu"));
        assertTrue(menu.contains("sliderObserved"));
        assertFalse(menu.contains("positive-slider-fallback"));
        assertFalse(menu.contains("set-slider"));
        assertFalse(menu.contains("ArrowRight"));
        assertFalse(menu.contains("new PointerEvent"));
    }

    @Test public void workPreferenceWaitsAreFiniteAndTerminal() throws Exception {
        String preference = src("WorkPreferenceDom.java");
        String service = src("SelfRunService.java");
        assertTrue(preference.contains("calibratedTargetValid"));
        assertTrue(preference.contains("__wpTimeoutMs=20000"));
        assertTrue(preference.contains("'SELECTION_TIMEOUT'"));
        assertTrue(preference.contains("'READBACK_MISMATCH'"));
        assertTrue(service.contains("isWorkPreferenceFailureStatus"));
        assertTrue(service.contains("WORK_MODEL_SELECTION_TIMEOUT"));
        assertTrue(service.contains("WORK_MODEL_READBACK_MISMATCH"));
        assertTrue(service.contains("WORK_REASONING_SELECTION_TIMEOUT"));
        assertTrue(service.contains("WORK_REASONING_READBACK_MISMATCH"));
        assertTrue(service.contains("WORK_PREFERENCE_FAILURE"));
    }

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
