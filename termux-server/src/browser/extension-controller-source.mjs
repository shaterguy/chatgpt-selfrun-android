// Serialized as a static, CSP-compatible extension page script by the packager.
// No page-supplied JavaScript or MAIN-world postMessage response is accepted.
export function controllerRuntime(config) {
  'use strict';
  const VERSION='selfrun-extension-controller-v1';
  const storageKey=VERSION+':'+config.browserSessionId;
  const headers={'Content-Type':'application/json','Authorization':'Bearer '+config.capability,'X-SelfRun-Owner':config.ownerId};
  const targets=new Map(),readyWaiters=new Map(),inflight=new Map(),completed=new Map(),contexts=new Map(),mutationLanes=new Map();
  let connectionId=null,stopping=false,currentTabId=null,streamAbort=null,saveChain=Promise.resolve(),commandChain=Promise.resolve();
  const originAllowed=url=>{try{return config.targetOrigins.includes(new URL(url).origin);}catch{return false;}};
  const mutating=new Set(['initialize','navigate','newChat','input','submit','installProfile','profileConfigure','installReceipt','receiptArm','receiptRetire','pauseAnimations','quiesce']);
  const targetId=id=>'ext:'+config.browserSessionId+':'+id;
  const urlKey=url=>{try{const u=new URL(url);return u.origin+u.pathname;}catch{return '';}};
  const isConversation=url=>{try{return /\/c\/[A-Za-z0-9_-]+\/?$/.test(new URL(url).pathname);}catch{return false;}};
  const checkUrl=async(t,url)=>{if(!originAllowed(url))throw fail('Owned target left approved site','TARGET_SCOPE_CHANGED');if(!t.boundUrl){t.boundUrl=urlKey(url);await persist();return;}if(urlKey(url)===t.boundUrl)return;if(t.entryTransition?.deadlineAt>Date.now()&&t.entryTransition.allowed.includes(urlKey(url))){t.boundUrl=urlKey(url);t.url=url;await persist();return;}if(t.released&&!isConversation(t.boundUrl)&&isConversation(url)){t.boundUrl=urlKey(url);t.url=url;await persist();return;}throw fail('Owned target was repurposed','TARGET_SCOPE_CHANGED');};
  const persist=()=>{const value=Object.fromEntries([...targets].map(([id,t])=>[id,{id,tabId:t.tabId,url:t.url,boundUrl:t.boundUrl||null,documentId:t.documentId||null,contentNonce:t.contentNonce||null,armed:t.armed||null,released:t.released||null,entryTransition:t.entryTransition||null,effectFence:t.effectFence||null,bootstrapUrl:t.bootstrapUrl||null,pendingUrl:t.pendingUrl||null,entryUrl:t.entryUrl||null,entryPending:t.entryPending===true}]));
    saveChain=saveChain.catch(()=>{}).then(()=>chrome.storage.local.set({[storageKey]:value}));return saveChain;};
  const post=async(path,data)=>{const r=await fetch(config.bridgeUrl+path,{method:'POST',headers,body:JSON.stringify({...data,browserSessionId:config.browserSessionId})});if(!r.ok)throw Error('Local bridge HTTP '+r.status);return r.json();};
  const report=(event,data={})=>post('/event',{connectionId,event,...data}).catch(()=>{});
  const fail=(message,code='EXTENSION_COMMAND_FAILED',released=false)=>Object.assign(new Error(message),{code,released});
  const validTarget=id=>{const t=targets.get(id);if(!t||t.closing)throw fail('Target is not owned','TARGET_NOT_OWNED');return t;};
  const liveTarget=async id=>{const t=validTarget(id);let tab;try{tab=await chrome.tabs.get(t.tabId);}catch{throw fail('Owned target is absent','TARGET_LOST');}
    const url=String(tab.url||'');if(url===t.bootstrapUrl)throw fail('Approved entry navigation is pending','PAGE_UNAVAILABLE');if(url&&url!=='about:blank'&&!originAllowed(url))throw fail('Owned target left allowed site','TARGET_SCOPE_CHANGED');
    if(url!=='about:blank')await checkUrl(t,url);return{...t,url,tab};};
  const ready=async t=>{
    if(t.documentId&&t.contentNonce)return t;
    await chrome.tabs.sendMessage(t.tabId,{kind:'selfrun.content.helloRequest'}).catch(()=>{});
    if(t.documentId&&t.contentNonce)return t;
    return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{readyWaiters.delete(t.id);reject(fail('Page content bridge unavailable','PAGE_UNAVAILABLE'));},15000);
      readyWaiters.set(t.id,{resolve:value=>{clearTimeout(timer);resolve(value);},reject:error=>{clearTimeout(timer);reject(error);}});});
  };
  const content=async(t,kind)=>{
    await ready(t);const documentId=t.documentId,nonce=t.contentNonce;
    const result=await chrome.tabs.sendMessage(t.tabId,{kind,nonce},{documentId});
    if(t.documentId!==documentId||result?.nonce&&result.nonce!==nonce)throw fail('Page document changed','DOCUMENT_CHANGED');
    if(result?.ok!==true)throw fail(result?.error||'Page content unavailable','PAGE_UNAVAILABLE');if(result.snapshot?.url)await checkUrl(t,result.snapshot.url);return result;
  };
  const main=async(t,operation,payload,check)=>{
    check();await ready(t);check();const documentId=t.documentId;
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
      readyWaiters.get(t.id)?.resolve(t);readyWaiters.delete(t.id);void report('targetReady',{target:{id:t.id,url:t.url,type:'page'}});
    } else if(message?.kind==='selfrun.content.dirty'&&message.nonce===t.contentNonce&&sender.documentId===t.documentId)void report('targetDirty',{targetId:t.id});
  });
  chrome.tabs.onRemoved.addListener(id=>{const key=targetId(id),t=targets.get(key);if(!t)return;
    targets.delete(key);readyWaiters.get(key)?.reject(fail('Owned target closed','TARGET_CLOSED'));readyWaiters.delete(key);void persist();void report('targetRemoved',{targetId:key});});
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
    if(operation==='idleStatus') {const tabs=await chrome.tabs.query({});return{ownedCount:targets.size,unrelatedCount:tabs.filter(t=>t.id!==currentTabId&&!targets.has(targetId(t.id))).length};}
    if(operation==='controllerStop') {const tabs=await chrome.tabs.query({});check();if(targets.size||tabs.some(tab=>tab.id!==currentTabId))return{stopped:false,reason:'browser_in_use'};stopping=true;setTimeout(()=>{streamAbort?.abort();void chrome.tabs.remove(currentTabId);},100);return{stopped:true};}
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
      check();t.closing=true;await chrome.tabs.remove(t.tabId);let absent=false;try{await chrome.tabs.get(t.tabId);}catch{absent=true;}
      if(absent){targets.delete(t.id);await persist();}return{closed:absent,reason:absent?'closed':'close_unconfirmed'};
    }
    const live=await liveTarget(t.id);if(!originAllowed(live.url))throw fail('Target is not at approved page','PAGE_UNAVAILABLE');
    if(operation==='snapshot'||operation==='progress')return (await content(t,'selfrun.content.snapshot')).snapshot;
    if(operation==='recoveryInput')return (await content(t,'selfrun.content.recoveryInput')).snapshot;
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
  async function handle(message) {
    if(message?.kind==='welcome'){connectionId=message.connectionId;await report('hello',{controllerVersion:VERSION,extensionId:chrome.runtime.id,browserSessionId:config.browserSessionId});return;}
    if(message?.kind==='cancel'&&message.connectionId===connectionId){const context=contexts.get(message.id);if(context)context.cancel();return;}
    if(message?.kind!=='command'||message.connectionId!==connectionId||typeof message.id!=='string')return;
    const id=message.id;
    if(completed.has(id)){await post('/result',completed.get(id)).catch(()=>{});return;}
    if(inflight.has(id))return;
    let rejectCancelled;const cancelled=new Promise((_,reject)=>{rejectCancelled=reject;});cancelled.catch(()=>{});
    const context={cancelled:false,connectionId:message.connectionId,cancel(){this.cancelled=true;rejectCancelled(fail('Command cancelled or expired','COMMAND_CANCELLED',message.operation==='submit'));}};contexts.set(id,context);
    const deadlineTimer=setTimeout(()=>context.cancel(),Math.max(0,Math.min(60000,Number(message.deadlineAt)-Date.now()||0)));
    const check=()=>{if(context.cancelled||(stopping&&message.operation!=='controllerStop')||connectionId!==context.connectionId||!Number.isFinite(message.deadlineAt)||Date.now()>=message.deadlineAt)throw fail('Command cancelled or expired','COMMAND_CANCELLED');};
    check.deadlineAt=message.deadlineAt;check.commandId=id;
    const work=commandChain.then(async()=>{let response;try{check();const target=message.payload?.targetId,isMutation=mutating.has(message.operation);
        if(target&&isMutation&&mutationLanes.has(target))throw fail('Prior mutation is still settling','TARGET_MUTATION_PENDING');
        const execution=execute(message.operation,message.payload||{},check);
        if(target&&isMutation){mutationLanes.set(target,id);execution.finally(()=>{if(mutationLanes.get(target)===id)mutationLanes.delete(target);}).catch(()=>{});}
        const result=await Promise.race([execution,cancelled]);check();response={id,connectionId:message.connectionId,ok:true,result};}
      catch(error){response={id,connectionId:message.connectionId,ok:false,error:{message:String(error?.message||error).slice(0,250),code:String(error?.code||'EXTENSION_COMMAND_FAILED'),released:error?.released===true}};}
      clearTimeout(deadlineTimer);completed.set(id,response);while(completed.size>512)completed.delete(completed.keys().next().value);await post('/result',response).catch(()=>{});
    });commandChain=work.catch(()=>{});inflight.set(id,work);try{await work;}finally{inflight.delete(id);contexts.delete(id);}
  }
  async function start() {
    currentTabId=(await chrome.tabs.getCurrent())?.id;
    const selfUrl=chrome.runtime.getURL?.('controller.html');
    if(selfUrl){const others=(await chrome.tabs.query({})).filter(tab=>tab.id!==currentTabId&&String(tab.url||'').split('#')[0]===selfUrl);if(others.some(tab=>tab.id<currentTabId)){stopping=true;await chrome.tabs.remove(currentTabId);return;}}
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
