package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ProbeUnrelatedFetchStabilityTest {
    @Test public void unrelatedSameOriginFetchDoesNotInvalidateCleanProofToken() {
        ConversationSyncInstrumentation.Session session = new ConversationSyncInstrumentation.Session();
        session.onConversationSyncEvent(event(1, ConversationSyncInstrumentation.Type.DOCUMENT_READY, 0, 0, "", "", ""));
        session.onConversationSyncEvent(event(2, ConversationSyncInstrumentation.Type.CONVERSATION_CHANNEL_OPEN, 0, 0, "", "", ""));
        session.onConversationSyncEvent(event(3, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h1", "c1", "s1"));
        String before = session.proof().tokenPart();
        assertFalse(before.isEmpty());

        session.onConversationSyncEvent(event(4, ConversationSyncInstrumentation.Type.PAGE_FETCH_START, 0, 7, "", "", ""));
        session.onConversationSyncEvent(event(5, ConversationSyncInstrumentation.Type.PAGE_FETCH_COMPLETE, 200, 7, "", "", ""));
        session.onConversationSyncEvent(event(6, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h1", "c1", "s1"));

        assertTrue(session.proof().proven);
        assertEquals(before, session.proof().tokenPart());
        assertTrue(session.proof().eventSequence > 3L);
    }

    @Test public void dirtyTransitionStillChangesProofToken() {
        ConversationSyncInstrumentation.Session session = new ConversationSyncInstrumentation.Session();
        session.onConversationSyncEvent(event(1, ConversationSyncInstrumentation.Type.DOCUMENT_READY, 0, 0, "", "", ""));
        session.onConversationSyncEvent(event(2, ConversationSyncInstrumentation.Type.CONVERSATION_CHANNEL_OPEN, 0, 0, "", "", ""));
        session.onConversationSyncEvent(event(3, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h1", "c1", "s1"));
        String before = session.proof().tokenPart();
        session.onConversationSyncEvent(event(4, ConversationSyncInstrumentation.Type.CHANNEL_ACTIVITY, 0, 0, "", "", ""));
        assertFalse(session.proof().proven);
        session.onConversationSyncEvent(event(5, ConversationSyncInstrumentation.Type.CLIENT_STATE, 0, 0, "h2", "c1", "s2"));
        assertTrue(session.proof().proven);
        assertNotEquals(before, session.proof().tokenPart());
    }

    private static ConversationSyncInstrumentation.Event event(long seq,
                                                                ConversationSyncInstrumentation.Type type,
                                                                int status, int rid,
                                                                String head, String composer, String sig) {
        return new ConversationSyncInstrumentation.Event(type, seq, status, rid,
                head, composer, sig, 1, "test");
    }
}
