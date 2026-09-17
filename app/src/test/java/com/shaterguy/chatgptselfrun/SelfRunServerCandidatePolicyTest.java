package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRunServerCandidatePolicyTest {
    @Test public void dev2IdentityAndPublicProductionGatewayArePinnedForTheTestCandidate() throws Exception {
        String gradle = text(resolve("app/build.gradle", "build.gradle"));
        assertTrue(gradle.contains("selfRunDriveVersionCode = 3026002"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '3.2.6-dev2'"));
        assertTrue(gradle.contains("https://selfrun-command-bridge.vercel.app"));
        assertFalse(gradle.contains("selfrun-command-bridge-git-v325-dev3-shaterguy.vercel.app"));
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
        assertTrue(source.contains("String[] labels = {\"서버를 통해 실행\", \"온디바이스\"}"));
        assertTrue(source.contains("Drive 변경을 빠르게 감지"));
        assertTrue(source.contains("휴대폰이 일정 간격으로 확인"));
    }

    @Test public void recoveryPlanningSnapshotsMainThreadFallbackStateBeforeIo() throws Exception {
        String source = source("SelfRun3Coordinator.java");
        assertTrue(source.contains("fallbackSnapshot = new HashSet<>(serverFallbackTurns)"));
        assertTrue(source.contains("fallbackSnapshot.contains(execution.turnId())"));
    }

    @Test public void candidateCiRunsGatewayTestsAndTypecheckBeforeAndroidCompile() throws Exception {
        String workflow = text(resolve(
                ".github/workflows/build-selfrun-v3-candidate.yml",
                "../.github/workflows/build-selfrun-v3-candidate.yml"));
        int node = workflow.indexOf("GATEWAY_TEST - command-bridge tests and typecheck");
        int android = workflow.indexOf("STATIC_PREFLIGHT - V3 policy and all test-source compile");
        assertTrue(node >= 0);
        assertTrue(workflow.contains("working-directory: command-bridge"));
        assertTrue(workflow.contains("npm ci"));
        assertTrue(workflow.contains("npm test"));
        assertTrue(workflow.contains("npm run typecheck"));
        assertTrue(android > node);
    }

    @Test public void readmeDocumentsServerFallbackAndAllFirebaseInputs() throws Exception {
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
        assertTrue(readme.contains("fallback"));
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
