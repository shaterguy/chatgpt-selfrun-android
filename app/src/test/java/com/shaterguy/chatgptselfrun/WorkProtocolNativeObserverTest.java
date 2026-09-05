package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure classification and pass-through contract tests for native Work request observation. */
public final class WorkProtocolNativeObserverTest {
    @Test public void canonicalClassificationUsesMethodOriginAndNormalizedPathOnly() {
        assertTrue(WorkProtocolNativeObserver.isCanonical(
                "POST", "https://chatgpt.com/backend-api/f/conversation"));
        assertTrue(WorkProtocolNativeObserver.isCanonical(
                "post", "https://www.chatgpt.com/backend-api/f/conversation/?ignored=1#fragment"));
        assertFalse(WorkProtocolNativeObserver.isCanonical(
                "GET", "https://chatgpt.com/backend-api/f/conversation"));
        assertFalse(WorkProtocolNativeObserver.isCanonical(
                "POST", "http://chatgpt.com/backend-api/f/conversation"));
        assertFalse(WorkProtocolNativeObserver.isCanonical(
                "POST", "https://evil.example/backend-api/f/conversation"));
        assertFalse(WorkProtocolNativeObserver.isCanonical(
                "POST", "https://chatgpt.com/backend-api/f/conversation-extra"));
        assertFalse(WorkProtocolNativeObserver.isCanonical(
                "POST", "https://chatgpt.com/backend-api/accounts/check"));
    }

    @Test public void onlySubmissionAndTurnWaitPhasesAreObservable() {
        assertTrue(WorkProtocolNativeObserver.observablePhase(SelfRunStore.PHASE_BOOTSTRAP_SEND));
        assertTrue(WorkProtocolNativeObserver.observablePhase(SelfRunStore.PHASE_SEND_CONTINUE));
        assertTrue(WorkProtocolNativeObserver.observablePhase(SelfRunStore.PHASE_WAIT_TURN_COMPLETION));
        assertFalse(WorkProtocolNativeObserver.observablePhase(SelfRunStore.PHASE_BOOTSTRAP));
        assertFalse(WorkProtocolNativeObserver.observablePhase(SelfRunStore.PHASE_APPLY_PREFS));
        assertFalse(WorkProtocolNativeObserver.observablePhase(SelfRunStore.PHASE_POST_PROTOCOL_DRIVE_SYNC));
    }

    @Test public void onlyWorkRunsAreObservable() {
        assertTrue(WorkProtocolNativeObserver.observableMode(SelfRunStore.MODE_WORK));
        assertFalse(WorkProtocolNativeObserver.observableMode("HYBRID"));
        assertFalse(WorkProtocolNativeObserver.observableMode(SelfRunStore.MODE_CHAT));
        assertFalse(WorkProtocolNativeObserver.observableMode(""));
    }

    @Test public void nativeObserversAreReadOnlyAndDoNotTouchSensitiveRequestMaterial() throws Exception {
        String source = source("WorkProtocolNativeObserver.java");
        assertTrue(source.contains("return null;"));
        assertTrue(source.contains("SOURCE_WEBVIEW = \"native_webview\""));
        assertTrue(source.contains("SOURCE_SERVICE_WORKER = \"native_service_worker\""));
        assertTrue(source.contains("ROUTE_CANONICAL_CONVERSATION = \"canonical_conversation\""));
        assertTrue(source.contains("\"WORK_PROTOCOL_TRANSPORT\""));
        assertTrue(source.contains(";outcome=canonical_request"));
        assertFalse(source.contains("getRequestHeaders"));
        assertFalse(source.contains("WebResourceResponse("));
        assertFalse(source.contains("Cookie"));
        assertFalse(source.contains("Authorization"));
        assertFalse(source.contains("response body"));
        assertFalse(source.contains("POST body"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
