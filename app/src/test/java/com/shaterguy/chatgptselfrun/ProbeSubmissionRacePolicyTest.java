package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ProbeSubmissionRacePolicyTest {
    @Test public void remoteProbeDirtyCancelsPreparedContinuationBeforeClick() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String listener = RateLimitAndStopPolicyTest.Source.between(service,
                "private void onConversationProbeEvent", "private void postWebCallback");
        assertTrue(listener.contains("invalidateConversationFreshness"));
        assertTrue(listener.contains("enterConversationSync"));
        String click = ContinuationGuardDom.clickPreparedDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "m", "tok", "h1", "c1", "s1");
        assertTrue(click.contains("window.__selfRunDriveFreshnessToken===__srProofToken"));
        assertTrue(click.contains("prepared.freshnessToken!=="));
        assertTrue(click.contains("SUBMIT_BLOCKED_FRESHNESS"));
    }

    @Test public void normalSyncReusesSameConversationWithoutMainFrameRequest() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String sync = RateLimitAndStopPolicyTest.Source.between(service,
                "private void startConversationSyncNavigation", "private void onMainFramePageStarted");
        assertTrue(sync.contains("activeConversationSyncNavigation = \"reuse\""));
        assertFalse(sync.contains("reload()"));
        int recovery = sync.indexOf("if (!match)");
        int load = sync.indexOf("webView.loadUrl(canonical)");
        assertTrue(recovery >= 0 && load > recovery);
    }

    @Test public void commandAttemptStillHasSingleActualSendClick() {
        String click = ContinuationGuardDom.clickPreparedDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "m", "tok", "h1", "c1", "s1");
        int first = click.indexOf("a.send.click()");
        assertTrue(first >= 0);
        assertEquals(-1, click.indexOf("a.send.click()", first + 1));
        assertTrue(click.contains("prepared.clicked"));
    }
}
