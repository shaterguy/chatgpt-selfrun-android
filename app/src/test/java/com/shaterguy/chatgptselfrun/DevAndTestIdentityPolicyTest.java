package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class DevAndTestIdentityPolicyTest {
    @Test public void devUsesOriginalInstalledTestPackageAndNotNewDevPackage() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        assertTrue(gradle.contains("selfRunDriveVersionCode = 4002026"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '4.0.11'"));
        assertTrue(gradle.contains("applicationId 'com.shaterguy.chatgptselfrun.drive'"));
        assertTrue(gradle.contains("applicationIdSuffix '.test'"));
        assertFalse(gradle.contains("applicationIdSuffix '.dev'"));
        assertFalse(gradle.contains("devApp {"));
        assertTrue(gradle.contains("selfRunAppLabel: 'SelfRun Drive DEV'"));
    }

    @Test public void buildPublishesOneInPlaceDevApkUsingOldTestSigner() throws Exception {
        String workflow = read(".github/workflows/build-selfrun-inplace-dev.yml",
                "../.github/workflows/build-selfrun-inplace-dev.yml");
        assertTrue(workflow.contains("selfrun-v4/v4.0.11-dev4"));
        assertTrue(workflow.contains("proof['source_sha']==os.environ['GITHUB_SHA']"));
        assertTrue(workflow.contains("proof['unsigned_sha256']"));
        assertFalse(workflow.contains(":app:assembleQaApp"));
        assertFalse(workflow.contains(":app:assembleDevApp"));
        assertFalse(workflow.contains(":app:assembleRelease"));
        assertTrue(workflow.contains("tools/sign_test.sh"));
        assertFalse(workflow.contains("tools/sign_release.sh"));
        assertTrue(workflow.contains("com.shaterguy.chatgptselfrun.drive.test"));
        assertFalse(workflow.contains("com.shaterguy.chatgptselfrun.drive.dev"));
        assertTrue(workflow.contains("versionCode='4002026'"));
        assertTrue(workflow.contains("2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e"));
        assertFalse(workflow.contains("android-emulator-runner"));
        assertTrue(workflow.contains("SelfRun-Drive-v4.0.11-dev4.apk"));
        assertFalse(Files.exists(Path.of(".github/workflows/build-selfrun-dev-separated.yml")));
    }
    private static String read(String first, String second) throws Exception {
        Path path = Path.of(first);
        if (!Files.exists(path)) path = Path.of(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
