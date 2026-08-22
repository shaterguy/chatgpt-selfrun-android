package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BootstrapStageAndDirectPickerPolicyTest {
    @Test public void monotonicModeStageAndHierarchicalPickerAreWiredBeforePositiveSliderFallback() throws Exception {
        String dom = src("SelfRunDom.java");
        String mode = src("BootstrapModeDom.java");
        String options = src("ChatReasoningOptionDom.java");
        String runner = androidTest("SelfRunAndroidTestRunner.java");
        String flatInstrumentation = androidTest("BootstrapStageAndDirectPickerAndroidTest.java");
        String hierarchicalInstrumentation = androidTest("ChatReasoningHierarchicalMenuAndroidTest.java");

        int modeAdapter = dom.indexOf("BootstrapModeDom.inline(requested, runId)");
        int optionAdapter = dom.indexOf("ChatReasoningOptionDom.inline(chatReasoning, runId)");
        int sliderAdapter = dom.indexOf("ChatReasoningDom.inline(chatReasoning, runId)");
        assertTrue(modeAdapter >= 0 && optionAdapter > modeAdapter && sliderAdapter > optionAdapter);
        assertTrue(mode.contains("chatgpt-selfrun:bootstrap-stage:"));
        assertTrue(mode.contains("MODE_CONFIRMED"));
        assertTrue(mode.contains("stageRegressionBlocked"));
        assertTrue(mode.contains("explicitContradiction"));
        assertTrue(mode.contains("querySelector?.(selector)"));
        assertTrue(options.contains("[role=\"menuitemradio\"]"));
        assertTrue(options.contains("open-advanced-menu"));
        assertTrue(options.contains("open-reasoning-level"));
        assertTrue(options.contains("wait-reasoning-options"));
        assertTrue(options.contains("nested-option-click"));
        assertTrue(options.contains("positive-slider-fallback"));
        assertTrue(options.contains("__sroSliderFound"));
        assertFalse(options.contains("legacy-slider-fallback"));
        assertTrue(runner.contains("BootstrapStageAndDirectPickerAndroidTest"));
        assertTrue(runner.contains("ChatReasoningHierarchicalMenuAndroidTest"));
        assertTrue(flatInstrumentation.contains("confirmedChatStageSurvivesPickerRenderAndAppliesInstantDirectly"));
        assertTrue(flatInstrumentation.contains("workToChatTransitionClicksExactlyOnceBeforeDirectPicker"));
        assertTrue(hierarchicalInstrumentation.contains("koreanSideSubmenuAppliesInstantWithoutSliderFallback"));
        assertTrue(hierarchicalInstrumentation.contains("englishReplacementMenuAppliesProWithOneClickPerStage"));
    }

    @Test public void dev10IdentityAdvancesOneStepWithoutDependencyChange() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        assertTrue(gradle.contains("selfRunDriveVersionCode = 1000074"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '1.4.2-dev10'"));
        assertTrue(gradle.contains("implementation 'com.google.android.gms:play-services-auth:21.6.0'"));
    }

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String androidTest(String file) throws Exception {
        return read("app/src/androidTest/java/com/shaterguy/chatgptselfrun/" + file,
                "src/androidTest/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
