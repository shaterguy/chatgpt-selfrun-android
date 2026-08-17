package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import static org.junit.Assert.*;

public class SelfRunWebDiagnosticsTest {
    @Test public void continuationWaitsUseStablePrivacySafeReasons() {
        assertEquals("status=UI_WAIT;phase=send_continue;reason=composer_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_SEND_CONTINUE, "UI_WAIT", "continuation 입력창 대기"));
        assertEquals("status=UI_WAIT;phase=send_continue;reason=send_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_SEND_CONTINUE, "UI_WAIT", "continuation 전송 버튼 대기"));
        assertEquals("status=UI_WAIT;phase=send_continue;reason=input_reflection_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_SEND_CONTINUE, "UI_WAIT", "입력 반영 확인 대기"));
        assertEquals("status=WAIT;phase=send_continue;reason=input_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_SEND_CONTINUE, "WAIT", "continuation 입력 대기"));
        String future = SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_SEND_CONTINUE, "UI_WAIT", "future detail containing user text");
        assertEquals("status=UI_WAIT;phase=send_continue;reason=ui_wait", future);
        assertFalse(future.contains("future detail"));
        assertFalse(future.contains("user text"));
    }

    @Test public void routeMismatchDoesNotExposeUrlsOrConversationIds() {
        String detail = SelfRunWebDiagnostics.routeMismatchDetail(
                "https://chatgpt.com/c/conversation123", "https://chatgpt.com/settings");
        assertEquals("status=ROUTE_MISMATCH;expected=general_conversation;actual=other;conversation_match=0", detail);
        assertFalse(detail.contains("chatgpt.com"));
        assertFalse(detail.contains("conversation123"));
    }

    @Test public void routeKindPreservesScopeWithoutIdentifiers() {
        assertEquals("general_root", SelfRunWebDiagnostics.routeKind("https://chatgpt.com/"));
        assertEquals("general_conversation", SelfRunWebDiagnostics.routeKind("https://www.chatgpt.com/c/abc?src=provider"));
        assertEquals("project_root", SelfRunWebDiagnostics.routeKind("https://chatgpt.com/g/g-p-test/project"));
        assertEquals("project_conversation", SelfRunWebDiagnostics.routeKind("https://chatgpt.com/g/g-p-test/c/abc"));
        assertEquals("other", SelfRunWebDiagnostics.routeKind("https://example.com/c/abc"));
    }
}
