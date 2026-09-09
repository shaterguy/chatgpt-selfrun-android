package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.Set;
import org.json.JSONObject;

/** One-shot, non-blocking canonical POST observer for SelfRun 3.1. */
final class SelfRun3DispatchScript {
    private SelfRun3DispatchScript() {}

    static boolean install(WebView web) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false;
        Set<String> origins = Set.of("https://chatgpt.com", "https://www.chatgpt.com");
        WebViewCompat.addWebMessageListener(web, "selfRun3Dispatch", origins,
                (view, message, origin, main, reply) -> {
                    if (!main || message.getType() != WebMessageCompat.TYPE_STRING) return;
                    String raw = message.getData();
                    if (raw == null || raw.length() > 2048) return;
                    try { SelfRun3WebAdapter.protocolEvent(view, new JSONObject(raw)); }
                    catch (Exception ignored) {}
                });
        WebViewCompat.addDocumentStartJavaScript(web, documentStartScript(), origins);
        return true;
    }

    static String arm(String task, String turn, String request) {
        return "window.__selfRun3Dispatch&&window.__selfRun3Dispatch.arm("
                + SelfRunScript.quote(task) + "," + SelfRunScript.quote(turn) + ","
                + SelfRunScript.quote(request) + ")";
    }

    static String documentStartScript() {
        return """
                (()=>{
                  if(window!==window.top||window.__selfRun3Dispatch)return;
                  let owner=null,claimed=false;
                  const canonical=(method,url)=>{try{
                    const u=new URL(url,location.href);
                    return String(method).toUpperCase()==='POST'&&u.origin===location.origin
                      &&u.pathname.replace(/\\/+$/,'')==='/backend-api/f/conversation';
                  }catch(_){return false;}};
                  const matches=text=>{try{
                    if(!owner||claimed||typeof text!=='string')return false;
                    const b=JSON.parse(text);
                    const messages=b?.messages,last=Array.isArray(messages)?messages.at(-1):null;
                    if(b?.conversation_id||last?.author?.role!=='user')return false;
                    const raw=JSON.stringify(last);
                    return raw.includes(owner.turn)&&raw.includes(owner.request);
                  }catch(_){return false;}};
                  const emit=()=>{if(!owner||claimed)return;claimed=true;try{
                    window.selfRun3Dispatch.postMessage(JSON.stringify({runId:owner.task,turnId:owner.turn,
                      turnToken:owner.request,stage:'turn_request',source:'canonical_post'}));
                  }catch(_){}};
                  const nativeFetch=window.fetch.bind(window);
                  window.fetch=function(input,init){
                    let matched=false;
                    try{
                      const isRequest=typeof Request!=='undefined'&&input instanceof Request;
                      const method=init?.method??(isRequest?input.method:'GET');
                      const url=isRequest?input.url:String(input??'');
                      if(canonical(method,url)){
                        matched=matches(init?.body);
                        if(!matched&&isRequest){
                          try{input.clone().text().then(text=>{if(matches(text))emit();},()=>{});}catch(_){}
                        }
                      }
                    }catch(_){}
                    const result=nativeFetch(input,init);
                    if(matched)emit();
                    return result;
                  };
                  const open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send,meta=new WeakMap();
                  XMLHttpRequest.prototype.open=function(method,url,...rest){
                    try{meta.set(this,{method,url});}catch(_){}
                    return open.call(this,method,url,...rest);
                  };
                  XMLHttpRequest.prototype.send=function(body){
                    let matched=false;
                    try{const m=meta.get(this);matched=!!m&&canonical(m.method,m.url)&&matches(body);}catch(_){}
                    const result=send.call(this,body);
                    if(matched)emit();
                    return result;
                  };
                  window.__selfRun3Dispatch={arm:(task,turn,request)=>{
                    if(!task||!turn||!request)return false;
                    if(owner)return owner.task===task&&owner.turn===turn&&owner.request===request&&!claimed;
                    owner={task,turn,request};claimed=false;return true;
                  }};
                })();
                """;
    }
}
