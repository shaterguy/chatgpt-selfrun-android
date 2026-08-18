from pathlib import Path

legacy_workflow = Path('.github/workflows/bootstrap-drive-dev3.yml')
workflow_text = legacy_workflow.read_text(encoding='utf-8')
begin_marker = "          python3 - <<'PY'\n"
end_marker = "\n          PY\n"
start = workflow_text.index(begin_marker) + len(begin_marker)
end = workflow_text.index(end_marker, start)
segment = workflow_text[start:end]
patch_code = "\n".join(line[10:] if line.startswith("          ") else line for line in segment.splitlines())
namespace = {'__name__': '__dev3_patch__'}
exec(compile(patch_code, 'bootstrap-drive-dev3.yml::<patch>', 'exec'), namespace, namespace)


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one corrective replacement, found {count}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


service = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java'
dom = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java'

replace_once(
    service,
    '    private long conversationVisualRequestId;\n    private int activeConversationSyncGeneration = -1;',
    '    private long conversationVisualRequestId;\n    private long activeConversationVisualRequestId;\n    private int activeConversationSyncGeneration = -1;'
)
replace_once(
    service,
    'private void invalidateConversationFreshness(){conversationFreshnessToken="";conversationFreshnessGeneration=-1;}',
    'private void invalidateConversationFreshness(){conversationFreshnessToken="";conversationFreshnessGeneration=-1;activeConversationVisualRequestId=0L;}'
)

old_visual = '''private void requestConversationVisualReady(WebView view,long sync,int expectedGeneration){long requestId=++conversationVisualRequestId;try{view.postVisualStateCallback(requestId,new WebView.VisualStateCallback(){@Override public void onComplete(long completedRequestId){if(completedRequestId!=requestId)return;onConversationVisualReady(view,sync,expectedGeneration,false);}});}catch(Throwable unsupported){handler.postDelayed(()->onConversationVisualReady(view,sync,expectedGeneration,true),250L);}}
private void onConversationVisualReady(WebView view,long sync,int expectedGeneration,boolean fallback){if(view!=webView||!conversationSyncInFlight||sync!=activeConversationSyncEpoch||expectedGeneration!=generation||!SelfRunStore.PHASE_SYNC_CONVERSATION.equals(store.phase())){runLog.record(store,"CONVERSATION_SYNC_DISCARDED",SelfRunWebDiagnostics.syncDetail(sync,generation,false,activeConversationSyncNavigation,-1,false,false,false,true));return;}boolean canonicalMatch=sameConversation(canonicalUrl(),view.getUrl());runLog.record(store,"CONVERSATION_SYNC_VISUAL_READY",SelfRunWebDiagnostics.syncDetail(sync,generation,canonicalMatch,activeConversationSyncNavigation,-1,false,false,true,false)+(fallback?";fallback=1":";fallback=0"));if(!canonicalMatch){handleConversationSyncPageFinished(view,view.getUrl());return;}evaluateConversationSyncReadiness(view,sync,expectedGeneration);}'''

new_visual = '''private void requestConversationVisualReady(WebView view,long sync,int expectedGeneration){
    long requestId=++conversationVisualRequestId;
    activeConversationVisualRequestId=requestId;
    handler.postDelayed(()->onConversationVisualReady(view,sync,expectedGeneration,true,requestId),1500L);
    try{
        view.postVisualStateCallback(requestId,new WebView.VisualStateCallback(){
            @Override public void onComplete(long completedRequestId){
                if(completedRequestId!=requestId)return;
                onConversationVisualReady(view,sync,expectedGeneration,false,requestId);
            }
        });
    }catch(Throwable unsupported){
        handler.postDelayed(()->onConversationVisualReady(view,sync,expectedGeneration,true,requestId),250L);
    }
}
private void onConversationVisualReady(WebView view,long sync,int expectedGeneration,boolean fallback,long requestId){
    if(requestId!=activeConversationVisualRequestId){
        runLog.record(store,"CONVERSATION_SYNC_DISCARDED",SelfRunWebDiagnostics.syncDetail(sync,generation,false,activeConversationSyncNavigation,-1,false,false,false,true)+";visual_request_match=0");
        return;
    }
    activeConversationVisualRequestId=0L;
    if(view!=webView||!conversationSyncInFlight||sync!=activeConversationSyncEpoch||expectedGeneration!=generation||!SelfRunStore.PHASE_SYNC_CONVERSATION.equals(store.phase())){
        runLog.record(store,"CONVERSATION_SYNC_DISCARDED",SelfRunWebDiagnostics.syncDetail(sync,generation,false,activeConversationSyncNavigation,-1,false,false,false,true)+";visual_request_match=1");
        return;
    }
    boolean canonicalMatch=sameConversation(canonicalUrl(),view.getUrl());
    runLog.record(store,"CONVERSATION_SYNC_VISUAL_READY",SelfRunWebDiagnostics.syncDetail(sync,generation,canonicalMatch,activeConversationSyncNavigation,-1,false,false,true,false)+(fallback?";fallback=1":";fallback=0"));
    if(!canonicalMatch){handleConversationSyncPageFinished(view,view.getUrl());return;}
    evaluateConversationSyncReadiness(view,sync,expectedGeneration);
}'''
replace_once(service, old_visual, new_visual)

replace_once(
    service,
    'conversationSyncInFlight=false;runLog.record(store,"CONVERSATION_SYNC_DISCARDED",SelfRunWebDiagnostics.syncDetail(sync,generation,false,activeConversationSyncNavigation,-1,false,false,false,true));handler.postDelayed(this::ensureWebView,1500L);return;}requestConversationVisualReady',
    'conversationSyncInFlight=false;runLog.record(store,"CONVERSATION_SYNC_DISCARDED",SelfRunWebDiagnostics.syncDetail(sync,generation,false,activeConversationSyncNavigation,-1,false,false,false,true));enterPreservedPause("CONVERSATION_SYNC_ROUTE_MISMATCH","conversation freshness 대상 경로 확인 실패 · 사용자 조치 대기",false);NotificationHelper.notifyUser(this,"확인 필요",store.status());return;}requestConversationVisualReady'
)
replace_once(
    service,
    'if("TARGET_ERROR".equals(status)){if(!conversationSyncRecoveryLoadUsed){conversationSyncRecoveryLoadUsed=true;activeConversationSyncNavigation="loadUrl_recovery";view.loadUrl(canonicalUrl());return;}conversationSyncInFlight=false;handler.postDelayed(this::ensureWebView,1500L);return;}',
    'if("TARGET_ERROR".equals(status)){if(!conversationSyncRecoveryLoadUsed){conversationSyncRecoveryLoadUsed=true;activeConversationSyncNavigation="loadUrl_recovery";view.loadUrl(canonicalUrl());return;}conversationSyncInFlight=false;enterPreservedPause("CONVERSATION_SYNC_TARGET_ERROR","conversation freshness 대상 확인 실패 · 사용자 조치 대기",false);NotificationHelper.notifyUser(this,"확인 필요",store.status());return;}'
)
replace_once(
    service,
    'if(!sameConversation(canonicalUrl(),view.getUrl())){conversationSyncInFlight=false;handler.post(this::ensureWebView);return;}',
    'if(!sameConversation(canonicalUrl(),view.getUrl())){conversationSyncInFlight=false;enterPreservedPause("CONVERSATION_SYNC_ROUTE_CHANGED","conversation freshness 확인 중 대상 경로 변경 · 사용자 조치 대기",false);NotificationHelper.notifyUser(this,"확인 필요",store.status());return;}'
)

dom_path = Path(dom)
dom_text = dom_path.read_text(encoding='utf-8')
edit_start = dom_text.index('const __srEditContext=e=>{')
edit_end = dom_text.index('};const __srComposerScope=e=>', edit_start) + 2
new_edit = "const __srEditContext=e=>{for(let n=e;n;n=n.parentElement){const tid=String(n.dataset?.testid||'').toLowerCase(),cls=String(n.className||'').toLowerCase(),meta=String((n.getAttribute?.('aria-label')||'')+' '+(n.getAttribute?.('title')||'')).toLowerCase(),role=String(n.getAttribute?.('role')||'').toLowerCase();if(role==='dialog'||tid==='edit'||tid.startsWith('edit-')||tid.includes('message-edit')||tid.includes('edit-message')||tid.includes('composer-edit')||tid.includes('edit-composer')||cls.includes('message-edit')||cls.includes('edit-message')||cls.includes('composer-edit')||cls.includes('edit-composer')||meta.includes('edit message')||meta.includes('수정')||meta.includes('편집'))return true;}return false;};"
dom_path.write_text(dom_text[:edit_start] + new_edit + dom_text[edit_end:], encoding='utf-8')


test_path = Path('app/src/test/java/com/shaterguy/chatgptselfrun/ConversationFreshnessBarrierTest.java')
test_text = test_path.read_text(encoding='utf-8')
helper_anchor = '    private static int count(String s,String token)'
extra_tests = '''    @Test public void visualReadyCallbackAndTimeoutHaveSingleWinner() throws Exception {
        String s=src("SelfRunService.java");
        assertTrue(s.contains("activeConversationVisualRequestId"));
        assertTrue(s.contains("handler.postDelayed(()->onConversationVisualReady(view,sync,expectedGeneration,true,requestId),1500L)"));
        assertTrue(s.contains("if(requestId!=activeConversationVisualRequestId)"));
        assertTrue(s.contains("activeConversationVisualRequestId=0L"));
    }
    @Test public void editComposerNestedUnderGenericFormStillFailsClosed() {
        String script=SelfRunDom.prepareDriveTurn("https://chatgpt.com/c/conversation123","continue","m","1:2");
        assertTrue(script.contains("for(let n=e;n;n=n.parentElement)"));
        assertTrue(script.contains("role==='dialog'"));
        assertTrue(script.contains("message-edit"));
        assertFalse(script.contains("e.closest('[data-testid*=\\\"edit\\\"],[data-testid*=\\\"message-edit\\\"],[role=\\\"dialog\\\"],form')"));
    }
'''
if test_text.count(helper_anchor) != 1:
    raise SystemExit('ConversationFreshnessBarrierTest helper anchor mismatch')
test_path.write_text(test_text.replace(helper_anchor, extra_tests + helper_anchor, 1), encoding='utf-8')

test_text = test_path.read_text(encoding='utf-8')
old_repeat = '''        assertTrue(s.contains("sync!=activeConversationSyncEpoch"));assertTrue(s.contains("expectedGeneration!=generation"));assertTrue(s.contains("conversationSyncInFlight"));assertTrue(s.contains("CONVERSATION_SYNC_DISCARDED"));'''
new_repeat = '''        assertTrue(s.contains("sync!=activeConversationSyncEpoch"));assertTrue(s.contains("expectedGeneration!=generation"));assertTrue(s.contains("conversationSyncInFlight"));assertTrue(s.contains("CONVERSATION_SYNC_DISCARDED"));
        assertTrue(s.contains("requestId!=activeConversationVisualRequestId"));assertTrue(s.contains("activeConversationVisualRequestId=0L"));'''
if test_text.count(old_repeat) != 1:
    raise SystemExit('repeatedRefreshCallbacksCannotDoubleSubmit assertion anchor mismatch')
test_path.write_text(test_text.replace(old_repeat, new_repeat, 1), encoding='utf-8')

service_text = Path(service).read_text(encoding='utf-8')
dom_text = Path(dom).read_text(encoding='utf-8')
if 'observeAssistant' in service_text or 'PHASE_READ_NEXT_CONTROL' in service_text or 'WAIT_ASSISTANT' in service_text:
    raise SystemExit('assistant DOM completion control must remain absent')
if 'PHASE_SYNC_CONVERSATION' not in service_text or 'CONVERSATION_SYNC_READY' not in service_text:
    raise SystemExit('conversation freshness barrier missing')
if 'webView.reload()' not in service_text or 'webView.loadUrl(canonical)' not in service_text:
    raise SystemExit('canonical reload/loadUrl contract missing')
if 'for(let n=e;n;n=n.parentElement)' not in dom_text or 'scope.contains(calibrated)' not in dom_text:
    raise SystemExit('composer fail-closed/current-scope contract missing')
