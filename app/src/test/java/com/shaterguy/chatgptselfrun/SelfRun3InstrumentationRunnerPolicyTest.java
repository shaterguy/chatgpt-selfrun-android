package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Prevents retired V2 instrumentation classes from re-entering the sole V3 runner. */
public final class SelfRun3InstrumentationRunnerPolicyTest {
    @Test public void centralRunnerOwnsOnlyExistingV3CriticalClasses() throws Exception {
        String runner = source("app/src/androidTest/java/com/shaterguy/chatgptselfrun/SelfRunAndroidTestRunner.java",
                "src/androidTest/java/com/shaterguy/chatgptselfrun/SelfRunAndroidTestRunner.java");
        assertTrue(runner.contains("V3_REQUIRED"));
        assertFalse(runner.contains("V2_REQUIRED"));
        assertFalse(runner.contains("ProtocolDetachedSurfaceWebViewTest"));
        assertFalse(runner.contains("RichComposerBootstrapWebViewTest"));
        assertFalse(runner.contains("TurnDocumentRetryAndroidTest"));
        assertFalse(runner.contains("DriveSignalDocumentIdentityAndroidTest"));
        for (String required : new String[]{
                "SelfRun3RuntimeAndroidTest",
                "SelfRun3PinnedComposerAndroidTest",
                "SelfRun3ObservationWebViewTest",
                "SelfRun3ComposerTransportWebViewTest",
                "TurnProtocolStateWebViewTest",
                "RequestProfileRecreationAndroidTest",
                "WorkTurnProtocolIngressWebViewTest"}) {
            assertTrue("missing V3 runner class: " + required, runner.contains(required));
            Path source = Paths.get("app/src/androidTest/java/com/shaterguy/chatgptselfrun/" + required + ".java");
            if (!Files.exists(source)) source = Paths.get("src/androidTest/java/com/shaterguy/chatgptselfrun/" + required + ".java");
            assertTrue("runner class source must exist: " + required, Files.exists(source));
        }
        assertFalse(runner.contains("BuildConfig.VERSION_NAME"));
    }

    private static String source(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
