package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/** Adds Work-only native admission, subframe relay, and Service Worker message capture. */
final class WorkProtocolTransportCaptureScript {
    static final String ENGINE_VERSION = "work-transport-capture-v1";
    private static final Set<String> CHATGPT_ORIGINS = Set.of(
            "https://chatgpt.com", "https://www.chatgpt.com");

    private WorkProtocolTransportCaptureScript() {}

    static void installDocumentStart(WebView webView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            throw new IllegalStateException("DOCUMENT_START_SCRIPT unsupported: fail closed");
        }
        WebViewCompat.addDocumentStartJavaScript(webView, documentStartScript(), CHATGPT_ORIGINS);
    }

    static String documentStartScript() {
        return """
                (()=>{
                  const ENGINE_VERSION=__ENGINE_VERSION__,RELAY='selfrun-work-relay-v1',DUPLICATE_MS=750;
                  if(window.__selfRunWorkProtocolTransportCapture?.version===ENGINE_VERSION)return;
                  const safe=value=>String(value??'').replace(/\\s+/g,' ').trim().slice(0,256);
                  const token=(value,max=80)=>safe(value).replace(/[^A-Za-z0-9_.:/,-]/g,'_').slice(0,max);
                  const target=()=>{try{return window.__selfRunRequestProfileEngine?.target?.()||null;}catch(_){return null;}};
                  const workMode=()=>safe(target()?.mode).toLowerCase()==='work';
                  const runId=()=>safe(target()?.runId||'');
                  const protocol=()=>{try{return window.__selfRunTurnProtocol||null;}catch(_){return null;}};
                  const ingress=()=>{try{return window.__selfRunWorkTurnProtocolIngress||null;}catch(_){return null;}};
                  const mainFrame=()=>{try{return window===window.top;}catch(_){return false;}};
                  const relayOrigin=()=>{try{return window.top.location.origin||location.origin;}catch(_){return location.origin;}};
                  const phase=()=>{try{return safe(protocol()?.snapshot?.()?.phase||'IDLE');}catch(_){return'IDLE';}};
                  const counters={fetchWrapperPasses:0,xhrWrapperPasses:0,serviceWorkerMessages:0,postMessages:0};
                  let telemetryDirty=false,flushTimer=0;
                  const postDiagnostic=(stage,fields={})=>{
                    if(!workMode())return false;
                    try{
                      const sink=window.selfRunTurnLog,id=runId();if(!sink||typeof sink.postMessage!=='function'||!id)return false;
                      const item={runId:id,stage:safe(stage),phase:phase(),source:token(fields.source||'capture',48)};
                      for(const key of ['transport','route','semantic','binding','transition','completion','outcome','reason'])if(fields[key]!=null&&safe(fields[key]))item[key]=token(fields[key],160);
                      if(typeof fields.serviceWorkerControllerSeen==='boolean')item.serviceWorkerControllerSeen=fields.serviceWorkerControllerSeen;
                      sink.postMessage(JSON.stringify(item));counters.postMessages++;return true;
                    }catch(_){return false;}
                  };
                  const flushDiagnostics=(reason='timer')=>{
                    if(flushTimer){clearTimeout(flushTimer);flushTimer=0;}if(!telemetryDirty||!workMode())return false;telemetryDirty=false;
                    return postDiagnostic('WORK_PROTOCOL_TRANSPORT',{source:'capture_aggregate',transport:'aggregate',outcome:'coalesced_'+token(reason,24)});
                  };
                  const scheduleFlush=()=>{if(flushTimer||!telemetryDirty)return;flushTimer=setTimeout(()=>{flushTimer=0;flushDiagnostics('periodic');},2500);};
                  const diagnostic=(stage,fields={})=>{
                    if(!workMode())return;if(stage==='WORK_PROTOCOL_DECODE_ERROR'||stage==='WORK_PROTOCOL_TRANSITION'){postDiagnostic(stage,fields);if(stage==='WORK_PROTOCOL_TRANSITION')flushDiagnostics('transition');return;}
                    telemetryDirty=true;scheduleFlush();
                  };
                  let envLogged=false,admission={runId:'',at:0,source:''};
                  const emitEnvironment=()=>{
                    if(envLogged||!mainFrame()||!workMode())return;envLogged=true;
                    diagnostic('WORK_PROTOCOL_ENV',{source:'document',serviceWorkerControllerSeen:!!navigator.serviceWorker?.controller});
                  };
                  const canonicalConversation=(method,url)=>{
                    if(String(method??'').toUpperCase()!=='POST')return false;
                    try{const parsed=new URL(url,location.href);if(parsed.origin!==location.origin)return false;
                      let path=parsed.pathname;if(path.length>1)path=path.replace(/\\/+$/,'');return path==='/backend-api/f/conversation';}
                    catch(_){return false;}
                  };
                  const requestMeta=(input,init)=>{
                    try{const req=typeof Request!=='undefined'&&input instanceof Request;
                      return{method:String(init?.method||(req?input.method:'GET')||'GET').toUpperCase(),url:String(req?input.url:input??'')};}
                    catch(_){return{method:'',url:''};}
                  };
                  const recordMainCanonical=source=>{
                    if(!mainFrame()||!workMode())return false;emitEnvironment();admission={runId:runId(),at:Date.now(),source};
                    diagnostic('WORK_PROTOCOL_TRANSPORT',{source,transport:source,route:'canonical_conversation',outcome:'canonical_request'});return true;
                  };
                  const acceptCanonical=(source,expectedRun)=>{
                    if(!mainFrame()||!workMode()||!runId()||safe(expectedRun||runId())!==runId())return false;emitEnvironment();
                    const now=Date.now(),current=phase(),sameWindow=admission.runId===runId()&&now-admission.at<=DUPLICATE_MS;
                    if(sameWindow&&(admission.source.startsWith('main_')||['THINKING','ANSWERING'].includes(current))){
                      diagnostic('WORK_PROTOCOL_TRANSPORT',{source,transport:source,route:'canonical_conversation',outcome:'duplicate_observation'});return false;}
                    admission={runId:runId(),at:now,source};diagnostic('WORK_PROTOCOL_TRANSPORT',{source,transport:source,route:'canonical_conversation',outcome:'canonical_request'});
                    const adapter=ingress();if(!adapter||typeof adapter.observeRequest!=='function')return false;
                    try{return !!adapter.observeRequest('POST',location.origin+'/backend-api/f/conversation');}
                    catch(_){diagnostic('WORK_PROTOCOL_DECODE_ERROR',{source,reason:'canonical_relay_failed'});return false;}
                  };
                  const minimalMessage=message=>{
                    if(!message||typeof message!=='object')return null;const out={};
                    if(message.id)out.id=safe(message.id);if(message.author?.role)out.author={role:token(message.author.role,32)};
                    if(message.channel)out.channel=token(message.channel,32);if(message.status)out.status=token(message.status,48);
                    if(message.end_turn===true)out.end_turn=true;return Object.keys(out).length?out:null;
                  };
                  const minimalSemantic=node=>{
                    if(!node||typeof node!=='object'||Array.isArray(node))return null;const out={};
                    for(const key of ['conversation_id','turn_id','type','marker','event','message_id','status'])if(node[key]!=null)out[key]=safe(node[key]);
                    if(node.end_turn===true)out.end_turn=true;if(node.author?.role)out.author={role:token(node.author.role,32)};if(node.channel)out.channel=token(node.channel,32);if(node.id)out.id=safe(node.id);
                    const message=minimalMessage(node.message);if(message)out.message=message;const delta=minimalMessage(node.v?.message);if(delta)out.v={message:delta};
                    if(!out.message&&!out.v?.message&&out.author?.role)out.message=minimalMessage(node);return Object.keys(out).length?out:null;
                  };
                  const parseSemanticSse=text=>{
                    const normalized=String(text??'').replace(/\\r\\n?/g,'\\n');
                    for(const block of normalized.split(/\\n\\n+/)){const data=[];
                      for(const line of block.split('\\n')){if(line==='data:')data.push('');else if(line.startsWith('data:'))data.push(line.slice(5).replace(/^ /,''));}
                      const value=data.join('\\n').trim();if(!value||value==='[DONE]')continue;try{return JSON.parse(value);}catch(_){}}
                    return null;
                  };
                  const relay=(kind,payload,source)=>{
                    if(mainFrame()||!workMode()||!runId()||!payload)return false;
                    try{window.top.postMessage({relay:RELAY,version:1,runId:runId(),kind,source:token(source,48),payload},relayOrigin());return true;}catch(_){return false;}
                  };
                  const handleRelayedSemantic=(payload,source,expectedRun)=>{
                    if(!mainFrame()||!workMode()||safe(expectedRun)!==runId())return false;
                    const minimal=minimalSemantic(payload);if(!minimal)return false;emitEnvironment();const p=protocol();if(!p||typeof p.observeSseText!=='function')return false;
                    const before=phase(),context={conversationId:safe(minimal.conversation_id||''),workTurnId:safe(minimal.turn_id||'')};
                    try{p.observeSseText('data: '+JSON.stringify(minimal)+'\\n\\n','work-subframe-relay',context);}catch(_){diagnostic('WORK_PROTOCOL_DECODE_ERROR',{source,reason:'semantic_relay_failed'});return false;}
                    const after=phase();diagnostic('WORK_PROTOCOL_SIGNAL',{source,semantic:token(minimal.type||minimal.status||'message',80),binding:'main_authority'});
                    if(before!==after)diagnostic('WORK_PROTOCOL_TRANSITION',{source,transition:before+'>'+after,completion:safe(p.snapshot?.()?.completionSource||'')});return true;
                  };
                  if(mainFrame()){
                    window.addEventListener('message',event=>{
                      const data=event.data;if(!workMode()||event.origin!==location.origin||!event.source||event.source===window||!data||data.relay!==RELAY||data.version!==1||safe(data.runId)!==runId())return;
                      if(data.kind==='canonical'){acceptCanonical('subframe_'+token(data.source,36),data.runId);return;}if(data.kind==='semantic')handleRelayedSemantic(data.payload,'subframe_'+token(data.source,36),data.runId);
                    });
                    if(navigator.serviceWorker?.addEventListener){
                      navigator.serviceWorker.addEventListener('message',event=>{
                        if(!workMode())return;emitEnvironment();counters.serviceWorkerMessages++;diagnostic('WORK_PROTOCOL_TRANSPORT',{source:'service_worker_message',transport:'service_worker_message',outcome:'message_received'});
                        try{void ingress()?.observeTransportData?.(event.data,'service_worker_message');}catch(_){}
                        for(const port of Array.from(event.ports||[])){try{port.addEventListener('message',portEvent=>{if(!workMode())return;counters.serviceWorkerMessages++;
                          diagnostic('WORK_PROTOCOL_TRANSPORT',{source:'service_worker_message_port',transport:'service_worker_message_port',outcome:'message_received'});
                          try{void ingress()?.observeTransportData?.(portEvent.data,'service_worker_message_port');}catch(_){};});port.start?.();}catch(_){}}
                      });
                    }
                  }else{
                    const p=protocol();if(p&&typeof p.observeSseText==='function'){
                      const downstream=p.observeSseText.bind(p);p.observeSseText=function(text,source,context){
                        if(workMode()&&String(source||'').startsWith('work-decoder-')){const node=parseSemanticSse(text),minimal=minimalSemantic(node);if(minimal&&relay('semantic',minimal,source))return p.snapshot?.()||{};}
                        return downstream(text,source,context);};
                    }
                  }
                  const downstreamFetch=window.fetch;
                  if(typeof downstreamFetch==='function'){
                    const wrappedFetch=function(...args){if(!workMode())return Reflect.apply(downstreamFetch,this,args);counters.fetchWrapperPasses++;
                      const meta=requestMeta(args[0],args[1]);if(canonicalConversation(meta.method,meta.url)){
                        if(mainFrame()){recordMainCanonical('main_fetch');try{ingress()?.observeCanonicalAdmission?.('main_fetch');}catch(_){}}
                        else relay('canonical',{route:'canonical_conversation'},'fetch');}
                      return Reflect.apply(downstreamFetch,this,args);};
                    try{Object.defineProperty(wrappedFetch,'name',{value:downstreamFetch.name});Object.defineProperty(wrappedFetch,'length',{value:downstreamFetch.length});wrappedFetch.toString=downstreamFetch.toString.bind(downstreamFetch);}catch(_){}window.fetch=wrappedFetch;
                  }
                  const downstreamOpen=XMLHttpRequest.prototype.open,downstreamSend=XMLHttpRequest.prototype.send,xhrMeta=new WeakMap();
                  XMLHttpRequest.prototype.open=function(method,url,...rest){if(workMode()){counters.xhrWrapperPasses++;xhrMeta.set(this,{method:String(method||''),url:String(url||'')});}return downstreamOpen.call(this,method,url,...rest);};
                  XMLHttpRequest.prototype.send=function(body){const meta=xhrMeta.get(this)||{method:'',url:''};
                    if(workMode()&&canonicalConversation(meta.method,meta.url)){
                      if(mainFrame()){recordMainCanonical('main_xhr');try{void ingress()?.observeRequest?.(meta.method,meta.url);}catch(_){}}
                      else relay('canonical',{route:'canonical_conversation'},'xhr');}
                    return downstreamSend.call(this,body);};
                  const observeNativeCanonical=(source,expectedRun)=>acceptCanonical(token(source,48),safe(expectedRun));
                  const observeServiceWorkerData=data=>{if(!mainFrame()||!workMode())return false;emitEnvironment();counters.serviceWorkerMessages++;
                    diagnostic('WORK_PROTOCOL_TRANSPORT',{source:'service_worker_message',transport:'service_worker_message',outcome:'message_received'});
                    try{void ingress()?.observeTransportData?.(data,'service_worker_message');return true;}catch(_){return false;}};
                  const observeServiceWorkerPortData=data=>{if(!mainFrame()||!workMode())return false;emitEnvironment();counters.serviceWorkerMessages++;
                    diagnostic('WORK_PROTOCOL_TRANSPORT',{source:'service_worker_message_port',transport:'service_worker_message_port',outcome:'message_received'});
                    try{void ingress()?.observeTransportData?.(data,'service_worker_message_port');return true;}catch(_){return false;}};
                  window.__selfRunWorkProtocolTransportCapture={version:ENGINE_VERSION,observeNativeCanonical,observeServiceWorkerData,observeServiceWorkerPortData,flushDiagnostics,
                    observeRelayedSemantic:(payload,source='fixture')=>handleRelayedSemantic(payload,source,runId()),
                    diagnostics:()=>({mode:safe(target()?.mode),mainFrame:mainFrame(),runId:runId(),admission:{...admission},...counters})};
                  emitEnvironment();
                })();
                """.replace("__ENGINE_VERSION__", SelfRunScript.quote(ENGINE_VERSION));
    }
}
