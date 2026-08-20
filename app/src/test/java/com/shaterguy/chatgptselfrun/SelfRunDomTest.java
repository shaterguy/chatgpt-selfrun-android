package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class SelfRunDomTest {
    @Test public void runtimeDomContainsNoAssistantResponseObservation() throws Exception {
        String dom = src("SelfRunDom.java");
        assertFalse(dom.contains("readLatestSelfRunControl"));
        assertFalse(dom.contains("observeAssistant"));
        assertFalse(dom.contains("assistantSnapshot"));
        assertFalse(dom.contains("assistantBaselineKey"));
        assertFalse(dom.contains("SELF_RUN_NEXT"));
        assertFalse(dom.contains("data-message-author-role=\\\"assistant\\\""));
        assertFalse(dom.contains("article[data-turn=\\\"assistant\\\"]"));
    }

    @Test public void continuationOnlyStagesComposerAndMarker() {
        String script = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/g/g-p-test/c/conversation123",
                "[2026.08.13 | 22:03:15] [SELF_RUN_CONTINUE SR-20260813-220315-A1B2C3]", "m1");
        assertTrue(script.contains("READY_TO_SUBMIT"));
        assertFalse(script.contains("CONFIRMED"));
        assertFalse(script.contains("assistant"));
    }

    @Test public void generalChatScopeSupportsBootstrapAndWorkPreferences() {
        String initial = SelfRunDom.prepareInitialContext(SelfRunScript.GENERAL_CHAT_URL, SelfRunStore.MODE_CHAT, "SR-20260814-TEST00");
        String model = WorkPreferenceDom.modelForProject(SelfRunScript.GENERAL_CHAT_URL, "sol");
        assertTrue(initial.contains("__GENERAL_CHAT__"));
        assertTrue(initial.contains("일반 Chat 범위 이탈"));
        assertTrue(model.contains("__GENERAL_CHAT__"));
        assertTrue(model.contains("일반 Chat 범위 이탈"));
    }

    @Test public void bootstrapReadsClosedModeTriggerAndOpensMenuBeforeSelectingOption() {
        for (String mode : new String[]{SelfRunStore.MODE_CHAT, SelfRunStore.MODE_WORK}) {
            String script = SelfRunDom.prepareInitialContext(SelfRunScript.GENERAL_CHAT_URL, mode,
                    "SR-20260820-MODE00");
            assertTrue(script.contains("const modeTrigger=rawModeControls.find(menuTrigger)||null"));
            assertTrue(script.contains("const triggerCurrent=modeTrigger?modeOf(labelOf(modeTrigger)):''"));
            assertTrue(script.contains("let modeReadback=currentMode===requestedMode"));
            assertTrue(script.contains("action='open-mode-menu';toggleMenu(modeTrigger,true)"));
            assertTrue(script.contains("[role=\"menuitemradio\"]"));
            assertFalse(script.contains("const groups=[]"));
            assertFalse(script.contains("calibratedImplicit"));
        }
    }

    @Test public void bootstrapUsesScopeSpecificCalibrationWithoutTrustingWrongModeText() {
        String general = SelfRunDom.prepareInitialContext(SelfRunScript.GENERAL_CHAT_URL,
                SelfRunStore.MODE_CHAT, "SR-20260820-GENERAL");
        String project = SelfRunDom.prepareInitialContext("https://chatgpt.com/g/g-p-test/project",
                SelfRunStore.MODE_WORK, "SR-20260820-PROJECT");
        assertTrue(general.contains("const newChatControl=__srFind(\"GENERAL_NEW_CHAT\")||fallbackNewChat"));
        assertFalse(general.contains("const newChatControl=__srFind(\"PROJECT_NEW_CHAT\")||fallbackNewChat"));
        assertTrue(project.contains("const newChatControl=__srFind(\"PROJECT_NEW_CHAT\")||fallbackNewChat"));
        assertFalse(project.contains("const newChatControl=__srFind(\"GENERAL_NEW_CHAT\")||fallbackNewChat"));
        assertTrue(general.contains("rawCalibratedTarget&&modeOf(labelOf(rawCalibratedTarget))===requestedMode"));
        assertTrue(project.contains("rawCalibratedTarget&&modeOf(labelOf(rawCalibratedTarget))===requestedMode"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
