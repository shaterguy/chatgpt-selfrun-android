package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class PauseResumeFreshnessPolicyTest {
    @Test public void pauseAndResumeForceFreshnessDirtyWithoutDestroyingWebView() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String pause = RateLimitAndStopPolicyTest.Source.between(service,
                "private void pauseFromUi", "private void resumeFromUi");
        String resume = RateLimitAndStopPolicyTest.Source.between(service,
                "private void resumeFromUi", "private void enterPreservedPause");
        assertTrue(pause.contains("forceDirty(\"pause\")"));
        assertTrue(resume.contains("forceDirty(\"manual_resume\")"));
        assertTrue(resume.contains("resumeWebView()"));
        assertFalse(pause.contains("cleanupWebView()"));
        assertFalse(resume.contains("cleanupWebView()"));
    }
}
