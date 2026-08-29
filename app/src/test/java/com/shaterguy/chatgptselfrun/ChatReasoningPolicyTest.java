package com.shaterguy.chatgptselfrun;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class ChatReasoningPolicyTest {
    @Before public void reset() { ProfileRegistry.resetForTests(); }
    @After public void cleanup() { ProfileRegistry.resetForTests(); }

    @Test public void capturedChatRegistryDefinesDefaultLeftToRightOrderWithoutProGuesses() {
        assertEquals(0, ChatReasoningPreferenceStore.ordinal("instant"));
        assertEquals(1, ChatReasoningPreferenceStore.ordinal("medium"));
        assertEquals(2, ChatReasoningPreferenceStore.ordinal("high"));
        assertEquals(3, ChatReasoningPreferenceStore.ordinal("xhigh"));
        assertEquals(-1, ChatReasoningPreferenceStore.ordinal("pro"));
        assertEquals("pro", ChatReasoningPreferenceStore.normalize(" Pro "));
        assertFalse(ChatReasoningPreferenceStore.shouldApply("pro"));
    }

    @Test public void keepSelectionIsNotSilentlyGuessedForChat() {
        String script = ChatReasoningOptionDom.inline(ChatReasoningPreferenceStore.KEEP, "SR-TEST");
        assertTrue(script.contains("CHAT_REASONING_OPTION_UNAVAILABLE"));
        assertTrue(script.contains("explicit registered reasoning profile"));
        assertFalse(script.contains("click()"));
    }

    @Test public void registeredReasoningStagesBootstrapAndContinuationProfilesWithoutMenuInteraction() {
        String script = ChatReasoningOptionDom.inline(ChatReasoningPreferenceStore.EXTRA_HIGH, "SR-PROFILE");
        assertTrue(script.contains("__selfRunRequestProfileEngine"));
        assertTrue(script.contains("installRegistry"));
        assertTrue(script.contains("setChatProfiles"));
        assertTrue(script.contains("profile-ready"));
        assertTrue(script.contains("uiClicks:0"));
        assertFalse(script.contains("querySelectorAll"));
        assertFalse(script.contains("click()"));
    }

    @Test public void deletedOrUnknownChatSignalFailsClosed() {
        String script = ChatReasoningOptionDom.inline("pro", "SR-PRO");
        assertTrue(script.contains("CHAT_REASONING_OPTION_UNAVAILABLE"));
        assertTrue(script.contains("Unsupported or deleted Chat bootstrap reasoning target"));
        assertFalse(script.contains("setChatProfiles"));
    }

    @Test public void productionBootstrapUsesOnlyRequestProfileBridges() throws Exception {
        String dom = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java");
        String service = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java");
        String activity = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunNewActivity.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunNewActivity.java");
        String script = read("app/src/main/java/com/shaterguy/chatgptselfrun/RequestProfileScript.java",
                "src/main/java/com/shaterguy/chatgptselfrun/RequestProfileScript.java");
        assertTrue(dom.contains("ChatReasoningOptionDom.inline(chatReasoning, runId)"));
        assertFalse(dom.contains("ChatReasoningDom.inline(chatReasoning, runId)"));
        assertFalse(service.contains("ChatReasoningDom"));
        assertTrue(activity.contains("ProfileRegistry.listChat()"));
        assertTrue(activity.contains("부트스트랩 전용 추론 정도"));
        assertTrue(activity.contains("ChatReasoningPreferenceStore.save(this, runId, bootstrapReasoning, continuationReasoning)"));
        assertTrue(script.contains("setChatProfiles"));
        assertTrue(script.contains("latestMessageText"));
        assertTrue(script.contains("SELF_RUN_BOOTSTRAP"));
        assertFalse(activity.contains("PRO_STANDARD"));
        assertFalse(activity.contains("PRO_EXTENDED"));
        assertFalse(activity.contains("Pro · 최고 성능"));
    }

    private static String read(String first, String second) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
