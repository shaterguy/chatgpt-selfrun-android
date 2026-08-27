package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BootstrapStageAndDirectPickerPolicyTest {
    @Test public void monotonicModeStageAndAdvancedPickerAreWiredWithoutProductionSlider() throws Exception {
        String dom = src("SelfRunDom.java");
        String mode = src("BootstrapModeDom.java");
        String options = src("ChatReasoningOptionDom.java");
        String runner = androidTest("SelfRunAndroidTestRunner.java");
        String flatInstrumentation = androidTest("BootstrapStageAndDirectPickerAndroidTest.java");
        String hierarchicalInstrumentation = androidTest("ChatReasoningHierarchicalMenuAndroidTest.java");

        int modeAdapter = dom.indexOf("BootstrapModeDom.inline(requested, runId)");
        int optionAdapter = dom.indexOf("ChatReasoningOptionDom.inline(chatReasoning, runId)");
        int sliderAdapter = dom.indexOf("ChatReasoningDom.inline(chatReasoning, runId)");
        assertTrue(modeAdapter >= 0 && optionAdapter > modeAdapter);
        assertTrue(sliderAdapter < 0);
        assertTrue(mode.contains("chatgpt-selfrun:bootstrap-stage:"));
        assertTrue(mode.contains("MODE_CONFIRMED"));
        assertTrue(mode.contains("stageRegressionBlocked"));
        assertTrue(mode.contains("data-tpp-toggle-value"));
        assertTrue(mode.contains("dispatchModeMouse(element,'pointerdown'"));
        assertTrue(options.contains("[role=\"slider\"]"));
        assertTrue(options.contains("open-reasoning-sheet"));
        assertTrue(options.contains("open-advanced-control"));
        assertTrue(options.contains("open-reasoning-menu"));
        assertTrue(options.contains("wait-reasoning-options"));
        assertTrue(options.contains("nested-option-click"));
        assertTrue(options.contains("sliderObserved"));
        assertFalse(options.contains("positive-slider-fallback"));
        assertFalse(options.contains("set-slider"));
        assertTrue(runner.contains("BootstrapStageAndDirectPickerAndroidTest"));
        assertTrue(runner.contains("ChatReasoningHierarchicalMenuAndroidTest"));
        assertTrue(flatInstrumentation.contains("confirmedChatStageSurvivesPickerRenderAndAppliesInstantDirectly"));
        assertTrue(flatInstrumentation.contains("workToChatTransitionClicksExactlyOnceBeforeDirectPicker"));
        assertTrue(hierarchicalInstrumentation.contains("koreanAdvancedButtonPathAppliesInstantWithoutSliderMutation"));
        assertTrue(hierarchicalInstrumentation.contains("englishAdvancedButtonReplacementMenuAppliesProExtendedWithoutSliderMutation"));
        assertTrue(hierarchicalInstrumentation.contains("englishAdvancedButtonReplacementMenuAppliesProStandardWithoutSliderMutation"));
    }

    @Test public void newChatRunDefaultsToExtraHighWithoutOverridingRestoredDraft() throws Exception {
        String activity = src("SelfRunNewActivity.java");
        int restoreMethod = activity.indexOf("private void restoreDraftState(Bundle state)");
        int freshDefault = activity.indexOf(
                "chatReasoning.setSelection(chatReasoningPosition(ChatReasoningPreferenceStore.EXTRA_HIGH));",
                restoreMethod);
        int restoredSelection = activity.indexOf("state.getInt(STATE_CHAT_REASONING, 0)", restoreMethod);
        assertTrue(restoreMethod >= 0 && freshDefault > restoreMethod);
        assertTrue(restoredSelection > freshDefault);
        assertTrue(activity.contains("private static int chatReasoningPosition(String value)"));
        assertTrue(activity.contains("CHAT_REASONING_VALUES[i].equals(value)"));
        assertTrue(activity.contains("chatReasoning.setVisibility(chat ? View.VISIBLE : View.GONE)"));
    }

    @Test public void currentDevIdentityKeepsApprovedUiDependenciesPinned() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        assertTrue(gradle.contains("selfRunDriveVersionCode = 1000102"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '1.7.2'"));
        assertTrue(gradle.contains("implementation 'com.google.android.gms:play-services-auth:21.6.0'"));
        assertTrue(gradle.contains("implementation 'com.google.android.material:material:1.14.0'"));
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
