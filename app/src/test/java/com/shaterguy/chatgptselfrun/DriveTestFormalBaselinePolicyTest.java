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
    @Test public void dev232UsesCurrentStable231FormalFixtureAndRc3TestPredecessor() throws Exception {
        Path path = Paths.get(".github/workflows/build-drive-test.yml");
        if (!Files.exists(path)) path = Paths.get("../.github/workflows/build-drive-test.yml");
        String workflow = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertTrue(workflow.contains("gh release download drive-v2.3.1"));
        assertTrue(workflow.contains("chatgpt-selfrun-drive-v2.3.1.apk"));
        assertTrue(workflow.contains("versionCode='2020033'"));
        assertTrue(workflow.contains("versionName='2.3.1'"));
        Path runtimePath = Paths.get("tools/verify_drive_ui_runtime.sh");
        if (!Files.exists(runtimePath)) runtimePath = Paths.get("../tools/verify_drive_ui_runtime.sh");
        String runtime = new String(Files.readAllBytes(runtimePath), StandardCharsets.UTF_8);
        assertTrue(runtime.contains("FORMAL_EXPECTED_VERSION=\"2.3.1\""));
        assertTrue(workflow.contains("if [[ \"$GITHUB_REF_NAME\" == 'selfrun-drive/v2.3.2-dev1' ]]"));
        assertTrue(workflow.contains("PREV_BRANCH='selfrun-drive/v2.3.1-rc3'"));
        assertTrue(workflow.contains("immutable 2.3.1-rc3 TEST artifact unavailable"));
        assertFalse(workflow.contains("gh release download drive-v2.3.0"));
        assertFalse(workflow.contains("versionCode='2020030'"));
        Path script = Paths.get("tools/verify_drive_test_coinstall_emulator.sh");
        if (!Files.exists(script)) script = Paths.get("../tools/verify_drive_test_coinstall_emulator.sh");
        String tool = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);
        assertTrue(tool.contains("FORMAL_EXPECTED_VERSION=\"${FORMAL_EXPECTED_VERSION:-2.3.1}\""));
        assertFalse(tool.contains("FORMAL_EXPECTED_VERSION=\"${FORMAL_EXPECTED_VERSION:-2.3.0}\""));
    }
}
