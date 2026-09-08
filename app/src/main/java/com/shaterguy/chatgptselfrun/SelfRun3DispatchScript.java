package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.Set;
import org.json.JSONObject;

/** One-shot outgoing canonical request observer. Never reads an assistant response. */
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
        return "window.__selfRun3Dispatch.arm(" + SelfRunScript.quote(task) + ","
                + SelfRunScript.quote(turn) + "," + SelfRunScript.quote(request) + ")";
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
                  const reject=()=>{throw new Error('SELFRUN_DISPATCH_IDENTITY_REJECTED');};
                  const claim=text=>{
                    if(!owner||claimed)reject();
                    let b;try{b=JSON.parse(text);}catch(_){reject();}
                    const messages=b?.messages,last=Array.isArray(messages)?messages.at(-1):null;
                    if(b?.conversation_id||location.pathname.split('/').includes('c')
                      ||last?.author?.role!=='user'||!JSON.stringify(last).includes(owner.turn)||!JSON.stringify(last).includes(owner.request))reject();
                    claimed=true;return {...owner};
                  };
                  const emit=o=>{try{window.selfRun3Dispatch.postMessage(JSON.stringify({
                    runId:o.task,turnToken:o.request,stage:'turn_request',source:'canonical_post'
                  }));}catch(_){}};
                  const captureIdentity=async(response,o)=>{
                    let reader,timer;
                    try{
                      reader=response.clone().body?.getReader?.();if(!reader)return;
                      const first=await Promise.race([reader.read(),new Promise(resolve=>{
                        timer=setTimeout(()=>resolve(null),1500);
                      })]);
                      if(!first||first.done||!first.value||first.value.byteLength>16384)return;
                      const text=new TextDecoder().decode(first.value);
                      for(const line of text.split(String.fromCharCode(10))){
                        if(!line.startsWith('data:'))continue;
                        let metadata;try{metadata=JSON.parse(line.slice(5).trim());}catch(_){continue;}
                        const id=metadata?.conversation_id;
                        if(typeof id!=='string'||!/^[A-Za-z0-9_-]{1,128}$/.test(id))continue;
                        window.selfRun3Dispatch.postMessage(JSON.stringify({
                          runId:o.task,turnId:o.turn,turnToken:o.request,
                          stage:'conversation_identity',conversationId:id
                        }));break;
                      }
                    }catch(_){}finally{
                      if(timer)clearTimeout(timer);
                      try{reader?.cancel?.()?.catch?.(()=>{});}catch(_){}
                    }
                  };
                  const fetch=window.fetch.bind(window);
                  window.fetch=async function(input,init){
                    const isRequest=typeof Request!=='undefined'&&input instanceof Request;
                    const method=init?.method??(isRequest?input.method:'GET');
                    const url=isRequest?input.url:String(input??'');
                    if(!canonical(method,url))return fetch(input,init);
                    const r=new Request(input,init);
                    const o=claim(await r.clone().text());
                    const result=fetch(input,init);emit(o);
                    result.then(response=>captureIdentity(response,o),()=>{});return result;
                  };
                  const open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send,meta=new WeakMap();
                  XMLHttpRequest.prototype.open=function(method,url,...rest){
                    meta.set(this,{method,url});return open.call(this,method,url,...rest);
                  };
                  XMLHttpRequest.prototype.send=function(body){
                    const m=meta.get(this);
                    if(!m||!canonical(m.method,m.url))return send.call(this,body);
                    const o=claim(body);const result=send.call(this,body);emit(o);return result;
                  };
                  window.__selfRun3Dispatch={arm:(task,turn,request)=>{
                    if(owner)return owner.task===task&&owner.turn===turn&&owner.request===request&&!claimed;
                    if(!task||!turn||!request)return false;
                    owner={task,turn,request};return true;
                  }};
                })();
                """;
    }
}
