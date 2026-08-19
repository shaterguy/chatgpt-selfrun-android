package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class CrossDeviceEvidenceContractTest {
    @Test public void requiredCrossDeviceEvidenceEventsExistInRuntime() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String probe = RateLimitAndStopPolicyTest.Source.read("ConversationSyncInstrumentation.java");
        assertTrue(service.contains("CONVERSATION_REMOTE_UPDATE"));
        assertTrue(service.contains("CONVERSATION_SYNC_PROVEN"));
        assertTrue(service.contains("RESPONSE_IDLE_CONFIRMED"));
        assertTrue(probe.contains("remoteEpoch++"));
        assertTrue(probe.contains("PAGE_FETCH_START"));
        assertTrue(probe.contains("PAGE_FETCH_COMPLETE"));
        assertTrue(probe.contains("CLIENT_STATE"));
        assertTrue(probe.contains("currentComposer"));
    }

    @Test public void noPrivateConversationEndpointOrPayloadIdentifierIsHardcoded() throws Exception {
        String probe = RateLimitAndStopPolicyTest.Source.read("ConversationSyncInstrumentation.java");
        assertFalse(probe.contains("/backend-api/"));
        assertFalse(probe.contains("/conversation/"));
        assertFalse(probe.contains("conversation_id"));
        assertFalse(probe.contains("message_id"));
        assertFalse(probe.contains("ev.data.includes"));
        assertFalse(probe.contains("requestUrl.includes"));
    }
}
