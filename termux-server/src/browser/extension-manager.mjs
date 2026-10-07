import fs from 'node:fs';import fsp from 'node:fs/promises';import path from 'node:path';import {spawn} from 'node:child_process';import {randomUUID} from 'node:crypto';
import {canonicalUrl,digest} from '../lifecycle.mjs';
import {applyExtensionClientProfile,extensionProfileFingerprint} from './extension-profile-bootstrap.mjs';
import {ExtensionBridge} from './extension-bridge.mjs';import {buildExtensionFiles,EXTENSION_ID} from './extension-package.mjs';
const fail=(message,code)=>Object.assign(new Error(message),{code});
function processIdentity(pid){try{const stat=fs.readFileSync(`/proc/${pid}/stat`,'utf8'),fields=stat.slice(stat.lastIndexOf(')')+2).split(' ');return{pid:Number(pid),state:fields[0],startTime:fields[19],args:fs.readFileSync(`/proc/${pid}/cmdline`,'utf8').split('\0').filter(Boolean)};}catch{return null;}}
function processExists(pid){try{fs.statSync(`/proc/${pid}`);return true;}catch(e){return e.code!=='ENOENT';}}
function bootId(){try{return fs.readFileSync('/proc/sys/kernel/random/boot_id','utf8').trim();}catch{return null;}}
export function extensionProcessMatches(proof,row,boot){return !!proof&&!!row&&row.state!=='Z'&&!!boot&&proof.bootId===boot&&proof.pid===row.pid&&proof.startTime===row.startTime&&row.args.includes(`--user-data-dir=${proof.profile}`)&&row.args.includes(`--load-extension=${proof.extensionPath}`)&&row.args.includes(`--selfrun-extension-owner=${proof.ownerId}`)&&(!proof.cdpPort||(row.args.includes(`--remote-debugging-port=${proof.cdpPort}`)&&row.args.includes('--remote-debugging-address=127.0.0.1')))&&!row.args.some(x=>x.startsWith('--type='));}
function profileBusy(profile){try{fs.lstatSync(path.join(profile,'SingletonLock'));return true;}catch(e){if(e.code!=='ENOENT')return true;}try{for(const pid of fs.readdirSync('/proc').filter(x=>/^\d+$/.test(x))){const p=processIdentity(pid);if(p?.args.includes(`--user-data-dir=${profile}`)&&!p.args.some(x=>x.startsWith('--type=')))return true;}}catch{return true;}return false;}
async function launch(binary,args,{logFile,env}){const out=fs.openSync(logFile,'a',0o600);let child;try{child=spawn(binary,args,{stdio:['ignore',out,out],env,detached:false});}finally{fs.closeSync(out);}await new Promise((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});child.unref();return child;}
async function atomicJson(file,value){const temp=file+'.tmp-'+randomUUID();await fsp.writeFile(temp,JSON.stringify(value)+'\n',{mode:0o600});const handle=await fsp.open(temp,'r');try{await handle.sync();}finally{await handle.close();}await fsp.rename(temp,file);}
export class ExtensionManager {
  constructor(config,deps={}){this.config=config;this.deps={processIdentity,processExists,bootId,profileBusy,launch,applyProfile:applyExtensionClientProfile,bridgeFactory:o=>new ExtensionBridge(o),...deps};this.transport='extension';this.recoveryNavigationDisabled=true;this.owned=new Set();this.closing=new Set();this.guards={};this.queue=Promise.resolve();this.bridge=null;this.journal=null;this.initPromise=null;this.stopped=false;this.saveChain=Promise.resolve();this.profileInitializations=new Map();this.bootstrapCdpCommands=0;this.profileLeases=new Map();this.profileGapCount=0;this.profileContinuityUnverified=false;}
  #serial(fn){const p=this.queue.then(fn,fn);this.queue=p.catch(()=>{});return p;}
  #allowed(guard,...args){try{return !guard||guard(...args)===true;}catch{return false;}}
  setLifecycleGuards(guards={}){this.guards=guards;}
  isOwnedTarget(id){return this.owned.has(String(id));}
  targetClosing(id){return this.closing.has(String(id));}
  isOwnedBootstrapTarget(target,conversationDigest){
    const metadata=this.journal?.targetMetadata?.[target?.id];if(!this.#processOwned()||!this.isOwnedTarget(target?.id)||!metadata?.bootstrapUrl||!metadata.entryUrl||target.entryPending!==true)return false;
    let url;try{url=new URL(metadata.bootstrapUrl);}catch{return false;}
    return url.origin===this.bridge?.url&&/^\/bootstrap\/[0-9a-f-]{36}$/.test(url.pathname)&&!url.search&&!url.hash&&target.url===metadata.bootstrapUrl&&target.bootstrapUrl===metadata.bootstrapUrl&&target.entryUrl===metadata.entryUrl&&!!canonicalUrl(metadata.entryUrl)&&digest(canonicalUrl(metadata.entryUrl))===conversationDigest;
  }
  status(){return{...(this.bridge?.status()||{adapter:'extension',connected:false,pending:0,commandCount:0}),profileBootstrap:this.config.extensionProfileBootstrap||'none',bootstrapCdpCommands:this.bootstrapCdpCommands,profileConnections:[...this.profileLeases.values()].filter(lease=>lease.isAlive()).length,profileGapCount:this.profileGapCount,profileContinuityUnverified:this.profileContinuityUnverified};}
  async #save(){const snapshot=structuredClone({...this.journal,targets:[...this.owned]});const write=this.saveChain.catch(()=>{}).then(()=>atomicJson(this.journalFile,snapshot));this.saveChain=write;await write;}
  #processOwned(){return extensionProcessMatches(this.journal?.process,this.deps.processIdentity(this.journal?.process?.pid),this.deps.bootId());}
  #processExited(){const p=this.journal?.process;if(!p)return true;const row=this.deps.processIdentity(p.pid);return !this.deps.processExists(p.pid)||(row?.state==='Z'&&row.startTime===p.startTime&&p.bootId===this.deps.bootId());}
  async #init(){
    if(this.stopped)throw fail('Extension manager stopped','EXTENSION_UNAVAILABLE');
    if(this.bridge)return;
    const cfg=this.config;if(cfg.browserClientProfile==='android-webview'&&cfg.extensionProfileBootstrap!=='cdp-emulation')throw fail('Android browser profile requires explicit initial CDP emulation selection','EXTENSION_PROFILE_BOOTSTRAP_REQUIRED');let pair;try{const stat=await fsp.lstat(cfg.extensionPairingFile);if(!stat.isFile()||(stat.mode&0o077)!==0)throw Error('private file required');pair=JSON.parse(await fsp.readFile(cfg.extensionPairingFile,'utf8'));}catch{throw fail('Approved private extension pairing file is required','EXTENSION_PAIRING_REQUIRED');}
    if(pair.schema!=='selfrun-extension-pair-v1'||!pair.ownerId||typeof pair.capability!=='string'||pair.capability.length<16)throw fail('Extension pairing file is invalid','EXTENSION_PAIRING_REQUIRED');
    if(!path.isAbsolute(cfg.extensionChromiumCommand||''))throw fail('Verified absolute Chromium binary is required','EXTENSION_BINARY_REQUIRED');
    await fsp.mkdir(cfg.dataDir,{recursive:true});this.journalFile=path.join(cfg.dataDir,'extension-ownership.json');this.extensionPath=path.join(cfg.dataDir,'browser-extension');
    let prior;try{prior=JSON.parse(await fsp.readFile(this.journalFile,'utf8'));}catch(e){if(e.code!=='ENOENT')throw fail('Extension ownership journal is unreadable','EXTENSION_OWNERSHIP_UNCERTAIN');}
    this.journal=prior?.ownerId===pair.ownerId&&prior.profile===cfg.browserProfile?prior:{schema:'selfrun-extension-ownership-v1',ownerId:pair.ownerId,profile:cfg.browserProfile,browserSessionId:randomUUID(),process:null,targets:[]};
    if(this.journal.process&&!this.#processOwned()){
      if(!this.#processExited())throw fail('Recorded browser identity changed or is unreadable','EXTENSION_OWNERSHIP_UNCERTAIN');
      this.journal={...this.journal,browserSessionId:randomUUID(),process:null,targets:[]};
    }
    this.journal.targetMetadata??={};this.owned=new Set(this.journal.targets||[]);
    const bridge=this.deps.bridgeFactory({port:cfg.extensionBridgePort,ownerId:pair.ownerId,capability:pair.capability,browserSessionId:this.journal.browserSessionId});
    await bridge.start();this.bridge=bridge;
    bridge.on('targetRemoved',({targetId})=>{if(this.owned.delete(targetId)){this.profileLeases.get(targetId)?.release();this.profileLeases.delete(targetId);this.closing.delete(targetId);delete this.journal.targetMetadata[targetId];void this.#save().catch(()=>{});}});
    // Runtime config contains the local capability; it is never committed or logged.
    await fsp.mkdir(this.extensionPath,{recursive:true,mode:0o700});
    const files=buildExtensionFiles({bridgeUrl:bridge.url,ownerId:pair.ownerId,capability:pair.capability,browserSessionId:this.journal.browserSessionId,fixtureOrigin:cfg.extensionFixtureOrigin||null});
    for(const [name,contents]of Object.entries(files))await fsp.writeFile(path.join(this.extensionPath,name),contents,{mode:0o600});await this.#save();
  }
  async #initialize(){if(!this.initPromise)this.initPromise=this.#init().catch(async e=>{await this.bridge?.close().catch(()=>{});this.bridge=null;this.initPromise=null;throw e;});return this.initPromise;}
  async #ensure(){await this.#initialize();if(this.#processOwned()){try{await this.bridge.waitReady(1000);return;}catch{}
      if(!this.#processOwned())throw fail('Owned browser changed before controller restore','EXTENSION_OWNERSHIP_UNCERTAIN');
      await this.deps.launch(this.config.extensionChromiumCommand,[`--user-data-dir=${this.config.browserProfile}`,`chrome-extension://${EXTENSION_ID}/controller.html`],{logFile:this.config.browserLogFile,env:{...process.env,TMPDIR:this.config.extensionTempDir}});
      await this.bridge.waitReady(15000);return;}
    if(this.journal.process){
      if(!this.#processExited())throw fail('Browser process identity is unreadable or changed','EXTENSION_OWNERSHIP_UNCERTAIN');
      this.journal={...this.journal,browserSessionId:randomUUID(),process:null,targets:[],targetMetadata:{}};this.owned.clear();this.closing.clear();await this.#save();await this.bridge.close();this.bridge=null;this.initPromise=null;await this.#initialize();
    }
    if(this.deps.profileBusy(this.config.browserProfile))throw fail('Browser profile is already in use; automatic takeover is forbidden','BROWSER_PROFILE_BUSY');
    const cfg=this.config;await fsp.mkdir(cfg.browserProfile,{recursive:true});await fsp.mkdir(cfg.extensionTempDir,{recursive:true,mode:0o700});
    const args=['--headless=new','--single-process','--no-zygote','--no-sandbox','--disable-gpu','--disable-dev-shm-usage','--no-first-run','--no-default-browser-check',`--user-data-dir=${cfg.browserProfile}`,`--load-extension=${this.extensionPath}`,...(cfg.browserClientProfile==='android-webview'?['--remote-debugging-address=127.0.0.1',`--remote-debugging-port=${cfg.browserPort}`]:[]),`--selfrun-extension-owner=${this.journal.ownerId}`,`chrome-extension://${EXTENSION_ID}/controller.html`];
    const child=await this.deps.launch(cfg.extensionChromiumCommand,args,{logFile:cfg.browserLogFile,env:{...process.env,TMPDIR:cfg.extensionTempDir}});
    const row=this.deps.processIdentity(child.pid),boot=this.deps.bootId();const proof={pid:child.pid,startTime:row?.startTime,bootId:boot,profile:cfg.browserProfile,extensionPath:this.extensionPath,ownerId:this.journal.ownerId,...(cfg.browserClientProfile==='android-webview'?{cdpPort:Number(cfg.browserPort)}:{})};
    if(!extensionProcessMatches(proof,row,boot))throw fail('Started browser ownership is unproven','EXTENSION_OWNERSHIP_UNCERTAIN');this.journal.process=proof;await this.#save();await this.bridge.waitReady(20000);
  }
  ensureStarted(){return this.#serial(()=>this.#ensure());}
  createTarget(url='about:blank'){return this.#serial(async()=>{await this.#ensure();const target=await this.bridge.request('createTarget',{url,deferNavigation:this.config.browserClientProfile==='android-webview'});if(!String(target?.id).startsWith(`ext:${this.journal.browserSessionId}:`))throw fail('Invalid target identity','EXTENSION_TARGET_INVALID');this.owned.add(target.id);this.journal.targetMetadata[target.id]={bootstrapUrl:target.bootstrapUrl||null,pendingUrl:target.pendingUrl||null,entryUrl:target.entryUrl||(url!=='about:blank'?url:null),profileFingerprint:null};await this.#save();return target;});}
  async listExistingTargets(timeoutMs=1500){try{await this.#initialize();if(!this.#processOwned())return null;await this.bridge.waitReady(timeoutMs);const targets=await this.bridge.request('listTargets',{}, {timeoutMs});if(!Array.isArray(targets))return null;for(const target of targets)if(String(target.id).startsWith(`ext:${this.journal.browserSessionId}:`))this.owned.add(target.id);await this.#save();return targets;}catch{return null;}}
  async #initializeTarget(target,payload,signal){
    const mode=this.config.browserClientProfile||'native';if(payload.clientProfile!==mode)throw fail('Browser client profile differs from configured contract','BROWSER_PROFILE_UNSUPPORTED');
    if(mode==='native')return this.bridge.request('initialize',{...payload,targetId:target.id},{signal});
    if(mode!=='android-webview'||this.config.extensionProfileBootstrap!=='cdp-emulation')throw fail('Explicit profile bootstrap selection is required','EXTENSION_PROFILE_BOOTSTRAP_REQUIRED');
    const metadata=this.journal.targetMetadata[target.id],fingerprint=extensionProfileFingerprint(this.config);
    if(!metadata)throw fail('Original target bootstrap proof is unavailable','EXTENSION_PROFILE_UNPROVEN');
    const guard=()=>!signal?.aborted&&this.isOwnedTarget(target.id)&&!this.targetClosing(target.id)&&this.#processOwned();
    const priorLease=this.profileLeases.get(target.id);
    if(metadata.profileFingerprint!==fingerprint||!priorLease?.isAlive()){
      if(metadata.cdpTargetId&&!priorLease?.isAlive())this.profileContinuityUnverified=true;
      priorLease?.release();this.profileLeases.delete(target.id);
      let current;for(let attempt=0;attempt<40;attempt++){const inventory=await this.bridge.request('listTargets',{}, {signal});current=inventory.find(entry=>entry.id===target.id);if(!current||current.url===metadata.bootstrapUrl||(current.url&&current.url!=='about:blank')||metadata.cdpTargetId)break;await new Promise(resolve=>setTimeout(resolve,50));if(signal?.aborted)throw signal.reason||Error('Profile initialization aborted');}
      if(!current)throw fail('Original profile target is unavailable','EXTENSION_PROFILE_UNPROVEN');
      let rebind=null;
      if(current.url!==metadata.bootstrapUrl){
        if(!metadata.cdpTargetId)throw fail('Fresh initial target is required for profile bootstrap','EXTENSION_PROFILE_UNPROVEN');
        const verified=await this.bridge.request('recoveryInput',{targetId:target.id},{signal});
        if(verified?.url!==current.url)throw fail('Owned page document changed before profile restoration','EXTENSION_PROFILE_UNPROVEN');
        rebind={cdpTargetId:metadata.cdpTargetId,expectedUrl:current.url};
      }
      if(!guard())throw fail('Target owner changed before profile bootstrap','EXTENSION_PROFILE_UNPROVEN');
      const applied=await this.deps.applyProfile({target:{...target,bootstrapUrl:metadata.bootstrapUrl},config:this.config,signal,guard,rebind,bootstrapOrigin:this.bridge.url,
        onLost:()=>{if(this.closing.has(target.id)||!this.isOwnedTarget(target.id))return;this.profileContinuityUnverified=true;this.profileGapCount++;console.warn(JSON.stringify({event:'EXTENSION_PROFILE_CONNECTION_LOST',target_id:target.id,page_preserved:true}));}});
      if(!guard()){applied.release();throw fail('Target owner changed after profile bootstrap','EXTENSION_PROFILE_UNPROVEN');}
      this.profileLeases.set(target.id,applied);this.bootstrapCdpCommands+=applied.cdpCommands;metadata.profileFingerprint=fingerprint;metadata.cdpTargetId=applied.cdpTargetId;await this.#save();
    }
    await this.bridge.request('initialize',{...payload,targetId:target.id,profileApplied:true},{signal});
    if(metadata.pendingUrl){const targets=await this.bridge.request('listTargets',{}, {signal});const current=targets.find(t=>t.id===target.id);if(!current)throw fail('Initial target disappeared','TARGET_LOST');
      if(current.url===metadata.bootstrapUrl)await this.bridge.request('navigate',{targetId:target.id,url:metadata.pendingUrl},{signal});
      else if(current.url!==metadata.pendingUrl)throw fail('Initial target URL changed','TARGET_SCOPE_CHANGED');
      metadata.pendingUrl=null;await this.#save();
    }
    return true;
  }
  async connectTarget(target){
    if(!this.isOwnedTarget(target?.id)||this.targetClosing(target.id))throw fail('Target is not available for this owner','TARGET_NOT_OWNED');
    const abort=new AbortController(),manager=this;
    return{transport:'extension',close(){abort.abort();},async run(operation,payload={},options={}){
      if(abort.signal.aborted)throw Object.assign(fail('Extension session closed','ABORT_ERR'),{name:'AbortError'});
      if(!manager.isOwnedTarget(target.id)||manager.targetClosing(target.id))throw fail('Target ownership changed','TARGET_NOT_OWNED');
      const budget=Math.max(1,Math.round(Number(options.timeoutMs)||20000)),deadline=Date.now()+budget;
      const signal=AbortSignal.any([abort.signal,...(options.signal?[options.signal]:[]),AbortSignal.timeout(budget)]);
      if(operation==='initialize'||(manager.config.browserClientProfile==='android-webview'&&!manager.profileLeases.get(target.id)?.isAlive())){
        const existing=manager.profileInitializations.get(target.id);
        if(existing&&existing.owner!==abort.signal)throw fail('Previous profile initialization is still settling','PROFILE_INITIALIZATION_BUSY');
        if(!existing){const work=manager.#initializeTarget(target,operation==='initialize'?payload:{clientProfile:manager.config.browserClientProfile},signal);const entry={owner:abort.signal,work};manager.profileInitializations.set(target.id,entry);work.finally(()=>{if(manager.profileInitializations.get(target.id)===entry)manager.profileInitializations.delete(target.id);}).catch(()=>{});}
        const work=manager.profileInitializations.get(target.id).work;
        let onAbort;try{await Promise.race([work,new Promise((_,reject)=>{onAbort=()=>reject(signal.reason||Error('Profile operation aborted'));if(signal.aborted)onAbort();else signal.addEventListener('abort',onAbort,{once:true});})]);}
        finally{if(onAbort)signal.removeEventListener('abort',onAbort);}
        if(operation==='initialize')return true;
      }
      return manager.bridge.request(operation,{...payload,targetId:target.id},{...options,timeoutMs:Math.max(1,deadline-Date.now()),signal});
    }};
  }

  async #closeTarget(id,{guard,caller=false}={}){const allowed=target=>this.#allowed(guard,target)&&(caller||this.#allowed(this.guards.canCloseTarget,id,target));const skip=reason=>({closed:false,absent:false,reason});if(!this.isOwnedTarget(id))return skip('unowned_target');if(!allowed())return skip('guard_rejected');const targets=await this.listExistingTargets();if(!targets)return skip('targets_unavailable');const target=targets.find(t=>t.id===id);if(!allowed(target))return skip('guard_rejected');if(!target){this.owned.delete(id);this.profileLeases.get(id)?.release();this.profileLeases.delete(id);delete this.journal.targetMetadata[id];await this.#save();return{absent:true,reason:'already_absent'};}this.closing.add(id);try{const result=await this.bridge.request('closeTarget',{targetId:id,expectedUrl:target.url},{timeoutMs:5000});if(result?.closed||result?.absent){this.owned.delete(id);this.profileLeases.get(id)?.release();this.profileLeases.delete(id);this.closing.delete(id);delete this.journal.targetMetadata[id];await this.#save();}return result;}catch{return skip('close_unconfirmed');}}
  closeOwnedTarget(id,options={}){return this.#serial(()=>this.#closeTarget(id,options));}
  async closeTarget(id,{guard}={}){const r=await this.#serial(()=>this.#closeTarget(id,{guard,caller:typeof guard==='function'}));return r.closed===true||r.absent===true;}
  releaseIfUnused({guard}={}){return this.#serial(async()=>{const allowed=()=>this.#allowed(guard)&&this.#allowed(this.guards.canRelease);if(!allowed())return{released:false,reason:'guard_rejected'};if(!this.bridge?.status().connected)return{released:false,reason:'controller_absent'};const targets=await this.listExistingTargets();if(!targets||targets.length||this.bridge.pendingCount||!allowed())return{released:false,reason:'browser_in_use'};const result=await this.bridge.request('controllerStop',{}, {timeoutMs:3000});return{released:result?.stopped===true,reason:result?.stopped?'owned_controller_closed':result?.reason||'close_unconfirmed'};});}
  async probe(){await this.ensureStarted();const targets=await this.listExistingTargets();return{ready:true,adapter:'extension',browser:'Chromium extension',protocolVersion:null,targets:targets?.length??null};}
  async close(){this.stopped=true;for(const lease of this.profileLeases.values())lease.release();this.profileLeases.clear();await this.bridge?.close();}
}
