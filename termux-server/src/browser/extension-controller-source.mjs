// Serialized as a static, CSP-compatible extension page script by the packager.
// No page-supplied JavaScript or MAIN-world postMessage response is accepted.
export function controllerRuntime(config) {
  'use strict';
  Object.defineProperty(globalThis,'__selfrunMonitorAttest',{configurable:false,writable:false,value:async challenge=>{
    if(typeof challenge!=='string'||challenge.length>100)return null;
    const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(config.installationKey),{name:'HMAC',hash:'SHA-256'},false,['sign']);
    const proof=await crypto.subtle.sign('HMAC',key,new TextEncoder().encode(challenge));
    return [...new Uint8Array(proof)].map(v=>v.toString(16).padStart(2,'0')).join('');
  }});
  const VERSION='selfrun-extension-controller-v1';
  const storageKey=VERSION+':'+config.browserSessionId;
  const headers={'Content-Type':'application/json','Authorization':'Bearer '+config.capability,'X-SelfRun-Owner':config.ownerId};
  const targets=new Map(),readyWaiters=new Map(),inflight=new Map(),completed=new Map(),completedTargets=new Map(),contexts=new Map(),mutationLanes=new Map(),commandQueue=[];
  let connectionId=null,stopping=false,currentTabId=null,streamAbort=null,saveChain=Promise.resolve(),commandRunning=false;
  const originAllowed=url=>{try{return config.targetOrigins.includes(new URL(url).origin);}catch{return false;}};
  const mutating=new Set();
  const observations=new Set(['snapshot','progress','recoveryInput','receiptSnapshot']);
  const cleanup=new Set(['listTargets','idleStatus','discardTarget','registerTarget']);
  const targetId=id=>'ext:'+config.browserSessionId+':'+id;
  const urlKey=url=>{try{const u=new URL(url);return u.origin+u.pathname;}catch{return '';}};
  const isConversation=url=>{try{return /\/c\/[A-Za-z0-9_-]+\/?$/.test(new URL(url).pathname);}catch{return false;}};
  const checkUrl=async(t,url)=>{if(!originAllowed(url))throw fail('Owned target left approved site','TARGET_SCOPE_CHANGED');if(!t.boundUrl){t.boundUrl=urlKey(url);await persist();return;}if(urlKey(url)===t.boundUrl)return;if(t.entryTransition?.deadlineAt>Date.now()&&t.entryTransition.allowed.includes(urlKey(url))){t.boundUrl=urlKey(url);t.url=url;await persist();return;}if(t.released&&!isConversation(t.boundUrl)&&isConversation(url)){t.boundUrl=urlKey(url);t.url=url;await persist();return;}throw fail('Owned target was repurposed','TARGET_SCOPE_CHANGED');};
  const persist=()=>{const value=Object.fromEntries([...targets].map(([id,t])=>[id,{id,tabId:t.tabId,url:t.url,boundUrl:t.boundUrl||null,documentId:t.documentId||null,contentNonce:t.contentNonce||null,bootstrapUrl:t.bootstrapUrl||null,pendingUrl:t.pendingUrl||null,entryUrl:t.entryUrl||null,entryPending:t.entryPending===true,createdBy:t.createdBy||null}]));
    saveChain=saveChain.catch(()=>{}).then(()=>chrome.storage.local.set({[storageKey]:value}));return saveChain;};
  const post=async(path,data)=>{const abort=new AbortController();let timer;
    const expired=new Promise((_,reject)=>{timer=setTimeout(()=>{abort.abort();reject(Error('Local bridge request timeout'));},5000);});
    try{return await Promise.race([expired,(async()=>{const r=await fetch(config.bridgeUrl+path,{method:'POST',headers,body:JSON.stringify({...data,controllerBuild:'selfrun-monitor-only-v1',browserSessionId:config.browserSessionId}),signal:abort.signal});if(!r.ok){await r.body?.cancel().catch(()=>{});throw Object.assign(Error('Local bridge HTTP '+r.status),{httpStatus:r.status});}return r.json();})()]);}
    finally{clearTimeout(timer);}
  };
  const report=(event,data={})=>post('/event',{connectionId,event,...data}).catch(()=>{});
  const fail=(message,code='EXTENSION_COMMAND_FAILED',released=false)=>Object.assign(new Error(message),{code,released});
  const validTarget=id=>{const t=targets.get(id);if(!t||t.closing)throw fail('Target is not owned','TARGET_NOT_OWNED');return t;};
  const liveTarget=async id=>{const t=validTarget(id);let tab;try{tab=await chrome.tabs.get(t.tabId);}catch{throw fail('Owned target is absent','TARGET_LOST');}
    const url=String(tab.url||'');if(url===t.bootstrapUrl)throw fail('Approved entry navigation is pending','PAGE_UNAVAILABLE');if(url&&url!=='about:blank'&&!originAllowed(url))throw fail('Owned target left allowed site','TARGET_SCOPE_CHANGED');
    if(url!=='about:blank')await checkUrl(t,url);return{...t,url,tab};};
  const settleReady=(id,error)=>{for(const waiter of [...(readyWaiters.get(id)||[])])error?waiter.reject(error):waiter.resolve(targets.get(id));};
  const cancelTargetWork=id=>{for(const context of contexts.values())if(context.targetId===id&&context.operation!=='closeTarget'&&context.operation!=='discardTarget')context.cancel();};
  const discardLocal=t=>{
    if(targets.get(t.id)!==t)return false;targets.delete(t.id);t.discarded=true;
    settleReady(t.id,fail('Execution discarded','TARGET_NOT_OWNED'));
    for(const [id,context]of contexts)if(context.targetId===t.id&&context.operation!=='discardTarget'){context.discarded=true;if(context.operation!=='closeTarget'){context.cancel();contexts.delete(id);inflight.delete(id);}}
    for(const [id,target]of completedTargets)if(target===t.id){completedTargets.delete(id);completed.delete(id);}
    mutationLanes.delete(t.id);t.armed=null;t.released=null;t.effectFence=null;t.entryTransition=null;return true;
  };
  const disposeTarget=async(t,check)=>{
    if(!discardLocal(t))return {discarded:true,pageCachePurged:false};
    await persist();let pageCachePurged=!t.documentId||!t.contentNonce;
    if(t.documentId&&t.contentNonce){
      const deactivated=chrome.tabs.sendMessage(t.tabId,{kind:'selfrun.content.dispose',nonce:t.contentNonce},{documentId:t.documentId});
      let timer;const expired=new Promise(resolve=>{timer=setTimeout(()=>resolve(null),Math.max(1,Math.min(1500,check.deadlineAt-Date.now())));});
      const result=await Promise.race([deactivated.catch(()=>null),expired]);clearTimeout(timer);
      pageCachePurged=result?.ok===true;
    }
    return {discarded:true,pageCachePurged};
  };
  const reconcile=async(ownership,check)=>{
    if(ownership?.schema!==1||!Array.isArray(ownership.targets)||!Array.isArray(ownership.creatingCommands))return;
    const allowed=new Set(ownership.targets),creating=new Set(ownership.creatingCommands);
    await Promise.all([...targets.values()].filter(t=>!allowed.has(t.id)&&!creating.has(t.createdBy)).map(t=>disposeTarget(t,check)));
  };
  const ready=async(t,check)=>{
    check();if(t.documentId&&t.contentNonce)return t;
    return new Promise((resolve,reject)=>{
      const waiters=readyWaiters.get(t.id)||new Set();readyWaiters.set(t.id,waiters);let timer;
      const finish=(error,value)=>{clearTimeout(timer);check.signal.removeEventListener('abort',abort);waiters.delete(waiter);if(!waiters.size&&readyWaiters.get(t.id)===waiters)readyWaiters.delete(t.id);error?reject(error):resolve(value);};
      const abort=()=>finish(fail('Command cancelled or expired','COMMAND_CANCELLED'));
      const waiter={resolve:value=>finish(null,value),reject:error=>finish(error)};waiters.add(waiter);
      timer=setTimeout(()=>finish(fail('Page content bridge unavailable','PAGE_UNAVAILABLE')),15000);check.signal.addEventListener('abort',abort,{once:true});
      if(check.signal.aborted)abort();else void chrome.tabs.sendMessage(t.tabId,{kind:'selfrun.content.helloRequest'}).catch(()=>{});
    });
  };
  const content=async(t,kind,check)=>{
    check();await ready(t,check);check();const documentId=t.documentId,nonce=t.contentNonce;
    const result=await chrome.tabs.sendMessage(t.tabId,{kind,nonce,deadlineAt:check.deadlineAt},{documentId});
    check();
    if(t.documentId!==documentId||result?.nonce&&result.nonce!==nonce)throw fail('Page document changed','DOCUMENT_CHANGED');
    if(result?.ok!==true)throw fail(result?.error||'Page content unavailable','PAGE_UNAVAILABLE');if(result.snapshot?.url)await checkUrl(t,result.snapshot.url);return result;
  };
  chrome.runtime.onMessage.addListener((message,sender)=>{
    // sender identity/document metadata is provided by Chrome, not the page.
    if(sender.id!==chrome.runtime.id||sender.frameId!==0||!sender.tab||!sender.documentId)return;
    const t=[...targets.values()].find(t=>t.tabId===sender.tab.id);if(!t||t.closing||!originAllowed(sender.url))return;
    if(message?.kind==='selfrun.content.hello'&&typeof message.nonce==='string'&&message.nonce.length<100){
      const entryChange=t.entryTransition?.deadlineAt>Date.now()&&t.entryTransition.allowed.includes(urlKey(sender.url));
      if(t.documentId&&t.documentId!==sender.documentId&&!entryChange)return;
      if(t.contentNonce&&t.contentNonce!==message.nonce&&!entryChange)return;
      if(t.boundUrl&&urlKey(sender.url)!==t.boundUrl&&!entryChange&&!(t.released&&!isConversation(t.boundUrl)&&isConversation(sender.url)))return;
      const first=!t.documentId||entryChange;t.documentId=sender.documentId;t.contentNonce=message.nonce;t.url=sender.url;
      if(first){t.entryPending=false;t.mainDocumentId=null;t.boundUrl??=urlKey(sender.url);void persist();}
      void chrome.tabs.sendMessage(t.tabId,{kind:'selfrun.content.activate',nonce:t.contentNonce},{documentId:t.documentId}).catch(()=>{});
      settleReady(t.id);void report('targetReady',{target:{id:t.id,url:t.url,type:'page'}});
    } else if(message?.kind==='selfrun.content.dirty'&&message.nonce===t.contentNonce&&sender.documentId===t.documentId)void report('targetDirty',{targetId:t.id});
  });
  chrome.tabs.onRemoved.addListener(id=>{const t=[...targets.values()].find(t=>t.tabId===id),key=t?.id;if(!t)return;
    discardLocal(t);void persist();void report('targetRemoved',{targetId:key});});
  async function execute(operation,payload,check) {
    check();
    if(operation==='registerTarget') {
      if(typeof payload.targetId!=='string'||!payload.targetId.startsWith('ext:'+config.browserSessionId+':')||typeof payload.challenge!=='string'||typeof payload.mac!=='string')throw fail('Invalid observation target');
      if(!originAllowed(payload.expectedUrl))throw fail('Observation URL outside approved scope');
      const candidates=await chrome.tabs.query({});let matched=null,matchedDocumentId=null;
      for(const tab of candidates){
        check();if(tab.url!==payload.expectedUrl||(Number.isSafeInteger(payload.tabId)&&tab.id!==payload.tabId))continue;
        const proof=await chrome.scripting.executeScript({target:{tabId:tab.id},world:'MAIN',func:async challenge=>({url:location.href,mac:await window.__selfrunExtensionPage?.attest(challenge)}),args:[payload.challenge]}).catch(()=>[]);
        check();if(proof.length===1&&proof[0].result?.url===payload.expectedUrl&&proof[0].result.mac===payload.mac){if(matched)throw fail('Observation target proof is ambiguous');matched=tab;matchedDocumentId=proof[0].documentId||null;}
      }
      if(!matched)throw fail('Exact observation target unavailable','TARGET_NOT_OWNED');
      const prior=targets.get(payload.targetId);if(prior&&prior.tabId!==matched.id)throw fail('Observation target changed','TARGET_SCOPE_CHANGED');
      const current=prior||{id:payload.targetId,tabId:matched.id,documentId:null,contentNonce:null};
      if(matchedDocumentId&&current.documentId!==matchedDocumentId){current.documentId=null;current.contentNonce=null;}
      current.url=payload.expectedUrl;current.boundUrl=urlKey(payload.expectedUrl);current.bootstrapUrl=payload.bootstrapUrl||null;
      targets.set(current.id,current);await persist();return{registered:true,id:current.id,tabId:current.tabId};
    }
    if(operation==='listTargets') {
      const list=[];for(const t of targets.values()){try{const tab=await chrome.tabs.get(t.tabId);list.push({id:t.id,type:'page',url:String(tab.url||''),closing:!!t.closing,bootstrapUrl:t.bootstrapUrl||null,pendingUrl:t.pendingUrl||null,entryUrl:t.entryUrl||null,entryPending:t.entryPending===true});}catch{discardLocal(t);}}
      await persist();return list;
    }
    if(operation==='idleStatus') {const tabs=await chrome.tabs.query({});return{stopping:stopping===true,ownedCount:targets.size,unrelatedCount:tabs.filter(t=>t.id!==currentTabId&&!targets.has(targetId(t.id))).length};}
    if(operation==='controllerStop') {const tabs=await chrome.tabs.query({});check();if(targets.size||tabs.some(tab=>tab.id!==currentTabId))return{stopped:false,reason:'browser_in_use'};stopping=true;setTimeout(()=>{streamAbort?.abort();void chrome.tabs.remove(currentTabId);},100);return{stopped:true};}
    if(operation==='discardTarget'){cancelTargetWork(payload.targetId);const t=targets.get(payload.targetId);return t?disposeTarget(t,check):{discarded:true,pageCachePurged:true};}
    const t=validTarget(payload.targetId);
    const live=await liveTarget(t.id);if(!originAllowed(live.url))throw fail('Target is not at approved page','PAGE_UNAVAILABLE');
    if(operation==='snapshot'||operation==='progress')return (await content(t,'selfrun.content.snapshot',check)).snapshot;
    if(operation==='recoveryInput')return (await content(t,'selfrun.content.recoveryInput',check)).snapshot;
    throw fail('Extension is observation-only','OBSERVATION_ONLY');
  }
  const drainCommands=()=>{if(commandRunning||!commandQueue.length)return;const entry=commandQueue.shift();entry.context.queued=null;commandRunning=true;
    Promise.resolve().then(entry.execute).then(entry.resolve,entry.reject).finally(()=>{commandRunning=false;drainCommands();});};
  const enqueueCommand=(execute,context)=>new Promise((resolve,reject)=>{const entry={execute,context,resolve,reject};context.queued=entry;commandQueue.push(entry);drainCommands();});
  async function handle(message) {
    if(message?.kind==='welcome'){connectionId=message.connectionId;const owner=connectionId,abort=new AbortController();const check=()=>{if(connectionId!==owner||Date.now()>=check.deadlineAt)throw fail('Controller connection changed','COMMAND_CANCELLED');};check.signal=abort.signal;check.deadlineAt=Date.now()+2000;check.commandId='reconcile';await reconcile(message.ownership,check);await report('hello',{controllerVersion:VERSION,controllerBuild:'selfrun-monitor-only-v1',extensionVersion:chrome.runtime.getManifest?.().version??null,extensionId:chrome.runtime.id,browserSessionId:config.browserSessionId});return;}
    if(message?.kind==='cancel'&&message.connectionId===connectionId){const context=contexts.get(message.id);if(context)context.cancel();return;}
    if(message?.kind!=='command'||message.connectionId!==connectionId||typeof message.id!=='string')return;
    const id=message.id;
    if(completed.has(id)){await post('/result',completed.get(id)).catch(()=>{});return;}
    if(inflight.has(id))return;
    let rejectCancelled;const cancelled=new Promise((_,reject)=>{rejectCancelled=reject;});cancelled.catch(()=>{});
    const abort=new AbortController();
    const context={cancelled:false,connectionId:message.connectionId,targetId:message.payload?.targetId,operation:message.operation,queued:null,cancel(){if(this.cancelled)return;this.cancelled=true;const reason=fail('Command cancelled or expired','COMMAND_CANCELLED',message.operation==='submit');abort.abort(reason);rejectCancelled(reason);if(this.queued){const index=commandQueue.indexOf(this.queued);if(index>=0)commandQueue.splice(index,1);this.queued.reject(reason);this.queued=null;}}};contexts.set(id,context);
    const deadlineAt=Number.isFinite(message.deadlineAt)?Math.min(message.deadlineAt,Date.now()+60000):0;
    const deadlineTimer=setTimeout(()=>context.cancel(),Math.max(0,deadlineAt-Date.now()));
    const check=()=>{if(context.cancelled||(stopping&&message.operation!=='controllerStop')||connectionId!==context.connectionId||Date.now()>=deadlineAt)throw fail('Command cancelled or expired','COMMAND_CANCELLED');};
    check.deadlineAt=deadlineAt;check.commandId=id;check.signal=abort.signal;check.context=context;
    const executeCommand=async()=>{check();const target=message.payload?.targetId,isMutation=mutating.has(message.operation);
        if(target&&isMutation&&mutationLanes.has(target))throw fail('Prior mutation is still settling','TARGET_MUTATION_PENDING');
        const execution=execute(message.operation,message.payload||{},check);
        if(target&&isMutation){mutationLanes.set(target,id);execution.finally(()=>{if(mutationLanes.get(target)===id)mutationLanes.delete(target);}).catch(()=>{});}
        const result=await Promise.race([execution,cancelled]);check();return result;};
    // Cleanup must reach its target even while an unrelated mutation is stuck.
    // Queued cancellation removes the entry, without interrupting its neighbor.
    const execution=observations.has(message.operation)||cleanup.has(message.operation)?Promise.resolve().then(executeCommand):enqueueCommand(executeCommand,context);
    const work=(async()=>{let response;try{const result=await execution;response={id,connectionId:message.connectionId,ok:true,result};}
      catch(error){response={id,connectionId:message.connectionId,ok:false,error:{message:String(error?.message||error).slice(0,250),code:String(error?.code||'EXTENSION_COMMAND_FAILED'),released:error?.released===true}};}
      clearTimeout(deadlineTimer);if(!context.discarded&&message.operation!=='discardTarget'&&context.targetId&&targets.has(context.targetId)){completed.set(id,response);completedTargets.set(id,context.targetId||response.result?.id||null);while(completed.size>512){const first=completed.keys().next().value;completed.delete(first);completedTargets.delete(first);}}
      // HTTP acknowledgement is independent of execution. Correlation remains
      // pinned to this command/connection; an old report cannot own a new turn.
      void post('/result',response).catch(()=>{});
    })();
    inflight.set(id,work);try{await work;}finally{inflight.delete(id);contexts.delete(id);}
  }
  async function start() {
    currentTabId=(await chrome.tabs.getCurrent())?.id;
    if(!Number.isSafeInteger(currentTabId))throw Error('Controller tab identity is unavailable');
    if(typeof globalThis.navigator?.locks?.request!=='function')throw Error('Controller singleton requires Web Locks');
    let backoff=250;
    while(!stopping){
      try{await post('/session',{});break;}
      catch(error){
        if([400,403,409].includes(error.httpStatus)){await closeSelf();return;}
        await new Promise(resolve=>setTimeout(resolve,backoff));backoff=Math.min(10000,backoff*2);
      }
    }
    // One origin-wide owner covers initialization and all reconnect/backoff.
    // A stale configuration is rejected above before it can hold this lock.
    await navigator.locks.request('selfrun-extension-controller:singleton',{ifAvailable:true},async lock=>{
      if(!lock){await closeSelf();return;}
      await runController();
    });
  }
  async function closeSelf(){
    stopping=true;streamAbort?.abort();for(const context of contexts.values())context.cancel();
    await chrome.tabs.remove(currentTabId);
  }
  async function runController() {
    const storage=await chrome.storage.local.get(null);
    for(const key of Object.keys(storage))if(key.startsWith(VERSION+':')&&key!==storageKey)await chrome.storage.local.remove(key);
    const saved=storage[storageKey]||{};
    for(const [id,t] of Object.entries(saved))if(id.startsWith('ext:'+config.browserSessionId+':')&&Number.isSafeInteger(t.tabId))try{const tab=await chrome.tabs.get(t.tabId);targets.set(id,{id,tabId:t.tabId,url:String(tab.url||''),boundUrl:t.boundUrl||null,documentId:t.documentId||null,contentNonce:t.contentNonce||null,bootstrapUrl:t.bootstrapUrl||null});}catch{}
    let backoff=250;
    while(!stopping){
      try{
        streamAbort=new AbortController();const r=await fetch(config.bridgeUrl+'/events',{method:'POST',body:JSON.stringify({controllerBuild:'selfrun-monitor-only-v1',browserSessionId:config.browserSessionId}),headers,signal:streamAbort.signal,cache:'no-store'});
        if([400,403,409].includes(r.status)){await r.body?.cancel().catch(()=>{});await closeSelf();return;}
        if(!r.ok||!r.body)throw Error('Bridge unavailable');
        backoff=250;const reader=r.body.getReader(),decoder=new TextDecoder();let buffer='';
        while(!stopping){const part=await reader.read();if(part.done)break;buffer+=decoder.decode(part.value,{stream:true});if(buffer.length>2*1024*1024)throw Error('Command frame too large');let i;
          while((i=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,i);buffer=buffer.slice(i+1);if(line.trim())void handle(JSON.parse(line));}}
      }catch{}
      connectionId=null;for(const context of contexts.values())context.cancel();
      if(!stopping){await new Promise(r=>setTimeout(r,backoff));backoff=Math.min(10000,backoff*2);}
    }
  }
  void start().catch(error=>console.error('SelfRun controller startup failed:',String(error?.message||error)));
}
