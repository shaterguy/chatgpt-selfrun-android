package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

@Category(ServerOnly.class)
public final class SelfRunServerCandidatePolicyTest {
    @Test public void dev331Dev2IdentityAndExplicitServerGateArePinned() throws Exception {
        String gradle = text(resolve("app/build.gradle", "build.gradle"));
        assertTrue(gradle.contains("selfRunDriveVersionCode = 3033003"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '3.3.1-dev3'"));
        assertTrue(gradle.contains("SELFRUN_SERVER_FEATURES_ENABLED"));
        assertTrue(gradle.contains("def selfRunServerFeaturesEnabled = true"));
        assertTrue(gradle.contains("selfRunServerChecks"));
        assertFalse(gradle.contains("SELFRUN_SERVER_FEATURES_ENABLED', selfRunServerChecks.toString()"));
    }

    @Test public void everyAndroidGatewayCallerUsesTheBuildConfiguredEndpoint() throws Exception {
        String serverWatch = source("SelfRunServerWatch.java");
        String ackWorker = source("SelfRunPushAckWorker.java");
        assertTrue(serverWatch.contains("new SelfRunPushGatewayClient(BuildConfig.SELFRUN_PUSH_GATEWAY_URL)"));
        assertTrue(ackWorker.contains("new SelfRunPushGatewayClient(BuildConfig.SELFRUN_PUSH_GATEWAY_URL)"));
    }

    @Test public void settingsCopyMatchesTheApprovedTwoModeDesign() throws Exception {
        String source = source("SelfRunLogMenuActivity.java");
        assertTrue(source.contains("\"작업 모드\""));
        assertTrue(source.contains("String[] labels = {\"온디바이스(기본·권장)\", \"서버를 통해 실행\"}"));
        assertTrue(source.contains("runtimeSettings.saveWorkMode(mode)"));
        assertTrue(source.contains("온디바이스(기본·권장)"));
        assertFalse(source.contains("일반 빌드는 온디바이스 모드로 고정되어 있습니다."));
    }

    @Test public void recoveryPlanningSnapshotsMainThreadFallbackStateBeforeIo() throws Exception {
        String source = source("SelfRun3Coordinator.java");
        assertTrue(source.contains("fallbackSnapshot = new HashSet<>(serverFallbackTurns)"));
        assertTrue(source.contains("fallbackSnapshot.contains(execution.turnId())"));
    }

    @Test public void candidateCiValidatesGatewayFirebaseAndServerPathBeforeTestDelivery() throws Exception {
        String workflow = text(resolve(
                ".github/workflows/build-selfrun-v3-candidate.yml",
                "../.github/workflows/build-selfrun-v3-candidate.yml"));
        assertTrue(workflow.contains("STATIC_PREFLIGHT - V3 policy and all test-source compile"));
        assertTrue(workflow.contains("GATEWAY_TEST - command-bridge tests and typecheck"));
        assertTrue(workflow.contains("FIREBASE_CONFIG_PREFLIGHT"));
        assertTrue(workflow.contains("actions/setup-node"));
        assertTrue(workflow.contains("-PselfRunServerChecks=true"));
        assertFalse(workflow.contains(":app:assembleRelease"));
    }

    @Test public void readmeDocumentsOnDeviceDefaultAndOptionalServerInputs() throws Exception {
        String readme = text(resolve("README.md", "../README.md"));
        assertTrue(readme.contains("SERVER"));
        assertTrue(readme.contains("ON_DEVICE"));
        assertTrue(readme.contains("SELFRUN_FIREBASE_API_KEY"));
        assertTrue(readme.contains("SELFRUN_FIREBASE_APPLICATION_ID"));
        assertTrue(readme.contains("SELFRUN_FIREBASE_PROJECT_ID"));
        assertTrue(readme.contains("SELFRUN_FIREBASE_SENDER_ID"));
        assertTrue(readme.contains("SELFRUN_PUSH_GATEWAY_URL"));
        assertTrue(readme.contains("FIREBASE_CLIENT_EMAIL"));
        assertTrue(readme.contains("FIREBASE_PRIVATE_KEY"));
        assertTrue(readme.contains("-PselfRunServerChecks=true"));
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
