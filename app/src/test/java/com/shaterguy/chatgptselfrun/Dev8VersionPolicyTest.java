package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class Dev8VersionPolicyTest {
    @Test public void dev8IdentityAndStableWebkitArePinned() throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get("app/build.gradle");
        if (!java.nio.file.Files.exists(p)) p = java.nio.file.Paths.get("build.gradle");
        String gradle = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(gradle.contains("selfRunDriveVersionCode = 1000048"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '1.3.0-dev8'"));
        assertTrue(gradle.contains("androidx.webkit:webkit:1.16.0"));
    }
}
