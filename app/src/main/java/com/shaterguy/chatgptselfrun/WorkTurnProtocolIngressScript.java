package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/** Work-only transport adapter for the shared response protocol state machine. */
final class WorkTurnProtocolIngressScript {
    static final String ENGINE_VERSION = "work-turn-ingress-v1";
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
                  const findDone=node=>{
                    if(Array.isArray(node)){for(const item of node){const found=findDone(item);if(found)return found;}return null;}
                    if(!node||typeof node!=='object')return null;
                    if(safe(node.type).toLowerCase()==='done'){
                      const conversationId=safe(node.conversation_id),workTurnId=safe(node.turn_id);
                      if(conversationId&&workTurnId)return{conversationId,workTurnId};
                    }
                    for(const child of Object.values(node))if(child&&typeof child==='object'){
                      const found=findDone(child);if(found)return found;
                    }
                    return null;
                  };
                  const observeSocketFrame=frame=>{
                    if(!workMode())return false;
                    let root;try{root=typeof frame==='string'?JSON.parse(frame):frame;}catch(_){return false;}
                    const done=findDone(root);if(!done)return false;
                    try{
                      const p=protocol();if(!p||typeof p.observeSseText!=='function')return false;
                      const semantic=JSON.stringify({type:'message_stream_complete',conversation_id:done.conversationId,turn_id:done.workTurnId});
                      p.observeSseText('data: '+semantic+'\\n\\n','work-websocket-done',
                        {conversationId:done.conversationId,workTurnId:done.workTurnId});
                      return true;
                    }catch(_){return false;}
                  };
                  const NativeWebSocket=window.WebSocket;
                  if(typeof NativeWebSocket==='function'){
                    const WrappedWebSocket=function(...args){
                      const socket=Reflect.construct(NativeWebSocket,args,NativeWebSocket);
                      try{socket.addEventListener('message',event=>{if(typeof event.data==='string')observeSocketFrame(event.data);});}catch(_){}
                      return socket;
                    };
                    WrappedWebSocket.prototype=NativeWebSocket.prototype;try{Object.setPrototypeOf(WrappedWebSocket,NativeWebSocket);}catch(_){}
                    window.WebSocket=WrappedWebSocket;
                  }
                  window.__selfRunWorkTurnProtocolIngress={
                    version:ENGINE_VERSION,observeRequest,observeSocketFrame,
                    diagnostics:()=>({mode:safe(target()?.mode),protocol:!!protocol()})
                  };
                })();
                """.replace("__ENGINE_VERSION__", SelfRunScript.quote(ENGINE_VERSION));
    }
}
