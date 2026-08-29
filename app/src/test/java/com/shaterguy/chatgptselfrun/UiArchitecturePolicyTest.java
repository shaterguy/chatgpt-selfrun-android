package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class UiArchitecturePolicyTest {
    @Test public void mainIsRunConsoleNotLegacyQuickActionCardStack() throws Exception {
        String main = src("MainActivity.java");
        assertTrue(main.contains("Run Console"));
        assertTrue(main.contains("Ui.setPrimaryContent(this, console, Ui.DEST_RUN)"));
        assertTrue(main.contains("차기턴 저장"));
        assertTrue(main.contains("즉시 강제입력"));
        assertTrue(main.contains("pauseButton.setVisibility(running ? View.VISIBLE : View.GONE)"));
        assertTrue(main.contains("resumeButton.setVisibility(paused ? View.VISIBLE : View.GONE)"));
        assertTrue(main.contains("if (Ui.isExpanded(this))"));
        assertFalse(main.contains("빠른 실행"));
    }

    @Test public void primaryNavigationChangesByWindowWidth() throws Exception {
        String ui = src("Ui.java");
        assertTrue(ui.contains("WIDTH_MEDIUM_DP = 600"));
        assertTrue(ui.contains("WIDTH_EXPANDED_DP = 840"));
        assertTrue(ui.contains("navigationRail(activity, destination)"));
        assertTrue(ui.contains("bottomNavigation(activity, destination)"));
        assertTrue(ui.contains("DEST_RUN"));
        assertTrue(ui.contains("DEST_HISTORY"));
        assertTrue(ui.contains("DEST_TOOLS"));
    }

    @Test public void historyAndDetailUseBrowserAndInspectorPatterns() throws Exception {
        String history = src("SelfRunHistoryActivity.java"), detail = src("SelfRunDetailActivity.java");
        assertTrue(history.contains("Run Browser"));
        assertTrue(history.contains("Ui.isExpanded(this)"));
        assertTrue(history.contains("renderDetailPane"));
        assertTrue(detail.contains("Run Inspector"));
        assertTrue(detail.contains("EXECUTION SNAPSHOT"));
        assertTrue(detail.contains("ORIGINAL MISSION"));
    }

    @Test public void launchWorkspaceUsesDynamicRegistriesAndAllowsWorkBootstrapSelection() throws Exception {
        String activity = src("SelfRunNewActivity.java");
        assertTrue(activity.contains("Launch Workspace"));
        assertTrue(activity.contains("DESTINATION"));
        assertTrue(activity.contains("RUNTIME"));
        assertTrue(activity.contains("MISSION"));
        assertTrue(activity.contains("REFERENCES"));
        assertTrue(activity.contains("ProfileRegistry.listChat()"));
        assertTrue(activity.contains("ProfileRegistry.listWork()"));
        assertTrue(activity.contains("첫 부트스트랩 Work 모델 · 추론 정도"));
        assertTrue(activity.contains("WorkBootstrapPreferenceStore.load(this)"));
        assertTrue(activity.contains("TURN_COMPLETED MODEL/REASONING"));
        assertFalse(activity.contains("CHAT_REASONING_LABELS"));
        assertFalse(activity.contains("Work 모드는 새 작업에서 수동 선택하지 않고"));
    }

    @Test public void toolsExposeProfileRegistryInsteadOfLegacyCalibrationEntry() throws Exception {
        String tools = src("SelfRunLogMenuActivity.java");
        assertTrue(tools.contains("CONNECTIONS"));
        assertTrue(tools.contains("RUNTIME"));
        assertTrue(tools.contains("PROFILES"));
        assertTrue(tools.contains("RUN LOGS"));
        assertTrue(tools.contains("모델 및 추론수준 관리"));
        assertTrue(tools.contains("ProfileRegistryActivity.class"));
        assertFalse(tools.contains("WebUiCalibrationActivity.class"));
        assertFalse(tools.contains("웹 UI 보정 로그"));
    }

    @Test public void profileManagementUsesPurposeSpecificCaptureLayout() throws Exception {
        String profiles = src("ProfileRegistryActivity.java");
        String drive = src("DriveSetupActivity.java");
        assertTrue(profiles.contains("모델 및 추론수준 관리"));
        assertTrue(profiles.contains("일반 Chat"));
        assertTrue(profiles.contains("Work"));
        assertTrue(profiles.contains("새로운 조합 등록"));
        assertTrue(profiles.contains("등록 조합 내보내기"));
        assertTrue(profiles.contains("webView.setVisibility(View.VISIBLE)"));
        assertTrue(profiles.contains("registryScroll.setVisibility(View.GONE)"));
        assertTrue(profiles.contains("메뉴 클릭만으로는 캡처되지 않습니다"));
        assertTrue(drive.contains("CONNECTED"));
    }

    @Test public void restartUsesRecoveryConsoleAndKeepsRecoverySemantics() throws Exception {
        String restart = src("SelfRunRestartActivity.java");
        assertTrue(restart.contains("Recovery Console"));
        assertTrue(restart.contains("RECOVERY TARGET"));
        assertTrue(restart.contains("RECOVERY PATH"));
        assertTrue(restart.contains("DriveAuthorization.requestSilently"));
        assertTrue(restart.contains("requireClaimOwnership();"));
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
