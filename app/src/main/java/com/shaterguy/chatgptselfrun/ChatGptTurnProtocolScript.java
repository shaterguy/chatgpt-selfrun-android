package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/**
 * Document-start, protocol-first ChatGPT response-state observer.
 *
 * <p>The most recent canonical conversation POST owns the active response. No persisted turn
 * number is used to decide whether a request may start. Late fetch and Work/Pro socket data from
 * a superseded request are fenced by ephemeral request/stream identities.</p>
 */
final class ChatGptTurnProtocolScript {
    static final String ENGINE_VERSION = "turn-protocol-v6";
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
                  const STORE_KEY='selfrun-drive:response-protocol-state:v6';
                  const VALID_PHASES=new Set(['IDLE','THINKING','ANSWERING','COMPLETE','ERROR']);
                  const blank=()=>({
                    runId:'',phase:'IDLE',requestIdentity:'',
                    canonicalConversationId:'',currentWorkTurnId:'',
                    currentFinalMessageId:'',finalMessageActive:false,
                    sawFinalChannelToken:false,sawVisibleAnswer:false,
                    sawAssistantFinalText:false,sawStreamComplete:false,
                    completionDispatched:false,completionSource:'',lastError:'',lastSource:''
                  });
                  const safe=value=>String(value??'').slice(0,256);
                  const nonEmptyText=value=>typeof value==='string'&&value.trim().length>0;
                  const requestIdentity=()=>{try{return safe(crypto.randomUUID());}catch(_){return safe(Date.now().toString(36)+'-'+Math.random().toString(36).slice(2));}};
                  const restore=()=>{
                    try{
                      const raw=sessionStorage.getItem(STORE_KEY);if(!raw)return blank();
                      const value=JSON.parse(raw);
                      if(!value||!VALID_PHASES.has(value.phase))return blank();
                      return{...blank(),...value};
                    }catch(_){return blank();}
                  };
                  let state=restore(),pendingTimer=0;
                  const retiredWorkTurnIds=[];
                  const save=()=>{try{sessionStorage.setItem(STORE_KEY,JSON.stringify(state));}catch(_){}};
                  const emitLog=(stage,source)=>{try{const sink=window.selfRunTurnLog;if(!sink||typeof sink.postMessage!=='function')return;sink.postMessage(JSON.stringify({runId:safe(state.runId),stage:safe(stage),source:safe(source),phase:state.phase}));}catch(_){}};
                  const profileTarget=()=>{try{return window.__selfRunRequestProfileEngine?.target?.()||null;}catch(_){return null;}};
                  const resetForRun=run=>{state=blank();state.runId=safe(run);retiredWorkTurnIds.length=0;save();};
                  const alignRun=()=>{
                    const run=safe(profileTarget()?.runId||'');
                    if(run&&state.runId&&run!==state.runId)resetForRun(run);
                    else if(run&&!state.runId){state.runId=run;save();}
                    return run||state.runId;
                  };
                  const retireWorkTurn=id=>{const value=safe(id);if(!value||retiredWorkTurnIds.includes(value))return;retiredWorkTurnIds.push(value);if(retiredWorkTurnIds.length>8)retiredWorkTurnIds.shift();};
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
                      return path==='/backend-api/f/conversation'?path:'';
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
                  const startRequest=meta=>{
                    alignRun();
                    if(pendingTimer){clearTimeout(pendingTimer);pendingTimer=0;}
                    retireWorkTurn(state.currentWorkTurnId);
                    state.phase='THINKING';state.requestIdentity=requestIdentity();
                    state.currentWorkTurnId='';state.currentFinalMessageId='';state.finalMessageActive=false;
                    state.sawFinalChannelToken=false;state.sawVisibleAnswer=false;
                    state.sawAssistantFinalText=false;state.sawStreamComplete=false;
                    state.completionDispatched=false;state.completionSource='';state.lastError='';
                    state.lastSource=safe(meta?.source||'fetch');
                    const route=routeConversationId();if(route&&!state.canonicalConversationId)state.canonicalConversationId=route;
                    save();emitLog('turn_request','canonical_post');return state.requestIdentity;
                  };
                  const bindConversation=id=>{
                    const value=safe(id);if(!value)return true;
                    if(!state.canonicalConversationId){state.canonicalConversationId=value;save();return true;}
                    return state.canonicalConversationId===value;
                  };
                  const bindWorkTurn=id=>{
                    const value=safe(id);if(!value)return true;
                    if(retiredWorkTurnIds.includes(value))return false;
                    if(!state.currentWorkTurnId){state.currentWorkTurnId=value;save();return true;}
                    return state.currentWorkTurnId===value;
                  };
                  const activeContext=context=>{
                    const identity=safe(context?.requestIdentity||'');
                    if(identity&&identity!==state.requestIdentity)return false;
                    if(state.phase!=='THINKING'&&state.phase!=='ANSWERING')return false;
                    return bindConversation(context?.conversationId||'')&&bindWorkTurn(context?.workTurnId||'');
                  };
                  const completionEvidence=()=>state.sawVisibleAnswer||state.sawFinalChannelToken||state.sawAssistantFinalText;
                  const noteAnswering=source=>{
                    if(state.phase!=='THINKING'&&state.phase!=='ANSWERING')return;
                    const first=state.phase==='THINKING';if(first)state.phase='ANSWERING';
                    state.lastSource=safe(source);state.lastError='';save();if(first)emitLog('answering_started',source);
                  };
                  const noteVisibleAnswer=source=>{
                    if(state.sawVisibleAnswer&&state.phase==='ANSWERING')return;
                    state.sawVisibleAnswer=true;save();noteAnswering(source);
                  };
                  const noteAssistantFinalText=source=>{
                    if(!state.sawAssistantFinalText){state.sawAssistantFinalText=true;save();}
                    noteVisibleAnswer(source);
                  };
                  const markFinalMessage=message=>{
                    if(!message||typeof message!=='object')return false;
                    const role=safe(message.author?.role).toLowerCase(),channel=safe(message.channel).toLowerCase();
                    if(role!=='assistant'||channel!=='final')return false;
                    state.finalMessageActive=true;if(message.id)state.currentFinalMessageId=safe(message.id);
                    save();noteVisibleAnswer('visible_answer');
                    const parts=Array.isArray(message.content?.parts)?message.content.parts:[];
                    if(parts.some(nonEmptyText))noteAssistantFinalText('visible_answer');return true;
                  };
                  const observeFinalTextDelta=value=>{
                    if(!state.finalMessageActive||!value||typeof value!=='object')return;
                    const path=safe(value.p||'');
                    if(path.includes('/message/content/parts')&&nonEmptyText(value.v)){noteAssistantFinalText('visible_answer');return;}
                    if(Array.isArray(value.v))for(const op of value.v){
                      if(!op||typeof op!=='object')continue;const p=safe(op.p||'');
                      if(p.includes('/message/content/parts')&&nonEmptyText(op.v)){noteAssistantFinalText('visible_answer');return;}
                    }
                  };
                  const cancelDomFallback=observer=>{
                    if(!observer)return;observer.fired=true;try{observer.observer?.disconnect();}catch(_){}
                    try{if(observer.timer)clearTimeout(observer.timer);}catch(_){}observer.timer=0;
                    if(window.__selfRunDriveTurnObserver===observer)window.__selfRunDriveTurnObserver=null;
                  };
                  const suspendDomFallback=observer=>{
                    if(!observer)return;observer.fired=true;try{observer.observer?.disconnect();}catch(_){}
                    try{if(observer.timer)clearTimeout(observer.timer);}catch(_){}observer.timer=0;
                  };
                  function schedulePendingDispatch(source){
                    if(pendingTimer||state.completionDispatched||state.phase!=='COMPLETE'||!completionEvidence())return;
                    let attempts=0;const retry=()=>{pendingTimer=0;if(state.completionDispatched||state.phase!=='COMPLETE'||!completionEvidence())return;
                      if(dispatchCompletion(source,false))return;attempts++;if(attempts<40)pendingTimer=setTimeout(retry,50);};
                    pendingTimer=setTimeout(retry,0);
                  }
                  function dispatchCompletion(source,allowRetry=true){
                    if(state.phase!=='COMPLETE'||state.completionDispatched||!completionEvidence())return false;
                    const run=alignRun(),observer=window.__selfRunDriveTurnObserver,token=safe(observer?.token||'');
                    if(!run||!token){if(allowRetry)schedulePendingDispatch(source);return false;}
                    state.completionDispatched=true;state.completionSource=safe(source);save();emitLog('completion_dispatch',source);
                    cancelDomFallback(observer);
                    location.href=COMPLETION_SCHEME+'://'+COMPLETION_HOST+'?run='+encodeURIComponent(run)
                      +'&token='+encodeURIComponent(token)+'&source='+encodeURIComponent(safe(source));return true;
                  }
                  const complete=source=>{
                    if(state.phase!=='THINKING'&&state.phase!=='ANSWERING')return false;
                    state.sawStreamComplete=true;
                    if(!completionEvidence()){
                      state.lastSource=safe(source);state.lastError='completion_without_final_answer_evidence';save();
                      emitLog('completion_ignored',source);return false;
                    }
                    state.phase='COMPLETE';state.lastSource=safe(source);state.lastError='';save();
                    emitLog('complete',source);dispatchCompletion(source,true);return true;
                  };
                  const markError=(reason,identity)=>{
                    if(safe(identity)!==state.requestIdentity)return;
                    if(state.phase!=='THINKING'&&state.phase!=='ANSWERING')return;
                    suspendDomFallback(window.__selfRunDriveTurnObserver);
                    state.phase='ERROR';state.lastError=safe(reason);save();emitLog('error',reason);
                  };
                  const inspectSemantic=(value,source,context)=>{
                    if(Array.isArray(value)){for(const item of value)inspectSemantic(item,source,context);return;}
                    if(!value||typeof value!=='object'||!activeContext({
                      requestIdentity:context?.requestIdentity,
                      conversationId:value.conversation_id||context?.conversationId,
                      workTurnId:value.turn_id||context?.workTurnId}))return;
                    if(value.type==='stream_handoff')return;
                    if(value.type==='message_marker'){
                      const marker=safe(value.marker),event=safe(value.event);
                      if(marker==='user_visible_token'||marker==='cot_token'||marker==='last_token')return;
                      if(marker==='final_channel_token'&&event==='first'){
                        state.sawFinalChannelToken=true;state.finalMessageActive=true;
                        if(value.message_id)state.currentFinalMessageId=safe(value.message_id);
                        save();noteVisibleAnswer('final_channel');return;
                      }
                    }
                    const directMessage=value.message&&typeof value.message==='object'?value.message:null;
                    const deltaMessage=value.v?.message&&typeof value.v.message==='object'?value.v.message:null;
                    const finalMessage=directMessage||deltaMessage;if(finalMessage)markFinalMessage(finalMessage);
                    observeFinalTextDelta(value);
                    if(value.type==='message_stream_complete'){complete('message_stream_complete');return;}
                    if(finalMessage&&safe(finalMessage.author?.role).toLowerCase()==='assistant'
                            &&safe(finalMessage.channel).toLowerCase()==='final'
                            &&finalMessage.status==='finished_successfully'&&finalMessage.end_turn===true){
                      complete('finished_successfully_end_turn');return;
                    }
                    if(directMessage&&directMessage!==value)inspectSemantic(directMessage,source,context);
                    if(deltaMessage&&deltaMessage!==value)inspectSemantic(deltaMessage,source,context);
                  };
                  const parseSseBlock=(block,source,context)=>{
                    const data=[];for(const line of String(block??'').split('\\n')){
                      if(line==='data:')data.push('');else if(line.startsWith('data:'))data.push(line.slice(5).replace(/^ /,''));}
                    const text=data.join('\\n').trim();if(!text||text==='[DONE]')return;
                    try{inspectSemantic(JSON.parse(text),source,context);}catch(_){}
                  };
                  const observeSseText=(text,source='manual',context={})=>{
                    const normalized=String(text??'').replace(/\\r\\n?/g,'\\n');
                    for(const block of normalized.split(/\\n\\n+/))parseSseBlock(block,source,context);return snapshot();
                  };
                  const observeFetchResponse=async(response,context)=>{
                    try{
                      const reader=response?.body?.getReader?.();if(!reader)return;const decoder=new TextDecoder();let buffer='';
                      while(true){const part=await reader.read();if(part.done)break;
                        if(context.requestIdentity!==state.requestIdentity)return;
                        buffer=(buffer+decoder.decode(part.value,{stream:true})).replace(/\\r\\n?/g,'\\n');let split;
                        while((split=buffer.indexOf('\\n\\n'))>=0){const block=buffer.slice(0,split);buffer=buffer.slice(split+2);parseSseBlock(block,'fetch-sse',context);}}
                      if(context.requestIdentity!==state.requestIdentity)return;
                      buffer=(buffer+decoder.decode()).replace(/\\r\\n?/g,'\\n');if(buffer.trim())parseSseBlock(buffer,'fetch-sse',context);
                    }catch(_){}
                  };
                  const acceptSocketPayload=payload=>{
                    const type=safe(payload?.type).toLowerCase();if(type==='done')return;
                    if(type!=='stream-item'||typeof payload.encoded_item!=='string')return;
                    const context={conversationId:safe(payload.conversation_id||''),workTurnId:safe(payload.turn_id||'')};
                    if(!activeContext(context))return;observeSseText(payload.encoded_item,'chatgpt-websocket',context);
                  };
                  const observeSocketFrame=frame=>{
                    let root;try{root=typeof frame==='string'?JSON.parse(frame):frame;}catch(_){return snapshot();}
                    const visit=node=>{if(Array.isArray(node)){for(const item of node)visit(item);return;}
                      if(!node||typeof node!=='object')return;acceptSocketPayload(node);
                      for(const child of Object.values(node))if(child&&typeof child==='object')visit(child);};
                    visit(root);return snapshot();
                  };
                  const downstreamFetch=window.fetch.bind(window);
                  window.fetch=async function(input,init){
                    const probe=requestProbe(input,init);const identity=probe.canonical?startRequest({source:'fetch'}):'';
                    try{
                      const response=await downstreamFetch(input,init);
                      if(probe.canonical){
                        if(identity!==state.requestIdentity)return response;
                        if(!response?.ok){markError('canonical_http_'+safe(response?.status),identity);return response;}
                        try{void observeFetchResponse(response.clone(),{requestIdentity:identity});}catch(_){}
                      }
                      return response;
                    }catch(error){if(probe.canonical)markError('canonical_fetch_rejected',identity);throw error;}
                  };
                  const NativeWebSocket=window.WebSocket;
                  if(typeof NativeWebSocket==='function'){
                    const WrappedWebSocket=function(...args){const socket=Reflect.construct(NativeWebSocket,args,NativeWebSocket);
                      try{socket.addEventListener('message',event=>{if(typeof event.data==='string')observeSocketFrame(event.data);});}catch(_){}return socket;};
                    WrappedWebSocket.prototype=NativeWebSocket.prototype;try{Object.setPrototypeOf(WrappedWebSocket,NativeWebSocket);}catch(_){}
                    window.WebSocket=WrappedWebSocket;
                  }
                  const observeRequest=(method,url)=>{
                    const path=String(method||'').toUpperCase()==='POST'?canonicalPath(url):'';
                    if(path)startRequest({source:'manual-request'});return snapshot();
                  };
                  window.__selfRunTurnProtocol={
                    version:ENGINE_VERSION,snapshot,
                    observeCanonicalRequest:()=>{startRequest({source:'manual-canonical'});return snapshot();},
                    observeRequest,observeSseText,observeSocketFrame,observeWorkFrame:observeSocketFrame,
                    diagnostics:()=>({phase:state.phase,requestIdentity:state.requestIdentity,
                      conversationId:state.canonicalConversationId,workTurnId:state.currentWorkTurnId,
                      sawFinalChannelToken:state.sawFinalChannelToken,sawVisibleAnswer:state.sawVisibleAnswer,
                      sawAssistantFinalText:state.sawAssistantFinalText,sawStreamComplete:state.sawStreamComplete,
                      completionDispatched:state.completionDispatched,lastError:state.lastError})
                  };
                  alignRun();
                  if(state.phase==='COMPLETE'&&!state.completionDispatched&&completionEvidence())schedulePendingDispatch(state.lastSource||'restored_complete');
                })();
                """
                .replace("__ENGINE_VERSION__", SelfRunScript.quote(ENGINE_VERSION))
                .replace("__COMPLETION_SCHEME__", SelfRunScript.quote(SelfRunContinuationDom.TURN_COMPLETION_SCHEME))
                .replace("__COMPLETION_HOST__", SelfRunScript.quote(SelfRunContinuationDom.TURN_COMPLETION_HOST));
    }
}
