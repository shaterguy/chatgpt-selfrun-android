package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class DriveV1Dev8NonRegressionPolicyTest {
    @Test public void driveAuthorityAndLegacyAssistantIsolationRemain() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String gradle = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/build.gradle")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(service.contains("DriveSignalParser.scan"));
        assertTrue(service.contains("CONTINUATION_GUARD_MS = 45_000L"));
        assertFalse(service.contains("observeAssistant"));
        assertFalse(service.contains("WAIT_ASSISTANT"));
        assertTrue(gradle.contains("SelfRunDom.observeAssistant"));
    }
}
