package com.shaterguy.chatgptselfrun;

/** Privacy-safe categories for continuation diagnostics written to the local run log. */
final class SelfRunWebDiagnostics {
    private SelfRunWebDiagnostics() {}

    static String waitDetail(String phase, String status, String detail) {
        String value = detail == null ? "" : detail;
        String reason;
        if (SelfRunStore.PHASE_APPLY_PREFS.equals(phase)) reason = "model_wait";
        else if (SelfRunStore.PHASE_APPLY_REASONING.equals(phase)) reason = "reasoning_wait";
        else if (value.contains("continuation 입력창 대기")) reason = "composer_wait";
        else if (value.contains("continuation 전송 버튼 대기")) reason = "send_wait";
        else if (value.contains("입력 반영 확인 대기")) reason = "input_reflection_wait";
        else if (value.contains("continuation 입력 대기")) reason = "input_wait";
        else if (value.contains("prepared continuation composer replaced")) reason = "composer_replaced_abort";
        else reason = "ui_wait";
        String safeStatus = "WAIT".equals(status) ? "WAIT" : "UI_WAIT";
        return "status=" + safeStatus + ";phase=" + phaseKind(phase) + ";reason=" + reason;
    }

    private static String phaseKind(String phase) {
        if (SelfRunStore.PHASE_APPLY_PREFS.equals(phase)) return "apply_model";
        if (SelfRunStore.PHASE_APPLY_REASONING.equals(phase)) return "apply_reasoning";
        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)) return "send_continue";
        return "other";
    }

    static String syncDetail(long syncEpoch, int generation, boolean canonicalMatch, String navigation,
                   int candidateCount, boolean turnContained, boolean submitScope,
                   boolean generationMatch, boolean discarded) {
        String safeNavigation = switch (navigation == null ? "" : navigation) {
  case "reload", "loadUrl", "loadUrl_recovery", "new_webview", "pending" -> navigation;
  default -> "none";
        };
        return "sync_epoch=" + syncEpoch + ";generation=" + generation
      + ";canonical_match=" + (canonicalMatch ? "1" : "0")
      + ";navigation=" + safeNavigation
      + ";candidate_count=" + candidateCount
      + ";turn_contained=" + (turnContained ? "1" : "0")
      + ";submit_scope=" + (submitScope ? "1" : "0")
      + ";generation_match=" + (generationMatch ? "1" : "0")
      + ";discarded=" + (discarded ? "1" : "0");
    }

    static String abortDetail(String reason, boolean webViewMatch, boolean generationMatch, boolean freshnessMatch) {
        String safeReason = "stale_callback".equals(reason) ? "stale_callback" : "other";
        return "abort=" + safeReason
                + ";webview_match=" + (webViewMatch ? "1" : "0")
                + ";generation_match=" + (generationMatch ? "1" : "0")
                + ";freshness_match=" + (freshnessMatch ? "1" : "0");
    }

    static String routeMismatchDetail(String expected, String actual) {
        String expectedId = SelfRunScript.conversationId(expected);
        String actualId = SelfRunScript.conversationId(actual);
        boolean sameConversation = !expectedId.isEmpty() && expectedId.equals(actualId);
        return "status=ROUTE_MISMATCH;expected=" + routeKind(expected)
                + ";actual=" + routeKind(actual)
                + ";conversation_match=" + (sameConversation ? "1" : "0");
    }

    static String routeKind(String url) {
        if (SelfRunScript.isGeneralChatUrl(url)) {
            return SelfRunScript.conversationId(url).isEmpty() ? "general_root" : "general_conversation";
        }
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(url);
        if (ref != null) return ref.conversationId.isEmpty() ? "project_root" : "project_conversation";
        return "other";
    }
}
