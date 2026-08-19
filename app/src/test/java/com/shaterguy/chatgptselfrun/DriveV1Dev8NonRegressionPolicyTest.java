package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class DriveV1Dev8NonRegressionPolicyTest {
    @Test public void driveAuthorityAndLegacyAssistantIsolationRemain() throws Exception {
        String service = RateLimitAndStopPolicyTest.Source.read("SelfRunService.java");
        String gradle = root("app/build.gradle", "build.gradle");
        assertTrue(service.contains("DriveSignalParser.scan"));
        assertTrue(service.contains("CONTINUATION_GUARD_MS = 45_000L"));
        assertFalse(service.contains("observeAssistant"));
        assertFalse(service.contains("WAIT_ASSISTANT"));
        assertTrue(gradle.contains("SelfRunDom.observeAssistant"));
    }

    private static String root(String first, String second) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
