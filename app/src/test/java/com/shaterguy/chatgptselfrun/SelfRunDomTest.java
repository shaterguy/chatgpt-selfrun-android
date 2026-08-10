package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class SelfRunDomTest {
    @Test
    public void routeHelpersExtractProjectAndConversation() {
        String url = "https://chatgpt.com/g/g-p-demo/project/c/abc123";
        assertEquals("g-p-demo", SelfRunScript.projectId(url));
        assertEquals("abc123", SelfRunScript.conversationId(url));
    }

    @Test
    public void workModelAndReasoningAreSeparateEvaluations() {
        String model = WorkPreferenceDom.modelForConversation(
                "https://chatgpt.com/g/p/c/abc", "luna");
        String reasoning = WorkPreferenceDom.reasoningForConversation(
                "https://chatgpt.com/g/p/c/abc", "max");
        assertTrue(model.contains("wanted=\"luna\""));
        assertTrue(model.contains("modelOf"));
        assertFalse(model.contains("wantedReasoning"));
        assertTrue(reasoning.contains("wanted=\"max\""));
        assertTrue(reasoning.contains("effort="));
        assertFalse(reasoning.contains("modelOf"));
    }

    @Test
    public void chatModePreparationDoesNotContainModelSelection() {
        String script = SelfRunDom.prepareMode(
                "https://chatgpt.com/g/g-p-demo", SelfRunStore.MODE_CHAT, "SR-1");
        assertTrue(script.contains("Chat 모드"));
        assertFalse(script.contains("sol|terra|luna"));
        assertFalse(script.contains("reasoning"));
    }

    @Test
    public void initialAndContinuationSubmissionHaveDurableSessionMarkers() {
        String initial = SelfRunDom.sendInitial(
                "https://chatgpt.com/g/g-p-demo", "hello", "SR-1");
        String next = SelfRunDom.sendTurn(
                "https://chatgpt.com/g/g-p-demo/c/abc", "[SELF_RUN_CONTINUE SR-1]", "SR-1", 2);
        assertTrue(initial.contains("chatgpt-selfrun:bootstrap:SR-1"));
        assertTrue(next.contains("chatgpt-selfrun:turn:SR-1:2"));
        assertTrue(next.contains("SUBMITTED"));
        assertTrue(next.contains("CONFIRMED"));
    }

    @Test
    public void desktopWebViewUsesSchedulerLandscapeViewport() {
        assertEquals(1440, HeadlessWebViewHost.WIDTH);
        assertEquals(900, HeadlessWebViewHost.HEIGHT);
        assertEquals(160, HeadlessWebViewHost.DENSITY_DPI);
    }
}
