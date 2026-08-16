package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class SelfRunNewRunIsolationTest {
    @Test public void differentRunIdDestroysOldWebViewBeforeRuntimeAdoption() throws Exception {
        String source = src("SelfRunService.java");
        String expected = "if (!currentRunId.equals(runtimeRunId)) {\n"
                + "            stopAutomationCallbacks();\n"
                + "            cleanupWebView();\n"
                + "            runtimeRunId = currentRunId;";
        assertTrue(source.contains(expected));
    }

    @Test public void activityKeepsExistingProjectSelectionContract() throws Exception {
        String source = src("SelfRunNewActivity.java");
        assertTrue(source.contains("store.setDefaultProjectUrl(project);"));
        assertTrue(source.contains("stopService(new Intent(this,SelfRunService.class));"));
        assertFalse(source.contains("getRunningServices"));
        assertFalse(source.contains("ActivityManager"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
