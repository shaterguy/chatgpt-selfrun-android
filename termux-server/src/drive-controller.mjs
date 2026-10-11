import { appendTurnStartDirective, humanizeSelfRunPrompt } from './prompt-directives.mjs';
import { LifecycleStore } from './lifecycle-store.mjs';
import { canonicalUrl, classifyAttach, cursor, digest, initialRecord, keyFor, matchingTaskControl, newIntent, projection, runningTaskControl, sameCursor } from './lifecycle.mjs';
import { readResult } from './result-reconciler.mjs';
import { retiredState } from './retired-requests.mjs';
import { TerminalBrowserCleanup } from './terminal-browser-cleanup.mjs';

const recoveryStates=new Set(['STALLED','VERIFY_RESULT','VERIFY_CONVERSATION','VERIFY_CURSOR','QUIESCING','VERIFY_INPUT']);
const controlStates=new Set(['RUNNING','PAUSED','WAITING_USER_INTERVENTION','STOPPED','DONE','RESUME_REQUESTED','RESUME_STOPPED_REQUESTED']);
const responseKey=p=>{const value=cursor(p||{});delete value.streaming;return JSON.stringify(value);};
const terminal = r => !r||r.retired||['COMMITTED','COMPLETED','CANCELLED','STOPPED'].includes(r.state);
const sameIntent = (a,b) => !!a&&!!b&&a.id===b.id&&a.receipt_generation===b.receipt_generation;
const connectionLost=error=>classifyAttach(error)==='BROWSER_DEAD'||['TARGET_LOST','TARGET_CLOSED'].includes(error?.code)
  ||/target (?:closed|lost|not found)|session closed/i.test(String(error?.message||error));
const errorInfo = (error,code) => ({code:code||error?.code||'BROWSER_ERROR',message:String(error?.message||error).slice(0,300)});

export class DriveDispatchController {
  constructor({browser,stateStore,config,promptDirectives=null,canDispatch=()=>true}) {
    this.canDispatch=canDispatch;this.browser=browser;this.stateStore=stateStore;this.config=config;this.promptDirectives=promptDirectives;
    this.repository=new LifecycleStore(stateStore);
    this.sessions=new Map();this.operations=new Map();this.resultRetirements=new Map();this.standby=false;
    this.serverGeneration=Date.now();
    this.browserCleanup=new TerminalBrowserCleanup({repository:this.repository,chromium:browser.chromium,stateStore,
      available:()=>!this.standby&&this.canDispatch(),idleReleaseAllowed:()=>true,busy:()=>this.sessions.size>0||this.operations.size>0});
  }
  async initialize() {
    await this.repository.initialize((await this.#directives()).turnContinueDirective||this.config.recoveryPrompt);
  }
  async cleanupTerminalResources(options) {const result=await this.browserCleanup.run(options);await this.repository.forgetTerminalHistory();return result;}
  get active() {return this.activeDispatches().at(-1)||null;}
  getActive(path) {return this.activeDispatches().find(a=>a.path===path)||null;}
  activeDispatches() {
    return this.repository.records().filter(r=>!terminal(r)&&r.control_state==='RUNNING'&&runningTaskControl(this.repository.control(r.task_id),r)).map(r=>({
      path:r.path,body:projection(r),serverStatus:projection(r).server_status,generation:r.run_generation,
      publishPending:false,session:this.sessions.get(r.key)?.session,target:this.sessions.get(r.key)?.target,
    }));
  }
  retiredPath(path) {return Object.values(this.repository.data.retired).some(r=>r.path===path);}
  controlWatermarkForTask(taskId) {return this.repository.controlWatermark(taskId);}
  controlForTask(taskId) {return this.repository.control(taskId);}
  async flush(transport) {await this.repository.flush(transport);}
  #serial(key,fn) {
    const prior=this.operations.get(key)||Promise.resolve();
    const p=prior.then(fn,fn);
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
    this.#detach(key);
    await this.repository.invalidateControl(key);
  }
  async #readCurrentControl(identity,transport) {
    try {
      const c=await transport.read('__SELFRUN_CONTROL__'+identity.task_id+'.json');
      if(c) {
        if(c.task_id!==identity.task_id||!controlStates.has(c.state))throw new Error('invalid task control');
        await this.controlDurable(null,c,transport);
        const current=this.repository.control(identity.task_id);
        if(matchingTaskControl(c,identity)&&matchingTaskControl(current,identity)
          &&current.control_epoch===c.control_epoch&&current.state===c.state)return current;
        if(matchingTaskControl(c,identity)&&!current&&!this.repository.get(keyFor(identity)))return c;
      }
      await this.#invalidateControl(identity);return null;
    } catch(error) {
      if(error?.code==='DRIVE_FILE_NOT_FOUND'){if(await this.repository.removeMissingRequest(identity))this.#detach(keyFor(identity));return null;}
      await this.#invalidateControl(identity);
      throw error;
    }
  }
  async #fresh(key,transport) {
    const r=this.repository.get(key);
    if(!r||this.standby||!this.canDispatch())return false;
    const c=await this.#readCurrentControl(r,transport),latest=this.repository.get(key);
    const allowed=runningTaskControl(c,latest)&&!terminal(latest)&&latest.control_state==='RUNNING';
    if(!allowed)this.#detach(key);
    return allowed;
  }
  async #applyControl(c,transport) {
    if(!c||!controlStates.has(c.state)||this.repository.controlQuarantined(c))return false;
    if(c.schema!=='selfrun-task-control-v1'||!c.task_id||!Number.isSafeInteger(c.control_epoch)||c.control_epoch<1)
      throw new Error('invalid task control');
    this.browserCleanup.observeControl(c);
    if(!this.repository.records().some(r=>r.task_id===c.task_id))
      return !!await this.#restoreCurrentControl(c,transport);
    const old=this.repository.control(c.task_id);
    if(old&&c.control_epoch<old.control_epoch) return false;
    if(old&&c.control_epoch===old.control_epoch) {
      await this.repository.applyControl(c);
      if(c.state==='STOPPED')await transport?.purgeStopped?.(c);
      await this.#restoreCurrentControl(old,transport);return false;
    }
    const sameRunningIdentity=old&&old.state==='RUNNING'&&c.state==='RUNNING'
      &&old.turn_id===c.turn_id&&old.request_id===c.request_id;
    // Same-request RUNNING refreshes update metadata only; they must not abort an in-flight browser operation.
    const stopping=[];
    if(!sameRunningIdentity) {
      for(const r of this.repository.records()) if(r.task_id===c.task_id&&!['COMMITTED','COMPLETED'].includes(r.state)) {
        const a=this.sessions.get(r.key);
        if(c.state==='STOPPED'&&r.request_id===c.request_id&&r.turn_id===c.turn_id) {
          a?.abort.abort(new Error('app STOP'));
          this.sessions.delete(r.key);
          stopping.push({r,a});
        } else this.#detach(r.key);
      }
    }
    await this.repository.applyControl(c);
    for(const r of this.repository.records())if(r.task_id===c.task_id)this.#scheduleDisplacedResult(r.key,transport);
    for(const item of stopping)await this.#disposeStoppedAttachment(item.r,item.a,c);
    if(c.state==='STOPPED')await transport?.purgeStopped?.(c);
    await this.#restoreCurrentControl(c,transport);
    if(['STOPPED','DONE'].includes(c.state)||c.state==='RUNNING'&&!sameRunningIdentity)void this.cleanupTerminalResources();
    return true;
  }
  async #restoreCurrentControl(c,transport,body=null,dispatchPath=null) {
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
    const result=await readResult(transport,initialRecord(dispatchPath,body));
    if(result.state==='COMMITTED')return null;
    if(result.state==='UNAVAILABLE')throw Object.assign(new Error(result.message),{code:result.code});
    if(['CANCELLED','SUPERSEDED'].includes(body.client_status)&&(!documentEpoch||c.control_epoch<=documentEpoch)) {
      await this.repository.removeCurrentTerminalControl(c);return null;
    }

    // Current app URL is an explicit watch command. Old server fields cannot
    // turn it into prompt submission or require a retained terminal marker.
    const clean=Object.fromEntries(Object.entries(body).filter(([k])=>!['lifecycle_record','lifecycle_state','server_status','conversation_url','server_control_epoch'].includes(k)));
    Object.assign(clean,{client_status:'SEND_REQUESTED',conversation_url:c.conversation_url,server_status:'STARTED',server_control_epoch:c.control_epoch});
    return this.repository.ingest(dispatchPath,clean,c,{resumeFrom:body});
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
    if(changed&&transport) await this.repository.flush(transport);
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
    this.sessions.delete(key);
    // Soft detach preserves targets for pause, standby and connection recovery.
  }
  async quiesceForStandby() {
    this.standby=true;
    for(const key of this.sessions.keys())this.#detach(key);
  }
  async #directives() {
    return this.promptDirectives?.current?this.promptDirectives.current():
      {turnStartDirective:this.config.turnStartDirective,turnContinueDirective:this.config.recoveryPrompt};
  }
  async #ingestRecord(path,body,transport) {
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
    const retry=imported.never_submitted&&body.dispatch_attempt>imported.dispatch_attempt;
    const resume=currentControl?.state==='RUNNING'&&currentControl.control_epoch>imported.control_epoch
      &&canonicalUrl(currentControl.conversation_url)&&!['COMMITTED','COMPLETED'].includes(imported.state);
    let authorized=currentControl;
    if(!retiredState(imported)||retry||resume) {
      authorized=await this.#readCurrentControl(imported,transport);
      if(!authorized)return null;
    }
    if(!prior&&['STOPPED','DONE'].includes(authorized?.state))return undefined;
    let record=await this.repository.ingest(path,body,authorized);
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
    if(r.retired){await this.browserCleanup.run();await this.repository.flush(transport);return r;}
    return this.#serial(r.key,()=>this.#advance(r.key,transport));
  }
  async ingestDurable(path,body,transport) {
    const r=await this.#ingestRecord(path,body,transport);
    if(r&&!r.retired)this.#scheduleAdvance(r.key,transport);
    else if(r?.retired)void this.browserCleanup.run().then(()=>this.repository.flush(transport)).catch(error=>console.error(JSON.stringify({event:'TERMINAL_FINALIZATION_FAILED',error:String(error?.message||error).slice(0,300)})));
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
    await this.repository.initialize((await this.#directives()).turnContinueDirective||this.config.recoveryPrompt);
    if(!this.canDispatch()){await this.quiesceForStandby();await this.cleanupTerminalResources({force:false});return;}
    this.standby=false;
    for(const r of this.repository.records())this.#scheduleDisplacedResult(r.key,transport);
    await transport?.purgeStopped?.();
    await this.repository.flush(transport);
    for(const r of this.repository.records()) {
      if(this.operations.has(r.key))continue;
      await this.#serial(r.key,()=>this.#advance(r.key,transport));
    }
    await this.cleanupTerminalResources({force:false});
  }
  #scheduleDisplacedResult(key,transport) {
    if(!transport||this.standby||!this.canDispatch()||this.resultRetirements.has(key)
      ||this.repository.integrityFaults.has(key)||!this.repository.displacedResultCandidate(key))return false;
    // Separate from browser operations and intake: a slow old Result cannot gate the app's next turn.
    const operation=(async()=>{
      const r=await this.repository.beginDisplacedResult(key,this.config.resultRetryMs);
      if(!r||this.standby||!this.canDispatch())return;
      const fence={run:r.run_generation,browser:r.browser_generation,
        result_document_id:r.body.result_document_id,dispatch_attempt:r.dispatch_attempt};
      const result=await readResult(transport,r);
      if(this.standby||!this.canDispatch())return;
      const settled=await this.repository.settleDisplacedResult(key,fence,result);
      if(settled?.state==='COMMITTED') {
        this.#detach(key);void this.cleanupTerminalResources();
        void this.repository.flush(transport).catch(error=>console.error(JSON.stringify({
          event:'COMPLETION_PROJECTION_PENDING',key,error:String(error?.message||error).slice(0,300)})));
      }
    })().catch(error=>console.error(JSON.stringify({event:'DISPLACED_RESULT_RETRY_PENDING',key,
      error:String(error?.message||error).slice(0,300)}))).finally(()=>this.resultRetirements.delete(key));
    this.resultRetirements.set(key,operation);return true;
  }
  async #result(key,transport,token=null) {
    if(this.standby||!this.canDispatch())return 'UNAVAILABLE';
    const r=this.repository.get(key);
    if(terminal(r))return 'UNAVAILABLE';
    if(r.result_retry_at>Date.now())return 'UNAVAILABLE';
    const fence={...(token||this.repository.token(key)),result_document_id:r.body.result_document_id,
      dispatch_attempt:r.dispatch_attempt};
    if(!this.repository.current(key,fence))return 'UNAVAILABLE';
    const result=await readResult(transport,r);
    if(this.standby||!this.canDispatch()||!this.repository.current(key,fence))return 'UNAVAILABLE';
    if(result.state==='COMMITTED') {
      // Exact current Result commit is final authority; this identity is consumed.
      const committed=await this.#move(key,'COMMITTED','RESULT_COMMITTED_TERMINAL',{result_recovery:result.recovery||null},fence);
      if(committed) {
        this.#detach(key);
        void this.cleanupTerminalResources();
        // Final status publication is independent of observer/browser teardown.
        void this.repository.flush(transport).catch(error=>console.error(JSON.stringify({
          event:'COMPLETION_PROJECTION_PENDING',key,error:String(error?.message||error).slice(0,300)})));
      }
    } else if(result.state==='UNAVAILABLE') {
      const retryAt=Date.now()+Math.max(1000,Number(this.config.resultRetryMs)||30000);
      await this.#move(key,r.state,'RESULT_RETRY_PENDING',{result_state:result.state,
        result_retry_at:retryAt,retry_at:retryAt,error:{code:result.code,message:result.message}},fence);
    } else if(result.state!==r.result_state||r.result_retry_at)
      await this.#move(key,r.state,'RESULT_OBSERVED',{result_state:result.state,result_retry_at:0,retry_at:0,
        ...(r.error?.code?.startsWith('RESULT_')?{error:null}:{})},fence);
    return result.state;
  }
  #nudgeDue(record) {
    const attempted=record.last_nudge_attempt_at??(record.intent?.kind==='recovery'?record.intent.created_at:0)??0;
    return Date.now()-Math.max(Number(record.last_progress_at||0),Number(attempted))>=Number(this.config.stallAfterMs||600000);
  }
  async #advance(key,transport) {
    let r=this.repository.get(key);
    if(this.repository.integrityFaults.has(key))throw new Error('current state is behind committed audit revision; reconciliation required');
    if(terminal(r))return;
    if(!await this.#fresh(key,transport)) {
      this.#scheduleDisplacedResult(key,transport);
      await this.repository.flushCurrent(transport,key);return;
    }
    r=this.repository.get(key);
    if(r.state==='BLOCKED'&&r.reason==='RECOVERY_ALREADY_APPLIED_NO_PROGRESS'
      &&r.error?.code==='RECOVERY_NO_PROGRESS'&&r.intent?.kind==='recovery'&&r.intent.outcome==='CONFIRMED')
      r=await this.#move(key,'OBSERVING','RECOVERY_INTERVAL_POLICY_RESTORED',{blocked_epoch:null,error:null});
    if(r.state==='BLOCKED'&&r.blocked_epoch===r.control_epoch) {
      if(['ATTACH_FAILED','ATTACH_BUDGET_EXHAUSTED','GENERATION_PAUSED'].includes(r.reason)) {
        await this.#move(key,r.conversation_url?'OBSERVING':'BLOCKED','ACTIVE_RETRY_REENABLED',
          {blocked_epoch:null,retry_at:Date.now()+Math.max(1000,Number(this.config.resumeRetryMs)||30000)});
        await this.repository.flushCurrent(transport,key);
      } else {
        // A browser block must not permanently block exact Result completion.
        // #result retains its retry deadline and current-request fences.
        await this.#result(key,transport);
        await this.repository.flushCurrent(transport,key);
      }
      return;
    }
    // The server tick owns the quiet deadline independently of page observation.
    // A pending/failed probe, receipt readback, or observation retry cannot reset it.
    const attached=this.sessions.get(key);
    if(attached?.guard?.()&&r.conversation_url&&this.#nudgeDue(r)) {
      if(r.result_retry_at>Date.now())return;
      try {
        if(r.state!=='STALLED')await this.#move(key,'STALLED','PROGRESS_DEADLINE_REACHED',{},attached.token);
        await this.#recover(key,transport,r.cursor||{},attached.token);
      } catch(error){await this.#observationFailure(key,transport,error,attached);}
      if(attached.guard())this.#monitor(key,transport);
      await this.repository.flushCurrent(transport,key);return;
    }
    if(r.retry_at>Date.now())return;
    let a=this.sessions.get(key);
    if(!a) {
      if(r.intent&&['PENDING','UNKNOWN','ABSENT'].includes(r.intent.outcome)&&!r.conversation_url) {
        // Initial POST may have created a conversation. Never open a replacement.
        if(!r.target_id) {
          await this.#move(key,'BLOCKED','INITIAL_POST_IDENTITY_UNCERTAIN',{blocked_epoch:r.control_epoch,error:{code:'POST_IDENTITY_UNCERTAIN',message:'original target/conversation readback required'}});
          await this.repository.flush(transport);return;
        }
        a=await this.#open(key,transport,'target');
      } else a=await this.#open(key,transport,r.conversation_url?'attach':'prepare');
      if(!a)return;
    }
    r=this.repository.get(key);
    if(r.intent&&['PENDING','UNKNOWN','ABSENT'].includes(r.intent.outcome)&&!recoveryStates.has(r.state)) {
      try {
        if(r.intent.kind!=='recovery') {
          if(r.intent.outcome==='ABSENT'&&!r.conversation_url)await this.#post(key,transport,r.intent.kind,r.intent.prompt,true);
          else await this.#reconcile(key,transport);
          return;
        }
        await this.#reconcile(key,transport);
        r=this.repository.get(key);
        if(!this.sessions.get(key)?.guard?.())return;
      } catch(error){await this.#observationFailure(key,transport,error,a);return;}
    }
    if(r.state==='PREPARED'&&r.body.client_status==='SEND_REQUESTED') {
      const directives=await this.#directives();
      const prompt=appendTurnStartDirective(humanizeSelfRunPrompt(r.body.prompt),directives.turnStartDirective);
      await this.#post(key,transport,'initial',prompt);return;
    }
    if(recoveryStates.has(r.state)||r.state==='STALLED') {
      const a=this.sessions.get(key);
      try{await this.#recover(key,transport,r.cursor||{},a.token);}
      catch(error){await this.#observationFailure(key,transport,error,a);return;}
    }
    if(r.conversation_url)this.#monitor(key,transport);
    await this.repository.flushCurrent(transport,key);
  }
  async #open(key,transport,mode) {
    let r=this.repository.get(key);
    if(mode!=='prepare') {
      const token=this.repository.token(key),result=await readResult(transport,r);
      const latest=this.repository.get(key);
      if(!this.repository.matches(key,token)||!runningTaskControl(this.repository.control(latest?.task_id),latest)||latest.control_state!=='RUNNING'||terminal(latest))return null;
      if(result.state==='COMMITTED') {
        await this.#move(key,'COMMITTED','RESULT_COMMITTED_TERMINAL',{result_recovery:result.recovery||null},token);
        this.#detach(key);await this.repository.flush(transport);void this.cleanupTerminalResources();return null;
      }
      // A read outage does not masquerade as completion or remove ongoing work.
    }
    const recoveryStage=recoveryStates.has(r.state)?r.state:null;
    // Generations fence stale callbacks; they do not describe why the browser
    // is opening. Explicit app entry retries remain explicit until entry succeeds.
    const explicitEntry=r.browser_entry?.control_epoch===r.control_epoch
      &&r.browser_entry.conversation_url===r.conversation_url;
    const automaticRecovery=!explicitEntry;
    const uncertain=!!r.intent&&['PENDING','UNKNOWN','ABSENT'].includes(r.intent.outcome);
    const next=mode==='prepare'?'PREPARING':'ATTACHING';
    if(r.control_state!=='RUNNING')return null;
    const beforeOpen=this.repository.token(key);
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
    const pending={abort,token,session:null,target:null,monitoring:false};
    this.sessions.set(key,pending);
    const guard=()=>this.sessions.get(key)===pending&&!abort.signal.aborted&&this.repository.current(key,token)&&!this.standby&&this.canDispatch();
    const onTarget=async target=>{
      const owned=this.browser.chromium?.isOwnedTarget?.(target.id)===true;
      const saved=guard()?await this.#move(key,this.repository.get(key).state,'BROWSER_TARGET_ACQUIRED',
        {target_id:target.id,target_owned:owned},token):null;
      // Retirement may commit while the acquisition write waits in the store queue.
      if(!saved&&owned){await this.repository.queueTargetCleanup(key,target.id,token);void this.cleanupTerminalResources();}
    };
    try {
      let attached;
      if(mode==='prepare') {
        const directives=await this.#directives();
        attached=await this.browser.prepare({projectUrl:r.body.project_url,
          prompt:appendTurnStartDirective(humanizeSelfRunPrompt(r.body.prompt),directives.turnStartDirective),signal:abort.signal,onTarget,guard,
          previousTargetId:r.target_id||null});
      } else if(mode==='target') {
        attached=await this.browser.attachTarget({targetId:r.target_id,signal:abort.signal,onTarget,guard});
      } else attached=await this.browser.resume({conversationUrl:r.conversation_url,signal:abort.signal,onTarget,guard,automaticRecovery,
        previousTargetId:explicitEntry?r.target_id||null:null});
      if(!guard()) {
        const control=this.repository.control(r.task_id);
        if(control?.state==='STOPPED'&&control.request_id===r.request_id&&control.turn_id===r.turn_id)
          await this.#disposeStoppedAttachment(r,attached,control);
        else {
          attached.session.close();
          if(this.browser.chromium?.isOwnedTarget?.(attached.target.id))
            await this.repository.queueTargetCleanup(key,attached.target.id,token);
          void this.cleanupTerminalResources();
        }
        return null;
      }
      Object.assign(pending,attached,{guard});
      await this.#move(key,uncertain?'POST_UNCERTAIN':mode==='prepare'?'PREPARED':'OBSERVING','BROWSER_ATTACHED',
        {target_id:attached.target.id,target_owned:this.browser.chromium?.isOwnedTarget?.(attached.target.id)===true,
          attach_attempts:0,error:null,resume_recovery_stage:recoveryStage,browser_entry:null},token);
      if(recoveryStage&&!uncertain) await this.#move(key,'STALLED','RESTORE_RECOVERY_STAGE',{recovery_stage:recoveryStage},token);
      return pending;
    } catch(error) {
      if(!guard()){
        const control=this.repository.control(r.task_id);
        if(control?.state==='STOPPED')await this.repository.recordStopOutcome(key,control.control_epoch,'NOT_REQUESTED');
        return null;
      }
      const code=classifyAttach(error),attempts=r.attach_attempts+1;
      this.#detach(key);
      await this.#move(key,'BLOCKED','ATTACH_FAILED',{attach_attempts:attempts,failure_epoch:r.control_epoch,
        blocked_epoch:null,retry_at:Date.now()+Math.max(1000,Number(this.config.resumeRetryMs)||30000),error:errorInfo(error,code)});
      await this.repository.flush(transport);
      return null;
    } finally {
      await this.repository.completeBrowserOpen(key,token);
    }
  }
  async #observationFailure(key,transport,error,a,monitorEnded=false) {
    if(!a?.guard())return;
    if(error?.code==='DRIVE_PROJECTION_FAILED') {
      this.#projectionFailure(error);return;
    }
    const lost=connectionLost(error);
    if(lost)this.#detach(key);else if(monitorEnded)a.monitoring=false;
    const reason=lost?'BROWSER_OBSERVATION_LOST':monitorEnded?'OBSERVATION_UNAVAILABLE':'OBSERVATION_CALLBACK_FAILED';
    await this.#move(key,lost?'BLOCKED':this.repository.get(key).state,reason,{
      error:errorInfo(error,lost?classifyAttach(error):reason),blocked_epoch:null,
      retry_at:Date.now()+Math.max(1000,Number(this.config.resumeRetryMs)||30000)},a.token);
    await this.repository.flushCurrent(transport,key);
  }
  #projectionFailure(error) {
    // Publication is retryable telemetry. Do not rewrite browser evidence,
    // advance the recovery clock, or convert a completed send to a send failure.
    console.error(JSON.stringify({event:'DRIVE_PROJECTION_PENDING',error:{
      code:error.code,operation:error.operation,transport_operation:error.transport_operation,
      stage:error.stage,http_status:error.http_status,cause_code:error.cause_code,
    }}));
  }
  async #publishAfterSubmission(key,transport) {
    try{await this.repository.flushCurrent(transport,key);}
    catch(error){if(error?.code!=='DRIVE_PROJECTION_FAILED')throw error;this.#projectionFailure(error);}
  }
  #monitor(key,transport) {
    const a=this.sessions.get(key);
    if(!a?.guard?.()||a.monitoring||!canonicalUrl(this.repository.get(key)?.conversation_url))return;
    a.monitoring=true;
    const r=this.repository.get(key);
    void this.browser.monitor({session:a.session,baseline:r.cursor||a.baseline,signal:a.abort.signal,
      livenessGate:()=>({state:this.repository.get(key)?.control_state,epoch:this.repository.get(key)?.control_epoch,
        observationGeneration:this.repository.get(key)?.intent?.receipt_generation||null}),
      onActivity:(activity,observationGeneration=this.repository.get(key)?.intent?.receipt_generation||null)=>{
        a.observationSequence=(a.observationSequence||0)+1;
        const sampledCursor=this.repository.get(key)?.cursor;
        return this.#serial(key,async()=>{
          try{return await this.#observe(key,transport,activity,a.token,sampledCursor,observationGeneration);}
          catch(error){await this.#observationFailure(key,transport,error,a);}
        });
      },
    }).then(async result=>{
      if(!a.guard())return;
      if(result?.status==='PAGE_ERROR')throw Object.assign(new Error('conversation load failed'),{code:'CONVERSATION_LOAD_FAILED'});
    }).catch(error=>this.#serial(key,async()=>{
      await this.#observationFailure(key,transport,error,a,true);
    })).catch(()=>{});
  }
  async #observe(key,transport,p,token,sampledCursor,observationGeneration) {
    if(this.standby||!this.sessions.get(key)?.guard?.()||!this.repository.current(key,token))return;
    if((this.repository.get(key)?.intent?.receipt_generation||null)!==observationGeneration)return;
    let r=this.repository.get(key);
    // Exact Result completion is independent of the observed page's readiness/URL.
    const result=r.retry_at>Date.now()?'UNAVAILABLE':await this.#result(key,transport,token);
    if(!this.repository.current(key,token))return;
    r=this.repository.get(key);
    const url=canonicalUrl(p.url||p.pageUrl);
    if(url&&url!==r.conversation_url) {
      await this.#move(key,'BLOCKED','CONVERSATION_OWNERSHIP_MISMATCH',{blocked_epoch:r.control_epoch,error:{code:'IDENTITY_MISMATCH',message:'observed conversation differs from canonical owner'}},token);
      this.#detach(key);return;
    }
    // A queued monitor sample predates any readback that changed the durable cursor.
    if(r.cursor&&(!sampledCursor||!sameCursor(sampledCursor,r.cursor)))return;
    if(result!=='UNAVAILABLE'&&r.intent&&['PENDING','UNKNOWN','ABSENT'].includes(r.intent.outcome)&&!recoveryStates.has(r.state)) {
      const before=r.cursor;
      await this.#reconcile(key,transport);
      if(!this.repository.current(key,token)||!this.repository.get(key)?.conversation_url)return;
      r=this.repository.get(key);
      // Readback samples after this callback's probe; never replay older telemetry over it.
      if(r.cursor&&(!before||!sameCursor(before,r.cursor)))return;
    }
    const changed=!r.cursor||!sameCursor(r.cursor,p);
    const progressed=!r.cursor||responseKey(r.cursor)!==responseKey(p);
    // A missing active flag or an unchanged cursor says nothing about answer completion.
    // Only the exact Result can end this request; observation never waits for an answer-end guess.
    const responseStatus=p.status==='PAGE_ERROR'?'ERROR':p.paused?'PAUSED':p.streaming?'RUNNING':'UNKNOWN';
    const observationRecovered=r.error?.code==='OBSERVATION_UNAVAILABLE'&&!p.probeError&&p.status!=='PAGE_ERROR';
    if(observationRecovered) {
      r=await this.#move(key,r.state,'OBSERVATION_RECOVERED',{error:null,retry_at:0},token);
      await this.repository.flushCurrent(transport,key);
      if(!r||!this.repository.current(key,token))return;
    }
    if(r.state==='BLOCKED'&&!changed)return;
    if(changed||r.response_status!==responseStatus) {
      // Result availability gates completion/input, not current browser progress.
      r=await this.#move(key,result==='UNAVAILABLE'?r.state:'OBSERVING',progressed?'RESPONSE_PROGRESS':'RESPONSE_TELEMETRY',{
        cursor:cursor(p),...(progressed?{last_progress_at:Date.now()}:{}),response_status:responseStatus,
      },token);
      await this.repository.flush(transport);
      if(progressed||!r||!this.repository.current(key,token))return;
    }
    if(result==='UNAVAILABLE')return;
    // Browser status is telemetry; only the durable progress/attempt deadline authorizes another nudge.
    if(this.#nudgeDue(r)) {
      if(r.state!=='STALLED')await this.#move(key,'STALLED','CURSOR_STALLED',{cursor:cursor(p)},token);
      await this.#recover(key,transport,p,token);
    }
  }
  async #recover(key,transport,p,token) {
    if(!this.repository.current(key,token)||!this.#nudgeDue(this.repository.get(key)))return;
    await this.#move(key,'VERIFY_RESULT','RECOVERY_RESULT_CHECK',{},token);
    if(await this.#result(key,transport,token)!=='NOT_COMMITTED')return;
    if(!await this.#fresh(key,transport)||!this.repository.current(key,token))return;
    const a=this.sessions.get(key);
    await this.#move(key,'VERIFY_CONVERSATION','RECOVERY_CONVERSATION_CHECK',{},token);
    const r=this.repository.get(key);
    // Claim the attempt before accessing the page. Only the input controls are
    // needed here; full response inspection must never gate a due continuation.
    const attemptAt=Date.now();
    if(!await this.#move(key,'VERIFY_CURSOR','RECOVERY_ATTEMPT_STARTED',{last_nudge_attempt_at:attemptAt},token))return;
    await this.repository.flushCurrent(transport,key);
    if(!await this.#fresh(key,transport)||!this.repository.current(key,token))return;
    try {
      let current=await this.browser.recoveryInputSnapshot({session:a.session,signal:a.abort.signal});
      if(!a.guard())return;
      if(canonicalUrl(current.url)!==r.conversation_url)throw new Error('recovery conversation mismatch');
      if(current.streaming||current.stopButtonVisible) {
        await this.#move(key,'QUIESCING','STUCK_STREAM_QUIESCE',{},token);
        if(await this.#result(key,transport,token)!=='NOT_COMMITTED'||!a.guard())return;
        current=await this.browser.quiesce({session:a.session,conversationUrl:r.conversation_url,signal:a.abort.signal,guard:a.guard})||current;
      }
      if(!a.guard())return;
      await this.#move(key,'VERIFY_INPUT','RECOVERY_INPUT_CHECK',{},token);
      if(current.composer===false) {
        await this.#move(key,'VERIFY_INPUT','RECOVERY_INPUT_UNAVAILABLE',{
          error:{code:'COMPOSER_UNAVAILABLE',message:'Conversation input is unavailable; observing the current page until retry'},
          retry_at:Date.now()+Math.max(1000,Number(this.config.resumeRetryMs)||30000)},token);
        await this.repository.flushCurrent(transport,key);return;
      }
      const directives=await this.#directives();
      await this.#post(key,transport,'recovery',directives.turnContinueDirective||this.config.recoveryPrompt,false,attemptAt);
    } finally {
      if(a.guard()&&this.repository.get(key)?.last_nudge_attempt_at===attemptAt) {
        await this.#move(key,this.repository.get(key).state,'RECOVERY_UI_ATTEMPT_FINISHED',
          {last_nudge_attempt_at:Date.now()},token);
        await this.repository.flushCurrent(transport,key);
      }
    }
  }
  #requestCallback(key,a,intent) {
    return async evidence=>{
      if(!a.guard())throw new Error('stale POST callback');
      const current=this.repository.get(key);
      if(!sameIntent(current.intent,intent))throw new Error('submission identity changed');
      if(evidence.receipt&&(evidence.receipt.intent_id!==intent.id||evidence.receipt.generation!==intent.receipt_generation))
        throw new Error('receipt generation identity changed');
      const saved=await this.#move(key,current.state,'POST_REQUEST_IDENTIFIED',{intent:{...current.intent,...evidence}},
        {...a.token,intent_id:intent.id,receipt_generation:intent.receipt_generation});
      if(!saved||!a.guard())throw new Error('stale POST callback');
      if(!sameIntent(this.repository.get(key)?.intent,intent))throw new Error('submission identity changed');
      if(evidence.receipt?.invalid==='http_error'&&Number.isInteger(evidence.receipt.http_status)
          &&evidence.receipt.http_status>=100&&evidence.receipt.http_status<=599
          &&['conversation','f_conversation'].includes(evidence.receipt.endpoint_category)
          &&(current.intent.receipt?.http_status!==evidence.receipt.http_status
            ||current.intent.receipt?.request_id!==evidence.receipt.request_id)) {
        console.error(JSON.stringify({event:'CHATGPT_POST_HTTP_ERROR',
          http_status:evidence.receipt.http_status,endpoint_category:evidence.receipt.endpoint_category}));
      }
    };
  }
  async #post(key,transport,kind,prompt,retry=false,attemptAt=null) {
    if(!prompt)throw new Error('configured input required');
    if(kind==='recovery'&&(retry||(attemptAt===null?!this.#nudgeDue(this.repository.get(key))
      :this.repository.get(key)?.last_nudge_attempt_at!==attemptAt)))return;
    if(kind==='recovery'&&await this.#result(key,transport)!=='NOT_COMMITTED')return;
    if(!await this.#fresh(key,transport))return;
    const a=this.sessions.get(key);
    if(!a?.guard())return;
    let r=this.repository.get(key);
    let intent=retry?r.intent:newIntent(r,kind,prompt,kind==='recovery'?(r.cursor||{}):
      await this.browser.livenessSnapshot({session:a.session,signal:a.abort.signal}));
    if(!a.guard())return;
    if(retry&&(intent.outcome!=='ABSENT'||intent.sends>=2))return;
    intent={...intent,...(retry?{receipt:null,message_id:null,released:false,absence_proof:null,transport_settled:false}:{}),
      sends:intent.sends+1,outcome:'PENDING',
      receipt_generation:JSON.stringify([this.serverGeneration,a.token.run,a.token.epoch,a.token.browser,intent.id,intent.sends+1])};
    r=await this.#move(key,kind==='initial'?'POST_PENDING':'RECOVERY_POST','SUBMISSION_INTENT_DURABLE',
      {intent,recovery_count:r.recovery_count+(kind==='recovery'&&!retry?1:0),error:null,
        ...(kind==='recovery'?{last_nudge_attempt_at:attemptAt??Date.now(),cursor:cursor(intent.baseline),
          last_recovered_response:responseKey(r.cursor),last_recovery_epoch:r.control_epoch}:{})},a.token);
    if(!r)return;
    await this.repository.flushCurrent(transport,key);
    // Final authority read immediately before the side effect, after the current request's durable Drive projection.
    if(kind==='recovery'&&await this.#result(key,transport,a.token)!=='NOT_COMMITTED') {
      if(a.guard()&&sameIntent(this.repository.get(key)?.intent,intent)) {
        // No browser submission call has happened for this provisional intent.
        // Preserve its exact input, but do not invent an uncertain released POST.
        await this.#move(key,'POST_UNCERTAIN','RECOVERY_RESULT_GATE_DEFERRED',{
          intent:{...this.repository.get(key).intent,outcome:'ABSENT',sends:Math.max(0,intent.sends-1),
            released:false,absence_proof:'NOT_SUBMITTED_RESULT_GATE'}},a.token);
        await this.repository.flushCurrent(transport,key);
      }
      return;
    }
    if(!await this.#fresh(key,transport)||!a.guard())return;
    let submitted;
    try {
      submitted=await this.browser.submitIntent({session:a.session,prompt,kind,intent,conversationUrl:r.conversation_url,
        profileOperations:r.body.profile_operations,signal:a.abort.signal,guard:a.guard,
        onRequest:this.#requestCallback(key,a,intent),
        onConversation:async evidence=>{
          if(!a.guard())throw new Error('stale conversation callback');
          const url=canonicalUrl(evidence?.url||evidence?.probe?.url);
          if(!url)throw new Error('canonical conversation callback missing URL');
          const current=this.repository.get(key);
          if(!sameIntent(current.intent,intent))throw new Error('submission identity changed');
          if(!current.conversation_url) {
            await this.#move(key,current.state,'POST_CONVERSATION_URL_OBSERVED',{conversation_url:url},a.token);
            await this.#publishAfterSubmission(key,transport);
          }
          this.#monitor(key,transport);
        }});
    } catch(error) {
      if(a.guard())await this.#move(key,'POST_UNCERTAIN','POST_OUTCOME_UNCERTAIN',
        {intent:{...this.repository.get(key).intent,outcome:'UNKNOWN',absence_proof:error.absenceProof||null,released:!!error.released},error:errorInfo(error,'POST_UNCERTAIN')},a.token);
    } finally {
      // Slow UI preparation/completion cannot leave an already expired send deadline.
      // Receipt reconciliation happens afterwards and never renews this attempt clock.
      if(kind==='recovery'&&a.guard()&&sameIntent(this.repository.get(key)?.intent,intent)) {
        await this.#move(key,this.repository.get(key).state,'RECOVERY_ATTEMPT_FINISHED',
          {last_nudge_attempt_at:Date.now()},
          {...a.token,intent_id:intent.id,receipt_generation:intent.receipt_generation});
      }
    }
    if(!a.guard())return;
    const submittedUrl=canonicalUrl(submitted?.url||submitted?.pageUrl);
    if(submittedUrl) {
      const current=this.repository.get(key);
      if(!current.conversation_url)
        await this.#move(key,current.state,'POST_CONVERSATION_URL_OBSERVED',{conversation_url:submittedUrl},a.token);
      this.#monitor(key,transport);
    }
    r=this.repository.get(key);
    if(r.state!=='POST_UNCERTAIN')await this.#move(key,'POST_UNCERTAIN','POST_AWAIT_READBACK',{intent:{...r.intent,outcome:'UNKNOWN'}},a.token);
    await this.#publishAfterSubmission(key,transport);
    await this.#reconcile(key,transport);
  }
  async #reconcile(key,transport) {
    const a=this.sessions.get(key),r=this.repository.get(key);
    if(!a?.guard()||!r.intent)return;
    if(r.conversation_url&&sameIntent(a.reconciling?.intent,r.intent))return;
    // Receipt evidence is diagnostic once this request owns a canonical conversation.
    if(r.conversation_url)this.#monitor(key,transport);
    // Verify the submission itself, never a business Result document.
    if(!a.guard())return;
    await this.#move(key,'RECONCILING','POST_READBACK',{},a.token);
    const prompt=r.intent.prompt;
    if(typeof prompt!=='string'||digest(prompt)!==r.intent.hash)throw new Error('durable submission input hash mismatch');
    const neverCalled=r.intent.kind==='recovery'&&r.intent.outcome==='ABSENT'
      &&r.intent.absence_proof==='NOT_SUBMITTED_RESULT_GATE';
    let observationSequence=a.observationSequence||0;
    const read=()=>{observationSequence=a.observationSequence||0;return neverCalled?{state:'ABSENT'}:this.browser.readSubmission({session:a.session,intent:r.intent,prompt,conversationUrl:r.conversation_url,
      signal:a.abort.signal,guard:()=>a.guard()&&sameIntent(this.repository.get(key)?.intent,r.intent),
      onRequest:r.conversation_url?evidence=>this.#serial(key,()=>this.#requestCallback(key,a,r.intent)(evidence)):
        this.#requestCallback(key,a,r.intent)});};
    if(r.conversation_url) {
      // Receipt diagnostics must not occupy the effectful dispatch lane. Only
      // their fenced state application is serialized with sends/observations.
      const pending={intent:r.intent};a.reconciling=pending;
      void Promise.resolve().then(read).then(outcome=>this.#serial(key,()=>this.#settleReceipt(key,transport,a,r,outcome,observationSequence)))
        .catch(error=>this.#serial(key,async()=>{
          if(a.guard()&&sameIntent(this.repository.get(key)?.intent,r.intent))
            await this.#observationFailure(key,transport,error,a);
        })).finally(()=>{if(a.reconciling===pending)delete a.reconciling;})
        .catch(error=>console.error(JSON.stringify({event:'RECEIPT_DIAGNOSTIC_FAILED',key,error:String(error?.message||error).slice(0,300)})));
      return;
    }
    await this.#settleReceipt(key,transport,a,r,await read(),observationSequence);
  }
  async #settleReceipt(key,transport,a,r,outcome,observationSequence) {
    if(!a.guard())return;
    const latest=this.repository.get(key);
    if(!sameIntent(latest.intent,r.intent))return;
    const token={...a.token,intent_id:r.intent.id,receipt_generation:r.intent.receipt_generation};
    if(outcome.state==='CONFIRMED') {
      const url=canonicalUrl(outcome.probe?.url);
      if(!url)throw new Error('confirmed input lacks canonical conversation');
      const intentKind=r.intent.kind,now=Date.now();
      const cursorCurrent=(a.observationSequence||0)===observationSequence;
      await this.#move(key,'OBSERVING','POST_USER_MESSAGE_CONFIRMED',{
        intent:{...latest.intent,outcome:'CONFIRMED',message_id:outcome.messageId||latest.intent.message_id},
        ...(intentKind==='recovery'?{last_recovered_response:responseKey(outcome.probe),last_recovery_epoch:r.control_epoch}:{}),
        conversation_url:url,submission_confirmed:true,response_status:'RUNNING',
        ...(cursorCurrent?{cursor:cursor(outcome.probe),
          ...((!latest.cursor||responseKey(latest.cursor)!==responseKey(outcome.probe))?{last_progress_at:now}:{})}:{}),error:null},token);
      await this.repository.flushCurrent(transport,key);
      this.#monitor(key,transport);
    } else if(outcome.state==='ABSENT'&&r.intent.kind!=='recovery'&&!latest.conversation_url&&r.intent.sends<2) {
      await this.#move(key,'POST_UNCERTAIN','POST_ABSENCE_PROVEN',{intent:{...latest.intent,outcome:'ABSENT'}},token);
      await this.#post(key,transport,r.intent.kind,r.intent.prompt,true);
    } else {
      // Generic recovery input may be attempted again after its durable quiet window, even without an ACK.
      const recovery=r.intent.kind==='recovery',observe=!!latest.conversation_url;
      await this.#move(key,observe?'OBSERVING':'POST_UNCERTAIN','POST_READBACK_UNKNOWN',
        {intent:{...latest.intent,outcome:recovery&&outcome.state==='ABSENT'?'ABSENT':'UNKNOWN'},
        error:latest.error||{code:'POST_UNCERTAIN',message:'submission readback remains inconclusive'}},token);
      await this.repository.flush(transport);
      if(observe)this.#monitor(key,transport);
    }
  }
}
