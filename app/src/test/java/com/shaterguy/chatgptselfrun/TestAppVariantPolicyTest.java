package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class TestAppVariantPolicyTest {
    @Test public void v2DevelopmentKeepsDedicatedFormalAndTestIdentity() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        String manifest = read("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml");
        assertTrue(gradle.contains("applicationId 'com.shaterguy.chatgptselfrun.v2'"));
        assertFalse(gradle.contains("applicationId 'com.shaterguy.chatgptselfrun.drive'"));
        assertTrue(gradle.contains("qaApp {"));
        assertTrue(gradle.contains("applicationIdSuffix '.test'"));
        assertTrue(gradle.contains("selfRunAppLabel: 'SelfRun 2.0 TEST'"));
        assertTrue(manifest.contains("android:label=\"${selfRunAppLabel}\""));
        assertTrue(manifest.contains(".SelfRunRestartActivity"));
        assertTrue(manifest.contains(".SelfRunRestartActivity\" android:exported=\"false\""));
    }

    @Test public void testSigningLineageIsDomainSeparatedFromFormalSigning() throws Exception {
        String formal = read("tools/derive_signing_identity.py", "../tools/derive_signing_identity.py");
        String test = read("tools/derive_test_signing_identity.py", "../tools/derive_test_signing_identity.py");
        String signer = read("tools/sign_test.sh", "../tools/sign_test.sh");
        assertTrue(formal.contains("chatgpt-selfrun-signing-v1|"));
        assertTrue(test.contains("chatgpt-selfrun-test-signing-v1|"));
        assertTrue(test.contains("ChatGPT SelfRun Android Test"));
        assertFalse(test.contains("b\"chatgpt-selfrun-signing-v1|\" + secret"));
        assertTrue(signer.contains("2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e"));
    }

    @Test public void v2DevPushUsesOnlyDedicatedV2TestWorkflow() throws Exception {
        String v1Test = read(".github/workflows/build-drive-test.yml", "../.github/workflows/build-drive-test.yml");
        String v2Test = read(".github/workflows/build-selfrun-v2-test.yml", "../.github/workflows/build-selfrun-v2-test.yml");
        assertTrue(v1Test.contains("- 'selfrun-drive/v*-dev*'"));
        assertFalse(v1Test.contains("selfrun-drive-v2/"));
        assertTrue(v2Test.contains("- 'selfrun-drive-v2/v2.*-dev*'"));
        assertTrue(v2Test.contains(":app:assembleQaApp"));
        assertTrue(v2Test.contains("com.shaterguy.chatgptselfrun.v2.test"));
        assertTrue(v2Test.contains("chatgpt-selfrun-v2-test-v"));
    }

    @Test public void restartClaimIsWiredToProcessOwnership() throws Exception {
        String activity = read(
                "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunRestartActivity.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunRestartActivity.java");
        assertTrue(activity.contains("claimProcessId"));
        assertTrue(activity.contains("SelfRunRestartPolicy.processClaimConflicts"));
        assertTrue(activity.contains("requireClaimOwnership();"));
    }

    @Test public void authDependencyVersionMustRemainIndependentFromAppVersion() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        assertTrue(gradle.contains("play-services-auth:21.6.0"));
        assertFalse(gradle.contains("play-services-auth:21.6.1-dev1"));
    }

    private static String read(String first, String second) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
