package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class RecoveryNavigationPolicyTest {
    @Test public void loadUrlIsOnlyUsedForLaunchOrRecoveryPaths() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String normal = RateLimitAndStopPolicyTest.Source.between(service,
                "private void startConversationSyncNavigation", "private void onMainFramePageStarted");
        assertFalse(normal.contains("reload()"));
        assertTrue(normal.contains("if (!match)"));
        assertTrue(normal.contains("loadUrl_recovery"));
        assertEquals(1, count(normal, "webView.loadUrl(canonical)"));
        String rate = RateLimitAndStopPolicyTest.Source.between(service,
                "private void handleWebRateLimit", "private void onConversationProbeEvent");
        assertFalse(rate.contains("loadUrl("));
        assertFalse(rate.contains("reload("));
    }

    private static int count(String s,String token){int n=0,i=0;while((i=s.indexOf(token,i))>=0){n++;i+=token.length();}return n;}
}
