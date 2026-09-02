package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ensures TEST co-install always uses the current stable SelfRun Drive baseline. */
public final class DriveTestFormalBaselinePolicyTest {
    @Test public void dev4UsesCurrentStable222FormalFixture() throws Exception {
        Path path = Paths.get(".github/workflows/build-drive-test.yml");
        if (!Files.exists(path)) path = Paths.get("../.github/workflows/build-drive-test.yml");
        String workflow = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertTrue(workflow.contains("gh release download drive-v2.2.2"));
        assertTrue(workflow.contains("chatgpt-selfrun-drive-v2.2.2.apk"));
        assertTrue(workflow.contains("versionCode='2020019'"));
        assertTrue(workflow.contains("versionName='2.2.2'"));
        assertTrue(workflow.contains("FORMAL_EXPECTED_VERSION=\"2.2.2\""));
        assertFalse(workflow.contains("drive-v1.6.0"));
        assertFalse(workflow.contains("versionCode='1000090'"));
    }
}
