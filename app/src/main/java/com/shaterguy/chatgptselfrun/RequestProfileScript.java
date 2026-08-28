package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/** Document-start fetch/XHR interceptor and target-profile bridge for SelfRun 2.0. */
final class RequestProfileScript {
    private static final Set<String> CHATGPT_ORIGINS = Set.of(
            "https://chatgpt.com", "https://www.chatgpt.com");

    private RequestProfileScript() {}

    static void installDocumentStart(WebView webView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            throw new IllegalStateException("DOCUMENT_START_SCRIPT unsupported: fail closed");
        }
        WebViewCompat.addDocumentStartJavaScript(webView, documentStartScript(), CHATGPT_ORIGINS);
    }

    static String beginTarget(String mode, String runId) {
        return call("begin", mode, runId);
    }

    static String setChatReasoning(String reasoning) {
        return call("setChatReasoning", reasoning);
    }

    static String setWorkModel(String model) {
        return call("setWorkModel", model);
    }

    static String setWorkReasoning(String reasoning) {
        return call("setWorkReasoning", reasoning);
    }

    private static String call(String method, String... values) {
        StringBuilder out = new StringBuilder("window.__selfRunRequestProfileEngine.")
                .append(method).append('(');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(SelfRunScript.quote(values[i] == null ? "" : values[i]));
        }
        return out.append(");").toString();
    }

    static String documentStartScript() {
        return """
                (()=>{
                  if(window.__selfRunRequestProfileEngine?.version==='calibration-v2')return;
                  const CONTROL=['model','thinking_effort','conversation_origin','service_tier'];
                  const state={target:null,last:{ok:false,reason:'not_attempted'}};
                  const fail=reason=>{state.last={ok:false,reason:String(reason||'profile_failure').slice(0,160)};throw new Error('SELFRUN_PROFILE:'+state.last.reason);};
                  const norm=v=>String(v??'').trim().toLowerCase();
                  const begin=(mode,runId)=>{const m=norm(mode);if(m!=='chat'&&m!=='work')fail('unsupported_mode');state.target={mode:m,model:'',reasoning:'',runId:String(runId||'').slice(0,128),profileVersion:'chatgpt-request-snapshot-calibration-v1@2026-08-28',ready:false};state.last={ok:true,reason:'target_begun',mode:m};return true;};
                  const requireTarget=mode=>{const t=state.target;if(!t||t.mode!==mode)fail('target_mode_not_initialized');return t;};
                  const setChatReasoning=reasoning=>{const t=requireTarget('chat'),r=norm(reasoning);if(['pro','pro_standard','pro_extended'].includes(r))fail('chat_pro_uncaptured');if(!['instant','medium','high','xhigh'].includes(r))fail('unsupported_chat_reasoning');t.model='chat';t.reasoning=r;t.ready=true;state.last={ok:true,reason:'target_ready',mode:'chat',model:'chat',reasoning:r};return true;};
                  const setWorkModel=model=>{const t=requireTarget('work'),m=norm(model).replace(/^5\\.6\\s+/,'');if(!['sol','terra','luna'].includes(m))fail('unsupported_work_model');t.model=m;t.reasoning='';t.ready=false;state.last={ok:true,reason:'work_model_set',mode:'work',model:m};return true;};
                  const setWorkReasoning=reasoning=>{const t=requireTarget('work'),r=norm(reasoning).replace('extra high','xhigh').replace('extra_high','xhigh');if(!t.model)fail('work_model_missing');if(!['light','medium','high','xhigh','max','ultra'].includes(r))fail('unsupported_work_reasoning');if(t.model==='luna'&&r==='ultra')fail('luna_ultra_unsupported');t.reasoning=r;t.ready=true;state.last={ok:true,reason:'target_ready',mode:'work',model:t.model,reasoning:r};return true;};
                  const plan=()=>{const t=state.target;if(!t||!t.ready)fail('target_not_ready');if(t.profileVersion!=='chatgpt-request-snapshot-calibration-v1@2026-08-28')fail('profile_version_mismatch');if(t.mode==='chat'){
                    const effort={medium:'standard',high:'extended',xhigh:'max'}[t.reasoning];
                    if(t.reasoning==='instant')return [['set','model','gpt-5-6'],['remove','thinking_effort'],['remove','conversation_origin'],['remove','service_tier']];
                    if(!effort)fail('unsupported_chat_reasoning');
                    return [['set','model','gpt-5-6-thinking'],['set','thinking_effort',effort],['remove','conversation_origin'],['remove','service_tier']];
                  }
                  const model={sol:'gpt-5.6-sol-wm',terra:'gpt-5.6-terra-wm',luna:'gpt-5.6-luna-wm'}[t.model];
                  const effort={light:'min',medium:'standard',high:'extended',xhigh:'xhigh',max:'max',ultra:'ultra'}[t.reasoning];
                  if(!model||!effort)fail('unsupported_work_profile');if(t.model==='luna'&&t.reasoning==='ultra')fail('luna_ultra_unsupported');
                  return [['set','model',model],['set','thinking_effort',effort],['set','conversation_origin','tpp'],['set','service_tier','standard']];};
                  const sameOrigin=url=>{try{return new URL(url,location.href).origin===location.origin;}catch(_){return false;}};
                  const conversationRoute=url=>{try{let p=new URL(url,location.href).pathname.toLowerCase();if(p.length>1)p=p.replace(/\\/+$/,'');return p==='/backend-api/conversation'||p==='/backend-api/f/conversation';}catch(_){return false;}};
                  const strip=obj=>{const copy={...obj};for(const key of CONTROL)delete copy[key];return copy;};
                  const patchObject=(body,url)=>{if(!conversationRoute(url))fail('conversation_route_not_allowed');if(!body||typeof body!=='object'||Array.isArray(body))fail('unknown_conversation_schema');if(!Array.isArray(body.messages))fail('unknown_conversation_schema');const before=JSON.stringify(strip(body));const out={...body};const ops=plan();for(const [kind,path,value] of ops){if(!CONTROL.includes(path))fail('control_allowlist_violation');if(kind==='set')out[path]=value;else if(kind==='remove')delete out[path];else fail('unknown_operation');}if(JSON.stringify(strip(out))!==before)fail('data_plane_changed');const t=state.target;state.last={ok:true,reason:'patched',mode:t.mode,model:t.model,reasoning:t.reasoning,ops:ops.map(op=>op[0]+':'+op[1]),schema:'messages-array'};return out;};
                  const patchText=(url,method,text)=>{if(norm(method)!=='post'||!sameOrigin(url)||!conversationRoute(url))return null;if(typeof text!=='string')fail('non_text_conversation_body');let body;try{body=JSON.parse(text);}catch(_){fail('invalid_conversation_json');}return JSON.stringify(patchObject(body,url));};
                  const nativeFetch=window.fetch.bind(window);
                  const fetchProbe=(input,init)=>{try{const requestInput=typeof Request!=='undefined'&&input instanceof Request;const url=requestInput?input.url:String(input??'');const method=init&&init.method!==undefined?init.method:(requestInput?input.method:'GET');return{url,method,eligible:norm(method)==='post'&&sameOrigin(url)&&conversationRoute(url)};}catch(_){return{url:'',method:'',eligible:false};}};
                  window.fetch=async function(input,init){
                    const probe=fetchProbe(input,init);if(!probe.eligible)return nativeFetch(input,init);
                    let request;try{const source=typeof Request!=='undefined'&&input instanceof Request?input.clone():input;request=new Request(source,init);}catch(_){try{fail('request_construction_failed');}catch(error){return Promise.reject(error);}}
                    let text='';try{text=await request.clone().text();}catch(_){try{fail('request_body_unreadable');}catch(error){return Promise.reject(error);}}
                    let patched;try{patched=patchText(request.url,request.method,text);}catch(error){return Promise.reject(error);}
                    if(patched===null)return nativeFetch(input,init);
                    try{return nativeFetch(new Request(request,{body:patched}));}catch(_){try{fail('patched_request_construction_failed');}catch(error){return Promise.reject(error);}}
                  };
                  const nativeOpen=XMLHttpRequest.prototype.open,nativeSend=XMLHttpRequest.prototype.send;
                  const meta=new WeakMap();
                  XMLHttpRequest.prototype.open=function(method,url,...rest){meta.set(this,{method:String(method||''),url:String(url||'')});return nativeOpen.call(this,method,url,...rest);};
                  XMLHttpRequest.prototype.send=function(body){const m=meta.get(this)||{method:'',url:''};let patched=null;try{patched=patchText(m.url,m.method,body);}catch(error){throw error;}return nativeSend.call(this,patched===null?body:patched);};
                  window.__selfRunRequestProfileEngine={version:'calibration-v2',begin,setChatReasoning,setWorkModel,setWorkReasoning,diagnostics:()=>({...state.last}),target:()=>state.target?{mode:state.target.mode,model:state.target.model,reasoning:state.target.reasoning,profileVersion:state.target.profileVersion,ready:state.target.ready}:null};
                })();
                """;
    }
}
