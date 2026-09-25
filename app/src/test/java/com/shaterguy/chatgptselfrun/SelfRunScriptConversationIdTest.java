package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SelfRunScriptConversationIdTest {
    @Test public void canonicalConversationIdIsAccepted() {
        String id = "6ab65275-7f9c-83e8-8a58-0e02ce714138";
        assertEquals(id, SelfRunScript.conversationId("https://chatgpt.com/c/" + id));
        assertTrue(SelfRunScript.isGeneralChatUrl("https://chatgpt.com/c/" + id));
    }

    @Test public void localChatgptPlaceholderIsRejected() {
        assertEquals("", SelfRunScript.conversationId("https://chatgpt.com/c/local-chatgpt"));
        assertFalse(SelfRunScript.isGeneralChatUrl("https://chatgpt.com/c/local-chatgpt"));
    }
}
