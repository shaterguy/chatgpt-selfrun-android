package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ConversationSyncInstrumentationPolicyTest {
    @Test public void probeIsTransparentPrivacyBoundedAndSchemaAgnostic() {
        String js = ConversationSyncInstrumentation.documentStartScript();
        assertTrue(js.contains("new Proxy(Ctor"));
        assertTrue(js.contains("new Proxy(NativeFetch"));
        assertTrue(js.contains("Reflect.construct(target,args,newTarget)"));
        assertTrue(js.contains("Reflect.apply(target,thisArg,args)"));
        assertFalse(js.contains("response.clone().text()"));
        assertFalse(js.contains("ev.data.includes"));
        assertFalse(js.contains("requestUrl.includes"));
        assertFalse(js.contains("conversationId()"));
        assertFalse(js.contains("innerText"));
        assertFalse(js.contains("textContent"));
        assertFalse(js.contains(".value"));
        assertFalse(js.contains("cookie"));
        assertFalse(js.contains("authorization"));
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

    @Test public void initialDocumentNeedsChannelAndStructuralState() {
        ConversationSyncInstrumentation.Session session = new ConversationSyncInstrumentation.Session();
        session.onConversationSyncEvent(event(1, ConversationSyncInstrumentation.Type.DOCUMENT_READY, 0, 0, "", "", ""));
        session.onConversationSyncEvent(event(2, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h1", "c1", "s1"));
        assertFalse(session.proof().proven);
        session.onConversationSyncEvent(event(3, ConversationSyncInstrumentation.Type.CONVERSATION_CHANNEL_OPEN, 0, 0, "", "", ""));
        session.onConversationSyncEvent(event(4, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h1", "c1", "s1"));
        assertTrue(session.proof().proven);
    }

    @Test public void channelActivityFailsClosedUntilHeadActuallyChanges() {
        ConversationSyncInstrumentation.Session session = readySession();
        session.onConversationSyncEvent(event(5, ConversationSyncInstrumentation.Type.CHANNEL_ACTIVITY, 0, 0, "", "", ""));
        assertFalse(session.proof().proven);
        session.onConversationSyncEvent(event(6, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h1", "c1", "s1"));
        assertFalse(session.proof().proven);
        session.onConversationSyncEvent(event(7, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h2", "c1", "s2"));
        assertTrue(session.proof().proven);
        assertEquals(1L, session.remoteEpoch());
        assertEquals("remote_render", session.proof().source);
    }

    @Test public void reconnectRequiresSameOriginFetchCompletionAndPostFetchState() {
        ConversationSyncInstrumentation.Session session = readySession();
        session.onConversationSyncEvent(event(5, ConversationSyncInstrumentation.Type.CONVERSATION_CHANNEL_CLOSED, 0, 0, "", "", ""));
        session.onConversationSyncEvent(event(6, ConversationSyncInstrumentation.Type.CONVERSATION_CHANNEL_OPEN, 0, 0, "", "", ""));
        session.onConversationSyncEvent(event(7, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h1", "c1", "s1"));
        assertFalse(session.proof().proven);
        session.onConversationSyncEvent(event(8, ConversationSyncInstrumentation.Type.PAGE_FETCH_START, 0, 9, "", "", ""));
        session.onConversationSyncEvent(event(9, ConversationSyncInstrumentation.Type.PAGE_FETCH_COMPLETE, 200, 9, "", "", ""));
        assertFalse(session.proof().proven);
        session.onConversationSyncEvent(event(10, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h1", "c1", "s1"));
        assertTrue(session.proof().proven);
        assertEquals("page_revalidation", session.proof().source);
    }

    @Test public void unknownProbeEventFailsClosed() {
        ConversationSyncInstrumentation.Session session = readySession();
        session.onConversationSyncEvent(event(5, ConversationSyncInstrumentation.Type.UNKNOWN, 0, 0, "", "", ""));
        assertTrue(session.isDirty());
        assertFalse(session.proof().proven);
    }

    @Test public void genericFetchDoesNotClearChannelActivityWithoutHeadChange() {
        ConversationSyncInstrumentation.Session session = readySession();
        session.onConversationSyncEvent(event(5, ConversationSyncInstrumentation.Type.CHANNEL_ACTIVITY, 0, 0, "", "", ""));
        session.onConversationSyncEvent(event(6, ConversationSyncInstrumentation.Type.PAGE_FETCH_START, 0, 3, "", "", ""));
        session.onConversationSyncEvent(event(7, ConversationSyncInstrumentation.Type.PAGE_FETCH_COMPLETE, 200, 3, "", "", ""));
        session.onConversationSyncEvent(event(8, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h1", "c1", "s1"));
        assertFalse(session.proof().proven);
    }

    private static ConversationSyncInstrumentation.Session readySession() {
        ConversationSyncInstrumentation.Session session = new ConversationSyncInstrumentation.Session();
        session.onConversationSyncEvent(event(1, ConversationSyncInstrumentation.Type.DOCUMENT_READY, 0, 0, "", "", ""));
        session.onConversationSyncEvent(event(2, ConversationSyncInstrumentation.Type.CONVERSATION_CHANNEL_OPEN, 0, 0, "", "", ""));
        session.onConversationSyncEvent(event(3, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h1", "c1", "s1"));
        session.onConversationSyncEvent(event(4, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h1", "c1", "s1"));
        assertTrue(session.proof().proven);
        return session;
    }

    private static ConversationSyncInstrumentation.Event event(long seq,
                                                                ConversationSyncInstrumentation.Type type,
                                                                int status, int rid,
                                                                String head, String composer, String sig) {
        return new ConversationSyncInstrumentation.Event(type, seq, status, rid, head, composer, sig, 1, "test");
    }
}
