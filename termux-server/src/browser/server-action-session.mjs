import {nativePreferenceExpression} from './native-profile-preparation.mjs';
import {createHmac,randomUUID} from 'node:crypto';
import {CdpSession} from './cdp.mjs';
import {actionConversationUrl,pendingConversationTransition} from './chatgpt.mjs';
import {buildPageRuntimeSource} from './extension-page-source.mjs';
import {LegacyInputTransport} from './legacy-input-transport.mjs';
import {actionNotSubmittedError,isActionNotSubmitted} from './server-action-errors.mjs';

const commands=new Set(['prepareInput','prepareProfile','newChat','input','sendReady','submit','installProfile','profileConfigure','installReceipt','receiptArm','receiptSnapshot','receiptRetire','pauseAnimations','quiesce','terminalDispose','recoveryInput']);
const allowedEndpoint=(value,port,path)=>{try{const u=new URL(value);return u.protocol==='ws:'&&u.hostname==='127.0.0.1'&&Number(u.port)===port&&u.pathname===path&&!u.search&&!u.hash;}catch{return false;}};
const connectDefault=async(url,{signal}={})=>{const session=new CdpSession(url);const abort=()=>session.close();signal?.addEventListener('abort',abort,{once:true});try{if(signal?.aborted)throw signal.reason;await session.connect();if(signal?.aborted)throw signal.reason;return session;}catch(error){session.close();throw error;}finally{signal?.removeEventListener('abort',abort);}};

// Server-owned action connection. Page initialization delivers document-start
// scripts; Runtime/Network enable and progress polling remain absent.
// The extension owns only DOM observation.
export class ServerActionSession {
  constructor({session,reconnect=null,onReconnect=null,installationKey,guard=()=>true,allowedOrigins=[],initialUrl=null}){this.boundUrl=initialUrl;this.entryTransition=null;this.pendingNavigation=null;this.reconnect=reconnect;this.onReconnect=onReconnect;this.reconnecting=null;this.transportLost=false;this.session=session;this.installationKey=installationKey;this.guard=guard;this.allowedOrigins=allowedOrigins;this.documentScriptRegistered=false;this.closed=false;this.armed=null;this.released=null;}
  isAlive(){return !this.closed&&!this.transportLost&&(!this.session.socket||this.session.socket.readyState===1);}
  check(signal){if(this.closed||signal?.aborted||!this.guard())throw Object.assign(signal?.reason||Error('Server action owner unavailable'),{code:'ACTION_CANCELLED'});}
  async connection(options={}){
    this.check(options.signal);
    if(this.transportLost||(this.session.socket&&this.session.socket.readyState!==1)){
      if(!this.reconnect)throw Error('Server action connection unavailable');
      if(!this.reconnecting)this.reconnecting=(async()=>{const prior=this.session;if(this.legacyInput&&!await this.legacyInput.dispose(true))throw Error('Owned input transport reconnect pending');const next=await this.reconnect(options);try{this.check(options.signal);}catch(error){next.close();throw error;}prior.close();this.session=next;this.transportLost=false;this.documentScriptRegistered=false;if(this.scriptIdentifier){try{await next.call('Page.removeScriptToEvaluateOnNewDocument',{identifier:this.scriptIdentifier},options);}catch{}this.scriptIdentifier=null;}this.onReconnect?.();return next;})().finally(()=>{this.reconnecting=null;});
      await this.reconnecting;this.check(options.signal);
    }
    return this.session;
  }
  async call(method,params={},options={}){const session=await this.connection(options);try{const result=await session.call(method,params,options);this.check(options.signal);return result;}catch(error){if(session.socket?.readyState!==undefined&&session.socket.readyState!==1||/websocket|command timeout/i.test(error.message||''))this.transportLost=true;throw error;}}
  async evaluate(expression,options={}){const value=await this.call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true},options);if(value.exceptionDetails)throw Error(value.exceptionDetails.exception?.description||value.exceptionDetails.text||'Server action failed');return value.result?.value;}
  async install(options={},repairInput=false){
    await this.connection(options);
    const source=buildPageRuntimeSource({installationKey:this.installationKey});
    const upgradeSource=repairInput?buildPageRuntimeSource({installationKey:this.installationKey,allowLegacy:true}):'';
    // Registration belongs to this connection, not to any current document.
    // A navigation (including an unchanged URL) replaces the document globals;
    // never treat an earlier document's proof as evidence for the next one.
    if(!this.documentScriptRegistered){
      // Chromium's Page agent must receive new-document instrumentation before
      // navigation. Registration alone may succeed without delivering scripts.
      await this.call('Page.enable',{},options);
      const installed=await this.call('Page.addScriptToEvaluateOnNewDocument',{source},options);this.scriptIdentifier=installed.identifier;
      this.documentScriptRegistered=true;
    }
    const challenge=randomUUID(),expected=createHmac('sha256',this.installationKey).update(challenge).digest('hex');
    const proof=await this.evaluate(`(async()=>{const h=window.__selfrunExtensionPageV2||window.__selfrunExtensionPage;if(!h){${source}}else if(${repairInput}&&!window.__selfrunExtensionPageV2){if(await h.attest(${JSON.stringify(challenge)})!==${JSON.stringify(expected)})throw Error('Page activation unauthorized');${upgradeSource}}return (window.__selfrunExtensionPageV2||window.__selfrunExtensionPage).attest(${JSON.stringify(challenge)});})()`,options);
    if(proof!==expected)throw Error('Server page runtime ownership unverified');
    return true;
  }
  verifyUrl(value){
    const key=v=>{const u=new URL(v);return u.origin+u.pathname;};
    if(typeof value!=='string')throw Error('Owned action URL unavailable');
    if(!this.boundUrl){this.boundUrl=value;return value;}
    if(key(value)===key(this.boundUrl))return value;
    if(this.pendingNavigation&&key(value)===key(this.pendingNavigation.to)){this.boundUrl=value;this.pendingNavigation=null;return value;}
    if(this.entryTransition?.deadline>Date.now()&&this.entryTransition.allowed.includes(key(value))||actionConversationUrl(this.boundUrl,value)||pendingConversationTransition(this.boundUrl,value)){this.boundUrl=value;return value;}
    throw Error('Owned action page was repurposed');
  }
  async run(operation,payload={},options={}){
    this.check(options.signal);
    const pendingNavigation=this.pendingNavigation;
    if(this.boundUrl)this.verifyUrl(await this.evaluate('location.href',options));
    if(operation==='navigate'){
      const u=new URL(payload.url);if(!this.allowedOrigins.includes(u.origin))throw Error('Action navigation outside approved scope');
      if(pendingNavigation?.to===payload.url&&!this.pendingNavigation)return{alreadyNavigated:true};
      await this.install(options);this.pendingNavigation={to:payload.url};
      const result=await this.call('Page.navigate',{url:payload.url},options);if(result.errorText)throw Error('Navigation failed: '+result.errorText);this.boundUrl=payload.url;this.pendingNavigation=null;return result;
    }
    if(!commands.has(operation))throw Error('Unsupported server action');
    await this.install(options,operation==='prepareInput');this.check(options.signal);
    if(operation==='prepareProfile')return this.evaluate(nativePreferenceExpression(payload.operations),options);
    if(operation==='prepareInput'){
      const previous=this.legacyInput;
      while(previous&&!await previous.dispose()){this.check(options.signal);await new Promise(resolve=>setTimeout(resolve,100));}
      this.legacyInput=null;
      if(await this.evaluate('window.__selfrunExtensionPageV2?.legacyTransport===true',options)===true){
        const physicalSession=this.session,ownedUrl=this.boundUrl;
        this.legacyInput=new LegacyInputTransport({session:physicalSession,evaluate:async(expression,opts)=>{const value=await physicalSession.call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true},opts);if(value.exceptionDetails)throw Error('Parent lookup pending');return value.result?.value;},prompt:payload.prompt,
          conversationId:new URL(this.boundUrl).pathname.match(/\/c\/([^/]+)\/?$/)?.[1]||'',
          signal:options.lifetimeSignal||options.signal,guard:()=>!this.closed&&this.guard(),closeTarget:()=>this.closeInputTarget(physicalSession,ownedUrl)});
        await this.legacyInput.install();
      }
    }
    if(operation==='receiptArm')this.armed={intentId:payload.key?.intent_id,generation:payload.key?.generation};
    const priorReleased=this.released;let releaseAttempt=null;
    if(operation==='submit'){
      if(!this.armed)throw Error('Submission intent unavailable');
      if(this.released?.intentId===this.armed.intentId&&this.released?.generation===this.armed.generation)throw Object.assign(Error('Submission may already have been released'),{code:'SUBMISSION_UNCERTAIN',released:true});
      // Set before the effectful call; a lost response never authorizes replay.
      releaseAttempt={...this.armed};this.released=releaseAttempt;
      payload={...payload,expectedArm:{intent_id:releaseAttempt.intentId,generation:releaseAttempt.generation}};
    }
    const deadline=Date.now()+Math.max(1,Number(options.timeoutMs)||5000);
    const expression=`(()=>{const helper=window.__selfrunExtensionPageV2||window.__selfrunExtensionPage;if(!helper)throw Error('Page action runtime unavailable');const u=new URL(location.href);if(!${JSON.stringify(this.allowedOrigins)}.includes(u.origin))throw Error('Action target left approved scope');return helper.run(${JSON.stringify(operation)},${JSON.stringify(payload)},${JSON.stringify('server-action-owner')},${JSON.stringify(this.boundUrl)}||u.origin+u.pathname,${deadline},${JSON.stringify(this.installationKey)});})()`;
    try{const result=await this.evaluate(expression,options);
      if(operation==='submit'&&result?.status==='CONVERSATION_OBSERVED'&&actionConversationUrl(payload.initialProjectUrl,result.url)){
        if(this.released===releaseAttempt)this.released=priorReleased;return result;
      }
      if(operation==='submit'&&result?.status==='NOT_SUBMITTED'&&['PREPARATION_LOST','NO_SEND','NO_COMPOSER'].includes(result.reason)
        &&result.intent_id===releaseAttempt.intentId&&result.generation===releaseAttempt.generation){
        if(this.released===releaseAttempt)this.released=priorReleased;
        throw actionNotSubmittedError(result.reason==='NO_SEND'?'submit':'input');
      }
      if(operation==='submit'&&result?.status!=='SUBMITTED')throw Error('Server submission outcome unavailable');
      if(operation==='newChat'&&this.boundUrl){const u=new URL(this.boundUrl),prefix=u.pathname.split('/c/')[0];this.entryTransition={deadline,allowed:[u.origin+'/',u.origin+prefix,u.origin+prefix+'/project']};}return result;}
    catch(error){if(operation==='submit'&&!isActionNotSubmitted(error))error.released=true;throw error;}
  }
  async closeInputTarget(physicalSession,expectedUrl){
    const endpoint=new URL(physicalSession.wsUrl);const targetId=endpoint.pathname.split('/').at(-1),port=Number(endpoint.port);
    const response=await fetch('http://127.0.0.1:'+port+'/json/version',{signal:AbortSignal.timeout(2000)});
    const metadata=await response.json();
    const result=await browserAction({browserKey:metadata.webSocketDebuggerUrl,port,method:'Target.closeTarget',params:{targetId},expectedUrl,guard:()=>true});
    return result.success===true;
  }
  async dispose(options={}){
    // Captured connection can only erase its page-local runtime after ownership
    // revocation. It cannot issue another input or register a lifecycle record.
    let purged=false;try{await this.legacyInput?.dispose(true);if(!this.closed){
      const expression=`(()=>{const h=window.__selfrunExtensionPageV2||window.__selfrunExtensionPage;if(!h)return true;const u=new URL(location.href);return h.run('terminalDispose',{},'server-action-owner',u.origin+u.pathname,${Date.now()+1500},${JSON.stringify(this.installationKey)});})()`;
      const result=await this.session.call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true},options);purged=!result.exceptionDetails&&result.result?.value===true;
    }}finally{try{if(!this.closed&&this.scriptIdentifier)await this.session.call('Page.removeScriptToEvaluateOnNewDocument',{identifier:this.scriptIdentifier},options);}finally{this.scriptIdentifier=null;this.armed=null;this.released=null;this.close();}}
    return purged;
  }
  close(){if(this.closed)return;this.closed=true;this.armed=null;this.released=null;const transport=this.session;if(this.legacyInput)void this.legacyInput.dispose(true).then(safe=>{if(safe)transport.close();});else transport.close();this.guard=()=>false;this.reconnect=null;this.onReconnect=null;this.installationKey=null;this.allowedOrigins=[];this.boundUrl=null;this.entryTransition=null;this.pendingNavigation=null;}
}

export async function connectServerAction({row,port,installationKey,guard,allowedOrigins,onReconnect,signal,connect=connectDefault}){
  if(!row?.id||!allowedEndpoint(row.webSocketDebuggerUrl,port,'/devtools/page/'+row.id))throw Error('Owned action endpoint invalid');
  if(signal?.aborted||!guard())throw signal?.reason||Error('Action owner unavailable');
  const session=await connect(row.webSocketDebuggerUrl,{signal});
  if(signal?.aborted||!guard()){session.close();throw signal?.reason||Error('Action owner changed');}
  return new ServerActionSession({session,installationKey,guard,allowedOrigins,onReconnect,initialUrl:row.url,reconnect:async options=>{if(!guard())throw Error('Action owner unavailable');return connect(row.webSocketDebuggerUrl,options);}});
}
export async function browserAction({browserKey,port,method,params,signal,guard=()=>true,expectedUrl=null,connect=connectDefault}){
  if(!['Target.createTarget','Target.closeTarget'].includes(method)||!allowedEndpoint(browserKey,port,new URL(browserKey).pathname)||!/^\/devtools\/browser\/[A-Za-z0-9-]+$/.test(new URL(browserKey).pathname))throw Error('Owned browser action invalid');
  if(signal?.aborted||!guard())throw signal?.reason||Error('Browser action owner unavailable');
  const session=await connect(browserKey,{signal});
  try{
    if(signal?.aborted||!guard())throw signal?.reason||Error('Browser action owner changed');
    if(method==='Target.closeTarget'){
      const {targetInfo}=await session.call('Target.getTargetInfo',{targetId:params.targetId},{signal,timeoutMs:2000});
      if(targetInfo?.targetId!==params.targetId||targetInfo.type!=='page'||expectedUrl!==targetInfo.url||!guard(targetInfo))throw Error('Owned close target changed');
    }
    if(signal?.aborted||!guard())throw signal?.reason||Error('Browser action owner changed');
    // Once create is on the wire, finish this bounded call even if STOP arrives.
    // Its exact returned tab can then be erased without a retained cleanup job.
    return await session.call(method,params,{...(method==='Target.createTarget'?{}:{signal}),timeoutMs:5000});
  }
  finally{session.close();}
}

// Read-only proof of the already paired monitor page. The challenge never
// carries credentials and the page returns only a keyed proof, not its key.
export async function attestController({row,port,challenge,signal,connect=connectDefault}){
  if(!row?.id||row.type!=='page'||!allowedEndpoint(row.webSocketDebuggerUrl,port,'/devtools/page/'+row.id))throw Error('Controller proof endpoint invalid');
  const session=await connect(row.webSocketDebuggerUrl,{signal});
  try{const result=await session.call('Runtime.evaluate',{expression:`globalThis.__selfrunMonitorAttest?.(${JSON.stringify(challenge)})`,returnByValue:true,awaitPromise:true},{signal,timeoutMs:2000});return result.exceptionDetails?null:result.result?.value;}finally{session.close();}
}

// Generic reconciliation uses only today's active set. It holds no ended-task
// identity and never creates/closes/navigates a tab or installs a page helper.
export async function purgeInactiveAction({row,port,installationKey,guard,connect=connectDefault}){
  if(!row?.id||row.type!=='page'||!allowedEndpoint(row.webSocketDebuggerUrl,port,'/devtools/page/'+row.id)||!guard())return false;
  const signal=AbortSignal.timeout(2000),session=await connect(row.webSocketDebuggerUrl,{signal});
  try{
    if(!guard())return false;
    const challenge=randomUUID(),expected=createHmac('sha256',installationKey).update(challenge).digest('hex');
    const proof=await session.call('Runtime.evaluate',{expression:`(window.__selfrunExtensionPageV2||window.__selfrunExtensionPage)?.attest(${JSON.stringify(challenge)})`,returnByValue:true,awaitPromise:true},{signal,timeoutMs:1000});
    if(proof.exceptionDetails||proof.result?.value!==expected||!guard())return false;
    const result=await session.call('Runtime.evaluate',{expression:`(()=>{const h=window.__selfrunExtensionPageV2||window.__selfrunExtensionPage;return h?.run('terminalDispose',{},'server-action-owner',location.href,${Date.now()+1000},${JSON.stringify(installationKey)});})()`,returnByValue:true,awaitPromise:true},{signal,timeoutMs:1000});
    return !result.exceptionDetails&&result.result?.value===true;
  }finally{session.close();}
}
