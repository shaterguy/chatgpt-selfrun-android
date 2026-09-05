package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TurnProtocolUiStateTest {
    @Test public void labelsDescribeProtocolOnlyState() {
        assertEquals("추론 중",TurnProtocolUiState.headlineFor("turn_request","THINKING"));
        assertEquals("답변 생성 중",TurnProtocolUiState.headlineFor("answering_started","ANSWERING"));
        assertEquals("답변 완료 · 차기 턴 대기",TurnProtocolUiState.headlineFor("complete","COMPLETE"));
        assertEquals("응답 감지 중 · 프로토콜",TurnProtocolUiState.detectorHeadline(TurnProtocolUiState.DETECTOR_PROTOCOL));
    }
    @Test public void ignoredEarlyBoundaryAndHandoffRemainThinking() {
        assertEquals("추론 중",TurnProtocolUiState.headlineFor("completion_ignored","THINKING"));
        assertEquals("추론 중",TurnProtocolUiState.headlineFor("stream_handoff","THINKING"));
        assertEquals("추론",TurnProtocolUiState.pillFor(TurnProtocolUiState.headlineFor("completion_ignored","THINKING")));
    }
    @Test public void snapshotRequiresOneExactToken() {
        TurnProtocolUiState.Snapshot s=new TurnProtocolUiState.Snapshot(true,"run","token","turn_request","THINKING","PROTOCOL",1L);
        assertTrue(s.activeGenerationFor("token"));
        assertTrue(s.generationStartedFor("token"));
        assertFalse(s.activeGenerationFor("stale"));
    }
}
