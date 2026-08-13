package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class SelfRunContinuationSubmissionTest {
    @Test
    public void durableClickMarkerAdvancesWithoutExactUserDomEcho() {
        String script = SelfRunDom.sendTurn(
                "https://chatgpt.com/g/g-p-demo/c/abc",
                "[SELF_RUN_CONTINUE SR-1]",
                "SR-1",
                3);

        assertTrue(script.contains("chatgpt-selfrun:turn:SR-1:3"));
        assertTrue(script.contains("assistantBaselineKey"));
        assertTrue(script.contains("전송 클릭 표식으로 현재 턴 인계"));
        assertTrue(script.contains("matching>baseline"));
        assertTrue(script.contains("assistantKey:assistantBaselineKey"));
    }

    @Test
    public void legacyMarkerStillUsesHistoricalCountFallback() {
        String script = SelfRunDom.sendTurn(
                "https://chatgpt.com/g/g-p-demo/c/abc",
                "[SELF_RUN_CONTINUE SR-1]",
                "SR-1",
                4);

        assertTrue(script.contains("이전 버전 제출 표식 DOM 확인 대기"));
        assertTrue(script.contains("matching>baseline"));
    }

    @Test
    public void bootstrapConversationUrlAndMarkerDoNotRequireExactUserDomEcho() {
        String script = SelfRunDom.sendInitial(
                "https://chatgpt.com/g/g-p-demo/project",
                "hello",
                "SR-boot");

        assertTrue(script.contains("chatgpt-selfrun:bootstrap:SR-boot"));
        assertTrue(script.contains("if(conv&&prior)return result('CONFIRMED'"));
        assertTrue(script.contains("새 conversation URL과 제출 표식 확인"));
        assertTrue(script.contains("conversationUrl:location.href"));
    }

    @Test
    public void driveBootstrapConfirmsFromConversationUrlAndDurableClickMarker() {
        String script = SelfRunDom.checkDriveInitialSubmitted(
                "https://chatgpt.com/g/g-p-demo/project",
                "SR-drive-boot");

        assertTrue(script.contains("if(conv&&prior)return result('CONFIRMED'"));
        assertTrue(script.contains("새 conversation URL과 제출 표식 확인"));
        assertTrue(script.contains("conversationUrl:location.href"));
        assertTrue(!script.contains("data-message-author-role=\"user\""));
    }
    @Test
    public void retryPreparationRechecksLateSuccessAndReturnsFreshBaseline() {
        String script = SelfRunDom.prepareDriveTurnRetry(
                "https://chatgpt.com/g/g-p-demo/c/abc",
                "[SELF_RUN_CONTINUE SR-1]", "SR-1:2:7", 3);
        assertTrue(script.contains("countPrompt()>baseline"));
        assertTrue(script.contains("재시도 전 기존 continuation 사용자 턴 확인"));
        assertTrue(script.contains("beforeCount:before"));
        assertTrue(script.contains("retry:true"));
    }

    @Test
    public void androidBaselineConfirmsEvenWhenWebMarkerWasLost() {
        String script = SelfRunDom.checkDriveTurnSubmitted(
                "https://chatgpt.com/g/g-p-demo/c/abc",
                "[SELF_RUN_CONTINUE SR-1]", "SR-1:2:7", 3);
        assertTrue(script.contains("androidBaseline>=0&&count>androidBaseline"));
        assertTrue(script.contains("Android baseline 이후 continuation 사용자 턴 증가 확인"));
        assertTrue(!script.contains("assistant"));
    }

}
