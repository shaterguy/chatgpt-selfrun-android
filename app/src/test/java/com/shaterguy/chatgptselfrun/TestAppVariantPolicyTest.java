package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class TestAppVariantPolicyTest {
    @Test public void v31KeepsFormalAndTestInstallIdentitiesSeparated() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        String manifest = read("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml");
        assertTrue(gradle.contains("applicationId 'com.shaterguy.chatgptselfrun.drive'"));
        assertTrue(gradle.contains("selfRunAppLabel: 'SelfRun Drive TEST'"));
        assertTrue(gradle.matches("(?s).*selfRunDriveVersionCode = 30[0-9]+.*"));
        assertTrue(gradle.matches("(?s).*selfRunDriveVersionName = '3\\.1\\.0(?:-(?:dev|rc)[0-9]+)?'.*"));
        assertTrue(gradle.contains("applicationIdSuffix '.test'"));
        assertTrue(manifest.contains("android:label=\"${selfRunAppLabel}\""));
    }

    @Test public void candidateWorkflowOwns31DevBuilds() throws Exception {
        String candidate = read(".github/workflows/build-selfrun-v3-candidate.yml", "../.github/workflows/build-selfrun-v3-candidate.yml");
        assertTrue(candidate.contains("selfrun-v3/v3.1.0-dev*"));
        assertTrue(candidate.contains("selfrun-v3/v3.1.0-rc*"));
        assertTrue(candidate.contains(":app:compileQaAppJavaWithJavac"));
        assertTrue(candidate.contains(":app:compileQaAppAndroidTestJavaWithJavac"));
        assertTrue(candidate.contains(":app:testQaAppUnitTest"));
        assertTrue(candidate.contains(":app:assembleQaApp"));
        assertTrue(candidate.contains("com.shaterguy.chatgptselfrun.drive.test"));
    }

    @Test public void testSigningLineageRemainsSeparated() throws Exception {
        String test = read("tools/derive_test_signing_identity.py", "../tools/derive_test_signing_identity.py");
        String signer = read("tools/sign_test.sh", "../tools/sign_test.sh");
        assertTrue(test.contains("chatgpt-selfrun-test-signing-v1|"));
        assertTrue(test.contains("ChatGPT SelfRun Android Test"));
        assertTrue(signer.contains("2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e"));
    }

    @Test public void authDependencyVersionRemainsIndependentFromAppVersion() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        assertTrue(gradle.contains("play-services-auth:21.6.0"));
        assertFalse(gradle.contains("play-services-auth:21.6.1-dev"));
    }

    private static String read(String first, String second) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
