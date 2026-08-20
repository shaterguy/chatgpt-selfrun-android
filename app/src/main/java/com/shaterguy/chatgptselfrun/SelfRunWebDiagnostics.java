package com.shaterguy.chatgptselfrun;

/** Privacy-safe categories for WebView diagnostics written to the local run log. */
final class SelfRunWebDiagnostics {
    private SelfRunWebDiagnostics() {}

    static String waitDetail(String phase, String status, String detail) {
        String value = detail == null ? "" : detail;
        String reason;
        if (SelfRunStore.PHASE_BOOTSTRAP.equals(phase) && value.contains("모드 전환 반영 대기")) reason = "mode_switch_wait";
        else if (SelfRunStore.PHASE_BOOTSTRAP.equals(phase) && value.contains("실행 모드 실제 상태 대기")) reason = "mode_state_wait";
        else if (SelfRunStore.PHASE_BOOTSTRAP.equals(phase) && value.contains("새 대화 입력창 대기")) reason = "composer_wait";
        else if (SelfRunStore.PHASE_BOOTSTRAP.equals(phase) && value.contains("새 대화 전환 반영 대기")) reason = "new_chat_navigation_wait";
        else if (SelfRunStore.PHASE_BOOTSTRAP_MODEL.equals(phase) || SelfRunStore.PHASE_APPLY_PREFS.equals(phase)) reason = "model_wait";
        else if (SelfRunStore.PHASE_BOOTSTRAP_REASONING.equals(phase) || SelfRunStore.PHASE_APPLY_REASONING.equals(phase)) reason = "reasoning_wait";
        else if (SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase) && value.contains("입력창 대기")) reason = "composer_wait";
        else if (SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase) && value.contains("전송 버튼 대기")) reason = "send_wait";
        else if (value.contains("continuation 입력창 대기")) reason = "composer_wait";
        else if (value.contains("continuation 전송 버튼 대기")) reason = "send_wait";
        else if (value.contains("입력 반영 확인 대기")) reason = "input_reflection_wait";
        else if (value.contains("continuation 입력 대기")) reason = "input_wait";
        else reason = "ui_wait";
        String safeStatus = "WAIT".equals(status) ? "WAIT" : "UI_WAIT";
        return "status=" + safeStatus + ";phase=" + phaseKind(phase) + ";reason=" + reason;
    }

    static String phaseKind(String phase) {
        if (SelfRunStore.PHASE_BOOTSTRAP.equals(phase)) return "bootstrap_context";
        if (SelfRunStore.PHASE_BOOTSTRAP_MODEL.equals(phase)) return "bootstrap_model";
        if (SelfRunStore.PHASE_BOOTSTRAP_REASONING.equals(phase)) return "bootstrap_reasoning";
        if (SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)) return "bootstrap_send";
        if (SelfRunStore.PHASE_APPLY_PREFS.equals(phase)) return "apply_model";
        if (SelfRunStore.PHASE_APPLY_REASONING.equals(phase)) return "apply_reasoning";
        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)) return "send_continue";
        return "other";
    }

    static String launchDetail(String phase) { return "phase=" + phaseKind(phase); }

    static String launchRetryDetail(Throwable error, long retryDelayMs) {
        String kind = error == null ? "Throwable" : error.getClass().getSimpleName();
        if (kind == null || !kind.matches("[A-Za-z0-9_$]{1,80}")) kind = "Throwable";
        return "error=" + kind + ";retry_in_ms=" + Math.max(0L, retryDelayMs);
    }

    static String targetRetryDetail(String phase, long retryDelayMs) {
        return "reason=target_missing;phase=" + phaseKind(phase)
                + ";retry_in_ms=" + Math.max(0L, retryDelayMs);
    }

    static String rendererRetryDetail(long retryDelayMs) {
        return "reason=renderer_gone;retry_in_ms=" + Math.max(0L, retryDelayMs);
    }

    static String pageDetail(String url) { return "route=" + routeKind(url); }

    static String pageErrorDetail(int errorCode, long retryDelayMs) {
        return "error_code=" + errorCode + ";retry_in_ms=" + Math.max(0L, retryDelayMs);
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
