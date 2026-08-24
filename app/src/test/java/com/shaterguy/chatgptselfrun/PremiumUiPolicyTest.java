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
        assertTrue(ui.contains("materialButtonTonalStyle"));
        assertTrue(ui.contains("materialButtonOutlinedStyle"));
        assertTrue(ui.contains("setMinimumHeight(dp(context, 48))"));
        assertTrue(ui.contains("WindowInsets.Type.systemBars()"));
        assertTrue(ui.contains("WindowInsets.Type.displayCutout()"));
        assertTrue(ui.contains("WindowInsets.Type.ime()"));
    }

    @Test public void mainDashboardKeepsExistingActionsWhileUsingCards() throws Exception {
        String main = src("MainActivity.java");
        assertTrue(main.contains("Ui.card(this"));
        assertTrue(main.contains("Ui.tonalButton(this"));
        assertTrue(main.contains("Ui.outlinedButton(this"));
        assertTrue(main.contains("Ui.dangerButton(this"));
        assertTrue(main.contains("pauseSelfRun()"));
        assertTrue(main.contains("resumeSelfRun()"));
        assertTrue(main.contains("stopSelfRun()"));
        assertTrue(main.contains("saveNextInput()"));
        assertTrue(main.contains("deleteNextInput()"));
        assertTrue(main.contains("requestNotificationPermission()"));
        assertTrue(main.contains("requestBatteryExemption()"));
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
