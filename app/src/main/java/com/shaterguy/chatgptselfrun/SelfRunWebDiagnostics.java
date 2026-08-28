package com.shaterguy.chatgptselfrun;

/** Privacy-safe categories for continuation diagnostics written to the local run log. */
final class SelfRunWebDiagnostics {
    private SelfRunWebDiagnostics() {}

    static String waitDetail(String phase, String status, String detail) {
        String safeStatus = switch (status == null ? "" : status) {
            case "WAIT", "UI_WAIT", "COMPOSER_CLEARING", "COMPOSER_INPUTTING", "SUBMISSION_PENDING",
                    "SEND_DISABLED", "STOP", "UNKNOWN", "SCRIPT_ERROR", "SUBMISSION_FAILED" -> status;
            default -> "OTHER";
        };
        String reason;
        if ("COMPOSER_CLEARING".equals(safeStatus)) reason = "composer_clearing";
        else if ("COMPOSER_INPUTTING".equals(safeStatus)) reason = "composer_inputting";
        else if ("SUBMISSION_PENDING".equals(safeStatus)) reason = "submission_pending";
        else if ("SEND_DISABLED".equals(safeStatus)) reason = "send_disabled";
        else if ("STOP".equals(safeStatus)) reason = "stop_visible";
        else if ("UNKNOWN".equals(safeStatus)) reason = "control_unknown";
        else if ("SCRIPT_ERROR".equals(safeStatus)) reason = "script_error";
        else if ("SUBMISSION_FAILED".equals(safeStatus)) reason = "request_profile_rejected".equals(detail)
                ? "request_profile_rejected" : "submission_failed";
        else if (SelfRunStore.PHASE_APPLY_PREFS.equals(phase)) reason = "model_wait";
        else if (SelfRunStore.PHASE_APPLY_REASONING.equals(phase)) reason = "reasoning_wait";
        else {
            String value = detail == null ? "" : detail;
            if (value.contains("continuation 입력창 대기")) reason = "composer_wait";
            else if (value.contains("continuation 전송 버튼 대기")) reason = "send_wait";
            else if (value.contains("입력 반영 확인 대기")) reason = "input_reflection_wait";
            else if (value.contains("continuation 입력 대기")) reason = "input_wait";
            else reason = "ui_wait";
        }
        return "status=" + safeStatus + ";phase=" + phaseKind(phase) + ";reason=" + reason;
    }

    static String stateDetail(String phase, String status) {
        String safeStatus;
        if (SelfRunContinuationDom.UNKNOWN.equals(status)) safeStatus = "UNKNOWN";
        else if (SelfRunContinuationDom.STOP.equals(status)) safeStatus = "STOP";
        else if (SelfRunContinuationDom.SEND_DISABLED.equals(status)) safeStatus = "SEND_DISABLED";
        else if (SelfRunContinuationDom.SEND_ENABLED.equals(status)) safeStatus = "SEND_ENABLED";
        else if (SelfRunContinuationDom.COMPOSER_IDLE.equals(status)) safeStatus = "COMPOSER_IDLE";
        else safeStatus = "OTHER";
        return "status=" + safeStatus + ";phase=" + phaseKind(phase) + ";reason=state_wait";
    }

    static String callbackTimeoutDetail(String phase) {
        return "status=CALLBACK_TIMEOUT;phase=" + phaseKind(phase) + ";reason=evaluate_javascript";
    }

    private static String phaseKind(String phase) {
        if (SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)) return "bootstrap_send";
        if (SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)) return "wait_turn_completion";
        if (SelfRunStore.PHASE_APPLY_PREFS.equals(phase)) return "apply_model";
        if (SelfRunStore.PHASE_APPLY_REASONING.equals(phase)) return "apply_reasoning";
        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)) return "send_continue";
        return "other";
    }

    static String routeMismatchDetail(String phase, String expected, String actual) {
        String expectedId = SelfRunScript.conversationId(expected);
        String actualId = SelfRunScript.conversationId(actual);
        boolean sameConversation = !expectedId.isEmpty() && expectedId.equals(actualId);
        return "status=ROUTE_MISMATCH;phase=" + phaseKind(phase) + ";expected=" + routeKind(expected)
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
