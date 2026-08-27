package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UserImmediateInputPolicyTest {
    @Test public void immediateAttemptIsLimitedToTheCurrentAssistantResponse() {
        assertTrue(UserImmediateInputCoordinator.immediateEligible(
                true, false, false, SelfRunStore.PHASE_WAIT_TURN_COMPLETION,
                "https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc", true));
        assertFalse(UserImmediateInputCoordinator.immediateEligible(
                true, false, false, SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC,
                "https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc", true));
        assertFalse(UserImmediateInputCoordinator.immediateEligible(
                true, true, false, SelfRunStore.PHASE_WAIT_TURN_COMPLETION,
                "https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc", true));
        assertFalse(UserImmediateInputCoordinator.immediateEligible(
                true, false, false, SelfRunStore.PHASE_WAIT_TURN_COMPLETION, "", true));
        assertFalse(UserImmediateInputCoordinator.immediateEligible(
                true, false, false, SelfRunStore.PHASE_WAIT_TURN_COMPLETION,
                "https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc", false));
    }

    @Test public void onlyAnIdenticalReservationIsReleasedBeforeImmediateClick() {
        assertTrue(UserImmediateInputCoordinator.matchingReservation("same", "same"));
        assertFalse(UserImmediateInputCoordinator.matchingReservation("scheduled later", "send now"));
        assertFalse(UserImmediateInputCoordinator.matchingReservation("", "send now"));
    }

    @Test public void wiringKeepsCompletionObserverSemanticsSeparateFromImmediateSendDetection() throws Exception {
        String existingDom = src("SelfRunContinuationDom.java");
        String immediateDom = src("UserImmediateInputDom.java");
        String coordinator = src("UserImmediateInputCoordinator.java");
        String host = src("HeadlessWebViewHost.java");
        String activity = src("MainActivity.java");

        int existingStop = existingDom.indexOf("const stop=controls.find(isStop);if(stop)return");
        int existingSend = existingDom.indexOf("const send=calibrated", existingStop);
        assertTrue(existingStop >= 0 && existingSend > existingStop);
        assertTrue(immediateDom.contains("const runningStop=()=>"));
        assertTrue(immediateDom.contains("if(!runningStop())"));
        assertTrue(immediateDom.contains("const forceSend=()=>"));
        assertTrue(immediateDom.contains("IMMEDIATE_INPUT_SEND_READY"));
        assertTrue(immediateDom.contains("IMMEDIATE_INPUT_CLICK_UNCERTAIN"));
        assertTrue(immediateDom.contains("send.click()"));
        assertFalse(immediateDom.contains("requestComposerSubmit"));
        assertTrue(coordinator.contains("SelfRunStore.PHASE_WAIT_TURN_COMPLETION"));
        assertTrue(coordinator.contains("UserNextInputStore.save(runId, text)"));
        assertTrue(coordinator.contains("UserNextInputStore.delete(runId)"));
        assertTrue(coordinator.contains("cleanupAfterAmbiguousClick"));
        assertTrue(coordinator.contains("fallback suppressed to prevent duplicate"));
        assertFalse(coordinator.contains("void click(int retry)"));
        assertTrue(host.contains("static WebView activeWebView()"));
        assertTrue(host.contains("if (activeWebView == webView) activeWebView = null"));
        assertTrue(activity.contains("\"즉시 강제입력\""));
        assertTrue(activity.contains("UserImmediateInputCoordinator.submit"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
