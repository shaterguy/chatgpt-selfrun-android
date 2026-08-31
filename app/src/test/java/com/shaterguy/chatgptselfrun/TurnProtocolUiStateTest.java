package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TurnProtocolUiStateTest {
    @Test public void userFacingLabelsDescribeActualTurnState() {
        assertEquals("추론 중", TurnProtocolUiState.headlineFor("turn_request", "THINKING"));
        assertEquals("답변 시작 대기 중",
                TurnProtocolUiState.headlineFor("completion_ignored", "THINKING"));
        assertEquals("답변 생성 중",
                TurnProtocolUiState.headlineFor("answering_started", "ANSWERING"));
        assertEquals("답변 완료 · 차기 턴 대기",
                TurnProtocolUiState.headlineFor("complete", "COMPLETE"));
        assertEquals("응답 상태 오류", TurnProtocolUiState.headlineFor("error", "ERROR"));
    }

    @Test public void pillsUseCompactKoreanStateNames() {
        assertEquals("추론", TurnProtocolUiState.pillFor("추론 중"));
        assertEquals("전환", TurnProtocolUiState.pillFor("답변 시작 대기 중"));
        assertEquals("답변", TurnProtocolUiState.pillFor("답변 생성 중"));
        assertEquals("대기", TurnProtocolUiState.pillFor("답변 완료 · 차기 턴 대기"));
        assertEquals("정지", TurnProtocolUiState.pillFor("일시정지"));
    }

    @Test public void detectorLabelsExposeProtocolPrimaryAndDomFallbackOnly() {
        assertEquals("응답 감지 중 · 프로토콜 우선 / DOM fallback 병행",
                TurnProtocolUiState.detectorHeadline(TurnProtocolUiState.DETECTOR_PROTOCOL_PRIMARY));
        assertEquals("응답 감지 중 · DOM fallback",
                TurnProtocolUiState.detectorHeadline(TurnProtocolUiState.DETECTOR_DOM_FALLBACK_ONLY));
        assertEquals("감지", TurnProtocolUiState.pillFor("응답 감지 중 · DOM fallback"));
    }
}
