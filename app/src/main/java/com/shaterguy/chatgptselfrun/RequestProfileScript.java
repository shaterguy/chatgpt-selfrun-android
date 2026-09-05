package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/** Document-start request profile registry executor plus one-shot outgoing submission capture. */
final class RequestProfileScript {
    static final String ENGINE_VERSION = "profile-registry-v3";
    private static final Set<String> CHATGPT_ORIGINS = Set.of(
            "https://chatgpt.com", "https://www.chatgpt.com");

    private RequestProfileScript() {}

    static String engineAvailableExpression() {
        return "window.__selfRunRequestProfileEngine?.version===" + SelfRunScript.quote(ENGINE_VERSION);
    }

    static void installDocumentStart(WebView webView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            throw new IllegalStateException("DOCUMENT_START_SCRIPT unsupported: fail closed");
        }
        WebViewCompat.addDocumentStartJavaScript(webView, documentStartScript(), CHATGPT_ORIGINS);
    }

    static String syncRegistry() {
        return "window.__selfRunRequestProfileEngine.installRegistry("
                + SelfRunScript.quote(ProfileRegistry.runtimeJson()) + ");";
    }

    static String beginTarget(String mode, String runId) {
        return syncRegistry() + call("begin", mode, runId);
    }

    static String setChatReasoning(String reasoning) {
        return syncRegistry() + call("setChatReasoning", reasoning);
    }

    static String setChatProfiles(String bootstrapReasoning, String continuationReasoning) {
        return syncRegistry() + call("setChatProfiles", bootstrapReasoning, continuationReasoning);
    }

    static String setWorkModel(String model) {
        return syncRegistry() + call("setWorkModel", model);
    }

    static String setWorkReasoning(String reasoning) {
        return syncRegistry() + call("setWorkReasoning", reasoning);
    }

    static String armCapture(String mode) {
        return syncRegistry() + call("armCapture", mode);
    }

    static String cancelCapture() {
        return "window.__selfRunRequestProfileEngine?.cancelCapture();";
    }

    static String consumeCapture() {
        return "JSON.stringify(window.__selfRunRequestProfileEngine?.consumeCapture()||null)";
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
                  if(window.__selfRunRequestProfileEngine?.version===__ENGINE_VERSION__)return;
                  const CONTROL=['model','thinking_effort','conversation_origin','service_tier'];
                  const REGISTRY_STORE='selfrun-drive:profile-registry-runtime:v1';
                  const TARGET_STORE='selfrun-drive:request-profile-target:v3';
                  const state={registry:[],target:null,capture:{armed:false,mode:'',value:null},last:{ok:false,reason:'not_attempted'}};
                  const norm=v=>String(v??'').trim().toLowerCase();
                  const fail=reason=>{state.last={ok:false,reason:String(reason||'profile_failure').slice(0,160)};throw new Error('SELFRUN_PROFILE:'+state.last.reason);};
                  const own=(o,k)=>Object.prototype.hasOwnProperty.call(o,k);
                  const safeToken=v=>/^[a-z0-9][a-z0-9._:-]{0,79}$/.test(norm(v));
                  const normalizeOp=op=>{
                    if(!op||typeof op!=='object'||Array.isArray(op))fail('registry_operation_invalid');
                    const kind=String(op.op||'').toUpperCase(),path=String(op.path||'');
                    if(!CONTROL.includes(path)||(kind!=='SET'&&kind!=='REMOVE'))fail('registry_operation_invalid');
                    if(kind==='SET'&&typeof op.value!=='string')fail('registry_operation_value_invalid');
                    if(kind==='SET'&&op.value.length>512)fail('registry_operation_value_too_long');
                    return kind==='SET'?{op:kind,path,value:op.value}:{op:kind,path};
                  };
                  const normalizeProfile=p=>{
                    if(!p||typeof p!=='object'||Array.isArray(p))fail('registry_profile_invalid');
                    const mode=norm(p.mode),signalModel=norm(p.signalModel),signalReasoning=norm(p.signalReasoning);
                    if(mode!=='chat'&&mode!=='work')fail('registry_mode_invalid');
                    if(!safeToken(signalReasoning)||(mode==='work'&&!safeToken(signalModel))||(mode==='chat'&&signalModel!==''))fail('registry_signal_invalid');
                    if(!Array.isArray(p.operations)||p.operations.length!==CONTROL.length)fail('registry_operations_incomplete');
                    const operations=p.operations.map(normalizeOp),seen=new Set(operations.map(op=>op.path));
                    if(seen.size!==CONTROL.length||CONTROL.some(path=>!seen.has(path)))fail('registry_operations_incomplete');
                    const ordered=CONTROL.map(path=>operations.find(op=>op.path===path));
                    const model=ordered.find(op=>op.path==='model');
                    if(!model||model.op!=='SET'||!model.value)fail('registry_model_missing');
                    return{mode,signalModel,signalReasoning,operations:ordered,fingerprint:String(p.fingerprint||'')};
                  };
                  const resolveProfile=(mode,model,reasoning)=>{
                    const m=norm(model),r=norm(reasoning);
                    return state.registry.find(p=>p.mode===mode&&p.signalReasoning===r&&(mode==='chat'||p.signalModel===m))||null;
                  };
                  const targetValid=t=>{
                    if(!t||typeof t!=='object'||Array.isArray(t)||typeof t.runId!=='string'||t.runId.length>128)return false;
                    if(t.mode==='chat'){
                      if(t.ready===false)return t.model===''&&t.reasoning===''&&norm(t.bootstrapReasoning)===''&&norm(t.continuationReasoning)==='';
                      const b=norm(t.bootstrapReasoning||t.reasoning),c=norm(t.continuationReasoning||t.reasoning);
                      return !!resolveProfile('chat','',b)&&!!resolveProfile('chat','',c)&&t.ready===true;
                    }
                    if(t.mode==='work'){
                      if(t.ready===false&&t.model===''&&t.reasoning==='')return true;
                      if(t.ready===false&&safeToken(t.model)&&t.reasoning==='')return state.registry.some(p=>p.mode==='work'&&p.signalModel===t.model);
                      return !!resolveProfile('work',t.model,t.reasoning)&&t.ready===true;
                    }
                    return false;
                  };
                  const persistRegistry=()=>{try{localStorage.setItem(REGISTRY_STORE,JSON.stringify(state.registry));}catch(_){}};
                  const restoreRegistry=()=>{try{const raw=localStorage.getItem(REGISTRY_STORE);if(!raw)return[];const list=JSON.parse(raw);if(!Array.isArray(list))return[];return list.map(normalizeProfile);}catch(_){return[];}};
                  const persistTarget=()=>{try{if(targetValid(state.target))localStorage.setItem(TARGET_STORE,JSON.stringify(state.target));else localStorage.removeItem(TARGET_STORE);}catch(_){}};
                  const restoreTarget=()=>{try{const raw=localStorage.getItem(TARGET_STORE);if(!raw)return null;const t=JSON.parse(raw);if(!targetValid(t)){localStorage.removeItem(TARGET_STORE);return null;}return{mode:t.mode,model:t.model,reasoning:t.reasoning,bootstrapReasoning:norm(t.bootstrapReasoning||t.reasoning),continuationReasoning:norm(t.continuationReasoning||t.reasoning),runId:t.runId,ready:t.ready};}catch(_){return null;}};
                  state.registry=restoreRegistry();
                  state.target=restoreTarget();
                  if(state.target)state.last={ok:true,reason:'target_restored',mode:state.target.mode,model:state.target.model,reasoning:state.target.reasoning,bootstrapReasoning:state.target.bootstrapReasoning,continuationReasoning:state.target.continuationReasoning};
                  const installRegistry=raw=>{
                    let list;try{list=typeof raw==='string'?JSON.parse(raw):raw;}catch(_){fail('registry_json_invalid');}
                    if(!Array.isArray(list))fail('registry_json_invalid');
                    const normalized=list.map(normalizeProfile),keys=new Set();
                    for(const p of normalized){const key=p.mode+'|'+p.signalModel+'|'+p.signalReasoning;if(keys.has(key))fail('registry_signal_duplicate');keys.add(key);}
                    state.registry=normalized;persistRegistry();
                    if(state.target&&!targetValid(state.target)){state.target=null;persistTarget();state.last={ok:false,reason:'target_deleted_or_unsupported'};}
                    return true;
                  };
                  const refreshRegistry=()=>{const restored=restoreRegistry();if(restored.length||state.registry.length===0)state.registry=restored;};
                  const begin=(mode,runId)=>{refreshRegistry();const m=norm(mode);if(m!=='chat'&&m!=='work')fail('unsupported_mode');state.target={mode:m,model:'',reasoning:'',bootstrapReasoning:'',continuationReasoning:'',runId:String(runId||'').slice(0,128),ready:false};persistTarget();state.last={ok:true,reason:'target_begun',mode:m};return true;};
                  const requireTarget=mode=>{const t=state.target;if(!t||t.mode!==mode)fail('target_mode_not_initialized');return t;};
                  const setChatProfiles=(bootstrapReasoning,continuationReasoning)=>{refreshRegistry();const t=requireTarget('chat'),b=norm(bootstrapReasoning),c=norm(continuationReasoning);if(!resolveProfile('chat','',b))fail('unsupported_chat_bootstrap_reasoning');if(!resolveProfile('chat','',c))fail('unsupported_chat_continuation_reasoning');t.model='';t.reasoning=b;t.bootstrapReasoning=b;t.continuationReasoning=c;t.ready=true;persistTarget();state.last={ok:true,reason:'target_ready',mode:'chat',reasoning:b,bootstrapReasoning:b,continuationReasoning:c};return true;};
                  const setChatReasoning=reasoning=>setChatProfiles(reasoning,reasoning);
                  const setWorkModel=model=>{refreshRegistry();const t=requireTarget('work'),m=norm(model);if(!safeToken(m)||!state.registry.some(p=>p.mode==='work'&&p.signalModel===m))fail('unsupported_work_model');t.model=m;t.reasoning='';t.ready=false;persistTarget();state.last={ok:true,reason:'work_model_set',mode:'work',model:m};return true;};
                  const setWorkReasoning=reasoning=>{refreshRegistry();const t=requireTarget('work'),r=norm(reasoning);if(!t.model)fail('work_model_missing');if(!resolveProfile('work',t.model,r))fail('unsupported_work_profile');t.reasoning=r;t.ready=true;persistTarget();state.last={ok:true,reason:'target_ready',mode:'work',model:t.model,reasoning:r};return true;};
                  const latestMessageText=body=>{try{const list=Array.isArray(body?.messages)?body.messages:[];return list.length?JSON.stringify(list[list.length-1]):'';}catch(_){return'';}};
                  const chatReasoningForBody=(body,t)=>{const latest=latestMessageText(body);const bootstrap=latest.includes('SELF_RUN_BOOTSTRAP')&&(!t.runId||latest.includes(t.runId));return bootstrap?norm(t.bootstrapReasoning||t.reasoning):norm(t.continuationReasoning||t.reasoning);};
                  const targetSnapshot=()=>state.target?{mode:state.target.mode,model:state.target.model,reasoning:state.target.reasoning,bootstrapReasoning:state.target.bootstrapReasoning,continuationReasoning:state.target.continuationReasoning,runId:state.target.runId,ready:state.target.ready}:null;
                  const profileForBody=(body,t)=>{refreshRegistry();if(!t||!t.ready)fail('target_not_ready');if(t.mode==='chat'){const reasoning=chatReasoningForBody(body,t),p=resolveProfile('chat','',reasoning);if(!p)fail('profile_deleted_or_unsupported');return{profile:p,effectiveReasoning:reasoning};}const p=resolveProfile('work',t.model,t.reasoning);if(!p)fail('profile_deleted_or_unsupported');return{profile:p,effectiveReasoning:t.reasoning};};
                  const sameOrigin=url=>{try{return new URL(url,location.href).origin===location.origin;}catch(_){return false;}};
                  const conversationRoute=url=>{try{let p=new URL(url,location.href).pathname.toLowerCase();if(p.length>1)p=p.replace(/\\/+$/,'');return p==='/backend-api/conversation'||p==='/backend-api/f/conversation';}catch(_){return false;}};
                  const strip=obj=>{const copy={...obj};for(const key of CONTROL)delete copy[key];return copy;};
                  const captureOperations=body=>CONTROL.map(path=>{
                    if(!own(body,path))return{op:'REMOVE',path};
                    if(typeof body[path]!=='string')fail('capture_control_non_string');
                    if(body[path].length>512)fail('capture_control_value_too_long');
                    return{op:'SET',path,value:body[path]};
                  });
                  const captureBody=body=>{
                    if(!state.capture.armed)return false;
                    if(!body||typeof body!=='object'||Array.isArray(body)||!Array.isArray(body.messages))return false;
                    const mode=state.capture.mode;state.capture.armed=false;
                    try{
                      const operations=captureOperations(body),model=operations.find(op=>op.path==='model');
                      if(!model||model.op!=='SET'||!model.value)fail('capture_model_missing');
                      state.capture.value={mode,operations};
                      state.last={ok:true,reason:'profile_captured',mode,ops:operations.map(op=>op.op+':'+op.path)};
                    }catch(error){state.capture.value=null;throw error;}
                    return true;
                  };
                  const armCapture=mode=>{const m=norm(mode);if(m!=='chat'&&m!=='work')fail('capture_mode_invalid');state.capture={armed:true,mode:m,value:null};state.last={ok:true,reason:'capture_armed',mode:m};return true;};
                  const cancelCapture=()=>{state.capture={armed:false,mode:'',value:null};state.last={ok:true,reason:'capture_cancelled'};return true;};
                  const consumeCapture=()=>{const value=state.capture.value;state.capture.value=null;return value;};
                  const parseSubmission=text=>{if(typeof text!=='string')fail('non_text_conversation_body');let body;try{body=JSON.parse(text);}catch(_){fail('invalid_conversation_json');}if(!body||typeof body!=='object'||Array.isArray(body)||!Array.isArray(body.messages))fail('unknown_conversation_schema');return body;};
                  const patchObject=(body,t)=>{const before=JSON.stringify(strip(body)),out={...body},planned=profileForBody(body,t),ops=planned.profile.operations;for(const op of ops){if(!CONTROL.includes(op.path))fail('control_allowlist_violation');if(op.op==='SET')out[op.path]=op.value;else if(op.op==='REMOVE')delete out[op.path];else fail('unknown_operation');}if(JSON.stringify(strip(out))!==before)fail('data_plane_changed');state.last={ok:true,reason:'patched',mode:t.mode,model:t.model,reasoning:planned.effectiveReasoning,bootstrapReasoning:t.bootstrapReasoning,continuationReasoning:t.continuationReasoning,ops:ops.map(op=>op.op+':'+op.path),schema:'messages-array'};return out;};
                  const nativeFetch=window.fetch.bind(window);
                  const fetchProbe=(input,init)=>{try{const isReq=typeof Request!=='undefined'&&input instanceof Request;const url=isReq?input.url:String(input??'');const method=init&&init.method!==undefined?init.method:(isReq?input.method:'GET');return{url,method,eligible:norm(method)==='post'&&sameOrigin(url)&&conversationRoute(url)};}catch(_){return{url:'',method:'',eligible:false};}};
                  window.fetch=async function(input,init){
                    const probe=fetchProbe(input,init);if(!probe.eligible)return nativeFetch(input,init);
                    const target=targetSnapshot();
                    let request;try{const source=typeof Request!=='undefined'&&input instanceof Request?input.clone():input;request=new Request(source,init);}catch(_){try{fail('request_construction_failed');}catch(error){return Promise.reject(error);}}
                    let text='';try{text=await request.clone().text();}catch(_){try{fail('request_body_unreadable');}catch(error){return Promise.reject(error);}}
                    let body;try{body=parseSubmission(text);}catch(error){return Promise.reject(error);}
                    if(state.capture.armed){try{captureBody(body);}catch(error){return Promise.reject(error);}return nativeFetch(input,init);}
                    let patched;try{patched=JSON.stringify(patchObject(body,target));}catch(error){return Promise.reject(error);}
                    try{return nativeFetch(new Request(request,{body:patched}));}catch(_){try{fail('patched_request_construction_failed');}catch(error){return Promise.reject(error);}}
                  };
                  const nativeOpen=XMLHttpRequest.prototype.open,nativeSend=XMLHttpRequest.prototype.send,meta=new WeakMap();
                  XMLHttpRequest.prototype.open=function(method,url,...rest){meta.set(this,{method:String(method||''),url:String(url||'')});return nativeOpen.call(this,method,url,...rest);};
                  XMLHttpRequest.prototype.send=function(body){const m=meta.get(this)||{method:'',url:''};if(norm(m.method)!=='post'||!sameOrigin(m.url)||!conversationRoute(m.url))return nativeSend.call(this,body);let parsed=parseSubmission(body);if(state.capture.armed){captureBody(parsed);return nativeSend.call(this,body);}return nativeSend.call(this,JSON.stringify(patchObject(parsed,targetSnapshot())));};
                  window.__selfRunRequestProfileEngine={version:__ENGINE_VERSION__,installRegistry,begin,setChatReasoning,setChatProfiles,setWorkModel,setWorkReasoning,armCapture,cancelCapture,consumeCapture,diagnostics:()=>({...state.last}),target:targetSnapshot};
                })();
                """.replace("__ENGINE_VERSION__", SelfRunScript.quote(ENGINE_VERSION));
    }
}
