package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ProbeDocumentRolloverPolicyTest {
    @Test public void documentReadySequenceRestartIsAcceptedAfterRecoveryNavigation() {
        ConversationSyncInstrumentation.Session session = new ConversationSyncInstrumentation.Session();
        session.onConversationSyncEvent(event(1, ConversationSyncInstrumentation.Type.DOCUMENT_READY));
        session.onConversationSyncEvent(event(2, ConversationSyncInstrumentation.Type.CONVERSATION_CHANNEL_OPEN));
        session.onConversationSyncEvent(state(3, "h1", "c1", "s1"));
        session.onConversationSyncEvent(state(4, "h1", "c1", "s1"));
        assertTrue(session.proof().proven);
        session.onConversationSyncEvent(event(1, ConversationSyncInstrumentation.Type.DOCUMENT_READY));
        assertFalse(session.proof().proven);
        session.onConversationSyncEvent(event(2, ConversationSyncInstrumentation.Type.CONVERSATION_CHANNEL_OPEN));
        session.onConversationSyncEvent(state(3, "h2", "c2", "s2"));
        assertTrue(session.proof().proven);
        assertTrue(session.proof().probeEpoch >= 2L);
    }

    private static ConversationSyncInstrumentation.Event event(long seq, ConversationSyncInstrumentation.Type type) {
        return new ConversationSyncInstrumentation.Event(type, seq, 0, 0, "", "", "", 0, "test");
    }
    private static ConversationSyncInstrumentation.Event state(long seq, String head, String composer, String sig) {
        return new ConversationSyncInstrumentation.Event(ConversationSyncInstrumentation.Type.CLIENT_STATE,
                seq, 0, 0, head, composer, sig, 1, "test");
    }
}
