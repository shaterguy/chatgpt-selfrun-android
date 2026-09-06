package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/** Dedicated transport adapter for markerless/long-running Pro responses in Chat mode. */
final class ProTurnProtocolIngressScript {
    static final String ENGINE_VERSION = "pro-turn-ingress-v2";
    private static final Set<String> CHATGPT_ORIGINS = Set.of(
            "https://chatgpt.com", "https://www.chatgpt.com");

    private ProTurnProtocolIngressScript() {}

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
                  if(window.__selfRunProTurnProtocolIngress?.version===ENGINE_VERSION)return;
                  const MAX_ENCODED_ITEMS=6,MAX_ENCODED_ITEM_LENGTH=200000,MAX_DECODE_DEPTH=8;
                  const safe=value=>String(value??'').replace(/\\s+/g,' ').trim().slice(0,256);
                  const target=()=>{try{return window.__selfRunRequestProfileEngine?.target?.()||null;}catch(_){return null;}};
                  const protocol=()=>{try{return window.__selfRunTurnProtocol||null;}catch(_){return null;}};
                  const activeChat=()=>{
                    try{
                      const t=target(),p=protocol(),s=p?.snapshot?.()||{};
                      return safe(t?.mode).toLowerCase()==='chat'&&!!safe(t?.runId)&&safe(t?.runId)===safe(s.runId)
                        &&(s.phase==='THINKING'||s.phase==='ANSWERING');
                    }catch(_){return false;}
                  };
                  const ownsCurrentTurn=()=>activeChat()&&safe(protocol()?.snapshot?.()?.detectorLane)==='PRO';
                  const handlesTransport=()=>activeChat();
                  const counters={requestProfileHints:0,requestHintMisses:0,webSocketCreated:0,webSocketMessages:0,
                    workerMessages:0,sharedWorkerMessages:0,serviceWorkerMessages:0,serviceWorkerPortMessages:0,
                    framesSeen:0,binaryDecoded:0,forwardedFrames:0,encodedItemsFound:0,decodedItems:0,
                    semanticSignals:0,ignoredTransport:0,decodeErrors:0};
                  const count=key=>{if(Object.prototype.hasOwnProperty.call(counters,key))counters[key]++;};
                  const canonicalConversationPost=(input,init)=>{
                    try{
                      const isRequest=typeof Request!=='undefined'&&input instanceof Request;
                      const url=isRequest?input.url:String(input??'');
                      const method=String(init?.method??(isRequest?input.method:'GET')).toUpperCase();
                      if(method!=='POST')return false;
                      const parsed=new URL(url,location.href),path=parsed.pathname.replace(/\\/+$/,'').toLowerCase();
                      return parsed.origin===location.origin
                        &&(path==='/backend-api/conversation'||path==='/backend-api/f/conversation');
                    }catch(_){return false;}
                  };
                  const submissionJson=(input,init)=>typeof init?.body==='string'?init.body:'';
                  const proRequestSelected=(input,init)=>{
                    try{
                      if(!canonicalConversationPost(input,init))return false;
                      const t=target();if(safe(t?.mode).toLowerCase()!=='chat'||!safe(t?.runId))return false;
                      const raw=submissionJson(input,init);if(!raw)return false;
                      const body=JSON.parse(raw),messages=Array.isArray(body?.messages)?body.messages:[];
                      const latest=messages.length?JSON.stringify(messages[messages.length-1]):'';
                      const run=safe(t.runId);
                      const bootstrap=latest.includes('SELF_RUN_BOOTSTRAP')&&(!run||latest.includes(run));
                      const reasoning=safe(bootstrap?(t.bootstrapReasoning||t.reasoning):(t.continuationReasoning||t.reasoning)).toLowerCase();
                      const model=safe(body?.model).toLowerCase();
                      return reasoning==='pro'||/(?:^|[-_.])pro(?:[-_.]|$)/.test(model);
                    }catch(_){return false;}
                  };
                  const promoteFromRequestProfile=()=>{
                    try{
                      const p=protocol(),before=p?.snapshot?.()||{};
                      if(before.detectorLane==='PRO')return true;
                      if(before.detectorLane!=='CHAT'||!before.requestIdentity
                              ||(before.phase!=='THINKING'&&before.phase!=='ANSWERING'))return false;
                      p.observeSseText('data: {"type":"stream_handoff","source":"request_profile_pro"}\\n\\n',
                        'pro-request-profile',{requestIdentity:before.requestIdentity});
                      return safe(p.snapshot?.()?.detectorLane)==='PRO';
                    }catch(_){return false;}
                  };
                  const blobText=blob=>{
                    if(blob&&typeof blob.text==='function')return blob.text();
                    return new Promise((resolve,reject)=>{try{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result??''));reader.onerror=()=>reject(reader.error);reader.readAsText(blob);}catch(error){reject(error);}});
                  };
                  const decodeBytes=async view=>{
                    if(typeof TextDecoder==='function')return new TextDecoder('utf-8').decode(view);
                    return blobText(new Blob([view]));
                  };
                  const looksSse=text=>/(^|\\n)(?:event|data):/.test(String(text??'').replace(/\\r\\n?/g,'\\n'));
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
                  const decodeBase64Text=value=>{
                    const text=String(value??'').trim();if(text.length<8||text.length%4===1||!/^[A-Za-z0-9+/_-]+={0,2}$/.test(text)||typeof atob!=='function')return null;
                    let normalized=text.replace(/-/g,'+').replace(/_/g,'/').replace(/=+$/g,'');while(normalized.length%4)normalized+='=';
                    try{const binary=atob(normalized),bytes=new Uint8Array(binary.length);for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i)&255;
                      return typeof TextDecoder==='function'?new TextDecoder('utf-8',{fatal:true}).decode(bytes):null;}catch(_){return null;}
                  };
                  const contextFor=(node,parent={})=>({
                    conversationId:safe(node?.conversation_id||parent.conversationId||''),
                    workTurnId:safe(node?.turn_id||parent.workTurnId||'')
                  });
                  const semanticCandidate=node=>{
                    if(!node||typeof node!=='object'||Array.isArray(node))return false;
                    const type=safe(node.type);
                    return ['message_marker','message_stream_complete','message_start','message_delta','message_update','stream_handoff','error'].includes(type)
                      ||(node.status==='finished_successfully'&&node.end_turn===true)
                      ||!!(node.message&&typeof node.message==='object')||!!(node.v?.message&&typeof node.v.message==='object');
                  };
                  const shouldForward=node=>ownsCurrentTurn()||safe(node?.type)==='stream_handoff';
                  const processSemantic=(node,context,decoder)=>{
                    if(!activeChat()||!semanticCandidate(node)||!shouldForward(node))return false;
                    const p=protocol();if(!p||typeof p.observeSseText!=='function')return false;
                    try{p.observeSseText('data: '+JSON.stringify(node)+'\\n\\n','pro-decoder-'+decoder,context||{});count('semanticSignals');return true;}
                    catch(_){count('decodeErrors');return false;}
                  };
                  let encodedItemBudget=0;
                  const processSse=(raw,context,decoder)=>{
                    count('decodedItems');
                    const normalized=String(raw??'').replace(/\\r\\n?/g,'\\n');
                    for(const block of normalized.split(/\\n\\n+/)){
                      const data=[];for(const line of block.split('\\n')){if(line==='data:')data.push('');else if(line.startsWith('data:'))data.push(line.slice(5).replace(/^ /,''));}
                      const text=data.join('\\n').trim();if(!text||text==='[DONE]')continue;
                      try{visitDecoded(JSON.parse(text),context,decoder,0);}catch(_){count('decodeErrors');}
                    }
                  };
                  const inspectEncodedItem=(raw,context)=>{
                    if(encodedItemBudget>=MAX_ENCODED_ITEMS)return;encodedItemBudget++;count('encodedItemsFound');
                    let text=String(raw??'');if(text.length>MAX_ENCODED_ITEM_LENGTH)text=text.slice(0,MAX_ENCODED_ITEM_LENGTH);
                    const trimmed=text.trim();if(!trimmed)return;
                    if(looksSse(trimmed)){processSse(trimmed,context,'sse');return;}
                    let parsed=parseJsonContainer(trimmed);
                    if(parsed!==null){count('decodedItems');visitDecoded(parsed,context,'json',0);return;}
                    if(/%[0-9A-Fa-f]{2}/.test(trimmed)){
                      try{const uri=decodeURIComponent(trimmed);if(looksSse(uri)){processSse(uri,context,'url-sse');return;}
                        parsed=parseJsonContainer(uri);if(parsed!==null){count('decodedItems');visitDecoded(parsed,context,'url-json',0);return;}}catch(_){}
                    }
                    const b64=decodeBase64Text(trimmed);
                    if(b64!==null){
                      if(looksSse(b64)){processSse(b64,context,'b64-sse');return;}
                      parsed=parseJsonContainer(b64);if(parsed!==null){count('decodedItems');visitDecoded(parsed,context,'b64-json',0);return;}
                    }
                  };
                  function visitDecoded(node,parentContext,decoder,depth){
                    if(depth>MAX_DECODE_DEPTH||node==null)return;
                    if(Array.isArray(node)){for(const child of node)visitDecoded(child,parentContext,decoder,depth+1);return;}
                    if(typeof node!=='object')return;
                    const context=contextFor(node,parentContext),type=safe(node.type);
                    if(type==='done')return;
                    if(semanticCandidate(node))processSemantic(node,context,decoder);
                    for(const [key,child] of Object.entries(node)){
                      if(key==='encoded_item'&&typeof child==='string'){inspectEncodedItem(child,context);continue;}
                      if(child&&typeof child==='object')visitDecoded(child,context,decoder,depth+1);
                    }
                  }
                  const fastCandidate=data=>{
                    if(ownsCurrentTurn())return true;
                    if(typeof data==='string')return data.includes('stream_handoff');
                    if(data&&typeof data==='object'){
                      try{return JSON.stringify(data).includes('stream_handoff');}catch(_){return false;}
                    }
                    return false;
                  };
                  const decodeFrame=(frame)=>{
                    if(!activeChat()||!fastCandidate(frame))return false;encodedItemBudget=0;
                    let root=frame;
                    if(typeof frame==='string'){
                      if(looksSse(frame)){processSse(frame,{},'outer-sse');count('forwardedFrames');return true;}
                      try{root=JSON.parse(frame);}catch(_){count('decodeErrors');return false;}
                    }
                    if(!root||typeof root!=='object')return false;
                    visitDecoded(root,{},'outer-json',0);count('forwardedFrames');return true;
                  };
                  const observeTransportData=async data=>{
                    if(!handlesTransport()||!fastCandidate(data))return false;count('framesSeen');
                    try{
                      if(typeof data==='string')return decodeFrame(data);
                      if(typeof Blob!=='undefined'&&data instanceof Blob){count('binaryDecoded');return decodeFrame(await blobText(data));}
                      if(typeof ArrayBuffer!=='undefined'&&data instanceof ArrayBuffer){count('binaryDecoded');return decodeFrame(await decodeBytes(new Uint8Array(data)));}
                      if(typeof ArrayBuffer!=='undefined'&&typeof ArrayBuffer.isView==='function'&&ArrayBuffer.isView(data)){
                        count('binaryDecoded');return decodeFrame(await decodeBytes(new Uint8Array(data.buffer,data.byteOffset,data.byteLength)));}
                      if(data&&typeof data==='object')return decodeFrame(data);
                    }catch(_){count('decodeErrors');return false;}
                    count('ignoredTransport');return false;
                  };
                  const downstreamFetch=window.fetch.bind(window);
                  window.fetch=function(input,init){
                    const hint=proRequestSelected(input,init),result=downstreamFetch(input,init);
                    if(hint){count('requestProfileHints');if(!promoteFromRequestProfile())count('requestHintMisses');}
                    return result;
                  };
                  const NativeWebSocket=window.WebSocket;
                  if(typeof NativeWebSocket==='function'){
                    const WrappedWebSocket=function(...args){const socket=Reflect.construct(NativeWebSocket,args,NativeWebSocket);
                      count('webSocketCreated');try{socket.addEventListener('message',event=>{count('webSocketMessages');void observeTransportData(event.data);});}catch(_){}return socket;};
                    WrappedWebSocket.prototype=NativeWebSocket.prototype;try{Object.setPrototypeOf(WrappedWebSocket,NativeWebSocket);Object.defineProperty(WrappedWebSocket,'name',{value:NativeWebSocket.name});Object.defineProperty(WrappedWebSocket,'length',{value:NativeWebSocket.length});WrappedWebSocket.toString=NativeWebSocket.toString.bind(NativeWebSocket);}catch(_){}
                    window.WebSocket=WrappedWebSocket;
                  }
                  const NativeWorker=window.Worker;
                  if(typeof NativeWorker==='function'){
                    const WrappedWorker=function(...args){const worker=Reflect.construct(NativeWorker,args,NativeWorker);
                      try{worker.addEventListener('message',event=>{count('workerMessages');void observeTransportData(event.data);});}catch(_){}return worker;};
                    WrappedWorker.prototype=NativeWorker.prototype;try{Object.setPrototypeOf(WrappedWorker,NativeWorker);}catch(_){}
                    window.Worker=WrappedWorker;
                  }
                  const NativeSharedWorker=window.SharedWorker;
                  if(typeof NativeSharedWorker==='function'){
                    const WrappedSharedWorker=function(...args){const shared=Reflect.construct(NativeSharedWorker,args,NativeSharedWorker);
                      try{shared?.port?.addEventListener?.('message',event=>{count('sharedWorkerMessages');void observeTransportData(event.data);});shared?.port?.start?.();}catch(_){}return shared;};
                    WrappedSharedWorker.prototype=NativeSharedWorker.prototype;try{Object.setPrototypeOf(WrappedSharedWorker,NativeSharedWorker);}catch(_){}
                    window.SharedWorker=WrappedSharedWorker;
                  }
                  if(navigator.serviceWorker?.addEventListener){
                    navigator.serviceWorker.addEventListener('message',event=>{
                      count('serviceWorkerMessages');void observeTransportData(event.data);
                      for(const port of Array.from(event.ports||[])){try{
                        port.addEventListener('message',portEvent=>{count('serviceWorkerPortMessages');void observeTransportData(portEvent.data);});port.start?.();
                      }catch(_){}}
                    });
                    try{navigator.serviceWorker.startMessages?.();}catch(_){}
                  }
                  window.__selfRunProTurnProtocolIngress={
                    version:ENGINE_VERSION,handlesTransport,ownsCurrentTurn,observeTransportData,
                    diagnostics:()=>({activeChat:activeChat(),ownsCurrentTurn:ownsCurrentTurn(),...counters})
                  };
                })();
                """.replace("__ENGINE_VERSION__", SelfRunScript.quote(ENGINE_VERSION));
    }
}
