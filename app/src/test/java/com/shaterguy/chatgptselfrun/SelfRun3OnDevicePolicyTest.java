package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRun3OnDevicePolicyTest {
    @Test public void serverTransportRequiresBothExplicitBuildGateAndServerMode() {
        assertFalse(SelfRunServerFeaturePolicy.serverPathEnabled(
                false, SelfRun3RuntimeSettings.WorkMode.SERVER));
        assertFalse(SelfRunServerFeaturePolicy.serverPathEnabled(
                true, SelfRun3RuntimeSettings.WorkMode.ON_DEVICE));
        assertTrue(SelfRunServerFeaturePolicy.serverPathEnabled(
                true, SelfRun3RuntimeSettings.WorkMode.SERVER));
    }

    @Test public void applicationOnlyInitializesServerStackBehindFeaturePolicy() throws Exception {
        String application = source("SelfRunApplication.java");
        assertTrue(application.contains("new SelfRun3RuntimeSettings(context)"));
        assertTrue(application.contains("if (SelfRunServerFeaturePolicy.enabled(context))"));
        assertTrue(application.contains("SelfRunFirebase.initialize(context)"));
        assertTrue(application.contains("SelfRunPushAckWorker.cancel(context)"));
        assertTrue(application.contains("SelfRunServerRecoveryWorker.cancel(context)"));
    }

    @Test public void normalBuildDisablesMessagingComponentAndWorkersAreSelfGating() throws Exception {
        String manifest = text(resolve("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml"));
        String ack = source("SelfRunPushAckWorker.java");
        String recovery = source("SelfRunServerRecoveryWorker.java");
        String fcm = source("SelfRunFirebaseMessagingService.java");
        assertTrue(manifest.contains("selfRunServerFeaturesEnabled"));
        assertTrue(ack.contains("SelfRunServerFeaturePolicy.enabled(context)"));
        assertTrue(ack.contains("cancelUniqueWork(UNIQUE_WORK)"));
        assertTrue(recovery.contains("SelfRunServerFeaturePolicy.enabled(context)"));
        assertTrue(fcm.contains("if (!SelfRunServerFeaturePolicy.enabled(this)) return;"));
    }

    @Test public void coordinatorKeepsServerRegistrationOutOfGeneralOnDeviceWait() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("SelfRunServerFeaturePolicy.buildEnabled()"));
        assertTrue(coordinator.contains("serverWaiting && SelfRunServerFeaturePolicy.enabled(service)"));
        assertTrue(coordinator.contains("if (!SelfRunServerFeaturePolicy.enabled(service)) return false;"));
        assertTrue(coordinator.contains("runDriveStep(execution, DriveStep.READ_RESULT)"));
        assertTrue(coordinator.contains("runtimeSettings.resultPollMs()"));
    }

    private static String source(String name) throws Exception {
        return text(resolve("app/src/main/java/com/shaterguy/chatgptselfrun/" + name,
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
