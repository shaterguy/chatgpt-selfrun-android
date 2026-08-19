package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class NoReloadSourcePolicyTest {
    @Test public void productionNormalFreshnessContainsNoWebViewReload() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        assertFalse(service.contains("webView.reload()"));
        assertFalse(service.contains("view.reload()"));
        String sync = RateLimitAndStopPolicyTest.Source.between(service,
                "private void startConversationSyncNavigation", "private void onMainFramePageStarted");
        assertFalse(sync.contains("reload("));
    }
}
