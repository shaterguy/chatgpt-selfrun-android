package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ConversationSyncInstrumentationPolicyTest {
    @Test public void documentStartProbeIsTransparentAndPrivacyBounded() {
        String js = ConversationSyncInstrumentation.documentStartScript();
        assertTrue(js.contains("new Proxy(NativeWebSocket"));
        assertTrue(js.contains("new Proxy(NativeFetch"));
        assertTrue(js.contains("Reflect.construct(target,args,newTarget)"));
        assertTrue(js.contains("Reflect.apply(target,thisArg,args)"));
        assertTrue(js.contains("response.clone().text()"));
        assertFalse(js.contains("cookie"));
        assertFalse(js.contains("authorization"));
        assertFalse(js.contains("prompt"));
        assertFalse(js.contains("assistant"));
        assertFalse(js.contains("reload("));
        assertFalse(js.contains("location.reload"));
    }

    @Test public void exactOriginsOnlyAndNoWildcard() {
        assertTrue(ConversationSyncInstrumentation.TRUSTED_ORIGINS.contains("https://chatgpt.com"));
        assertTrue(ConversationSyncInstrumentation.TRUSTED_ORIGINS.contains("https://www.chatgpt.com"));
        assertFalse(ConversationSyncInstrumentation.TRUSTED_ORIGINS.contains("*"));
        for (String origin : ConversationSyncInstrumentation.TRUSTED_ORIGINS) {
            assertTrue(origin.startsWith("https://"));
            assertFalse(origin.contains("*"));
        }
    }

    @Test public void remoteUpdateNeedsPostUpdateStateBeforeProof() {
        ConversationSyncInstrumentation.Session session = new ConversationSyncInstrumentation.Session();
        session.onConversationSyncEvent(event(1, ConversationSyncInstrumentation.Type.DOCUMENT_READY, false, 0, "", "", ""));
        session.onConversationSyncEvent(event(2, ConversationSyncInstrumentation.Type.CONVERSATION_CHANNEL_OPEN, false, 0, "", "", ""));
        session.onConversationSyncEvent(event(3, ConversationSyncInstrumentation.Type.CLIENT_STATE, false, 0, "h1", "c1", "s1"));
        assertTrue(session.proof().proven);
        session.onConversationSyncEvent(event(4, ConversationSyncInstrumentation.Type.CONVERSATION_REMOTE_UPDATE, true, 0, "", "", ""));
        assertFalse(session.proof().proven);
        session.onConversationSyncEvent(event(5, ConversationSyncInstrumentation.Type.CLIENT_STATE, false, 0, "h2", "c1", "s2"));
        assertTrue(session.proof().proven);
        assertEquals("remote_render", session.proof().source);
    }

    @Test public void reconnectRequiresRevalidationBeforeProof() {
        ConversationSyncInstrumentation.Session session = readySession();
        session.onConversationSyncEvent(event(4, ConversationSyncInstrumentation.Type.CONVERSATION_CHANNEL_CLOSED, false, 0, "", "", ""));
        session.onConversationSyncEvent(event(5, ConversationSyncInstrumentation.Type.CONVERSATION_CHANNEL_OPEN, false, 0, "", "", ""));
        session.onConversationSyncEvent(event(6, ConversationSyncInstrumentation.Type.CLIENT_STATE, false, 0, "h1", "c1", "s1"));
        assertFalse(session.proof().proven);
        session.onConversationSyncEvent(event(7, ConversationSyncInstrumentation.Type.CONVERSATION_REVALIDATION_COMPLETE, true, 200, "", "", ""));
        session.onConversationSyncEvent(event(8, ConversationSyncInstrumentation.Type.CLIENT_STATE, false, 0, "h1", "c1", "s1"));
        assertTrue(session.proof().proven);
        assertEquals("revalidation", session.proof().source);
    }

    @Test public void opaqueChannelTrafficFailsClosed() {
        ConversationSyncInstrumentation.Session session = readySession();
        session.onConversationSyncEvent(event(4, ConversationSyncInstrumentation.Type.UNCLASSIFIED_CHANNEL_TRAFFIC, false, 0, "", "", ""));
        assertTrue(session.isDirty());
        assertFalse(session.proof().proven);
    }

    private static ConversationSyncInstrumentation.Session readySession() {
        ConversationSyncInstrumentation.Session session = new ConversationSyncInstrumentation.Session();
        session.onConversationSyncEvent(event(1, ConversationSyncInstrumentation.Type.DOCUMENT_READY, false, 0, "", "", ""));
        session.onConversationSyncEvent(event(2, ConversationSyncInstrumentation.Type.CONVERSATION_CHANNEL_OPEN, false, 0, "", "", ""));
        session.onConversationSyncEvent(event(3, ConversationSyncInstrumentation.Type.CLIENT_STATE, false, 0, "h1", "c1", "s1"));
        assertTrue(session.proof().proven);
        return session;
    }

    private static ConversationSyncInstrumentation.Event event(long seq, ConversationSyncInstrumentation.Type type,
                                                                boolean match, int status,
                                                                String head, String composer, String sig) {
        return new ConversationSyncInstrumentation.Event(type, seq, match, status, head, composer, sig, 1, "test");
    }
}
