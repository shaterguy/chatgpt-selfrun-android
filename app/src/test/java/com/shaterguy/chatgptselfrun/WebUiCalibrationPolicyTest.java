package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class WebUiCalibrationPolicyTest {
    @Test public void recorderRedactsComposerTextFromCalibrationPayload() {
        String script = WebUiCalibrationDom.install(WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT);
        assertTrue(script.contains("addEventListener('input'"));
        assertTrue(script.contains("addEventListener('submit'"));
        assertTrue(script.contains("composer:state.composer"));
        assertTrue(script.contains("text:inputLike(e)?'':norm(e.innerText||e.textContent)"));
        assertFalse(script.contains("target.value"));
        assertFalse(script.contains("composer.value"));
    }

    @Test public void matcherPrioritizesStableAttributesAndKeepsFallbackThreshold() {
        String script = WebUiCalibrationDom.runtimePrelude();
        assertTrue(script.contains(WebUiCalibrationStore.STORAGE_KEY));
        assertTrue(script.contains("__srFind"));
        assertTrue(script.contains("s+=14"));
        assertTrue(script.contains("score>=6"));
    }

    @Test public void runtimeUsesPurposeSpecificCalibrationBeforeLegacyHeuristics() {
        String chat = SelfRunDom.prepareInitialContext(SelfRunScript.GENERAL_CHAT_URL,
                SelfRunStore.MODE_CHAT, "SR-20260816-CAL001");
        String project = SelfRunDom.prepareInitialContext(
                "https://chatgpt.com/g/g-p-test/project", SelfRunStore.MODE_WORK, "SR-20260816-CAL002");
        String model = WorkPreferenceDom.modelForProject("https://chatgpt.com/g/g-p-test/project", "sol");
        String reasoning = WorkPreferenceDom.reasoningForProject("https://chatgpt.com/g/g-p-test/project", "xhigh");
        assertTrue(chat.contains(WebUiCalibrationStore.PURPOSE_MODE_CHAT));
        assertTrue(chat.contains(WebUiCalibrationStore.TARGET_GENERAL_COMPOSER));
        assertTrue(project.contains(WebUiCalibrationStore.PURPOSE_MODE_WORK));
        assertTrue(project.contains(WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT));
        assertTrue(project.contains(WebUiCalibrationStore.TARGET_PROJECT_COMPOSER));
        assertTrue(model.contains(WebUiCalibrationStore.PURPOSE_WORK_MODEL));
        assertTrue(reasoning.contains(WebUiCalibrationStore.PURPOSE_WORK_REASONING));
        assertTrue(chat.contains("rawModeControls"));
        assertTrue(model.contains("heuristicTrigger"));
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

    @Test public void purposeScopedCalibrationLogIsVisibleInsideExistingLogMenu() throws Exception {
        String logMenu = src("SelfRunLogMenuActivity.java");
        assertTrue(logMenu.contains("웹 UI 보정 로그"));
        assertTrue(logMenu.contains("calibration.logText(120)"));
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
