package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRunFirebaseBuildConfigPolicyTest {
    @Test public void candidateCiInjectsAndRequiresAllFourPublicFirebaseValues() throws Exception {
        String workflow = text(resolve(".github/workflows/build-selfrun-v3-candidate.yml",
                "../.github/workflows/build-selfrun-v3-candidate.yml"));
        for (String name : new String[]{"SELFRUN_FIREBASE_API_KEY", "SELFRUN_FIREBASE_APPLICATION_ID",
                "SELFRUN_FIREBASE_PROJECT_ID", "SELFRUN_FIREBASE_SENDER_ID"}) {
            assertTrue(workflow.contains(name + ": ${{ secrets." + name + " }}"));
        }
        assertTrue(workflow.contains("FIREBASE_CONFIG_GATE - Android public config is injected"));
        assertTrue(workflow.contains("${!name:-}"));
        assertFalse(workflow.contains("echo \"$SELFRUN_FIREBASE_API_KEY\""));
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

    private static Path resolve(String repositoryPath, String appPath) {
        Path path = Path.of(repositoryPath);
        return Files.exists(path) ? path : Path.of(appPath);
    }

    private static String text(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
