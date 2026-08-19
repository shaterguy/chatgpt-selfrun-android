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
        assertEquals("status=UI_WAIT;phase=send_continue;reason=composer_replaced_abort",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_SEND_CONTINUE, "UI_WAIT", "prepared continuation composer replaced · click abort"));
        String future = SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_SEND_CONTINUE, "UI_WAIT", "future detail containing user text");
        assertEquals("status=UI_WAIT;phase=send_continue;reason=ui_wait", future);
        assertFalse(future.contains("future detail"));
        assertFalse(future.contains("user text"));
    }

    @Test public void staleCallbackAbortContainsOnlySafeCategories() {
        String detail = SelfRunWebDiagnostics.abortDetail("stale_callback", false, false, false);
        assertEquals("abort=stale_callback;webview_match=0;generation_match=0;freshness_match=0", detail);
        assertFalse(detail.contains("chatgpt.com"));
        assertFalse(detail.contains("conversation"));
        assertFalse(detail.contains("continue"));
    }

    @Test public void conversationSyncDiagnosticsRejectReloadAsNormalNavigation() {
        String detail = SelfRunWebDiagnostics.syncDetail(7L, 12, true, "reuse", 2, false, true, true, false);
        assertEquals("sync_epoch=7;generation=12;canonical_match=1;navigation=reuse;candidate_count=2;turn_contained=0;submit_scope=1;generation_match=1;discarded=0", detail);
        assertFalse(detail.contains("chatgpt.com"));
        assertFalse(detail.contains("conversation"));
        String legacy = SelfRunWebDiagnostics.syncDetail(7L, 12, true, "reload", 2, false, true, true, false);
        assertTrue(legacy.contains("navigation=none"));
        assertFalse(legacy.contains("navigation=reload"));
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
