package com.shaterguy.chatgptselfrun;

/** Privacy-safe categories for continuation diagnostics written to the local run log. */
final class SelfRunWebDiagnostics {
    private SelfRunWebDiagnostics() {}

    static String waitDetail(String status, String detail) {
        String value = detail == null ? "" : detail;
        String reason;
        if (value.contains("continuation 입력창 대기")) reason = "composer_wait";
        else if (value.contains("continuation 전송 버튼 대기")) reason = "send_wait";
        else if (value.contains("입력 반영 확인 대기")) reason = "input_reflection_wait";
        else if (value.contains("continuation 입력 대기")) reason = "input_wait";
        else reason = "ui_wait";
        String safeStatus = "WAIT".equals(status) ? "WAIT" : "UI_WAIT";
        return "status=" + safeStatus + ";reason=" + reason;
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
