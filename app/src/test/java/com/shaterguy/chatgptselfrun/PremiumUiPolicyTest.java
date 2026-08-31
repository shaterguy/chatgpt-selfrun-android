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
        assertTrue(application.contains("ProfileRegistry.initialize(context)"));
    }

    @Test public void commonUiUsesMaterialComponentsAndAccessibleTargets() throws Exception {
        String ui = src("Ui.java");
        assertTrue(ui.contains("MaterialButton"));
        assertTrue(ui.contains("MaterialCardView"));
        assertTrue(ui.contains("MaterialAutoCompleteTextView"));
        assertTrue(ui.contains("TextInputLayout"));
        assertTrue(ui.contains("setMinimumHeight(dp(context, 48))"));
        assertTrue(ui.contains("fontScale >= 1.6f"));
    }

    @Test public void selectionInputsUseDynamicRegistryAndPreserveTokenState() throws Exception {
        String ui = src("Ui.java"), task = src("SelfRunNewActivity.java");
        assertTrue(task.contains("Ui.SelectionField project"));
        assertTrue(task.contains("Ui.SelectionField mode"));
        assertTrue(task.contains("Ui.SelectionField chatReasoning"));
        assertTrue(task.contains("mode.setOnSelectionChangedListener"));
        assertTrue(task.contains("outState.putInt(STATE_MODE"));
        assertTrue(task.contains("outState.putString(STATE_CHAT_REASONING, selectedChatReasoning())"));
        assertTrue(task.contains("refreshChatReasoningOptions(reasoning)"));
        assertTrue(task.contains("ProfileRegistry.listChat()"));
        assertTrue(task.contains("project.setSelection(selected)"));
        assertTrue(ui.contains("int getSelectedItemPosition()"));
        assertTrue(ui.contains("void setSelection(int position)"));
        assertFalse(task.contains("android.widget.Spinner"));
        assertFalse(task.contains("CHAT_REASONING_LABELS"));
    }

    @Test public void commonContentAdaptsAcrossWindowWidthClasses() throws Exception {
        String ui = src("Ui.java");
        assertTrue(ui.contains("WIDTH_MEDIUM_DP = 600"));
        assertTrue(ui.contains("WIDTH_EXPANDED_DP = 840"));
        assertTrue(ui.contains("navigationRail(activity, destination)"));
        assertTrue(ui.contains("bottomNavigation(activity, destination)"));
        assertTrue(ui.contains("applyAdaptiveContentWidth"));
    }

    @Test public void mainConsoleShowsSemanticStateWithoutTurnOrCursorCounters() throws Exception {
        String main = src("MainActivity.java"), runtime = src("TurnProtocolUiState.java");
        String tools = src("SelfRunLogMenuActivity.java");
        assertTrue(main.contains("Run Console"));
        assertTrue(main.contains("pauseSelfRun()"));
        assertTrue(main.contains("resumeSelfRun()"));
        assertTrue(main.contains("stopSelfRun()"));
        assertTrue(main.contains("TurnProtocolUiState.read(this, runId)"));
        assertTrue(main.contains("displayRuntimeStatus(protocol, paused, terminal)"));
        assertTrue(runtime.contains("추론 중"));
        assertTrue(runtime.contains("답변 생성 중"));
        assertTrue(runtime.contains("답변 완료 · 차기 턴 대기"));
        assertTrue(main.contains("CONTINUE 전송 중"));
        assertTrue(main.contains("마지막 인식 signal document ID"));
        assertFalse(main.contains("SelfRun Turn"));
        assertFalse(main.contains("ChatGPT Turn"));
        assertFalse(main.contains("Drive signal cursor"));
        assertFalse(main.contains("currentStatus.setText(store.status())"));
        assertFalse(main.contains("requestNotificationPermission()"));
        assertTrue(tools.contains("requestNotificationPermission()"));
        assertTrue(tools.contains("requestBatteryExemption()"));
        assertTrue(tools.contains("모델 및 추론수준 관리"));
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
