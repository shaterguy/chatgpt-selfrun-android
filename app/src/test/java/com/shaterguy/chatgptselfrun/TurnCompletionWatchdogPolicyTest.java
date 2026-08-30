package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Regression policy for hidden-WebView turn completion and resynchronization. */
public final class TurnCompletionWatchdogPolicyTest {
    @Test public void completedStateMustRemainStableForFiveSeconds() throws Exception {
        assertEquals(5_000L, SelfRunService.TURN_COMPLETION_STABILITY_MS);
        String observer = observer();
        assertEquals(1, count(observer, "new MutationObserver"));
        assertEquals(1, count(observer, "setTimeout"));
        assertEquals(0, count(observer, "setInterval"));
        assertTrue(observer.contains("observerIdle(current.state)"));
        assertTrue(observer.contains("!turn.hasAnswer"));
        assertTrue(observer.contains("location.href=observerCallback"));
    }

    @Test public void stopCanNeverCompleteTheTurn() throws Exception {
        String observer = observer();
        String verification = section(source("SelfRunContinuationDom.java"),
      "private static String continuationClickedVerification", "private static String runIdFromContinuationMarker");
        assertTrue(verification.contains("users>baseline"));
        assertFalse(verification.contains("users>baseline)||c.state==='\" + STOP + \"'"));
        assertTrue(observer.contains("if(current.state==='\" + STOP + \"')"));
        assertTrue(observer.contains("requestResync('stale_stop')"));
        assertFalse(observer.contains("turn.finalAction"));
        assertFalse(observer.contains("forceFinal"));
        assertFalse(observer.contains("recoveredStable"));
    }

    @Test public void staleLocalStopRequestsNativeSameConversationResync() throws Exception {
        String dom = source("SelfRunContinuationDom.java");
        String service = source("SelfRunService.java");
        assertEquals(3 * 60_000L, SelfRunContinuationDom.STALE_STOP_RESYNC_MS);
        assertTrue(dom.contains("TURN_RESYNC_HOST"));
        assertTrue(observer().contains("location.href=resyncCallback"));
        assertFalse(observer().contains("location.reload()"));
        assertTrue(service.contains("requestTurnCompletionResync"));
        assertTrue(service.contains("cleanupWebView();"));
        assertTrue(service.contains("handler.postDelayed(this::ensureWebView,800L)"));
        assertTrue(service.contains("MAX_TURN_COMPLETION_RESYNCS = 2"));
    }

    @Test public void manualResumeAndRepeatedCallbacksUseResyncNotNewSubmission() throws Exception {
        String service = source("SelfRunService.java");
        String resume = section(service, "private void resumeFromUi", "private void enterPreservedPause");
        String deadline = section(service, "private void scheduleContinuationCallbackDeadline", "private void recoverBootstrapSendCallback");
        assertTrue(resume.contains("turnObserverSawStop()"));
        assertTrue(resume.contains("requestTurnCompletionResync(\"manual_resume\")"));
        assertTrue(deadline.contains("TURN_COMPLETION_CALLBACK_TIMEOUT_RESYNC_THRESHOLD"));
        assertTrue(deadline.contains("requestTurnCompletionResync(\"evaluate_javascript_timeout\")"));
        assertFalse(resume.contains("continuationSubmitted("));
    }

    @Test public void observerTracksOnlyPrivacySafeLatestAssistantProgress() throws Exception {
        String observer = observer();
        String controls = section(source("SelfRunContinuationDom.java"),
      "private static String controls", "private static String composerOps");
        assertTrue(observer.contains("characterData:true"));
        assertTrue(controls.contains("[data-message-author-role]"));
        assertTrue(controls.contains("hashText(text)"));
        assertTrue(controls.contains("text.length"));
        assertFalse(observer.contains("innerText||latest"));
        assertFalse(observer.contains("textContent||latest"));
    }

    @Test public void rendererWatchdogRemainsScopedToBackgroundWebView() throws Exception {
        String config = source("WebViewConfig.java");
        String service = source("SelfRunService.java");
        assertTrue(config.contains("HeadlessWebViewHost.activeWebView() != webView"));
        assertTrue(config.contains("RENDERER_UNRESPONSIVE_LIMIT = 3"));
        assertTrue(config.contains("WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE"));
        assertTrue(config.contains("WEB_VIEW_RENDERER_TERMINATE"));
        assertTrue(service.contains("onRenderProcessGone"));
    }

    @Test public void threeMinutePostDomDriveWindowHasExactBoundary() {
        long start = 1_000_000L;
        assertFalse(SelfRunService.postDomDriveSyncTimedOut(start,
      start + SelfRunService.POST_DOM_DRIVE_MAX_WAIT_MS - 1L));
        assertTrue(SelfRunService.postDomDriveSyncTimedOut(start,
      start + SelfRunService.POST_DOM_DRIVE_MAX_WAIT_MS));
        assertEquals(5_000L, SelfRunService.POST_DOM_DRIVE_RETRY_MS);
        assertEquals(3 * 60_000L, SelfRunService.POST_DOM_DRIVE_MAX_WAIT_MS);
    }

    private static String observer() throws Exception {
        return section(source("SelfRunContinuationDom.java"),
      "private static String completionObserver", "private static String conversationGuard");
    }

    private static int count(String text, String value) {
        int n = 0, at = 0;
        while ((at = text.indexOf(value, at)) >= 0) { n++; at += value.length(); }
        return n;
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String section(String text, String start, String end) {
        int a = text.indexOf(start);
        int b = text.indexOf(end, a + start.length());
        assertTrue("missing start: " + start, a >= 0);
        assertTrue("missing end: " + end, b > a);
        return text.substring(a, b);
    }
}
