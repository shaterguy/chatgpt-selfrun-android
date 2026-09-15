package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRunPushAckPolicyTest {
    @Test public void manifestAndBuildWireFcmAndWorkManager() throws Exception {
        String manifest = text(resolve("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml"));
        String gradle = text(resolve("app/build.gradle", "build.gradle"));
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
        return text(resolve(
                "app/src/main/java/com/shaterguy/chatgptselfrun/" + name,
                "src/main/java/com/shaterguy/chatgptselfrun/" + name));
    }

    private static Path resolve(String repositoryPath, String appPath) {
        Path path = Path.of(repositoryPath);
        return Files.exists(path) ? path : Path.of(appPath);
    }

    private static String text(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
