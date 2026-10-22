// Serialized into the private page runtime. No network work is started by
// installation, observation, or preparation; only a matching submitted input
// reads the conversation immediately before forwarding its original POST.
export function inputParentRuntime({nativeFetch,href,retryMs=500,apply=Reflect.apply,onForward=()=>{}}) {
  let current=null;
  const xhrMetadata=new WeakMap();
  const conversation=()=>{try{return new URL(href()).pathname.match(/\/c\/([^/]+)\/?$/)?.[1]||'';}catch{return '';}};
  const normalize=value=>String(value??'').replace(/[\u200B-\u200D\uFEFF]/g,'').trim();
  const dispose=()=>{current?.abort.abort(new DOMException('Input preparation disposed','AbortError'));current=null;};
  const prepare=prompt=>{dispose();const id=conversation();if(id&&!id.startsWith('WEB:'))current={id,prompt:normalize(prompt),abort:new AbortController()};return true;};
  const check=arm=>{if(current!==arm||arm.abort.signal.aborted||conversation()!==arm.id)throw arm.abort.signal.reason||new DOMException('Input preparation cancelled','AbortError');};
  const matches=(body,arm)=>body?.conversation_id===arm.id&&Array.isArray(body.messages)
    &&body.messages.some(m=>m.author?.role==='user'&&m.content?.parts?.some(p=>typeof p==='string'&&normalize(p)===arm.prompt));
  const eligible=(url,method)=>{try{const u=new URL(url,href());return String(method).toUpperCase()==='POST'&&u.origin===new URL(href()).origin&&['/backend-api/conversation','/backend-api/f/conversation'].includes(u.pathname.replace(/\/+$/,''));}catch{return false;}};
  const wait=arm=>new Promise((resolve,reject)=>{
    const aborted=()=>{clearTimeout(timer);reject(arm.abort.signal.reason);};
    const timer=setTimeout(()=>{arm.abort.signal.removeEventListener('abort',aborted);resolve();},retryMs);
    arm.abort.signal.addEventListener('abort',aborted,{once:true});if(arm.abort.signal.aborted)aborted();
  });
  const read=async(path,arm,authorization='')=>{
    check(arm);
    const signal=AbortSignal.any([arm.abort.signal,AbortSignal.timeout(7000)]);
    return nativeFetch(new URL(path,href()).href,{credentials:'include',cache:'no-store',signal,
      ...(authorization?{headers:{Authorization:authorization}}:{})});
  };
  const parent=async(arm,authorization)=>{
    // Transient reads stay inside the already-decided send. They do not pause
    // the task, create a new error status, or forward a known stale parent.
    while(true){
      check(arm);
      try{
        let response=await read('/backend-api/conversation/'+encodeURIComponent(arm.id),arm,authorization);
        if(response.status===401||response.status===403||!authorization&&!response.ok){
          const session=await read('/api/auth/session',arm);
          if(session.ok){const value=await session.json(),token=value.accessToken||value.access_token;if(token){authorization='Bearer '+token;response=await read('/backend-api/conversation/'+encodeURIComponent(arm.id),arm,authorization);}}
        }
        if(response.ok){
          const graph=await response.json(),id=graph?.current_node;
          if(typeof id==='string'&&id&&graph.mapping?.[id]&&(!graph.conversation_id||graph.conversation_id===arm.id)){check(arm);return id;}
        }
      }catch(error){if(arm.abort.signal.aborted||current!==arm)throw arm.abort.signal.reason||error;}
      check(arm);await wait(arm);
    }
  };
  const forward=async(request,body,arm)=>{
    const id=await parent(arm,request.headers.get('authorization')||'');check(arm);
    const updated=new Request(request,{body:JSON.stringify({...body,parent_message_id:id}),
      signal:AbortSignal.any([request.signal,arm.abort.signal])});
    onForward();return nativeFetch(updated);
  };
  const fetchInput=function(input,init){
    const arm=current;if(!arm)return nativeFetch(input,init);
    const isRequest=typeof Request!=='undefined'&&input instanceof Request;
    if(!eligible(isRequest?input.url:input,init?.method||(isRequest?input.method:'GET')))return nativeFetch(input,init);
    return (async()=>{
      const request=new Request(isRequest?input.clone():new URL(input,href()).href,init);
      const body=await request.clone().json();check(arm);
      if(!matches(body,arm))return nativeFetch(input,init);
      return forward(request,body,arm);
    })();
  };
  return {prepare,dispose,fetch:fetchInput,
    openXhr:(xhr,method,url)=>xhrMetadata.set(xhr,{method,url,authorization:''}),
    headerXhr:(xhr,name,value)=>{const meta=xhrMetadata.get(xhr);if(meta&&String(name).toLowerCase()==='authorization')meta.authorization=String(value);},
    sendXhr(xhr,body,nativeSend){
      const arm=current,meta=xhrMetadata.get(xhr);let value;
      if(!arm||!meta||!eligible(meta.url,meta.method)||typeof body!=='string')return apply(nativeSend,xhr,[body]);
      try{value=JSON.parse(body);}catch{return apply(nativeSend,xhr,[body]);}
      if(!matches(value,arm))return apply(nativeSend,xhr,[body]);
      void parent(arm,meta.authorization).then(id=>{check(arm);onForward();apply(nativeSend,xhr,[JSON.stringify({...value,parent_message_id:id})]);}).catch(()=>{try{xhr.abort?.();}catch{}});
      return undefined;
    }};
}
