package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class SelfRun3InstrumentationRunnerPolicyTest {
    @Test public void centralRunnerOwnsOnlyCurrent31CriticalClasses() throws Exception {
        String runner = source("app/src/androidTest/java/com/shaterguy/chatgptselfrun/SelfRunAndroidTestRunner.java",
                "src/androidTest/java/com/shaterguy/chatgptselfrun/SelfRunAndroidTestRunner.java");
        assertTrue(runner.contains("V3_REQUIRED"));
        for (String required : new String[]{
                "SelfRun31FirstConversationAndroidTest",
                "SelfRun3RuntimeAndroidTest",
                "SelfRun3DispatchAndroidTest",
                "SelfRun3ParallelLedgerAndroidTest",
                "SelfRun3InputCommitAndroidTest",
                "RequestProfileRecreationAndroidTest",
                "ChatReasoningProcessRecreationAndroidTest"}) {
            assertTrue("missing V3.1 runner class: " + required, runner.contains(required));
            Path source = Paths.get("app/src/androidTest/java/com/shaterguy/chatgptselfrun/" + required + ".java");
            if (!Files.exists(source)) source = Paths.get("src/androidTest/java/com/shaterguy/chatgptselfrun/" + required + ".java");
            assertTrue("runner class source must exist: " + required, Files.exists(source));
        }
        for (String stale : new String[]{
                "SelfRun3PinnedComposerAndroidTest",
                "SelfRun3ObservationWebViewTest",
                "SelfRun3ComposerTransportWebViewTest",
                "TurnProtocolStateWebViewTest",
                "WorkTurnProtocolIngressWebViewTest",
                "RichComposerBootstrapWebViewTest",
                "ProtocolDetachedSurfaceWebViewTest"}) {
            assertFalse("stale 3.0 continuation/completion runner entry remains: " + stale, runner.contains(stale));
        }
        assertFalse(runner.contains("BuildConfig.VERSION_NAME"));
    }

    @Test public void upgradeSeedAndVerifyRemainIsolatedAcrossTargetReplacement() throws Exception {
        String runner = source("app/src/androidTest/java/com/shaterguy/chatgptselfrun/SelfRunAndroidTestRunner.java",
                "src/androidTest/java/com/shaterguy/chatgptselfrun/SelfRunAndroidTestRunner.java");
        assertTrue(runner.contains("SelfRun3UpgradePersistenceAndroidTest#"));
        assertTrue(runner.contains("seedUpgradeState"));
        assertTrue(runner.contains("verifyUpgradeState"));
        int gate = runner.indexOf("if (selected.equals(upgrade + \"seedUpgradeState\")");
        int merge = runner.indexOf("merged.addAll(V3_REQUIRED)");
        assertTrue(gate >= 0 && merge > gate);
        String emulator = source("tools/verify_selfrun3_candidate_emulator.sh",
                "../tools/verify_selfrun3_candidate_emulator.sh");
        assertTrue(emulator.contains("SelfRun3UpgradePersistenceAndroidTest#seedUpgradeState"));
        assertTrue(emulator.contains("SelfRun3UpgradePersistenceAndroidTest#verifyUpgradeState"));
        assertTrue(emulator.indexOf("#seedUpgradeState") < emulator.indexOf("adb install -r current/candidate.apk"));
        assertTrue(emulator.indexOf("adb install -r current/candidate.apk") < emulator.indexOf("#verifyUpgradeState"));
    }

    private static String source(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
