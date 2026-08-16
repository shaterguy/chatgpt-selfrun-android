package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class SelfRunDriveDev4PolicyTest {
    @Test public void bootstrapRequiresFreshEmptyConversation() throws Exception {
        String dom = src("SelfRunDom.java"), service = src("SelfRunService.java");
        assertTrue(dom.contains("NEW_CONVERSATION_REQUESTED"));
        assertTrue(dom.contains("create-new-chat-button"));
        assertTrue(dom.contains("STALE_NEW_ROUTE"));
        assertTrue(dom.contains("turnCount"));
        assertTrue(service.contains("pre_submit_route_barrier"));
        assertTrue(service.contains("BOOTSTRAP_NEW_CONVERSATION_TIMEOUT"));
        assertTrue(dom.contains("if(e.tagName==='A')return sameScopeNewHref(e)"));
        assertTrue(dom.contains("e.closest('nav,aside,header')"));
    }

    @Test public void chatModeCanUseDefaultWhenControlsAreAbsentButWorkCannot() throws Exception {
        String dom = src("SelfRunDom.java");
        assertTrue(dom.contains("requestedMode==='chat'&&rawModeControls.length===0"));
        assertTrue(dom.contains("chatDefaultWithoutControls"));
        assertFalse(dom.contains("requestedMode==='work'&&rawModeControls.length===0"));
    }

    @Test public void captureIsGatedAndWebDiagnosticsAreRecorded() throws Exception {
        String store = src("SelfRunStore.java"), service = src("SelfRunService.java");
        assertTrue(store.contains("shouldCaptureBootstrapConversation"));
        assertTrue(store.contains("bootstrapSubmittedAt"));
        assertTrue(store.contains("BOOTSTRAP_SUBMISSION_STARTED"));
        assertTrue(store.contains("BOOTSTRAP_SUBMISSION_CONFIRMED"));
        assertTrue(service.contains("WEBVIEW_PAGE_START"));
        assertTrue(service.contains("WEBVIEW_PAGE_FINISH"));
        assertTrue(service.contains("WEBVIEW_NAVIGATION"));
        assertTrue(service.contains("DOM_EVALUATE"));
        assertTrue(service.contains("DOM_RESULT"));
        assertFalse(service.contains("clearCookies"));
        assertFalse(service.contains("clearAllData"));
    }

    @Test public void ambiguousBootstrapNeverCreatesDurableSubmissionEvidence() throws Exception {
        String service = src("SelfRunService.java");
        String evaluate = between(service, "private void evaluate", "private void handleWebResult");
        String bootstrapBranch = between(evaluate,
                "SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)&&isAmbiguousSubmissionStatus(status)",
                "SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)&&isAmbiguousSubmissionStatus(status)");
        assertTrue(bootstrapBranch.contains("pauseBootstrapIfTimedOut"));
        assertTrue(bootstrapBranch.contains("scheduleWeb"));
        assertFalse(bootstrapBranch.contains("commandSubmitted"));
        assertTrue(evaluate.contains("\"BOOTSTRAP_SUBMITTED\".equals(status)){commandSubmitted(SelfRunStore.RETRY_BOOTSTRAP"));
    }

    @Test public void bootstrapWatchdogIsIndependentAndRejectsStaleCallbacks() throws Exception {
        String service = src("SelfRunService.java");
        assertTrue(service.contains("bootstrapWatchdogRunnable"));
        String watchdog = between(service, "private void bootstrapWatchdogElapsed", "private void cancelBootstrapWatchdog");
        assertTrue(watchdog.contains("bootstrapWatchdogEpoch != automationEpoch"));
        assertTrue(watchdog.contains("bootstrapWatchdogRunId.equals(store.runId())"));
        assertTrue(watchdog.contains("bootstrapWatchdogPhase.equals(phase)"));
        assertTrue(watchdog.contains("pauseBootstrapIfTimedOut"));
        assertTrue(service.contains("handler.removeCallbacks(bootstrapWatchdogRunnable)"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start), to = source.indexOf(end, from + start.length());
        assertTrue("start marker missing: " + start, from >= 0);
        assertTrue("end marker missing: " + end, to > from);
        return source.substring(from, to);
    }
}
