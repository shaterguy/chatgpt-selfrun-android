package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SelfRunResumeFreshnessTest {
    @Test public void continuationArmsCanonicalParentGuardAndWaitsForNetworkReadback() throws Exception {
        String dom = src("SelfRunDom.java");
        String prepare = between(dom, "static String prepareDriveTurn", "/** Retry path");
        String click = between(dom, "static String clickPreparedDriveTurn", "/** Crash recovery");
        assertTrue(prepare.contains("parentGuardOutcome"));
        assertTrue(click.contains("parentGuardOutcome"));
        assertTrue(click.contains("armParentGuard"));
        assertTrue(click.contains("send.click()"));
        assertTrue(click.contains("canonical parent guard 제출 확인 대기"));
        assertFalse(click.contains("conversationFreshnessBarrier"));
        assertFalse(click.contains("location.reload"));
        assertFalse(click.contains("loadUrl"));
        assertFalse(click.contains("retry-button"));
    }

    @Test public void documentStartGuardRewritesOnlyParentAndFailsClosed() throws Exception {
        String guard = src("SelfRunNetworkGuard.java");
        String web = src("WebViewConfig.java");
        assertTrue(web.contains("static boolean applyAutomation"));
        assertTrue(web.contains("return SelfRunNetworkGuard.install(webView)"));
        assertTrue(guard.contains("DOCUMENT_START_SCRIPT"));
        assertTrue(guard.contains("addDocumentStartJavaScript"));
        assertTrue(guard.contains("/backend-api/f/conversation"));
        assertTrue(guard.contains("/backend-api/conversation"));
        assertTrue(guard.contains("current_node"));
        assertTrue(guard.contains("parent_message_id"));
        assertTrue(guard.contains("payload.parent_message_id = parent"));
        assertTrue(guard.contains("window.fetch = async function"));
        assertTrue(guard.contains("NativeXHR.prototype.send"));
        assertTrue(guard.contains("failClosed"));
        assertFalse(guard.contains("location.reload"));
        assertFalse(guard.contains("window.next?.router"));
        assertFalse(guard.contains("visibilitychange"));
    }

    @Test public void unsupportedDocumentStartPausesWithoutRetryStormOrReconnect() throws Exception {
        String service = src("SelfRunService.java");
        String dom = src("SelfRunDom.java");
        String run = between(service, "private void runWebStep", "private void evaluate");
        String pause = between(service, "private void pauseUnsupportedParentGuard", "private void pauseFromUi");
        String evaluate = between(service, "private void evaluate", "private void handleWebResult");
        assertTrue(service.contains("continuationParentGuardAvailable = WebViewConfig.applyAutomation(webView)"));
        assertTrue(run.contains("SelfRunContinuationCapability.requiresUserAction"));
        assertTrue(run.indexOf("pauseUnsupportedParentGuard()") < run.indexOf("SelfRunDom.prepareDriveTurn"));
        assertTrue(pause.contains("enterPreservedPause"));
        assertTrue(pause.contains("NotificationHelper.notifyUser"));
        assertFalse(pause.contains("scheduleWeb"));
        assertFalse(pause.contains("cleanupWebView"));
        assertFalse(pause.contains("loadUrl"));
        assertTrue(dom.contains("CAPABILITY_UNAVAILABLE"));
        assertTrue(evaluate.contains("CAPABILITY_UNAVAILABLE"));
        assertTrue(evaluate.contains("pauseUnsupportedParentGuard()"));
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
