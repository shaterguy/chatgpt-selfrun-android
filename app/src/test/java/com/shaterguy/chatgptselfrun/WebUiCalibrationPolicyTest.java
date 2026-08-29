package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Keeps legacy selector calibration isolated while the active profile path uses request capture. */
public final class WebUiCalibrationPolicyTest {
    @Test public void runtimeWorkProfilesDoNotDependOnLegacyCalibrationSelectors() {
        String model = WorkPreferenceDom.modelForConversation("https://chatgpt.com/c/conversation123", "sol");
        String reasoning = WorkPreferenceDom.reasoningForConversation("https://chatgpt.com/c/conversation123", "xhigh");
        for (String script : new String[]{model, reasoning}) {
            assertTrue(script.contains("__selfRunRequestProfileEngine"));
            assertTrue(script.contains("installRegistry"));
            assertTrue(script.contains("strategy:'request-profile'"));
            assertTrue(script.contains("uiClicks:0"));
            assertFalse(script.contains("__wpCalibratedOptionValid"));
            assertFalse(script.contains("open-work-mode-fallback"));
            assertFalse(script.contains("querySelectorAll"));
        }
    }

    @Test public void legacyCalibrationRemainsPrivateButHasNoToolsEntrypoint() throws Exception {
        String manifest = read("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml");
        String tools = src("SelfRunLogMenuActivity.java");
        assertTrue(manifest.contains(".WebUiCalibrationActivity"));
        assertTrue(manifest.contains(".ProfileRegistryActivity"));
        assertTrue(manifest.contains("android:exported=\"false\""));
        assertFalse(tools.contains("WebUiCalibrationActivity.class"));
        assertTrue(tools.contains("ProfileRegistryActivity.class"));
        assertTrue(tools.contains("모델 및 추론수준 관리"));
    }

    @Test public void activeCaptureAddsNoJavascriptBridgeOrPermissionSurface() throws Exception {
        String activity = src("ProfileRegistryActivity.java");
        String config = src("WebViewConfig.java");
        String manifest = read("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml");
        assertTrue(activity.contains("evaluateJavascript(RequestProfileScript.consumeCapture()"));
        assertTrue(activity.contains("trustedUrl(url)"));
        assertFalse(activity.contains("addJavascriptInterface"));
        assertFalse(activity.contains("setAllowUniversalAccessFromFileURLs(true)"));
        assertTrue(config.contains("RequestProfileScript.installDocumentStart(webView)"));
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"));
    }

    @Test public void legacyRecorderStillRedactsComposerTextIfInternallyInvoked() {
        String script = WebUiCalibrationDom.install(WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT);
        assertTrue(script.contains("text:inputLike(e)?'':norm(e.innerText||e.textContent)"));
        assertFalse(script.contains("target.value"));
        assertFalse(script.contains("composer.value"));
    }

    @Test public void profileManagementDisplaysSignalAndActualCombinationWithoutEditAction() throws Exception {
        String activity = src("ProfileRegistryActivity.java");
        assertTrue(activity.contains("신호 REASONING="));
        assertTrue(activity.contains("MODEL="));
        assertTrue(activity.contains("실제 조합 "));
        assertTrue(activity.contains("Ui.dangerButton(this, \"삭제\""));
        assertFalse(activity.contains("이름 수정"));
        assertFalse(activity.contains("displayName"));
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
