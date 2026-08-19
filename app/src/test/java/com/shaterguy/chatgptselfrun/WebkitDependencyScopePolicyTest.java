package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class WebkitDependencyScopePolicyTest {
    @Test public void webkitDependencyIsStableAndNoNewRepositoryIsAdded() throws Exception {
        String gradle = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/build.gradle")), java.nio.charset.StandardCharsets.UTF_8);
        String settings = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("settings.gradle")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(gradle.contains("androidx.webkit:webkit:1.16.0"));
        assertTrue(settings.contains("google()"));
        assertTrue(settings.contains("mavenCentral()"));
        assertFalse(settings.contains("jitpack"));
    }
}
