package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class SelfRun3CoreCiBoundaryTest {
    @Test public void generalJvmSuiteExcludesDormantServerCategory() throws Exception {
        String gradle = text(resolve("app/build.gradle", "build.gradle"));
        assertTrue(gradle.contains("excludeCategories 'com.shaterguy.chatgptselfrun.ServerOnly'"));
        assertTrue(gradle.contains("selfRunServerChecks"));
    }

    @Test public void serverCapableCandidateRunsGatewayFirebaseAndServerRegressionGates() throws Exception {
        String workflow = text(resolve(".github/workflows/build-selfrun-v3-candidate.yml",
                "../.github/workflows/build-selfrun-v3-candidate.yml"));
        assertTrue(workflow.contains("FIREBASE_CONFIG_PREFLIGHT"));
        assertTrue(workflow.contains("GATEWAY_TEST - command-bridge tests and typecheck"));
        assertTrue(workflow.contains("actions/setup-node"));
        assertTrue(workflow.contains(":app:compileQaAppAndroidTestJavaWithJavac"));
        assertTrue(workflow.contains("-PselfRunServerChecks=true :app:testQaAppUnitTest"));
        assertFalse(workflow.contains(":app:assembleRelease"));
    }

    @Test public void currentIdentityAndOnDeviceMigrationAreExplicitCoreContracts() throws Exception {
        String gradle = text(resolve("app/build.gradle", "build.gradle"));
        String settings = source("SelfRun3RuntimeSettings.java");
        assertTrue(gradle.contains("selfRunDriveVersionCode = 3030012"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '3.2.10-dev3'"));
        assertTrue(settings.contains("DEFAULT_WORK_MODE = WorkMode.ON_DEVICE"));
        assertTrue(settings.contains("KEY_ON_DEVICE_DEFAULT_MIGRATION_VERSION"));
        assertTrue(settings.contains("putString(KEY_WORK_MODE, WorkMode.ON_DEVICE.name())"));
    }

    @Test public void restartAuthorityRemainsCoreWhileServerAssertionsAreCategorized() throws Exception {
        String authority = text(resolve(
                "app/src/test/java/com/shaterguy/chatgptselfrun/SelfRun3ResultAuthorityDriveAuthRegressionTest.java",
                "src/test/java/com/shaterguy/chatgptselfrun/SelfRun3ResultAuthorityDriveAuthRegressionTest.java"));
        assertTrue(authority.contains("scenario16RestartAuthorityStillLoadsExactExecution"));
        assertTrue(authority.contains("ledger.loadExecution"));
        assertTrue(authority.contains("@Category(ServerOnly.class)"));
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
