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
    public void bootstrapRequiresProjectNewConversationContext() {
        String script = SelfRunDom.prepareInitialContext(
                "https://chatgpt.com/g/g-p-demo", SelfRunStore.MODE_WORK, "SR-1");
        assertTrue(script.contains("EXISTING_CONVERSATION"));
        assertTrue(script.contains("프로젝트 새 대화 입력창 대기"));
        assertTrue(script.contains("실행 모드 실제 상태 대기"));
        assertTrue(script.contains("chatgpt-selfrun:mode:SR-1"));
    }

    @Test
    public void workModelAndReasoningAreSeparateEvaluations() {
        String model = WorkPreferenceDom.modelForConversation(
                "https://chatgpt.com/g/p/c/abc", "luna");
        String reasoning = WorkPreferenceDom.reasoningForConversation(
                "https://chatgpt.com/g/p/c/abc", "max");
        assertTrue(model.contains("wanted=\"luna\""));
        assertTrue(model.contains("modelOf"));
        assertTrue(model.contains("openMenu"));
        assertTrue(reasoning.contains("wanted=\"max\""));
        assertTrue(reasoning.contains("effort="));
        assertTrue(reasoning.contains("openMenu"));
    }

    @Test
    public void chatBootstrapFailsClosedWithoutActualModeReadback() {
        String script = SelfRunDom.prepareInitialContext(
                "https://chatgpt.com/g/g-p-demo", SelfRunStore.MODE_CHAT, "SR-1");
        assertTrue(script.contains("requestedMode="));
        assertTrue(script.contains("modeCandidates"));
        assertTrue(script.contains("modeSelected"));
        assertTrue(script.contains("mode.click()"));
        assertTrue(script.contains("aria-pressed"));
        assertTrue(script.contains("currentMode"));
        assertTrue(script.contains("modeFound"));
        assertTrue(script.contains("modeReadback"));
        assertTrue(script.contains("if(!modeReadback)return result('UI_WAIT','실행 모드 실제 상태 대기'"));
        assertFalse(script.contains("sol|terra|luna"));
        assertFalse(script.contains("reasoningDiagnostics"));
    }

    @Test
    public void bootstrapUsesRequestedTopSwitchButtonForBothModes() {
        String chat = SelfRunDom.prepareInitialContext(
                "https://chatgpt.com/g/g-p-demo", SelfRunStore.MODE_CHAT, "SR-chat");
        String work = SelfRunDom.prepareInitialContext(
                "https://chatgpt.com/g/g-p-demo", SelfRunStore.MODE_WORK, "SR-work");
        assertTrue(chat.contains("['chat','채팅']"));
        assertTrue(work.contains("['work','작업']"));
        assertTrue(chat.contains("[role=\"tab\"]"));
        assertTrue(chat.contains("modeSelected"));
        assertFalse(chat.contains("triggerCandidates"));
    }

    @Test
    public void continuationUsesPerTurnBaselineInsteadOfAnyHistoricalMatch() {
        String next = SelfRunDom.sendTurn(
                "https://chatgpt.com/g/g-p-demo/c/abc", "[SELF_RUN_CONTINUE SR-1]", "SR-1", 2);
        assertTrue(next.contains("chatgpt-selfrun:turn:SR-1:2"));
        assertTrue(next.contains("const matching="));
        assertTrue(next.contains("baseline:matching"));
        assertTrue(next.contains("matching>baseline"));
        assertTrue(next.contains("현재 사용자 턴 확인"));
        assertFalse(next.contains("if(users.some(t=>t===canonical(expected)))return"));
    }

    @Test
    public void assistantObserverOnlyAcceptsAssistantAfterLatestUserTurn() {
        String script = SelfRunDom.observeAssistant(
                "https://chatgpt.com/g/g-p-demo/project/c/abc", "message-1:0");
        assertTrue(script.contains("userIndex=-1"));
        assertTrue(script.contains("for(let i=userIndex+1"));
        assertTrue(script.contains("if(role==='user')break"));
        assertTrue(script.contains("새 assistant 응답 대기"));
        assertTrue(script.contains("assistantKey"));
        assertTrue(script.contains("STALE"));
    }

    @Test
    public void assistantIdentityDoesNotDependOnRenderedTextDigest() {
        String initial = SelfRunDom.sendInitial(
                "https://chatgpt.com/g/g-p-demo", "hello", "SR-1");
        assertTrue(initial.contains("assistantKey"));
        assertFalse(initial.contains("assistantDigest"));
    }

    @Test
    public void initialAndContinuationSubmissionHavePersistentGuards() {
        String initial = SelfRunDom.sendInitial(
                "https://chatgpt.com/g/g-p-demo", "hello", "SR-1");
        String next = SelfRunDom.sendTurn(
                "https://chatgpt.com/g/g-p-demo/c/abc", "[SELF_RUN_CONTINUE SR-1]", "SR-1", 2);
        assertTrue(initial.contains("chatgpt-selfrun:bootstrap:SR-1"));
        assertTrue(initial.contains("EXISTING_CONVERSATION"));
        assertTrue(next.contains("chatgpt-selfrun:turn:SR-1:2"));
        assertTrue(initial.contains("localStorage.setItem"));
        assertTrue(next.contains("localStorage.setItem"));
        assertTrue(next.contains("MARKER_FAILED"));
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
