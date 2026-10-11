// Serialized as a static, CSP-compatible extension page script by the packager.
// No page-supplied JavaScript or MAIN-world postMessage response is accepted.
export function controllerRuntime(config) {
  'use strict';
  const VERSION='selfrun-extension-controller-v1';
  const storageKey=VERSION+':'+config.browserSessionId;
  const headers={'Content-Type':'application/json','Authorization':'Bearer '+config.capability,'X-SelfRun-Owner':config.ownerId};
  const targets=new Map(),readyWaiters=new Map(),inflight=new Map(),completed=new Map(),contexts=new Map(),mutationLanes=new Map(),commandQueue=[];
  let connectionId=null,stopping=false,currentTabId=null,streamAbort=null,saveChain=Promise.resolve(),commandRunning=false;
  const originAllowed=url=>{try{return config.targetOrigins.includes(new URL(url).origin);}catch{return false;}};
  const mutating=new Set(['initialize','navigate','newChat','input','submit','installProfile','profileConfigure','installReceipt','receiptArm','receiptRetire','pauseAnimations','quiesce']);
  const observations=new Set(['snapshot','progress','recoveryInput','receiptSnapshot']);
  const cleanup=new Set(['listTargets','idleStatus','closeTarget','restoreTarget']);
  const targetId=id=>'ext:'+config.browserSessionId+':'+id;
  const urlKey=url=>{try{const u=new URL(url);return u.origin+u.pathname;}catch{return '';}};
  const isConversation=url=>{try{return /\/c\/[A-Za-z0-9_-]+\/?$/.test(new URL(url).pathname);}catch{return false;}};
  const checkUrl=async(t,url)=>{if(!originAllowed(url))throw fail('Owned target left approved site','TARGET_SCOPE_CHANGED');if(!t.boundUrl){t.boundUrl=urlKey(url);await persist();return;}if(urlKey(url)===t.boundUrl)return;if(t.entryTransition?.deadlineAt>Date.now()&&t.entryTransition.allowed.includes(urlKey(url))){t.boundUrl=urlKey(url);t.url=url;await persist();return;}if(t.released&&!isConversation(t.boundUrl)&&isConversation(url)){t.boundUrl=urlKey(url);t.url=url;await persist();return;}throw fail('Owned target was repurposed','TARGET_SCOPE_CHANGED');};
  const persist=()=>{const value=Object.fromEntries([...targets].map(([id,t])=>[id,{id,tabId:t.tabId,url:t.url,boundUrl:t.boundUrl||null,documentId:t.documentId||null,contentNonce:t.contentNonce||null,armed:t.armed||null,released:t.released||null,entryTransition:t.entryTransition||null,effectFence:t.effectFence||null,bootstrapUrl:t.bootstrapUrl||null,pendingUrl:t.pendingUrl||null,entryUrl:t.entryUrl||null,entryPending:t.entryPending===true}]));
    saveChain=saveChain.catch(()=>{}).then(()=>chrome.storage.local.set({[storageKey]:value}));return saveChain;};
  const post=async(path,data)=>{const abort=new AbortController();let timer;
    const expired=new Promise((_,reject)=>{timer=setTimeout(()=>{abort.abort();reject(Error('Local bridge request timeout'));},5000);});
    try{return await Promise.race([expired,(async()=>{const r=await fetch(config.bridgeUrl+path,{method:'POST',headers,body:JSON.stringify({...data,browserSessionId:config.browserSessionId}),signal:abort.signal});if(!r.ok)throw Error('Local bridge HTTP '+r.status);return r.json();})()]);}
    finally{clearTimeout(timer);}
  };
  const report=(event,data={})=>post('/event',{connectionId,event,...data}).catch(()=>{});
  const fail=(message,code='EXTENSION_COMMAND_FAILED',released=false)=>Object.assign(new Error(message),{code,released});
  const validTarget=id=>{const t=targets.get(id);if(!t||t.closing)throw fail('Target is not owned','TARGET_NOT_OWNED');return t;};
  const liveTarget=async id=>{const t=validTarget(id);let tab;try{tab=await chrome.tabs.get(t.tabId);}catch{throw fail('Owned target is absent','TARGET_LOST');}
    const url=String(tab.url||'');if(url===t.bootstrapUrl)throw fail('Approved entry navigation is pending','PAGE_UNAVAILABLE');if(url&&url!=='about:blank'&&!originAllowed(url))throw fail('Owned target left allowed site','TARGET_SCOPE_CHANGED');
    if(url!=='about:blank')await checkUrl(t,url);return{...t,url,tab};};
  const settleReady=(id,error)=>{for(const waiter of [...(readyWaiters.get(id)||[])])error?waiter.reject(error):waiter.resolve(targets.get(id));};
  const cancelTargetWork=id=>{for(const context of contexts.values())if(context.targetId===id&&context.operation!=='closeTarget')context.cancel();};
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
  const main=async(t,operation,payload,check)=>{
    check();await ready(t,check);check();const documentId=t.documentId;
    if(t.mainDocumentId!==documentId){
      const challenge=crypto.randomUUID();const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(config.installationKey),{name:'HMAC',hash:'SHA-256'},false,['sign']);
      const expected=Array.from(new Uint8Array(await crypto.subtle.sign('HMAC',key,new TextEncoder().encode(challenge))),x=>x.toString(16).padStart(2,'0')).join('');check();
      const proof=await chrome.scripting.executeScript({target:{tabId:t.tabId,documentIds:[documentId]},world:'MAIN',func:async(_kind,challenge)=>({url:location.href,mac:await window.__selfrunExtensionPage?.attest(challenge)}),args:['selfrun-attest',challenge]});
      check();if(t.documentId!==documentId||proof.length!==1||proof[0].documentId!==documentId||proof[0].result?.mac!==expected||urlKey(proof[0].result.url)!==t.boundUrl)throw fail('Page bootstrap is not verified','PAGE_BOOTSTRAP_UNVERIFIED');t.mainDocumentId=documentId;
    }
    if(mutating.has(operation)){if(t.effectFence&&t.effectFence.deadlineAt>Date.now())throw fail('Prior document mutation is still settling','TARGET_MUTATION_PENDING');t.effectFence={id:check.commandId,deadlineAt:check.deadlineAt,documentId};await persist();}
    check();let values;try{values=await chrome.scripting.executeScript({target:{tabId:t.tabId,documentIds:[documentId]},world:'MAIN',
      func:(name,data,expected,deadline,installationKey,nonce)=>{if(Date.now()>=deadline)throw Error('COMMAND_EXPIRED');const u=new URL(location.href);if(u.origin+u.pathname!==expected)throw Error('TARGET_SCOPE_CHANGED');const helper=window.__selfrunExtensionPage;if(!helper)throw Error('PAGE_BOOTSTRAP_UNVERIFIED');return{url:location.href,value:helper.run(name,data,nonce,expected,deadline,installationKey)};},args:[operation,payload,t.boundUrl,check.deadlineAt,config.installationKey,t.contentNonce]});}finally{if(t.effectFence?.id===check.commandId){t.effectFence=null;await persist();}}
    check();if(t.documentId!==documentId||values.length!==1||values[0].documentId!==documentId||!originAllowed(values[0].result?.url))throw fail('Page document changed','DOCUMENT_CHANGED');
    await checkUrl(t,values[0].result.url);return values[0].result.value;
  };
  chrome.runtime.onMessage.addListener((message,sender)=>{
    // sender identity/document metadata is provided by Chrome, not the page.
    if(sender.id!==chrome.runtime.id||sender.frameId!==0||!sender.tab||!sender.documentId)return;
    const t=targets.get(targetId(sender.tab.id));if(!t||t.closing||!originAllowed(sender.url))return;
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
  chrome.tabs.onRemoved.addListener(id=>{const key=targetId(id),t=targets.get(key);if(!t)return;
    targets.delete(key);settleReady(key,fail('Owned target closed','TARGET_CLOSED'));cancelTargetWork(key);void persist();void report('targetRemoved',{targetId:key});});
  async function execute(operation,payload,check) {
    check();
    if(operation==='createTarget') {
      if(payload.url!=='about:blank'&&!originAllowed(payload.url))throw fail('Target URL is outside approved scope');
      const bootstrapUrl=config.bridgeUrl+'/bootstrap/'+crypto.randomUUID();
      const tab=await chrome.tabs.create({url:bootstrapUrl,active:false});const id=targetId(tab.id);
      const t={id,tabId:tab.id,url:bootstrapUrl,bootstrapUrl,pendingUrl:payload.deferNavigation&&payload.url!=='about:blank'?payload.url:null,entryUrl:payload.url!=='about:blank'?payload.url:null,entryPending:true,boundUrl:payload.url==='about:blank'?null:urlKey(payload.url),documentId:null,contentNonce:null};targets.set(id,t);await persist();
      try{check();}catch(error){try{const current=await chrome.tabs.get(tab.id);if(current.url===bootstrapUrl){await chrome.tabs.remove(tab.id);targets.delete(id);await persist();}}catch{}throw error;}
      if(payload.url!=='about:blank'&&!payload.deferNavigation)await chrome.tabs.update(tab.id,{url:payload.url});
      return{id,type:'page',url:payload.url==='about:blank'||payload.deferNavigation?bootstrapUrl:payload.url,bootstrapUrl,pendingUrl:t.pendingUrl,entryUrl:t.entryUrl,entryPending:t.entryPending};
    }
    if(operation==='listTargets') {
      const list=[];for(const t of targets.values()){try{const tab=await chrome.tabs.get(t.tabId);list.push({id:t.id,type:'page',url:String(tab.url||''),closing:!!t.closing,bootstrapUrl:t.bootstrapUrl||null,pendingUrl:t.pendingUrl||null,entryUrl:t.entryUrl||null,entryPending:t.entryPending===true});}catch{targets.delete(t.id);}}
      await persist();return list;
    }
    if(operation==='idleStatus') {const tabs=await chrome.tabs.query({});return{stopping:stopping===true,ownedCount:targets.size,unrelatedCount:tabs.filter(t=>t.id!==currentTabId&&!targets.has(targetId(t.id))).length};}
    if(operation==='controllerStop') {const tabs=await chrome.tabs.query({});check();if(targets.size||tabs.some(tab=>tab.id!==currentTabId))return{stopped:false,reason:'browser_in_use'};stopping=true;setTimeout(()=>{streamAbort?.abort();void chrome.tabs.remove(currentTabId);},100);return{stopped:true};}
    if(operation==='restoreTarget') {
      // Rebind the exact tab after the controller loses its own volatile registry.
      const id=String(payload.targetId||''),prefix='ext:'+config.browserSessionId+':';
      const suffix=id.startsWith(prefix)?id.slice(prefix.length):'';
      if(!/^[1-9][0-9]*$/.test(suffix)||!Number.isSafeInteger(Number(suffix)))throw fail('Target identity is invalid','TARGET_LOST');
      const tabId=Number(suffix);
      let tab;try{tab=await chrome.tabs.get(tabId);}catch{throw fail('Original target is absent','TARGET_LOST');}
      const url=String(tab.url||''),bootstrap=typeof payload.bootstrapUrl==='string'&&url===payload.bootstrapUrl;
      if(url!==payload.expectedUrl||(!bootstrap&&!originAllowed(url)))throw fail('Original target page changed','TARGET_SCOPE_CHANGED');
      check();
      let current=targets.get(id);
      if(current?.closing)throw fail('Target is closing','TARGET_LOST');
      if(!current){
        current={id,tabId,url,boundUrl:bootstrap?null:urlKey(url),
          bootstrapUrl:payload.bootstrapUrl||null,pendingUrl:bootstrap?payload.pendingUrl||null:null,
          entryUrl:payload.entryUrl||(!bootstrap?url:null),entryPending:bootstrap,
          documentId:null,contentNonce:null};
        targets.set(id,current);await persist();
      }
      return{restored:true,id,url};
    }
    const t=validTarget(payload.targetId);
    if(mutating.has(operation)&&t.effectFence?.deadlineAt>Date.now())throw fail('Prior document mutation is still settling','TARGET_MUTATION_PENDING');
    if(operation==='initialize') {if(payload.clientProfile!=='native'&&!(payload.clientProfile==='android-webview'&&payload.profileApplied===true))throw fail('Verified browser profile bootstrap is required','BROWSER_PROFILE_UNSUPPORTED');return true;}
    if(operation==='navigate') {
      // Navigation is only a named entry action. Transport recovery never calls it.
      if(!originAllowed(payload.url))throw fail('Navigation URL outside approved scope');
      let tab;for(let attempt=0;attempt<40;attempt++){check();tab=await chrome.tabs.get(t.tabId);if(tab.url===t.bootstrapUrl)break;if(tab.url&&tab.url!=='about:blank'&&tab.pendingUrl!==t.bootstrapUrl)break;await new Promise(resolve=>setTimeout(resolve,50));}
      if(!t.bootstrapUrl||tab.url!==t.bootstrapUrl)throw fail('Existing page navigation is forbidden','PAGE_NAVIGATION_FORBIDDEN');
      t.documentId=null;t.contentNonce=null;t.mainDocumentId=null;t.boundUrl=urlKey(payload.url);t.pendingUrl=null;t.entryUrl=payload.url;t.entryPending=true;await persist();check();await chrome.tabs.update(t.tabId,{url:payload.url});return true;
    }
    if(operation==='closeTarget') {
      let tab;try{tab=await chrome.tabs.get(t.tabId);}catch{targets.delete(t.id);await persist();return{absent:true};}
      if(tab.url!==t.bootstrapUrl)await checkUrl(t,String(tab.url||''));
      if(String(tab.url||'')!==String(payload.expectedUrl||''))return{closed:false,reason:'guard_rejected'};
      check();t.closing=true;cancelTargetWork(t.id);await chrome.tabs.remove(t.tabId);let absent=false;try{await chrome.tabs.get(t.tabId);}catch{absent=true;}
      if(absent){targets.delete(t.id);await persist();}return{closed:absent,reason:absent?'closed':'close_unconfirmed'};
    }
    const live=await liveTarget(t.id);if(!originAllowed(live.url))throw fail('Target is not at approved page','PAGE_UNAVAILABLE');
    if(operation==='snapshot'||operation==='progress')return (await content(t,'selfrun.content.snapshot',check)).snapshot;
    if(operation==='recoveryInput')return (await content(t,'selfrun.content.recoveryInput',check)).snapshot;
    const allowed=new Set(['newChat','input','sendReady','submit','installProfile','profileConfigure','installReceipt','receiptArm','receiptSnapshot','receiptRetire','pauseAnimations','quiesce']);
    if(!allowed.has(operation))throw fail('Unsupported named page operation');
    if(operation==='newChat'){const u=new URL(t.boundUrl),prefix=u.pathname.split('/c/')[0];t.entryTransition={allowed:[u.origin+'/',u.origin+prefix,u.origin+prefix+'/project'],deadlineAt:check.deadlineAt};await persist();}
    if(operation==='profileConfigure'){t.entryTransition=null;await persist();}
    if(operation==='receiptArm'){
      const key=payload.key;if(typeof key?.intent_id!=='string'||typeof key?.generation!=='string')throw fail('Invalid intent');
      t.armed={intentId:key.intent_id,generation:key.generation};await persist();
    }
    if(operation==='submit') {
      if(!t.armed)throw fail('Submission intent was not armed');
      if(t.released?.intentId===t.armed.intentId&&t.released?.generation===t.armed.generation)throw fail('Submission may already have been released','SUBMISSION_UNCERTAIN',true);
      t.released={...t.armed};await persist();
      try{const result=await main(t,operation,payload,check);if(result?.status!=='SUBMITTED'){t.released=null;await persist();}return result;}
      catch(error){error.released=true;throw error;}
    }
    return main(t,operation,payload,check);
  }
  const drainCommands=()=>{if(commandRunning||!commandQueue.length)return;const entry=commandQueue.shift();entry.context.queued=null;commandRunning=true;
    Promise.resolve().then(entry.execute).then(entry.resolve,entry.reject).finally(()=>{commandRunning=false;drainCommands();});};
  const enqueueCommand=(execute,context)=>new Promise((resolve,reject)=>{const entry={execute,context,resolve,reject};context.queued=entry;commandQueue.push(entry);drainCommands();});
  async function handle(message) {
    if(message?.kind==='welcome'){connectionId=message.connectionId;await report('hello',{controllerVersion:VERSION,extensionVersion:chrome.runtime.getManifest?.().version??null,extensionId:chrome.runtime.id,browserSessionId:config.browserSessionId});return;}
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
    check.deadlineAt=deadlineAt;check.commandId=id;check.signal=abort.signal;
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
      clearTimeout(deadlineTimer);completed.set(id,response);while(completed.size>512)completed.delete(completed.keys().next().value);
      // HTTP acknowledgement is independent of execution. Correlation remains
      // pinned to this command/connection; an old report cannot own a new turn.
      void post('/result',response).catch(()=>{});
    })();
    inflight.set(id,work);try{await work;}finally{inflight.delete(id);contexts.delete(id);}
  }
  async function start() {
    currentTabId=(await chrome.tabs.getCurrent())?.id;
    const selfUrl=chrome.runtime.getURL?.('controller.html');
    if(selfUrl){
      // tabs.query may omit extension-page URLs without the tabs permission.
      let ids=[];
      if(typeof chrome.runtime.getContexts==='function'){
        try{ids=(await chrome.runtime.getContexts({contextTypes:['TAB'],documentUrls:[selfUrl]}))
          .map(context=>context.tabId).filter(Number.isSafeInteger);}catch{}
      }
      if(!ids.length)ids=(await chrome.tabs.query({})).filter(tab=>String(tab.url||'').split('#')[0]===selfUrl).map(tab=>tab.id);
      if(Number.isSafeInteger(currentTabId)&&ids.some(id=>id!==currentTabId&&id<currentTabId)){
        stopping=true;await chrome.tabs.remove(currentTabId);return;
      }
    }
    const saved=(await chrome.storage.local.get(storageKey))[storageKey]||{};
    for(const [id,t] of Object.entries(saved))if(id===targetId(t.tabId))try{const tab=await chrome.tabs.get(t.tabId);targets.set(id,{...t,url:String(tab.url||'')});}catch{}
    let backoff=250;
    while(!stopping){
      try{
        streamAbort=new AbortController();const r=await fetch(config.bridgeUrl+'/events',{method:'POST',body:'{}',headers,signal:streamAbort.signal,cache:'no-store'});if(!r.ok||!r.body)throw Error('Bridge unavailable');
        backoff=250;const reader=r.body.getReader(),decoder=new TextDecoder();let buffer='';
        while(!stopping){const part=await reader.read();if(part.done)break;buffer+=decoder.decode(part.value,{stream:true});if(buffer.length>2*1024*1024)throw Error('Command frame too large');let i;
          while((i=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,i);buffer=buffer.slice(i+1);if(line.trim())void handle(JSON.parse(line));}}
      }catch{}
      connectionId=null;for(const context of contexts.values())context.cancel();
      if(!stopping){await new Promise(r=>setTimeout(r,backoff));backoff=Math.min(10000,backoff*2);}
    }
  }
  void start();
}
