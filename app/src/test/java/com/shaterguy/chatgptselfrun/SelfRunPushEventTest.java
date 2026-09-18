package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@Category(ServerOnly.class)
public final class SelfRunPushEventTest {
    @Test public void validPayloadParsesAndMatchesExactLedgerIdentity() {
        Map<String, String> data = valid();
        SelfRunPushEvent event = SelfRunPushEvent.parse(data, "com.shaterguy.chatgptselfrun.drive.test");
        assertEquals("EV-1234567890123456", event.eventId);
        assertEquals("INS-1234567890123456", event.installationId);
        assertEquals("SR-20260915-ABCDEF", event.taskId);
        assertEquals("SR-20260915-ABCDEF:turn:4", event.turnId);
        assertEquals("1AbcDefGhijkLMNopQRstuVwxyz012345", event.resultDocumentId);
        assertTrue(event.matches("INS-1234567890123456", "SR-20260915-ABCDEF",
                "SR-20260915-ABCDEF:turn:4", "1AbcDefGhijkLMNopQRstuVwxyz012345"));
        assertFalse(event.matches("OTHER", "SR-20260915-ABCDEF",
                "SR-20260915-ABCDEF:turn:4", "1AbcDefGhijkLMNopQRstuVwxyz012345"));
    }

    @Test public void wrongApplicationIdIsRejected() {
        try {
            SelfRunPushEvent.parse(valid(), "com.shaterguy.chatgptselfrun.drive");
            fail("expected application mismatch");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("application"));
        }
    }

    @Test public void missingOrOversizedIdentityIsRejected() {
        Map<String, String> missing = valid();
        missing.remove("turnId");
        assertRejected(missing);

        Map<String, String> oversized = valid();
        oversized.put("eventId", "x".repeat(300));
        assertRejected(oversized);
    }

    private static void assertRejected(Map<String, String> data) {
        try {
            SelfRunPushEvent.parse(data, "com.shaterguy.chatgptselfrun.drive.test");
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private static Map<String, String> valid() {
        Map<String, String> data = new HashMap<>();
        data.put("schema", "selfrun-push-v1");
        data.put("type", "RESULT_CHANGED");
        data.put("eventId", "EV-1234567890123456");
        data.put("installationId", "INS-1234567890123456");
        data.put("applicationId", "com.shaterguy.chatgptselfrun.drive.test");
        data.put("taskId", "SR-20260915-ABCDEF");
        data.put("turnId", "SR-20260915-ABCDEF:turn:4");
        data.put("resultDocumentId", "1AbcDefGhijkLMNopQRstuVwxyz012345");
        return data;
    }
}
