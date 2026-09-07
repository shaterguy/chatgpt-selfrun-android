package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UserImmediateInputPolicyTest {
    @Test public void immediateAttemptIsLimitedToTheCurrentV3AssistantResponse() {
        String conversation = "https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc";
        assertTrue(UserImmediateInputCoordinator.immediateEligible(
                true, false, false, SelfRun3Coordinator.PHASE_WAITING, conversation, true));
        assertFalse(UserImmediateInputCoordinator.immediateEligible(
                true, false, false, SelfRun3Coordinator.PHASE_RECONCILING, conversation, true));
        assertFalse(UserImmediateInputCoordinator.immediateEligible(
                true, true, false, SelfRun3Coordinator.PHASE_WAITING, conversation, true));
        assertFalse(UserImmediateInputCoordinator.immediateEligible(
                true, false, false, SelfRun3Coordinator.PHASE_WAITING, "", true));
        assertFalse(UserImmediateInputCoordinator.immediateEligible(
                true, false, false, SelfRun3Coordinator.PHASE_WAITING, conversation, false));
    }

    @Test public void onlyAnIdenticalReservationIsReleasedBeforeImmediateClick() {
        assertTrue(UserImmediateInputCoordinator.matchingReservation("same", "same"));
        assertFalse(UserImmediateInputCoordinator.matchingReservation("scheduled later", "send now"));
        assertFalse(UserImmediateInputCoordinator.matchingReservation("", "send now"));
    }

    @Test public void wiringKeepsV3CompletionOwnershipSeparateFromImmediateSendDetection() throws Exception {
        String immediateDom = src("UserImmediateInputDom.java");
        String coordinator = src("UserImmediateInputCoordinator.java");
        String host = src("HeadlessWebViewHost.java");
        String activity = src("MainActivity.java");

        assertTrue(immediateDom.contains("const runningStop=()=>"));
        assertTrue(immediateDom.contains("if(!runningStop())"));
        assertTrue(immediateDom.contains("const forceSend=()=>"));
        assertTrue(immediateDom.contains("IMMEDIATE_INPUT_SEND_READY"));
        assertTrue(immediateDom.contains("IMMEDIATE_INPUT_CLICK_UNCERTAIN"));
        assertTrue(immediateDom.contains("send.click()"));
        assertFalse(immediateDom.contains("requestComposerSubmit"));
        assertTrue(coordinator.contains("SelfRun3Coordinator.PHASE_WAITING"));
        assertFalse(coordinator.contains("PHASE_WAIT_TURN_COMPLETION"));
        assertTrue(coordinator.contains("UserNextInputStore.save(runId, text)"));
        assertTrue(coordinator.contains("UserNextInputStore.delete(runId)"));
        assertTrue(coordinator.contains("cleanupAfterAmbiguousClick"));
        assertTrue(coordinator.contains("fallback suppressed to prevent duplicate"));
        assertFalse(coordinator.contains("void click(int retry)"));
        assertTrue(host.contains("static WebView activeWebView()"));
        assertTrue(host.contains("if (activeWebView == webView) activeWebView = null"));
        assertTrue(activity.contains("\"즉시 보내기\""));
        assertTrue(activity.contains("UserImmediateInputCoordinator.submit"));
    }

    @Test public void forceInputReattachesBeforeSendAndUsesComposerGateAfterConfirmedSend() throws Exception {
        String coordinator = src("UserImmediateInputCoordinator.java");
        String host = src("HeadlessWebViewHost.java");

        assertTrue(host.contains("static HeadlessWebViewHost activeHost()"));
        assertTrue(coordinator.contains("HeadlessWebViewHost.activeHost()"));
        assertTrue(coordinator.contains("host.attachOutput()"));
        assertTrue(coordinator.contains("restoreOutputAfterAttempt(true)"));
        assertTrue(coordinator.contains("host.detachOutputWhenComposerReady()"));
        assertTrue(coordinator.contains("restoreOutputAfterAttempt(false)"));
        assertTrue(coordinator.contains("else if (restoreDetached)"));
        assertTrue(coordinator.contains("host.detachOutput()"));
        assertFalse(coordinator.contains("view.reload()"));
        assertFalse(coordinator.contains("view.loadUrl("));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
