package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import static org.junit.Assert.*;

public class SelfRunDomTest {
    @Test public void controlReadIsBestEffortOnly() {
        String script = SelfRunDom.readLatestSelfRunControl(
                "https://chatgpt.com/g/g-p-test/c/conversation123", "SR-20260813-220315-A1B2C3");
        assertTrue(script.contains("SELF_RUN_NEXT"));
        assertTrue(script.contains("CONTROL_MISSING"));
        assertFalse(script.contains("stop-button"));
        assertFalse(script.contains("GENERATING"));
        assertFalse(script.contains("data-is-streaming"));
    }

    @Test public void continuationOnlyStagesComposerAndMarker() {
        String script = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/g/g-p-test/c/conversation123",
                "[2026.08.13 | 22:03:15] [SELF_RUN_CONTINUE SR-20260813-220315-A1B2C3]", "m1");
        assertTrue(script.contains("READY_TO_SUBMIT"));
        assertFalse(script.contains("CONFIRMED"));
        assertFalse(script.contains("assistant"));
    }

    @Test public void bootstrapStageAndClickBothRecheckFreshRoute() {
        String url = "https://chatgpt.com/g/g-p-test/project";
        String stage = SelfRunDom.sendDriveInitial(url, "bootstrap", "m1");
        String click = SelfRunDom.clickPreparedDriveInitial(url, "bootstrap", "m1");
        for (String script : new String[]{stage, click}) {
            assertTrue(script.contains("freshConversation"));
            assertTrue(script.contains("freshTurnCount"));
            assertTrue(script.contains("EXISTING_CONVERSATION"));
            assertTrue(script.contains("STALE_NEW_ROUTE"));
        }
    }

    @Test public void generalChatScopeSupportsBootstrapAndWorkPreferences() {
        String initial = SelfRunDom.prepareInitialContext(SelfRunScript.GENERAL_CHAT_URL, SelfRunStore.MODE_CHAT, "SR-20260814-TEST00");
        String model = WorkPreferenceDom.modelForProject(SelfRunScript.GENERAL_CHAT_URL, "sol");
        assertTrue(initial.contains("__GENERAL_CHAT__"));
        assertTrue(initial.contains("일반 Chat 범위 이탈"));
        assertTrue(model.contains("__GENERAL_CHAT__"));
        assertTrue(model.contains("일반 Chat 범위 이탈"));
    }

    @Test public void initialModeGateFallsBackOnlyForChatAfterRecordedClick() {
        String script = SelfRunDom.prepareInitialContext(
                SelfRunScript.GENERAL_CHAT_URL, SelfRunStore.MODE_CHAT, "SR-20260816-MODE00");
        assertTrue(script.contains("const chatImplicitAfterClick=requestedMode==='chat'"));
        assertTrue(script.contains("priorAction==='select-mode'"));
        assertTrue(script.contains("priorRequested==='chat'"));
        assertTrue(script.contains("selectedModes.length===0"));
        assertTrue(script.contains("!!composer"));
        assertTrue(script.contains("||chatImplicitAfterClick"));
        assertFalse(script.contains("requestedMode==='work'&&priorAction==='select-mode'"));
    }
}
