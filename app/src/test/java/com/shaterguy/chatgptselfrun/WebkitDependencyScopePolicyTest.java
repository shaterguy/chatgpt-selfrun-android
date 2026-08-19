package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class WebkitDependencyScopePolicyTest {
    @Test public void webkitDependencyIsStableAndNoNewRepositoryIsAdded() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        String settings = read("settings.gradle", "../settings.gradle");
        assertTrue(gradle.contains("androidx.webkit:webkit:1.16.0"));
        assertTrue(settings.contains("google()"));
        assertTrue(settings.contains("mavenCentral()"));
        assertFalse(settings.contains("jitpack"));
    }

    private static String read(String first, String second) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
