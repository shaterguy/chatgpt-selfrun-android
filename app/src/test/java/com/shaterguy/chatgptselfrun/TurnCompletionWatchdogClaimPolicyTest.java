package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Regression policy for the native completion callback and bounded Drive synchronization. */
public final class TurnCompletionWatchdogClaimPolicyTest {
    @Test public void callbackCarriesUnpredictableRunScopedToken() throws Exception {
        String service = source("SelfRunService.java");
        String dom = source("SelfRunContinuationDom.java");
        assertTrue(service.contains("UUID.randomUUID().toString().replace"));
        assertTrue(service.contains("token.equals(store.turnObserverToken())"));
        assertTrue(service.contains("launchedRunId.equals(run)"));
        assertTrue(service.contains("PHASE_WAIT_TURN_COMPLETION.equals(store.phase())"));
        assertTrue(dom.contains("encodeURIComponent(observerRun)"));
        assertTrue(dom.contains("encodeURIComponent(observerToken)"));
    }

    @Test public void malformedOrStaleObserverCallbackIsConsumedWithoutTransition() throws Exception {
        String callback = section(source("SelfRunService.java"),
                "private boolean isTurnCompletionCallback", "private void maybeCaptureConversationUrl");
        assertTrue(callback.contains("TURN_COMPLETION_SCHEME"));
        assertTrue(callback.contains("TURN_COMPLETION_HOST"));
        assertTrue(callback.contains("callback_rejected"));
        assertTrue(callback.contains("return true"));
        assertTrue(callback.contains("store.beginPostDomDriveSync(token)"));
    }

    @Test public void immediateDriveReadThenFiveSecondRetriesAreBounded() throws Exception {
        String service = source("SelfRunService.java");
        String callback = section(service,
                "private boolean isTurnCompletionCallback", "private void maybeCaptureConversationUrl");
        String poll = section(service, "private void pollDriveNow", "private void replayTerminalSideEffect");
        assertTrue(callback.contains("handler.post(this::authorizeAndRunDrive)"));
        assertTrue(poll.contains("drive.readDocumentSnapshot"));
        assertTrue(poll.contains("schedulePostDomDriveSync(POST_DOM_DRIVE_RETRY_MS)"));
        assertTrue(poll.contains("POST_DOM_DRIVE_SYNC_TIMEOUT"));
        assertTrue(poll.contains("action=pause_fail_closed"));
        assertFalse(poll.contains("continueAfterPostDomDriveTimeout"));
        assertFalse(service.contains("NORMAL_POLL_MS"));
        assertFalse(service.contains("TURN_COMPLETION_WATCHDOG_MS"));
        assertFalse(service.contains("scheduleDrivePoll"));
    }

    @Test public void pauseAndTeardownDisconnectTheObserver() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("disconnectTurnObserver();removeAutomationCallbacks()"));
        assertTrue(service.contains("cancelTurnCompletionObserver(token)"));
        String dom = source("SelfRunContinuationDom.java");
        assertTrue(dom.contains("OBSERVER_DISCONNECTED"));
    }

    @Test public void ordinaryNavigationDoesNotEnableIdleBaselineRecovery() throws Exception {
        String service = source("SelfRunService.java");
        String pageStarted = section(service, "onPageStarted", "onPageFinished");
        assertFalse(pageStarted.contains("turnObserverNeedsIdleBaseline=true"));
        assertTrue(service.contains("onRenderProcessGone"));
        assertTrue(service.contains("turnObserverNeedsIdleBaseline=store!=null&&SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())&&store.turnObserverSawStop()"));
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
