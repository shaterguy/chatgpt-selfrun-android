package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public final class LastUsedExecutionProfileStoreTest {
    @Test public void roundTripStableIdentity() {
        LastUsedExecutionProfileStore.Selection original =
                new LastUsedExecutionProfileStore.Selection("work", "GPT-6-Sol", "XHigh");
        String encoded = LastUsedExecutionProfileStore.encode(SelfRunStore.MODE_HYBRID, original);
        LastUsedExecutionProfileStore.Selection decoded =
                LastUsedExecutionProfileStore.decode(SelfRunStore.MODE_HYBRID, encoded);
        assertNotNull(decoded);
        assertEquals("WORK", decoded.mode);
        assertEquals("gpt-6-sol", decoded.model);
        assertEquals("xhigh", decoded.reasoning);
        assertEquals(encoded, LastUsedExecutionProfileStore.encode(SelfRunStore.MODE_HYBRID, decoded));
    }

    @Test public void rejectTaskActualModeMismatch() {
        LastUsedExecutionProfileStore.Selection chat =
                new LastUsedExecutionProfileStore.Selection("CHAT", "gpt-5-6-thinking", "medium");
        assertNull(LastUsedExecutionProfileStore.encode(SelfRunStore.MODE_WORK, chat));
        assertNull(LastUsedExecutionProfileStore.decode(SelfRunStore.MODE_WORK,
                LastUsedExecutionProfileStore.encode(SelfRunStore.MODE_CHAT, chat)));
        assertNotNull(LastUsedExecutionProfileStore.decode(SelfRunStore.MODE_HYBRID,
                LastUsedExecutionProfileStore.encode(SelfRunStore.MODE_CHAT, chat)));
    }

    @Test public void absentMalformedAndWrongTypesAreSafe() {
        assertNull(LastUsedExecutionProfileStore.decode(SelfRunStore.MODE_CHAT, null));
        assertNull(LastUsedExecutionProfileStore.decode(SelfRunStore.MODE_CHAT, "{"));
        assertNull(LastUsedExecutionProfileStore.decode(SelfRunStore.MODE_CHAT,
                "{\"mode\":3,\"model\":\"a\",\"reasoning\":\"b\"}"));
        assertNull(LastUsedExecutionProfileStore.decode(SelfRunStore.MODE_CHAT,
                "{\"mode\":\"CHAT\",\"model\":\"\",\"reasoning\":\"medium\"}"));
        assertNull(LastUsedExecutionProfileStore.decode(SelfRunStore.MODE_CHAT,
                "{\"mode\":\"CHAT\",\"model\":\"gpt\",\"reasoning\":\"keep\"}"));
        assertNull(LastUsedExecutionProfileStore.decode("UNKNOWN",
                "{\"mode\":\"CHAT\",\"model\":\"gpt\",\"reasoning\":\"medium\"}"));
        // A structurally sound removed model is retained until the UI asks for reselection.
        assertEquals("removed-model", LastUsedExecutionProfileStore.decode(SelfRunStore.MODE_WORK,
                "{\"mode\":\"WORK\",\"model\":\"removed-model\",\"reasoning\":\"max\"}").model);
    }
}
