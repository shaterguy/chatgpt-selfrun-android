import os from 'node:os';import fs from 'node:fs';import fsp from 'node:fs/promises';import path from 'node:path';import {spawn} from 'node:child_process';import {randomUUID,createHash,createHmac} from 'node:crypto';
import {canonicalUrl,digest} from '../lifecycle.mjs';
import {applyExtensionClientProfile,extensionProfileFingerprint} from './extension-profile-bootstrap.mjs';
import {connectServerAction,browserAction,attestController,purgeInactiveAction} from './server-action-session.mjs';
import {ExtensionBridge} from './extension-bridge.mjs';import {buildExtensionFiles,EXTENSION_ID} from './extension-package.mjs';
const fail=(message,code)=>Object.assign(new Error(message),{code});
function processIdentity(pid){try{const stat=fs.readFileSync(`/proc/${pid}/stat`,'utf8'),fields=stat.slice(stat.lastIndexOf(')')+2).split(' ');return{pid:Number(pid),state:fields[0],startTime:fields[19],args:fs.readFileSync(`/proc/${pid}/cmdline`,'utf8').split('\0').filter(Boolean)};}catch{return null;}}
function processExists(pid){try{fs.statSync(`/proc/${pid}`);return true;}catch(e){return e.code!=='ENOENT';}}
function bootId(){try{return fs.readFileSync('/proc/sys/kernel/random/boot_id','utf8').trim();}catch{return null;}}
export function extensionProcessMatches(proof,row,boot){return !!proof&&!!row&&row.state!=='Z'&&!!boot&&proof.bootId===boot&&proof.pid===row.pid&&proof.startTime===row.startTime&&row.args.includes(`--user-data-dir=${proof.profile}`)&&row.args.includes(`--load-extension=${proof.extensionPath}`)&&row.args.includes(`--selfrun-extension-owner=${proof.ownerId}`)&&(!proof.cdpPort||(row.args.includes(`--remote-debugging-port=${proof.cdpPort}`)&&row.args.includes('--remote-debugging-address=127.0.0.1')))&&!row.args.some(x=>x.startsWith('--type='));}
// PID reuse and a changed boot prove that the recorded process exited.
// An unreadable process is not proof of exit and must never be signalled.
export function extensionProcessExited(proof,deps){
  if(!proof||!Number.isSafeInteger(proof.pid)||proof.pid<1||typeof proof.startTime!=='string'||!proof.startTime
    ||typeof proof.bootId!=='string'||!proof.bootId)return false;
  const row=deps.processIdentity(proof.pid),exists=deps.processExists(proof.pid),boot=deps.bootId();
  if(exists===false&&!row)return true;
  if(boot&&proof.bootId!==boot)return true;
  if(row?.pid===proof.pid&&typeof row.startTime==='string'&&row.startTime!==proof.startTime)return true;
  return !!row&&row.pid===proof.pid&&row.state==='Z'&&row.startTime===proof.startTime&&proof.bootId===boot;
}
// Only a root carrying this SelfRun pairing, exact extension and profile may be
// retired. This is deliberately scoped to the private Termux Chromium instance.
function findOwnedBrowserRoots({profile,extensionPath,ownerId}){
  let pids;try{pids=fs.readdirSync('/proc').filter(x=>/^\d+$/.test(x));}catch{return null;}
  const boot=bootId();if(!boot)return null;const roots=[];
  for(const raw of pids){const row=processIdentity(Number(raw));if(!row||row.state==='Z'||!row.startTime)continue;
    const proof={pid:row.pid,startTime:row.startTime,bootId:boot,profile,extensionPath,ownerId};
    if(extensionProcessMatches(proof,row,boot))roots.push(proof);
  }
  return roots;
}
// Pure proof check shared with guarded offline maintenance; never signals or initializes a browser.
export function clearedExitedExtensionJournal(journal,deps,canRelease=()=>false){
  try{
    if(canRelease()!==true||journal?.schema!=='selfrun-extension-ownership-v1'||!Array.isArray(journal.targets)||journal.targets.length
      ||!journal.targetMetadata||typeof journal.targetMetadata!=='object'||Array.isArray(journal.targetMetadata)||Object.keys(journal.targetMetadata).length)return null;
    const exited=proof=>extensionProcessExited(proof,deps);
    if(!exited(journal.process)||(journal.retiringProcess&&!exited(journal.retiringProcess))||canRelease()!==true)return null;
    return {...journal,controllerStopPending:false,retiringProcess:null};
  }catch{return null;}
}
export function profileBusy(profile){
  // Do not erase lock files. A dead, same-host holder is the ordinary Chromium
  // crash-recovery case; Chromium still performs its own singleton handshake.
  let lock=null;try{lock=fs.lstatSync(path.join(profile,'SingletonLock'));}catch(error){if(error.code!=='ENOENT')return true;}
  if(lock){
    if(!lock.isSymbolicLink()||lock.uid!==process.getuid())return true;
    let match;try{match=fs.readlinkSync(path.join(profile,'SingletonLock')).match(/^(.+)-([1-9][0-9]*)$/);}catch{return true;}
    if(!match||match[1]!==os.hostname())return true;const pid=Number(match[2]);
    if(!Number.isSafeInteger(pid)||pid>2147483647||processExists(pid))return true;
    try{process.kill(pid,0);return true;}catch(error){if(error.code!=='ESRCH')return true;}
  }
  try{for(const pid of fs.readdirSync('/proc').filter(x=>/^\d+$/.test(x))){const p=processIdentity(pid);if(p?.args.includes(`--user-data-dir=${profile}`)&&!p.args.some(x=>x.startsWith('--type=')))return true;}}catch{return true;}
  return false;
}

async function readCdpMetadata(port,resource,{signal}={}){const r=await fetch(`http://127.0.0.1:${port}/json/${resource}`,{redirect:'error',signal:signal?AbortSignal.any([signal,AbortSignal.timeout(2000)]):AbortSignal.timeout(2000)});if(!r.ok)throw Error('Owned browser inventory unavailable');return r.json();}
function validBrowserKey(key,port){try{const u=new URL(key);return u.protocol==='ws:'&&u.hostname==='127.0.0.1'&&Number(u.port)===Number(port)&&/^\/devtools\/browser\/[a-zA-Z0-9-]+$/.test(u.pathname)&&!u.search&&!u.hash;}catch{return false;}}
async function launch(binary,args,{logFile,env}){const out=fs.openSync(logFile,'a',0o600);let child;try{child=spawn(binary,args,{stdio:['ignore',out,out],env,detached:false});}finally{fs.closeSync(out);}await new Promise((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});child.unref();return child;}
async function atomicJson(file,value,guard=()=>true){
  if(!guard())return false;const temp=file+'.tmp-'+randomUUID();let pending=true;
  try{await fsp.writeFile(temp,JSON.stringify(value)+'\n',{mode:0o600});const handle=await fsp.open(temp,'r');try{await handle.sync();}finally{await handle.close();}
    if(!guard())return false;await fsp.rename(temp,file);pending=false;return true;
  }finally{if(pending)await fsp.unlink(temp).catch(error=>{if(error.code!=='ENOENT')throw error;});}
}
export class ExtensionManager {
  constructor(config,deps={}){this.config=config;this.deps={processIdentity,processExists,bootId,profileBusy,launch,readCdpMetadata,connectServerAction,browserAction,attestController,purgeInactiveAction,signalProcess:(pid)=>process.kill(pid,'SIGTERM'),delay:ms=>new Promise(r=>setTimeout(r,ms)),applyProfile:applyExtensionClientProfile,bridgeFactory:o=>new ExtensionBridge(o),findOwnedBrowserRoots,...deps};this.transport='extension';this.recoveryNavigationDisabled=true;this.owned=new Set();this.closing=new Set();this.guards={};this.queue=Promise.resolve();this.targetClosures=new Map();this.targetRemovalGeneration=0;this.bridge=null;this.journal=null;this.initPromise=null;this.stopped=false;this.lastActivityAt=Date.now();this.saveChain=Promise.resolve();this.profileInitializations=new Map();this.bootstrapCdpCommands=0;this.actionSessions=new Map();this.profileLeases=new Map();this.sessions=new Map();this.pendingAcquisitions=new Set();this.profileGapCount=0;this.profileContinuityUnverified=false;}
  #serial(fn){const p=this.queue.then(fn,fn);this.queue=p.catch(()=>{});return p;}
  #allowed(guard,...args){try{return !guard||guard(...args)===true;}catch{return false;}}
  setLifecycleGuards(guards={}){this.guards=guards;}
  #activeOwnedTargets(){
    const active=this.guards.activeTargetIds?.();
    if(!Array.isArray(active))return [...this.owned];
    for(const id of this.pendingAcquisitions)if(active.includes(id))this.pendingAcquisitions.delete(id);
    return [...new Set([...active.filter(id=>this.owned.has(id)),...this.pendingAcquisitions])];
  }
  isOwnedTarget(id){return this.owned.has(String(id));}
  async inspectCleanupDisposition(id){
    await this.#initialize();
    return this.cleanupDisposition(id);
  }
  cleanupDisposition(id){
    // Unaddressable metadata is not proof of tab absence or business completion.
    if(typeof id!=='string'||!id||this.journal?.schema!=='selfrun-extension-ownership-v1'||typeof this.journal.browserSessionId!=='string'
      ||!this.journal.browserSessionId||this.owned.has(id)||this.closing.has(id)||this.journal.controllerStopPending)return null;
    let reason;
    if(!id.startsWith('ext:'))reason='UNADDRESSABLE_NAMESPACE';
    else {
      const parts=id.split(':');
      if(parts.length!==3||!parts[1]||!parts[2]||parts[1]===this.journal.browserSessionId)return null;
      reason='OWNER_SESSION_CHANGED';
    }
    return {schema:1,adapter:'extension',target_id:id,observed_session:this.journal.browserSessionId,reason};
  }
  targetClosing(id){return this.closing.has(String(id));}
  isOwnedBootstrapTarget(target,conversationDigest){
    const metadata=this.journal?.targetMetadata?.[target?.id];if(!this.#processOwned()||!this.isOwnedTarget(target?.id)||!metadata?.bootstrapUrl||!metadata.entryUrl||target.entryPending!==true)return false;
    let url;try{url=new URL(metadata.bootstrapUrl);}catch{return false;}
    return url.origin===this.bridge?.url&&/^\/bootstrap\/[0-9a-f-]{36}$/.test(url.pathname)&&!url.search&&!url.hash&&target.url===metadata.bootstrapUrl&&target.bootstrapUrl===metadata.bootstrapUrl&&target.entryUrl===metadata.entryUrl&&!!canonicalUrl(metadata.entryUrl)&&digest(canonicalUrl(metadata.entryUrl))===conversationDigest;
  }
  status(){return{...(this.bridge?.status()||{adapter:'extension',connected:false,pending:0,commandCount:0}),profileBootstrap:this.config.extensionProfileBootstrap||'none',bootstrapCdpCommands:this.bootstrapCdpCommands,profileConnections:[...this.profileLeases.values()].filter(lease=>lease.isAlive()).length,profileGapCount:this.profileGapCount,profileContinuityUnverified:this.profileContinuityUnverified};}
  // Ownership snapshots and idle retirement must share one disk-write lane.
  // A delayed callback from a retired browser cannot resurrect that generation.
  #persistJournal(snapshot,guard=()=>true){
    const write=this.saveChain.catch(()=>{}).then(()=>atomicJson(this.journalFile,snapshot,guard));
    this.saveChain=write;return write;
  }
  async #save(){
    const owner=this.journal;owner.targets=[...this.owned];const snapshot=structuredClone(owner);
    await this.#persistJournal(snapshot,()=>this.journal===owner);
  }
  #processOwned(){return extensionProcessMatches(this.journal?.process,this.deps.processIdentity(this.journal?.process?.pid),this.deps.bootId());}
  #processExited(){return !this.journal?.process||extensionProcessExited(this.journal.process,this.deps);}
  #ownedRoots(){
    const roots=this.deps.findOwnedBrowserRoots({profile:this.config.browserProfile,extensionPath:this.extensionPath,ownerId:this.journal.ownerId});
    if(!Array.isArray(roots))return null;
    if(this.#processOwned()&&!roots.some(p=>p.pid===this.journal.process.pid))roots.push({...this.journal.process});
    return roots;
  }
  async #clearIdleBrowser(allowed){
    if(!allowed()||this.profileInitializations.size||this.bridge?.pendingCount)return false;
    const roots=this.#ownedRoots();if(!roots||roots.length||this.journal.process&&!this.#processExited())return false;
    if(!this.journal.process&&!this.owned.size&&!(this.journal.targets||[]).length&&!Object.keys(this.journal.targetMetadata||{}).length
      &&!this.journal.controllerStopPending&&!this.journal.retiringProcess){
      if(this.bridge){const bridge=this.bridge;this.bridge=null;this.initPromise=null;await bridge.close();}
      return true;
    }
    const previous=this.journal,next={...previous,browserSessionId:randomUUID(),process:null,targets:[],targetMetadata:{},controllerStopPending:false,retiringProcess:null};
    if(!await this.#persistJournal(next,()=>allowed()&&this.journal===previous&&!this.#ownedRoots()?.length))return false;
    this.journal=next;this.targetRemovalGeneration++;this.owned.clear();this.closing.clear();this.targetClosures.clear();
    for(const lease of this.profileLeases.values())lease.release();this.profileLeases.clear();
    if(this.bridge){const bridge=this.bridge;this.bridge=null;this.initPromise=null;await bridge.close();}
    return true;
  }
  async #init(){
    if(this.stopped)throw fail('Extension manager stopped','EXTENSION_UNAVAILABLE');
    if(this.bridge)return;
    const cfg=this.config;if(cfg.browserClientProfile==='android-webview'&&cfg.extensionProfileBootstrap!=='cdp-emulation')throw fail('Android browser profile requires explicit initial CDP emulation selection','EXTENSION_PROFILE_BOOTSTRAP_REQUIRED');let pair;try{const stat=await fsp.lstat(cfg.extensionPairingFile);if(!stat.isFile()||(stat.mode&0o077)!==0)throw Error('private file required');pair=JSON.parse(await fsp.readFile(cfg.extensionPairingFile,'utf8'));}catch{throw fail('Approved private extension pairing file is required','EXTENSION_PAIRING_REQUIRED');}
    if(pair.schema!=='selfrun-extension-pair-v1'||!pair.ownerId||typeof pair.capability!=='string'||pair.capability.length<16)throw fail('Extension pairing file is invalid','EXTENSION_PAIRING_REQUIRED');
    if(!path.isAbsolute(cfg.extensionChromiumCommand||''))throw fail('Verified absolute Chromium binary is required','EXTENSION_BINARY_REQUIRED');
    await fsp.mkdir(cfg.dataDir,{recursive:true});this.journalFile=path.join(cfg.dataDir,'extension-ownership.json');this.extensionPath=path.join(cfg.dataDir,'browser-extension');
    let prior;try{prior=JSON.parse(await fsp.readFile(this.journalFile,'utf8'));}catch(e){if(e.code!=='ENOENT')throw fail('Extension ownership journal is unreadable','EXTENSION_OWNERSHIP_UNCERTAIN');}
    this.journal=prior?.ownerId===pair.ownerId&&prior.profile===cfg.browserProfile?prior:{schema:'selfrun-extension-ownership-v1',ownerId:pair.ownerId,profile:cfg.browserProfile,browserSessionId:randomUUID(),process:null,targets:[],targetMetadata:{},controllerStopPending:false,retiringProcess:null};
    if(this.journal.process&&!this.#processOwned()){
      if(!this.#processExited())throw fail('Recorded browser identity changed or is unreadable','EXTENSION_OWNERSHIP_UNCERTAIN');
      this.journal={...this.journal,browserSessionId:randomUUID(),process:null,targets:[],targetMetadata:{},controllerStopPending:false,retiringProcess:null};
    }
    this.installationKey=createHash('sha256').update('selfrun-page-bootstrap:'+pair.capability+':'+this.journal.browserSessionId).digest('hex');
    this.journal.targetMetadata??={};this.owned=new Set(this.journal.targets||[]);
    const active=this.guards.activeTargetIds?.();
    if(Array.isArray(active)){
      for(const id of [...this.owned])if(!active.includes(id))this.owned.delete(id);
      for(const id of Object.keys(this.journal.targetMetadata))if(!this.owned.has(id))delete this.journal.targetMetadata[id];
    }
    const bridge=this.deps.bridgeFactory({port:cfg.extensionBridgePort,ownerId:pair.ownerId,capability:pair.capability,browserSessionId:this.journal.browserSessionId,activeOwnedTargets:()=>this.#activeOwnedTargets()});
    await bridge.start();this.bridge=bridge;
    bridge.on('ready',()=>{void this.reconcileInactiveActions().catch(()=>{});});
    bridge.on('targetRemoved',({targetId})=>{if(this.bridge!==bridge||!String(targetId).startsWith(`ext:${this.journal.browserSessionId}:`))return;this.targetRemovalGeneration++;const action=this.actionSessions.get(targetId);this.actionSessions.delete(targetId);action?.close();if(this.owned.delete(targetId)){this.pendingAcquisitions.delete(targetId);for(const session of this.sessions.get(targetId)||[])session.abort();this.sessions.delete(targetId);this.profileInitializations.delete(targetId);this.profileLeases.get(targetId)?.release();this.profileLeases.delete(targetId);this.closing.delete(targetId);delete this.journal.targetMetadata[targetId];void this.#save().catch(()=>{});}});
    // Runtime config contains the local capability; it is never committed or logged.
    await fsp.mkdir(this.extensionPath,{recursive:true,mode:0o700});
    const files=buildExtensionFiles({bridgeUrl:bridge.url,ownerId:pair.ownerId,capability:pair.capability,browserSessionId:this.journal.browserSessionId,fixtureOrigin:cfg.extensionFixtureOrigin||null});
    for(const [name,contents]of Object.entries(files))await fsp.writeFile(path.join(this.extensionPath,name),contents,{mode:0o600});await this.#save();
  }
  async #initialize(){if(!this.initPromise)this.initPromise=this.#init().catch(async e=>{await this.bridge?.close().catch(()=>{});this.bridge=null;this.initPromise=null;throw e;});return this.initPromise;}
  #emptyLocal(){return this.owned.size===0&&this.closing.size===0&&this.profileLeases.size===0&&this.profileInitializations.size===0&&!this.bridge?.pendingCount;}
  async #bindBrowserEndpoint(targetId,expectedUrl,signal,guard){
    const port=Number(this.config.browserPort),check=()=>{if(signal?.aborted)throw signal.reason;if(!guard()||!this.#processOwned())throw fail('Browser endpoint owner changed','EXTENSION_OWNERSHIP_UNCERTAIN');};
    const matches=rows=>Array.isArray(rows)&&rows.filter(t=>(!targetId||t.id===targetId)&&t.type==='page'&&t.url===expectedUrl).length===1;
    check();const before=await this.deps.readCdpMetadata(port,'list',{signal});if(!matches(before))throw fail('Owned profile target not found','EXTENSION_PROFILE_UNPROVEN');targetId=before.find(t=>(!targetId||t.id===targetId)&&t.type==='page'&&t.url===expectedUrl).id;
    const version=await this.deps.readCdpMetadata(port,'version',{signal});check();if(!validBrowserKey(version?.webSocketDebuggerUrl,port))throw fail('Browser endpoint key invalid','EXTENSION_PROFILE_UNPROVEN');
    if(!matches(await this.deps.readCdpMetadata(port,'list',{signal})))throw fail('Owned profile target changed','EXTENSION_PROFILE_UNPROVEN');check();
    if(this.journal.process.browserKey&&this.journal.process.browserKey!==version.webSocketDebuggerUrl)throw fail('Owned browser endpoint changed','EXTENSION_OWNERSHIP_UNCERTAIN');
    this.journal.process.browserKey=version.webSocketDebuggerUrl;
  }
  async #proveEmptyBrowser(proof,allowed,deadline=Date.now()+2000){
    const valid=()=>Date.now()<deadline&&allowed()&&this.#emptyLocal()&&this.#processOwned()&&this.journal.process?.pid===proof.pid&&this.journal.process?.startTime===proof.startTime&&this.journal.process?.browserKey===proof.browserKey;
    if(!valid()||Number(proof.cdpPort)!==Number(this.config.browserPort)||!validBrowserKey(proof.browserKey,proof.cdpPort))return false;
    try{const options={signal:AbortSignal.timeout(Math.max(1,deadline-Date.now()))};const before=await this.deps.readCdpMetadata(proof.cdpPort,'version',options);if(!valid()||before?.webSocketDebuggerUrl!==proof.browserKey)return false;
      const rows=await this.deps.readCdpMetadata(proof.cdpPort,'list',options);if(!valid()||!Array.isArray(rows)||rows.length)return false;
      const after=await this.deps.readCdpMetadata(proof.cdpPort,'version',options);return valid()&&after?.webSocketDebuggerUrl===proof.browserKey;
    }catch{return false;}
  }
  async #retireEmptyBrowser(allowed,deadline=Date.now()+5000){
    if(this.#processExited())return true;const proof={...this.journal.process};
    if(!await this.#proveEmptyBrowser(proof,allowed,deadline))return false;
    this.journal.retiringProcess={pid:proof.pid,startTime:proof.startTime,bootId:proof.bootId,browserKey:proof.browserKey};await this.#save();
    if(!await this.#proveEmptyBrowser(proof,allowed,deadline))return false;
    // Only the dedicated, proven empty root is signalled. No broad process kill,
    // lock deletion, page navigation, extra CDP command or SIGKILL fallback.
    if(!allowed()||!this.#emptyLocal()||!this.#processOwned())return false;
    try{this.deps.signalProcess(proof.pid);}catch(error){if(error.code!=='ESRCH')return false;}
    for(let n=0;n<100&&Date.now()<deadline;n++){if(this.#processExited())return true;if(!this.#processOwned())return false;await this.deps.delay(50);}return this.#processExited();
  }
  async #ensure({signal,guard=()=>true,observerReady=true}={}){
    const check=()=>{if(this.stopped||signal?.aborted||!this.#allowed(guard))throw Object.assign(fail('Browser acquisition cancelled','ABORT_ERR'),{name:'AbortError'});};
    check();await this.#initialize();check();if(this.#processOwned()){
      if(!observerReady&&!this.journal.retiringProcess&&!this.journal.controllerStopPending)return;
      if(!this.journal.retiringProcess){try{
        // The retained controller reconnects with up to 10s backoff. Await its
        // authenticated hello using the existing cold-start budget, waking on
        // readiness or loss of the exact owner/current request, not a blind delay.
        await this.bridge.waitReady(20000,{signal,guard:()=>!this.stopped&&this.#allowed(guard)&&this.#processOwned()});
        check();if(!this.#processOwned())throw fail('Browser owner changed during readiness','EXTENSION_OWNERSHIP_UNCERTAIN');
        if(!this.journal.controllerStopPending)return;
        const idle=await this.bridge.request('idleStatus',{}, {timeoutMs:1000,signal});if(idle?.stopping===false){this.journal.controllerStopPending=false;await this.#save();return;}
      }catch(error){check();if(error.code==='ABORT_ERR'||!this.#processOwned())throw error;}}
      // Headless Chromium rejects singleton CLI forwarding. Reopen only after
      // a persisted owned-target/endpoint proof establishes an empty old root.
      if(!await this.#retireEmptyBrowser(()=>!this.stopped&&!signal?.aborted&&this.#allowed(guard)))throw fail('Owned controller unavailable and browser cannot be safely retired','EXTENSION_UNAVAILABLE');
    }
    if(this.journal.process){
      if(!this.#processExited())throw fail('Browser process identity is unreadable or changed','EXTENSION_OWNERSHIP_UNCERTAIN');
      this.journal={...this.journal,browserSessionId:randomUUID(),process:null,targets:[],targetMetadata:{},controllerStopPending:false,retiringProcess:null};this.owned.clear();this.closing.clear();await this.#save();await this.bridge.close();this.bridge=null;this.initPromise=null;await this.#initialize();
    }
    // Let Chromium handle its native profile singleton; stale lock heuristics must not block launch.
    check();
    const cfg=this.config;await fsp.mkdir(cfg.browserProfile,{recursive:true});await fsp.mkdir(cfg.extensionTempDir,{recursive:true,mode:0o700});
    const args=['--headless=new','--single-process','--no-zygote','--no-sandbox','--disable-gpu','--disable-dev-shm-usage','--no-first-run','--no-default-browser-check',`--user-data-dir=${cfg.browserProfile}`,`--load-extension=${this.extensionPath}`,...(cfg.browserClientProfile==='android-webview'?['--remote-debugging-address=127.0.0.1',`--remote-debugging-port=${cfg.browserPort}`]:[]),`--selfrun-extension-owner=${this.journal.ownerId}`,`chrome-extension://${EXTENSION_ID}/controller.html`];
    check();const child=await this.deps.launch(cfg.extensionChromiumCommand,args,{logFile:cfg.browserLogFile,env:{...process.env,TMPDIR:cfg.extensionTempDir}});
    const row=this.deps.processIdentity(child.pid),boot=this.deps.bootId();const proof={pid:child.pid,startTime:row?.startTime,bootId:boot,profile:cfg.browserProfile,extensionPath:this.extensionPath,ownerId:this.journal.ownerId,...(cfg.browserClientProfile==='android-webview'?{cdpPort:Number(cfg.browserPort)}:{})};
    if(!extensionProcessMatches(proof,row,boot))throw fail('Started browser ownership is unproven','EXTENSION_OWNERSHIP_UNCERTAIN');this.journal.process=proof;await this.#save();if(observerReady)await this.bridge.waitReady(20000,{signal,guard:()=>!this.stopped&&this.#allowed(guard)&&this.#processOwned()});check();
  }
  ensureStarted(){this.lastActivityAt=Date.now();return this.#serial(()=>this.#ensure());}
  createTarget(url='about:blank',options={}){this.lastActivityAt=Date.now();return this.#serial(async()=>{
    await this.#ensure({...options,observerReady:false});
    const guard=()=>!this.stopped&&!options.signal?.aborted&&this.#allowed(options.guard)&&this.#processOwned();
    let version;const acquisitionDeadline=Date.now()+20000;
    for(let attempt=0;attempt<200&&guard();attempt++){
      try{version=await this.deps.readCdpMetadata(Number(this.config.browserPort),'version',options);break;}
      catch(error){if(!guard()||Date.now()>=acquisitionDeadline)throw error;await this.deps.delay(100);}
    }
    if(!guard()||!validBrowserKey(version?.webSocketDebuggerUrl,Number(this.config.browserPort)))throw fail('Owned action endpoint unavailable','ACTION_ENDPOINT_UNAVAILABLE');
    if(this.journal.process.browserKey&&this.journal.process.browserKey!==version.webSocketDebuggerUrl)throw fail('Browser endpoint changed','EXTENSION_OWNERSHIP_UNCERTAIN');
    if(!this.journal.process.browserKey){
      let proven=false;
      for(let attempt=0;attempt<200&&guard()&&Date.now()<acquisitionDeadline;attempt++){
        const challenge=randomUUID(),expected=createHmac('sha256',this.installationKey).update(challenge).digest('hex');
        const rows=await this.deps.readCdpMetadata(Number(this.config.browserPort),'list',options);
        for(const row of Array.isArray(rows)?rows:[])if(row.type==='page'&&row.url===`chrome-extension://${EXTENSION_ID}/controller.html`){
          try{if(await this.deps.attestController({row,port:Number(this.config.browserPort),challenge,signal:options.signal})===expected){proven=true;break;}}catch{}
        }
        const after=await this.deps.readCdpMetadata(Number(this.config.browserPort),'version',options);
        if(!guard()||after?.webSocketDebuggerUrl!==version.webSocketDebuggerUrl)throw fail('Browser endpoint ownership is unproven','EXTENSION_PROFILE_UNPROVEN');
        if(proven)break;await this.deps.delay(100);
      }
      if(!proven)throw fail('Browser endpoint ownership is unproven','EXTENSION_PROFILE_UNPROVEN');
      this.journal.process.browserKey=version.webSocketDebuggerUrl;
    }
    const bootstrapUrl=this.bridge.url+'/bootstrap/'+randomUUID();
    let created;
    try{created=await this.deps.browserAction({browserKey:version.webSocketDebuggerUrl,port:Number(this.config.browserPort),method:'Target.createTarget',params:{url:bootstrapUrl},signal:options.signal,guard});}
    catch(error){
      // One in-flight reconciliation only: recover the exact random bootstrap
      // under the same proven browser. Nothing is queued or retained afterwards.
      try{
        const cleanupSignal=AbortSignal.timeout(2000);
        const current=await this.deps.readCdpMetadata(Number(this.config.browserPort),'version',{signal:cleanupSignal});
        if(this.#processOwned()&&current?.webSocketDebuggerUrl===version.webSocketDebuggerUrl){
          for(let attempt=0;attempt<10&&!cleanupSignal.aborted;attempt++){
            const rows=await this.deps.readCdpMetadata(Number(this.config.browserPort),'list',{signal:cleanupSignal});
            const matches=rows.filter(row=>row.type==='page'&&row.url===bootstrapUrl);
            if(matches.length===1){created={targetId:matches[0].id};break;}
            if(matches.length>1)break;await this.deps.delay(100);
          }
        }
      }catch{}
      if(!created)throw error;
    }
    if(typeof created?.targetId!=='string'||!created.targetId)throw fail('Created target identity unavailable','ACTION_TARGET_UNAVAILABLE');
    if(!guard()){
      try{await this.deps.browserAction({browserKey:version.webSocketDebuggerUrl,port:Number(this.config.browserPort),method:'Target.closeTarget',params:{targetId:created.targetId},expectedUrl:bootstrapUrl,guard:()=>this.#processOwned()&&this.journal.process.browserKey===version.webSocketDebuggerUrl});}catch{}
      throw fail('Created target owner ended','ABORT_ERR');
    }
    const id='ext:'+this.journal.browserSessionId+':'+created.targetId;
    this.owned.add(id);this.pendingAcquisitions.add(id);
    this.journal.targetMetadata[id]={bootstrapUrl,pendingUrl:url==='about:blank'?null:url,entryUrl:url==='about:blank'?null:url,profileFingerprint:null,cdpTargetId:created.targetId,observerTabId:null};await this.#save();
    if(!guard()){await this.closeOwnedTarget(id,{guard:row=>!row||row.url===bootstrapUrl});if(this.owned.has(id))await this.discardOwnedTarget(id,{remote:false});throw fail('Created target owner ended','ABORT_ERR');}
    return{id,type:'page',url:bootstrapUrl,bootstrapUrl,entryUrl:url==='about:blank'?null:url,entryPending:true};
  });}
  async listExistingTargets(timeoutMs=1500){try{
    await this.#initialize();if(!this.#processOwned())return null;
    const rows=await this.deps.readCdpMetadata(Number(this.config.browserPort),'list',{signal:AbortSignal.timeout(timeoutMs)});
    if(!Array.isArray(rows)||!this.#processOwned())return null;
    return [...this.owned].flatMap(id=>{const meta=this.journal.targetMetadata[id],row=rows.find(r=>r.id===meta?.cdpTargetId&&r.type==='page');return row?[{...row,id,bootstrapUrl:meta.bootstrapUrl,entryUrl:meta.entryUrl,entryPending:row.url===meta.bootstrapUrl}]:[];});
  }catch{return null;}}
  async reconcileInactiveActions(){
    const owner=this.journal,proof=owner?.process;
    if(this.stopped||!proof?.browserKey||!this.#processOwned())return;
    const stillOwned=()=>!this.stopped&&this.journal===owner&&owner.process===proof&&this.#processOwned();
    const version=await this.deps.readCdpMetadata(Number(this.config.browserPort),'version',{signal:AbortSignal.timeout(2000)});
    if(!stillOwned()||version?.webSocketDebuggerUrl!==proof.browserKey)return;
    const rows=await this.deps.readCdpMetadata(Number(this.config.browserPort),'list',{signal:AbortSignal.timeout(2000)});
    for(const row of Array.isArray(rows)?rows:[]){
      const id='ext:'+owner.browserSessionId+':'+row.id;
      let allowedOrigin=false;try{allowedOrigin=new URL(row.url).origin===(this.config.extensionFixtureOrigin||'https://chatgpt.com');}catch{}
      const inactive=()=>stillOwned()&&!this.owned.has(id)&&!this.pendingAcquisitions.has(id)&&!(this.guards.activeTargetIds?.()||[]).includes(id);
      if(row.type!=='page'||!allowedOrigin||!inactive())continue;
      try{await this.deps.purgeInactiveAction({row,port:Number(this.config.browserPort),installationKey:createHmac('sha256',this.installationKey).update(id).digest('hex'),guard:inactive});}catch{}
    }
  }
  async #actionSession(target,signal){
    let existing=this.actionSessions.get(target.id);if(existing&&!existing.closed)return existing;
    const meta=this.journal.targetMetadata[target.id];
    const guard=()=>this.isOwnedTarget(target.id)&&!this.targetClosing(target.id)&&this.#processOwned();
    const rows=await this.deps.readCdpMetadata(Number(this.config.browserPort),'list',{signal});
    const row=rows?.find(r=>r.id===meta?.cdpTargetId&&r.type==='page');if(!row||!guard())throw fail('Owned action target unavailable','TARGET_LOST');
    const session=await this.deps.connectServerAction({row,port:Number(this.config.browserPort),installationKey:createHmac('sha256',this.installationKey).update(target.id).digest('hex'),guard,allowedOrigins:[this.config.extensionFixtureOrigin||'https://chatgpt.com'],onReconnect:()=>{void this.reconcileInactiveActions().catch(()=>{});},signal});
    if(!guard()){session.close();throw fail('Action owner changed','TARGET_NOT_OWNED');}
    this.actionSessions.set(target.id,session);void this.reconcileInactiveActions().catch(()=>{});return session;
  }
  async #registerObserver(target,signal){
    const meta=this.journal.targetMetadata[target.id];if(!meta||!this.isOwnedTarget(target.id))throw fail('Observation target unavailable','TARGET_NOT_OWNED');
    const rows=await this.deps.readCdpMetadata(Number(this.config.browserPort),'list',{signal}),row=rows?.find(r=>r.id===meta.cdpTargetId&&r.type==='page');
    if(!row)throw fail('Observation target unavailable','TARGET_LOST');
    await this.bridge.waitReady(5000,{signal,guard:()=>this.isOwnedTarget(target.id)&&!this.targetClosing(target.id)&&this.#processOwned()});
    const challenge=randomUUID(),key=createHmac('sha256',this.installationKey).update(target.id).digest('hex'),mac=createHmac('sha256',key).update(challenge).digest('hex');
    const registered=await this.bridge.request('registerTarget',{targetId:target.id,tabId:meta.observerTabId,expectedUrl:row.url,bootstrapUrl:meta.bootstrapUrl,challenge,mac},{signal});
    if(!this.isOwnedTarget(target.id)||this.journal.targetMetadata[target.id]!==meta)throw fail('Observation owner changed','TARGET_NOT_OWNED');
    meta.observerTabId=registered.tabId;await this.#save();return true;
  }
  async #initializeTarget(target,payload,signal){
    const mode=this.config.browserClientProfile||'native';if(payload.clientProfile!==mode)throw fail('Browser client profile differs from configured contract','BROWSER_PROFILE_UNSUPPORTED');
    const metadata=this.journal.targetMetadata[target.id],guard=()=>!signal?.aborted&&this.isOwnedTarget(target.id)&&!this.targetClosing(target.id)&&this.#processOwned();
    if(mode==='android-webview'&&(!this.profileLeases.get(target.id)?.isAlive()||metadata?.profileFingerprint!==extensionProfileFingerprint(this.config))){
      const rows=await this.deps.readCdpMetadata(Number(this.config.browserPort),'list',{signal}),row=rows?.find(r=>r.id===metadata?.cdpTargetId);
      if(!row)throw fail('Profile target unavailable','TARGET_LOST');
      if(row.url!==metadata.bootstrapUrl&&!metadata.profileFingerprint)throw fail('Fresh initial target is required for profile bootstrap','EXTENSION_PROFILE_UNPROVEN');
      this.actionSessions.get(target.id)?.verifyUrl(row.url);
      const applied=await this.deps.applyProfile({target:{...target,bootstrapUrl:metadata.bootstrapUrl},config:this.config,signal,guard,
        rebind:row.url===metadata.bootstrapUrl?null:{cdpTargetId:metadata.cdpTargetId,expectedUrl:row.url},bootstrapOrigin:this.bridge.url,
        onLost:()=>{if(guard()){this.profileContinuityUnverified=true;this.profileGapCount++;}}});
      if(!guard()){applied.release();throw fail('Profile owner changed','TARGET_NOT_OWNED');}
      this.profileLeases.get(target.id)?.release();this.profileLeases.set(target.id,applied);this.bootstrapCdpCommands+=applied.cdpCommands;
      metadata.profileFingerprint=extensionProfileFingerprint(this.config);await this.#save();
    }
    let action;try{action=await this.#actionSession(target,signal);}catch(error){if(!guard()){this.profileLeases.get(target.id)?.release();this.profileLeases.delete(target.id);}throw error;}await action.install({signal,timeoutMs:5000});
    if(metadata.pendingUrl){await action.run('navigate',{url:metadata.pendingUrl},{signal,timeoutMs:5000});metadata.pendingUrl=null;await this.#save();}
    return true;
  }
  async #restoreLostControllerTarget(id,{signal}={}){try{return await this.#registerObserver({id},signal);}catch{return false;}}
  async connectTarget(target){
    if(!this.isOwnedTarget(target?.id)||this.targetClosing(target.id))throw fail('Target is not available for this owner','TARGET_NOT_OWNED');
    const abort=new AbortController(),manager=this;
    const sessions=this.sessions.get(target.id)||new Set();sessions.add(abort);this.sessions.set(target.id,sessions);
    return{transport:'extension',close(){abort.abort();sessions.delete(abort);if(!sessions.size&&manager.sessions.get(target.id)===sessions)manager.sessions.delete(target.id);},async run(operation,payload={},options={}){
      if(abort.signal.aborted)throw Object.assign(fail('Extension session closed','ABORT_ERR'),{name:'AbortError'});
      manager.lastActivityAt=Date.now();
      if(!manager.isOwnedTarget(target.id)||manager.targetClosing(target.id))throw fail('Target ownership changed','TARGET_NOT_OWNED');
      const budget=Math.max(1,Math.round(Number(options.timeoutMs)||20000)),deadline=Date.now()+budget;
      const signal=AbortSignal.any([abort.signal,...(options.signal?[options.signal]:[]),AbortSignal.timeout(budget)]);
      if(operation==='initialize'||(!['snapshot','progress'].includes(operation)&&manager.config.browserClientProfile==='android-webview'&&!manager.profileLeases.get(target.id)?.isAlive())){
        const existing=manager.profileInitializations.get(target.id);
        if(existing&&existing.owner!==abort.signal)throw fail('Previous profile initialization is still settling','PROFILE_INITIALIZATION_BUSY');
        if(!existing){const work=manager.#initializeTarget(target,operation==='initialize'?payload:{clientProfile:manager.config.browserClientProfile},signal);const entry={owner:abort.signal,work};manager.profileInitializations.set(target.id,entry);work.finally(()=>{if(manager.profileInitializations.get(target.id)===entry)manager.profileInitializations.delete(target.id);}).catch(()=>{});}
        const work=manager.profileInitializations.get(target.id).work;
        let onAbort;try{await Promise.race([work,new Promise((_,reject)=>{onAbort=()=>reject(signal.reason||Error('Profile operation aborted'));if(signal.aborted)onAbort();else signal.addEventListener('abort',onAbort,{once:true});})]);}
        finally{if(onAbort)signal.removeEventListener('abort',onAbort);}
        if(operation==='initialize')return true;
      }
      if(!['snapshot','progress'].includes(operation)){
        const action=await manager.#actionSession(target,signal);
        return action.run(operation,payload,{...options,timeoutMs:Math.max(1,deadline-Date.now()),signal});
      }
      await manager.#registerObserver(target,signal);
      return manager.bridge.request(operation,{targetId:target.id},{...options,timeoutMs:Math.max(1,deadline-Date.now()),signal});
    }};
  }

  async #closeTarget(id,{guard,caller=false,ownership}={}){
    const sameOwner=()=>!ownership||(this.journal===ownership.journal&&this.journal?.targetMetadata?.[id]===ownership.metadata);
    const authority=target=>this.#allowed(guard,target)&&(caller||this.#allowed(this.guards.canCloseTarget,id,target));
    const allowed=target=>sameOwner()&&authority(target);
    const alreadyRemoved=()=>this.journal===ownership?.journal&&this.targetRemovalGeneration>ownership.removalGeneration&&!this.owned.has(id)&&this.journal.targetMetadata[id]===undefined;
    const skip=reason=>({closed:false,absent:false,reason});
    if(!this.isOwnedTarget(id))return skip('unowned_target');if(!allowed())return skip('guard_rejected');
    const targets=await this.listExistingTargets();if(alreadyRemoved()){await this.saveChain;return authority()?{absent:true,reason:'already_removed'}:skip('guard_rejected');}if(!targets)return skip('targets_unavailable');
    const target=targets.find(t=>t.id===id);if(!allowed(target))return skip('guard_rejected');
    if(!target){await this.discardOwnedTarget(id,{guard:()=>allowed(),remote:false});return{absent:true,reason:'already_absent'};}
    this.closing.add(id);
    try{const out=await this.deps.browserAction({browserKey:this.journal.process.browserKey,port:Number(this.config.browserPort),method:'Target.closeTarget',params:{targetId:this.journal.targetMetadata[id].cdpTargetId},expectedUrl:target.url,guard:fresh=>allowed(fresh?{...target,url:fresh.url}:target)&&this.#processOwned()});const result={closed:out?.success===true};
      if(result?.closed||result?.absent){
        if(!sameOwner()&&!alreadyRemoved())return skip('guard_rejected');
        if(this.owned.has(id))await this.discardOwnedTarget(id,{guard:()=>allowed(),remote:false});else await this.saveChain;
      }
      return result;
    }catch{return skip('close_unconfirmed');}
  }
  #closeSerial(id,options){
    const ownership={journal:this.journal,metadata:this.journal?.targetMetadata?.[id],removalGeneration:this.targetRemovalGeneration};
    const prior=this.targetClosures.get(id)||Promise.resolve();
    const work=prior.catch(()=>{}).then(()=>this.#closeTarget(id,{...options,ownership}));this.targetClosures.set(id,work);
    work.finally(()=>{if(this.targetClosures.get(id)===work)this.targetClosures.delete(id);}).catch(()=>{});return work;
  }
  closeOwnedTarget(id,options={}){return this.#closeSerial(id,options);}
  async discardOwnedTarget(id,{guard,remote=true}={}) {
    const journal=this.journal;
    if(!journal||!this.isOwnedTarget(id)||!this.#allowed(guard))return {localDiscarded:false,remoteCachePurged:false,reason:'ownership_guard_rejected'};
    const action=this.actionSessions.get(id);this.actionSessions.delete(id);
    // Revoke local authority before independent best-effort remote cache disposal.
    this.owned.delete(id);this.closing.delete(id);this.pendingAcquisitions.delete(id);this.targetRemovalGeneration++;
    delete journal.targetMetadata[id];this.profileLeases.get(id)?.release();this.profileLeases.delete(id);
    for(const session of this.sessions.get(id)||[])session.abort();this.sessions.delete(id);this.profileInitializations.delete(id);
    await this.#save();
    let actionCachePurged=!action;
    if(action)try{actionCachePurged=await action.dispose({timeoutMs:1500})===true;}catch{action.close();}
    let remoteCachePurged=false;
    if(remote&&this.journal===journal&&!this.owned.has(id)&&this.#allowed(guard))try{
      const result=await this.bridge?.request('discardTarget',{targetId:id},{timeoutMs:2500});remoteCachePurged=result?.discarded===true&&result?.pageCachePurged===true;
    }catch{}
    return {localDiscarded:true,remoteCachePurged:remoteCachePurged&&actionCachePurged,actionCachePurged};
  }
  async closeTarget(id,{guard}={}){const r=await this.#closeSerial(id,{guard,caller:typeof guard==='function'});return r.closed===true||r.absent===true;}
  async #finishReleasedBrowser(allowed){
    const current=this.journal,proof=current?.process,retiring=current?.retiringProcess;
    const candidate=()=>clearedExitedExtensionJournal({...this.journal,targets:[...this.owned]},this.deps,()=>allowed()&&this.#emptyLocal());
    const valid=()=>this.journal===current&&this.journal?.process===proof&&this.journal?.retiringProcess===retiring&&!!candidate();
    const next=candidate();if(!next||!valid())return false;
    if(current.controllerStopPending===false&&current.retiringProcess===null)return true;
    const write=this.#persistJournal(next,valid);
    if(!await write)return false;
    current.controllerStopPending=false;current.retiringProcess=null;return true;
  }
  // No admitted SelfRun work means no SelfRun browser, including untracked or
  // legacy-session tabs. Retire only roots with exact per-process proof; never
  // signal unrelated Chromium or a PID whose identity cannot be established.
  releaseIfUnused({guard}={}){return this.#serial(async()=>{
    const allowed=()=>!this.stopped&&this.#allowed(guard)&&this.#allowed(this.guards.canRelease);
    if(!allowed())return{released:false,reason:'guard_rejected'};
    if(!this.journal)await this.#initialize();if(!allowed())return{released:false,reason:'guard_rejected'};
    if(this.profileInitializations.size||this.bridge?.pendingCount)return{released:false,reason:'browser_operation_pending'};
    if(this.journal.process&&!this.#processOwned()&&!this.#processExited())return{released:false,reason:'ownership_unproven'};
    const roots=this.#ownedRoots();if(!roots)return{released:false,reason:'process_inventory_unavailable'};
    for(const proof of roots){
      if(!allowed())return{released:false,reason:'guard_rejected'};
      if(!extensionProcessMatches(proof,this.deps.processIdentity(proof.pid),this.deps.bootId()))
        return{released:false,reason:'ownership_changed'};
      try{this.deps.signalProcess(proof.pid);}catch(error){if(error.code!=='ESRCH')return{released:false,reason:'browser_signal_failed'};}
    }
    const deadline=Date.now()+5000;
    for(let n=0;n<100&&Date.now()<deadline&&roots.some(p=>!extensionProcessExited(p,this.deps));n++){
      if(!allowed())return{released:false,reason:'guard_rejected'};await this.deps.delay(50);
    }
    if(roots.some(p=>!extensionProcessExited(p,this.deps)))return{released:false,reason:'browser_exit_unconfirmed'};
    const released=await this.#clearIdleBrowser(allowed);
    return{released,reason:released?'idle_browser_retired':'idle_release_proof_changed'};
  });}

  async probe(){await this.ensureStarted();const targets=await this.listExistingTargets();return{ready:true,adapter:'extension',browser:'Chromium extension',protocolVersion:null,targets:targets?.length??null};}
  async close(){this.stopped=true;for(const action of this.actionSessions.values())action.close();this.actionSessions.clear();for(const sessions of this.sessions.values())for(const session of sessions)session.abort();this.sessions.clear();for(const lease of this.profileLeases.values())lease.release();this.profileLeases.clear();await this.bridge?.close();}
}
