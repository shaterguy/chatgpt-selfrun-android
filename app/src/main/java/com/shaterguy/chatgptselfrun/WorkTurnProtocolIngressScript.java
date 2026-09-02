package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/** Work-only transport adapter for the shared response protocol state machine. */
final class WorkTurnProtocolIngressScript {
    static final String ENGINE_VERSION = "work-turn-ingress-v2";
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
                  const safe=value=>String(value??'').slice(0,256);
                  const target=()=>{try{return window.__selfRunRequestProfileEngine?.target?.()||null;}catch(_){return null;}};
                  const workMode=()=>safe(target()?.mode).toLowerCase()==='work';
                  const protocol=()=>{try{return window.__selfRunTurnProtocol||null;}catch(_){return null;}};
                  const counters={webSocketMessages:0,workerMessages:0,sharedWorkerMessages:0,
                    binaryDecoded:0,forwardedFrames:0,ignoredTransport:0,decodeErrors:0};
                  const count=key=>{if(Object.prototype.hasOwnProperty.call(counters,key))counters[key]++;};
                  const canonicalConversation=(method,url)=>{
                    if(String(method??'').toUpperCase()!=='POST')return false;
                    try{
                      const parsed=new URL(url,location.href);if(parsed.origin!==location.origin)return false;
                      let path=parsed.pathname;if(path.length>1)path=path.replace(/\\/+$/,'');
                      return path==='/backend-api/f/conversation';
                    }catch(_){return false;}
                  };
                  const observeRequest=(method,url)=>{
                    if(!workMode()||!canonicalConversation(method,url))return false;
                    try{const p=protocol();if(!p||typeof p.observeRequest!=='function')return false;p.observeRequest(method,url);return true;}catch(_){return false;}
                  };
                  const nativeOpen=XMLHttpRequest.prototype.open,nativeSend=XMLHttpRequest.prototype.send,xhrMeta=new WeakMap();
                  XMLHttpRequest.prototype.open=function(method,url,...rest){
                    xhrMeta.set(this,{method:String(method||''),url:String(url||'')});
                    return nativeOpen.call(this,method,url,...rest);
                  };
                  XMLHttpRequest.prototype.send=function(body){
                    const meta=xhrMeta.get(this)||{method:'',url:''};
                    const eligible=workMode()&&canonicalConversation(meta.method,meta.url);
                    const result=nativeSend.call(this,body);
                    if(eligible)observeRequest(meta.method,meta.url);
                    return result;
                  };
                  const forwardFrame=(frame,source)=>{
                    if(!workMode()){count('ignoredTransport');return false;}
                    try{
                      const p=protocol();if(!p){count('ignoredTransport');return false;}
                      const observe=typeof p.observeWorkFrame==='function'?p.observeWorkFrame:
                        (typeof p.observeSocketFrame==='function'?p.observeSocketFrame:null);
                      if(!observe){count('ignoredTransport');return false;}
                      observe.call(p,frame);count('forwardedFrames');return true;
                    }catch(_){count('decodeErrors');return false;}
                  };
                  const blobText=blob=>{
                    if(blob&&typeof blob.text==='function')return blob.text();
                    return new Promise((resolve,reject)=>{
                      try{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result??''));reader.onerror=()=>reject(reader.error);reader.readAsText(blob);}catch(error){reject(error);}
                    });
                  };
                  const decodeBytes=async view=>{
                    if(typeof TextDecoder==='function')return new TextDecoder().decode(view);
                    return blobText(new Blob([view]));
                  };
                  const observeTransportData=async(data,source)=>{
                    if(!workMode())return false;
                    try{
                      if(typeof data==='string')return forwardFrame(data,source);
                      if(typeof Blob!=='undefined'&&data instanceof Blob){
                        count('binaryDecoded');return forwardFrame(await blobText(data),source);
                      }
                      if(typeof ArrayBuffer!=='undefined'&&data instanceof ArrayBuffer){
                        count('binaryDecoded');return forwardFrame(await decodeBytes(new Uint8Array(data)),source);
                      }
                      if(typeof ArrayBuffer!=='undefined'&&typeof ArrayBuffer.isView==='function'&&ArrayBuffer.isView(data)){
                        count('binaryDecoded');return forwardFrame(await decodeBytes(new Uint8Array(data.buffer,data.byteOffset,data.byteLength)),source);
                      }
                      if(data&&typeof data==='object')return forwardFrame(data,source);
                    }catch(_){count('decodeErrors');return false;}
                    count('ignoredTransport');return false;
                  };
                  const NativeWebSocket=window.WebSocket;
                  if(typeof NativeWebSocket==='function'){
                    const WrappedWebSocket=function(...args){
                      const socket=Reflect.construct(NativeWebSocket,args,NativeWebSocket);
                      try{socket.addEventListener('message',event=>{count('webSocketMessages');void observeTransportData(event.data,'work-websocket');});}catch(_){}
                      return socket;
                    };
                    WrappedWebSocket.prototype=NativeWebSocket.prototype;try{Object.setPrototypeOf(WrappedWebSocket,NativeWebSocket);}catch(_){}
                    window.WebSocket=WrappedWebSocket;
                  }
                  const NativeWorker=window.Worker;
                  if(typeof NativeWorker==='function'){
                    const WrappedWorker=function(...args){
                      const worker=Reflect.construct(NativeWorker,args,NativeWorker);
                      try{worker.addEventListener('message',event=>{count('workerMessages');void observeTransportData(event.data,'work-worker');});}catch(_){}
                      return worker;
                    };
                    WrappedWorker.prototype=NativeWorker.prototype;try{Object.setPrototypeOf(WrappedWorker,NativeWorker);}catch(_){}
                    window.Worker=WrappedWorker;
                  }
                  const NativeSharedWorker=window.SharedWorker;
                  if(typeof NativeSharedWorker==='function'){
                    const WrappedSharedWorker=function(...args){
                      const shared=Reflect.construct(NativeSharedWorker,args,NativeSharedWorker);
                      try{shared?.port?.addEventListener?.('message',event=>{count('sharedWorkerMessages');void observeTransportData(event.data,'work-shared-worker');});}catch(_){}
                      return shared;
                    };
                    WrappedSharedWorker.prototype=NativeSharedWorker.prototype;try{Object.setPrototypeOf(WrappedSharedWorker,NativeSharedWorker);}catch(_){}
                    window.SharedWorker=WrappedSharedWorker;
                  }
                  window.__selfRunWorkTurnProtocolIngress={
                    version:ENGINE_VERSION,observeRequest,
                    observeSocketFrame:frame=>observeTransportData(frame,'work-manual'),
                    observeTransportData,
                    diagnostics:()=>({mode:safe(target()?.mode),protocol:!!protocol(),...counters})
                  };
                })();
                """.replace("__ENGINE_VERSION__", SelfRunScript.quote(ENGINE_VERSION));
    }
}
