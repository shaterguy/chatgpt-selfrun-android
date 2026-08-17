from pathlib import Path

SERVICE = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java')
RUN_LOG = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunRunLog.java')
HELPER = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunWebDiagnostics.java')
TEST = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunWebDiagnosticsTest.java')
POLICY_TEST = Path('app/src/test/java/com/shaterguy/chatgptselfrun/ContinuationDiagnosticsPolicyTest.java')
WORKFLOW = Path('.github/workflows/ac04-diagnostics-fix.yml')
SELF = Path('tools/apply_ac04_diagnostics.py')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    return text.replace(old, new, 1)


service = SERVICE.read_text(encoding='utf-8')
service = replace_once(
    service,
    'if (!allowed) postWebCallback(SelfRunService.this::restoreCanonical, 800L);',
    'if (!allowed) { recordContinuationRouteMismatch(requested); postWebCallback(SelfRunService.this::restoreCanonical, 800L); }',
    'navigation route diagnostic',
)
service = replace_once(
    service,
    'if(!routeAcceptable(webView.getUrl())){restoreCanonical();return;}',
    'if(!routeAcceptable(webView.getUrl())){recordContinuationRouteMismatch(webView.getUrl());restoreCanonical();return;}',
    'web step route diagnostic',
)
service = replace_once(
    service,
    'if("TARGET_ERROR".equals(status)){restoreCanonical();return;}',
    'if("TARGET_ERROR".equals(status)){recordContinuationTargetError(phase);restoreCanonical();return;}',
    'target error diagnostic',
)
service = replace_once(
    service,
    'if("UI_WAIT".equals(status)||"WAIT".equals(status)){scheduleWeb("WAIT".equals(status)?2000L:1200L);return;}',
    'if("UI_WAIT".equals(status)||"WAIT".equals(status)){recordContinuationWait(phase,status,result.optString("detail",""));scheduleWeb("WAIT".equals(status)?2000L:1200L);return;}',
    'wait diagnostic',
)
anchor = 'private void handleWebResult(String phase,String status,JSONObject result)'
helpers = '''private void recordContinuationWait(String phase,String status,String detail){if(!SelfRunStore.PHASE_SEND_CONTINUE.equals(phase))return;runLog.record(store,"DOM_RESULT",SelfRunWebDiagnostics.waitDetail(status,detail));}\n\nprivate void recordContinuationRouteMismatch(String actual){if(!SelfRunStore.PHASE_SEND_CONTINUE.equals(store.phase()))return;runLog.record(store,"DOM_RESULT",SelfRunWebDiagnostics.routeMismatchDetail(canonicalUrl(),actual));}\n\nprivate void recordContinuationTargetError(String phase){if(!SelfRunStore.PHASE_SEND_CONTINUE.equals(phase))return;runLog.record(store,"DOM_RESULT","status=TARGET_ERROR;reason=target_guard");}\n\n'''
if anchor not in service:
    raise SystemExit('handleWebResult anchor missing')
service = service.replace(anchor, helpers + anchor, 1)
SERVICE.write_text(service, encoding='utf-8')

run_log = RUN_LOG.read_text(encoding='utf-8')
run_log = replace_once(
    run_log,
    '|| event.equals("TARGET_DRIFT") || event.equals("TARGET_RESTORE")',
    '|| event.equals("TARGET_DRIFT") || event.equals("TARGET_RESTORE") || event.equals("DOM_RESULT")',
    'DOM_RESULT execution visibility',
)
run_log = replace_once(
    run_log,
    'case "TARGET_RESTORE" -> "대상 화면 복구";',
    'case "TARGET_RESTORE" -> "대상 화면 복구";\n            case "DOM_RESULT" -> "WebView 대기/진단";',
    'DOM_RESULT label',
)
RUN_LOG.write_text(run_log, encoding='utf-8')

HELPER.write_text('''package com.shaterguy.chatgptselfrun;\n\n/** Privacy-safe categories for continuation diagnostics written to the local run log. */\nfinal class SelfRunWebDiagnostics {\n    private SelfRunWebDiagnostics() {}\n\n    static String waitDetail(String status, String detail) {\n        String value = detail == null ? "" : detail;\n        String reason;\n        if (value.contains("continuation 입력창 대기")) reason = "composer_wait";\n        else if (value.contains("continuation 전송 버튼 대기")) reason = "send_wait";\n        else if (value.contains("입력 반영 확인 대기")) reason = "input_reflection_wait";\n        else if (value.contains("continuation 입력 대기")) reason = "input_wait";\n        else reason = "ui_wait";\n        String safeStatus = "WAIT".equals(status) ? "WAIT" : "UI_WAIT";\n        return "status=" + safeStatus + ";reason=" + reason;\n    }\n\n    static String routeMismatchDetail(String expected, String actual) {\n        String expectedId = SelfRunScript.conversationId(expected);\n        String actualId = SelfRunScript.conversationId(actual);\n        boolean sameConversation = !expectedId.isEmpty() && expectedId.equals(actualId);\n        return "status=ROUTE_MISMATCH;expected=" + routeKind(expected)\n                + ";actual=" + routeKind(actual)\n                + ";conversation_match=" + (sameConversation ? "1" : "0");\n    }\n\n    static String routeKind(String url) {\n        if (SelfRunScript.isGeneralChatUrl(url)) {\n            return SelfRunScript.conversationId(url).isEmpty() ? "general_root" : "general_conversation";\n        }\n        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(url);\n        if (ref != null) return ref.conversationId.isEmpty() ? "project_root" : "project_conversation";\n        return "other";\n    }\n}\n''', encoding='utf-8')

TEST.write_text('''package com.shaterguy.chatgptselfrun;\n\nimport org.junit.Test;\nimport static org.junit.Assert.*;\n\npublic class SelfRunWebDiagnosticsTest {\n    @Test public void continuationWaitsUseStablePrivacySafeReasons() {\n        assertEquals("status=UI_WAIT;reason=composer_wait",\n                SelfRunWebDiagnostics.waitDetail("UI_WAIT", "continuation 입력창 대기"));\n        assertEquals("status=UI_WAIT;reason=send_wait",\n                SelfRunWebDiagnostics.waitDetail("UI_WAIT", "continuation 전송 버튼 대기"));\n        assertEquals("status=UI_WAIT;reason=input_reflection_wait",\n                SelfRunWebDiagnostics.waitDetail("UI_WAIT", "입력 반영 확인 대기"));\n        assertEquals("status=WAIT;reason=input_wait",\n                SelfRunWebDiagnostics.waitDetail("WAIT", "continuation 입력 대기"));\n        assertEquals("status=UI_WAIT;reason=ui_wait",\n                SelfRunWebDiagnostics.waitDetail("UI_WAIT", "future detail containing user text"));\n    }\n\n    @Test public void routeMismatchDoesNotExposeUrlsOrConversationIds() {\n        String detail = SelfRunWebDiagnostics.routeMismatchDetail(\n                "https://chatgpt.com/c/conversation123", "https://chatgpt.com/settings");\n        assertEquals("status=ROUTE_MISMATCH;expected=general_conversation;actual=other;conversation_match=0", detail);\n        assertFalse(detail.contains("chatgpt.com"));\n        assertFalse(detail.contains("conversation123"));\n    }\n\n    @Test public void routeKindPreservesScopeWithoutIdentifiers() {\n        assertEquals("general_root", SelfRunWebDiagnostics.routeKind("https://chatgpt.com/"));\n        assertEquals("general_conversation", SelfRunWebDiagnostics.routeKind("https://www.chatgpt.com/c/abc?src=provider"));\n        assertEquals("project_root", SelfRunWebDiagnostics.routeKind("https://chatgpt.com/g/g-p-test/project"));\n        assertEquals("project_conversation", SelfRunWebDiagnostics.routeKind("https://chatgpt.com/g/g-p-test/c/abc"));\n        assertEquals("other", SelfRunWebDiagnostics.routeKind("https://example.com/c/abc"));\n    }\n}\n''', encoding='utf-8')

POLICY_TEST.write_text('''package com.shaterguy.chatgptselfrun;\n\nimport org.junit.Test;\nimport java.nio.charset.StandardCharsets;\nimport java.nio.file.Files;\nimport java.nio.file.Path;\nimport java.nio.file.Paths;\nimport static org.junit.Assert.*;\n\npublic class ContinuationDiagnosticsPolicyTest {\n    @Test public void sendContinueWaitAndRouteFailuresReachDeduplicatedRunLog() throws Exception {\n        String service = src("SelfRunService.java");\n        String log = src("SelfRunRunLog.java");\n        assertTrue(service.contains("recordContinuationRouteMismatch(webView.getUrl())"));\n        assertTrue(service.contains("recordContinuationRouteMismatch(requested)"));\n        assertTrue(service.contains("recordContinuationWait(phase,status,result.optString(\\\"detail\\\",\\\"\\\"))"));\n        assertTrue(service.contains("recordContinuationTargetError(phase)"));\n        assertTrue(service.contains("runLog.record(store,\\\"DOM_RESULT\\\""));\n        assertTrue(log.contains("if (\\\"DOM_RESULT\\\".equals(event))"));\n        assertTrue(log.contains("event.equals(\\\"DOM_RESULT\\\")"));\n        assertTrue(log.contains("case \\\"DOM_RESULT\\\" -> \\\"WebView 대기/진단\\\""));\n        assertTrue(log.contains("NOISY_HEARTBEAT_MS = 30_000L"));\n    }\n\n    private static String src(String file) throws Exception {\n        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);\n        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);\n        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);\n    }\n}\n''', encoding='utf-8')

# The helper workflow and this one-shot patcher must not remain in the final tree.
if WORKFLOW.exists():
    WORKFLOW.unlink()
if SELF.exists():
    SELF.unlink()
