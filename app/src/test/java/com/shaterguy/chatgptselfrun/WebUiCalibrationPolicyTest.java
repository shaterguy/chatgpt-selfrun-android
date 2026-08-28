package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class WebUiCalibrationPolicyTest {
    @Test public void recorderRedactsComposerTextAndFiltersSubmitControl() {
        String script = WebUiCalibrationDom.install(WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT);
        assertTrue(script.contains("addEventListener('input'"));
        assertTrue(script.contains("addEventListener('submit'"));
        assertTrue(script.contains("composer:state.composer"));
        assertTrue(script.contains("text:inputLike(e)?'':norm(e.innerText||e.textContent)"));
        assertTrue(script.contains("const looksSubmit=e=>"));
        assertTrue(script.contains("state.composer&&looksSubmit(e)"));
        assertFalse(script.contains("target.value"));
        assertFalse(script.contains("composer.value"));
    }

    @Test public void matcherPrioritizesStableAttributesKeepsFallbackAndLogsPurpose() {
        String script = WebUiCalibrationDom.runtimePrelude();
        assertTrue(script.contains(WebUiCalibrationStore.STORAGE_KEY));
        assertTrue(script.contains("__srFind"));
        assertTrue(script.contains("s+=14"));
        assertTrue(script.contains("score>=6"));
        assertTrue(script.contains("rawScore>=6?'REJECT':'MISS'"));
        assertTrue(script.contains("60000"));
        assertTrue(WebUiCalibrationDom.readRuntimeLog().contains("ui-runtime-log"));
    }

    @Test public void runtimeSelectsCalibrationByScopeAndTurnStage() {
        String generalBootstrapModel = WorkPreferenceDom.modelForProject(SelfRunScript.GENERAL_CHAT_URL, "sol");
        String generalBootstrapReasoning = WorkPreferenceDom.reasoningForProject(SelfRunScript.GENERAL_CHAT_URL, "xhigh");
        String projectBootstrapModel = WorkPreferenceDom.modelForProject("https://chatgpt.com/g/g-p-test/project", "sol");
        String projectBootstrapReasoning = WorkPreferenceDom.reasoningForProject("https://chatgpt.com/g/g-p-test/project", "xhigh");
        String generalContinuationModel = WorkPreferenceDom.modelForConversation("https://chatgpt.com/c/conversation123", "sol");
        String generalContinuationReasoning = WorkPreferenceDom.reasoningForConversation("https://chatgpt.com/c/conversation123", "xhigh");
        String projectContinuationModel = WorkPreferenceDom.modelForConversation("https://chatgpt.com/g/g-p-test/c/conversation123", "sol");
        String projectContinuationReasoning = WorkPreferenceDom.reasoningForConversation("https://chatgpt.com/g/g-p-test/c/conversation123", "xhigh");

        assertTrue(generalBootstrapModel.contains(WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL));
        assertTrue(generalBootstrapReasoning.contains(WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_REASONING));
        assertTrue(projectBootstrapModel.contains(WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_MODEL));
        assertTrue(projectBootstrapReasoning.contains(WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING));
        assertTrue(generalContinuationModel.contains(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL));
        assertTrue(generalContinuationReasoning.contains(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_REASONING));
        assertTrue(projectContinuationModel.contains(WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_MODEL));
        assertTrue(projectContinuationReasoning.contains(WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_REASONING));

        assertFalse(generalBootstrapModel.contains(WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_MODEL));
        assertFalse(generalContinuationModel.contains(WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_MODEL));
        assertFalse(projectBootstrapModel.contains(WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL));
        assertFalse(projectContinuationModel.contains(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL));
    }

    @Test public void allEightWorkCalibrationTargetsAreDistinct() {
        Set<String> purposes = new HashSet<>();
        purposes.add(WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL);
        purposes.add(WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_REASONING);
        purposes.add(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL);
        purposes.add(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_REASONING);
        purposes.add(WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_MODEL);
        purposes.add(WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING);
        purposes.add(WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_MODEL);
        purposes.add(WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_REASONING);
        assertEquals(8, purposes.size());
        assertEquals(WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL,
                WebUiCalibrationStore.workModelPurpose(true, true));
        assertEquals(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL,
                WebUiCalibrationStore.workModelPurpose(true, false));
        assertEquals(WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING,
                WebUiCalibrationStore.workReasoningPurpose(false, true));
        assertEquals(WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_REASONING,
                WebUiCalibrationStore.workReasoningPurpose(false, false));
    }

    @Test public void workPreferenceValidatesScopedCalibrationAndKeepsCurrentEffortFallback() {
        String model = WorkPreferenceDom.modelForConversation("https://chatgpt.com/c/conversation123", "sol");
        String reasoning = WorkPreferenceDom.reasoningForConversation("https://chatgpt.com/c/conversation123", "xhigh");
        assertTrue(model.contains("__wpCalibratedRaw=__srFind(__wpPurpose)"));
        assertTrue(reasoning.contains("__wpCalibratedRaw=__srFind(__wpPurpose)"));
        assertTrue(model.contains("__wpCalibrated=__wpCalibratedRaw?.closest?"));
        assertTrue(reasoning.contains("__wpCalibrated=__wpCalibratedRaw?.closest?"));
        assertTrue(model.contains("__wpVisible(__wpCalibrated)&&__wpNear(__wpCalibrated)"));
        assertTrue(reasoning.contains("__wpVisible(__wpCalibrated)&&__wpNear(__wpCalibrated)"));
        assertTrue(model.contains("__wpRoot=__wpRoots[0]?.element||"));
        assertTrue(reasoning.contains("__wpRoot=__wpRoots[0]?.element||"));
        assertTrue(model.contains(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL));
        assertTrue(reasoning.contains(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_REASONING));
        assertTrue(model.contains("open-effort-popover"));
        assertTrue(reasoning.contains("open-effort-popover"));
        assertTrue(model.contains("open-model-menu"));
        assertTrue(reasoning.contains("set-slider-detent"));
        assertTrue(reasoning.contains("set-slider-track"));
        assertTrue(model.contains("[aria-haspopup],[aria-expanded]"));
        assertTrue(reasoning.contains("[aria-haspopup],[aria-expanded]"));
        assertTrue(model.contains("[role=\"menu\"],[role=\"listbox\"],[role=\"dialog\"]"));
        assertTrue(reasoning.contains("[role=\"menu\"],[role=\"listbox\"],[role=\"dialog\"]"));
    }

    @Test public void calibrationActivityExposesFourIndependentWorkContexts() throws Exception {
        String activity = src("WebUiCalibrationActivity.java");
        assertTrue(activity.contains(WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL));
        assertTrue(activity.contains(WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_REASONING));
        assertTrue(activity.contains(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL));
        assertTrue(activity.contains(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_REASONING));
        assertTrue(activity.contains(WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_MODEL));
        assertTrue(activity.contains(WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING));
        assertTrue(activity.contains(WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_MODEL));
        assertTrue(activity.contains(WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_REASONING));
    }

    @Test public void calibrationUsesWebViewFirstLayoutAndTransientPurposePicker() throws Exception {
        String activity = src("WebUiCalibrationActivity.java");
        assertTrue(activity.contains("root.addView(webView, new LinearLayout.LayoutParams("));
        assertTrue(activity.contains("ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f"));
        assertTrue(activity.contains("Ui.topBar(this, \"웹 UI 보정\""));
        assertTrue(activity.contains("Ui.actionStrip(this, selectButton, cancelButton, confirmButton)"));
        assertTrue(activity.contains("showPurposePicker()"));
        assertTrue(activity.contains("setTitle(\"보정 항목 선택\")"));
        assertTrue(activity.contains("setItems(items, (dialog, which) -> startPurpose(PURPOSES[which]))"));
        assertFalse(activity.contains("statusText(\"\")"));
        assertFalse(activity.contains("task(\"일반채팅 메뉴\""));
    }

    @Test public void calibrationCaptureModeLeavesOnlyMinimalActionsVisible() throws Exception {
        String activity = src("WebUiCalibrationActivity.java");
        String ui = src("Ui.java");
        assertTrue(activity.contains("selectButton.setVisibility(active ? View.GONE : View.VISIBLE)"));
        assertTrue(activity.contains("manageButton.setVisibility(active ? View.GONE : View.VISIBLE)"));
        assertTrue(activity.contains("cancelButton.setVisibility(active ? View.VISIBLE : View.GONE)"));
        assertTrue(activity.contains("confirmButton.setVisibility(canConfirm ? View.VISIBLE : View.GONE)"));
        assertTrue(activity.contains("CAPTURE_CANCELLED"));
        assertTrue(ui.contains("setMinHeight(dp(context, 48))"));
        assertTrue(ui.contains("setMinimumHeight(dp(context, 48))"));
    }

    @Test public void automationAndCalibrationShareMobileWebViewPolicy() throws Exception {
        String config = src("WebViewConfig.java");
        String host = src("HeadlessWebViewHost.java");
        String activity = src("WebUiCalibrationActivity.java");
        assertTrue(config.contains("setUseWideViewPort(false)"));
        assertTrue(config.contains("setLoadWithOverviewMode(false)"));
        assertFalse(host.contains("1440"));
        assertFalse(host.contains("900"));
        assertTrue(host.contains("new WebUiCalibrationStore(context).viewport()"));
        assertTrue(activity.contains("WebViewConfig.applyAutomation(webView)"));
    }

    @Test public void calibrationAddsNoJavascriptBridgeOrNewPermissionSurface() throws Exception {
        String activity = src("WebUiCalibrationActivity.java");
        String manifest = read("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml");
        assertFalse(activity.contains("addJavascriptInterface"));
        assertFalse(activity.contains("setAllowUniversalAccessFromFileURLs(true)"));
        assertTrue(manifest.contains("WebUiCalibrationActivity"));
        assertTrue(manifest.contains("android:exported=\"false\""));
    }

    @Test public void calibrationAndRuntimeLogsAreVisibleInsideApp() throws Exception {
        String logMenu = src("SelfRunLogMenuActivity.java");
        String activity = src("WebUiCalibrationActivity.java");
        assertTrue(logMenu.contains("웹 UI 보정 로그"));
        assertTrue(logMenu.contains("calibration.logText(120)"));
        assertTrue(activity.contains("[런타임 MATCH/MISS]"));
        assertTrue(activity.contains("WebUiCalibrationDom.readRuntimeLog()"));
    }

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String second) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
