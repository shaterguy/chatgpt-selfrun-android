package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/** Work-only transport adapter for the shared response protocol state machine. */
final class WorkTurnProtocolIngressScript {
    static final String ENGINE_VERSION = "work-turn-ingress-v3";
    private static final Set<String> CHATGPT_ORIGINS = Set.of(
            "https://chatgpt.com", "https://www.chatgpt.com");

    private WorkTurnProtocolIngressScript() {}

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
                  if(window.__selfRunWorkTurnProtocolIngress?.version===ENGINE_VERSION)return;
                  const MAX_ENCODED_ITEMS=6,MAX_ENCODED_ITEM_LENGTH=200000,MAX_DECODE_DEPTH=8;
                  const safe=value=>String(value??'').replace(/\\s+/g,' ').trim().slice(0,256);
                  const token=(value,max=80)=>safe(value).replace(/[^A-Za-z0-9_.:/,-]/g,'_').slice(0,max);
                  const target=()=>{try{return window.__selfRunRequestProfileEngine?.target?.()||null;}catch(_){return null;}};
                  const workMode=()=>safe(target()?.mode).toLowerCase()==='work';
                  const protocol=()=>{try{return window.__selfRunTurnProtocol||null;}catch(_){return null;}};
                  const counters={fetchRequests:0,webSocketCreated:0,webSocketMessages:0,workerMessages:0,sharedWorkerMessages:0,
                    framesSeen:0,binaryDecoded:0,forwardedFrames:0,encodedItemsFound:0,decodedItems:0,
                    semanticSignals:0,staleFrames:0,ignoredTransport:0,decodeErrors:0};
                  const decoderKinds={};
                  const count=key=>{if(Object.prototype.hasOwnProperty.call(counters,key))counters[key]++;};
                  const noteDecoder=kind=>{const key=token(kind,40)||'unknown';decoderKinds[key]=(decoderKinds[key]||0)+1;};
                  const phase=()=>{try{return safe(protocol()?.snapshot?.()?.phase||'IDLE');}catch(_){return'IDLE';}};
                  const diagnosticsState=()=>{try{return protocol()?.diagnostics?.()||{};}catch(_){return{};}};
                  const diagnostic=(stage,fields={})=>{
                    if(!workMode())return;
                    try{
                      const sink=window.selfRunTurnLog,runId=safe(target()?.runId||'');
                      if(!sink||typeof sink.postMessage!=='function'||!runId)return;
                      const item={runId,stage:safe(stage),phase:phase(),source:token(fields.source||'work',40)};
                      const stringKeys=['transport','dataType','topKeys','decoder','semantic','binding','transition','completion','outcome','reason'];
                      for(const key of stringKeys){if(fields[key]!=null&&safe(fields[key]))item[key]=token(fields[key],160);}
                      const numberKeys=['frameCount','byteLength'];
                      for(const key of numberKeys){if(Number.isFinite(Number(fields[key])))item[key]=Math.max(0,Math.floor(Number(fields[key])));}
                      const booleanKeys=['encodedItemFound','websocketCreated','staleRejected'];
                      for(const key of booleanKeys){if(typeof fields[key]==='boolean')item[key]=fields[key];}
                      sink.postMessage(JSON.stringify(item));
                    }catch(_){}
                  };
                  let transportInstallLogged=false,generationEpoch=0;
                  const workTurnEpochs=new Map();
                  const trimEpochMap=()=>{while(workTurnEpochs.size>24)workTurnEpochs.delete(workTurnEpochs.keys().next().value);};
                  const transportAvailability={fetch:false,websocket:false,worker:false,sharedworker:false};
                  const ensureInstallDiagnostic=()=>{
                    if(transportInstallLogged||!workMode())return;
                    transportInstallLogged=true;
                    const available=Object.entries(transportAvailability).filter(([,value])=>value).map(([key])=>key).join(',')||'none';
                    diagnostic('WORK_PROTOCOL_TRANSPORT',{source:'observer_install',transport:available,outcome:'installed'});
                  };
                  const canonicalConversation=(method,url)=>{
                    if(String(method??'').toUpperCase()!=='POST')return false;
                    try{
                      const parsed=new URL(url,location.href);if(parsed.origin!==location.origin)return false;
                      let path=parsed.pathname;if(path.length>1)path=path.replace(/\\/+$/,'');
                      return path==='/backend-api/f/conversation';
                    }catch(_){return false;}
                  };
                  const requestMeta=(input,init)=>{
                    try{
                      const isRequest=typeof Request!=='undefined'&&input instanceof Request;
                      return{method:String(init?.method||(isRequest?input.method:'GET')||'GET').toUpperCase(),
                        url:String(isRequest?input.url:input??'')};
                    }catch(_){return{method:'',url:''};}
                  };
                  const noteCanonical=source=>{
                    if(!workMode())return'';
                    ensureInstallDiagnostic();generationEpoch++;
                    diagnostic('WORK_PROTOCOL_TRANSPORT',{source,transport:source,outcome:'canonical_request',frameCount:counters.framesSeen});
                    return phase();
                  };
                  const noteTransition=(before,source)=>{
                    const afterSnapshot=(()=>{try{return protocol()?.snapshot?.()||{};}catch(_){return{};}})();
                    const after=safe(afterSnapshot.phase||phase());
                    if(before&&after&&before!==after){
                      diagnostic('WORK_PROTOCOL_TRANSITION',{source,transition:before+'>'+after,
                        completion:safe(afterSnapshot.completionSource||'')});
                    }
                    return afterSnapshot;
                  };
                  const observeRequest=(method,url)=>{
                    if(!workMode()||!canonicalConversation(method,url))return false;
                    const before=noteCanonical('xhr');
                    try{const p=protocol();if(!p||typeof p.observeRequest!=='function')return false;p.observeRequest(method,url);noteTransition(before,'canonical_post');return true;}
                    catch(_){diagnostic('WORK_PROTOCOL_DECODE_ERROR',{source:'xhr',reason:'canonical_observer_failed'});return false;}
                  };
                  const nativeOpen=XMLHttpRequest.prototype.open,nativeSend=XMLHttpRequest.prototype.send,xhrMeta=new WeakMap();
                  XMLHttpRequest.prototype.open=function(method,url,...rest){
                    xhrMeta.set(this,{method:String(method||''),url:String(url||'')});
                    return nativeOpen.call(this,method,url,...rest);
                  };
                  XMLHttpRequest.prototype.send=function(body){
                    const meta=xhrMeta.get(this)||{method:'',url:''};
                    const eligible=workMode()&&canonicalConversation(meta.method,meta.url);
                    const result=nativeSend.call(this,body);if(eligible)observeRequest(meta.method,meta.url);return result;
                  };
                  const nativeFetch=window.fetch;
                  if(typeof nativeFetch==='function'){
                    const wrappedFetch=function(...args){
                      const meta=requestMeta(args[0],args[1]),eligible=workMode()&&canonicalConversation(meta.method,meta.url);
                      const before=eligible?noteCanonical('fetch'):'';if(eligible)count('fetchRequests');
                      const result=Reflect.apply(nativeFetch,this,args);if(eligible)noteTransition(before,'canonical_post');return result;
                    };
                    try{Object.defineProperty(wrappedFetch,'name',{value:nativeFetch.name});Object.defineProperty(wrappedFetch,'length',{value:nativeFetch.length});wrappedFetch.toString=nativeFetch.toString.bind(nativeFetch);}catch(_){}
                    window.fetch=wrappedFetch;transportAvailability.fetch=true;
                  }
                  const blobText=blob=>{
                    if(blob&&typeof blob.text==='function')return blob.text();
                    return new Promise((resolve,reject)=>{try{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result??''));reader.onerror=()=>reject(reader.error);reader.readAsText(blob);}catch(error){reject(error);}});
                  };
                  const decodeBytes=async view=>{
                    if(typeof TextDecoder==='function')return new TextDecoder('utf-8').decode(view);
                    return blobText(new Blob([view]));
                  };
                  const dataType=data=>{
                    if(typeof data==='string')return'string';
                    if(typeof Blob!=='undefined'&&data instanceof Blob)return'blob';
                    if(typeof ArrayBuffer!=='undefined'&&data instanceof ArrayBuffer)return'arraybuffer';
                    if(typeof ArrayBuffer!=='undefined'&&typeof ArrayBuffer.isView==='function'&&ArrayBuffer.isView(data))return'arraybuffer_view';
                    if(data&&typeof data==='object')return'object';return typeof data;
                  };
                  const dataLength=data=>{
                    if(typeof data==='string')return data.length;
                    if(typeof Blob!=='undefined'&&data instanceof Blob)return data.size;
                    return Number(data?.byteLength)||0;
                  };
                  const parseJsonContainer=value=>{
                    let current=String(value??'').trim();
                    for(let depth=0;depth<2;depth++){
                      if(!current)return null;
                      if(current.startsWith('{')||current.startsWith('[')){try{return JSON.parse(current);}catch(_){return null;}}
                      if(current.startsWith('"')){try{const decoded=JSON.parse(current);if(typeof decoded!=='string')return null;current=decoded.trim();continue;}catch(_){return null;}}
                      return null;
                    }
                    return null;
                  };
                  const normalizeBase64=value=>{
                    const text=String(value??'').trim();if(text.length<8||text.length%4===1||!/^[A-Za-z0-9+/_-]+={0,2}$/.test(text))return null;
                    let normalized=text.replace(/-/g,'+').replace(/_/g,'/').replace(/=+$/g,'');while(normalized.length%4)normalized+='=';return normalized;
                  };
                  const decodeBase64Text=value=>{
                    if(typeof atob!=='function')return null;const normalized=normalizeBase64(value);if(!normalized)return null;
                    try{const binary=atob(normalized),bytes=new Uint8Array(binary.length);for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i)&255;
                      return typeof TextDecoder==='function'?new TextDecoder('utf-8',{fatal:true}).decode(bytes):null;}catch(_){return null;}
                  };
                  const looksSse=text=>/(^|\\n)(?:event|data):/.test(String(text??'').replace(/\\r\\n?/g,'\\n'));
                  const contextFor=(node,parent={})=>({
                    conversationId:safe(node?.conversation_id||parent.conversationId||''),
                    workTurnId:safe(node?.turn_id||parent.workTurnId||'')
                  });
                  const semanticLabel=node=>{
                    if(!node||typeof node!=='object')return'';const type=token(node.type,40),marker=token(node.marker,40),event=token(node.event,40);
                    if(type==='message_marker')return[type,marker,event].filter(Boolean).join(':');
                    if(type)return type;
                    if(node.status==='finished_successfully'&&node.end_turn===true)return'finished_successfully_end_turn';
                    return node.message&&typeof node.message==='object'?'message_envelope':'';
                  };
                  const semanticCandidate=node=>{
                    if(!node||typeof node!=='object'||Array.isArray(node))return false;
                    const type=safe(node.type);
                    return ['message_marker','message_stream_complete','message_start','message_delta','message_update','stream_handoff','done','error'].includes(type)
                      ||(node.status==='finished_successfully'&&node.end_turn===true)||!!(node.message&&typeof node.message==='object')||!!(node.v?.message&&typeof node.v.message==='object');
                  };
                  const processSemantic=(node,source,context,decoder)=>{
                    const p=protocol();if(!p||typeof p.observeSseText!=='function')return false;
                    const label=semanticLabel(node);if(!label)return false;
                    const beforeSnapshot=(()=>{try{return p.snapshot?.()||{};}catch(_){return{};}})(),beforePhase=safe(beforeSnapshot.phase||'');
                    const beforeDiag=diagnosticsState(),turnId=safe(context?.workTurnId||'');
                    const knownEpoch=turnId?workTurnEpochs.get(turnId):null,staleExpected=!!(knownEpoch&&knownEpoch<generationEpoch);
                    try{p.observeSseText('data: '+JSON.stringify(node)+'\\n\\n','work-decoder-'+decoder,context||{});count('semanticSignals');}
                    catch(_){count('decodeErrors');diagnostic('WORK_PROTOCOL_DECODE_ERROR',{source,decoder,reason:'semantic_forward_failed'});return false;}
                    const afterDiag=diagnosticsState(),afterSnapshot=(()=>{try{return p.snapshot?.()||{};}catch(_){return{};}})();
                    let binding='none',staleRejected=false;
                    if(turnId){
                      if(safe(afterDiag.workTurnId||'')===turnId){binding='accepted';if(!knownEpoch){workTurnEpochs.set(turnId,generationEpoch);trimEpochMap();}}
                      else if(staleExpected){binding='stale_rejected';staleRejected=true;count('staleFrames');}
                      else if(safe(beforeDiag.workTurnId||'')&&safe(beforeDiag.workTurnId||'')!==turnId)binding='rejected';
                      else binding='unbound';
                    }
                    diagnostic('WORK_PROTOCOL_SIGNAL',{source,decoder,semantic:label,binding,staleRejected});
                    const afterPhase=safe(afterSnapshot.phase||'');
                    if(beforePhase&&afterPhase&&beforePhase!==afterPhase){
                      diagnostic('WORK_PROTOCOL_TRANSITION',{source,decoder,transition:beforePhase+'>'+afterPhase,
                        completion:safe(afterSnapshot.completionSource||'')});
                    }
                    return true;
                  };
                  let encodedItemBudget=0;
                  const processSse=(raw,source,context,decoder)=>{
                    noteDecoder(decoder);count('decodedItems');
                    const normalized=String(raw??'').replace(/\\r\\n?/g,'\\n');
                    for(const block of normalized.split(/\\n\\n+/)){
                      const data=[];for(const line of block.split('\\n')){if(line==='data:')data.push('');else if(line.startsWith('data:'))data.push(line.slice(5).replace(/^ /,''));}
                      const text=data.join('\\n').trim();if(!text)continue;
                      if(text==='[DONE]'){diagnostic('WORK_PROTOCOL_SIGNAL',{source,decoder,semantic:'sse_done_ignored',binding:'transport_boundary'});continue;}
                      try{visitDecoded(JSON.parse(text),source,context,decoder,0);}catch(_){count('decodeErrors');diagnostic('WORK_PROTOCOL_DECODE_ERROR',{source,decoder,reason:'sse_json_parse'});}
                    }
                  };
                  const inspectEncodedItem=(raw,source,context)=>{
                    if(encodedItemBudget>=MAX_ENCODED_ITEMS)return;encodedItemBudget++;count('encodedItemsFound');
                    let text=String(raw??'');if(text.length>MAX_ENCODED_ITEM_LENGTH){text=text.slice(0,MAX_ENCODED_ITEM_LENGTH);diagnostic('WORK_PROTOCOL_DECODE_ERROR',{source,reason:'encoded_item_truncated'});}
                    const trimmed=text.trim();if(!trimmed)return;
                    if(looksSse(trimmed)){processSse(trimmed,source,context,'sse');return;}
                    let parsed=parseJsonContainer(trimmed);
                    if(parsed!==null){noteDecoder('json');count('decodedItems');visitDecoded(parsed,source,context,'json',0);return;}
                    if(/%[0-9A-Fa-f]{2}/.test(trimmed)){
                      try{const uri=decodeURIComponent(trimmed);if(looksSse(uri)){processSse(uri,source,context,'url-sse');return;}
                        parsed=parseJsonContainer(uri);if(parsed!==null){noteDecoder('url-json');count('decodedItems');visitDecoded(parsed,source,context,'url-json',0);return;}}catch(_){}
                    }
                    const b64=decodeBase64Text(trimmed);
                    if(b64!==null){
                      if(looksSse(b64)){processSse(b64,source,context,'b64-sse');return;}
                      parsed=parseJsonContainer(b64);if(parsed!==null){noteDecoder('b64-json');count('decodedItems');visitDecoded(parsed,source,context,'b64-json',0);return;}
                      noteDecoder('b64-text');diagnostic('WORK_PROTOCOL_SIGNAL',{source,decoder:'b64-text',semantic:'opaque_text',binding:'none'});return;
                    }
                    noteDecoder('opaque');diagnostic('WORK_PROTOCOL_SIGNAL',{source,decoder:'opaque',semantic:'unparsed_encoded_item',binding:'none'});
                  };
                  function visitDecoded(node,source,parentContext,decoder,depth){
                    if(depth>MAX_DECODE_DEPTH||node==null)return;
                    if(Array.isArray(node)){for(const child of node)visitDecoded(child,source,parentContext,decoder,depth+1);return;}
                    if(typeof node!=='object')return;
                    const context=contextFor(node,parentContext);
                    if(semanticCandidate(node))processSemantic(node,source,context,decoder);
                    for(const [key,child] of Object.entries(node)){
                      if(key==='encoded_item'&&typeof child==='string'){inspectEncodedItem(child,source,context);continue;}
                      if(child&&typeof child==='object')visitDecoded(child,source,context,decoder,depth+1);
                    }
                  }
                  const decodeFrame=(frame,source)=>{
                    const p=protocol();if(!p)return false;encodedItemBudget=0;
                    const before=(()=>{try{return p.snapshot?.()||{};}catch(_){return{};}})();
                    try{
                      const legacy=typeof p.observeWorkFrame==='function'?p.observeWorkFrame:(typeof p.observeSocketFrame==='function'?p.observeSocketFrame:null);
                      if(legacy){legacy.call(p,frame);count('forwardedFrames');}
                    }catch(_){count('decodeErrors');diagnostic('WORK_PROTOCOL_DECODE_ERROR',{source,reason:'legacy_forward_failed'});}
                    const afterLegacy=(()=>{try{return p.snapshot?.()||{};}catch(_){return{};}})();
                    if(safe(before.phase)&&safe(afterLegacy.phase)&&safe(before.phase)!==safe(afterLegacy.phase)){
                      diagnostic('WORK_PROTOCOL_TRANSITION',{source,decoder:'legacy',transition:safe(before.phase)+'>'+safe(afterLegacy.phase),completion:safe(afterLegacy.completionSource||'')});
                    }
                    let root=frame;
                    if(typeof frame==='string'){
                      if(looksSse(frame)){processSse(frame,source,{},'outer-sse');return true;}
                      try{root=JSON.parse(frame);}catch(_){count('decodeErrors');diagnostic('WORK_PROTOCOL_DECODE_ERROR',{source,reason:'outer_json_parse'});return false;}
                    }
                    if(!root||typeof root!=='object')return false;
                    const topKeys=Object.keys(root).filter(key=>/^[A-Za-z0-9_.:/-]{1,80}$/.test(key)).slice(0,10).join(',');
                    diagnostic('WORK_PROTOCOL_FRAME',{source,topKeys,encodedItemFound:JSON.stringify(root).includes('"encoded_item"')});
                    visitDecoded(root,source,{},'outer-json',0);return true;
                  };
                  const observeTransportData=async(data,source)=>{
                    if(!workMode())return false;ensureInstallDiagnostic();
                    const kind=dataType(data);count('framesSeen');
                    diagnostic('WORK_PROTOCOL_FRAME',{source,transport:source,dataType:kind,frameCount:counters.framesSeen,byteLength:dataLength(data)});
                    try{
                      if(typeof data==='string')return decodeFrame(data,source);
                      if(typeof Blob!=='undefined'&&data instanceof Blob){count('binaryDecoded');return decodeFrame(await blobText(data),source);}
                      if(typeof ArrayBuffer!=='undefined'&&data instanceof ArrayBuffer){count('binaryDecoded');return decodeFrame(await decodeBytes(new Uint8Array(data)),source);}
                      if(typeof ArrayBuffer!=='undefined'&&typeof ArrayBuffer.isView==='function'&&ArrayBuffer.isView(data)){
                        count('binaryDecoded');return decodeFrame(await decodeBytes(new Uint8Array(data.buffer,data.byteOffset,data.byteLength)),source);}
                      if(data&&typeof data==='object')return decodeFrame(data,source);
                    }catch(_){count('decodeErrors');diagnostic('WORK_PROTOCOL_DECODE_ERROR',{source,dataType:kind,reason:'transport_decode_failed'});return false;}
                    count('ignoredTransport');diagnostic('WORK_PROTOCOL_DECODE_ERROR',{source,dataType:kind,reason:'unsupported_data_type'});return false;
                  };
                  const NativeWebSocket=window.WebSocket;
                  if(typeof NativeWebSocket==='function'){
                    const WrappedWebSocket=function(...args){
                      const socket=Reflect.construct(NativeWebSocket,args,NativeWebSocket);count('webSocketCreated');
                      if(workMode()){ensureInstallDiagnostic();diagnostic('WORK_PROTOCOL_TRANSPORT',{source:'websocket',transport:'websocket',websocketCreated:true,outcome:'created'});}
                      try{socket.addEventListener('message',event=>{count('webSocketMessages');void observeTransportData(event.data,'work-websocket');});}catch(_){}
                      return socket;
                    };
                    WrappedWebSocket.prototype=NativeWebSocket.prototype;try{Object.setPrototypeOf(WrappedWebSocket,NativeWebSocket);Object.defineProperty(WrappedWebSocket,'name',{value:NativeWebSocket.name});Object.defineProperty(WrappedWebSocket,'length',{value:NativeWebSocket.length});WrappedWebSocket.toString=NativeWebSocket.toString.bind(NativeWebSocket);}catch(_){}
                    window.WebSocket=WrappedWebSocket;transportAvailability.websocket=true;
                  }
                  const NativeWorker=window.Worker;
                  if(typeof NativeWorker==='function'){
                    const WrappedWorker=function(...args){const worker=Reflect.construct(NativeWorker,args,NativeWorker);
                      try{worker.addEventListener('message',event=>{count('workerMessages');void observeTransportData(event.data,'work-worker');});}catch(_){}return worker;};
                    WrappedWorker.prototype=NativeWorker.prototype;try{Object.setPrototypeOf(WrappedWorker,NativeWorker);}catch(_){}
                    window.Worker=WrappedWorker;transportAvailability.worker=true;
                  }
                  const NativeSharedWorker=window.SharedWorker;
                  if(typeof NativeSharedWorker==='function'){
                    const WrappedSharedWorker=function(...args){const shared=Reflect.construct(NativeSharedWorker,args,NativeSharedWorker);
                      try{shared?.port?.addEventListener?.('message',event=>{count('sharedWorkerMessages');void observeTransportData(event.data,'work-shared-worker');});}catch(_){}return shared;};
                    WrappedSharedWorker.prototype=NativeSharedWorker.prototype;try{Object.setPrototypeOf(WrappedSharedWorker,NativeSharedWorker);}catch(_){}
                    window.SharedWorker=WrappedSharedWorker;transportAvailability.sharedworker=true;
                  }
                  window.__selfRunWorkTurnProtocolIngress={
                    version:ENGINE_VERSION,observeRequest,
                    observeSocketFrame:frame=>observeTransportData(frame,'work-manual'),
                    observeTransportData,
                    diagnostics:()=>({mode:safe(target()?.mode),protocol:!!protocol(),generationEpoch,...counters,decoderKinds:{...decoderKinds}})
                  };
                })();
                """.replace("__ENGINE_VERSION__", SelfRunScript.quote(ENGINE_VERSION));
    }
}
