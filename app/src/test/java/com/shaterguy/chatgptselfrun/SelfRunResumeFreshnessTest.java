package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SelfRunResumeFreshnessTest {
    @Test public void continuationRequiresCanonicalConversationTipBeforeComposerSubmission() throws Exception {
        String dom = src("SelfRunDom.java");
        String prepare = between(dom, "static String prepareDriveTurn", "/** Retry path");
        String barrier = between(dom, "private static String conversationFreshnessBarrier", "private static String composer");
        assertTrue(prepare.contains("conversationFreshnessBarrier"));
        assertTrue(barrier.contains("/backend-api/conversation/"));
        assertTrue(barrier.contains("current_node"));
        assertTrue(barrier.contains("data-message-id"));
        assertTrue(barrier.contains("conversation 최신 tip 동기화 대기"));
        assertTrue(barrier.contains("visibilitychange"));
        assertTrue(barrier.contains("window.next?.router"));
        assertFalse(barrier.contains("location.reload"));
        assertFalse(barrier.contains("window.location="));
        assertFalse(barrier.contains("loadUrl"));
    }

    @Test public void clickPathRechecksFreshnessAndNeverEditsExistingUserTurn() throws Exception {
        String dom = src("SelfRunDom.java");
        String click = between(dom, "static String clickPreparedDriveTurn", "/** Crash recovery");
        assertTrue(click.contains("conversationFreshnessBarrier"));
        assertTrue(click.contains("750L"));
        assertTrue(click.contains("send.click()"));
        assertFalse(click.contains("edit"));
        assertFalse(click.contains("retry-button"));
    }

    @Test public void pauseResumeKeepsExistingWebViewAndUsesCommonContinuePath() throws Exception {
        String service = src("SelfRunService.java");
        String store = src("SelfRunStore.java");
        String pause = between(service, "private void enterPreservedPause", "private void removeAutomationCallbacks");
        String resume = between(service, "private void resumeFromUi", "private void enterPreservedPause");
        String baseline = between(store, "void baselineManualResume", "void captureConversationUrl");
        assertTrue(pause.contains("pauseWebView()"));
        assertFalse(pause.contains("cleanupWebView()"));
        assertTrue(resume.contains("resumeWebView()"));
        assertFalse(resume.contains("cleanupWebView()"));
        assertTrue(baseline.contains("PHASE_SEND_CONTINUE"));
    }

    private static String src(String f) throws Exception {
        Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);
        if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);
        return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
    }
    private static String between(String s,String a,String b){
        int start=s.indexOf(a), end=s.indexOf(b,start+1);
        assertTrue("missing start marker: "+a,start>=0);
        assertTrue("missing end marker: "+b,end>start);
        return s.substring(start,end);
    }
}
