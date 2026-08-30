package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/**
 * Document-start, protocol-first ChatGPT turn state observer.
 *
 * <p>It passively observes canonical conversation fetch responses and Work WebSocket frames,
 * normalizes them into FIRST/FOLLOWUP plus THINKING/ANSWERING/COMPLETE, and reuses the
 * existing verified turn-completion callback. STOP/SEND observation remains an independent
 * fallback owned by {@link SelfRunContinuationDom}.</p>
 */
final class ChatGptTurnProtocolScript {
    static final String ENGINE_VERSION = "turn-protocol-v2";
    private static final Set<String> CHATGPT_ORIGINS = Set.of(
            "https://chatgpt.com", "https://www.chatgpt.com");

    private ChatGptTurnProtocolScript() {}

    static void installDocumentStart(WebView webView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            throw new IllegalStateException("DOCUMENT_START_SCRIPT unsupported: fail closed");
        }
        WebViewCompat.addDocumentStartJavaScript(webView, documentStartScript(), CHATGPT_ORIGINS);
    }

    static String documentStartScript() {
        return """
                (()=>{
                  const ENGINE_VERSION=__ENGINE_VERSION__;
                  if(window.__selfRunTurnProtocol?.version===ENGINE_VERSION)return;
                  const COMPLETION_SCHEME=__COMPLETION_SCHEME__;
                  const COMPLETION_HOST=__COMPLETION_HOST__;
                  const STORE_KEY='selfrun-drive:turn-protocol-state:v2';
                  const VALID_PHASES=new Set(['IDLE','THINKING','ANSWERING','COMPLETE','ERROR']);
                  const blank=()=>({
                    runId:'',phase:'IDLE',turnSequence:0,turnKind:'NONE',
                    canonicalConversationId:'',temporaryConversationId:'',
                    currentTurnStartRequestId:'',currentTurnExchangeId:'',currentWorkTurnId:'',
                    sawFinalChannelToken:false,sawStreamComplete:false,completionDispatched:false,
                    completionSource:'',serverFirstTurn:null,firstTurnMismatch:false,lastError:'',lastSource:''
                  });
                  const safe=value=>String(value??'').slice(0,256);
                  const restore=()=>{
                    try{
                      const raw=sessionStorage.getItem(STORE_KEY);if(!raw)return blank();
                      const value=JSON.parse(raw);
                      if(!value||!VALID_PHASES.has(value.phase)||!Number.isInteger(value.turnSequence)||value.turnSequence<0)return blank();
                      return{...blank(),...value,turnSequence:value.turnSequence};
                    }catch(_){return blank();}
                  };
                  let state=restore(),pendingTimer=0;
                  const save=()=>{try{sessionStorage.setItem(STORE_KEY,JSON.stringify(state));}catch(_){}};
                  const emitLog=(stage,source)=>{try{const sink=window.selfRunTurnLog;if(!sink||typeof sink.postMessage!=='function')return;sink.postMessage(JSON.stringify({stage:safe(stage),source:safe(source),phase:state.phase,sequence:state.turnSequence,kind:state.turnKind}));}catch(_){}};
                  const profileTarget=()=>{try{return window.__selfRunRequestProfileEngine?.target?.()||null;}catch(_){return null;}};
                  const resetForRun=run=>{state=blank();state.runId=safe(run);save();};
                  const alignRun=()=>{
                    const run=safe(profileTarget()?.runId||'');
                    if(run&&state.runId&&run!==state.runId)resetForRun(run);
                    else if(run&&!state.runId){state.runId=run;save();}
                    return run||state.runId;
                  };
                  const snapshot=()=>JSON.parse(JSON.stringify(state));
                  const routeConversationId=()=>{
                    const parts=location.pathname.split('/').filter(Boolean),at=parts.indexOf('c');
                    return at>=0&&at+1<parts.length?safe(parts[at+1]):'';
                  };
                  const canonicalPath=url=>{
                    try{
                      const parsed=new URL(url,location.href);
                      if(parsed.origin!==location.origin)return'';
                      let path=parsed.pathname;if(path.length>1)path=path.replace(/\\/+$/,'');
                      return path==='/backend-api/f/conversation'||path==='/backend-api/f/responses'?path:'';
                    }catch(_){return'';}
                  };
                  const requestProbe=(input,init)=>{
                    try{
                      const isRequest=typeof Request!=='undefined'&&input instanceof Request;
                      const url=isRequest?input.url:String(input??'');
                      const method=String(init?.method??(isRequest?input.method:'GET')).toUpperCase();
                      const path=method==='POST'?canonicalPath(url):'';
                      return{canonical:!!path,url,path,method};
                    }catch(_){return{canonical:false,url:'',path:'',method:''};}
                  };
                  const startTurn=meta=>{
                    alignRun();
                    const previous=state.phase;
                    if(previous==='IDLE'){
                      state.turnSequence=1;state.turnKind='FIRST_TURN';
                    }else if(previous==='COMPLETE'){
                      state.turnSequence=Math.max(1,state.turnSequence+1);state.turnKind='FOLLOWUP_TURN';
                    }else if(previous==='ERROR'){
                      if(state.turnSequence<=0){state.turnSequence=1;state.turnKind='FIRST_TURN';}
                    }else return false;
                    state.phase='THINKING';
                    state.currentTurnStartRequestId=safe(meta?.requestId||'');
                    state.currentTurnExchangeId='';state.currentWorkTurnId='';
                    state.sawFinalChannelToken=false;state.sawStreamComplete=false;
                    state.completionDispatched=false;state.completionSource='';
                    state.serverFirstTurn=null;state.firstTurnMismatch=false;state.lastError='';
                    state.lastSource=safe(meta?.source||'fetch');
                    const route=routeConversationId();
                    if(route.startsWith('WEB:'))state.temporaryConversationId=route;
                    save();emitLog('turn_request','canonical_post');return true;
                  };
                  const bindConversation=id=>{
                    const value=safe(id);if(!value)return true;
                    if(!state.canonicalConversationId){state.canonicalConversationId=value;save();return true;}
                    return state.canonicalConversationId===value;
                  };
                  const bindWorkTurn=id=>{
                    const value=safe(id);if(!value)return true;
                    if(!state.currentWorkTurnId){state.currentWorkTurnId=value;save();return true;}
                    return state.currentWorkTurnId===value;
                  };
                  const activeContext=context=>{
                    const sequence=Number(context?.sequence??state.turnSequence);
                    if(sequence!==state.turnSequence)return false;
                    if(state.phase!=='THINKING'&&state.phase!=='ANSWERING')return false;
                    return bindConversation(context?.conversationId||'')&&bindWorkTurn(context?.workTurnId||'');
                  };
                  const cancelDomFallback=observer=>{
                    if(!observer)return;
                    observer.fired=true;
                    try{observer.observer?.disconnect();}catch(_){}
                    try{if(observer.timer)clearTimeout(observer.timer);}catch(_){}
                    observer.timer=0;
                    if(window.__selfRunDriveTurnObserver===observer)window.__selfRunDriveTurnObserver=null;
                  };
                  const suspendDomFallback=observer=>{
                    if(!observer)return;
                    observer.fired=true;
                    try{observer.observer?.disconnect();}catch(_){}
                    try{if(observer.timer)clearTimeout(observer.timer);}catch(_){}
                    observer.timer=0;
                  };
                  function schedulePendingDispatch(source){
                    if(pendingTimer||state.completionDispatched||state.phase!=='COMPLETE'||!state.sawFinalChannelToken)return;
                    let attempts=0;
                    const retry=()=>{
                      pendingTimer=0;
                      if(state.completionDispatched||state.phase!=='COMPLETE'||!state.sawFinalChannelToken)return;
                      if(dispatchCompletion(source,false))return;
                      attempts++;
                      if(attempts<40)pendingTimer=setTimeout(retry,50);
                    };
                    pendingTimer=setTimeout(retry,0);
                  }
                  function dispatchCompletion(source,allowRetry=true){
                    if(state.phase!=='COMPLETE'||state.completionDispatched||!state.sawFinalChannelToken)return false;
                    const run=alignRun(),observer=window.__selfRunDriveTurnObserver;
                    const token=safe(observer?.token||'');
                    if(!run||!token){if(allowRetry)schedulePendingDispatch(source);return false;}
                    state.completionDispatched=true;state.completionSource=safe(source);save();
                    emitLog('completion_dispatch',source);
                    cancelDomFallback(observer);
                    const callback=COMPLETION_SCHEME+'://'+COMPLETION_HOST
                      +'?run='+encodeURIComponent(run)+'&token='+encodeURIComponent(token)
                      +'&source='+encodeURIComponent(safe(source));
                    location.href=callback;return true;
                  }
                  const complete=source=>{
                    if(state.phase!=='THINKING'&&state.phase!=='ANSWERING')return false;
                    state.sawStreamComplete=true;
                    if(!state.sawFinalChannelToken){
                      suspendDomFallback(window.__selfRunDriveTurnObserver);
                      state.lastSource=safe(source);state.lastError='completion_before_final_channel';save();
                      emitLog('completion_ignored',source);return false;
                    }
                    state.phase='COMPLETE';
                    state.lastSource=safe(source);state.lastError='';save();
                    emitLog('complete',source);
                    dispatchCompletion(source,true);return true;
                  };
                  const markError=(reason,sequence)=>{
                    if(Number(sequence)!==state.turnSequence)return;
                    if(state.phase!=='THINKING'&&state.phase!=='ANSWERING')return;
                    suspendDomFallback(window.__selfRunDriveTurnObserver);
                    state.phase='ERROR';state.lastError=safe(reason);save();emitLog('error',reason);
                  };
                  const inspectSemantic=(value,source,context)=>{
                    if(Array.isArray(value)){for(const item of value)inspectSemantic(item,source,context);return;}
                    if(!value||typeof value!=='object'||!activeContext({
                      sequence:context?.sequence,conversationId:value.conversation_id||context?.conversationId,
                      workTurnId:value.turn_id||context?.workTurnId
                    }))return;
                    if(value.exchange_id)state.currentTurnExchangeId=safe(value.exchange_id);
                    if(value.type==='server_ste_metadata'&&typeof value.metadata?.is_first_turn==='boolean'){
                      state.serverFirstTurn=value.metadata.is_first_turn;
                      state.firstTurnMismatch=(state.turnKind==='FIRST_TURN')!==value.metadata.is_first_turn;
                      save();
                    }
                    if(value.type==='message_marker'&&value.event==='first'){
                      if(value.marker==='user_visible_token')return;
                      if(value.marker==='final_channel_token'){
                        state.sawFinalChannelToken=true;
                        if(state.phase==='THINKING')state.phase='ANSWERING';
                        state.lastSource=safe(source);state.lastError='';save();emitLog('answering_started','final_channel');return;
                      }
                    }
                    if(value.type==='message_stream_complete'){complete('message_stream_complete');return;}
                    const message=value.message&&typeof value.message==='object'?value.message:value;
                    if(message.status==='finished_successfully'&&message.end_turn===true){
                      complete('finished_successfully_end_turn');return;
                    }
                    if(value.message&&value.message!==value)inspectSemantic(value.message,source,context);
                  };
                  const parseSseBlock=(block,source,context)=>{
                    const data=[];
                    for(const line of String(block??'').split('\\n')){
                      if(line==='data:')data.push('');
                      else if(line.startsWith('data:'))data.push(line.slice(5).replace(/^ /,''));
                    }
                    const text=data.join('\\n').trim();
                    if(!text||text==='[DONE]')return;
                    try{inspectSemantic(JSON.parse(text),source,context);}catch(_){}
                  };
                  const observeSseText=(text,source='manual',context={})=>{
                    const normalized=String(text??'').replace(/\\r\\n?/g,'\\n');
                    for(const block of normalized.split(/\\n\\n+/))parseSseBlock(block,source,context);
                    return snapshot();
                  };
                  const observeFetchResponse=async(response,context)=>{
                    try{
                      const reader=response?.body?.getReader?.();if(!reader)return;
                      const decoder=new TextDecoder();let buffer='';
                      while(true){
                        const part=await reader.read();
                        if(part.done)break;
                        buffer=(buffer+decoder.decode(part.value,{stream:true})).replace(/\\r\\n?/g,'\\n');
                        let split;
                        while((split=buffer.indexOf('\\n\\n'))>=0){
                          const block=buffer.slice(0,split);buffer=buffer.slice(split+2);
                          parseSseBlock(block,'fetch-sse',context);
                        }
                      }
                      buffer=(buffer+decoder.decode()).replace(/\\r\\n?/g,'\\n');
                      if(buffer.trim())parseSseBlock(buffer,'fetch-sse',context);
                    }catch(_){}
                  };
                  const acceptWorkPayload=payload=>{
                    const type=safe(payload?.type).toLowerCase();
                    if(type!=='stream-item'&&type!=='done')return;
                    const conversationId=safe(payload.conversation_id||''),workTurnId=safe(payload.turn_id||'');
                    const context={sequence:state.turnSequence,conversationId,workTurnId};
                    if(!activeContext(context))return;
                    if(type==='stream-item'&&typeof payload.encoded_item==='string'){
                      observeSseText(payload.encoded_item,'work-websocket',context);return;
                    }
                    if(type==='done')complete('work_done');
                  };
                  const observeWorkFrame=frame=>{
                    let root;try{root=typeof frame==='string'?JSON.parse(frame):frame;}catch(_){return snapshot();}
                    const visit=node=>{
                      if(Array.isArray(node)){for(const item of node)visit(item);return;}
                      if(!node||typeof node!=='object')return;
                      acceptWorkPayload(node);
                      for(const child of Object.values(node))if(child&&typeof child==='object')visit(child);
                    };
                    visit(root);return snapshot();
                  };
                  const downstreamFetch=window.fetch.bind(window);
                  window.fetch=async function(input,init){
                    const probe=requestProbe(input,init);
                    if(probe.canonical)startTurn({source:'fetch',requestId:'',path:probe.path});
                    const sequence=state.turnSequence;
                    try{
                      const response=await downstreamFetch(input,init);
                      if(probe.canonical){
                        if(!response?.ok){markError('canonical_http_'+safe(response?.status),sequence);return response;}
                        try{void observeFetchResponse(response.clone(),{sequence});}catch(_){}
                      }
                      return response;
                    }catch(error){
                      if(probe.canonical)markError('canonical_fetch_rejected',sequence);
                      throw error;
                    }
                  };
                  const NativeWebSocket=window.WebSocket;
                  if(typeof NativeWebSocket==='function'){
                    const WrappedWebSocket=function(...args){
                      const socket=Reflect.construct(NativeWebSocket,args,NativeWebSocket);
                      try{socket.addEventListener('message',event=>{if(typeof event.data==='string')observeWorkFrame(event.data);});}catch(_){}
                      return socket;
                    };
                    WrappedWebSocket.prototype=NativeWebSocket.prototype;
                    try{Object.setPrototypeOf(WrappedWebSocket,NativeWebSocket);}catch(_){}
                    window.WebSocket=WrappedWebSocket;
                  }
                  const observeRequest=(method,url)=>{
                    const path=String(method||'').toUpperCase()==='POST'?canonicalPath(url):'';
                    if(path)startTurn({source:'manual-request',path});
                    return snapshot();
                  };
                  window.__selfRunTurnProtocol={
                    version:ENGINE_VERSION,snapshot,
                    observeCanonicalRequest:()=>{startTurn({source:'manual-canonical'});return snapshot();},
                    observeRequest,observeSseText,observeWorkFrame,
                    diagnostics:()=>({phase:state.phase,turnSequence:state.turnSequence,turnKind:state.turnKind,
                      conversationId:state.canonicalConversationId,workTurnId:state.currentWorkTurnId,
                      sawFinalChannelToken:state.sawFinalChannelToken,sawStreamComplete:state.sawStreamComplete,
                      completionDispatched:state.completionDispatched,lastError:state.lastError})
                  };
                  alignRun();
                  if(state.phase==='COMPLETE'&&!state.completionDispatched&&state.sawFinalChannelToken)schedulePendingDispatch(state.lastSource||'restored_complete');
                })();
                """
                .replace("__ENGINE_VERSION__", SelfRunScript.quote(ENGINE_VERSION))
                .replace("__COMPLETION_SCHEME__", SelfRunScript.quote(
                        SelfRunContinuationDom.TURN_COMPLETION_SCHEME))
                .replace("__COMPLETION_HOST__", SelfRunScript.quote(
                        SelfRunContinuationDom.TURN_COMPLETION_HOST));
    }
}
