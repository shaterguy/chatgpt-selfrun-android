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

    @Test public void bootstrapWaitsUseStablePrivacySafeReasons() {
        assertEquals("status=UI_WAIT;phase=bootstrap_context;reason=mode_switch_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_BOOTSTRAP, "UI_WAIT", "모드 전환 반영 대기 · private"));
        assertEquals("status=UI_WAIT;phase=bootstrap_context;reason=mode_state_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_BOOTSTRAP, "UI_WAIT", "실행 모드 실제 상태 대기 · private"));
        assertEquals("status=UI_WAIT;phase=bootstrap_context;reason=composer_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_BOOTSTRAP, "UI_WAIT", "새 대화 입력창 대기"));
        assertEquals("status=WAIT;phase=bootstrap_model;reason=model_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_BOOTSTRAP_MODEL, "WAIT", "private"));
        assertEquals("status=UI_WAIT;phase=bootstrap_reasoning;reason=reasoning_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_BOOTSTRAP_REASONING, "UI_WAIT", "private"));
    }

    @Test public void launchAndPageDiagnosticsNeverExposeUrlsOrErrorMessages() {
        String retry = SelfRunWebDiagnostics.launchRetryDetail(new IllegalStateException("private url"), 2_500L);
        assertEquals("error=IllegalStateException;retry_in_ms=2500", retry);
        assertFalse(retry.contains("private"));
        assertEquals("route=project_conversation",
                SelfRunWebDiagnostics.pageDetail("https://chatgpt.com/g/g-p-secret/c/private-conversation"));
        assertEquals("reason=target_missing;phase=bootstrap_context;retry_in_ms=300000",
                SelfRunWebDiagnostics.targetRetryDetail(SelfRunStore.PHASE_BOOTSTRAP, 300_000L));
        assertEquals("reason=renderer_gone;retry_in_ms=2000",
                SelfRunWebDiagnostics.rendererRetryDetail(2_000L));
        assertEquals("error_code=-2;retry_in_ms=3000", SelfRunWebDiagnostics.pageErrorDetail(-2, 3_000L));
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
