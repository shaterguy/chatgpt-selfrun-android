package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class SelfRunGeneralChatPolicyTest {
    @Test public void rootAndConversationShareGeneralChatScope() {
        assertEquals(SelfRunScript.GENERAL_CHAT_SCOPE, SelfRunScript.projectId("https://chatgpt.com/"));
        assertEquals(SelfRunScript.GENERAL_CHAT_SCOPE, SelfRunScript.projectId("https://chatgpt.com/c/conversation123"));
        assertEquals(SelfRunScript.GENERAL_CHAT_SCOPE, SelfRunScript.projectId("https://www.chatgpt.com/c/conversation123"));
        assertEquals("conversation123", SelfRunScript.conversationId("https://chatgpt.com/c/conversation123"));
        assertEquals("conversation123", SelfRunScript.conversationId("https://www.chatgpt.com/c/conversation123?temporary=1#state"));
        assertEquals("g-p-test", SelfRunScript.projectId("https://chatgpt.com/g/g-p-test/c/conversation123"));
        assertEquals("conversation123", SelfRunScript.conversationId("https://chatgpt.com/g/g-p-test/c/conversation123"));
        assertTrue(SelfRunScript.isGeneralChatUrl("https://chatgpt.com/"));
        assertTrue(SelfRunScript.isGeneralChatUrl("https://chatgpt.com/c/conversation123"));
        assertTrue(SelfRunScript.isGeneralChatUrl("https://www.chatgpt.com/c/conversation123"));
        assertTrue(SelfRunScript.isGeneralChatUrl("https://chatgpt.com/c/conversation123?temporary=1#state"));
        assertFalse(SelfRunScript.isGeneralChatUrl("https://chatgpt.com/g/g-p-test"));
        assertFalse(SelfRunScript.isGeneralChatUrl("https://chatgpt.com/settings"));
        assertFalse(SelfRunScript.isGeneralChatUrl("https://example.com/c/conversation123"));
    }

    @Test public void generalConversationComparisonIsStrictAndProjectAware() {
        assertTrue(ProjectUrlPolicy.sameConversation("https://chatgpt.com/c/conversation123", "https://chatgpt.com/c/conversation123"));
        assertTrue(ProjectUrlPolicy.sameConversation("https://chatgpt.com/c/conversation123", "https://www.chatgpt.com/c/conversation123?temporary=1#state"));
        assertFalse(ProjectUrlPolicy.sameConversation("https://chatgpt.com/c/conversation123", "https://chatgpt.com/c/conversation456"));
        assertFalse(ProjectUrlPolicy.sameConversation("https://chatgpt.com/c/conversation123", "https://chatgpt.com/g/g-p-test/c/conversation123"));
        assertTrue(ProjectUrlPolicy.sameConversation("https://chatgpt.com/g/g-p-test/c/conversation123", "https://chatgpt.com/g/g-p-test/c/conversation123"));
    }

    @Test public void generalConversationIdRejectsUnsafeRoutesButIgnoresProviderState() {
        assertEquals("", SelfRunScript.conversationId("https://chatgpt.com/c/abc%2Fdef"));
        assertEquals("abc", SelfRunScript.conversationId("https://chatgpt.com/c/abc?x=1"));
        assertEquals("", SelfRunScript.conversationId("https://evil.example/c/conversation123"));
        assertEquals("", SelfRunScript.conversationId("https://chatgpt.com/settings?c=conversation123"));
    }

    @Test public void conversationCapturePolicySupportsGeneralAndProjectRoutes() {
        assertTrue(SelfRunStore.canCaptureConversationUrl(SelfRunScript.GENERAL_CHAT_URL, "https://chatgpt.com/c/conversation123"));
        assertTrue(SelfRunStore.canCaptureConversationUrl(SelfRunScript.GENERAL_CHAT_URL, "https://www.chatgpt.com/c/conversation123?temporary=1#state"));
        assertFalse(SelfRunStore.canCaptureConversationUrl(SelfRunScript.GENERAL_CHAT_URL, "https://chatgpt.com/"));
        assertFalse(SelfRunStore.canCaptureConversationUrl(SelfRunScript.GENERAL_CHAT_URL, "https://chatgpt.com/settings"));
        assertFalse(SelfRunStore.canCaptureConversationUrl(SelfRunScript.GENERAL_CHAT_URL, "https://chatgpt.com/g/g-p-test/c/conversation123"));
        assertFalse(SelfRunStore.canCaptureConversationUrl(SelfRunScript.GENERAL_CHAT_URL, "https://evil.example/c/conversation123"));
        assertTrue(SelfRunStore.canCaptureConversationUrl("https://chatgpt.com/g/g-p-test/project", "https://chatgpt.com/g/g-p-test/c/conversation123"));
        assertFalse(SelfRunStore.canCaptureConversationUrl("https://chatgpt.com/g/g-p-test/project", "https://chatgpt.com/g/g-p-other/c/conversation123"));
        assertFalse(SelfRunStore.canCaptureConversationUrl("https://chatgpt.com/g/g-p-test/project", "https://chatgpt.com/c/conversation123"));
    }

    @Test public void captureLogRequiresPersistedConversationRoute() throws Exception {
        String service = compact(src("SelfRunService.java"));
        assertTrue(service.contains("store.captureConversationUrl(url);if(sameConversation(store.conversationUrl(),url))"));
        assertTrue(service.contains("trusted_general_route"));
        assertTrue(service.contains("trusted_project_route"));
    }

    @Test public void generalChatIsSelectedFromTheFixedSafeOption() throws Exception {
        String activity = src("SelfRunNewActivity.java");
        assertTrue(activity.contains("position<=0?SelfRunScript.GENERAL_CHAT_URL"));
        assertTrue(activity.contains("store.setDefaultProjectUrl(project);"));
        assertFalse(activity.contains("EditText project"));
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
