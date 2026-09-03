package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/** HYBRID first-submission bootstrap -> durable continuation request-profile switch. */
final class HybridRequestProfileScript {
    private static final String VERSION = "hybrid-request-profile-v2";
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

    static String documentStartScript(HybridRunProfileStore.Selection selection) {
        if (selection == null || !selection.valid()) throw new IllegalArgumentException("valid HYBRID selection required");
        HybridRunProfileStore.Endpoint bootstrap = selection.bootstrap;
        HybridRunProfileStore.Endpoint continuation = selection.continuation;
        boolean initialContinuation = selection.continuationStage();
        return """
                (()=>{
                  if(window.__selfRunHybridProfileBridge?.version===__VERSION__)return;
                  const RUN_ID=__RUN_ID__;
                  const REGISTRY=__REGISTRY__;
                  const BOOTSTRAP=__BOOTSTRAP__;
                  const CONTINUATION=__CONTINUATION__;
                  const SWITCH_KEY='selfrun-drive:hybrid-switched:'+RUN_ID;
                  const BOOTSTRAP_SEEN_KEY='selfrun-drive:hybrid-bootstrap-seen:'+RUN_ID;
                  const norm=v=>String(v??'').trim().toLowerCase();
                  const sameOrigin=url=>{try{return new URL(url,location.href).origin===location.origin;}catch(_){return false;}};
                  const conversationRoute=url=>{try{let p=new URL(url,location.href).pathname.toLowerCase();if(p.length>1)p=p.replace(/[/]+$/,'');return p==='/backend-api/conversation'||p==='/backend-api/f/conversation';}catch(_){return false;}};
                  let switched=__INITIAL_CONTINUATION__;
                  let bootstrapSeen=__INITIAL_CONTINUATION__;
                  try{if(localStorage.getItem(SWITCH_KEY)==='1')switched=true;if(localStorage.getItem(BOOTSTRAP_SEEN_KEY)==='1')bootstrapSeen=true;}catch(_){}
                  const markBootstrapSeen=()=>{bootstrapSeen=true;try{localStorage.setItem(BOOTSTRAP_SEEN_KEY,'1');}catch(_){}};
                  const markSwitched=()=>{bootstrapSeen=true;switched=true;try{localStorage.setItem(BOOTSTRAP_SEEN_KEY,'1');localStorage.setItem(SWITCH_KEY,'1');}catch(_){}};
                  const endpointMatches=(t,e)=>!!t&&t.ready===true&&t.mode===e.mode&&norm(t.reasoning)===e.reasoning&&(e.mode==='chat'||norm(t.model)===e.model);
                  const configure=e=>{
                    const engine=window.__selfRunRequestProfileEngine;
                    if(!engine||engine.version!==__ENGINE_VERSION__)throw new Error('SELFRUN_HYBRID:profile_engine_unavailable');
                    engine.installRegistry(REGISTRY);
                    const current=engine.target();
                    if(endpointMatches(current,e))return false;
                    engine.begin(e.mode,RUN_ID);
                    if(e.mode==='chat')engine.setChatProfiles(e.reasoning,e.reasoning);
                    else{engine.setWorkModel(e.model);engine.setWorkReasoning(e.reasoning);}
                    const next=engine.target();
                    if(!endpointMatches(next,e))throw new Error('SELFRUN_HYBRID:target_readback_mismatch');
                    return true;
                  };
                  const messageBatchText=body=>{try{return Array.isArray(body?.messages)?JSON.stringify(body.messages):'';}catch(_){return'';}};
                  const decisionForBody=body=>{
                    const batch=messageBatchText(body);
                    const ownRun=batch.includes(RUN_ID);
                    const isContinue=ownRun&&batch.includes('SELF_RUN_CONTINUE');
                    const isBootstrap=ownRun&&batch.includes('SELF_RUN_BOOTSTRAP');
                    if(switched)return{endpoint:CONTINUATION,stage:'continuation',reason:'already-switched',mark:''};
                    if(isContinue)return{endpoint:CONTINUATION,stage:'continuation',reason:'explicit-continue',mark:'switch'};
                    if(isBootstrap)return{endpoint:BOOTSTRAP,stage:'bootstrap',reason:'explicit-bootstrap',mark:'bootstrap'};
                    if(bootstrapSeen)return{endpoint:CONTINUATION,stage:'continuation',reason:'post-bootstrap-submission',mark:'switch'};
                    return{endpoint:BOOTSTRAP,stage:'bootstrap',reason:'first-submission',mark:'bootstrap'};
                  };
                  let lastDecision={stage:switched?'continuation':'bootstrap',reason:switched?'restored-continuation':'initial'};
                  const prepare=text=>{
                    let body;try{body=JSON.parse(String(text??''));}catch(_){return;}
                    if(!body||typeof body!=='object'||Array.isArray(body)||!Array.isArray(body.messages))return;
                    const decision=decisionForBody(body);
                    configure(decision.endpoint);
                    if(decision.mark==='switch')markSwitched();else if(decision.mark==='bootstrap')markBootstrapSeen();
                    lastDecision={stage:decision.stage,reason:decision.reason};
                  };
                  if(switched)try{configure(CONTINUATION);}catch(_){}
                  const innerFetch=window.fetch.bind(window);
                  window.fetch=async function(input,init){
                    let probe={eligible:false};
                    try{const isReq=typeof Request!=='undefined'&&input instanceof Request;const url=isReq?input.url:String(input??'');const method=init&&init.method!==undefined?init.method:(isReq?input.method:'GET');probe={eligible:norm(method)==='post'&&sameOrigin(url)&&conversationRoute(url)};}catch(_){}
                    if(!probe.eligible)return innerFetch(input,init);
                    let request;try{const source=typeof Request!=='undefined'&&input instanceof Request?input.clone():input;request=new Request(source,init);}catch(_){return innerFetch(input,init);}
                    let text='';try{text=await request.clone().text();}catch(_){return innerFetch(input,init);}
                    try{prepare(text);}catch(error){return Promise.reject(error);}
                    return innerFetch(input,init);
                  };
                  const innerOpen=XMLHttpRequest.prototype.open,innerSend=XMLHttpRequest.prototype.send,xhrMeta=new WeakMap();
                  XMLHttpRequest.prototype.open=function(method,url,...rest){xhrMeta.set(this,{method:String(method||''),url:String(url||'')});return innerOpen.call(this,method,url,...rest);};
                  XMLHttpRequest.prototype.send=function(body){const m=xhrMeta.get(this)||{method:'',url:''};if(norm(m.method)==='post'&&sameOrigin(m.url)&&conversationRoute(m.url)){try{prepare(body);}catch(error){throw error;}}return innerSend.call(this,body);};
                  window.__selfRunHybridProfileBridge={version:__VERSION__,runId:RUN_ID,switched:()=>switched,bootstrapSeen:()=>bootstrapSeen,lastDecision:()=>({...lastDecision}),bootstrap:()=>({...BOOTSTRAP}),continuation:()=>({...CONTINUATION})};
                })();
                """
                .replace("__VERSION__", q(VERSION))
                .replace("__RUN_ID__", q(selection.runId))
                .replace("__REGISTRY__", q(ProfileRegistry.runtimeJson()))
                .replace("__BOOTSTRAP__", endpointJson(bootstrap))
                .replace("__CONTINUATION__", endpointJson(continuation))
                .replace("__INITIAL_CONTINUATION__", initialContinuation ? "true" : "false")
                .replace("__ENGINE_VERSION__", q(RequestProfileScript.ENGINE_VERSION));
    }

    private static String endpointJson(HybridRunProfileStore.Endpoint endpoint) {
        return "{mode:" + q(endpoint.isWork() ? "work" : "chat")
                + ",model:" + q(endpoint.model) + ",reasoning:" + q(endpoint.reasoning) + "}";
    }

    private static String q(String value) { return SelfRunScript.quote(value == null ? "" : value); }
}
