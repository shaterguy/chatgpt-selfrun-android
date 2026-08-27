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
        assertTrue(main.contains("pane.addView(composerPanel"));
        assertTrue(main.contains("supportingPane = pane"));
        assertTrue(main.contains("workspace.setOrientation(LinearLayout.HORIZONTAL)"));
        assertFalse(main.contains("빠른 실행"));
        assertFalse(main.contains("백그라운드 실행 준비"));
        assertFalse(main.contains("Ui.row(this, pauseButton, resumeButton, stopButton)"));
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
        String history = src("SelfRunHistoryActivity.java");
        String detail = src("SelfRunDetailActivity.java");
        assertTrue(history.contains("Run Browser"));
        assertTrue(history.contains("Ui.isExpanded(this)"));
        assertTrue(history.contains("renderDetailPane"));
        assertTrue(history.contains("Ui.setPrimaryContent(this, screen, Ui.DEST_HISTORY)"));
        assertTrue(detail.contains("Run Inspector"));
        assertTrue(detail.contains("EXECUTION SNAPSHOT"));
        assertTrue(detail.contains("SOURCE & DIAGNOSTIC"));
        assertTrue(detail.contains("ORIGINAL MISSION"));
    }

    @Test public void launchWorkspaceSeparatesSetupMissionAndReferences() throws Exception {
        String activity = src("SelfRunNewActivity.java");
        assertTrue(activity.contains("Launch Workspace"));
        assertTrue(activity.contains("DESTINATION"));
        assertTrue(activity.contains("RUNTIME"));
        assertTrue(activity.contains("MISSION"));
        assertTrue(activity.contains("REFERENCES"));
        assertTrue(activity.contains("workspace.setOrientation(LinearLayout.HORIZONTAL)"));
        assertTrue(activity.contains("chatReasoning.setVisibility(chat ? View.VISIBLE : View.GONE)"));
    }

    @Test public void toolsOwnRuntimeSetupAndDiagnostics() throws Exception {
        String tools = src("SelfRunLogMenuActivity.java");
        String main = src("MainActivity.java");
        assertTrue(tools.contains("CONNECTIONS"));
        assertTrue(tools.contains("RUNTIME"));
        assertTrue(tools.contains("DIAGNOSTICS"));
        assertTrue(tools.contains("requestNotificationPermission"));
        assertTrue(tools.contains("requestBatteryExemption"));
        assertFalse(main.contains("requestNotificationPermission"));
        assertFalse(main.contains("requestBatteryExemption"));
    }

    @Test public void logsLoginCalibrationAndDriveUsePurposeSpecificLayouts() throws Exception {
        String logs = src("SelfRunLogsActivity.java");
        String login = src("LoginActivity.java");
        String calibration = src("WebUiCalibrationActivity.java");
        String drive = src("DriveSetupActivity.java");
        assertTrue(logs.contains("root.addView(viewer, new LinearLayout.LayoutParams("));
        assertTrue(logs.contains("ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f"));
        assertTrue(login.contains("ChatGPT 세션 · 프로젝트"));
        assertTrue(login.contains("root.addView(webView, new LinearLayout.LayoutParams("));
        assertTrue(login.contains("ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f"));
        assertFalse(login.contains("Ui.row(this,"));
        assertTrue(calibration.contains("Ui.topBar(this, \"웹 UI 보정\""));
        assertTrue(calibration.contains("Ui.actionStrip(this, selectButton, cancelButton, confirmButton)"));
        assertTrue(calibration.contains("ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f"));
        assertFalse(calibration.contains("controls.addView(status"));
        assertTrue(drive.contains("Ui.statusPill(this, store.driveRunsBaseFolderId().isEmpty() ? \"NOT CONNECTED\" : \"CONNECTED\")"));
        assertFalse(drive.contains("Ui.row(this, Ui.button(this, \"Drive 실행문서 저장 위치 연결\""));
    }

    @Test public void restartUsesRecoveryConsoleAndKeepsRecoverySemantics() throws Exception {
        String restart = src("SelfRunRestartActivity.java");
        assertTrue(restart.contains("Recovery Console"));
        assertTrue(restart.contains("RECOVERY TARGET"));
        assertTrue(restart.contains("RECOVERY PATH"));
        assertTrue(restart.contains("progressText("));
        assertTrue(restart.contains("showRecoveryStage(\"AUTH\""));
        assertTrue(restart.contains("showRecoveryStage(\"RECOVERING\""));
        assertTrue(restart.contains("showRecoveryStage(\"READY\""));
        assertTrue(restart.contains("closeButton.setEnabled(!recoveryStarted)"));
        assertTrue(restart.contains("DriveAuthorization.requestSilently"));
        assertTrue(restart.contains("requireClaimOwnership();"));
        assertTrue(restart.contains("restoreRun(baseFolderId, actualAccount, jobFolder, document, baseline, restartCompletion, prompt)"));
        assertFalse(restart.contains("Ui.title(this, \"중지 작업 재시작\")"));
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
