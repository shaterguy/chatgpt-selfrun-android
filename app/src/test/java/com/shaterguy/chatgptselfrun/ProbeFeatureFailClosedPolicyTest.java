package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ProbeFeatureFailClosedPolicyTest {
    @Test public void unsupportedDocumentStartProbePausesInsteadOfFallingBackToDom() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String launch = RateLimitAndStopPolicyTest.Source.between(service,
                "private void launchWebView", "private boolean armBootstrapConversationCapture");
        assertTrue(launch.contains("ConversationSyncInstrumentation.install"));
        assertTrue(launch.contains("CONVERSATION_SYNC_INSTRUMENTATION_UNAVAILABLE"));
        assertTrue(launch.contains("enterPreservedPause"));
    }
}
