import { appendTurnStartDirective, humanizeSelfRunPrompt } from './prompt-directives.mjs';
import { LifecycleStore } from './lifecycle-store.mjs';
import { canonicalUrl, classifyAttach, cursor, digest, keyFor, newIntent, projection, sameCursor } from './lifecycle.mjs';
import { readResult } from './result-reconciler.mjs';

const recoveryStates=new Set(['STALLED','VERIFY_RESULT','VERIFY_CONVERSATION','VERIFY_CURSOR','QUIESCING','VERIFY_INPUT']);
const controlStates=new Set(['RUNNING','PAUSED','WAITING_USER_INTERVENTION','STOPPED','DONE','RESUME_REQUESTED','RESUME_STOPPED_REQUESTED']);
const responseKey=p=>{const value=cursor(p);delete value.streaming;return JSON.stringify(value);};
const terminal = r => ['COMPLETED','CANCELLED'].includes(r.state);
const errorInfo = (error,code) => ({code:code||error?.code||'BROWSER_ERROR',message:String(error?.message||error).slice(0,300)});

export class DriveDispatchController {
  constructor({browser,stateStore,config,promptDirectives=null,canDispatch=()=>true}) {
    this.canDispatch=canDispatch;this.browser=browser;this.stateStore=stateStore;this.config=config;this.promptDirectives=promptDirectives;
    this.repository=new LifecycleStore(stateStore);
    this.sessions=new Map();this.operations=new Map();this.stopOperations=new Map();this.standby=false;
    this.serverGeneration=Date.now();
  }
  get active() {return this.activeDispatches().at(-1)||null;}
  getActive(path) {return this.activeDispatches().find(a=>a.path===path)||null;}
  activeDispatches() {
    return this.repository.records().filter(r=>!terminal(r)).map(r=>({
      path:r.path,body:projection(r),serverStatus:projection(r).server_status,generation:r.run_generation,
      publishPending:false,session:this.sessions.get(r.key)?.session,target:this.sessions.get(r.key)?.target,
    }));
  }
  controlForTask(taskId) {return this.repository.control(taskId);}
  async flush(transport) {await this.repository.flush(transport);}
  #serial(key,fn) {
    const prior=this.operations.get(key)||Promise.resolve();
    const p=prior.then(fn,fn);
    this.operations.set(key,p);
    return p.finally(()=>{if(this.operations.get(key)===p)this.operations.delete(key);});
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
  async #fresh(key,transport) {
    const r=this.repository.get(key);
    if(!r||this.standby||!this.canDispatch())return false;
    let c;
    try {c=await transport.read('__SELFRUN_CONTROL__'+r.task_id+'.json');}
    catch(error) {
      // Known absence differs from an unreadable control that may contain STOP.
      if(error?.code!=='DRIVE_FILE_NOT_FOUND')throw error;
    }
    if(c&&controlStates.has(c.state))await this.controlDurable(null,c);
    const latest=this.repository.get(key);
    if(c&&controlStates.has(c.state)&&(c.state!=='RUNNING'||c.turn_id!==r.turn_id||c.request_id!==r.request_id))return false;
    return !terminal(latest)&&latest.control_state==='RUNNING';
  }
  async #applyControl(c) {
    if(!c||!controlStates.has(c.state))return false;
    if(c.schema!=='selfrun-task-control-v1'||!c.task_id||!Number.isSafeInteger(c.control_epoch)||c.control_epoch<1)
      throw new Error('invalid task control');
    const old=this.repository.control(c.task_id);
    if(old&&c.control_epoch<old.control_epoch) return false;
    if(old&&c.control_epoch===old.control_epoch) {
      await this.repository.applyControl(c);return false;
    }
    const sameRunningIdentity=old&&old.state==='RUNNING'&&c.state==='RUNNING'
      &&old.turn_id===c.turn_id&&old.request_id===c.request_id;
    // Same-request RUNNING refreshes update metadata only; they must not abort an in-flight browser operation.
    const stopping=[];
    if(!sameRunningIdentity) {
      for(const r of this.repository.records()) if(r.task_id===c.task_id) {
        const a=this.sessions.get(r.key);
        if(c.state==='STOPPED'&&r.request_id===c.request_id&&r.turn_id===c.turn_id) {
          a?.abort.abort(new Error('app STOP'));
          this.sessions.delete(r.key);
          stopping.push({r,a});
        } else this.#detach(r.key);
      }
    }
    await this.repository.applyControl(c);
    for(const item of stopping)await this.#stopOwned(item.r,item.a,c);
    return true;
  }
  async #stopOwned(record,attached,control) {
    const pending=this.stopOperations.get(record.key);
    if(pending)return pending;
    const work=this.#performStop(record,attached,control);
    this.stopOperations.set(record.key,work);
    try {return await work;} finally {if(this.stopOperations.get(record.key)===work)this.stopOperations.delete(record.key);}
  }
  async #performStop(record,attached,control) {
    let session=attached?.session;
    const guard=()=>{
      const latest=this.repository.control(record.task_id);
      return !this.standby&&this.canDispatch()&&latest?.state==='STOPPED'&&latest.control_epoch===control.control_epoch
        &&latest.request_id===control.request_id&&latest.turn_id===control.turn_id;
    };
    try {
      if(!guard())return;
      await this.#move(record.key,'STOPPED','APP_STOP_CLEANUP_PENDING',{stop_status:'PENDING',stop_control_epoch:control.control_epoch});
      if(attached&&!session) {
        // The original attach is still pending. Fence now; its late callback owns cleanup.
        await this.#move(record.key,'STOPPED','APP_STOP_PENDING_ATTACHMENT',{stop_status:'PENDING'});
        return;
      }
      if(!record.conversation_url) {
        if(!record.intent) {
          // No durable send was started; fencing preparation is sufficient.
          if(guard())await this.#move(record.key,'STOPPED','APP_STOP_BEFORE_SUBMISSION',{stop_status:'CONFIRMED',stop_retry_at:0,error:null});
          return;
        }
        if(!record.target_id)throw new Error('original submission target is unavailable for STOP readback');
        if(!session) {
          const target=await this.browser.attachTarget({targetId:record.target_id,signal:new AbortController().signal});
          session=target.session;
        }
        if(!guard())return;
        const outcome=await this.browser.readSubmission({session,intent:record.intent,prompt:record.intent.prompt,
          conversationUrl:'',signal:new AbortController().signal});
        if(!guard())return;
        if(outcome.state==='ABSENT') {
          await this.#move(record.key,'STOPPED','APP_STOP_SUBMISSION_ABSENT',{stop_status:'CONFIRMED',stop_retry_at:0,error:null});
          return;
        }
        const url=canonicalUrl(outcome.probe?.url);
        if(outcome.state!=='CONFIRMED'||!url)throw new Error('submitted conversation URL remains unconfirmed for STOP');
        record=await this.#move(record.key,'STOPPED','APP_STOP_SUBMISSION_LOCATED',{
          conversation_url:url,submission_confirmed:true,intent:{...record.intent,outcome:'CONFIRMED',message_id:outcome.messageId||record.intent.message_id}});
      }
      if(!session) {
        const resumed=await this.browser.resume({conversationUrl:record.conversation_url,signal:new AbortController().signal});
        session=resumed.session;
      }
      if(!guard())return;
      await this.browser.quiesce({session,conversationUrl:record.conversation_url,signal:new AbortController().signal,guard});
      if(guard())await this.#move(record.key,'STOPPED','APP_STOP_CONFIRMED',{stop_status:'CONFIRMED',stop_retry_at:0,error:null});
    } catch(error) {
      if(guard())await this.#move(record.key,'STOPPED','APP_STOP_UNCONFIRMED',{stop_status:'UNCONFIRMED',stop_retry_at:Date.now()+Number(this.config.resumeRetryMs??30000),error:errorInfo(error,'STOP_UNCONFIRMED')});
    } finally {session?.close();}
  }
  async control(_path,c,transport) {
    const changed=await this.#applyControl(c);
    if(changed&&transport) await this.repository.flush(transport);
    return changed;
  }
  async controlDurable(_path,c) {
    return this.#applyControl(c);
  }
  #detach(key) {
    const a=this.sessions.get(key);
    if(!a)return;
    a.abort.abort(new Error('lifecycle ownership changed'));
    a.session?.close();
    this.sessions.delete(key);
    // A CDP session is disposable; the target and canonical conversation survive.
  }
  async quiesceForStandby() {
    this.standby=true;
    for(const key of this.sessions.keys())this.#detach(key);
  }
  async #directives() {
    return this.promptDirectives?.current?this.promptDirectives.current():
      {turnStartDirective:this.config.turnStartDirective,turnContinueDirective:this.config.recoveryPrompt};
  }
  async #ingestRecord(path,body) {
    await this.repository.initialize((await this.#directives()).turnContinueDirective||this.config.recoveryPrompt);
    if(body?.schema!=='selfrun-server-dispatch-v1')throw new Error('invalid dispatch schema');
    if(!Array.isArray(body.profile_operations)||!body.prompt||!body.project_url)throw new Error('invalid dispatch payload');
    const record=await this.repository.ingest(path,body);
    if(['CANCELLED','SUPERSEDED'].includes(record.body.client_status))this.#detach(record.key);
    return record;
  }
  async ingest(path,body,transport) {
    const r=await this.#ingestRecord(path,body);
    return this.#serial(r.key,()=>this.#advance(r.key,transport));
  }
  async ingestDurable(path,body,transport) {
    const r=await this.#ingestRecord(path,body);
    this.#scheduleAdvance(r.key,transport);
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
    if(!this.canDispatch()){await this.quiesceForStandby();return;}
    this.standby=false;
    await this.repository.flush(transport);
    for(const r of this.repository.records()) {
      if(this.operations.has(r.key))continue;
      await this.#serial(r.key,()=>this.#advance(r.key,transport));
    }
  }
  async #result(key,transport,token=null) {
    if(this.standby||!this.canDispatch())return 'UNAVAILABLE';
    const r=this.repository.get(key), result=await readResult(transport,r);
    if(this.standby||!this.canDispatch())return 'UNAVAILABLE';
    if(token&&!this.repository.current(key,token))return result.state;
    // Result is app-owned. Observation may report it, but it cannot finish this observer.
    if(result.state!==r.result_state&&!terminal(r))
      await this.#move(key,r.state,'RESULT_OBSERVED',{result_state:result.state});
    return result.state;
  }
  async #advance(key,transport) {
    let r=this.repository.get(key);
    if(this.repository.integrityFaults.has(key))throw new Error('current state is behind committed audit revision; reconciliation required');
    const attemptCancelled=['CANCELLED','SUPERSEDED'].includes(r.body.client_status);
    if(attemptCancelled) {
      this.#detach(key);
      const priorControl=this.repository.control(r.task_id);
      if(priorControl?.state==='STOPPED'&&priorControl.request_id===r.request_id&&priorControl.turn_id===r.turn_id) {
        // Cancelling input does not cancel the app's separate request to stop generation.
        await this.#fresh(key,transport);
        const current=this.repository.get(key),control=this.repository.control(r.task_id);
        if(control?.state==='STOPPED'&&control.request_id===r.request_id&&control.turn_id===r.turn_id) {
          if(current.stop_status!=='CONFIRMED'&&Number(current.stop_retry_at||0)<=Date.now())
            await this.#stopOwned(current,null,control);
          await this.repository.flushCurrent(transport,key);return;
        }
      }
      if(this.repository.get(key).state!=='CANCELLED')await this.#move(key,'CANCELLED','CLIENT_CANCELLED');
      await this.repository.flushCurrent(transport,key);return;
    }
    if(terminal(r))return;
    if(!await this.#fresh(key,transport)) {
      const current=this.repository.get(key),control=this.repository.control(current.task_id);
      if(current.state==='STOPPED'&&control?.state==='STOPPED'&&control.request_id===current.request_id
          &&control.turn_id===current.turn_id&&current.stop_status!=='CONFIRMED'&&Number(current.stop_retry_at||0)<=Date.now())
        await this.#stopOwned(current,null,control);
      await this.repository.flushCurrent(transport,key);return;
    }
    r=this.repository.get(key);
    if(r.state==='BLOCKED'&&r.blocked_epoch===r.control_epoch)return;
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
    if(r.intent&&['PENDING','UNKNOWN','ABSENT'].includes(r.intent.outcome)) {
      if(r.intent.outcome==='ABSENT')await this.#post(key,transport,r.intent.kind,r.intent.prompt,true);
      else await this.#reconcile(key,transport);return;
    }
    if(r.state==='PREPARED'&&r.body.client_status==='SEND_REQUESTED') {
      const directives=await this.#directives();
      const prompt=appendTurnStartDirective(humanizeSelfRunPrompt(r.body.prompt),directives.turnStartDirective);
      await this.#post(key,transport,'initial',prompt);return;
    }
    if(recoveryStates.has(r.state)||r.state==='STALLED') {
      const a=this.sessions.get(key);
      await this.#recover(key,transport,r.cursor||{},a.token);
    }
    if(r.conversation_url)this.#monitor(key,transport);
    await this.repository.flushCurrent(transport,key);
  }
  async #open(key,transport,mode) {
    let r=this.repository.get(key);
    const budget=Number(this.config.maxAttachAttempts||3);
    if(r.attach_attempts>=budget&&r.failure_epoch===r.control_epoch) {
      await this.#move(key,'BLOCKED','ATTACH_BUDGET_EXHAUSTED',{blocked_epoch:r.control_epoch,retry_at:0});
      await this.repository.flush(transport);return null;
    }
    const recoveryStage=recoveryStates.has(r.state)?r.state:null;
    const uncertain=!!r.intent&&['PENDING','UNKNOWN','ABSENT'].includes(r.intent.outcome);
    const next=mode==='prepare'?'PREPARING':'ATTACHING';
    if(r.control_state!=='RUNNING')return null;
    const beforeOpen=this.repository.token(key);
    if(mode==='target'&&r.state!=='ATTACHING') {
      // ATTACHING retains the durable send intent even before a URL was known.
    }
    r=await this.#move(key,next,'BROWSER_'+mode.toUpperCase(),{
      run_generation:r.run_generation+1,browser_generation:r.browser_generation+1,server_generation:this.serverGeneration,
      attach_attempts:r.failure_epoch===r.control_epoch?r.attach_attempts:0,retry_at:0,error:null},beforeOpen);
    if(!r)return null;
    const token=this.repository.token(key),abort=new AbortController();
    const pending={abort,token,session:null,target:null,monitoring:false};
    this.sessions.set(key,pending);
    const guard=()=>this.sessions.get(key)===pending&&!abort.signal.aborted&&this.repository.current(key,token)&&!this.standby&&this.canDispatch();
    try {
      let attached;
      if(mode==='prepare') {
        const directives=await this.#directives();
        attached=await this.browser.prepare({projectUrl:r.body.project_url,
          prompt:appendTurnStartDirective(humanizeSelfRunPrompt(r.body.prompt),directives.turnStartDirective),signal:abort.signal});
      } else if(mode==='target') {
        attached=await this.browser.attachTarget({targetId:r.target_id,signal:abort.signal});
      } else attached=await this.browser.resume({conversationUrl:r.conversation_url,signal:abort.signal});
      if(!guard()) {
        const control=this.repository.control(r.task_id);
        if(control?.state==='STOPPED'&&control.request_id===r.request_id&&control.turn_id===r.turn_id)
          await this.#stopOwned(r,attached,control);
        else attached.session.close();
        return null;
      }
      Object.assign(pending,attached,{guard});
      await this.#move(key,uncertain?'POST_UNCERTAIN':mode==='prepare'?'PREPARED':'OBSERVING','BROWSER_ATTACHED',
        {target_id:attached.target.id,attach_attempts:0,error:null,resume_recovery_stage:recoveryStage},token);
      if(recoveryStage&&!uncertain) await this.#move(key,'STALLED','RESTORE_RECOVERY_STAGE',{recovery_stage:recoveryStage},token);
      return pending;
    } catch(error) {
      if(!guard())return null;
      const code=classifyAttach(error),attempts=r.attach_attempts+1;
      const blocked=['AUTH_REQUIRED','CONVERSATION_UNAVAILABLE'].includes(code)||attempts>=budget;
      this.#detach(key);
      await this.#move(key,'BLOCKED','ATTACH_FAILED',{attach_attempts:attempts,failure_epoch:r.control_epoch,
        blocked_epoch:blocked?r.control_epoch:null,retry_at:blocked?0:Date.now()+Number(this.config.resumeRetryMs||30000),error:errorInfo(error,code)});
      await this.repository.flush(transport);
      return null;
    }
  }
  #monitor(key,transport) {
    const a=this.sessions.get(key);
    if(!a||a.monitoring)return;
    a.monitoring=true;
    const r=this.repository.get(key);
    void this.browser.monitor({session:a.session,baseline:r.cursor||a.baseline,signal:a.abort.signal,
      livenessGate:()=>({state:this.repository.get(key)?.control_state,epoch:this.repository.get(key)?.control_epoch}),
      onActivity:activity=>this.#serial(key,()=>this.#observe(key,transport,activity,a.token)),
    }).then(async result=>{
      if(!a.guard())return;
      if(result?.status==='PAGE_ERROR')throw Object.assign(new Error('conversation load failed'),{code:'CONVERSATION_LOAD_FAILED'});
    }).catch(error=>this.#serial(key,async()=>{
      if(!a.guard())return;
      this.#detach(key);
      await this.#move(key,'BLOCKED','BROWSER_OBSERVATION_LOST',{error:errorInfo(error,classifyAttach(error)),blocked_epoch:null,retry_at:Date.now()+Number(this.config.resumeRetryMs||30000)});
      await this.repository.flush(transport);
    })).catch(()=>{});
  }
  async #observe(key,transport,p,token) {
    if(this.standby||!this.sessions.get(key)?.guard?.()||!this.repository.current(key,token))return;
    await this.#result(key,transport,token);
    if(!this.repository.current(key,token))return;
    let r=this.repository.get(key);
    const url=canonicalUrl(p.url||p.pageUrl);
    if(url&&url!==r.conversation_url) {
      await this.#move(key,'BLOCKED','CONVERSATION_OWNERSHIP_MISMATCH',{blocked_epoch:r.control_epoch,error:{code:'IDENTITY_MISMATCH',message:'observed conversation differs from canonical owner'}},token);
      this.#detach(key);return;
    }
    if(r.intent&&['PENDING','UNKNOWN','ABSENT'].includes(r.intent.outcome)) {
      await this.#reconcile(key,transport);return;
    }
    const changed=!r.cursor||!sameCursor(r.cursor,p);
    const responseStatus=p.status==='PAGE_ERROR'?'ERROR':p.paused?'PAUSED':p.streaming?'RUNNING':
      (p.status==='COMPLETED'||(!changed&&(Number(p.assistantCount)>0||Number(p.responseTextLength)>0)))?'COMPLETED':'IDLE';
    if(r.state==='BLOCKED'&&!changed)return;
    if(changed) {
      await this.#move(key,'OBSERVING','RESPONSE_PROGRESS',{
        cursor:cursor(p),last_progress_at:Date.now(),response_status:responseStatus,error:null,
      },token);
      await this.repository.flush(transport);
      return;
    }
    const stalled=Date.now()-r.last_progress_at>=Number(this.config.stallAfterMs||600000);
    if(stalled||p.status==='STALLED') {
      if(r.state!=='STALLED')await this.#move(key,'STALLED','CURSOR_STALLED',{cursor:cursor(p)},token);
      await this.#recover(key,transport,p,token);
    } else if(!p.streaming&&r.state==='OBSERVING') {
      await this.#move(key,'WAIT_RESULT','RESPONSE_IDLE_OBSERVING',{response_status:responseStatus},token);
      await this.repository.flush(transport);
    }
  }
  async #recover(key,transport,p,token) {
    if(!this.repository.current(key,token))return;
    await this.#move(key,'VERIFY_RESULT','RECOVERY_RESULT_CHECK',{},token);
    if(await this.#result(key,transport,token)!=='NOT_COMMITTED')return;
    if(!await this.#fresh(key,transport)||!this.repository.current(key,token))return;
    const a=this.sessions.get(key);
    await this.#move(key,'VERIFY_CONVERSATION','RECOVERY_CONVERSATION_CHECK',{},token);
    let current=await this.browser.livenessSnapshot({session:a.session,signal:a.abort.signal});
    if(!a.guard())return;
    const r=this.repository.get(key);
    if(canonicalUrl(current.url)!==r.conversation_url)throw new Error('recovery conversation mismatch');
    await this.#move(key,'VERIFY_CURSOR','RECOVERY_CURSOR_CHECK',{},token);
    if(!sameCursor(p,current)) {
      await this.#move(key,'OBSERVING','CURSOR_CHANGED',{cursor:cursor(current),last_progress_at:Date.now()},token);return;
    }
    if(r.intent?.kind==='recovery'&&r.intent.outcome==='CONFIRMED'&&r.last_recovery_epoch===r.control_epoch
      &&r.last_recovered_response===responseKey(current)) {
      await this.#move(key,'BLOCKED','RECOVERY_ALREADY_APPLIED_NO_PROGRESS',{blocked_epoch:r.control_epoch,
        error:{code:'RECOVERY_NO_PROGRESS',message:'previous recovery input confirmed; awaiting new response or explicit resume'}},token);return;
    }
    if(current.paused) {
      await this.#move(key,'BLOCKED','GENERATION_PAUSED',{blocked_epoch:r.control_epoch,error:{code:'GENERATION_PAUSED',message:'generation requires explicit continuation'}},token);return;
    }
    if(current.streaming||current.stopButtonVisible) {
      await this.#move(key,'QUIESCING','STUCK_STREAM_QUIESCE',{},token);
      if(await this.#result(key,transport,token)!=='NOT_COMMITTED'||!a.guard())return;
      await this.browser.quiesce({session:a.session,conversationUrl:r.conversation_url,signal:a.abort.signal,guard:a.guard});
      current=await this.browser.livenessSnapshot({session:a.session,signal:a.abort.signal});
      if(current.streaming||current.stopButtonVisible)throw new Error('stream did not quiesce');
    }
    if(!a.guard())return;
    await this.#move(key,'VERIFY_INPUT','RECOVERY_INPUT_CHECK',{},token);
    const directives=await this.#directives();
    await this.#post(key,transport,'recovery',directives.turnContinueDirective||this.config.recoveryPrompt);
  }
  async #post(key,transport,kind,prompt,retry=false) {
    if(!prompt)throw new Error('configured input required');
    if(kind==='recovery'&&await this.#result(key,transport)!=='NOT_COMMITTED')return;
    if(!await this.#fresh(key,transport))return;
    const a=this.sessions.get(key);
    if(!a?.guard())return;
    let r=this.repository.get(key);
    let intent=retry?r.intent:newIntent(r,kind,prompt,await this.browser.livenessSnapshot({session:a.session,signal:a.abort.signal}));
    if(!a.guard())return;
    if(retry&&(intent.outcome!=='ABSENT'||intent.sends>=2))return;
    intent={...intent,sends:intent.sends+1,outcome:'PENDING'};
    r=await this.#move(key,kind==='initial'?'POST_PENDING':'RECOVERY_POST','SUBMISSION_INTENT_DURABLE',
      {intent,recovery_count:r.recovery_count+(kind==='recovery'&&!retry?1:0),error:null,
        ...(kind==='recovery'?{last_recovered_response:responseKey(r.cursor),last_recovery_epoch:r.control_epoch}:{})},a.token);
    if(!r)return;
    await this.repository.flushCurrent(transport,key);
    // Final authority read immediately before the side effect, after the current request's durable Drive projection.
    if((kind==='recovery'&&await this.#result(key,transport,a.token)!=='NOT_COMMITTED')
        ||!await this.#fresh(key,transport)||!a.guard())return;
    try {
      const submitted=await this.browser.submitIntent({session:a.session,prompt,kind,intent,conversationUrl:r.conversation_url,
        profileOperations:r.body.profile_operations,signal:a.abort.signal,guard:a.guard,
        onRequest:async evidence=>{
          if(!a.guard())throw new Error('stale POST callback');
          const current=this.repository.get(key);
          if(current.intent?.id!==intent.id)throw new Error('submission identity changed');
          await this.#move(key,current.state,'POST_REQUEST_IDENTIFIED',{intent:{...current.intent,...evidence}},a.token);
          if(!a.guard())throw new Error('stale POST callback');
        },
        onConversation:async evidence=>{
          if(!a.guard())throw new Error('stale conversation callback');
          const url=canonicalUrl(evidence?.url||evidence?.probe?.url);
          if(!url)throw new Error('canonical conversation callback missing URL');
          const current=this.repository.get(key);
          if(current.intent?.id!==intent.id)throw new Error('submission identity changed');
          if(!current.conversation_url) {
            await this.#move(key,current.state,'POST_CONVERSATION_URL_OBSERVED',{conversation_url:url},a.token);
            try{await this.repository.flushCurrent(transport,key);}catch(error){
              console.error(JSON.stringify({event:'CONVERSATION_URL_PROJECTION_PENDING',key,url,error:String(error?.message||error).slice(0,300)}));
            }
          }
        }});
      const submittedUrl=canonicalUrl(submitted?.url||submitted?.pageUrl);
      if(submittedUrl&&a.guard()) {
        const current=this.repository.get(key);
        if(!current.conversation_url) {
          await this.#move(key,current.state,'POST_CONVERSATION_URL_OBSERVED',{conversation_url:submittedUrl},a.token);
          await this.repository.flushCurrent(transport,key);
        }
      }
    } catch(error) {
      if(a.guard())await this.#move(key,'POST_UNCERTAIN','POST_OUTCOME_UNCERTAIN',
        {intent:{...this.repository.get(key).intent,outcome:'UNKNOWN',absence_proof:error.absenceProof||null,released:!!error.released},error:errorInfo(error,'POST_UNCERTAIN')},a.token);
    }
    if(!a.guard())return;
    r=this.repository.get(key);
    if(r.state!=='POST_UNCERTAIN')await this.#move(key,'POST_UNCERTAIN','POST_AWAIT_READBACK',{intent:{...r.intent,outcome:'UNKNOWN'}},a.token);
    await this.#reconcile(key,transport);
  }
  async #reconcile(key,transport) {
    const a=this.sessions.get(key),r=this.repository.get(key);
    if(!a?.guard()||!r.intent)return;
    // Verify the submission itself, never a business Result document.
    if(!a.guard())return;
    await this.#move(key,'RECONCILING','POST_READBACK',{},a.token);
    const prompt=r.intent.prompt;
    if(typeof prompt!=='string'||digest(prompt)!==r.intent.hash)throw new Error('durable submission input hash mismatch');
    const outcome=await this.browser.readSubmission({session:a.session,intent:r.intent,prompt,conversationUrl:r.conversation_url,signal:a.abort.signal});
    if(!a.guard())return;
    if(outcome.state==='CONFIRMED') {
      const url=canonicalUrl(outcome.probe?.url);
      if(!url)throw new Error('confirmed input lacks canonical conversation');
      const intentKind=r.intent.kind,now=Date.now();
      await this.#move(key,'OBSERVING','POST_USER_MESSAGE_CONFIRMED',{
        intent:{...r.intent,outcome:'CONFIRMED',message_id:outcome.messageId||r.intent.message_id},
        ...(intentKind==='recovery'?{last_recovered_response:responseKey(outcome.probe),last_recovery_epoch:r.control_epoch}:{}),
        conversation_url:url,submission_confirmed:true,response_status:'RUNNING',cursor:cursor(outcome.probe),last_progress_at:now,error:null},a.token);
      await this.repository.flushCurrent(transport,key);
      this.#monitor(key,transport);
    } else if(outcome.state==='ABSENT'&&r.intent.sends<2) {
      await this.#move(key,'POST_UNCERTAIN','POST_ABSENCE_PROVEN',{intent:{...r.intent,outcome:'ABSENT'}},a.token);
      await this.#post(key,transport,r.intent.kind,prompt,true);
    } else {
      await this.#move(key,'POST_UNCERTAIN','POST_READBACK_UNKNOWN',{intent:{...r.intent,outcome:'UNKNOWN'},
        error:r.error||{code:'POST_UNCERTAIN',message:'submission readback remains inconclusive'}},a.token);
      await this.repository.flush(transport);
    }
  }
}
