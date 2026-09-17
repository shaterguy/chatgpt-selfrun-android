package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRunFirebaseBuildConfigPolicyTest {
    @Test public void firebaseCandidateConfigGateRequiresAllFourPublicValuesWithoutPrintingSecrets() throws Exception {
        String gate = text(resolve("tools/verify_selfrun_firebase_config_env.sh",
                "../tools/verify_selfrun_firebase_config_env.sh"));
        for (String name : new String[]{"SELFRUN_FIREBASE_API_KEY", "SELFRUN_FIREBASE_APPLICATION_ID",
                "SELFRUN_FIREBASE_PROJECT_ID", "SELFRUN_FIREBASE_SENDER_ID"}) {
            assertTrue(gate.contains(name));
        }
        assertTrue(gate.contains("${!name:-}"));
        assertTrue(gate.contains("Firebase candidate configuration missing"));
        assertFalse(gate.contains("echo \"$SELFRUN_FIREBASE_API_KEY\""));
    }

    @Test public void appReadsPublicConfigFromEnvironmentWithoutPrivateFirebaseCredential() throws Exception {
        String gradle = text(resolve("app/build.gradle", "build.gradle"));
        for (String name : new String[]{"SELFRUN_FIREBASE_API_KEY", "SELFRUN_FIREBASE_APPLICATION_ID",
                "SELFRUN_FIREBASE_PROJECT_ID", "SELFRUN_FIREBASE_SENDER_ID"}) {
            assertTrue(gradle.contains("configValue('" + name + "', '')"));
        }
        assertFalse(gradle.contains("FIREBASE_PRIVATE_KEY"));
        assertFalse(gradle.contains("FIREBASE_CLIENT_EMAIL"));
    }

    @Test public void firebaseApplicationIdIsBoundToTheInstalledFormalOrTestPackage() throws Exception {
        String source = text(resolve(
                "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunFirebase.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunFirebase.java"));
        assertTrue(source.contains("FORMAL_PACKAGE = \"com.shaterguy.chatgptselfrun.drive\""));
        assertTrue(source.contains("TEST_PACKAGE = \"com.shaterguy.chatgptselfrun.drive.test\""));
        assertTrue(source.contains("FORMAL_FIREBASE_APPLICATION_ID = \"1:859485943787:android:feab87243808eb6ca30bae\""));
        assertTrue(source.contains("TEST_FIREBASE_APPLICATION_ID = \"1:859485943787:android:c7eed0b22c51fa42a30bae\""));
        assertTrue(source.contains("FORMAL_PACKAGE.equals(BuildConfig.APPLICATION_ID)"));
        assertTrue(source.contains("TEST_PACKAGE.equals(BuildConfig.APPLICATION_ID)"));
        assertTrue(source.contains("present(firebaseApplicationId())"));
        assertTrue(source.contains(".setApplicationId(firebaseApplicationId())"));
    }

    private static Path resolve(String repositoryPath, String appPath) {
        Path path = Path.of(repositoryPath);
        return Files.exists(path) ? path : Path.of(appPath);
    }

    private static String text(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
