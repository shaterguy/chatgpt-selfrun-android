package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Regression policy for MutationObserver-based turn completion. */
public final class TurnCompletionWatchdogPolicyTest {
    @Test public void completedStateMustRemainStableForFiveSeconds() throws Exception {
        assertEquals(5_000L, SelfRunService.TURN_COMPLETION_STABILITY_MS);
        String dom = source("SelfRunContinuationDom.java");
        String observer = section(dom, "private static String completionObserver", "private static String conversationGuard");
        assertEquals(1, count(observer, "new MutationObserver"));
        assertEquals(1, count(observer, "setTimeout"));
        assertEquals(0, count(observer, "setInterval"));
        assertTrue(observer.contains("const confirmed=controlState(),turn=trackProgress(),now=Date.now()"));
        assertTrue(observer.contains("!turn.hasAnswer"));
        assertTrue(observer.contains("state.observer?.disconnect()"));
        assertTrue(observer.contains("location.href=observerCallback"));
    }

    @Test public void staleStopCannotConfirmSubmissionButFinalAssistantCanComplete() throws Exception {
        String dom = source("SelfRunContinuationDom.java");
        String verification = section(dom,
                "private static String continuationClickedVerification", "private static String runIdFromContinuationMarker");
        String observer = section(dom,
                "private static String completionObserver", "private static String conversationGuard");

        assertTrue(verification.contains("users>baseline"));
        assertTrue(verification.contains("stopOnly=c.state==='\" + STOP + \"'"));
        assertFalse(verification.contains("users>baseline)||c.state==='\" + STOP + \"'"));
        assertTrue(observer.contains("turn.finalAction"));
        assertTrue(observer.contains("turn.hasAnswer"));
        assertTrue(observer.contains("turn.streaming"));
        assertTrue(observer.contains("fireStable(true)"));
    }

    @Test public void observerTracksOnlyPrivacySafeLatestAssistantProgress() throws Exception {
        String dom = source("SelfRunContinuationDom.java");
        String observer = section(dom,
                "private static String completionObserver", "private static String conversationGuard");
        String controls = section(dom, "private static String controls", "private static String composerOps");

        assertTrue(observer.contains("document.querySelector('main')"));
        assertTrue(observer.contains("characterData:true"));
        assertTrue(controls.contains("[data-message-author-role]"));
        assertTrue(controls.contains("lastUser"));
        assertTrue(controls.contains("hashText(text)"));
        assertTrue(controls.contains("text.length"));
        assertFalse(observer.contains("innerText||latest"));
        assertFalse(observer.contains("textContent||latest"));
    }

    @Test public void observerRebindsAndMayReloadOnlyTheSameConversationOnce() throws Exception {
        String observer = section(source("SelfRunContinuationDom.java"),
                "private static String completionObserver", "private static String conversationGuard");
        String service = source("SelfRunService.java");

        assertEquals(15_000L, SelfRunService.TURN_OBSERVER_HEALTHCHECK_MS);
        assertEquals(3 * 60_000L, SelfRunContinuationDom.STALE_STOP_RELOAD_MS);
        assertEquals(30_000L, SelfRunContinuationDom.RELOADED_STOP_COMPLETION_MS);
        assertTrue(observer.contains("state.root!==observeRoot"));
        assertTrue(observer.contains("state.composer!==composer"));
        assertTrue(observer.contains("!state.root?.isConnected"));
        assertTrue(observer.contains("!state.composer?.isConnected"));
        assertTrue(observer.contains("state.observer?.disconnect()"));
        assertTrue(observer.contains("state.observer=new MutationObserver(evaluate)"));
        assertTrue(observer.contains("sessionStorage.setItem(recoveryKey"));
        assertTrue(observer.contains("if(state.reloads>=1||state.fired)return false"));
        assertTrue(observer.contains("location.reload()"));
        assertFalse(observer.contains("location.assign"));
        assertFalse(observer.contains("history.go"));
        assertFalse(observer.contains("window.open"));
        assertTrue(service.contains("scheduleWeb(TURN_OBSERVER_HEALTHCHECK_MS)"));
    }

    @Test public void activeWaitKeepsWebViewRunningButReleasesWakeLock() throws Exception {
        String service = source("SelfRunService.java");
        String activeWait = section(service,
                "if(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)){",
                "if(\"TARGET_ERROR\".equals(status))");
        String preservedPause = section(service,
                "private void enterPreservedPause", "private void removeAutomationCallbacks");

        assertTrue(activeWait.contains("releaseWakeLock();"));
        assertTrue(activeWait.contains("scheduleWeb(TURN_OBSERVER_HEALTHCHECK_MS);"));
        assertFalse(activeWait.contains("pauseWebView();"));
        assertTrue(preservedPause.contains("pauseWebView();"));
    }

    @Test public void rendererWatchdogIsScopedToTheBackgroundWebViewAndHandledByService() throws Exception {
        String config = source("WebViewConfig.java");
        String service = source("SelfRunService.java");

        assertTrue(config.contains("HeadlessWebViewHost.activeWebView() != webView"));
        assertTrue(config.contains("RENDERER_UNRESPONSIVE_LIMIT = 3"));
        assertTrue(config.contains("WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE"));
        assertTrue(config.contains("WEB_VIEW_RENDERER_TERMINATE"));
        assertTrue(config.contains("renderer.terminate()"));
        assertTrue(service.contains("onRenderProcessGone"));
        assertTrue(service.contains("cleanupWebView();"));
        assertTrue(service.contains("ensureWebView"));
    }

    @Test public void threeMinutePostDomDriveWindowHasExactBoundary() {
        long start = 1_000_000L;
        assertFalse(SelfRunService.postDomDriveSyncTimedOut(start,
                start + SelfRunService.POST_DOM_DRIVE_MAX_WAIT_MS - 1L));
        assertTrue(SelfRunService.postDomDriveSyncTimedOut(start,
                start + SelfRunService.POST_DOM_DRIVE_MAX_WAIT_MS));
        assertFalse(SelfRunService.postDomDriveSyncTimedOut(0L, Long.MAX_VALUE));
        assertEquals(5_000L, SelfRunService.POST_DOM_DRIVE_RETRY_MS);
        assertEquals(3 * 60_000L, SelfRunService.POST_DOM_DRIVE_MAX_WAIT_MS);
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
