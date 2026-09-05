package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/** HYBRID profile stage plus an API-profile readback gate for continuation. */
final class HybridRequestProfileScript {
    private static final String VERSION = "hybrid-request-profile-v6";
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
            throw new IllegalArgumentException("valid HYBRID profile boundary required");
        }
        if (actionScript == null || actionScript.isEmpty()) {
            throw new IllegalArgumentException("continuation action required");
        }
        String script = """
                (()=>{
                  const RUN_ID=__RUN_ID__,BOUNDARY=__BOUNDARY__,BRIDGE_VERSION=__VERSION__;
                  const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});
                  const norm=value=>String(value??'').trim().toLowerCase();
                  const bridge=window.__selfRunHybridProfileBridge;
                  if(!bridge||bridge.version!==BRIDGE_VERSION||bridge.runId!==RUN_ID
                    ||typeof bridge.selectStage!=='function'||typeof bridge.continuation!=='function'
                    ||typeof bridge.target!=='function')
                    return result('HYBRID_PROFILE_UNAVAILABLE','native HYBRID stage bridge unavailable');
                  const endpoint=bridge.continuation(),desired=norm(endpoint?.mode);
                  if(desired!=='chat'&&desired!=='work')
                    return result('HYBRID_PROFILE_UNAVAILABLE','continuation endpoint unavailable');
                  const profileMatches=(target,expected)=>!!target&&target.ready===true
                    &&target.runId===RUN_ID&&norm(target.mode)===norm(expected.mode)
                    &&norm(target.reasoning)===norm(expected.reasoning)
                    &&(expected.mode!=='chat'||(
                      norm(target.bootstrapReasoning)===norm(expected.reasoning)
                      &&norm(target.continuationReasoning)===norm(expected.reasoning)))
                    &&(expected.mode==='chat'||norm(target.model)===norm(expected.model));
                  const diagnostics=(outcome,reason,observed='unknown')=>({
                    hybridProfileGate:true,hybridProfileBoundary:BOUNDARY,
                    hybridProfileTarget:desired,hybridProfileObserved:observed,
                    hybridProfileOutcome:outcome,hybridProfileReason:reason
                  });
                  try{bridge.selectStage('continuation');}
                  catch(_){return result('HYBRID_PROFILE_UNAVAILABLE','continuation profile activation failed',
                    diagnostics('blocked','profile_activation_failed'));}
                  if(bridge.stage?.()!=='continuation')
                    return result('HYBRID_PROFILE_UNAVAILABLE','continuation stage readback failed',
                      diagnostics('blocked','stage_readback_failed'));
                  const target=bridge.target();
                  if(!profileMatches(target,endpoint))
                    return result('HYBRID_PROFILE_UNAVAILABLE','continuation profile readback failed',
                      diagnostics('blocked','profile_readback_failed',norm(target?.mode)||'unknown'));
                  let forwarded;
                  try{forwarded=(__ACTION__);}catch(_){
                    return result('SCRIPT_ERROR','continuation action failed',
                      diagnostics('blocked','action_exception',desired));
                  }
                  try{
                    const parsed=typeof forwarded==='string'?JSON.parse(forwarded):forwarded;
                    if(!parsed||typeof parsed!=='object'||Array.isArray(parsed))
                      return result('SCRIPT_ERROR','continuation action result invalid',
                        diagnostics('blocked','action_result_invalid',desired));
                    const prior=parsed.diagnostics&&typeof parsed.diagnostics==='object'
                      &&!Array.isArray(parsed.diagnostics)?parsed.diagnostics:{};
                    parsed.diagnostics={...prior,...diagnostics('verified','profile_readback',desired)};
                    return JSON.stringify(parsed);
                  }catch(_){
                    return result('SCRIPT_ERROR','continuation action result unreadable',
                      diagnostics('blocked','action_result_unreadable',desired));
                  }
                })()
                """;
        return script
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
                  const endpointMatches=(t,e)=>!!t&&t.ready===true&&t.runId===RUN_ID&&t.mode===e.mode
                    &&norm(t.reasoning)===e.reasoning
                    &&(e.mode!=='chat'||(norm(t.bootstrapReasoning)===e.reasoning
                      &&norm(t.continuationReasoning)===e.reasoning))
                    &&(e.mode==='chat'||norm(t.model)===e.model);
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
                  window.__selfRunHybridProfileBridge={version:__VERSION__,runId:RUN_ID,stage:()=>stage,selectStage,lastDecision:()=>({...lastDecision}),bootstrap:()=>({...BOOTSTRAP}),continuation:()=>({...CONTINUATION}),target:()=>({...engine.target()})};
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
