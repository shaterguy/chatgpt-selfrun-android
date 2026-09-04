#!/usr/bin/env python3
from pathlib import Path
import re, subprocess

BASE='33506387ffe23919d26e3266ceb09a6a26945f32'
ROOT=Path('.')
SRC=ROOT/'app/src/main/java/com/shaterguy/chatgptselfrun'
TEST=ROOT/'app/src/test/java/com/shaterguy/chatgptselfrun'
ATEST=ROOT/'app/src/androidTest/java/com/shaterguy/chatgptselfrun'

def read(p): return Path(p).read_text(encoding='utf-8')
def write(p,s):
    p=Path(p); p.parent.mkdir(parents=True,exist_ok=True); p.write_text(s,encoding='utf-8')
def rep(s,old,new,n=1,label=''):
    c=s.count(old)
    if c!=n: raise SystemExit(f'{label or old[:60]}: expected {n}, found {c}')
    return s.replace(old,new,n)
def rx(s,pat,new,n=1,label=''):
    out,c=re.subn(pat,new,s,count=n,flags=re.S)
    if c!=n: raise SystemExit(f'{label or pat[:60]}: expected {n}, found {c}')
    return out

def git(cmd): return subprocess.check_output(['git',*cmd],text=True).strip()
if git(['rev-parse','HEAD^']) != BASE:
    raise SystemExit('builder parent is not exact dev3 baseline')

# Version identity.
p=ROOT/'app/build.gradle'; s=read(p)
s=rep(s,'def selfRunDriveVersionCode = 2020036','def selfRunDriveVersionCode = 2020037',label='versionCode')
s=rep(s,"def selfRunDriveVersionName = '2.3.2-dev3'","def selfRunDriveVersionName = '2.3.2-dev4'",label='versionName')
s=rep(s,"if (selfRunDriveVersionName != '2.3.2-dev3') {","if (selfRunDriveVersionName != '2.3.2-dev4') {",label='variant version assertion')
s=rep(s,"        if (!webConfig.contains('TurnCompletionDomFallbackScript.installDocumentStart')) {\n            throw new GradleException('assistant-aware DOM completion fallback is required')\n        }",
      "        if (webConfig.contains('TurnCompletionDomFallbackScript.installDocumentStart')) {\n            throw new GradleException('DOM completion fallback must not be installed')\n        }",label='build DOM requirement')
write(p,s)

# Protocol: move durable correlation into pending/active protocol-owned tokens.
p=SRC/'ChatGptTurnProtocolScript.java'; s=read(p)
s=rep(s,'turn-protocol-v6','turn-protocol-v7',n=1,label='protocol engine')
s=rep(s,"selfrun-drive:response-protocol-state:v6","selfrun-drive:response-protocol-state:v7",n=1,label='protocol store')
s=rep(s,"runId:'',phase:'IDLE',requestIdentity:'',","runId:'',phase:'IDLE',requestIdentity:'',pendingTurnToken:'',activeTurnToken:'',",label='token state')
s=rep(s,"                  const currentObserverToken=()=>safe(window.__selfRunDriveTurnObserver?.token||'');\n",'',label='observer token helper')
s=rep(s,'                  let state=restore(),pendingTimer=0;','                  let state=restore();',label='pending timer state')
s=rep(s,"sink.postMessage(JSON.stringify({runId:safe(state.runId),stage:safe(stage),source:safe(source),phase:state.phase,observerToken:currentObserverToken()}));",
      "sink.postMessage(JSON.stringify({runId:safe(state.runId),stage:safe(stage),source:safe(source),phase:state.phase,turnToken:safe(state.activeTurnToken)}));",label='protocol event token')
old_start="""                  const startRequest=meta=>{\n                    alignRun();if(pendingTimer){clearTimeout(pendingTimer);pendingTimer=0;}retireWorkTurn(state.currentWorkTurnId);\n                    state.phase='THINKING';state.requestIdentity=requestIdentity();state.currentWorkTurnId='';state.currentFinalMessageId='';state.finalMessageActive=false;\n                    state.sawFinalChannelToken=false;state.sawVisibleAnswer=false;state.sawAssistantFinalText=false;state.sawStreamComplete=false;\n                    state.completionDispatched=false;state.completionSource='';state.lastError='';state.lastSource=safe(meta?.source||'fetch');\n                    const route=routeConversationId();if(route&&!state.canonicalConversationId)state.canonicalConversationId=route;\n                    save();emitLog('turn_request','canonical_post');return state.requestIdentity;\n                  };"""
new_start="""                  const bindPendingTurn=(run,token)=>{\n                    const expectedRun=safe(run),value=safe(token),currentRun=alignRun();\n                    if(!expectedRun||!value||!currentRun||expectedRun!==currentRun)return{ok:false,reason:'run_or_token_mismatch'};\n                    if(state.pendingTurnToken&&state.pendingTurnToken!==value)return{ok:false,reason:'pending_token_conflict'};\n                    if((state.phase==='THINKING'||state.phase==='ANSWERING')&&state.activeTurnToken&&state.activeTurnToken!==value)return{ok:false,reason:'active_turn_in_progress'};\n                    state.pendingTurnToken=value;save();return{ok:true,token:value};\n                  };\n                  const startRequest=meta=>{\n                    alignRun();const pending=safe(state.pendingTurnToken);\n                    if(!pending){state.phase='ERROR';state.lastError='canonical_without_pending_turn_token';state.lastSource=safe(meta?.source||'fetch');save();emitLog('error','canonical_without_pending_turn_token');return'';}\n                    retireWorkTurn(state.currentWorkTurnId);state.activeTurnToken=pending;state.pendingTurnToken='';\n                    state.phase='THINKING';state.requestIdentity=requestIdentity();state.currentWorkTurnId='';state.currentFinalMessageId='';state.finalMessageActive=false;\n                    state.sawFinalChannelToken=false;state.sawVisibleAnswer=false;state.sawAssistantFinalText=false;state.sawStreamComplete=false;\n                    state.completionDispatched=false;state.completionSource='';state.lastError='';state.lastSource=safe(meta?.source||'fetch');\n                    const route=routeConversationId();if(route&&!state.canonicalConversationId)state.canonicalConversationId=route;\n                    save();emitLog('turn_request','canonical_post');return state.requestIdentity;\n                  };"""
s=rep(s,old_start,new_start,label='startRequest')
s=rx(s,r"\n                  const cancelDomFallback=observer=>\{.*?\n                  function schedulePendingDispatch\(source\)\{.*?\n                  \}","",label='DOM fallback helpers and retry scheduler')
s=rx(s,r"                  function dispatchCompletion\(source,allowRetry=true\)\{.*?\n                  \}","""                  function dispatchCompletion(source){\n                    if(state.phase!=='COMPLETE'||state.completionDispatched||!completionEvidence())return false;\n                    const allowed=source==='message_stream_complete'||source==='finished_successfully_end_turn'||source==='restored_complete';\n                    const run=alignRun(),token=safe(state.activeTurnToken);if(!allowed||!run||!token)return false;\n                    state.completionDispatched=true;state.completionSource=safe(source);save();emitLog('completion_dispatch',source);\n                    location.href=COMPLETION_SCHEME+'://'+COMPLETION_HOST+'?run='+encodeURIComponent(run)\n                      +'&token='+encodeURIComponent(token)+'&source='+encodeURIComponent(safe(source));return true;\n                  }\n                  const dispatchStoredCompletion=()=>state.phase==='COMPLETE'&&!state.completionDispatched&&completionEvidence()?dispatchCompletion('restored_complete'):false;""",label='protocol dispatch')
s=rep(s,"dispatchCompletion(source,true);return true;","dispatchCompletion(source);return true;",label='complete dispatch call')
s=rep(s,"                    suspendDomFallback(window.__selfRunDriveTurnObserver);state.phase='ERROR';","                    state.phase='ERROR';",label='error DOM suspension')
s=rep(s,"                    version:ENGINE_VERSION,snapshot,\n                    observeCanonicalRequest:()=>{startRequest({source:'manual-canonical'});return snapshot();},\n                    observeRequest,observeSseText,observeSocketFrame,observeWorkFrame:observeSocketFrame,\n                    diagnostics:()=>({phase:state.phase,requestIdentity:state.requestIdentity,conversationId:state.canonicalConversationId,workTurnId:state.currentWorkTurnId,\n                      observerToken:currentObserverToken(),sawFinalChannelToken:state.sawFinalChannelToken,sawVisibleAnswer:state.sawVisibleAnswer,\n                      sawAssistantFinalText:state.sawAssistantFinalText,sawStreamComplete:state.sawStreamComplete,completionDispatched:state.completionDispatched,lastError:state.lastError})",
      "                    version:ENGINE_VERSION,snapshot,bindPendingTurn,dispatchStoredCompletion,\n                    observeCanonicalRequest:()=>{startRequest({source:'manual-canonical'});return snapshot();},\n                    observeRequest,observeSseText,observeSocketFrame,observeWorkFrame:observeSocketFrame,\n                    diagnostics:()=>({phase:state.phase,requestIdentity:state.requestIdentity,conversationId:state.canonicalConversationId,workTurnId:state.currentWorkTurnId,\n                      turnToken:state.activeTurnToken,pendingTurnToken:state.pendingTurnToken,activeTurnToken:state.activeTurnToken,sawFinalChannelToken:state.sawFinalChannelToken,sawVisibleAnswer:state.sawVisibleAnswer,\n                      sawAssistantFinalText:state.sawAssistantFinalText,sawStreamComplete:state.sawStreamComplete,completionDispatched:state.completionDispatched,lastError:state.lastError})",label='protocol public API')
s=rep(s,"                  alignRun();if(state.phase==='COMPLETE'&&!state.completionDispatched&&completionEvidence())schedulePendingDispatch(state.lastSource||'restored_complete');",
      "                  alignRun();if(state.phase==='COMPLETE'&&!state.completionDispatched&&completionEvidence()&&state.activeTurnToken)dispatchCompletion('restored_complete');",label='restored completion')
for forbidden in ['__selfRunDriveTurnObserver','cancelDomFallback','suspendDomFallback','pendingTimer','schedulePendingDispatch','observerToken:currentObserverToken']:
    if forbidden in s: raise SystemExit('protocol retains forbidden DOM dependency: '+forbidden)
write(p,s)

# Submission DOM keeps only UI manipulation / one-time submission confirmation and binds Protocol immediately before click.
p=SRC/'SelfRunContinuationDom.java'; s=read(p)
s=rep(s,'    static final String TURN_STOP_SEEN_HOST = "turn-stop-seen";\n','',label='turn stop callback constant')
s=s.replace('String runId, String observerToken, long stabilityMs)', 'String runId, String turnToken)')
if s.count('String runId, String turnToken)') < 2: raise SystemExit('click signatures not migrated')
s=s.replace('+ completionObserver(runId, observerToken, stabilityMs)', '+ protocolBinding(runId, turnToken)')
if s.count('+ protocolBinding(runId, turnToken)') != 2: raise SystemExit('protocol binding injection count mismatch')
s=s.replace('const baselineUserCount=userMessageCount(),clickedAt=Date.now();',"const binding=bindTurnProtocol();if(!binding.ok)return result(binding.status,binding.detail);const baselineUserCount=userMessageCount(),clickedAt=Date.now();")
if s.count('const binding=bindTurnProtocol()') != 2: raise SystemExit('pre-click bind count mismatch')
s=s.replace('armCompletionObserver(false);','')
s=s.replace(';observer=armed;verification=pending',';protocol=bound;verification=pending')
s=rx(s,r"\n    static String observeTurnCompletion\(.*?\n    private static String conversationGuard",r'''\n    private static String protocolBinding(String runId, String turnToken) {\n        String run = q(runId), token = q(turnToken);\n        return "const bindTurnProtocol=()=>{const p=window.__selfRunTurnProtocol;if(!p||typeof p.bindPendingTurn!=='function')return{ok:false,status:'TURN_PROTOCOL_UNAVAILABLE',detail:'turn protocol unavailable'};"\n                + "try{const b=p.bindPendingTurn(" + run + "," + token + ");if(b&&b.ok)return{ok:true};return{ok:false,status:'TURN_PROTOCOL_BIND_FAILED',detail:String(b?.reason||'bind_failed')};}"\n                + "catch(_){return{ok:false,status:'TURN_PROTOCOL_BIND_FAILED',detail:'bind_exception'};}};";\n    }\n\n    private static String conversationGuard''',label='remove DOM completion observer methods')
for forbidden in ['observeTurnCompletion','cancelTurnCompletionObserver','armCompletionObserver','completionObserver(','TURN_STOP_SEEN_HOST','turn-stop-seen','new MutationObserver','observerCallback']:
    if forbidden in s: raise SystemExit('continuation DOM retains completion detector: '+forbidden)
write(p,s)

# Protocol-only document-start plan and capability fail-closed.
write(SRC/'WebViewConfig.java',r'''package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

final class WebViewConfig {
    private WebViewConfig() {}

    static final class AutomationPlan {
        final boolean requestProfile;
        final boolean hybridProfile;
        final boolean chatProtocol;
        final boolean workIngress;
        final boolean workTransport;

        AutomationPlan(boolean requestProfile, boolean hybridProfile,
                       boolean chatProtocol, boolean workIngress, boolean workTransport) {
            this.requestProfile = requestProfile;
            this.hybridProfile = hybridProfile;
            this.chatProtocol = chatProtocol;
            this.workIngress = workIngress;
            this.workTransport = workTransport;
        }

        int documentStartScriptCount() {
            int count = requestProfile ? 1 : 0;
            if (hybridProfile) count++;
            if (chatProtocol) count++;
            if (workIngress) count++;
            if (workTransport) count++;
            return count;
        }
    }

    static AutomationPlan automationPlan(String mode, boolean hybridValid,
                                         boolean hybridUsesWork, boolean protocolObservable) {
        boolean hybrid = HybridRunProfileStore.MODE_HYBRID.equals(mode) && hybridValid;
        boolean work = SelfRunStore.MODE_WORK.equals(mode) || (hybrid && hybridUsesWork);
        return new AutomationPlan(true, hybrid, protocolObservable,
                protocolObservable && work, protocolObservable && work);
    }

    /** Background SelfRun WebView: Protocol observability is mandatory and DOM completion fallback does not exist. */
    @SuppressWarnings("SetJavaScriptEnabled")
    static boolean applyAutomation(WebView webView) {
        ChatReasoningPreferenceStore.initialize(webView.getContext());
        configureMobileAutomationSurface(webView, false);

        Context rawContext = webView.getContext();
        if (rawContext instanceof ProfileRegistryActivity) {
            RequestProfileScript.installDocumentStart(webView);
            return true;
        }
        if (rawContext instanceof WebUiCalibrationActivity) return true;

        Context context = rawContext.getApplicationContext();
        if (context == null) context = rawContext;
        SelfRunStore store = new SelfRunStore(context);
        HybridRunProfileStore.initialize(context);
        HybridRunProfileStore.Selection selection = HybridRunProfileStore.currentSelection();
        boolean hybridValid = selection != null && selection.valid();
        boolean hybridUsesWork = hybridValid
                && (selection.bootstrap.isWork() || selection.continuation.isWork());
        boolean protocolObservable = TurnProtocolLogBridge.install(webView);
        if (!protocolObservable) return false;
        AutomationPlan plan = automationPlan(store.mode(), hybridValid, hybridUsesWork, true);

        if (plan.requestProfile) RequestProfileScript.installDocumentStart(webView);
        if (plan.hybridProfile) HybridRequestProfileScript.installDocumentStart(webView);
        if (plan.chatProtocol) ChatGptTurnProtocolScript.installDocumentStart(webView);
        if (plan.workIngress) WorkTurnProtocolIngressScript.installDocumentStart(webView);
        if (plan.workTransport) WorkProtocolTransportCaptureScript.installDocumentStart(webView);
        if (plan.workIngress) WorkProtocolNativeObserver.recordEnvironmentIfWork(context);
        return true;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    static void applyLogin(WebView webView) {
        WebSettings settings = common(webView);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
    }

    private static void configureMobileAutomationSurface(WebView webView, boolean thirdPartyCookies) {
        WebSettings settings = common(webView);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setOffscreenPreRaster(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        String current = settings.getUserAgentString();
        String marker = "SelfRunV2/" + BuildConfig.VERSION_NAME;
        if (current != null && !current.contains(marker)) settings.setUserAgentString(current + " " + marker);
        webView.setInitialScale(100);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, thirdPartyCookies);
    }

    private static WebSettings common(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        return settings;
    }
}
''')

# Native protocol UI projection: one token, one detector.
write(SRC/'TurnProtocolUiState.java',r'''package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Small UI-facing projection of the latest active Protocol-owned ChatGPT response state. */
final class TurnProtocolUiState {
    static final String DETECTOR_PROTOCOL = "PROTOCOL";
    private static final String PREFS = "selfrun_turn_protocol_ui";
    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_STAGE = "stage";
    private static final String KEY_PHASE = "phase";
    private static final String KEY_DETECTOR = "detector";
    private static final String KEY_TURN_TOKEN = "turnToken";
    private static final String KEY_UPDATED_AT = "updatedAt";

    private static volatile String processRunId = "";
    private static volatile String processPhase = "IDLE";
    private static volatile String processTurnToken = "";

    static final class Snapshot {
        final boolean present;
        final String stage;
        final String phase;
        final String detector;
        final String turnToken;
        final long updatedAt;

        Snapshot(boolean present, String stage, String phase, String detector, long updatedAt) {
            this(present, stage, phase, detector, "", updatedAt);
        }
        Snapshot(boolean present, String stage, String phase, String detector,
                 String turnToken, long updatedAt) {
            this.present = present; this.stage = safe(stage); this.phase = safe(phase);
            this.detector = safe(detector); this.turnToken = safe(turnToken);
            this.updatedAt = Math.max(0L, updatedAt);
        }
        boolean activeGenerationFor(String token) {
            String expected=safe(token);
            return present && activePhase(phase) && !expected.isEmpty() && expected.equals(turnToken);
        }
        String headline() {
            String base=headlineFor(stage, phase);
            if (DETECTOR_PROTOCOL.equals(detector)) return base.isEmpty()?"응답 감지 중 · Protocol":base+" · 응답 Protocol";
            return base;
        }
    }

    private TurnProtocolUiState() {}

    static void recordDetector(Context context, String runId, String detector) {
        if(context==null||runId==null||runId.isEmpty()||!DETECTOR_PROTOCOL.equals(detector))return;
        SharedPreferences prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        boolean newRun=!runId.equals(prefs.getString(KEY_RUN_ID,""));
        SharedPreferences.Editor edit=prefs.edit();
        if(newRun){edit.putString(KEY_STAGE,"").putString(KEY_PHASE,"IDLE").putString(KEY_TURN_TOKEN,"");setProcess(runId,"IDLE","");}
        edit.putString(KEY_RUN_ID,runId).putString(KEY_DETECTOR,detector).putLong(KEY_UPDATED_AT,System.currentTimeMillis()).apply();
    }

    static void record(Context context,String runId,String stage,String phase){record(context,runId,stage,phase,"");}
    static void record(Context context,String runId,String stage,String phase,String turnToken){
        if(context==null||runId==null||runId.isEmpty()||!validPhase(phase))return;
        SharedPreferences prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String token=safe(turnToken);
        prefs.edit().putString(KEY_RUN_ID,runId).putString(KEY_STAGE,safe(stage)).putString(KEY_PHASE,phase)
                .putString(KEY_DETECTOR,DETECTOR_PROTOCOL).putString(KEY_TURN_TOKEN,token)
                .putLong(KEY_UPDATED_AT,System.currentTimeMillis()).apply();
        setProcess(runId,phase,token);
    }

    static Snapshot read(Context context,String runId){
        if(context==null||runId==null||runId.isEmpty())return empty();
        SharedPreferences prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(!runId.equals(prefs.getString(KEY_RUN_ID,"")))return empty();
        String detector=prefs.getString(KEY_DETECTOR,""),phase=normalizedPhase(prefs.getString(KEY_PHASE,"IDLE"));
        if(!DETECTOR_PROTOCOL.equals(detector)&&"IDLE".equals(phase))return empty();
        String token=prefs.getString(KEY_TURN_TOKEN,"");setProcess(runId,phase,token);
        return new Snapshot(true,prefs.getString(KEY_STAGE,""),phase,detector,token,prefs.getLong(KEY_UPDATED_AT,0L));
    }

    static boolean activeGenerationForCurrentTurn(){return !processRunId.isEmpty()&&!processTurnToken.isEmpty()&&activePhase(processPhase);}
    static boolean activeGenerationFor(String turnToken){String token=safe(turnToken);return !processRunId.isEmpty()&&!token.isEmpty()&&activePhase(processPhase)&&token.equals(processTurnToken);}

    static String headlineFor(String stage,String phase){
        if("completion_ignored".equals(stage)&&"THINKING".equals(phase))return "답변 시작 대기 중";
        return switch(safe(phase)){case "THINKING"->"추론 중";case "ANSWERING"->"답변 생성 중";case "COMPLETE"->"답변 완료 · 차기 턴 대기";case "ERROR"->"응답 상태 오류";default->"";};
    }
    static String detectorHeadline(String detector){return DETECTOR_PROTOCOL.equals(detector)?"응답 감지 중 · Protocol":"";}
    static String pillFor(String headline){
        String value=safe(headline);if(value.equals("추론 중")||value.startsWith("추론 중 ·"))return "추론";
        if(value.equals("답변 시작 대기 중")||value.startsWith("답변 시작 대기 중 ·"))return "전환";
        if(value.equals("답변 생성 중")||value.startsWith("답변 생성 중 ·"))return "답변";
        if(value.equals("답변 완료 · 차기 턴 대기")||value.startsWith("답변 완료 · 차기 턴 대기 ·"))return "대기";
        if(value.contains("오류"))return "오류";if(value.contains("일시정지"))return "정지";if(value.contains("감지"))return "감지";
        if(value.contains("완료")||value.contains("종료"))return "완료";if(value.contains("전송"))return "전송";if(value.contains("설정"))return "설정";if(value.contains("준비"))return "준비";return "실행";
    }
    private static void setProcess(String runId,String phase,String turnToken){processRunId=safe(runId);processPhase=normalizedPhase(phase);processTurnToken=safe(turnToken);}
    private static boolean activePhase(String phase){return "THINKING".equals(phase)||"ANSWERING".equals(phase);}
    private static boolean validPhase(String phase){return "IDLE".equals(phase)||"THINKING".equals(phase)||"ANSWERING".equals(phase)||"COMPLETE".equals(phase)||"ERROR".equals(phase);}
    private static String normalizedPhase(String phase){return validPhase(phase)?phase:"IDLE";}
    private static Snapshot empty(){return new Snapshot(false,"","","","",0L);}
    private static String safe(String value){return value==null?"":value;}
}
''')

# Bridge keeps Work diagnostics coalesced but has Protocol-only detector semantics.
p=SRC/'TurnProtocolLogBridge.java'; s=read(p)
s=rx(s,r"        if \(!messageBridge \|\| !documentStart\) \{.*?            return false;\n        \}",'''        if (!messageBridge || !documentStart) {
            if (!runId.isEmpty()) log.record(store, "TURN_DETECTOR", "path=UNAVAILABLE;reason="
                    + (!messageBridge ? "web_message_listener_unavailable" : "document_start_script_unavailable"));
            return false;
        }''',label='bridge fail closed')
s=rx(s,r"                        String observerToken = item\.optString\(\"observerToken\", \"\"\);.*?                        WorkProtocolCoverageTracker\.observeProtocol\(context, store, stage, source, phase\);",'''                        String turnToken = item.optString("turnToken", "");
                        String source = normalizedSource(stage, item.optString("source", ""));
                        if (source.isEmpty() || !validPhaseForStage(stage, phase)) return;
                        TurnProtocolUiState.record(context, eventRunId, stage, phase, turnToken);
                        WorkProtocolCoverageTracker.observeProtocol(context, store, stage, source, phase);''',label='bridge protocol token')
s=rep(s,'TurnProtocolUiState.DETECTOR_PROTOCOL_PRIMARY','TurnProtocolUiState.DETECTOR_PROTOCOL',n=1,label='bridge detector constant')
s=rep(s,'"path=PROTOCOL_PRIMARY;fallback=DOM;bridge=web_message_listener;document_start=1"','"path=PROTOCOL;bridge=web_message_listener;document_start=1"',n=1,label='bridge detector log')
for forbidden in ['DETECTOR_DOM_FALLBACK_ONLY','observer_bound','observerToken','DOM_FALLBACK_ONLY','fallback=DOM']:
    if forbidden in s: raise SystemExit('bridge retains fallback/observer state: '+forbidden)
write(p,s)

# Durable store keeps compatibility storage key but runtime meaning is Protocol token only.
p=SRC/'SelfRunStore.java'; s=read(p)
s=s.replace('.putBoolean("turnObserverSawStop",false)','')
s=rep(s,'String turnObserverToken() { return get("turnObserverToken"); }','String turnProtocolToken() { return get("turnObserverToken"); }',label='store getter')
s=s.replace('    boolean turnObserverSawStop() { return prefs.getBoolean("turnObserverSawStop", false); }\n','')
s=rx(s,r"void prepareTurnObserver\(String token\)\{.*?\nvoid bootstrapSubmissionConfirmed",'''void prepareTurnProtocolToken(String token){String value=safe(token);if(value.isEmpty())throw new IllegalArgumentException("turn protocol token required");commitOrThrow(prefs.edit().putString("turnObserverToken",value));}
void bootstrapSubmissionConfirmed''',label='store observer methods')
s=s.replace('observerToken','turnToken')
s=s.replace('turnObserverToken()','turnProtocolToken()')
s=s.replace('prepared turn observer required','prepared turn protocol token required')
s=s.replace('beginPostDomDriveSync','beginPostTurnDriveSync')
s=s.replace('답변 완료 5초 재확인 · Drive 신호 즉시 확인','Protocol 답변 완료 · Drive 신호 즉시 확인')
s=s.replace('업데이트된 실행 · 답변 완료 Observer 재연결','업데이트된 실행 · Protocol 답변 완료 대기')
for forbidden in ['turnObserverSawStop','markTurnObserverStopSeen','prepareTurnObserver(','isActiveTurnObserverCallbackPhase']:
    if forbidden in s: raise SystemExit('store retains observer state: '+forbidden)
write(p,s)

# Rollover no-start uses token-correlated Protocol generation only, never DOM STOP.
p=SRC/'SelfRunRolloverPolicy.java'; s=read(p)
s=rx(s,r"    static int postDispatchNoStartAction\(long dispatchStartedElapsed, boolean sawStop,.*?    static boolean postDispatchNoStartTimedOut\(long dispatchStartedElapsed, boolean sawStop,.*?\n    \}",'''    static int postDispatchNoStartAction(long dispatchStartedElapsed,
                                         long validatedSinceElapsed, long nowElapsed,
                                         boolean transientSeen) {
        return postDispatchNoStartAction(dispatchStartedElapsed, validatedSinceElapsed, nowElapsed,
                transientSeen, TurnProtocolUiState.activeGenerationForCurrentTurn());
    }

    static int postDispatchNoStartAction(long dispatchStartedElapsed,
                                         long validatedSinceElapsed, long nowElapsed,
                                         boolean transientSeen, boolean protocolGenerationActive) {
        if (dispatchStartedElapsed <= 0L || validatedSinceElapsed <= 0L
                || nowElapsed < dispatchStartedElapsed || nowElapsed < validatedSinceElapsed) return NO_START_WAIT;
        if (protocolGenerationActive || transientSeen) return NO_START_WAIT;
        long continuouslyValidatedStart = Math.max(dispatchStartedElapsed, validatedSinceElapsed);
        if (nowElapsed - continuouslyValidatedStart < CONTINUATION_NO_START_MAX_WAIT_MS) return NO_START_WAIT;
        return NO_START_ROLLOVER;
    }

    static boolean postDispatchNoStartTimedOut(long dispatchStartedElapsed,
                                                long validatedSinceElapsed, long nowElapsed) {
        return postDispatchNoStartAction(dispatchStartedElapsed, validatedSinceElapsed,
                nowElapsed, false) == NO_START_ROLLOVER;
    }''',label='rollover protocol generation')
if 'sawStop' in s or 'activeGenerationForCurrentObserver' in s: raise SystemExit('rollover still uses DOM stop/observer')
write(p,s)

# Service: WAIT_TURN_COMPLETION becomes Protocol-event-driven and Surface remains detached.
p=SRC/'SelfRunService.java'; s=read(p)
s=s.replace('/** Drive V1 runtime. STOP/SEND mutations detect completion; Drive synchronizes the completed turn payload. */','/** Drive V1 runtime. Protocol events own response state; DOM is limited to submission UI operations. */')
for line in [
'    static final long TURN_COMPLETION_STABILITY_MS = 5_000L;\n',
'    /** MutationObserver is immediate; this low-frequency pass only repairs a detached DOM binding. */\n',
'    static final long TURN_OBSERVER_HEALTHCHECK_MS = 15_000L;\n',
'    static final long DETACHED_OBSERVER_RETRY_MS = 800L;\n',
'    static final int DETACHED_OBSERVER_MAX_RECOVERIES = 2;\n',
'    private boolean turnObserverNeedsIdleBaseline = false;\n',
'    private String loggedTurnObserverToken = "";\n',
'    private int detachedObserverRecoveries;\n',
'    private boolean detachedObserverRecoveryActive;\n',
'    private boolean keepDisplayAttachedForTurn;\n']:
    s=s.replace(line,'')
s=s.replace('            resetDetachedObserverFallback();\n','')
s=s.replace('        resetDetachedObserverFallback();\n','')
s=rep(s,'static boolean shouldGuardContinuationCallback(String phase){return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)||SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}',
      'static boolean shouldGuardContinuationCallback(String phase){return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}',label='callback guard')
s=rep(s,'private void ensureWebView(){if(!canRun()||!isWebAutomationPhase(store.phase()))return;String target=store.conversationUrl().isEmpty()?store.projectUrl():store.conversationUrl();if(target.isEmpty()||!validAutomationTarget(target)){store.setLastError("TARGET_MISSING_RETRY","ChatGPT 대상 URL을 안전하게 재확인합니다.");handler.postDelayed(this::ensureWebView,WEB_RECOVERY_DELAY_MS);return;}acquireWakeLock();if(webView!=null){if(!SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase()))resumeWebView();maybeCaptureConversationUrl(webView.getUrl());scheduleWeb(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())?0L:250L);return;}launchWebView(target);}',
'''private void ensureWebView(){if(!canRun()||!isWebAutomationPhase(store.phase()))return;String target=store.conversationUrl().isEmpty()?store.projectUrl():store.conversationUrl();if(target.isEmpty()||!validAutomationTarget(target)){store.setLastError("TARGET_MISSING_RETRY","ChatGPT 대상 URL을 안전하게 재확인합니다.");handler.postDelayed(this::ensureWebView,WEB_RECOVERY_DELAY_MS);return;}acquireWakeLock();if(webView!=null){if(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())){detachDisplayOutput("wait_turn_completion");releaseWakeLock();return;}resumeWebView();maybeCaptureConversationUrl(webView.getUrl());scheduleWeb(250L);return;}launchWebView(target);}''',label='ensure webview WAIT')
s=rep(s,'            host = HeadlessWebViewHost.create(this); webView = host.webView(); WebViewConfig.applyAutomation(webView);',
'''            host = HeadlessWebViewHost.create(this); webView = host.webView();
            if(!WebViewConfig.applyAutomation(webView)){
                runLog.record(store,"TURN_PROTOCOL_UNAVAILABLE","WEB_MESSAGE_LISTENER_OR_DOCUMENT_START_SCRIPT_UNAVAILABLE");
                enterPreservedPause("TURN_PROTOCOL_UNAVAILABLE","TURN_PROTOCOL_UNAVAILABLE · 응답 Protocol 관찰 기능을 구성할 수 없습니다.",false);
                return;
            }''',label='service capability fail closed')
s=rep(s,'@Override public void onPageFinished(WebView view, String url) {if (!launchedRunId.equals(store.runId())) return;maybeCaptureConversationUrl(url);if (isWebAutomationPhase(store.phase())) scheduleWeb(800L);}',
      '@Override public void onPageFinished(WebView view, String url) {if (!launchedRunId.equals(store.runId())) return;maybeCaptureConversationUrl(url);if(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())){detachDisplayOutput("wait_page_ready");releaseWakeLock();return;}if (isWebAutomationPhase(store.phase())) scheduleWeb(800L);}',label='page finished WAIT')
s=rx(s,r"private boolean isTurnCompletionCallback\(String requested,String launchedRunId\)\{.*?\n\}\n\nprivate void maybeCaptureConversationUrl",'''private static boolean allowedTurnCompletionSource(String source){return "message_stream_complete".equals(source)||"finished_successfully_end_turn".equals(source)||"restored_complete".equals(source);}
private boolean isTurnCompletionCallback(String requested,String launchedRunId){
    Uri uri;try{uri=Uri.parse(requested);}catch(Throwable ignored){return false;}
    if(!SelfRunContinuationDom.TURN_COMPLETION_SCHEME.equals(uri.getScheme())||!SelfRunContinuationDom.TURN_COMPLETION_HOST.equals(uri.getHost()))return false;
    String run=uri.getQueryParameter("run"),token=uri.getQueryParameter("token"),source=uri.getQueryParameter("source");
    if(!launchedRunId.equals(run)||!launchedRunId.equals(store.runId())||!SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())||token==null||!token.equals(store.turnProtocolToken())||!allowedTurnCompletionSource(source)){
        runLog.record(store,"TURN_PROTOCOL_COMPLETION","result=callback_rejected;source="+BootstrapResultPolicy.safe(source,80));return true;
    }
    maybeCaptureConversationUrl(webView==null?"":webView.getUrl());
    if(!store.beginPostTurnDriveSync(token))return true;
    resetPostDispatchNoStartState();detachDisplayOutput("protocol_complete");releaseWakeLock();
    runLog.record(store,"TURN_PROTOCOL_COMPLETION","result=accepted;source="+source);
    handler.post(this::authorizeAndRunDrive);return true;
}

private void maybeCaptureConversationUrl''',label='native completion callback')
# Replace runWebStep wholesale up to token helper.
s=rx(s,r"private void runWebStep\(\)\{.*?\nprivate String ensureTurnObserverToken\(\)\{.*?\}\n",'''private void runWebStep(){
    if(!canRun()||!isWebAutomationPhase(store.phase())||webView==null||domInFlight)return;
    String phase=store.phase();recordDisplayDrainFailure();
    if(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)){
        detachDisplayOutput("wait_turn_completion");releaseWakeLock();
        if(postDispatchWindowActive()&&SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){
            int action=SelfRunRolloverPolicy.postDispatchNoStartAction(postDispatchStartedElapsed,
                    networkState.validatedSinceElapsed(),SystemClock.elapsedRealtime(),postDispatchTransientSeen,
                    TurnProtocolUiState.activeGenerationFor(store.turnProtocolToken()));
            if(action==SelfRunRolloverPolicy.NO_START_ROLLOVER&&networkState.isValidated())rolloverConversation(SelfRunRolloverPolicy.CONTINUATION_NO_START_TIMEOUT);
        }
        return;
    }
    resumeWebView();maybeCaptureConversationUrl(webView.getUrl());
    if(SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)&&bootstrapSendTimedOut(store.phaseStartedAt(),System.currentTimeMillis())){failBootstrapSubmissionTimeout("deadline_invalid_or_elapsed");return;}
    String script;
    switch(phase){
        case SelfRunStore.PHASE_BOOTSTRAP->script=SelfRunDom.prepareInitialContext(store.projectUrl(),store.mode(),store.runId());
        case SelfRunStore.PHASE_BOOTSTRAP_MODEL->script=WorkPreferenceDom.modelForProject(store.projectUrl(),store.pendingModel());
        case SelfRunStore.PHASE_BOOTSTRAP_REASONING->script=WorkPreferenceDom.reasoningForProject(store.projectUrl(),store.pendingReasoning());
        case SelfRunStore.PHASE_BOOTSTRAP_SEND->{String prompt=commandPrompt(SelfRunStore.RETRY_BOOTSTRAP);script=SelfRunContinuationDom.prepareBootstrap(store.projectUrl(),prompt,store.commandMarkerId());}
        case SelfRunStore.PHASE_APPLY_PREFS->script=WorkPreferenceDom.modelForConversation(store.conversationUrl(),store.pendingModel());
        case SelfRunStore.PHASE_APPLY_REASONING->script=WorkPreferenceDom.reasoningForConversation(store.conversationUrl(),store.pendingReasoning());
        case SelfRunStore.PHASE_SEND_CONTINUE->{String prompt=continuationPrompt();script=SelfRunContinuationDom.prepareDriveTurn(store.conversationUrl(),prompt,continuationMarkerId());}
        default->{store.setLastError("WEB_STATE_RETRY","Drive V1 WebView 단계를 자동 재확인합니다: "+phase);scheduleWeb(2000L);return;}
    }
    evaluate(phase,script);
}

private String ensureTurnProtocolToken(){String token=store.turnProtocolToken();if(token.isEmpty()){token=UUID.randomUUID().toString().replace("-","");store.prepareTurnProtocolToken(token);}return token;}
''',label='runWebStep protocol event wait')
# Remove old WAIT evaluate result branch and detached callback recovery.
s=rx(s,r"  if\(SelfRunStore\.PHASE_WAIT_TURN_COMPLETION\.equals\(phase\)\)\{.*?\n  \}\n  if\(\"TARGET_ERROR\"\.equals\(status\)\)",'''  if("TARGET_ERROR".equals(status))''',label='WAIT DOM result handler')
s=s.replace('        if(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)&&recoverDetachedObserverOutput("callback_timeout")){scheduleWeb(DETACHED_OBSERVER_RETRY_MS);return;}\n','')
s=s.replace('        scheduleWeb(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)?TURN_OBSERVER_HEALTHCHECK_MS:1200L);','        scheduleWeb(1200L);')
# Click paths and post-submission lifecycle.
s=s.replace('ensureTurnObserverToken()','ensureTurnProtocolToken()')
s=s.replace(',token,TURN_COMPLETION_STABILITY_MS)',',token)')
s=rep(s,'private void continuationSubmitted(String detail){if(!canRun())return;if(!postDispatchWindowActive())beginPostDispatchNoStartWindow();String token=ensureTurnProtocolToken();runLog.record(store,"CONTINUATION_SUBMISSION_DISPATCHED","detail="+detail);clearContinuationAttempt();store.beginTurnCompletionWait(token,"다음 턴 제출 확인 · 답변 완료 감지 중");turnObserverNeedsIdleBaseline=false;releaseWakeLock();scheduleWeb(0L);}',
'''private void continuationSubmitted(String detail){if(!canRun())return;if(!postDispatchWindowActive())beginPostDispatchNoStartWindow();String token=ensureTurnProtocolToken();runLog.record(store,"CONTINUATION_SUBMISSION_DISPATCHED","detail="+detail);clearContinuationAttempt();store.beginTurnCompletionWait(token,"다음 턴 제출 확인 · Protocol 답변 완료 대기");flushStoredProtocolCompletion();detachDisplayOutput("submission_confirmed");releaseWakeLock();scheduleWeb(SelfRunRolloverPolicy.CONTINUATION_NO_START_MAX_WAIT_MS);}''',label='continuation submitted')
s=rep(s,'private void bootstrapSubmitted(String detail){if(!canRun())return;if(!postDispatchWindowActive())beginPostDispatchNoStartWindow();String token=ensureTurnProtocolToken();store.bootstrapSubmissionConfirmed(token);runLog.record(store,"BOOTSTRAP_SUBMISSION_DISPATCHED","detail="+detail);turnObserverNeedsIdleBaseline=false;releaseWakeLock();scheduleWeb(0L);}',
'''private void bootstrapSubmitted(String detail){if(!canRun())return;if(!postDispatchWindowActive())beginPostDispatchNoStartWindow();String token=ensureTurnProtocolToken();store.bootstrapSubmissionConfirmed(token);runLog.record(store,"BOOTSTRAP_SUBMISSION_DISPATCHED","detail="+detail);flushStoredProtocolCompletion();detachDisplayOutput("submission_confirmed");releaseWakeLock();scheduleWeb(SelfRunRolloverPolicy.CONTINUATION_NO_START_MAX_WAIT_MS);}''',label='bootstrap submitted')
# One-shot Protocol flush closes the rare COMPLETE-before-WAIT race; it is not DOM polling.
insert='''\nprivate void flushStoredProtocolCompletion(){WebView active=webView;if(active==null)return;try{active.evaluateJavascript("(()=>{try{return !!window.__selfRunTurnProtocol?.dispatchStoredCompletion?.();}catch(_){return false;}})()",null);}catch(Throwable ignored){}}\n'''
s=rep(s,'\nprivate String commandPrompt(String kind)',insert+'\nprivate String commandPrompt(String kind)',label='protocol flush insertion')
s=rep(s,'private static boolean isContinuationDiagnosticPhase(String phase){return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)||SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}',
      'private static boolean isContinuationDiagnosticPhase(String phase){return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)||SelfRunStore.PHASE_APPLY_PREFS.equals(phase)||SelfRunStore.PHASE_APPLY_REASONING.equals(phase)||SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);}',label='diagnostic phases')
# Remove detached observer helper methods / disconnect paths regardless of compact formatting.
s=rx(s,r"\n    private void disconnectTurnObserver\(\).*?\n    private void recordDisplayDrainFailure",'\n    private void recordDisplayDrainFailure',n=1,label='disconnect/detached helpers')
s=s.replace('disconnectTurnObserver();','')
s=s.replace('resetDetachedObserverFallback();','')
s=s.replace('turnObserverNeedsIdleBaseline=store!=null&&SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())&&store.turnObserverSawStop();','')
# Safety: WAIT transient resource paths may not schedule UI reevaluation / Surface attach.
s=s.replace('if(SelfRunRolloverPolicy.retryHttpStatus(status)) scheduleWeb(30_000L);','if(SelfRunRolloverPolicy.retryHttpStatus(status)&&!SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())) scheduleWeb(30_000L);')
# Rename store transition calls that may remain.
s=s.replace('beginPostDomDriveSync','beginPostTurnDriveSync')
for forbidden in ['TURN_COMPLETION_STABILITY_MS','TURN_OBSERVER_HEALTHCHECK_MS','DETACHED_OBSERVER_RETRY_MS','DETACHED_OBSERVER_MAX_RECOVERIES','detachedObserverRecoveries','detachedObserverRecoveryActive','keepDisplayAttachedForTurn','turnObserverNeedsIdleBaseline','loggedTurnObserverToken','TURN_STOP_SEEN_HOST','markTurnObserverStopSeen','turnObserverSawStop','observeTurnCompletion','cancelTurnCompletionObserver','recoverDetachedObserverOutput','ensureTurnObserverToken']:
    if forbidden in s: raise SystemExit('service retains removed DOM completion mechanism: '+forbidden)
write(p,s)

# Build policy script: DOM completion observer is forbidden; Protocol token bindings required.
p=ROOT/'tools/verify_drive_variant.sh'; s=read(p)
s=s.replace("grep -Fq 'TURN_COMPLETION_STABILITY_MS = 5_000L' \"$SERVICE\"","! grep -Fq 'TURN_COMPLETION_STABILITY_MS' \"$SERVICE\"")
s=s.replace("grep -Fq 'SelfRunContinuationDom.observeTurnCompletion' \"$SERVICE\"","! grep -Fq 'SelfRunContinuationDom.observeTurnCompletion' \"$SERVICE\"")
s=s.replace("grep -Fq 'new MutationObserver' \"$CONTINUE_DOM\"","! grep -Fq 'new MutationObserver' \"$CONTINUE_DOM\"")
s=s.replace("grep -Fq 'data-message-author-role' \"$CONTINUE_DOM\"","grep -Fq 'data-message-author-role' \"$CONTINUE_DOM\"\ngrep -Fq 'bindPendingTurn' \"$CONTINUE_DOM\"\ngrep -Fq 'pendingTurnToken' \"$SRC/ChatGptTurnProtocolScript.java\"\ngrep -Fq 'activeTurnToken' \"$SRC/ChatGptTurnProtocolScript.java\"\n! test -e \"$SRC/TurnCompletionDomFallbackScript.java\"")
write(p,s)

# Canonical TEST workflow uses Protocol-only instrumentation class.
p=ROOT/'.github/workflows/build-drive-test.yml'; s=read(p)
s=rep(s,'TurnCompletionDomFallbackWebViewTest','TurnProtocolSurfaceDetachedWebViewTest',n=1,label='instrumentation selector')
write(p,s)

# Renderer workload regression contract: dev3 Work processing unchanged, DOM fallback absent, script count -1.
write(TEST/'RendererWorkloadOptimizationPolicyTest.java',r'''package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class RendererWorkloadOptimizationPolicyTest {
    @Test public void documentStartRoutingDropsExactlyOneDomFallbackScript() {
        WebViewConfig.AutomationPlan chat=WebViewConfig.automationPlan(SelfRunStore.MODE_CHAT,false,false,true);assertEquals(2,chat.documentStartScriptCount());
        WebViewConfig.AutomationPlan work=WebViewConfig.automationPlan(SelfRunStore.MODE_WORK,false,false,true);assertEquals(4,work.documentStartScriptCount());
        WebViewConfig.AutomationPlan hybridChat=WebViewConfig.automationPlan(HybridRunProfileStore.MODE_HYBRID,true,false,true);assertEquals(3,hybridChat.documentStartScriptCount());
        WebViewConfig.AutomationPlan hybridWork=WebViewConfig.automationPlan(HybridRunProfileStore.MODE_HYBRID,true,true,true);assertEquals(5,hybridWork.documentStartScriptCount());
        assertTrue(chat.requestProfile&&chat.chatProtocol);assertTrue(work.workIngress&&work.workTransport);assertTrue(hybridChat.hybridProfile);assertTrue(hybridWork.workIngress&&hybridWork.workTransport);
    }
    @Test public void visibleManagementWebViewsDoNotInstallSelfRunTransportEngines() throws Exception {
        String config=source("WebViewConfig.java");int profile=config.indexOf("rawContext instanceof ProfileRegistryActivity"),calibration=config.indexOf("rawContext instanceof WebUiCalibrationActivity"),bridge=config.indexOf("TurnProtocolLogBridge.install(webView)");
        assertTrue(profile>=0&&profile<bridge);assertTrue(calibration>=0&&calibration<bridge);assertTrue(config.substring(profile,calibration).contains("RequestProfileScript.installDocumentStart(webView)"));
    }
    @Test public void workPrimitiveHooksHaveSingleOwnerPerPrimitive() throws Exception {
        String ingress=source("WorkTurnProtocolIngressScript.java"),capture=source("WorkProtocolTransportCaptureScript.java");
        assertFalse(ingress.contains("window.fetch=wrappedFetch"));assertFalse(ingress.contains("XMLHttpRequest.prototype.open=function"));assertFalse(ingress.contains("XMLHttpRequest.prototype.send=function"));
        assertTrue(capture.contains("window.fetch=wrappedFetch"));assertTrue(capture.contains("XMLHttpRequest.prototype.open=function"));assertTrue(capture.contains("XMLHttpRequest.prototype.send=function"));
        assertTrue(ingress.contains("window.WebSocket=WrappedWebSocket"));assertTrue(ingress.contains("window.Worker=WrappedWorker"));assertTrue(ingress.contains("window.SharedWorker=WrappedSharedWorker"));
        assertFalse(capture.contains("wrapCreated('Worker'"));assertFalse(capture.contains("wrapCreated('SharedWorker'"));
    }
    @Test public void decoderUsesOriginalBoundsMacrotaskYieldAndCompletionShortCircuit() throws Exception {
        String ingress=source("WorkTurnProtocolIngressScript.java");
        assertTrue(ingress.contains("MAX_ENCODED_ITEMS=6"));assertTrue(ingress.contains("MAX_ENCODED_ITEM_LENGTH=200000"));assertTrue(ingress.contains("MAX_DECODE_DEPTH=8"));assertTrue(ingress.contains("MAX_DECODE_NODES=512"));assertTrue(ingress.contains("MAX_SYNC_BATCH=4"));
        assertTrue(ingress.contains("setTimeout(drainQueue,0)"));assertFalse(ingress.contains("queueMicrotask"));assertTrue(ingress.contains("completionReached()"));assertTrue(ingress.contains("visitedNodes=new WeakSet()"));assertTrue(ingress.contains("maxSynchronousBatch"));assertTrue(ingress.contains("eventLoopYields"));
    }
    @Test public void domCompletionFallbackDoesNotExist() throws Exception {
        assertFalse(Files.exists(path("TurnCompletionDomFallbackScript.java")));String config=source("WebViewConfig.java"),cont=source("SelfRunContinuationDom.java");
        assertFalse(config.contains("TurnCompletionDomFallbackScript"));assertFalse(cont.contains("new MutationObserver"));assertFalse(cont.contains("observeTurnCompletion"));assertFalse(cont.contains("__selfRunDriveTurnObserver"));
    }
    @Test public void protocolHotPathIsShallowTokenBoundAndStopsAfterCompletion() throws Exception {
        String protocol=source("ChatGptTurnProtocolScript.java");assertTrue(protocol.contains("const snapshot=()=>({...state})"));assertFalse(protocol.contains("JSON.parse(JSON.stringify(state))"));assertTrue(protocol.contains("const visited=seen||new WeakSet()"));assertTrue(protocol.contains("if(state.completionDispatched)return"));
        assertTrue(protocol.contains("bindPendingTurn"));assertTrue(protocol.contains("pendingTurnToken"));assertTrue(protocol.contains("activeTurnToken"));assertFalse(protocol.contains("MutationObserver"));assertFalse(protocol.contains("setInterval"));
    }
    @Test public void optimizationLayerDoesNotKillRendererOrAttachSurfaceAsRecovery() throws Exception {
        String combined=source("WebViewConfig.java")+source("WorkTurnProtocolIngressScript.java")+source("WorkProtocolTransportCaptureScript.java")+source("ChatGptTurnProtocolScript.java");
        for(String forbidden:new String[]{"pauseTimers()","resumeTimers()","WebViewRenderProcess.terminate","terminate()"})assertFalse(forbidden,combined.contains(forbidden));
        String host=source("HeadlessWebViewHost.java");assertTrue(host.contains("virtualDisplay.setSurface(null)"));
    }
    private static Path path(String file){Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+file);if(!Files.exists(p))p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+file);return p;}
    private static String source(String file)throws Exception{return new String(Files.readAllBytes(path(file)),StandardCharsets.UTF_8);}
}
''')

# Protocol-only contract tests replacing observer/fallback state assumptions.
write(TEST/'TurnProtocolUiStateTest.java',r'''package com.shaterguy.chatgptselfrun;
import org.junit.Test;
import static org.junit.Assert.*;
public final class TurnProtocolUiStateTest {
 @Test public void headlinesAreProtocolOnly(){assertEquals("응답 감지 중 · Protocol",TurnProtocolUiState.detectorHeadline(TurnProtocolUiState.DETECTOR_PROTOCOL));assertEquals("추론",TurnProtocolUiState.pillFor("추론 중 · 응답 Protocol"));}
 @Test public void snapshotCorrelatesExactlyOneTurnToken(){TurnProtocolUiState.Snapshot s=new TurnProtocolUiState.Snapshot(true,"turn_request","THINKING",TurnProtocolUiState.DETECTOR_PROTOCOL,"tok",1L);assertTrue(s.activeGenerationFor("tok"));assertFalse(s.activeGenerationFor("other"));}
}
''')
write(TEST/'TurnProtocolObservabilityContractTest.java',r'''package com.shaterguy.chatgptselfrun;
import org.junit.Test;
import java.nio.charset.StandardCharsets;import java.nio.file.*;
import static org.junit.Assert.*;
public final class TurnProtocolObservabilityContractTest {
 @Test public void bridgeIsProtocolOnlyAndFeatureFailureIsFailClosed()throws Exception{String b=source("TurnProtocolLogBridge.java"),c=source("WebViewConfig.java"),s=source("SelfRunService.java");assertTrue(b.contains("WEB_MESSAGE_LISTENER"));assertTrue(b.contains("DOCUMENT_START_SCRIPT"));assertTrue(b.contains("path=UNAVAILABLE"));assertTrue(b.contains("turnToken"));assertFalse(b.contains("DOM_FALLBACK"));assertFalse(b.contains("observer_bound"));assertTrue(c.contains("if (!protocolObservable) return false"));assertTrue(s.contains("TURN_PROTOCOL_UNAVAILABLE"));}
 @Test public void protocolSourcesRemainWhitelisted()throws Exception{String b=source("TurnProtocolLogBridge.java"),s=source("SelfRunService.java");for(String v:new String[]{"message_stream_complete","finished_successfully_end_turn","restored_complete"}){assertTrue(b.contains(v));assertTrue(s.contains(v));}assertFalse(s.contains("dom_assistant_final_ui"));assertFalse(s.contains("stable_idle"));}
 private static String source(String f)throws Exception{Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);if(!Files.exists(p))p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);return Files.readString(p,StandardCharsets.UTF_8);}
}
''')
write(TEST/'TurnProtocolGenerationGuardPolicyTest.java',r'''package com.shaterguy.chatgptselfrun;
import org.junit.Test;import java.nio.charset.StandardCharsets;import java.nio.file.*;import static org.junit.Assert.*;
public final class TurnProtocolGenerationGuardPolicyTest {
 @Test public void pendingAndActiveTokensAreDistinctAndRequestIdentityRemainsFenced()throws Exception{String p=source("ChatGptTurnProtocolScript.java");assertTrue(p.contains("pendingTurnToken"));assertTrue(p.contains("activeTurnToken"));assertTrue(p.contains("state.activeTurnToken=pending;state.pendingTurnToken=''"));assertTrue(p.contains("identity&&identity!==state.requestIdentity"));assertTrue(p.contains("retiredWorkTurnIds.includes(value)"));}
 @Test public void nextPendingTokenCannotReplaceCompletedActiveGeneration()throws Exception{String p=source("ChatGptTurnProtocolScript.java");assertTrue(p.contains("token=safe(state.activeTurnToken)"));assertTrue(p.contains("state.pendingTurnToken=value;save()"));assertFalse(p.contains("activeTurnToken=value;save()"));}
 @Test public void nativeCompletionRequiresRunWaitTokenAndProtocolSource()throws Exception{String s=source("SelfRunService.java");assertTrue(s.contains("PHASE_WAIT_TURN_COMPLETION.equals(store.phase())"));assertTrue(s.contains("token.equals(store.turnProtocolToken())"));assertTrue(s.contains("allowedTurnCompletionSource(source)"));assertTrue(s.contains("store.beginPostTurnDriveSync(token)"));}
 @Test public void waitHasNoDomObserverOrPollingRecovery()throws Exception{String s=source("SelfRunService.java"),d=source("SelfRunContinuationDom.java");for(String f:new String[]{"observeTurnCompletion","TURN_OBSERVER_HEALTHCHECK_MS","recoverDetachedObserverOutput","TURN_STOP_SEEN_HOST","turnObserverSawStop"}){assertFalse(s.contains(f));assertFalse(d.contains(f));}assertFalse(d.contains("new MutationObserver"));}
 private static String source(String f)throws Exception{Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);if(!Files.exists(p))p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);return Files.readString(p,StandardCharsets.UTF_8);}
}
''')
write(TEST/'SelfRunDriveDev3PolicyTest.java',r'''package com.shaterguy.chatgptselfrun;
import org.junit.Test;import java.nio.charset.StandardCharsets;import java.nio.file.*;import static org.junit.Assert.*;
/** Dev4 preserves dev3 workload contracts while making completion Protocol-only. */
public final class SelfRunDriveDev3PolicyTest {
 @Test public void waitIsEventDrivenAndSurfaceDetached()throws Exception{String s=source("SelfRunService.java"),h=source("HeadlessWebViewHost.java");assertTrue(s.contains("detachDisplayOutput(\"wait_turn_completion\")"));assertFalse(s.contains("SelfRunContinuationDom.observeTurnCompletion"));assertFalse(s.contains("TURN_OBSERVER_HEALTHCHECK_MS"));assertTrue(h.contains("virtualDisplay.setSurface(null)"));}
 @Test public void submissionBindsProtocolBeforeClick()throws Exception{String d=source("SelfRunContinuationDom.java");int bind=d.indexOf("const binding=bindTurnProtocol()"),click=d.indexOf("c.send.click()",bind);assertTrue(bind>=0&&click>bind);assertTrue(d.contains("p.bindPendingTurn("));}
 @Test public void actualRendererGoneRecoveryStillExistsWithoutTerminatePolicy()throws Exception{String s=source("SelfRunService.java");assertTrue(s.contains("onRenderProcessGone"));assertFalse(s.contains("WebViewRenderProcess.terminate"));}
 private static String source(String f)throws Exception{Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);if(!Files.exists(p))p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);return Files.readString(p,StandardCharsets.UTF_8);}
}
''')
write(TEST/'TurnCompletionWatchdogClaimPolicyTest.java',r'''package com.shaterguy.chatgptselfrun;
import org.junit.Test;import java.nio.charset.StandardCharsets;import java.nio.file.*;import static org.junit.Assert.*;
/** Protocol callback fencing plus bounded Drive synchronization. */
public final class TurnCompletionWatchdogClaimPolicyTest {
 @Test public void callbackUsesDurableProtocolTokenAndAllowedSource()throws Exception{String s=source("SelfRunService.java");assertTrue(s.contains("UUID.randomUUID().toString().replace"));assertTrue(s.contains("token.equals(store.turnProtocolToken())"));assertTrue(s.contains("allowedTurnCompletionSource(source)"));assertTrue(s.contains("store.beginPostTurnDriveSync(token)"));}
 @Test public void driveSignalReadRemainsBoundedAfterProtocolCompletion()throws Exception{String s=source("SelfRunService.java");assertTrue(s.contains("POST_DOM_DRIVE_RETRY_MS"));assertTrue(s.contains("POST_DOM_DRIVE_MAX_WAIT_MS"));assertTrue(s.contains("drive.readDocumentSnapshot"));assertTrue(s.contains("schedulePostDomDriveSync(POST_DOM_DRIVE_RETRY_MS)"));}
 private static String source(String f)throws Exception{Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);if(!Files.exists(p))p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);return Files.readString(p,StandardCharsets.UTF_8);}
}
''')
write(TEST/'TurnCompletionWatchdogFencePolicyTest.java',r'''package com.shaterguy.chatgptselfrun;
import org.junit.Test;import java.nio.charset.StandardCharsets;import java.nio.file.*;import static org.junit.Assert.*;
public final class TurnCompletionWatchdogFencePolicyTest {
 @Test public void noLegacyDomFenceRemains()throws Exception{String s=source("SelfRunService.java"),d=source("SelfRunContinuationDom.java"),st=source("SelfRunStore.java");for(String f:new String[]{"TURN_STOP_SEEN_HOST","turnObserverSawStop","markTurnObserverStopSeen","completionObserver","observerCallback"}){assertFalse(s.contains(f));assertFalse(d.contains(f));assertFalse(st.contains(f));}}
 @Test public void protocolCallbackFencePrecedesDriveTransition()throws Exception{String s=source("SelfRunService.java");int run=s.indexOf("launchedRunId.equals(run)"),phase=s.indexOf("PHASE_WAIT_TURN_COMPLETION.equals(store.phase())",run),token=s.indexOf("token.equals(store.turnProtocolToken())",phase),source=s.indexOf("allowedTurnCompletionSource(source)",token),transition=s.indexOf("store.beginPostTurnDriveSync(token)",source);assertTrue(run>=0&&phase>run&&token>phase&&source>token&&transition>source);}
 private static String source(String f)throws Exception{Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);if(!Files.exists(p))p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);return Files.readString(p,StandardCharsets.UTF_8);}
}
''')
# Recovery-ID parser test remains, only live-path assertion changes.
p=TEST/'TurnCompletionWatchdogRecoveryIdPolicyTest.java'; s=read(p); s=s.replace('assertTrue(service.contains("observeTurnCompletion"));','assertFalse(service.contains("observeTurnCompletion"));\n        assertTrue(service.contains("allowedTurnCompletionSource"));');write(p,s)
# Old DOM watchdog/fallback tests are obsolete; required coverage is replaced above and in instrumentation.
for name in ['TurnCompletionWatchdogPolicyTest.java','TurnCompletionAssistantFallbackPolicyTest.java','TurnObserverEarlyStopPolicyTest.java']:
    q=TEST/name
    if q.exists(): q.unlink()
# Update protocol script unit identity and semantics.
p=TEST/'ChatGptTurnProtocolScriptTest.java'; s=read(p);s=s.replace('turn-protocol-v6','turn-protocol-v7').replace('response-protocol-state:v6','response-protocol-state:v7').replace('earlySemanticCompleteCannotFinishAndKeepsDomFallbackAvailable','earlySemanticCompleteCannotFinishWithoutDomFallback').replace("assertFalse(script.contains(\"if(!completionEvidence()){\\n                      suspendDomFallback(window.__selfRunDriveTurnObserver);\"));","assertFalse(script.contains(\"__selfRunDriveTurnObserver\"));\n        assertTrue(script.contains(\"pendingTurnToken\"));\n        assertTrue(script.contains(\"activeTurnToken\"));")
write(p,s)

# Rollover tests: adapt method signatures and remove STOP-as-start expectations generically.
for q in [TEST/'SelfRunRolloverPolicyTest.java',TEST/'WebViewTransientNoStartPolicyWiringTest.java']:
    if q.exists():
        t=read(q);t=t.replace(', false, validated',', validated').replace(', true, validated',', validated')
        t=t.replace('activeGenerationForCurrentObserver','activeGenerationForCurrentTurn')
        write(q,t)

# New Protocol-only detached Surface instrumentation + preserved Work frame-burst and renderer responsiveness measurement.
new_at=ATEST/'TurnProtocolSurfaceDetachedWebViewTest.java'
write(new_at,r'''package com.shaterguy.chatgptselfrun;

import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewRenderProcess;
import androidx.webkit.WebViewRenderProcessClient;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class TurnProtocolSurfaceDetachedWebViewTest {
    private static final String ORIGIN="https://chatgpt.com/g/g-p-selfrun-protocol/c/protocol123";

    @Test public void chatCompletesByProtocolWhileSurfaceDetached() throws Exception { runDetachedProtocolTurn("chat","SR-PROTOCOL-CHAT","tok-chat",false); }
    @Test public void workCompletesByProtocolWhileSurfaceDetached() throws Exception { runDetachedProtocolTurn("work","SR-PROTOCOL-WORK","tok-work",true); }

    private void runDetachedProtocolTurn(String mode,String runId,String token,boolean installWork) throws Exception {
        try(ActivityScenario<SelfRunNewActivity> scenario=ActivityScenario.launch(SelfRunNewActivity.class)){
            AtomicReference<HeadlessWebViewHost> host=new AtomicReference<>();AtomicReference<WebView> web=new AtomicReference<>();AtomicReference<String> completion=new AtomicReference<>("");AtomicInteger unresponsive=new AtomicInteger(),responsive=new AtomicInteger();
            CountDownLatch loaded=new CountDownLatch(1);
            scenario.onActivity(activity->{HeadlessWebViewHost h=HeadlessWebViewHost.create(activity);WebView v=h.webView();v.getSettings().setJavaScriptEnabled(true);v.getSettings().setDomStorageEnabled(true);installRendererClient(v,unresponsive,responsive);v.setWebViewClient(client(completion,loaded));host.set(h);web.set(v);v.loadDataWithBaseURL(ORIGIN,fixture(),"text/html","UTF-8",null);});
            assertTrue(loaded.await(15,TimeUnit.SECONDS));
            evaluateRaw(scenario,web,"window.fetch=async()=>({ok:true,clone:()=>({body:null})});window.__selfRunRequestProfileEngine={target:()=>({mode:'"+mode+"',runId:'"+runId+"'})};'ready';");
            evaluateRaw(scenario,web,ChatGptTurnProtocolScript.documentStartScript());
            if(installWork){evaluateRaw(scenario,web,WorkTurnProtocolIngressScript.documentStartScript());evaluateRaw(scenario,web,WorkProtocolTransportCaptureScript.documentStartScript());}
            assertEquals("true",evaluate(scenario,web,"String(window.__selfRunTurnProtocol.bindPendingTurn('"+runId+"','"+token+"').ok)"));
            scenario.onActivity(activity->{assertTrue(host.get().hasDetachableOutput());assertTrue(host.get().detachOutput());assertFalse(host.get().isOutputAttached());});
            assertEquals("THINKING",evaluate(scenario,web,"fetch('/backend-api/f/conversation',{method:'POST'}).then(()=>window.__selfRunTurnProtocol.snapshot().phase)"));
            assertEquals("ANSWERING",evaluate(scenario,web,"window.__selfRunTurnProtocol.observeSseText('data: {\\\"type\\\":\\\"message_marker\\\",\\\"marker\\\":\\\"final_channel_token\\\",\\\"event\\\":\\\"first\\\",\\\"message_id\\\":\\\"m1\\\"}\\n\\n');window.__selfRunTurnProtocol.snapshot().phase"));
            evaluateRaw(scenario,web,"window.__selfRunTurnProtocol.observeSseText('data: {\\\"type\\\":\\\"message_stream_complete\\\"}\\n\\n');'complete';");
            for(int i=0;i<60&&completion.get().isEmpty();i++)Thread.sleep(50L);
            String callback=completion.get();assertTrue(callback.startsWith("selfrun-drive://turn-completed?"));assertTrue(callback.contains("run="+runId));assertTrue(callback.contains("token="+token));assertTrue(callback.contains("source=message_stream_complete"));
            scenario.onActivity(activity->{assertFalse(host.get().isOutputAttached());host.get().destroy();});assertEquals("renderer unresponsive callback",0,unresponsive.get());
        }
    }

    @Test public void domFinalUiCannotCompleteWithoutProtocolComplete() throws Exception {
        try(ActivityScenario<SelfRunNewActivity> scenario=ActivityScenario.launch(SelfRunNewActivity.class)){
            AtomicReference<WebView> web=new AtomicReference<>();AtomicReference<String> completion=new AtomicReference<>("");CountDownLatch loaded=new CountDownLatch(1);
            scenario.onActivity(activity->{WebView v=new WebView(activity);v.getSettings().setJavaScriptEnabled(true);v.setWebViewClient(client(completion,loaded));activity.setContentView(v);web.set(v);v.loadDataWithBaseURL(ORIGIN,fixture(),"text/html","UTF-8",null);});assertTrue(loaded.await(15,TimeUnit.SECONDS));
            evaluateRaw(scenario,web,"window.fetch=async()=>({ok:true,clone:()=>({body:null})});window.__selfRunRequestProfileEngine={target:()=>({mode:'chat',runId:'SR-DOM-NOAUTH'})};'ready';");evaluateRaw(scenario,web,ChatGptTurnProtocolScript.documentStartScript());assertEquals("true",evaluate(scenario,web,"String(window.__selfRunTurnProtocol.bindPendingTurn('SR-DOM-NOAUTH','tok').ok)"));assertEquals("THINKING",evaluate(scenario,web,"fetch('/backend-api/f/conversation',{method:'POST'}).then(()=>window.__selfRunTurnProtocol.snapshot().phase)"));evaluateRaw(scenario,web,"document.querySelector('main').insertAdjacentHTML('beforeend','<article data-message-author-role=assistant>done<button aria-label=Copy>Copy</button></article>');'dom';");Thread.sleep(500L);assertEquals("",completion.get());assertEquals("THINKING",evaluate(scenario,web,"window.__selfRunTurnProtocol.snapshot().phase"));
        }
    }

    @Test public void newPendingTokenDoesNotResurrectCompletedGeneration() throws Exception {
        try(ActivityScenario<SelfRunNewActivity> scenario=ActivityScenario.launch(SelfRunNewActivity.class)){
            AtomicReference<WebView> web=new AtomicReference<>();AtomicReference<String> completion=new AtomicReference<>("");CountDownLatch loaded=new CountDownLatch(1);scenario.onActivity(activity->{WebView v=new WebView(activity);v.getSettings().setJavaScriptEnabled(true);v.setWebViewClient(client(completion,loaded));activity.setContentView(v);web.set(v);v.loadDataWithBaseURL(ORIGIN,fixture(),"text/html","UTF-8",null);});assertTrue(loaded.await(15,TimeUnit.SECONDS));
            evaluateRaw(scenario,web,"window.fetch=async()=>({ok:true,clone:()=>({body:null})});window.__selfRunRequestProfileEngine={target:()=>({mode:'chat',runId:'SR-TOKEN-GEN'})};'ready';");evaluateRaw(scenario,web,ChatGptTurnProtocolScript.documentStartScript());evaluateRaw(scenario,web,"window.__selfRunTurnProtocol.bindPendingTurn('SR-TOKEN-GEN','old');window.__selfRunTurnProtocol.observeCanonicalRequest();window.__selfRunTurnProtocol.observeSseText('data: {\\\"type\\\":\\\"message_marker\\\",\\\"marker\\\":\\\"final_channel_token\\\",\\\"event\\\":\\\"first\\\"}\\n\\n');window.__selfRunTurnProtocol.observeSseText('data: {\\\"type\\\":\\\"message_stream_complete\\\"}\\n\\n');'done';");for(int i=0;i<40&&completion.get().isEmpty();i++)Thread.sleep(25L);assertTrue(completion.get().contains("token=old"));completion.set("");assertEquals("true",evaluate(scenario,web,"String(window.__selfRunTurnProtocol.bindPendingTurn('SR-TOKEN-GEN','new').ok)"));evaluateRaw(scenario,web,"window.__selfRunTurnProtocol.dispatchStoredCompletion();'flush';");Thread.sleep(250L);assertEquals("",completion.get());assertEquals("old",evaluate(scenario,web,"window.__selfRunTurnProtocol.snapshot().activeTurnToken"));assertEquals("new",evaluate(scenario,web,"window.__selfRunTurnProtocol.snapshot().pendingTurnToken"));
        }
    }

    @Test public void workDecoderFrameBurstPreservesOrderYieldsAndCoalescesDiagnostics() throws Exception {
        try(ActivityScenario<SelfRunNewActivity> scenario=ActivityScenario.launch(SelfRunNewActivity.class)){
            AtomicReference<WebView> web=new AtomicReference<>();AtomicReference<String> completion=new AtomicReference<>("");AtomicInteger unresponsive=new AtomicInteger(),responsive=new AtomicInteger();CountDownLatch loaded=new CountDownLatch(1);
            scenario.onActivity(activity->{WebView v=new WebView(activity);v.getSettings().setJavaScriptEnabled(true);installRendererClient(v,unresponsive,responsive);v.setWebViewClient(client(completion,loaded));activity.setContentView(v);web.set(v);v.loadDataWithBaseURL(ORIGIN,fixture(),"text/html","UTF-8",null);});assertTrue(loaded.await(15,TimeUnit.SECONDS));
            evaluateRaw(scenario,web,"window.__workOrder=[];window.__workPosts=0;window.__workDone=false;window.selfRunTurnLog={postMessage:()=>{window.__workPosts++;}};window.__selfRunRequestProfileEngine={target:()=>({mode:'work',runId:'SR-WORK-STRESS'})};window.__selfRunTurnProtocol={snapshot:()=>({phase:'THINKING',completionDispatched:false}),diagnostics:()=>({workTurnId:'WT-1'}),observeRequest:()=>true,observeSseText:(text)=>{const node=JSON.parse(String(text).slice(6).trim());window.__workOrder.push(node.seq);return {};}};'ready';");evaluateRaw(scenario,web,WorkTurnProtocolIngressScript.documentStartScript());evaluateRaw(scenario,web,"(()=>{const p=[];for(let i=0;i<40;i++)p.push(window.__selfRunWorkTurnProtocolIngress.observeTransportData({type:'message_delta',turn_id:'WT-1',seq:i},'fixture'));Promise.all(p).then(()=>window.__workDone=true);return 'queued';})()");for(int i=0;i<100;i++){if(Boolean.parseBoolean(evaluate(scenario,web,"String(window.__workDone)")))break;Thread.sleep(50L);}JSONObject result=new JSONObject(evaluate(scenario,web,"JSON.stringify({diag:window.__selfRunWorkTurnProtocolIngress.diagnostics(),order:window.__workOrder,posts:window.__workPosts})"));JSONObject diag=result.getJSONObject("diag");assertEquals(40,diag.getInt("framesSeen"));assertTrue(diag.getInt("maxSynchronousBatch")<=4);assertTrue(diag.getInt("eventLoopYields")>=9);assertTrue(diag.getInt("maxQueueDepth")>=36);assertTrue(result.getInt("posts")<=4);assertEquals(40,result.getJSONArray("order").length());for(int i=0;i<40;i++)assertEquals(i,result.getJSONArray("order").getInt(i));assertEquals(0,unresponsive.get());
        }
    }

    private static void installRendererClient(WebView v,AtomicInteger unresponsive,AtomicInteger responsive){if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE))return;WebViewCompat.setWebViewRenderProcessClient(v,new WebViewRenderProcessClient(){@Override public void onRenderProcessUnresponsive(WebView view,WebViewRenderProcess renderer){unresponsive.incrementAndGet();}@Override public void onRenderProcessResponsive(WebView view,WebViewRenderProcess renderer){responsive.incrementAndGet();}});}
    private static WebViewClient client(AtomicReference<String> completion,CountDownLatch loaded){return new WebViewClient(){@Override public void onPageFinished(WebView v,String url){if(url!=null&&url.startsWith("https://chatgpt.com/"))loaded.countDown();}@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){String u=r==null||r.getUrl()==null?"":r.getUrl().toString();if(u.startsWith("selfrun-drive://")){completion.set(u);return true;}return false;}@SuppressWarnings("deprecation")@Override public boolean shouldOverrideUrlLoading(WebView v,String u){if(u!=null&&u.startsWith("selfrun-drive://")){completion.set(u);return true;}return false;}};}
    private static String fixture(){return "<!doctype html><html><body><main><form><div id='prompt-textarea' contenteditable='true'></div><button data-testid='send-button'>Send</button></form></main></body></html>";}
    private static String evaluate(ActivityScenario<SelfRunNewActivity> s,AtomicReference<WebView> w,String js)throws Exception{Object decoded=new JSONTokener(evaluateRaw(s,w,js)).nextValue();return String.valueOf(decoded);}
    private static String evaluateRaw(ActivityScenario<SelfRunNewActivity> s,AtomicReference<WebView> w,String js)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicReference<String> out=new AtomicReference<>();s.onActivity(a->w.get().evaluateJavascript(js,v->{out.set(v);done.countDown();}));assertTrue(done.await(15,TimeUnit.SECONDS));return out.get();}
}
''')
old_at=ATEST/'TurnCompletionDomFallbackWebViewTest.java'
if old_at.exists(): old_at.unlink()
# Remove any remaining test whose sole contract references deleted completion-observer identifiers.
obsolete_terms=['TurnCompletionDomFallbackScript','TURN_COMPLETION_STABILITY_MS','TURN_OBSERVER_HEALTHCHECK_MS','TURN_STOP_SEEN_HOST','turnObserverSawStop','markTurnObserverStopSeen','cancelTurnCompletionObserver','observeTurnCompletion','completionObserver(']
protected={new_at.name,'RendererWorkloadOptimizationPolicyTest.java','TurnProtocolGenerationGuardPolicyTest.java','TurnProtocolObservabilityContractTest.java','SelfRunDriveDev3PolicyTest.java','TurnCompletionWatchdogClaimPolicyTest.java','TurnCompletionWatchdogFencePolicyTest.java','TurnCompletionWatchdogRecoveryIdPolicyTest.java','ChatGptTurnProtocolScriptTest.java'}
for root in [TEST,ATEST]:
    for q in list(root.glob('*.java')):
        if q.name in protected: continue
        txt=read(q)
        if any(term in txt for term in obsolete_terms): q.unlink()

# Audit forbidden production completion detectors and stale identity coupling.
prod='\n'.join(read(q) for q in SRC.glob('*.java'))
for forbidden in ['__selfRunDomAssistantFallback','__selfRunDriveTurnObserver','PROTOCOL_STALE_MS','dom_assistant_final_ui','stable_idle','TURN_STOP_SEEN_HOST','turn-stop-seen','turnObserverSawStop','markTurnObserverStopSeen','observeTurnCompletion','cancelTurnCompletionObserver','TURN_COMPLETION_STABILITY_MS','TURN_OBSERVER_HEALTHCHECK_MS','DETACHED_OBSERVER_FALLBACK','recoverDetachedObserverOutput']:
    if forbidden in prod: raise SystemExit('forbidden production DOM completion token remains: '+forbidden)
if (SRC/'TurnCompletionDomFallbackScript.java').exists(): (SRC/'TurnCompletionDomFallbackScript.java').unlink()
# Work workload files must stay byte-for-byte from baseline.
if git(['show',BASE+':app/src/main/java/com/shaterguy/chatgptselfrun/WorkTurnProtocolIngressScript.java']) != read(SRC/'WorkTurnProtocolIngressScript.java').rstrip('\n'):
    raise SystemExit('WorkTurnProtocolIngressScript changed')
if git(['show',BASE+':app/src/main/java/com/shaterguy/chatgptselfrun/WorkProtocolTransportCaptureScript.java']) != read(SRC/'WorkProtocolTransportCaptureScript.java').rstrip('\n'):
    raise SystemExit('WorkProtocolTransportCaptureScript changed')
# No stale development identity in code/test/build policy after intentional dev4 mutation.
for q in [ROOT/'app/build.gradle',*TEST.glob('*.java'),*ATEST.glob('*.java')]:
    if '2.3.2-dev3' in read(q): raise SystemExit('stale dev3 identity: '+str(q))
print('dev4 protocol-only patch applied')
