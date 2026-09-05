package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/** HYBRID profile stage plus a verified ChatGPT mode-radio gate for continuation. */
final class HybridRequestProfileScript {
    private static final String VERSION = "hybrid-request-profile-v5";
    private static final Set<String> CHATGPT_ORIGINS = Set.of(
            "https://chatgpt.com", "https://www.chatgpt.com");

    private HybridRequestProfileScript() {}

    static void installDocumentStart(WebView webView) {
        HybridRunProfileStore.Selection selection = HybridRunProfileStore.currentSelection();
        if (!selection.valid()) return;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            throw new IllegalStateException("DOCUMENT_START_SCRIPT unsupported: fail closed");
        }
        WebViewCompat.addDocumentStartJavaScript(webView, documentStartScript(selection), CHATGPT_ORIGINS);
    }

    static String prepareContinuationAndThen(String runId, String actionScript) {
        return continuationUiModeAndThen(runId, "prepare", actionScript);
    }

    static String selectContinuationAndThen(String runId, String actionScript) {
        return continuationUiModeAndThen(runId, "submit", actionScript);
    }

    private static String continuationUiModeAndThen(
            String runId, String boundary, String actionScript) {
        if (!SelfRunProtocolRules.validRunId(runId)) {
            throw new IllegalArgumentException("valid HYBRID run id required");
        }
        if (!"prepare".equals(boundary) && !"submit".equals(boundary)) {
            throw new IllegalArgumentException("valid HYBRID mode boundary required");
        }
        if (actionScript == null || actionScript.isEmpty()) {
            throw new IllegalArgumentException("continuation action required");
        }
        String script = """
                (()=>{__CALIBRATION_PRELUDE__
                  const RUN_ID=__RUN_ID__,BOUNDARY=__BOUNDARY__,BRIDGE_VERSION=__VERSION__;
                  const MODE_WAIT_MS=10000;
                  const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});
                  const norm=value=>String(value??'').trim().toLowerCase();
                  const bridge=window.__selfRunHybridProfileBridge;
                  if(!bridge||bridge.version!==BRIDGE_VERSION||bridge.runId!==RUN_ID
                    ||typeof bridge.selectStage!=='function'||typeof bridge.continuation!=='function')
                    return result('HYBRID_PROFILE_UNAVAILABLE','native HYBRID stage bridge unavailable');
                  const endpoint=bridge.continuation(),desired=norm(endpoint?.mode);
                  if(desired!=='chat'&&desired!=='work')
                    return result('HYBRID_PROFILE_UNAVAILABLE','continuation endpoint unavailable');
                  const wantedValue=desired==='chat'?'chatgpt':'work';
                  const calibrationKey=desired==='chat'?'MODE_CHAT':'MODE_WORK';
                  const radioSelector='button[role="radio"][data-tpp-toggle-value]';
                  const exact=e=>!!e&&__srVisible(e)&&e.matches?.(radioSelector)
                    &&norm(e.getAttribute('data-tpp-toggle-value'))===wantedValue;
                  const calibrated=typeof __srFind==='function'?__srFind(calibrationKey):null;
                  const exactNodes=[...document.querySelectorAll(radioSelector)]
                    .filter(__srVisible).filter(exact);
                  let target=null,source='';
                  if(exact(calibrated)){target=calibrated;source='calibration';}
                  else if(exactNodes.length===1){target=exactNodes[0];source='semantic';}
                  const state=window.__selfRunHybridModeUi;
                  const fresh=!state||state.runId!==RUN_ID||state.desired!==desired
                    ||state.boundary!==BOUNDARY||!Number.isFinite(state.startedAt);
                  const gate=fresh
                    ?(window.__selfRunHybridModeUi={runId:RUN_ID,desired,boundary:BOUNDARY,
                        startedAt:Date.now(),clicks:0,lastClickAt:0})
                    :state;
                  const diagnostics=(outcome,reason,observed='unknown')=>({
                    hybridModeGate:true,hybridModeBoundary:BOUNDARY,hybridModeSource:source||'none',
                    hybridModeTarget:desired,hybridModeObserved:observed,
                    hybridModeOutcome:outcome,hybridModeReason:reason
                  });
                  const unavailableAfterWait=(reason,detail)=>{
                    const waiting=Date.now()-gate.startedAt<MODE_WAIT_MS;
                    return result(waiting?'UI_WAIT':'HYBRID_MODE_UNAVAILABLE',detail,
                      diagnostics(waiting?'waiting':'blocked',reason));
                  };
                  if(!target){
                    const reason=exactNodes.length>1?'ambiguous_target':'target_missing';
                    if(reason==='target_missing')
                      return unavailableAfterWait(reason,'HYBRID continuation mode radio unavailable');
                    return result('HYBRID_MODE_UNAVAILABLE','HYBRID continuation mode radio unavailable',
                      diagnostics('blocked',reason));
                  }
                  const group=target.closest?.('[role="radiogroup"]');
                  if(!group||!__srVisible(group))
                    return unavailableAfterWait('group_missing',
                      'HYBRID continuation mode group unavailable');
                  const groupRadios=[...group.querySelectorAll(radioSelector)].filter(__srVisible);
                  const chatRadios=groupRadios.filter(e=>norm(e.getAttribute('data-tpp-toggle-value'))==='chatgpt');
                  const workRadios=groupRadios.filter(e=>norm(e.getAttribute('data-tpp-toggle-value'))==='work');
                  if(groupRadios.length!==2||chatRadios.length!==1||workRadios.length!==1)
                    return result('HYBRID_MODE_UNAVAILABLE','HYBRID continuation mode group is ambiguous',
                      diagnostics('blocked','group_ambiguous'));
                  const chat=chatRadios[0],work=workRadios[0];
                  const counterpart=desired==='chat'?work:chat;
                  const on=e=>norm(e.getAttribute('aria-checked'))==='true'
                    &&norm(e.getAttribute('data-state'))==='on';
                  const off=e=>norm(e.getAttribute('aria-checked'))==='false'
                    &&norm(e.getAttribute('data-state'))==='off';
                  const observed=on(chat)&&off(work)?'chat':on(work)&&off(chat)?'work':'conflict';
                  if(on(target)&&!off(counterpart))
                    return result('HYBRID_MODE_UNAVAILABLE','HYBRID continuation mode state conflicts',
                      diagnostics('blocked','state_conflict',observed));
                  if(on(target)){
                    gate.clicks=0;gate.lastClickAt=0;
                    try{bridge.selectStage('continuation');}
                    catch(_){return result('HYBRID_PROFILE_UNAVAILABLE','continuation profile activation failed',
                      diagnostics('blocked','profile_activation_failed',observed));}
                    if(bridge.stage?.()!=='continuation')
                      return result('HYBRID_PROFILE_UNAVAILABLE','continuation stage readback failed',
                        diagnostics('blocked','profile_readback_failed',observed));
                    let forwarded;
                    try{forwarded=(__ACTION__);}catch(_){
                      return result('SCRIPT_ERROR','continuation action failed',
                        diagnostics('blocked','action_exception',observed));
                    }
                    try{
                      const parsed=typeof forwarded==='string'?JSON.parse(forwarded):forwarded;
                      if(!parsed||typeof parsed!=='object'||Array.isArray(parsed))
                        return result('SCRIPT_ERROR','continuation action result invalid',
                          diagnostics('blocked','action_result_invalid',observed));
                      const prior=parsed.diagnostics&&typeof parsed.diagnostics==='object'
                        &&!Array.isArray(parsed.diagnostics)?parsed.diagnostics:{};
                      parsed.diagnostics={...prior,...diagnostics('verified','checked_readback',observed)};
                      return JSON.stringify(parsed);
                    }catch(_){
                      return result('SCRIPT_ERROR','continuation action result unreadable',
                        diagnostics('blocked','action_result_unreadable',observed));
                    }
                  }
                  if(!off(target)||!on(counterpart))
                    return result('HYBRID_MODE_UNAVAILABLE','HYBRID continuation mode state unreadable',
                      diagnostics('blocked','state_invalid',observed));
                  if(target.disabled||norm(target.getAttribute('aria-disabled'))==='true')
                    return result('HYBRID_MODE_UNAVAILABLE','HYBRID continuation mode radio disabled',
                      diagnostics('blocked','target_disabled',observed));
                  const rect=target.getBoundingClientRect(),hit=document.elementFromPoint(
                    rect.left+rect.width/2,rect.top+rect.height/2);
                  if(!hit||!(hit===target||target.contains(hit)||hit.contains(target)))
                    return unavailableAfterWait('target_obstructed',
                      'HYBRID continuation mode radio obstructed');
                  const now=Date.now();
                  if(gate.clicks>=3)
                    return result('HYBRID_MODE_UNAVAILABLE','HYBRID continuation mode did not switch',
                      diagnostics('blocked','click_not_applied',observed));
                  if(now-gate.lastClickAt<1000)
                    return result('UI_WAIT','HYBRID continuation mode readback pending',
                      diagnostics('waiting','readback_pending',observed));
                  gate.clicks+=1;gate.lastClickAt=now;
                  try{target.click();}
                  catch(_){return result('HYBRID_MODE_UNAVAILABLE','HYBRID continuation mode click failed',
                    diagnostics('blocked','click_exception',observed));}
                  return result('UI_WAIT','HYBRID continuation mode switching',
                    diagnostics('switching','radio_clicked',observed));
                })()
                """;
        return script
                .replace("__CALIBRATION_PRELUDE__", WebUiCalibrationDom.runtimePrelude())
                .replace("__RUN_ID__", q(runId))
                .replace("__BOUNDARY__", q(boundary))
                .replace("__VERSION__", q(VERSION))
                .replace("__ACTION__", actionScript);
    }

    static String documentStartScript(HybridRunProfileStore.Selection selection) {
        if (selection == null || !selection.valid()) throw new IllegalArgumentException("valid HYBRID selection required");
        HybridRunProfileStore.Endpoint bootstrap = selection.bootstrap;
        HybridRunProfileStore.Endpoint continuation = selection.continuation;
        boolean initialContinuation = selection.continuationStage();
        return """
                (()=>{
                  if(window.__selfRunHybridProfileBridge?.version===__VERSION__&&window.__selfRunHybridProfileBridge?.runId===__RUN_ID__)return;
                  const RUN_ID=__RUN_ID__;
                  const REGISTRY=__REGISTRY__;
                  const BOOTSTRAP=__BOOTSTRAP__;
                  const CONTINUATION=__CONTINUATION__;
                  const norm=v=>String(v??'').trim().toLowerCase();
                  const sameOrigin=url=>{try{return new URL(url,location.href).origin===location.origin;}catch(_){return false;}};
                  const conversationRoute=url=>{try{let p=new URL(url,location.href).pathname.toLowerCase();if(p.length>1)p=p.replace(/[/]+$/,'');return p==='/backend-api/conversation'||p==='/backend-api/f/conversation';}catch(_){return false;}};
                  const engine=window.__selfRunRequestProfileEngine;
                  if(!engine||engine.version!==__ENGINE_VERSION__)throw new Error('SELFRUN_HYBRID:profile_engine_unavailable');
                  engine.installRegistry(REGISTRY);
                  let stage=__INITIAL_STAGE__;
                  let lastDecision={stage,reason:'native-stage-initial'};
                  const endpointMatches=(t,e)=>!!t&&t.ready===true&&t.runId===RUN_ID&&t.mode===e.mode&&norm(t.reasoning)===e.reasoning&&(e.mode==='chat'||norm(t.model)===e.model);
                  const configure=(e,envelope)=>{
                    const expectedEnvelope=envelope===true;
                    const current=engine.target();
                    let changed=!endpointMatches(current,e);
                    if(changed){
                      engine.begin(e.mode,RUN_ID);
                      if(e.mode==='chat')engine.setChatProfiles(e.reasoning,e.reasoning);
                      else{engine.setWorkModel(e.model);engine.setWorkReasoning(e.reasoning);}
                    }
                    if(current?.hybridContinuation!==expectedEnvelope)changed=true;
                    engine.setHybridContinuationEnvelope(expectedEnvelope);
                    const next=engine.target();
                    if(!endpointMatches(next,e)||next.hybridContinuation!==expectedEnvelope)throw new Error('SELFRUN_HYBRID:target_readback_mismatch');
                    return changed;
                  };
                  const stageEndpoint=()=>stage==='continuation'?CONTINUATION:BOOTSTRAP;
                  const selectStage=value=>{
                    const next=norm(value);
                    if(next!=='bootstrap'&&next!=='continuation')throw new Error('SELFRUN_HYBRID:invalid_native_stage');
                    const endpoint=next==='continuation'?CONTINUATION:BOOTSTRAP;
                    configure(endpoint,next==='continuation');
                    stage=next;
                    lastDecision={stage,reason:'native-stage-selected'};
                    return true;
                  };
                  const prepare=()=>{
                    configure(stageEndpoint(),stage==='continuation');
                    lastDecision={stage,reason:'native-stage-request'};
                  };
                  configure(stageEndpoint(),stage==='continuation');
                  const innerFetch=window.fetch.bind(window);
                  window.fetch=function(input,init){
                    let eligible=false;
                    try{const isReq=typeof Request!=='undefined'&&input instanceof Request;const url=isReq?input.url:String(input??'');const method=init&&init.method!==undefined?init.method:(isReq?input.method:'GET');eligible=norm(method)==='post'&&sameOrigin(url)&&conversationRoute(url);}catch(_){}
                    if(!eligible)return innerFetch(input,init);
                    try{prepare();}catch(error){return Promise.reject(error);}
                    return innerFetch(input,init);
                  };
                  const innerOpen=XMLHttpRequest.prototype.open,innerSend=XMLHttpRequest.prototype.send,xhrMeta=new WeakMap();
                  XMLHttpRequest.prototype.open=function(method,url,...rest){xhrMeta.set(this,{method:String(method||''),url:String(url||'')});return innerOpen.call(this,method,url,...rest);};
                  XMLHttpRequest.prototype.send=function(body){const m=xhrMeta.get(this)||{method:'',url:''};if(norm(m.method)==='post'&&sameOrigin(m.url)&&conversationRoute(m.url)){prepare();}return innerSend.call(this,body);};
                  window.__selfRunHybridProfileBridge={version:__VERSION__,runId:RUN_ID,stage:()=>stage,selectStage,lastDecision:()=>({...lastDecision}),bootstrap:()=>({...BOOTSTRAP}),continuation:()=>({...CONTINUATION})};
                })();
                """
                .replace("__VERSION__", q(VERSION))
                .replace("__RUN_ID__", q(selection.runId))
                .replace("__REGISTRY__", q(ProfileRegistry.runtimeJson()))
                .replace("__BOOTSTRAP__", endpointJson(bootstrap))
                .replace("__CONTINUATION__", endpointJson(continuation))
                .replace("__INITIAL_STAGE__", q(initialContinuation ? "continuation" : "bootstrap"))
                .replace("__ENGINE_VERSION__", q(RequestProfileScript.ENGINE_VERSION));
    }

    private static String endpointJson(HybridRunProfileStore.Endpoint endpoint) {
        return "{mode:" + q(endpoint.isWork() ? "work" : "chat")
                + ",model:" + q(endpoint.model) + ",reasoning:" + q(endpoint.reasoning) + "}";
    }

    private static String q(String value) { return SelfRunScript.quote(value == null ? "" : value); }
}
