package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Static security, relay, and Service Worker transport contract for dev4 capture. */
public final class WorkProtocolTransportCaptureContractTest {
    @Test public void captureUsesWorkRunOriginSchemaAndShortCanonicalDedupe() throws Exception {
        String source = source("WorkProtocolTransportCaptureScript.java");
        assertTrue(source.contains("DUPLICATE_MS=750"));
        assertTrue(source.contains("event.origin!==location.origin"));
        assertTrue(source.contains("safe(data.runId)!==runId()"));
        assertTrue(source.contains("data.relay!==RELAY"));
        assertTrue(source.contains("data.version!==1"));
        assertTrue(source.contains("outcome:'duplicate_observation'"));
        assertTrue(source.contains("observeNativeCanonical"));
        assertTrue(source.contains("subframe_"));
    }

    @Test public void serviceWorkerAndPortsReuseExistingIngressDecoder() throws Exception {
        String source = source("WorkProtocolTransportCaptureScript.java");
        assertTrue(source.contains("navigator.serviceWorker.addEventListener('message'"));
        assertTrue(source.contains("event.ports"));
        assertTrue(source.contains("port.start?.()"));
        assertTrue(source.contains("observeTransportData?.(event.data,'service_worker_message')"));
        assertTrue(source.contains("observeTransportData?.(portEvent.data,'service_worker_message_port')"));
        assertFalse(source.contains("encoded_item"));
        assertFalse(source.contains("decodeBase64"));
    }

    @Test public void relayStripsAssistantContentAndKeepsOnlySemanticMetadata() throws Exception {
        String source = source("WorkProtocolTransportCaptureScript.java");
        assertTrue(source.contains("minimalMessage"));
        assertTrue(source.contains("minimalSemantic"));
        assertFalse(source.contains("content.parts"));
        assertFalse(source.contains("requestBody"));
        assertFalse(source.contains("responseBody"));
        assertFalse(source.contains("getRequestHeaders"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
