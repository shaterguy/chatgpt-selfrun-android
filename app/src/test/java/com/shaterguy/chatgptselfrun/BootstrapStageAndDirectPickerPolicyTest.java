package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BootstrapStageAndDirectPickerPolicyTest {
    @Test public void bootstrapStagesAbsoluteProfileWithoutModeOrReasoningMenuClicks() throws Exception {
        String dom = src("SelfRunDom.java");
        String mode = src("BootstrapModeDom.java");
        String options = src("ChatReasoningOptionDom.java");
        String interceptor = src("RequestProfileScript.java");

        int modeAdapter = dom.indexOf("BootstrapModeDom.inline(requested, runId)");
        int optionAdapter = dom.indexOf("ChatReasoningOptionDom.inline(chatReasoning, runId)");
        int sliderAdapter = dom.indexOf("ChatReasoningDom.inline(chatReasoning, runId)");
        assertTrue(modeAdapter >= 0 && optionAdapter > modeAdapter);
        assertTrue(sliderAdapter < 0);
        assertTrue(mode.contains("__selfRunRequestProfileEngine"));
        assertTrue(mode.contains("begin(requestedMode,modeRunId)"));
        assertTrue(mode.contains("uiClicks=0"));
        assertFalse(mode.contains("data-tpp-toggle-value"));
        assertFalse(mode.contains("dispatchModeMouse"));
        assertTrue(options.contains("__selfRunRequestProfileEngine"));
        assertTrue(options.contains("setChatReasoning"));
        assertTrue(options.contains("uiClicks:0"));
        assertFalse(options.contains("open-reasoning-sheet"));
        assertFalse(options.contains("nested-option-click"));
        assertFalse(options.contains("[role=\"slider\"]"));
        assertTrue(interceptor.contains("installDocumentStart"));
        assertTrue(interceptor.contains("window.fetch"));
        assertTrue(interceptor.contains("XMLHttpRequest"));
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

    @Test public void currentDevIdentityKeepsApprovedDependenciesPinned() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        assertTrue(gradle.contains("selfRunDriveVersionCode = 2000006"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '2.0.0-dev6'"));
        assertTrue(gradle.contains("implementation 'com.google.android.gms:play-services-auth:21.6.0'"));
        assertTrue(gradle.contains("implementation 'com.google.android.material:material:1.14.0'"));
        assertTrue(gradle.contains("implementation 'androidx.webkit:webkit:1.17.0'"));
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
