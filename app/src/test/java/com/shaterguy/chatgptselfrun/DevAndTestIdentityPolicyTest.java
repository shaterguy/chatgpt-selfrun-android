package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class DevAndTestIdentityPolicyTest {
    @Test public void devAndTestRemainSeparateFromFormalAndKeepUpdateLineage() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        assertTrue(gradle.contains("selfRunDriveVersionCode = 4002024"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '4.0.11-dev2'"));
        assertTrue(gradle.contains("applicationId 'com.shaterguy.chatgptselfrun.drive'"));
        assertTrue(gradle.contains("applicationIdSuffix '.dev'"));
        assertTrue(gradle.contains("applicationIdSuffix '.test'"));
        assertTrue(gradle.contains("SelfRun Drive DEV"));
        String workflow = read(".github/workflows/build-selfrun-dev-separated.yml",
                "../.github/workflows/build-selfrun-dev-separated.yml");
        assertTrue(workflow.contains(":app:assembleDevApp :app:assembleQaApp"));
        assertTrue(workflow.contains("tools/sign_test.sh"));
        assertFalse(workflow.contains("tools/sign_release.sh"));
        assertFalse(workflow.contains(":app:assembleRelease"));
        assertTrue(workflow.contains("com.shaterguy.chatgptselfrun.drive.dev"));
        assertTrue(workflow.contains("com.shaterguy.chatgptselfrun.drive.test"));
        assertTrue(workflow.contains("versionCode='4002024'"));
        assertTrue(workflow.contains("2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e"));
        assertFalse(workflow.contains("android-emulator-runner"));
    }
    private static String read(String first, String second) throws Exception {
        Path path = Path.of(first);
        if (!Files.exists(path)) path = Path.of(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
