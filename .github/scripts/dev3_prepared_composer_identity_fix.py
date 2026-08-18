from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one replacement, found {count}: {old[:160]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


dom = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java'
service = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java'
diag = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunWebDiagnostics.java'
latest_test = 'app/src/test/java/com/shaterguy/chatgptselfrun/LatestComposerSubmissionPolicyTest.java'
diag_test = 'app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunWebDiagnosticsTest.java'
fresh_test = 'app/src/test/java/com/shaterguy/chatgptselfrun/ConversationFreshnessBarrierTest.java'

# Keep the exact prepare-time composer DOM object in page-local state. The durable marker
# remains body-free and keeps only state/timestamp/freshness token.
replace_once(
    dom,
    "if(!persisted)return result('MARKER_FAILED','continuation 제출 표식 저장 실패');return result('READY_TO_SUBMIT','continuation 제출 준비 완료');",
    "if(!persisted)return result('MARKER_FAILED','continuation 제출 표식 저장 실패');window.__selfRunDrivePreparedContinuation={markerKey:markerKey2,composer,freshnessToken:__srFreshnessToken,clicked:false};return result('READY_TO_SUBMIT','continuation 제출 준비 완료');"
)

# A replacement composer is never allowed to inherit a prepared attempt merely because its
# text matches. A repeated click evaluation after the first click is also non-clicking.
replace_once(
    dom,
    "+composer(composerKey)+\"if(!composer)return result('UI_WAIT','제출 직전 최신 continuation 입력창 대기');\"+composerOps(sendKey)+",
    "+composer(composerKey)+\"if(!composer)return result('UI_WAIT','제출 직전 최신 continuation 입력창 대기');const prepared=window.__selfRunDrivePreparedContinuation;if(!prepared||prepared.markerKey!==markerKey||prepared.composer!==composer||prepared.freshnessToken!==__srFreshnessToken){window.__selfRunDrivePreparedContinuation=null;return result('UI_WAIT','prepared continuation composer replaced · click abort');}if(prepared.clicked)return result('SUBMISSION_PENDING','continuation click already recorded');\"+composerOps(sendKey)+"
)
replace_once(
    dom,
    "}catch(_){}send.click();return result('SUBMITTED','continuation 클릭 완료');",
    "}catch(_){}prepared.clicked=true;send.click();return result('SUBMITTED','continuation 클릭 완료');"
)

# Every new freshness barrier and pagehide invalidates any prior page-local prepared composer.
replace_once(
    dom,
    "const freshness=\"+token+\";window.__selfRunDriveFreshnessToken=freshness;window.addEventListener('pagehide',()=>{if(window.__selfRunDriveFreshnessToken===freshness)window.__selfRunDriveFreshnessToken='';},{once:true});",
    "const freshness=\"+token+\";window.__selfRunDrivePreparedContinuation=null;window.__selfRunDriveFreshnessToken=freshness;window.addEventListener('pagehide',()=>{if(window.__selfRunDriveFreshnessToken===freshness)window.__selfRunDriveFreshnessToken='';window.__selfRunDrivePreparedContinuation=null;},{once:true});"
)

# Stale same-run continuation callbacks are discarded and logged without URL/message bodies.
replace_once(
    service,
    "active.evaluateJavascript(script,raw->{if(active!=webView||epoch!=generation||!runId.equals(store.runId())||(isContinuationPhase(phase)&&(!freshnessValid()||!freshnessAtStart.equals(conversationFreshnessToken))))return;domInFlight=false;",
    "active.evaluateJavascript(script,raw->{boolean sameRun=runId.equals(store.runId()),sameWebView=active==webView,generationMatch=epoch==generation,freshnessMatch=!isContinuationPhase(phase)||(freshnessValid()&&freshnessAtStart.equals(conversationFreshnessToken));if(!sameRun||!sameWebView||!generationMatch||!freshnessMatch){if(sameRun&&sameWebView&&generationMatch)domInFlight=false;if(sameRun&&isContinuationPhase(phase))runLog.record(store,\"CONTINUE_SUBMIT_ABORT\",SelfRunWebDiagnostics.abortDetail(\"stale_callback\",sameWebView,generationMatch,freshnessMatch));return;}domInFlight=false;"
)

replace_once(
    diag,
    '        else if (value.contains("continuation 입력 대기")) reason = "input_wait";\n        else reason = "ui_wait";',
    '        else if (value.contains("continuation 입력 대기")) reason = "input_wait";\n        else if (value.contains("prepared continuation composer replaced")) reason = "composer_replaced_abort";\n        else reason = "ui_wait";'
)
replace_once(
    diag,
    '    static String routeMismatchDetail(String expected, String actual) {',
    '''    static String abortDetail(String reason, boolean webViewMatch, boolean generationMatch, boolean freshnessMatch) {
        String safeReason = "stale_callback".equals(reason) ? "stale_callback" : "other";
        return "abort=" + safeReason
                + ";webview_match=" + (webViewMatch ? "1" : "0")
                + ";generation_match=" + (generationMatch ? "1" : "0")
                + ";freshness_match=" + (freshnessMatch ? "1" : "0");
    }

    static String routeMismatchDetail(String expected, String actual) {'''
)

old_latest = '''    @Test public void preClickComposerReplacementIsFastRetryNotDriveAckWait() {
        String click = SelfRunDom.clickPreparedDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "marker-reacquire");
        assertTrue(click.contains("제출 직전 최신 continuation 입력창 재확보 · 입력 재반영"));
        assertTrue(click.contains("return result('UI_WAIT'"));
        assertFalse(click.contains("SUBMISSION_AMBIGUOUS"));
        assertFalse(click.contains("location.reload"));
        assertFalse(click.contains("loadUrl"));
    }
'''
new_latest = '''    @Test public void preClickComposerReplacementIsFastRetryNotDriveAckWait() {
        String prepare = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "marker-reacquire", "1:2");
        String click = SelfRunDom.clickPreparedDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "marker-reacquire", "1:2");
        assertTrue(prepare.contains("window.__selfRunDrivePreparedContinuation={markerKey:markerKey2,composer,freshnessToken:__srFreshnessToken,clicked:false}"));
        assertTrue(click.contains("prepared.composer!==composer"));
        assertTrue(click.contains("prepared continuation composer replaced · click abort"));
        assertTrue(click.contains("prepared.clicked"));
        assertTrue(click.contains("SUBMISSION_PENDING"));
        assertTrue(click.indexOf("prepared.composer!==composer") < click.indexOf("send.click()"));
        assertTrue(click.contains("제출 직전 최신 continuation 입력창 재확보 · 입력 재반영"));
        assertTrue(click.contains("return result('UI_WAIT'"));
        assertFalse(click.contains("SUBMISSION_AMBIGUOUS"));
        assertFalse(click.contains("location.reload"));
        assertFalse(click.contains("loadUrl"));
    }
'''
replace_once(latest_test, old_latest, new_latest)

replace_once(
    diag_test,
    '''        assertEquals("status=WAIT;phase=send_continue;reason=input_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_SEND_CONTINUE, "WAIT", "continuation 입력 대기"));
''',
    '''        assertEquals("status=WAIT;phase=send_continue;reason=input_wait",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_SEND_CONTINUE, "WAIT", "continuation 입력 대기"));
        assertEquals("status=UI_WAIT;phase=send_continue;reason=composer_replaced_abort",
                SelfRunWebDiagnostics.waitDetail(SelfRunStore.PHASE_SEND_CONTINUE, "UI_WAIT", "prepared continuation composer replaced · click abort"));
'''
)
replace_once(
    diag_test,
    '    @Test public void conversationSyncDiagnosticsContainOnlySafeCategories() {',
    '''    @Test public void staleCallbackAbortContainsOnlySafeCategories() {
        String detail = SelfRunWebDiagnostics.abortDetail("stale_callback", false, false, false);
        assertEquals("abort=stale_callback;webview_match=0;generation_match=0;freshness_match=0", detail);
        assertFalse(detail.contains("chatgpt.com"));
        assertFalse(detail.contains("conversation"));
        assertFalse(detail.contains("continue"));
    }

    @Test public void conversationSyncDiagnosticsContainOnlySafeCategories() {'''
)

replace_once(
    fresh_test,
    '        assertTrue(d.contains("pagehide"));assertTrue(d.contains("prepared continuation belongs to stale generation"));',
    '        assertTrue(d.contains("pagehide"));assertTrue(d.contains("prepared continuation belongs to stale generation"));assertTrue(d.contains("__selfRunDrivePreparedContinuation=null"));'
)
helper = '    private static int count(String s,String token)'
extra = '''    @Test public void replacedPreparedComposerCannotSubmitEvenWhenTextMatches() {
        String prepare=SelfRunDom.prepareDriveTurn("https://chatgpt.com/c/conversation123","continue","m","1:2");
        String click=SelfRunDom.clickPreparedDriveTurn("https://chatgpt.com/c/conversation123","continue","m","1:2");
        assertTrue(prepare.contains("window.__selfRunDrivePreparedContinuation={markerKey:markerKey2,composer,freshnessToken:__srFreshnessToken,clicked:false}"));
        assertTrue(click.contains("prepared.composer!==composer"));
        assertTrue(click.contains("prepared.clicked"));
        assertTrue(click.contains("SUBMISSION_PENDING"));
        assertTrue(click.indexOf("prepared.composer!==composer")<click.indexOf("send.click()"));
    }
    @Test public void staleContinuationCallbackHasPrivacySafeAbortDiagnostic() throws Exception {
        String s=src("SelfRunService.java");
        assertTrue(s.contains("CONTINUE_SUBMIT_ABORT"));
        assertTrue(s.contains("SelfRunWebDiagnostics.abortDetail(\\\"stale_callback\\\""));
        String detail=SelfRunWebDiagnostics.abortDetail("stale_callback",false,false,false);
        assertEquals("abort=stale_callback;webview_match=0;generation_match=0;freshness_match=0",detail);
        assertFalse(detail.contains("chatgpt.com"));
        assertFalse(detail.contains("conversation123"));
    }
'''
text = Path(fresh_test).read_text(encoding='utf-8')
if text.count(helper) != 1:
    raise SystemExit('freshness helper anchor mismatch')
Path(fresh_test).write_text(text.replace(helper, extra + helper, 1), encoding='utf-8')

# Fail closed if any prohibited assistant-DOM completion authority reappears.
service_text = Path(service).read_text(encoding='utf-8')
dom_text = Path(dom).read_text(encoding='utf-8')
diag_text = Path(diag).read_text(encoding='utf-8')
if any(token in service_text for token in ('observeAssistant', 'PHASE_READ_NEXT_CONTROL', 'WAIT_ASSISTANT')):
    raise SystemExit('assistant DOM completion authority must remain absent')
if 'window.__selfRunDrivePreparedContinuation={markerKey:markerKey2,composer' not in dom_text:
    raise SystemExit('prepare-time composer identity not retained')
if 'prepared.composer!==composer' not in dom_text or 'prepared.clicked=true;send.click()' not in dom_text:
    raise SystemExit('prepared composer identity/click-once guard missing')
if 'composer_replaced_abort' not in diag_text:
    raise SystemExit('composer replacement privacy-safe diagnostic missing')
if 'static String abortDetail' not in diag_text or '"stale_callback".equals(reason)' not in diag_text or 'return "abort=" + safeReason' not in diag_text:
    raise SystemExit('stale callback privacy-safe diagnostic missing')
