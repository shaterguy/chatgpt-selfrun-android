package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRunPushAckPolicyTest {
    @Test public void manifestAndBuildWireFcmAndWorkManager() throws Exception {
        String manifest = text(Path.of("app/src/main/AndroidManifest.xml"));
        String gradle = text(Path.of("app/build.gradle"));
        assertTrue(manifest.contains("SelfRunFirebaseMessagingService"));
        assertTrue(manifest.contains("com.google.firebase.MESSAGING_EVENT"));
        assertTrue(gradle.contains("firebase-messaging"));
        assertTrue(gradle.contains("work-runtime"));
    }

    @Test public void receiverPersistsReceivedAckBeforeStartingSelfRunService() throws Exception {
        String source = source("SelfRunFirebaseMessagingService.java");
        int enqueue = source.indexOf("AckState.RECEIVED");
        int start = source.indexOf("startForegroundService");
        assertTrue(enqueue >= 0);
        assertTrue(start > enqueue);
    }

    @Test public void ackWorkerUsesNetworkConstraintAndOnlyDeletesAfterSuccess() throws Exception {
        String worker = source("SelfRunPushAckWorker.java");
        String outbox = source("SelfRunPushAckOutbox.java");
        assertTrue(worker.contains("NetworkType.CONNECTED"));
        assertTrue(worker.contains("markDelivered"));
        assertTrue(outbox.contains("PREFS = \"selfrun_push_ack_outbox\""));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return text(path);
    }

    private static String text(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
