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
        assertTrue(observer.contains("const confirmed=controlState()"));
        assertTrue(observer.contains("if(confirmed.state==='\" + STOP + \"')"));
        assertTrue(observer.contains("state.observer?.disconnect()"));
        assertTrue(observer.contains("location.href=observerCallback"));
    }

    @Test public void stopReturningDuringStabilityWindowCancelsCompletion() throws Exception {
        String observer = section(source("SelfRunContinuationDom.java"),
                "private static String completionObserver", "private static String conversationGuard");
        assertTrue(observer.contains("const noteStop="));
        assertTrue(observer.contains("if(current.state==='\" + STOP + \"'){noteStop();return;}"));
        assertTrue(observer.contains("if(confirmed.state==='\" + STOP + \"'){noteStop();return;}"));
        assertTrue(observer.indexOf("const confirmed=controlState()")
                < observer.indexOf("location.href=observerCallback"));
    }

    @Test public void observerTargetsStopSendAreaAndNotAssistantMessages() throws Exception {
        String observer = section(source("SelfRunContinuationDom.java"),
                "private static String completionObserver", "private static String conversationGuard");
        assertTrue(observer.contains("composerRoot?.parentElement"));
        assertTrue(observer.contains("childList:true,subtree:true,attributes:true"));
        assertTrue(source("SelfRunContinuationDom.java").contains("const controls=composerRoot?[...composerRoot.querySelectorAll('button,[role=\\\"button\\\"]')].filter(visible):[];"));
        assertFalse(observer.contains("assistant"));
    }

    @Test public void observerRebindsInPlaceAndNativeHealthcheckDoesNotReloadChat() throws Exception {
        String observer = section(source("SelfRunContinuationDom.java"),
                "private static String completionObserver", "private static String conversationGuard");
        String service = source("SelfRunService.java");

        assertEquals(15_000L, SelfRunService.TURN_OBSERVER_HEALTHCHECK_MS);
        assertTrue(observer.contains("state.root!==observeRoot"));
        assertTrue(observer.contains("state.composer!==composer"));
        assertTrue(observer.contains("!state.root?.isConnected"));
        assertTrue(observer.contains("!state.composer?.isConnected"));
        assertTrue(observer.contains("state.observer?.disconnect()"));
        assertTrue(observer.contains("state.observer=new MutationObserver(evaluate)"));
        assertTrue(observer.contains("idleSince:0"));
        assertTrue(observer.contains("Date.now()-state.idleSince"));
        assertTrue(observer.contains("if(bindingChanged)cancelTimer()"));
        assertFalse(observer.contains("if(bindingChanged)resetIdle()"));
        assertTrue(service.contains("scheduleWeb(TURN_OBSERVER_HEALTHCHECK_MS)"));
        assertTrue(service.contains("boolean firstArm="));
        assertTrue(service.contains("boolean rebound=detail.contains(\"bindingChanged=1\")"));
        assertTrue(service.contains("if(firstArm||rebound)"));
        assertTrue(service.contains("rebound&&!firstArm?\"rebound\":\"armed\""));
        assertFalse(observer.contains("location.reload"));
        assertFalse(observer.contains("location.assign"));
        assertFalse(observer.contains("history.go"));
        assertFalse(observer.contains("window.open"));
    }

    @Test public void composerRebindMustPreserveTheCumulativeIdleWindow() throws Exception {
        String observer = section(source("SelfRunContinuationDom.java"),
                "private static String completionObserver", "private static String conversationGuard");

        assertTrue(observer.contains("const resetIdle=()=>{state.idleSince=0;cancelTimer();}"));
        assertTrue(observer.contains("if(!state.idleSince)state.idleSince=Date.now()"));
        assertTrue(observer.contains("observerStableMs-(Date.now()-state.idleSince)"));
        assertTrue(observer.contains("if(Date.now()-state.idleSince>=observerStableMs)fireStable()"));
        assertTrue(observer.contains(";idleMs='+idleMs"));
    }

    @Test public void fiveMinutePostDomDriveWindowHasExactBoundary() {
        long start = 1_000_000L;
        assertFalse(SelfRunService.postDomDriveSyncTimedOut(start,
                start + SelfRunService.POST_DOM_DRIVE_MAX_WAIT_MS - 1L));
        assertTrue(SelfRunService.postDomDriveSyncTimedOut(start,
                start + SelfRunService.POST_DOM_DRIVE_MAX_WAIT_MS));
        assertFalse(SelfRunService.postDomDriveSyncTimedOut(0L, Long.MAX_VALUE));
        assertEquals(5_000L, SelfRunService.POST_DOM_DRIVE_RETRY_MS);
        assertEquals(5 * 60_000L, SelfRunService.POST_DOM_DRIVE_MAX_WAIT_MS);
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
