import {probeExpression,recoveryInputExpression,newChatExpression,inputExpression,sendReadyExpression,
  submitExpression,requestProfileDocumentStartSource,receiptObserverDocumentStartSource,
  configureRequestProfileExpression,normalizeProfileOperations,DECORATIVE_ANIMATION_PAUSE_SOURCE,
  QUIESCE_EXPRESSION} from './chatgpt.mjs';

export function buildPageRuntimeSource({installationKey='selfrun-static-test-key'}={}) {
  const placeholder='__SELFRUN_STATIC_PROMPT_PLACEHOLDER_8b1c__';
  const input=inputExpression(placeholder).replace(JSON.stringify(placeholder),'String(payload.prompt??\'\')');
  const operations=['model','thinking_effort','conversation_origin','service_tier'].map(path=>({op:'REMOVE',path}));
  const privateRefs=source=>source.replaceAll('window.__selfrunRequestProfileEngine','profileEngine').replaceAll('window.__selfrunReceiptObserver','receiptObserver');
  const configure=privateRefs(configureRequestProfileExpression(operations))
    .replace(JSON.stringify(normalizeProfileOperations(operations)),'normalizeProfileOperations(payload.operations)');
  const profile=privateRefs(requestProfileDocumentStartSource())
    .replace('const nativeFetch=window.fetch.bind(window);','const nativeFetch=(...args)=>Reflect.apply(originalFetch,window,args);')
    .replace('window.fetch=async function','profileFetch=async function')
    .replace('const nativeOpen=XMLHttpRequest.prototype.open,nativeSend=XMLHttpRequest.prototype.send,metadata=new WeakMap();','const nativeOpen=originalOpen,nativeSend=originalSend,metadata=new WeakMap();')
    .replace('XMLHttpRequest.prototype.open=function','profileOpen=function')
    .replace('XMLHttpRequest.prototype.send=function','profileSend=function');
  const receipt=privateRefs(receiptObserverDocumentStartSource())
    .replace('const downstreamFetch=window.fetch;','const downstreamFetch=function(...args){return Reflect.apply(profileFetch||originalFetch,this,args);};')
    .replace('window.fetch=function','receiptFetch=function')
    .replace('current===entry&&!entry.retired&&!entry.invalid','current===entry&&!entry.retired&&!entry.invalid&&ownerActive()');
  const actions={snapshot:probeExpression(),progress:probeExpression(),recoveryInput:recoveryInputExpression(),
    newChat:newChatExpression(),input,sendReady:sendReadyExpression(),submit:submitExpression(),
    installProfile:profile,profileConfigure:configure,installReceipt:receipt,
    receiptArm:'receiptObserver.arm(payload.key)',receiptSnapshot:'receiptObserver?.snapshot(payload.key)||null',
    receiptRetire:'receiptObserver?.retire(payload.key)',pauseAnimations:DECORATIVE_ANIMATION_PAUSE_SOURCE,quiesce:QUIESCE_EXPRESSION};
  return `(()=>{'use strict';const version='selfrun-extension-page-v1';
    if(window.__selfrunExtensionPage)throw new Error('Unexpected page helper');
    const nativeNow=Date.now.bind(Date);
    const installationKey=${JSON.stringify(installationKey)};
    const encoder=new TextEncoder(),encode=encoder.encode.bind(encoder),subtle=crypto.subtle,importKey=subtle.importKey.bind(subtle),sign=subtle.sign.bind(subtle);let attestKey=null;
    const attest=async challenge=>{if(typeof challenge!=='string'||challenge.length>200)throw new Error('Invalid attestation challenge');attestKey??=importKey('raw',encode(installationKey),{name:'HMAC',hash:'SHA-256'},false,['sign']);const bytes=new Uint8Array(await sign('HMAC',await attestKey,encode(challenge)));return Array.from(bytes,x=>x.toString(16).padStart(2,'0')).join('');};
    const originalFetch=window.fetch,xhr=typeof XMLHttpRequest==='function'?XMLHttpRequest.prototype:null;
    const originalOpen=xhr?.open,originalSend=xhr?.send,activeXhr=new WeakSet();
    let profileEngine=null,receiptObserver=null,profileFetch=null,receiptFetch=null,profileOpen=null,profileSend=null;
    let ownership=null,active=false,revoked=false,allowInitialConversation=false,armedKey=null,entryTransition=null;
    const urlKey=url=>{try{const u=new URL(url);return u.origin+u.pathname;}catch{return '';}};
    const conversation=url=>{try{return /\\/c\\/[A-Za-z0-9_-]+\\/?$/.test(new URL(url).pathname);}catch{return false;}};
    const revoke=()=>{active=false;revoked=true;try{if(armedKey)receiptObserver?.retire(armedKey);}catch{}};
    const ownerMatches=()=>{if(revoked||!ownership)return false;const now=urlKey(location.href);if(now===ownership.url)return true;if(entryTransition?.deadline>nativeNow()&&entryTransition.allowed.includes(now)){ownership.url=now;return true;}
      if(allowInitialConversation&&!conversation(ownership.url)&&conversation(now)){ownership.url=now;allowInitialConversation=false;return true;}revoke();return false;};
    const ownerActive=()=>active&&ownerMatches();
    // Inactive paths do not inspect URL, arguments, body, response, or Promise.
    // These non-async wrappers preserve the original receiver, return and throw.
    window.fetch=function(){if(!active)return Reflect.apply(originalFetch,this,arguments);if(!ownerActive())return Reflect.apply(originalFetch,this,arguments);return Reflect.apply(receiptFetch||profileFetch||originalFetch,this,arguments);};
    if(xhr){xhr.open=function(){if(!active||!ownerActive())return Reflect.apply(originalOpen,this,arguments);const result=Reflect.apply(profileOpen||originalOpen,this,arguments);activeXhr.add(this);return result;};
      xhr.send=function(){if(!active||!ownerActive())return Reflect.apply(originalSend,this,arguments);if(profileSend&&!activeXhr.has(this))throw new Error('REQUEST_PROFILE:untracked_active_xhr');return Reflect.apply(profileSend||originalSend,this,arguments);};}
    const history=window.history;
    if(history)for(const name of ['pushState','replaceState']){const original=history[name];if(typeof original==='function')history[name]=function(){const result=Reflect.apply(original,this,arguments);if(ownership)ownerMatches();return result;};}
    window.addEventListener?.('pagehide',revoke,{once:true});window.addEventListener?.('popstate',()=>{if(ownership)ownerMatches();});window.addEventListener?.('hashchange',()=>{if(ownership)ownerMatches();});
    const REQUEST_PROFILE_CONTROL=Object.freeze(['model','thinking_effort','conversation_origin','service_tier']);
    const normalizeProfileOperations=${normalizeProfileOperations.toString()};
    const actions={${Object.entries(actions).map(([name,expression])=>JSON.stringify(name)+':(payload)=>('+expression+')').join(',\n')}};
    const run=(operation,payload={},nonce,expectedUrl,deadline,activationKey)=>{
      if(!Object.hasOwn(actions,operation))throw new Error('Unsupported page operation');
      if(activationKey!==installationKey)throw new Error('Page activation unauthorized');
      if(!Number.isFinite(deadline)||nativeNow()>=deadline)throw new Error('Page command expired');
      if(revoked||typeof nonce!=='string'||nonce.length<16||urlKey(location.href)!==urlKey(expectedUrl))throw new Error('Page ownership unavailable');
      if(!ownership)ownership={nonce,url:urlKey(expectedUrl)};
      if(ownership.nonce!==nonce)throw new Error('Page owner mismatch');
      if(active&&!ownerActive())throw new Error('Page ownership revoked');
      if(operation==='newChat'){const u=new URL(ownership.url),prefix=u.pathname.split('/c/')[0];entryTransition={allowed:[u.origin+'/',u.origin+prefix,u.origin+prefix+'/project'],deadline};}
      if(operation==='submit')allowInitialConversation=!conversation(ownership.url);
      const result=actions[operation](payload);
      if(operation==='profileConfigure'){ownership.url=urlKey(expectedUrl);entryTransition=null;active=true;}
      if(operation==='receiptArm')armedKey=payload.key;
      if(operation==='receiptRetire')armedKey=null;
      return result;
    };
    Object.defineProperty(window,'__selfrunExtensionPage',{value:Object.freeze({version,attest,run}),writable:false,configurable:false});
  })();\n`;
}

export function contentRuntime(snapshot,recoveryInput) {
  'use strict';
  const nonce=crypto.randomUUID();let active=false,dirty=true,cache=null,observer=null;
  const ownSender=sender=>sender.id===chrome.runtime.id&&String(sender.url||'').startsWith(chrome.runtime.getURL('controller.html'));
  const hello=()=>chrome.runtime.sendMessage({kind:'selfrun.content.hello',nonce}).catch(()=>{});
  const changed=()=>{if(!active||dirty)return;dirty=true;chrome.runtime.sendMessage({kind:'selfrun.content.dirty',nonce}).catch(()=>{});};
  chrome.runtime.onMessage.addListener((message,sender,reply)=>{
    if(!ownSender(sender))return;
    if(message?.kind==='selfrun.content.helloRequest'){void hello();reply({ok:true});return;}
    if(message?.nonce!==nonce)return;
    if(message.kind==='selfrun.content.activate') {
      active=true;dirty=true;
      if(!observer){observer=new MutationObserver(changed);observer.observe(document,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['class','style','hidden','aria-hidden','aria-disabled','disabled']});}
      reply({ok:true});return;
    }
    if(message.kind==='selfrun.content.deactivate') {active=false;observer?.disconnect();observer=null;cache=null;dirty=true;reply({ok:true});return;}
    if(!active)return;
    try {
      if(message.kind==='selfrun.content.snapshot') {
        // The content script is isolated from the page. Dirty notices are hints,
        // not ownership or receipt evidence. Every server observation takes a fresh sample.
        cache=snapshot();
        dirty=false;reply({ok:true,nonce,snapshot:{...cache,url:location.href,readyState:document.readyState},sampledAt:Date.now()});
      } else if(message.kind==='selfrun.content.recoveryInput')reply({ok:true,nonce,snapshot:recoveryInput(),sampledAt:Date.now()});
    }catch(error){reply({ok:false,error:String(error?.message||error).slice(0,200)});}
  });
  addEventListener('pagehide',()=>{active=false;observer?.disconnect();observer=null;},{once:true});
  void hello();
}

export function buildContentSource() {
  return `(${contentRuntime.toString()})(()=>(${probeExpression()}),()=>(${recoveryInputExpression()}));\n`;
}
