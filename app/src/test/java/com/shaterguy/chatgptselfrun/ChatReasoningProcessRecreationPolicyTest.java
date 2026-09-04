package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Static contract for process initialization; runtime persistence is covered by Android instrumentation. */
public final class ChatReasoningProcessRecreationPolicyTest {
    @Test public void processInitializerAndContextExplicitLookupAreWired() throws Exception {
        String manifest = read("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml");
        String gradle = read("app/build.gradle", "build.gradle");
        String application = src("SelfRunApplication.java");
        String preferences = src("ChatReasoningPreferenceStore.java");
        String history = src("SelfRunHistoryActivity.java");
        String instrumentation = read(
                "app/src/androidTest/java/com/shaterguy/chatgptselfrun/ChatReasoningProcessRecreationAndroidTest.java",
                "src/androidTest/java/com/shaterguy/chatgptselfrun/ChatReasoningProcessRecreationAndroidTest.java");
        String runner = read(
                "app/src/androidTest/java/com/shaterguy/chatgptselfrun/SelfRunAndroidTestRunner.java",
                "src/androidTest/java/com/shaterguy/chatgptselfrun/SelfRunAndroidTestRunner.java");

        assertTrue(manifest.contains("android:name=\".SelfRunApplication\""));
        assertTrue(application.contains("initializeProcess(this)"));
        assertTrue(application.contains("ChatReasoningPreferenceStore.initialize(context)"));
        assertTrue(preferences.contains("selectionForRun(Context context, String runId)"));
        assertTrue(preferences.contains("BootstrapRunStateStore.requested(application, runId)"));
        assertTrue(instrumentation.contains("processRecreationReloadsDurableRunSelection"));
        assertTrue(instrumentation.contains("resetProcessCache()"));
        assertTrue(gradle.contains("testInstrumentationRunner 'com.shaterguy.chatgptselfrun.SelfRunAndroidTestRunner'"));
        assertTrue(runner.contains("private static final String[] REQUIRED"));
        assertTrue(runner.contains("\"com.shaterguy.chatgptselfrun.ChatReasoningProcessRecreationAndroidTest\""));
        assertTrue(runner.contains("for(String required:REQUIRED)appendRequiredClass(effective,required)"));
        assertTrue(runner.contains("private static void appendRequiredClass"));
        assertTrue(runner.contains("if(!containsClass(selected,required))"));
        assertTrue(history.contains("BootstrapRunStateStore.summary(item)"));
        assertFalse(history.contains("모델 변경 없음"));
    }

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
