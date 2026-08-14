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
        assertEquals("g-p-test", SelfRunScript.projectId("https://chatgpt.com/g/g-p-test/c/conversation123"));
        assertTrue(SelfRunScript.isGeneralChatUrl("https://chatgpt.com/"));
        assertTrue(SelfRunScript.isGeneralChatUrl("https://www.chatgpt.com/c/conversation123"));
        assertFalse(SelfRunScript.isGeneralChatUrl("https://chatgpt.com/g/g-p-test"));
        assertFalse(SelfRunScript.isGeneralChatUrl("https://chatgpt.com/settings"));
        assertFalse(SelfRunScript.isGeneralChatUrl("https://example.com/c/conversation123"));
    }

    @Test public void generalChatIsAlwaysFirstAndMapsInternallyToGeneralChatRoot() throws Exception {
        String activity = src("SelfRunNewActivity.java");
        assertTrue(activity.contains("GENERAL_CHAT_LABEL = \"일반채팅\""));
        assertTrue(activity.contains("choices.add(new ProjectCatalog.Entry(GENERAL_CHAT_LABEL,\"\"))"));
        assertTrue(activity.contains("projectCanonical.isEmpty()?SelfRunScript.GENERAL_CHAT_URL:projectCanonical"));
        assertTrue(activity.contains("store.setDefaultProjectUrl(projectCanonical)"));
        assertFalse(activity.contains("private EditText projectUrl;"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
