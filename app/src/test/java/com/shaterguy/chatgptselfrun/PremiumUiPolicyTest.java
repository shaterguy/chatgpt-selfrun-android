package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PremiumUiPolicyTest {
    @Test public void expressiveThemeAndDynamicColorAreAppWide() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        String styles = read("app/src/main/res/values/styles.xml", "src/main/res/values/styles.xml");
        String night = read("app/src/main/res/values-night/styles.xml", "src/main/res/values-night/styles.xml");
        String application = src("SelfRunApplication.java");
        assertTrue(gradle.contains("com.google.android.material:material:1.14.0"));
        assertTrue(styles.contains("Theme.Material3Expressive.DayNight.NoActionBar"));
        assertTrue(night.contains("Theme.Material3Expressive.DayNight.NoActionBar"));
        assertTrue(styles.contains("dynamicColorThemeOverlay"));
        assertTrue(application.contains("DynamicColors.applyToActivitiesIfAvailable(this)"));
    }

    @Test public void commonUiUsesMaterialComponentsAndAccessibleTargets() throws Exception {
        String ui = src("Ui.java");
        assertTrue(ui.contains("MaterialButton"));
        assertTrue(ui.contains("MaterialCardView"));
        assertTrue(ui.contains("MaterialAutoCompleteTextView"));
        assertTrue(ui.contains("TextInputLayout"));
        assertTrue(ui.contains("textInputOutlinedExposedDropdownMenuStyle"));
        assertTrue(ui.contains("materialButtonTonalStyle"));
        assertTrue(ui.contains("materialButtonOutlinedStyle"));
        assertTrue(ui.contains("setMinimumHeight(dp(context, 48))"));
        assertTrue(ui.contains("WindowInsets.Type.systemBars()"));
        assertTrue(ui.contains("WindowInsets.Type.displayCutout()"));
        assertTrue(ui.contains("WindowInsets.Type.ime()"));
    }

    @Test public void selectionInputsUseMaterialDropdownsAndPreserveIndexState() throws Exception {
        String ui = src("Ui.java");
        String task = src("SelfRunNewActivity.java");
        assertTrue(task.contains("Ui.SelectionField project"));
        assertTrue(task.contains("Ui.SelectionField mode"));
        assertTrue(task.contains("Ui.SelectionField chatReasoning"));
        assertTrue(task.contains("mode.setOnSelectionChangedListener"));
        assertTrue(task.contains("outState.putInt(STATE_MODE"));
        assertTrue(task.contains("mode.getSelectedItemPosition()"));
        assertTrue(task.contains("mode.setSelection(modePosition)"));
        assertTrue(task.contains("chatReasoning.setSelection(reasoningPosition)"));
        assertTrue(task.contains("project.setSelection(selected)"));
        assertTrue(ui.contains("int getSelectedItemPosition()"));
        assertTrue(ui.contains("void setSelection(int position)"));
        assertTrue(ui.contains("input.setKeyListener(null)"));
        assertTrue(ui.contains("addView(input, new LinearLayout.LayoutParams("));
        assertFalse(ui.contains("addView(input, new ViewGroup.LayoutParams("));
        assertFalse(task.contains("android.widget.Spinner"));
        assertFalse(task.contains("simple_spinner_dropdown_item"));
    }

    @Test public void commonContentAdaptsAcrossWindowWidthClasses() throws Exception {
        String ui = src("Ui.java");
        assertTrue(ui.contains("WIDTH_MEDIUM_DP = 600"));
        assertTrue(ui.contains("WIDTH_EXPANDED_DP = 840"));
        assertTrue(ui.contains("navigationRail(activity, destination)"));
        assertTrue(ui.contains("bottomNavigation(activity, destination)"));
        assertTrue(ui.contains("applyAdaptiveContentWidth"));
        assertTrue(ui.contains("FrameLayout"));
        assertTrue(ui.contains("Gravity.TOP | Gravity.CENTER_HORIZONTAL"));
    }

    @Test public void mainConsoleKeepsRunActionsWhileToolsOwnRuntimeSetup() throws Exception {
        String main = src("MainActivity.java");
        String tools = src("SelfRunLogMenuActivity.java");
        assertTrue(main.contains("Run Console"));
        assertTrue(main.contains("Ui.heroSurface(this"));
        assertTrue(main.contains("Ui.outlinedButton(this"));
        assertTrue(main.contains("Ui.dangerButton(this"));
        assertTrue(main.contains("pauseSelfRun()"));
        assertTrue(main.contains("resumeSelfRun()"));
        assertTrue(main.contains("stopSelfRun()"));
        assertTrue(main.contains("saveNextInput()"));
        assertTrue(main.contains("deleteNextInput()"));
        assertFalse(main.contains("requestNotificationPermission()"));
        assertFalse(main.contains("requestBatteryExemption()"));
        assertTrue(tools.contains("requestNotificationPermission()"));
        assertTrue(tools.contains("requestBatteryExemption()"));
    }

    @Test public void uiChangeDoesNotExpandAndroidPermissionSurface() throws Exception {
        String manifest = read("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml");
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("android:sharedUserId"));
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
