package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import static org.junit.Assert.*;

public class SelfRunWebDiagnosticsTest {
    @Test public void continuationWaitsUseStablePrivacySafeReasons() {
        String phase = SelfRun3Coordinator.PHASE_PREPARING;
        assertEquals("status=UI_WAIT;phase=v3_preparing;reason=composer_wait",
                SelfRunWebDiagnostics.waitDetail(phase, "UI_WAIT", "continuation 입력창 대기"));
        assertEquals("status=UI_WAIT;phase=v3_preparing;reason=send_wait",
                SelfRunWebDiagnostics.waitDetail(phase, "UI_WAIT", "continuation 전송 버튼 대기"));
        assertEquals("status=UI_WAIT;phase=v3_preparing;reason=input_reflection_wait",
                SelfRunWebDiagnostics.waitDetail(phase, "UI_WAIT", "입력 반영 확인 대기"));
        assertEquals("status=WAIT;phase=v3_preparing;reason=input_wait",
                SelfRunWebDiagnostics.waitDetail(phase, "WAIT", "continuation 입력 대기"));
        String future = SelfRunWebDiagnostics.waitDetail(phase, "UI_WAIT", "future detail containing user text");
        assertEquals("status=UI_WAIT;phase=v3_preparing;reason=ui_wait", future);
        assertFalse(future.contains("future detail"));
        assertFalse(future.contains("user text"));
    }

    @Test public void dispatchDiagnosticsAreV3ScopedAndPrivacySafe() {
        String phase = SelfRun3Coordinator.PHASE_DISPATCHING;
        String wait = SelfRunWebDiagnostics.waitDetail(phase,
                "UI_WAIT", "future detail containing secret prompt");
        assertEquals("status=UI_WAIT;phase=v3_dispatching;reason=ui_wait", wait);
        assertEquals("status=CALLBACK_TIMEOUT;phase=v3_dispatching;reason=evaluate_javascript",
                SelfRunWebDiagnostics.callbackTimeoutDetail(phase));
        assertEquals("status=COMPOSER_CLEARING;phase=v3_dispatching;reason=composer_clearing",
                SelfRunWebDiagnostics.waitDetail(phase,
                        "COMPOSER_CLEARING", "secret prompt must not leak"));
        assertEquals("status=COMPOSER_INPUTTING;phase=v3_dispatching;reason=composer_inputting",
                SelfRunWebDiagnostics.waitDetail(phase,
                        "COMPOSER_INPUTTING", "secret prompt must not leak"));
        assertEquals("status=SEND_DISABLED;phase=v3_dispatching;reason=send_disabled",
                SelfRunWebDiagnostics.waitDetail(phase,
                        "SEND_DISABLED", "secret prompt must not leak"));
        assertEquals("status=SUBMISSION_PENDING;phase=v3_dispatching;reason=submission_pending",
                SelfRunWebDiagnostics.waitDetail(phase,
                        "SUBMISSION_PENDING", "dispatch detail with secret prompt"));
        assertEquals("status=SUBMISSION_FAILED;phase=v3_dispatching;reason=request_profile_rejected",
                SelfRunWebDiagnostics.waitDetail(phase,
                        "SUBMISSION_FAILED", "request_profile_rejected"));
        assertFalse(wait.contains("secret prompt"));
    }

    @Test public void targetErrorsUseOnlyFixedPrivacySafeReasons() {
        String phase = SelfRun3Coordinator.PHASE_PREPARING;
        assertEquals("status=TARGET_ERROR;phase=v3_preparing;reason=host_mismatch",
                SelfRunWebDiagnostics.targetErrorDetail(phase, "host mismatch"));
        assertEquals("status=TARGET_ERROR;phase=v3_preparing;reason=project_mismatch",
                SelfRunWebDiagnostics.targetErrorDetail(phase, "프로젝트 불일치"));
        assertEquals("status=TARGET_ERROR;phase=v3_preparing;reason=conversation_mismatch",
                SelfRunWebDiagnostics.targetErrorDetail(phase, "canonical conversation mismatch"));
        assertEquals("status=TARGET_ERROR;phase=v3_preparing;reason=general_target_mismatch",
                SelfRunWebDiagnostics.targetErrorDetail(phase, "일반 Chat 범위 이탈"));
        String unknown = SelfRunWebDiagnostics.targetErrorDetail(
                phase, "https://chatgpt.com/g/secret-project/c/secret-conversation");
        assertEquals("status=TARGET_ERROR;phase=v3_preparing;reason=unknown", unknown);
        assertFalse(unknown.contains("chatgpt.com"));
        assertFalse(unknown.contains("secret"));
    }

    @Test public void routeMismatchDoesNotExposeUrlsOrConversationIds() {
        String detail = SelfRunWebDiagnostics.routeMismatchDetail(SelfRun3Coordinator.PHASE_PREPARING,
                "https://chatgpt.com/c/conversation123", "https://chatgpt.com/settings");
        assertEquals("status=ROUTE_MISMATCH;phase=v3_preparing;expected=general_conversation;actual=other;conversation_match=0", detail);
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
