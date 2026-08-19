package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class MainFrame429CallbackPolicyTest {
    @Test public void bothHttp429AndTooManyRequestsErrorUseBackoff() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String launch = RateLimitAndStopPolicyTest.Source.between(service,
                "private void launchWebView", "private boolean armBootstrapConversationCapture");
        assertTrue(launch.contains("s.getStatusCode() == 429"));
        assertTrue(launch.contains("WebViewClient.ERROR_TOO_MANY_REQUESTS"));
        assertTrue(launch.contains("handleWebRateLimit(v)"));
    }
}
