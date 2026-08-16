package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ChatRoutePolicyTest {
    @Test public void parsesGeneralNewAndConversationRoutes() {
        ChatRoutePolicy.Route root = ChatRoutePolicy.parse("https://chatgpt.com/");
        ChatRoutePolicy.Route conversation = ChatRoutePolicy.parse("https://chatgpt.com/c/chat_123");
        assertNotNull(root); assertTrue(root.general); assertFalse(root.hasConversation());
        assertEquals("https://chatgpt.com/", root.canonicalNewUrl);
        assertNotNull(conversation); assertTrue(conversation.general);
        assertEquals("chat_123", conversation.conversationId);
        assertTrue(ChatRoutePolicy.sameScope("https://chatgpt.com/", "https://chatgpt.com/c/chat_123"));
        assertTrue(ChatRoutePolicy.sameConversation("https://chatgpt.com/c/chat_123", "https://chatgpt.com/c/chat_123"));
    }

    @Test public void parsesProjectNewAndConversationRoutes() {
        ChatRoutePolicy.Route root = ChatRoutePolicy.parse("https://chatgpt.com/g/g-p-project/project");
        ChatRoutePolicy.Route conversation = ChatRoutePolicy.parse("https://chatgpt.com/g/g-p-project/c/chat_123");
        assertNotNull(root); assertFalse(root.general); assertFalse(root.hasConversation());
        assertEquals("https://chatgpt.com/g/g-p-project/project", root.canonicalNewUrl);
        assertNotNull(conversation); assertEquals("g-p-project", conversation.projectId);
        assertEquals("chat_123", conversation.conversationId);
        assertTrue(ChatRoutePolicy.sameScope(root.canonicalNewUrl,
                "https://chatgpt.com/g/g-p-project/c/chat_123"));
        assertFalse(ChatRoutePolicy.sameScope(root.canonicalNewUrl,
                "https://chatgpt.com/g/g-p-other/c/chat_123"));
    }

    @Test public void rejectsUnsupportedOrConfusingRoutes() {
        String[] rejected = {"https://www.chatgpt.com/", "http://chatgpt.com/",
                "https://chatgpt.com/settings", "https://chatgpt.com/c/",
                "https://chatgpt.com/c/chat?x=1", "https://chatgpt.com/g/g-p-project/c/chat/extra"};
        for (String value : rejected) assertNull(value, ChatRoutePolicy.parse(value));
    }

    @Test public void capturesOnlyAfterBootstrapSubmissionInExpectedScope() {
        String generalRoot = "https://chatgpt.com/", generalConversation = "https://chatgpt.com/c/new_chat";
        assertFalse(ChatRoutePolicy.shouldCaptureBootstrapConversation(false,
                "", generalRoot, generalConversation));
        assertFalse(ChatRoutePolicy.shouldCaptureBootstrapConversation(true,
                "", generalRoot, "https://chatgpt.com/g/g-p-other/c/new_chat"));
        assertTrue(ChatRoutePolicy.shouldCaptureBootstrapConversation(true,
                "", generalRoot, generalConversation));
        assertFalse(ChatRoutePolicy.shouldCaptureBootstrapConversation(true,
                "https://chatgpt.com/c/already", generalRoot, generalConversation));
    }

    @Test public void durableSubmissionEvidenceStillAllowsCaptureAfterFastDriveAck() {
        boolean awaitingCommandAck = false;
        boolean durableBootstrapSubmitted = true;
        assertFalse(awaitingCommandAck);
        assertTrue(ChatRoutePolicy.shouldCaptureBootstrapConversation(durableBootstrapSubmitted, "",
                "https://chatgpt.com/g/g-p-project/project",
                "https://chatgpt.com/g/g-p-project/c/new_after_ack"));
    }
}
