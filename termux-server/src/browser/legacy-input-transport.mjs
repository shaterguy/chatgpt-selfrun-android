// v1 froze a facade containing startup-captured native transports. A request
// stage CDP interceptor is scoped to the explicitly decided input on such a
// document. New documents use the v2 page runtime and never enable Fetch.
export async function readInputParent(id, authorization) {
  const read=async(path,auth='')=>fetch(path,{credentials:'include',cache:'no-store',signal:AbortSignal.timeout(7000),...(auth?{headers:{authorization:auth}}:{})});
  try {
    let response=await read('/backend-api/conversation/'+encodeURIComponent(id),authorization);
    if(response.status===401||response.status===403||!authorization&&!response.ok){
      const session=await read('/api/auth/session');
      if(session.ok){const value=await session.json(),token=value.accessToken||value.access_token;if(token)response=await read('/backend-api/conversation/'+encodeURIComponent(id),'Bearer '+token);}
    }
    if(!response.ok)return null;
    const graph=await response.json(),tip=graph?.current_node;
    return typeof tip==='string'&&tip&&graph.mapping?.[tip]&&(!graph.conversation_id||graph.conversation_id===id)?tip:null;
  } catch {return null;}
}
export class LegacyInputTransport {
  constructor({session,evaluate,prompt,conversationId,signal,guard=()=>true,retryMs=500,closeTarget=async()=>false}) {
    Object.assign(this,{session,evaluate,prompt,conversationId,signal,guard,retryMs,closeTarget});
    this.pending=new Set();this.active=true;
    this.cancel=()=>{void this.dispose(true);};
  }
  async install() {
    this.remove=this.session.on('Fetch.requestPaused',event=>{void this.paused(event).catch(()=>this.dispose());});
    this.signal?.addEventListener('abort',this.cancel,{once:true});
    await this.session.call('Fetch.enable',{patterns:[{urlPattern:'https://chatgpt.com/backend-api/*conversation*',requestStage:'Request'}]});
    if(!this.current())await this.dispose();
  }
  current(){return this.active&&!this.signal?.aborted&&this.guard();}
  async paused(event) {
    const request=event.request||{},id=event.requestId;
    this.pending.add(id);
    let text=request.postData;
    if(!text&&request.postDataEntries?.every(p=>p.bytes))text=Buffer.concat(request.postDataEntries.map(p=>Buffer.from(p.bytes,'base64'))).toString();
    if(!text&&request.method==='POST'&&event.networkId){try{text=(await this.session.call('Network.getRequestPostData',{requestId:event.networkId})).postData;}catch{}}
    let body;try{body=JSON.parse(text);}catch{}
    const normalize=s=>String(s??'').replace(/[\u200B-\u200D\uFEFF]/g,'').trim();
    let route=false;try{route=['/backend-api/conversation','/backend-api/f/conversation'].includes(new URL(request.url).pathname.replace(/\/+$/,''));}catch{}
    const matching=request.method==='POST'&&route&&body&&(this.conversationId?body.conversation_id===this.conversationId:!body.conversation_id)
      &&body.messages?.some(m=>m.author?.role==='user'&&m.content?.parts?.some(p=>typeof p==='string'&&normalize(p)===normalize(this.prompt)));
    if(!this.current()){await this.fail(id);return;}
    if(!matching){this.pending.delete(id);await this.session.call('Fetch.continueRequest',{requestId:id});return;}
    const authorization=Object.entries(request.headers||{}).find(([key])=>key.toLowerCase()==='authorization')?.[1]||'';
    let parent=null;
    while(this.conversationId&&this.current()&&!parent){
      try{parent=await this.evaluate(`(${readInputParent.toString()})(${JSON.stringify(this.conversationId)},${JSON.stringify(authorization)})`,{timeoutMs:20000,signal:this.signal});}catch{if(!this.current())break;}
      if(!parent&&this.current())await new Promise(resolve=>{const done=()=>{clearTimeout(timer);this.signal?.removeEventListener('abort',done);resolve();};const timer=setTimeout(done,this.retryMs);this.signal?.addEventListener('abort',done,{once:true});});
    }
    if(!this.current()){await this.fail(id);return;}
    if(this.conversationId)body.parent_message_id=parent;
    await this.session.call('Fetch.continueRequest',{requestId:id,postData:Buffer.from(JSON.stringify(body)).toString('base64')});
    this.pending.delete(id);await this.dispose();
  }
  async fail(id){try{await this.session.call('Fetch.failRequest',{requestId:id,errorReason:'Aborted'},{timeoutMs:1500});this.pending.delete(id);return true;}catch{return false;}}
  dispose(terminal=false){
    this.terminal=!!(this.terminal||terminal);
    if(this.disposing)return this.disposing;
    this.active=false;this.signal?.removeEventListener('abort',this.cancel);
    this.disposing=(async()=>{await Promise.allSettled([...this.pending].map(id=>this.fail(id)));
      if(this.pending.size||this.terminal){let closed=false;try{closed=await this.closeTarget();}catch{}if(!closed)return false;this.pending.clear();}
      try{await this.session.call('Fetch.disable',{}, {timeoutMs:1500});}catch{}this.remove?.();return true;})();
    this.disposing.then(safe=>{if(!safe)this.disposing=null;});
    return this.disposing;
  }
}
