import { appendTurnStartDirective, humanizeSelfRunPrompt } from './prompt-directives.mjs';
import { LifecycleStore } from './lifecycle-store.mjs';
import { canonicalUrl, classifyAttach, digest, initialRecord, keyFor, matchingTaskControl, newIntent, observingTaskControl, runningTaskControl } from './lifecycle.mjs';
import { readUserIntervention } from './result-reconciler.mjs';
import { retiredState } from './retired-requests.mjs';
import { TerminalBrowserCleanup } from './terminal-browser-cleanup.mjs';
import { observedConversationUrl, preSubmitFailureStage } from './browser/chatgpt.mjs';

const controlStates=new Set(['RUNNING','PAUSED','WAITING_USER_INTERVENTION','STOPPED','DONE','RESUME_REQUESTED','RESUME_STOPPED_REQUESTED']);
const dispatchRequested=r=>['CREATE_REQUESTED','SEND_REQUESTED'].includes(r?.body?.client_status);
const terminal = r => !r||r.retired||['COMMITTED','COMPLETED','CANCELLED','STOPPED'].includes(r.state);
const connectionLost=error=>classifyAttach(error)==='BROWSER_DEAD'||['TARGET_LOST','TARGET_CLOSED'].includes(error?.code)
  ||/target (?:closed|lost|not found)|session closed/i.test(String(error?.message||error));
const errorInfo = (error,code) => ({code:code||error?.code||'BROWSER_ERROR',message:String(error?.message||error).slice(0,300)});

export class DriveDispatchController {
  constructor({browser,stateStore,config,promptDirectives=null,canDispatch=()=>true,executionClaims=null}) {
    this.canDispatch=canDispatch;this.browser=browser;this.stateStore=stateStore;this.config=config;this.promptDirectives=promptDirectives;
    this.executionClaims=executionClaims;
    this.repository=new LifecycleStore(stateStore,{onTerminal:records=>this.#discardRuntime(records),
      canPublish:key=>!this.executionClaims||this.#observationAvailable(key)});
    this.sessions=new Map();this.operations=new Map();this.standby=false;
    this.browserOpenings=new Map();this.admissions=new Set();this.terminalDiscardListeners=new Set();
    this.claimReleases=new Map();this.taskClosures=new Map();
    this.serverGeneration=Date.now();
    this.browserCleanup=new TerminalBrowserCleanup({repository:this.repository,chromium:browser.chromium,stateStore,
      available:()=>!!this.executionClaims||!this.standby&&this.canDispatch(),idleReleaseAllowed:()=>{
        // Retain a reusable authenticated Chromium for the configured one-hour
        // inactivity window instead of retiring it after every idle tick.
        const idleMs=Math.max(0,Number(config.browserTargetIdleMs)||0);
        const last=Number(browser.chromium?.lastActivityAt);
        return !(idleMs>0&&Number.isFinite(last)&&Date.now()-last<idleMs);
      },busy:()=>this.sessions.size>0||this.operations.size>0});
  }
  async initialize() {
    await this.repository.initialize((await this.#directives()).turnContinueDirective||this.config.recoveryPrompt);
  }
  async cleanupTerminalResources(options) {const result=await this.browserCleanup.run(options);await this.repository.forgetTerminalHistory();return result;}
  get active() {return this.activeDispatches().at(-1)||null;}
  getActive(path) {return this.activeDispatches().find(a=>a.path===path)||null;}
  activeDispatches() {
    return this.repository.records().filter(r=>!terminal(r)&&['RUNNING','WAITING_USER_INTERVENTION'].includes(r.control_state)&&observingTaskControl(this.repository.control(r.task_id),r)).map(r=>({
      path:r.path,body:{...r.body,conversation_url:r.conversation_url},generation:r.run_generation,
      publishPending:false,session:this.sessions.get(r.key)?.session,target:this.sessions.get(r.key)?.target,
    }));
  }
  retiredPath(path) {return Object.values(this.repository.data.retired).some(r=>r.path===path);}
  controlWatermarkForTask(taskId) {return this.repository.controlWatermark(taskId);}
  controlForTask(taskId) {return this.repository.control(taskId);}
  async flush(transport) {this.repository.transport=transport;await this.repository.flush(transport,{awaitTerminal:true});}
  onTerminalDiscard(listener) {this.terminalDiscardListeners.add(listener);return ()=>this.terminalDiscardListeners.delete(listener);}
  hasCurrentExecutions() {return this.repository.records().some(r=>!terminal(r))||this.admissions.size>0;}
  #executionAvailable(key) {return this.executionClaims?this.executionClaims.localOwns(key):!this.standby&&this.canDispatch();}
  #observationAvailable(key) {
    if(this.#executionAvailable(key))return true;
    const record=this.repository.get(key);
    // The durable current request-to-target mapping is read-only authority.
    // It cannot create a tab or input, and does not depend on remote claim I/O.
    return !!this.executionClaims&&!terminal(record)&&!!record.target_id
      &&observingTaskControl(this.repository.control(record.task_id),record);
  }
  #admissionCurrent(admission) {
    return this.admissions.has(admission)&&(!this.executionClaims||admission.claim
      &&this.executionClaims.localOwns(admission.key,admission.claim.token)
      &&(this.canDispatch()||!!this.repository.get(admission.key)||!admission.claim.created));
  }
  async #claimAdmission(admission,control,transport) {
    if(!this.executionClaims)return this.admissions.has(admission);
    if(!control||!['RUNNING','WAITING_USER_INTERVENTION'].includes(control.state)||!this.admissions.has(admission))return false;
    // A cancelled attempt releases its claim before the app's next attempt.
    await this.claimReleases.get(admission.key);
    admission.claim=await this.executionClaims.acquire(control,{createAllowed:this.canDispatch()});
    if(!admission.claim||!this.#admissionCurrent(admission))return false;
    // An existing claim with missing local effect history is not proof that an
    // initial send never happened. Only a current URL may restore observation.
    if(!admission.claim.created&&!this.repository.get(admission.key)&&!canonicalUrl(control.conversation_url))return false;
    // A STOP or successor may arrive while the CAS response is delayed.
    const latest=await transport.read('__SELFRUN_CONTROL__'+control.task_id+'.json');
    if(!latest||latest.schema!=='selfrun-task-control-v1'||keyFor(latest)!==admission.key
      ||latest.control_epoch!==control.control_epoch||latest.state!==control.state)return false;
    return this.#admissionCurrent(admission);
  }
  async #finishAdmission(admission) {
    this.admissions.delete(admission);
    if(admission.claim?.created&&!this.repository.get(admission.key)
      &&![...this.admissions].some(a=>a.key===admission.key)) {
      try {await this.executionClaims.release(admission.key,admission.claim.token);}
      catch(error){console.error(JSON.stringify({event:'EXECUTION_CLAIM_RELEASE_FAILED',error:String(error?.message||error).slice(0,200)}));}
    }
  }
  #discardRuntime(records) {
    for(const r of records) {
      for(const admission of this.admissions)if(admission.key===r.key)this.admissions.delete(admission);
      const claim=this.executionClaims?.current(r.key);
      const control=this.repository.control(r.task_id);
      const ended=['STOPPED','DONE','COMMITTED','COMPLETED','CANCELLED'].includes(r.state)
        ||control&&control.control_epoch>=r.control_epoch&&keyFor(control)!==r.key;
      if(claim&&ended) {
        const release=this.executionClaims.release(r.key,claim.token).catch(error=>console.error(JSON.stringify({
          event:'EXECUTION_CLAIM_RELEASE_FAILED',error:String(error?.message||error).slice(0,200)})));
        this.claimReleases.set(r.key,release);
        void release.finally(()=>{if(this.claimReleases.get(r.key)===release)this.claimReleases.delete(r.key);});
      } else if(claim)this.executionClaims.forget(r.key,claim.token);
      const pending=new Set([this.sessions.get(r.key),...this.browserOpenings.values()].filter(a=>a&&(a.key===r.key||a===this.sessions.get(r.key))));
      for(const a of pending) {
        a.terminalResource={key:r.key,task_id:r.task_id,turn_id:r.turn_id,request_id:r.request_id,
        control_epoch:r.control_epoch,conversation_digest:r.conversation_digest||(r.conversation_url?digest(r.conversation_url):null),terminal_state:r.state};
        a.discardedTargets=new Set(this.repository.cleanupEntries().filter(e=>e.key===r.key).map(e=>e.target_id));
        a.abort.abort(new Error('terminal execution discarded'));
        // Keep opening barriers until late-created resources have been disposed.
      }
      this.#detach(r.key);
      this.operations.delete(r.key);
    }
    for(const listener of this.terminalDiscardListeners)listener(records);
  }
  async #discardedTarget(pending,targetId) {
    if(!pending.terminalResource||!this.browser.chromium?.isOwnedTarget?.(targetId))return false;
    pending.discardedTargets??=new Set();
    if(pending.discardedTargets.has(targetId))return true;
    pending.discardedTargets.add(targetId);
    if(!this.repository.resourceCleanups.has(targetId))this.repository.resourceCleanups.set(targetId,
      {...pending.terminalResource,target_id:targetId});
    void this.cleanupTerminalResources();return true;
  }
  #serial(key,fn) {
    const token=this.repository.token(key);
    if(!token)return Promise.resolve();
    const execute=()=>this.repository.matches(key,token)?fn():undefined;
    const prior=this.operations.get(key)||Promise.resolve();
    const p=prior.then(execute,execute);
    this.operations.set(key,p);
    return p.finally(()=>{
      if(this.operations.get(key)===p)this.operations.delete(key);
      if(this.repository.get(key)?.retired)void this.cleanupTerminalResources();
    });
  }
  #scheduleAdvance(key,transport) {
    if(this.operations.has(key))return false;
    void this.#serial(key,()=>this.#advance(key,transport)).catch(error=>{
      console.error(JSON.stringify({event:'DRIVE_DISPATCH_ADVANCE_ERROR',key,error:String(error?.message||error).slice(0,300)}));
    });
    return true;
  }
  async #move(key,state,reason,patch={},token=null) {
    return this.repository.move(key,state,reason,patch,token);
  }
  async #invalidateControl(identity) {
    const key=keyFor(identity);
    // An uncertain control read suspends effects, not read-only observation.
    const active=this.sessions.get(key);
    if(active)active.inputAuthorized=false;
  }
  async #readCurrentControl(identity,transport) {
    const token=this.repository.token(keyFor(identity));
    try {
      const c=await transport.read('__SELFRUN_CONTROL__'+identity.task_id+'.json');
      if(token&&!this.repository.matches(keyFor(identity),token))return null;
      if(c) {
        if(c.task_id!==identity.task_id||!controlStates.has(c.state))throw new Error('invalid task control');
        await this.controlDurable(null,c,transport);
        const current=this.repository.control(identity.task_id);
        if(matchingTaskControl(c,identity)&&matchingTaskControl(current,identity)
          &&current.control_epoch===c.control_epoch&&current.state===c.state)return current;
        if(matchingTaskControl(c,identity)&&!this.repository.get(keyFor(identity))
          &&(!current||c.control_epoch>current.control_epoch))return c;
      }
      await this.#invalidateControl(identity);return null;
    } catch(error) {
      if(token&&!this.repository.matches(keyFor(identity),token))return null;
      if(error?.code==='DRIVE_FILE_NOT_FOUND'){if(await this.repository.removeMissingRequest(identity))this.#detach(keyFor(identity));return null;}
      await this.#invalidateControl(identity);
      throw error;
    }
  }
  async #fresh(key,transport) {
    const r=this.repository.get(key);
    if(!r)return false;
    if(!this.executionClaims&&(this.standby||!this.canDispatch()))return false;
    const token=this.repository.token(key);
    const c=await this.#readCurrentControl(r,transport),latest=this.repository.get(key);
    if(!this.repository.matches(key,token))return false;
    const allowed=observingTaskControl(c,latest)&&!terminal(latest);
    if(allowed&&this.executionClaims) {
      try {
        const ownership=await this.executionClaims.verify(latest);
        if(!this.repository.matches(key,token)) {
          const stale=this.executionClaims.current(key);
          if(stale&&!this.repository.get(key))await this.executionClaims.release(key,stale.token).catch(error=>console.error(JSON.stringify({
            event:'EXECUTION_CLAIM_RELEASE_FAILED',error:String(error?.message||error).slice(0,200)})));
          return false;
        }
        if(ownership!=='OWNED') {
          await this.#invalidateControl(latest);
          if(ownership==='FOREIGN') {
            this.#detach(key);
            if(await this.repository.removeMissingRequest(latest))this.#discardRuntime([latest]);
          } else if(ownership==='REPLACED')this.#detach(key);
          return false;
        }
        if(this.executionClaims.current(key).control_epoch<c.control_epoch) {
          await this.#applyControl(c,transport);
          if(!this.repository.matches(key,token))return false;
        }
      }
      catch(error){await this.#invalidateControl(latest);throw error;}
    }
    if(allowed){const active=this.sessions.get(key);if(active)active.inputAuthorized=true;}
    else if(c)this.#detach(key);
    return allowed;
  }
  async #applyControl(c,transport) {
    if(transport)this.repository.transport=transport;
    if(!c||!controlStates.has(c.state)||this.repository.controlQuarantined(c))return false;
    if(c.schema!=='selfrun-task-control-v1'||!c.task_id||!Number.isSafeInteger(c.control_epoch)||c.control_epoch<1)
      throw new Error('invalid task control');
    const old=this.repository.control(c.task_id);
    if(old&&c.control_epoch<old.control_epoch)return false;
    if(old&&c.control_epoch===old.control_epoch&&(c.state!==old.state||keyFor(c)!==keyFor(old)))throw new Error('control epoch conflict');
    for(const admission of this.admissions)if(admission.task_id===c.task_id&&admission.control) {
      if(c.control_epoch<admission.control.control_epoch)return false;
      if(c.control_epoch===admission.control.control_epoch&&(c.state!==admission.control.state||keyFor(c)!==admission.key))throw new Error('control epoch conflict');
    }
    for(const [key,current]of this.executionClaims?.local||[])if(JSON.parse(key)[0]===c.task_id) {
      if(c.control_epoch<current.control_epoch)return false;
      if(c.control_epoch===current.control_epoch&&(['STOPPED','DONE'].includes(c.state)||keyFor(c)!==key))throw new Error('control epoch conflict');
    }
    if(['STOPPED','DONE'].includes(c.state)&&(!old||c.control_epoch>=old.control_epoch)) {
      for(const a of new Set([...this.sessions.values(),...this.browserOpenings.values()]))if(a.taskId===c.task_id||this.repository.get(a.key)?.task_id===c.task_id) {
        a.abort.abort(new Error('app '+c.state));a.session?.close();
      }
    }
    const claim=this.executionClaims?.current(keyFor(c));
    if(claim&&claim.control_epoch<c.control_epoch&&['RUNNING','WAITING_USER_INTERVENTION'].includes(c.state)) {
      await this.#invalidateControl(c);
      const refresh={task_id:c.task_id,key:keyFor(c),control:{...c}};this.admissions.add(refresh);
      try {
        const updated=await this.executionClaims.acquire(c,{createAllowed:false});
        if(!this.admissions.has(refresh)) {
          if(updated)await this.executionClaims.release(refresh.key,updated.token).catch(error=>console.error(JSON.stringify({
            event:'EXECUTION_CLAIM_RELEASE_FAILED',error:String(error?.message||error).slice(0,200)})));
          return false;
        }
      }
      catch(error){await this.#invalidateControl(c);throw error;}
      finally {this.admissions.delete(refresh);}
    }
    for(const admission of this.admissions)if(admission.task_id===c.task_id) {
      if(!admission.control)admission.control={...c};
      else if(c.control_epoch>admission.control.control_epoch
        ||c.control_epoch===admission.control.control_epoch&&(c.state!==admission.control.state||keyFor(c)!==keyFor(admission.control)))this.admissions.delete(admission);
      if(['STOPPED','DONE'].includes(c.state)&&c.control_epoch>=Number(admission.control?.control_epoch||0))this.admissions.delete(admission);
    }
    if(this.executionClaims&&['STOPPED','DONE'].includes(c.state)) {
      for(const [key,claim]of this.executionClaims.local)if(JSON.parse(key)[0]===c.task_id&&claim.control_epoch<=c.control_epoch)
        this.executionClaims.forget(key,claim.token);
      void this.executionClaims.releaseControl(c).catch(error=>console.error(JSON.stringify({
        event:'EXECUTION_CLAIM_RELEASE_FAILED',error:String(error?.message||error).slice(0,200)})));
    }
    this.browserCleanup.observeControl(c);
    if(!this.repository.records().some(r=>r.task_id===c.task_id)) {
      // A terminal control must serialize after an admission already persisting,
      // even while that request is not visible in the committed snapshot yet.
      if(['STOPPED','DONE'].includes(c.state)) {
        const changed=await this.repository.applyControl(c);
        if(c.state==='STOPPED')await transport?.purgeStopped?.(c);
        for(const listener of this.terminalDiscardListeners)listener([{task_id:c.task_id,turn_id:c.turn_id,request_id:c.request_id,key:keyFor(c),control_epoch:c.control_epoch,state:'STOPPED'}]);
        void this.cleanupTerminalResources({force:true});return changed;
      }
      if(Object.values(this.repository.data.retired).some(r=>r.task_id===c.task_id))await this.repository.applyControl(c);
      return !!await this.#restoreCurrentControl(c,transport);
    }
    if(old&&c.control_epoch===old.control_epoch) {
      await this.repository.applyControl(c);
      if(c.state==='STOPPED')await transport?.purgeStopped?.(c);
      if(['STOPPED','DONE'].includes(c.state))void this.cleanupTerminalResources({force:true});
      else await this.#restoreCurrentControl(old,transport);
      return false;
    }
    const sameRunningIdentity=old&&['RUNNING','WAITING_USER_INTERVENTION'].includes(old.state)&&['RUNNING','WAITING_USER_INTERVENTION'].includes(c.state)
      &&old.turn_id===c.turn_id&&old.request_id===c.request_id;
    // Same-request RUNNING refreshes update metadata only; they must not abort an in-flight browser operation.
    const stopping=[];
    if(!sameRunningIdentity) {
      for(const r of this.repository.records()) if(r.task_id===c.task_id&&!['COMMITTED','COMPLETED'].includes(r.state)) {
        const a=this.sessions.get(r.key);
        if(['STOPPED','DONE'].includes(c.state))a?.abort.abort(new Error('app '+c.state));
        if(c.state==='STOPPED'&&r.request_id===c.request_id&&r.turn_id===c.turn_id) {
          a?.abort.abort(new Error('app STOP'));
          stopping.push({r,a});
        } else if(!['STOPPED','DONE'].includes(c.state))this.#detach(r.key);
      }
    }
    await this.repository.applyControl(c);
    for(const item of stopping)await this.#disposeStoppedAttachment(item.r,item.a,c);
    if(c.state==='STOPPED')await transport?.purgeStopped?.(c);
    await this.#restoreCurrentControl(c,transport);
    if(['STOPPED','DONE'].includes(c.state)||c.state==='RUNNING'&&!sameRunningIdentity)
      void this.cleanupTerminalResources({force:true});
    return true;
  }
  async #restoreCurrentControl(c,transport,body=null,dispatchPath=null) {
    const admission={task_id:c.task_id,key:keyFor(c),control:{...c}};this.admissions.add(admission);
    try{return await this.#restoreAdmittedControl(c,transport,body,dispatchPath,admission);}
    finally{await this.#finishAdmission(admission);}
  }
  async #restoreAdmittedControl(c,transport,body,dispatchPath,admission) {
    if(!transport||c.state!=='RUNNING'||!canonicalUrl(c.conversation_url)||this.repository.records().some(r=>keyFor(r)===keyFor(c)))return null;

    if(!body) {
      if(dispatchPath)body=await transport.read(dispatchPath);
      else for(const row of await transport.list())if(typeof transport.local==='function'?row.path==='__SELFRUN_DISPATCH__HTTP_'+digest(c.request_id)+'.json':row.path.startsWith('__SELFRUN_DISPATCH__'+c.request_id.replace(/[^A-Za-z0-9._:-]/g,'_').slice(0,160)+'__A')) {
        const candidate=await transport.read(row.path);
        if(candidate?.schema==='selfrun-server-dispatch-v1'&&keyFor(candidate)===keyFor(c)){body=candidate;dispatchPath=row.path;break;}
      }
    }
    if(!body||keyFor(body)!==keyFor(c))return null;
    const documentEpoch=Number(body.server_control_epoch||body.lifecycle_record?.control_epoch||0);
    if(retiredState(initialRecord(dispatchPath,body))&&c.control_epoch<=documentEpoch)return null;
    if(['CANCELLED','SUPERSEDED'].includes(body.client_status)&&(!documentEpoch||c.control_epoch<=documentEpoch)) {
      await this.repository.removeCurrentTerminalControl(c);return null;
    }

    // Current app URL is an explicit watch command. Old server fields cannot
    // turn it into prompt submission or require a retained terminal marker.
    const clean=Object.fromEntries(Object.entries(body).filter(([k])=>!['lifecycle_record','lifecycle_state','server_status','conversation_url','server_control_epoch'].includes(k)));
    Object.assign(clean,{client_status:'SEND_REQUESTED',conversation_url:c.conversation_url,server_status:'STARTED',server_control_epoch:c.control_epoch});
    const latest=await transport.read('__SELFRUN_CONTROL__'+c.task_id+'.json');
    if(!latest||latest.schema!=='selfrun-task-control-v1'||keyFor(latest)!==keyFor(c)||latest.state!=='RUNNING'
      ||latest.control_epoch!==c.control_epoch||canonicalUrl(latest.conversation_url)!==canonicalUrl(c.conversation_url))return null;
    if(!await this.#claimAdmission(admission,latest,transport))return null;
    const restored=await this.repository.ingest(dispatchPath,clean,latest,{resumeFrom:body,admissionGuard:()=>this.#admissionCurrent(admission)});
    return this.admissions.has(admission)?restored:null;
  }
  async #disposeStoppedAttachment(record,attached,control) {
    // APP STOP is local teardown only. Never read receipts or click remote generation controls.
    attached?.session?.close();
    if(attached?.target?.id&&this.browser.chromium?.isOwnedTarget?.(attached.target.id))
      await this.repository.queueTargetCleanup(record.key,attached.target.id);
    await this.repository.recordStopOutcome(record.key,control.control_epoch,'NOT_REQUESTED');
    void this.cleanupTerminalResources();
  }
  async control(_path,c,transport) {
    const changed=await this.#applyControl(c,transport);
    if(changed&&transport) await this.repository.flush(transport,{awaitTerminal:{key:keyFor(c),control_epoch:c.control_epoch}});
    return changed;
  }
  async controlDurable(_path,c,transport) {
    return this.#applyControl(c,transport);
  }
  #detach(key) {
    const a=this.sessions.get(key);
    if(!a)return;
    a.abort.abort(new Error('lifecycle ownership changed'));
    a.session?.close();
    delete a.reconciling;
    this.sessions.delete(key);
    // Soft detach preserves targets for pause, standby and connection recovery.
  }
  async quiesceForStandby() {
    if(this.executionClaims) {
      this.standby=false;
      for(const key of this.sessions.keys())if(!this.#observationAvailable(key))this.#detach(key);
      return;
    }
    this.standby=true;
    for(const key of this.sessions.keys())this.#detach(key);
  }
  async #directives() {
    return this.promptDirectives?.current?this.promptDirectives.current():
      {turnStartDirective:this.config.turnStartDirective,turnContinueDirective:this.config.recoveryPrompt};
  }
  async #ingestRecord(path,body,transport) {
    const admission={task_id:body?.task_id,key:keyFor(body||{}),control:null};this.admissions.add(admission);
    try{return await this.#ingestAdmittedRecord(path,body,transport,admission);}
    finally{await this.#finishAdmission(admission);}
  }
  async #ingestAdmittedRecord(path,body,transport,admission) {
    this.repository.transport=transport;
    await this.repository.initialize((await this.#directives()).turnContinueDirective||this.config.recoveryPrompt);
    if(body?.schema!=='selfrun-server-dispatch-v1')throw new Error('invalid dispatch schema');
    // The current document, rather than a remembered terminal identity or a
    // delayed caller snapshot, is the input for this intake transaction.
    let current;
    try{current=await transport.read(path);}catch(error){
      if(error?.code!=='DRIVE_FILE_NOT_FOUND')throw error;
      const prior=this.repository.get(keyFor(body));
      if(prior&&!prior.retired&&await this.repository.removeMissingRequest(prior))this.#detach(prior.key);
      return undefined;
    }
    if(!current||current.schema!=='selfrun-server-dispatch-v1'||keyFor(current)!==keyFor(body))return null;
    body=current;

    if(!Array.isArray(body.profile_operations)||!body.prompt||!body.project_url)throw new Error('invalid dispatch payload');
    // Inert terminal history needs no remote read. Every executable import or
    // explicit retry must still obtain current app authorization.
    const prior=this.repository.get(keyFor(body)),imported=prior||initialRecord(path,body);
    if(!prior&&['CANCELLED','SUPERSEDED'].includes(body.client_status))return undefined;
    if(!prior&&retiredState(imported)) {
      const current=await this.#readCurrentControl(imported,transport);
      // Old final projections do not become local jobs. An explicit current
      // app URL may have already attached its matching dispatch in control().
      if(current?.state==='RUNNING'&&canonicalUrl(current.conversation_url))return this.repository.get(keyFor(body))||undefined;
      return undefined;
    }
    const currentControl=this.repository.control(body.task_id);
    const retry=(imported.state==='CANCELLED'&&['CREATE_REQUESTED','SEND_REQUESTED'].includes(body.client_status)
      &&body.dispatch_attempt>imported.dispatch_attempt)
      ||(imported.never_submitted&&body.dispatch_attempt>imported.dispatch_attempt);
    const resume=currentControl?.state==='RUNNING'&&currentControl.control_epoch>imported.control_epoch
      &&canonicalUrl(currentControl.conversation_url)&&!['COMMITTED','COMPLETED'].includes(imported.state);
    let authorized=currentControl;
    if(!retiredState(imported)||retry||resume) {
      authorized=await this.#readCurrentControl(imported,transport);
      if(!authorized)return null;
    }
    if(!prior&&['STOPPED','DONE'].includes(authorized?.state))return undefined;
    if(!await this.#claimAdmission(admission,authorized,transport))return null;
    let record=await this.repository.ingest(path,body,authorized,{admissionGuard:()=>this.#admissionCurrent(admission)});
    if(!record||!this.admissions.has(admission))return null;
    const control=this.repository.control(record.task_id);
    if(record.retired&&control?.state==='RUNNING'&&control.control_epoch>record.control_epoch&&canonicalUrl(control.conversation_url))
      record=await this.#restoreCurrentControl(control,transport,body,path)||record;
    if(record.retired||['CANCELLED','SUPERSEDED'].includes(record.body?.client_status)) {
      this.#detach(record.key);void this.cleanupTerminalResources();
    }
    return record;
  }
  async ingest(path,body,transport) {
    const r=await this.#ingestRecord(path,body,transport);
    if(!r)return r;
    if(r.retired){await this.browserCleanup.run({force:true});await this.repository.flush(transport);return r;}
    return this.#serial(r.key,()=>this.#advance(r.key,transport));
  }
  async ingestDurable(path,body,transport) {
    const r=await this.#ingestRecord(path,body,transport);
    if(r&&!r.retired)this.#scheduleAdvance(r.key,transport);
    else if(r?.retired)void this.browserCleanup.run({force:true}).then(()=>this.repository.flush(transport)).catch(error=>console.error(JSON.stringify({event:'TERMINAL_FINALIZATION_FAILED',error:String(error?.message||error).slice(0,300)})));
    return r;
  }
  prepare(path,body,transport) {return this.ingest(path,body,transport);}
  send(path,body,transport) {return this.ingest(path,body,transport);}
  resume(path,body,transport) {return this.ingest(path,body,transport);}
  cancel(path,body,transport) {return this.ingest(path,{...body,client_status:'CANCELLED'},transport);}
  async retryResumeFromControl(c,transport) {
    for(const r of this.repository.records())if(r.task_id===c.task_id&&r.request_id===c.request_id)await this.#serial(r.key,()=>this.#advance(r.key,transport));
  }
  async tick(transport) {
    this.repository.transport=transport;
    await this.repository.initialize((await this.#directives()).turnContinueDirective||this.config.recoveryPrompt);
    if(!this.executionClaims&&!this.canDispatch()){await this.quiesceForStandby();await this.cleanupTerminalResources({force:false});return;}
    this.standby=false;
    await transport?.purgeStopped?.();
    // The independent quiet deadline must not join a pending URL publication.
    // Reuse the existing flush/publication lanes; no retry work is queued while
    // a remote publication is already pending.
    if(!this.repository.publications.size)void this.repository.flush(transport).catch(error=>{
      if(error?.code==='DRIVE_PROJECTION_FAILED')this.#projectionFailure(error);
      else console.error(JSON.stringify({event:'LIFECYCLE_FLUSH_FAILED',code:error?.code||'FLUSH_FAILED'}));
    });
    for(const r of this.repository.records()) {
      if(this.operations.has(r.key))continue;
      await this.#serial(r.key,()=>this.#advance(r.key,transport));
    }
    await this.cleanupTerminalResources({force:false});
  }
  #nudgeDue(record) {
    return dispatchRequested(record)
      &&Date.now()-Math.max(Number(record.last_progress_at||0),Number(record.last_nudge_attempt_at||0),Number(record.last_quiet_cycle_at||0))>=600000;
  }
  async #advance(key,transport) {
    let r=this.repository.get(key);
    if(terminal(r))return;
    let authorized=false;
    try {authorized=await this.#fresh(key,transport);}
    catch(error) {
      if(!this.#observationAvailable(key))throw error;
      console.error(JSON.stringify({event:'APP_CONTROL_IO_RETRY',task_id:r.task_id}));
    }
    if(!authorized){await this.#continueObservation(key,transport);return;}
    r=this.repository.get(key);if(!r)return;
    let a=this.sessions.get(key);
    if(!a) {
      if(r.retry_at>Date.now())return;
      // Reconnect this exact in-flight request after a transport interruption.
      // A new app request always starts from prepare, regardless of turn number.
      a=await this.#open(key,transport,r.target_id?'target':r.conversation_url?'attach':'prepare');
      if(!a)return;
    }
    if(!a.guard())return;
    r=this.repository.get(key);
    if(!r.conversation_url&&this.browser.ownedConversationUrl) {
      const observed=await this.browser.ownedConversationUrl({targetId:r.target_id,projectUrl:r.body.project_url,signal:a.abort.signal,guard:a.guard});
      if(observed&&a.guard())await this.#recordUrl(key,transport,observed,a.token);
    }
    r=this.repository.get(key);if(!r||!a.guard())return;
    const provenUnsent=r.intent?.outcome==='ABSENT'&&r.intent?.pre_submit_stage;
    if(dispatchRequested(r)&&(!r.intent&&!r.conversation_url||provenUnsent)&&r.retry_at<=Date.now()) {
      const directives=await this.#directives();
      await this.#post(key,transport,provenUnsent?r.intent.kind:'initial',provenUnsent?r.intent.prompt:
        appendTurnStartDirective(humanizeSelfRunPrompt(r.body.prompt),directives.turnStartDirective));
    }
    if(!a.guard())return;
    this.#monitor(key,transport);
    r=this.repository.get(key);
    if(r.conversation_url&&observingTaskControl(this.repository.control(r.task_id),r)
      &&(this.#nudgeDue(r)||r.quiet_pending&&r.quiet_retry_at<=Date.now()))await this.#quietCycle(key,transport,a);
    this.#publishAfterSubmission(key,transport);
  }
  async #continueObservation(key,transport) {
    let r=this.repository.get(key);
    if(!r||!r.target_id||!this.#observationAvailable(key))return;
    const token=this.repository.token(key),guard=()=>this.repository.current(key,token)&&this.#observationAvailable(key);
    const address=await this.browser.ownedConversationUrl?.({targetId:r.target_id,guard});
    if(address&&guard())await this.#recordUrl(key,transport,address,token);
    r=this.repository.get(key);if(!r||!guard())return;
    let a=this.sessions.get(key);
    if(!a&&r.retry_at<=Date.now())a=await this.#open(key,transport,'target',{observationOnly:true});
    if(!a?.guard?.())return;
    const observed=await this.browser.ownedConversationUrl?.({targetId:r.target_id,signal:a.abort.signal,guard:a.guard});
    if(observed&&a.guard())await this.#recordUrl(key,transport,observed,a.token);
    if(a.guard())this.#monitor(key,transport);
  }
  async #quietCycle(key,transport,a) {
    if(a.quietRead||!a.guard())return;
    let r=this.repository.get(key);if(!r)return;
    const cycle=r.quiet_pending?r.last_quiet_cycle_at:Date.now(),progress=r.last_progress_at,progressCount=r.cursor?.responseTextLength,progressVersion=a.progressVersion||0;
    if(!await this.#move(key,'OBSERVING','QUIET_DOCUMENT_READ',{
      last_quiet_cycle_at:cycle,quiet_pending:true,quiet_retry_at:0},a.token))return;
    // Document I/O never occupies the lane used by character observations.
    const pending={};a.quietRead=pending;
    void readUserIntervention(transport,this.repository.get(key),{signal:a.abort.signal})
      .then(result=>this.#serial(key,async()=>{
        const current=this.repository.get(key);
        if(!a.guard()||!current||!current.quiet_pending||current.last_progress_at!==progress
          ||current.cursor?.responseTextLength!==progressCount||(a.progressVersion||0)!==progressVersion||current.last_quiet_cycle_at!==cycle)return;
        if(result.state==='UNKNOWN') {
          await this.#move(key,'OBSERVING','QUIET_DOCUMENT_RETRY',{quiet_pending:true,quiet_retry_at:Date.now()+30000},a.token);
          if(result.ioError)console.error(JSON.stringify({event:'CONTINUATION_DOCUMENT_IO_RETRY',task_id:current.task_id}));
          return;
        }
        await this.#move(key,'OBSERVING',result.state==='INTERVENTION'?'USER_INTERVENTION_PROMPT_SKIPPED':'QUIET_DOCUMENT_CHECKED',
          {quiet_pending:false,quiet_retry_at:0},a.token);
        if(result.state==='INTERVENTION')return;
        const directives=await this.#directives();
        if(a.guard()&&this.repository.get(key)?.last_progress_at===progress
          &&this.repository.get(key)?.cursor?.responseTextLength===progressCount
          &&this.repository.get(key)?.last_quiet_cycle_at===cycle&&(a.progressVersion||0)===progressVersion)
          await this.#post(key,transport,'recovery',directives.turnContinueDirective||this.config.recoveryPrompt,cycle,progressVersion);
      })).catch(error=>{if(a.guard())console.error(JSON.stringify({event:'CONTINUATION_DOCUMENT_IO_RETRY',task_id:r.task_id}));})
      .finally(()=>{if(a.quietRead===pending)delete a.quietRead;});
  }
  async #recordUrl(key,transport,value,token) {
    const observed=observedConversationUrl(value),url=canonicalUrl(observed);
    if(!url||!this.repository.current(key,token))return;
    const r=this.repository.get(key);
    if(r.observed_conversation_url===observed&&r.conversation_url)return;
    await this.#move(key,'OBSERVING','CONVERSATION_URL_OBSERVED',{
      ...(!r.conversation_url?{conversation_url:url}:{}),observed_conversation_url:observed,error:null,retry_at:0},token);
    this.#publishAfterSubmission(key,transport);
  }
  async #closePredecessors(taskId,key) {
    // Cancellation is immediate, but a successor must await physical disposal.
    for(const opening of [...this.browserOpenings.values()])if(opening.key!==key&&opening.taskId===taskId) {
      opening.abort.abort(new Error('successor requested'));
      await opening.done;
    }
    for(const entry of this.repository.cleanupEntries().filter(e=>e.task_id===taskId&&e.key!==key)) {
      const id=entry.target_id;
      const active=target=>!this.repository.records().some(r=>!terminal(r)&&r.target_id===id)
        &&(!target||!entry.conversation_digest||digest(canonicalUrl(target.url))===entry.conversation_digest
          ||this.browser.chromium?.isOwnedBootstrapTarget?.(target,entry.conversation_digest)===true);
      let result;
      if(this.browser.chromium?.isOwnedTarget?.(id))result=await this.browser.chromium.closeOwnedTarget(id,{caller:true,guard:active});
      else {
        if(this.browser.chromium?.confirmTargetAbsent)result=await this.browser.chromium.confirmTargetAbsent(id)?{absent:true}:null;
        else {const rows=await this.browser.chromium?.listExistingTargets?.();
          result=Array.isArray(rows)&&!rows.some(t=>t.id===id)?{absent:true}:null;}
      }
      if(!result?.closed&&!result?.absent)throw Object.assign(new Error('Previous task tab could not be closed'),{code:'TAB_CLOSE_FAILED'});
      await this.repository.recordTargetCleanup(entry,{status:result.closed?'CLOSED':'ABSENT',observed_at_ms:Date.now(),reason:'successor disposal'});
    }
  }
  async #open(key,transport,mode,{observationOnly=false}={}) {
    let r=this.repository.get(key);
    const recoveryStage=null;
    // Generations fence stale callbacks; they do not describe why the browser
    // is opening. Explicit app entry retries remain explicit until entry succeeds.
    const explicitEntry=r.browser_entry?.control_epoch===r.control_epoch
      &&r.browser_entry.conversation_url===r.conversation_url;
    const automaticRecovery=!explicitEntry;
    const uncertain=!!r.intent&&['PENDING','UNKNOWN','ABSENT'].includes(r.intent.outcome);
    const next=mode==='prepare'?'PREPARING':'ATTACHING';
    if(r.control_state!=='RUNNING'&&!(r.control_state==='WAITING_USER_INTERVENTION'&&mode!=='prepare'))return null;
    const beforeOpen=this.repository.token(key);
    if(mode!=='target')await this.#closePredecessors(r.task_id,key);
    if(mode==='prepare') {
      const priorTarget=r.target_id;
      if(priorTarget&&this.browser.chromium?.isOwnedTarget?.(priorTarget)) {
        const closed=await this.browser.chromium.closeOwnedTarget(priorTarget,{caller:true,guard:()=>this.repository.current(key,beforeOpen)});
        if(!closed?.closed&&!closed?.absent)throw Object.assign(new Error('Preparation tab could not be closed'),{code:'TAB_CLOSE_FAILED'});
      }
      if(!this.repository.current(key,beforeOpen))return null;
    }
    if(mode==='target'&&r.state!=='ATTACHING') {
      // ATTACHING retains the durable send intent even before a URL was known.
    }
    r=await this.#move(key,next,'BROWSER_'+mode.toUpperCase(),{
      run_generation:r.run_generation+1,browser_generation:r.browser_generation+1,server_generation:this.serverGeneration,
      attach_attempts:r.failure_epoch===r.control_epoch?r.attach_attempts:0,retry_at:0,error:null,
      browser_open_pending:{run:r.run_generation+1,browser:r.browser_generation+1}},beforeOpen);
    if(!r)return null;
    const token=this.repository.token(key);
    if(!token||!this.repository.current(key,token))return null;
    const abort=new AbortController();
    let finishOpening;const done=new Promise(resolve=>finishOpening=resolve);
    const pending={abort,token,key,taskId:r.task_id,done,openingId:JSON.stringify([key,token]),session:null,target:null,monitoring:false,...(observationOnly?{inputAuthorized:false}:{})};
    this.browserOpenings.set(pending.openingId,pending);
    this.sessions.set(key,pending);
    const guard=()=>this.sessions.get(key)===pending&&!abort.signal.aborted&&this.repository.current(key,token)
      &&(observationOnly?this.#observationAvailable(key):this.#executionAvailable(key));
    const onTarget=async target=>{
      const owned=this.browser.chromium?.isOwnedTarget?.(target.id)===true;
      const saved=guard()?await this.#move(key,this.repository.get(key).state,'BROWSER_TARGET_ACQUIRED',
        {target_id:target.id,target_owned:owned,preparation_target_disposed:false},token):null;
      // Retirement may commit while the acquisition write waits in the store queue.
      if(!saved&&owned){if(!await this.#discardedTarget(pending,target.id))await this.repository.queueTargetCleanup(key,target.id,token);void this.cleanupTerminalResources();}
    };
    try {
      let attached;
      if(mode==='prepare') {
        const directives=await this.#directives();
        attached=await this.browser.prepare({projectUrl:r.body.project_url,ownerTaskId:r.task_id,
          prompt:appendTurnStartDirective(humanizeSelfRunPrompt(r.body.prompt),directives.turnStartDirective),signal:abort.signal,onTarget,guard});
      } else if(mode==='target') {
        attached=await this.browser.attachTarget({targetId:r.target_id,signal:abort.signal,onTarget,guard,...(observationOnly?{observationOnly:true}:{})});
      } else attached=await this.browser.resume({conversationUrl:r.conversation_url,ownerTaskId:r.task_id,signal:abort.signal,onTarget,guard,automaticRecovery,
        previousTargetId:explicitEntry?r.target_id||null:null});
      if(!guard()) {
        const control=this.repository.control(r.task_id);
        if(control?.state==='STOPPED'&&control.request_id===r.request_id&&control.turn_id===r.turn_id)
          await this.#disposeStoppedAttachment(r,attached,control);
        else {
          attached.session.close();
          if(this.browser.chromium?.isOwnedTarget?.(attached.target.id))
            if(!await this.#discardedTarget(pending,attached.target.id))await this.repository.queueTargetCleanup(key,attached.target.id,token);
          void this.cleanupTerminalResources();
        }
        return null;
      }
      Object.assign(pending,attached,{guard});
      await this.#move(key,mode==='prepare'?'PREPARED':'OBSERVING','BROWSER_ATTACHED',
        {target_id:attached.target.id,target_owned:this.browser.chromium?.isOwnedTarget?.(attached.target.id)===true,
          attach_attempts:0,error:null,resume_recovery_stage:recoveryStage,browser_entry:null},token);

      return pending;
    } catch(error) {
      if(!guard()){
        const control=this.repository.control(r.task_id);
        if(control?.state==='STOPPED')await this.repository.recordStopOutcome(key,control.control_epoch,'NOT_REQUESTED');
        return null;
      }
      const code=classifyAttach(error),attempts=r.attach_attempts+1;
      const failed=this.repository.get(key);
      // Retire only an owned, unsent preparation tab that stopped answering CDP.
      // A pending submission must keep its original target for readback.
      const failedBeforeSubmit=mode==='prepare'&&!!failed?.target_id&&failed.target_owned===true
        &&!failed.intent&&!failed.conversation_url&&!failed.submission_confirmed
        &&(/CDP (?:command|query|websocket connect) timeout/i.test(String(error?.message||error))
          ||['CDP_COMMAND_TIMEOUT','CDP_QUERY_TIMEOUT'].includes(error?.code));
      let retired=false;
      if(failedBeforeSubmit) {
        const targetId=failed.target_id;
        const stillOwned=()=>guard()&&this.repository.get(key)?.target_id===targetId
          &&!this.repository.get(key)?.intent&&!this.repository.get(key)?.conversation_url
          &&!this.repository.get(key)?.submission_confirmed;
        try {
          const closed=await this.browser.chromium?.closeOwnedTarget?.(targetId,{caller:true,guard:stillOwned});
          retired=!!(closed?.closed||closed?.absent);
        } catch {}
        if(!guard())return null;
      }
      this.#detach(key);
      await this.#move(key,'BLOCKED','ATTACH_FAILED',{attach_attempts:attempts,failure_epoch:r.control_epoch,
        ...(retired?{preparation_target_disposed:true}:{}),
        blocked_epoch:null,retry_at:Date.now()+Math.max(1000,Number(this.config.resumeRetryMs)||30000),error:errorInfo(error,code)});
      await this.repository.flush(transport);
      return null;
    } finally {
      this.browserOpenings.delete(pending.openingId);
      try {await this.repository.completeBrowserOpen(key,token);} finally {finishOpening();}
    }
  }
  async #observationFailure(key,transport,error,a,monitorEnded=false) {
    if(!a?.guard())return;
    if(error?.code==='DRIVE_PROJECTION_FAILED'){this.#projectionFailure(error);return;}
    if(monitorEnded||connectionLost(error))this.#detach(key);
    await this.#move(key,this.repository.get(key).state,'BROWSER_OPERATION_FAILED',{
      error:errorInfo(error),retry_at:Date.now()+Math.max(1000,Number(this.config.resumeRetryMs)||30000)},a.token);
    this.#publishAfterSubmission(key,transport);
  }
  #projectionFailure(error) {
    console.error(JSON.stringify({event:'DRIVE_PROJECTION_PENDING',code:error.code,operation:error.operation,http_status:error.http_status}));
  }
  #publishAfterSubmission(key,transport) {
    const pending=this.repository.data.outbox[key];
    if(!pending||this.repository.publications.has(pending.path))return;
    void this.repository.flushCurrent(transport,key).catch(error=>this.#projectionFailure(error));
  }
  #monitor(key,transport) {
    const a=this.sessions.get(key);
    if(!a?.guard?.()||a.monitoring)return;
    a.monitoring=true;
    void this.browser.monitor({session:a.session,signal:a.abort.signal,
      onActivity:p=>{
        const count=Number(p.responseTextLength??p.assistantTextLength);
        if(Number.isFinite(count)&&count>=0&&a.observedCount!==count){a.observedCount=count;a.progressVersion=(a.progressVersion||0)+1;}
        const version=a.progressVersion||0;
        return this.#serial(key,()=>version===(a.progressVersion||0)?this.#observe(key,transport,p,a.token):undefined);
      },
    }).catch(error=>this.#serial(key,()=>this.#observationFailure(key,transport,error,a,true))).catch(()=>{});
  }
  async #observe(key,transport,p,token) {
    if(!this.#observationAvailable(key)||!this.sessions.get(key)?.guard?.()||!this.repository.current(key,token))return;
    await this.#recordUrl(key,transport,p.url||p.pageUrl,token);
    const r=this.repository.get(key);if(!r)return;
    const count=Number(p.responseTextLength??p.assistantTextLength);
    if(!Number.isFinite(count)||count<0)return;
    const changed=r.cursor?.responseTextLength!==count;
    if(changed) {
      await this.#move(key,'OBSERVING','CHARACTER_COUNT_CHANGED',{
        cursor:{responseTextLength:count},last_progress_at:Date.now(),error:null,retry_at:0,quiet_pending:false,quiet_retry_at:0},token);
      this.#publishAfterSubmission(key,transport);
    }
  }
  async #post(key,transport,kind,prompt,quietCycle=null,quietVersion=null) {
    const r=this.repository.get(key),a=this.sessions.get(key);
    if(!a?.guard()||!dispatchRequested(r)||!(kind==='recovery'?observingTaskControl:runningTaskControl)(this.repository.control(r.task_id),r))return;
    const version=quietVersion??(a.progressVersion||0);
    const inputCurrent=()=>a.guard()&&(kind!=='recovery'||(a.progressVersion||0)===version);
    if(!inputCurrent())return;
    if(!prompt)throw new Error('configured input required');
    if(kind==='recovery'&&quietCycle!==r.last_quiet_cycle_at&&!this.#nudgeDue(r)&&!(r.intent?.kind==='recovery'&&r.intent?.outcome==='ABSENT'))return;
    if(!await this.#fresh(key,transport)||!inputCurrent())return;
    if(kind==='recovery'&&typeof this.browser.livenessSnapshot==='function') {
      const sample=await this.browser.livenessSnapshot({session:a.session,signal:a.abort.signal});
      if(!a.guard())return;
      const count=Number(sample?.responseTextLength??sample?.assistantTextLength);
      if(Number.isFinite(count)&&count>=0&&count!==r.cursor?.responseTextLength) {
        await this.#observe(key,transport,sample,a.token);return;
      }
    }
    let intent=newIntent(r,kind,prompt,r.cursor||{});
    intent={...intent,sends:1,receipt_generation:JSON.stringify([this.serverGeneration,a.token.run,a.token.browser,intent.id])};
    const now=Date.now();
    if(!await this.#move(key,kind==='initial'?'POST_PENDING':'RECOVERY_POST','INPUT_ATTEMPT_STARTED',{
      intent,error:null,retry_at:0,...(kind==='recovery'?{last_nudge_attempt_at:now,recovery_count:r.recovery_count+1}:{last_progress_at:now})},a.token))return;
    let released=false;
    try {
      const submitted=await this.browser.submitIntent({session:a.session,prompt,kind,intent,conversationUrl:r.conversation_url,
        projectUrl:r.body.project_url,targetId:r.target_id,profileOperations:r.body.profile_operations,signal:a.abort.signal,
        guard:()=>inputCurrent()&&a.inputAuthorized!==false&&(kind==='recovery'?observingTaskControl:runningTaskControl)(this.repository.control(r.task_id),this.repository.get(key)),
        observationGuard:a.guard,
        onSubmitted:async()=>{released=true;this.#monitor(key,transport);},
        onRequest:async()=>{},onConversation:async e=>{if(a.guard())await this.#recordUrl(key,transport,e?.url||e?.probe?.url,a.token);}});
      if(!a.guard())return;
      await this.#recordUrl(key,transport,submitted?.url||submitted?.pageUrl,a.token);
      const current=this.repository.get(key);
      await this.#move(key,'OBSERVING','INPUT_ATTEMPT_FINISHED',{
        intent:{...current.intent,outcome:released?'SENT':'UNKNOWN',released},error:null,retry_at:0,
        ...(kind==='recovery'?{last_nudge_attempt_at:Date.now()}:{})},a.token);
    } catch(error) {
      if(!a.guard())return;
      if(kind==='recovery'&&!inputCurrent()) {
        await this.#move(key,'OBSERVING','CHARACTER_COUNT_CHANGED_DURING_INPUT',{quiet_pending:false,quiet_retry_at:0,error:null},a.token);
        return;
      }
      const stage=preSubmitFailureStage(error);
      const current=this.repository.get(key);
      // A missing receipt after the one send is not an error and never gates observation.
      // Retry only a locally proven attempt that never reached the submit operation.
      await this.#move(key,'OBSERVING',stage?'INPUT_NOT_SENT':'INPUT_RELEASED_UNCONFIRMED',{
        intent:{...current.intent,outcome:stage?'ABSENT':'UNKNOWN',released:released||!!error.released,pre_submit_stage:stage||null},
        error:stage?errorInfo(error,'INPUT_OPERATION_FAILED'):null,
        retry_at:stage?Date.now()+Math.max(1000,Number(this.config.resumeRetryMs)||30000):0,
        ...(kind==='recovery'?{last_nudge_attempt_at:Date.now()}:{})},a.token);
    }
    if(a.guard()){this.#monitor(key,transport);this.#publishAfterSubmission(key,transport);}
  }
}
