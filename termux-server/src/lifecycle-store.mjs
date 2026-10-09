import { controlQuarantined } from './maintenance-quarantine.mjs';
import { readLifecycleHistory, migrateLegacy } from './lifecycle-migration.mjs';
import { appInput, inputFingerprint, retiredState, retireRecord, queueTargetCleanup, cancelledCleanupConfirmed, queueCancelledProjection } from './retired-requests.mjs';
import { audit, canonicalUrl, digest, initialRecord, keyFor, preparationInputDigest, projection, provenUnsubmitted, runningTaskControl, transition } from './lifecycle.mjs';

function projectionError(error,operation) {
  const status=error?.http_status??Number(/^Drive \w+ HTTP ([1-5]\d\d)\b/.exec(String(error?.message||''))?.[1]);
  const http_status=Number.isInteger(status)&&status>=100&&status<=599?status:null;
  const codes=new Set(['ECONNRESET','ECONNREFUSED','ENOTFOUND','EAI_AGAIN','ETIMEDOUT','EPIPE',
    'ENETUNREACH','EHOSTUNREACH','UND_ERR_CONNECT_TIMEOUT','UND_ERR_HEADERS_TIMEOUT','UND_ERR_BODY_TIMEOUT','UND_ERR_SOCKET','ABORT_ERR']);
  const candidate=error?.cause_code||error?.cause?.code||error?.code;
  return Object.assign(new Error('Drive projection '+operation+' failed'+(http_status?' HTTP '+http_status:'')),{
    code:'DRIVE_PROJECTION_FAILED',operation,
    transport_operation:['resolve','version','read','write','list','export'].includes(error?.operation)?error.operation:null,
    stage:['request','response','body','parse','auth','rate_limit'].includes(error?.stage)?error.stage:'unknown',
    http_status,cause_code:codes.has(candidate)?candidate:null,
  });
}

function queueDisplacedCleanup(data,r,targetId=r.target_id) {
  // A new app entry discards only this browser resource. Its Result/intent stay
  // under the existing independent business reconciliation policy.
  const d=r.browser_cleanup_displacement;
  if(!targetId||r.target_owned!==true||!d||d.target_id!==targetId||d.browser_generation!==r.browser_generation)return false;
  const outcome=data.browserCleanupOutcomes?.[targetId];
  if(['CLOSED','ABSENT'].includes(outcome?.status)&&outcome.target_id===targetId&&outcome.key===r.key
    &&outcome.browser_generation===d.browser_generation&&outcome.run_generation===d.run_generation)return false;
  data.browserCleanup[targetId]={kind:'DISPLACED',target_id:targetId,key:r.key,task_id:r.task_id,
    turn_id:r.turn_id,request_id:r.request_id,control_epoch:d.control_epoch,
    browser_generation:d.browser_generation,run_generation:d.run_generation,successor_key:d.successor_key,
    conversation_digest:r.conversation_url?digest(r.conversation_url):null};
  return true;
}


// A single durable snapshot owns both current records and the publication outboxes.
// Audit/Drive writes are projections; neither can make an uncommitted transition authoritative.
export class LifecycleStore {
  constructor(stateStore) {
    this.store=stateStore;
    this.data=structuredClone(stateStore.snapshot().lifecycle||{schema:1,requests:{},controls:{},events:[],outbox:{}});
    this.data.retired??={};this.data.browserCleanup??={};this.data.completionOutbox??={};
    this.tail=Promise.resolve();this.history={legacy:new Map(),audits:new Map()};this.integrityFaults=new Set();
    this.publications=new Map();
  }
  async initialize(prompt) {
    if(this.initPromise)return this.initPromise;
    this.initPromise=(async()=>{
      this.history=await readLifecycleHistory(this.store.config?.eventsFile);
      this.legacyPrompt=prompt;
      // Rebuild derived summaries without turning finalized history into live work.
      await this.serial(async()=>{
        const next=structuredClone(this.data);
        for(const r of Object.values(next.requests))if(retiredState(r))retireRecord(next,r);
        for(const marker of Object.values(next.retired))queueCancelledProjection(next,marker);
        await this.save(next);
      });
      for(const r of this.records())if((this.history.audits.get(r.key)||0)>r.revision)this.integrityFaults.add(r.key);
    })();
    return this.initPromise;
  }
  serial(fn) {
    const p=this.tail.then(fn,fn);
    this.tail=p.catch(()=>{});
    return p;
  }
  get(key) {return structuredClone(this.data.requests[key]||this.data.retired[key]||null);}
  records() {return Object.values(this.data.requests).map(r=>structuredClone(r));}
  ownedByOtherIngress(task,ingress) {
    return [...Object.values(this.data.requests),...Object.values(this.data.retired)]
      .some(r=>r.task_id===task&&(r.body?.server_ingress||r.server_ingress||'DRIVE')!==ingress);
  }
  controlQuarantined(control) {return controlQuarantined(this.data,control);}
  controlWatermark(task) {return structuredClone(this.data.controls[task]||null);}
  control(task) {const control=this.data.controls[task];return this.controlQuarantined(control)?null:structuredClone(control||null);}
  async save(next) {
    const records=Object.values(next.requests);
    const active=records.filter(r=>!controlQuarantined(next,next.controls[r.task_id])&&runningTaskControl(next.controls[r.task_id],r)&&r.control_state==='RUNNING'&&!['COMMITTED','COMPLETED','CANCELLED','STOPPED','PAUSED'].includes(r.state));
    const latest=records.toSorted((a,b)=>b.updated_at-a.updated_at)[0];
    const status=active.some(r=>r.state==='BLOCKED')?'ERROR':active.some(r=>['STALLED','POST_UNCERTAIN','RECONCILING'].includes(r.state))?'STALLED':
      active.length?'RUNNING':latest?.state==='COMPLETED'?'COMPLETED':latest?.state==='STOPPED'?'STOPPED':'IDLE';
    await this.store.patch({lifecycle:next,status,activeCount:active.length,
      activeDispatches:active.map(r=>({
        path:r.path,task_id:r.task_id,turn_id:r.turn_id,request_id:r.request_id,dispatch_attempt:r.dispatch_attempt,
        ...Object.fromEntries(Object.entries(projection(r)).filter(([k])=>['server_status','lifecycle_state','lifecycle_revision','server_generation','browser_generation','conversation_url','recovery_count','server_error','server_control_state','server_control_epoch'].includes(k)))})),
      conversationUrl:latest?.conversation_url||null,lastError:latest?.error?.message||null,
      lastActivityAt:latest?new Date(latest.last_progress_at).toISOString():null});
    this.data=next;
  }
  async ingest(path,body) {
    return this.serial(async()=>{
      const key=keyFor(body), old=this.data.requests[key];
      const retired=this.data.retired[key];
      if(retired) {
        if(retired.state!=='CANCELLED'||!retired.never_submitted||retired.pre_submit_proof?.schema!==1||body.dispatch_attempt<=retired.dispatch_attempt
          ||['CANCELLED','SUPERSEDED'].includes(body.client_status))return structuredClone(retired);
        const control=this.control(body.task_id);
        if(!runningTaskControl(control,retired)||retired.conversation_digest||control.conversation_url||body.conversation_url)return structuredClone(retired);
        if(preparationInputDigest(body)!==retired.pre_submit_proof.input_digest)
          throw new Error('accepted preparation input/profile is immutable');
        if(!cancelledCleanupConfirmed(this.data,retired))return structuredClone(retired);
        body={...appInput(body),server_control_epoch:control?.control_epoch||retired.control_epoch};
      }
      const incoming=canonicalUrl(body.conversation_url);
      if(incoming&&this.records().some(r=>r.key!==key&&r.conversation_url===incoming&&!['COMPLETED','CANCELLED'].includes(r.state)))
        throw new Error('canonical conversation is already owned by another request');
      if(old) {
        if(body.dispatch_attempt<old.dispatch_attempt) return structuredClone(old);
        if(path===old.path&&(inputFingerprint(body)===inputFingerprint(old.body)
          ||digest(inputFingerprint(body))===old.resumed_dispatch_fingerprint))return structuredClone(old);
        const next=structuredClone(this.data);
        let r=next.requests[key];
        r.path=path;
        const newerAttempt=body.dispatch_attempt>r.dispatch_attempt;
        r.dispatch_attempt=body.dispatch_attempt;
        if(newerAttempt&&r.state==='CANCELLED'&&!['CANCELLED','SUPERSEDED'].includes(body.client_status)) {
          r.state=r.intent&&r.intent.outcome!=='CONFIRMED'?'POST_UNCERTAIN':r.conversation_url?'ATTACHING':'PREPARING';
          r.run_generation+=1;r.error=null;r.blocked_epoch=null;r.retry_at=0;
        }
        // The client owns the claim and prompt; server fields come exclusively from this record.
        if((r.pre_submit_proof&&preparationInputDigest(body)!==r.pre_submit_proof.input_digest)
          ||r.intent&&(r.body.prompt!==body.prompt||JSON.stringify(r.body.profile_operations)!==JSON.stringify(body.profile_operations)))
          throw new Error('accepted request input/profile is immutable');
        r.body={...r.body,...appInput(body)};
        if(['CANCELLED','SUPERSEDED'].includes(body.client_status)&&!['COMMITTED','COMPLETED','CANCELLED'].includes(r.state)) {
          const previous=r.state;
          r=transition(r,'CANCELLED','CLIENT_CANCELLED_INGRESS',{run_generation:r.run_generation+1,
            never_submitted:provenUnsubmitted(r)});
          next.requests[key]=r;next.events.push(audit(r,previous,r.reason));
        }
        if(retiredState(r))retireRecord(next,r);
        else next.outbox[key]={revision:r.revision,path,body:projection(r)};
        await this.save(next);
        return structuredClone(next.retired[key]||r);
      }
      const next=structuredClone(this.data);let r=initialRecord(path,body);
      // Remote lifecycle projections cannot grant a new browser entry. Only
      // locally accepted app input/control may establish this pending authority.
      r.browser_entry=null;
      r.browser_cleanup_displacement=null;
      if(retired){delete next.retired[key];r.run_generation=(retired.run_generation||0)+1;r.revision=(retired.revision||0)+1;
        r.pre_submit_proof=structuredClone(retired.pre_submit_proof);}
      if(['CANCELLED','SUPERSEDED'].includes(body.client_status)&&!retiredState(r)){
        // Missing fields on a cold historical import are not proof that input was never sent.
        r.never_submitted=false;
        r.state='CANCELLED';
      }
      if(!retiredState(r))r=migrateLegacy(r,this.history,this.legacyPrompt||'');
      if(!retired&&(this.history.audits.has(key)||this.history.legacy.has(key)))r.pre_submit_proof=null;
      const candidate=next.controls[r.task_id],control=controlQuarantined(next,candidate)?null:candidate;
      const explicitColdResume=r.state==='PAUSED'&&runningTaskControl(control,r)
        &&control.control_epoch>r.control_epoch&&r.conversation_url
        &&(this.history.audits.get(key)||0)<=r.revision
        &&canonicalUrl(control.conversation_url)===r.conversation_url;
      if(control&&control.control_epoch>=r.control_epoch&&!['COMMITTED','COMPLETED'].includes(r.state)
        &&(!retiredState(r)||['STOPPED','DONE'].includes(control.state))) {
        const matching=control.turn_id===r.turn_id&&control.request_id===r.request_id;
        const state=['STOPPED','DONE'].includes(control.state)?'STOPPED':control.state!=='RUNNING'||!matching?'PAUSED':r.state;
        const previous=r.state;
        r=transition(r,state,'INGEST_CONTROL_RECONCILED',{control_epoch:control.control_epoch,control_state:control.state,run_generation:r.run_generation+1,
          ...(state==='STOPPED'?{stop_status:'NOT_REQUESTED'}:{})});
        next.events.push(audit(r,previous,r.reason));
      }
      if(explicitColdResume||r.conversation_url&&runningTaskControl(control,r)&&r.control_state==='RUNNING'
        &&!retiredState(r)&&!body.lifecycle_record&&!body.lifecycle_state
        &&!this.history.audits.has(key)&&!this.history.legacy.has(key))
        r.browser_entry={control_epoch:r.control_epoch,conversation_url:r.conversation_url};
      // Only fresh locally accepted input can establish this authority. Remote
      // projections and identities with prior history carry an explicit null.
      if(!Object.hasOwn(r,'pre_submit_proof')&&r.state==='DISCOVERED'&&runningTaskControl(control,r)
        &&r.intent===null&&r.conversation_url===''&&r.submission_confirmed===false)
        r.pre_submit_proof={schema:1,input_digest:preparationInputDigest(r.body)};
      next.requests[key]=r;
      if(retiredState(r))retireRecord(next,r);
      else next.outbox[key]={revision:r.revision,path,body:projection(r)};
      await this.save(next);
      return structuredClone(next.retired[key]||r);
    });
  }
  async invalidateControl(key) {
    return this.serial(async()=>{
      const old=this.data.requests[key];
      if(!old||retiredState(old)||old.control_state!=='RUNNING')return null;
      // Preserve the latest state/intent if another transition reached the store
      // while its control read was outstanding. Do not erase the epoch watermark.
      const r=transition(old,old.state,'APP_CONTROL_UNCONFIRMED',{
        control_state:'UNKNOWN',run_generation:old.run_generation+1});
      const next=structuredClone(this.data);next.requests[key]=r;
      next.events.push(audit(r,old.state,r.reason));next.outbox[key]={revision:r.revision,path:r.path,body:projection(r)};
      await this.save(next);return structuredClone(r);
    });
  }
  async move(key,state,reason,patch={},fence=null) {
    return this.serial(async()=>{
      const old=this.data.requests[key];
      if(!old){if(this.data.retired[key])return null;throw new Error('unknown request');}
      if(fence&&(!this.matches(key,fence)||old.control_state!=='RUNNING'||['COMMITTED','COMPLETED','CANCELLED'].includes(old.state))) return null;
      if(fence?.intent_id&&(old.intent?.id!==fence.intent_id||old.intent?.receipt_generation!==fence.receipt_generation))return null;
      const url=canonicalUrl(patch.conversation_url||old.conversation_url);
      if(url&&Object.values(this.data.requests).some(r=>r.key!==key&&r.conversation_url===url&&!['COMPLETED','CANCELLED'].includes(r.state)))
        throw new Error('canonical conversation is already owned by another request');
      const r=transition(old,state,reason,patch);
      const next=structuredClone(this.data);
      next.requests[key]=r;
      next.events.push(audit(r,old.state,reason));
      if(retiredState(r))retireRecord(next,r);
      else next.outbox[key]={revision:r.revision,path:r.path,body:projection(r)};
      await this.save(next);
      return structuredClone(r);
    });
  }
  token(key) {
    const r=this.data.requests[key];
    return r?{run:r.run_generation,epoch:r.control_epoch,browser:r.browser_generation}:null;
  }
  matches(key,t) {
    const r=this.data.requests[key];
    return !!r&&!!t&&r.run_generation===t.run&&r.browser_generation===t.browser
      &&(!Object.hasOwn(t,'result_document_id')||r.body.result_document_id===t.result_document_id)
      &&(!Object.hasOwn(t,'dispatch_attempt')||r.dispatch_attempt===t.dispatch_attempt);
  }
  current(key,t) {
    const r=this.data.requests[key];
    return this.matches(key,t)&&runningTaskControl(this.control(r.task_id),r)&&r.control_state==='RUNNING'
      &&!['STOPPED','PAUSED','COMMITTED','COMPLETED','CANCELLED'].includes(r.state);
  }
  displacedResultCandidate(key) {
    const r=this.data.requests[key],c=r&&this.control(r.task_id);
    return !!r&&r.state==='PAUSED'&&!!(r.conversation_url||r.target_id||r.intent)
      &&!!c&&!['STOPPED','DONE'].includes(c.state)&&keyFor(c)!==key;
  }
  async beginDisplacedResult(key,retryMs) {
    return this.serial(async()=>{
      if(!this.displacedResultCandidate(key))return null;
      const old=this.data.requests[key],now=Date.now();
      if(old.result_retirement_retry_at>now)return null;
      // Persist pacing before any remote read; restart cannot turn this into a busy loop.
      const r=transition(old,old.state,'DISPLACED_RESULT_CHECK',{
        result_retirement_retry_at:now+Math.max(1000,Number(retryMs)||30000)},now);
      const next=structuredClone(this.data);next.requests[key]=r;
      next.outbox[key]={revision:r.revision,path:r.path,body:projection(r)};
      await this.save(next);return structuredClone(r);
    });
  }
  async settleDisplacedResult(key,fence,result) {
    return this.serial(async()=>{
      // This lane can only consume an exact displaced identity; it never restores execution.
      if(!this.displacedResultCandidate(key)||!this.matches(key,fence))return null;
      const old=this.data.requests[key],committed=result.state==='COMMITTED';
      if(!committed&&result.state===old.result_state&&result.code===old.error?.code)return null;
      const patch={result_state:result.state,...(committed?{result_recovery:result.recovery||null}:{}),
        ...(result.state==='UNAVAILABLE'?{error:{code:result.code,message:result.message}}:
          old.error?.code?.startsWith('RESULT_')?{error:null}:{})};
      const r=transition(old,committed?'COMMITTED':old.state,
        committed?'RESULT_COMMITTED_TERMINAL':'DISPLACED_RESULT_OBSERVED',patch);
      const next=structuredClone(this.data);next.requests[key]=r;
      if(committed){next.events.push(audit(r,old.state,r.reason));retireRecord(next,r);}
      else next.outbox[key]={revision:r.revision,path:r.path,body:projection(r)};
      await this.save(next);return structuredClone(next.retired[key]||r);
    });
  }
  async restoreFromControl(c,body) {
    return this.serial(async()=>{
      if(this.controlQuarantined(c))return null;
      const key=keyFor(c),marker=this.data.retired[key],control=this.control(c.task_id);
      const url=canonicalUrl(c.conversation_url);
      if(['COMMITTED','COMPLETED'].includes(marker?.state))return null;
      if(!marker||c.state!=='RUNNING'||!url||c.control_epoch<=marker.control_epoch
        ||control?.control_epoch!==c.control_epoch||control.state!=='RUNNING'||keyFor(control)!==key)return null;
      const reject=reason=>{throw Object.assign(new Error(reason),{code:'RESUME_REJECTED',reason});};
      if((this.history.audits.get(key)||0)>marker.revision)reject('RESUME_LOCAL_HISTORY_AHEAD');
      if(!body||body.schema!=='selfrun-server-dispatch-v1'||keyFor(body)!==key)reject('RESUME_DISPATCH_IDENTITY_CONFLICT');
      if(marker.conversation_digest&&marker.conversation_digest!==digest(url))reject('RESUME_CONVERSATION_MISMATCH');
      if(!Array.isArray(body.profile_operations)||typeof body.prompt!=='string'||!body.prompt||!body.project_url
        ||!Number.isSafeInteger(body.dispatch_attempt)||body.dispatch_attempt<1)reject('RESUME_INVALID_DISPATCH');
      if(Object.values(this.data.requests).some(r=>r.conversation_url===url))reject('RESUME_CONVERSATION_ALREADY_OWNED');
      const clean={...appInput(body),client_status:'SEND_REQUESTED',conversation_url:url,server_status:'STARTED',server_control_epoch:c.control_epoch};
      const r={...initialRecord(marker.path,clean),run_generation:(marker.run_generation||0)+1,
        browser_generation:(marker.browser_generation||0)+1,revision:(marker.revision||0)+1,control_state:'RUNNING',
        browser_entry:{control_epoch:c.control_epoch,conversation_url:url},
        resumed_dispatch_fingerprint:digest(inputFingerprint(body))};
      const next=structuredClone(this.data);delete next.retired[key];next.requests[key]=r;
      await this.save(next);return structuredClone(r);
    });
  }
  async completeBrowserOpen(key,fence) {
    return this.serial(async()=>{
      const next=structuredClone(this.data),r=next.requests[key]||next.retired[key],pending=r?.browser_open_pending;
      if(!pending||pending.run!==fence.run||pending.browser!==fence.browser)return false;
      delete r.browser_open_pending;
      await this.save(next);return true;
    });
  }
  async queueTargetCleanup(key,targetId,fence=null) {
    return this.serial(async()=>{
      const marker=this.data.retired[key];
      const next=structuredClone(this.data);
      if(marker&&retiredState(marker))queueTargetCleanup(next,{...marker,target_owned:true},targetId);
      else {
        const r=next.requests[key],d=r?.browser_cleanup_displacement,candidate=r&&next.controls[r.task_id],c=controlQuarantined(next,candidate)?null:candidate;
        if(!fence||r?.state!=='PAUSED'||!d||d.browser_generation!==fence.browser||r.browser_generation!==fence.browser
          ||d.run_generation!==fence.run||!(c?.control_epoch>=d.control_epoch)||keyFor(c||{})===key
          ||(d.target_id&&d.target_id!==targetId))return false;
        d.target_id=targetId;r.target_id=targetId;r.target_owned=true;
        if(!queueDisplacedCleanup(next,r,targetId))return false;
      }
      await this.save(next);return true;
    });
  }
  async quarantineTargetCleanup(entry,proof,guard) {
    return this.serial(async()=>{
      if(typeof guard!=='function'||!guard()||JSON.stringify(this.data.browserCleanup[entry.target_id])!==JSON.stringify(entry))return false;
      const next=structuredClone(this.data),archiveKey=digest(JSON.stringify([entry,proof]));
      next.browserCleanupUnresolved??={};
      next.browserCleanupUnresolved[archiveKey]??={schema:1,status:'UNRESOLVED',recovery:'UNAVAILABLE_IN_CURRENT_ADAPTER',reason:proof.reason,
        entry:structuredClone(entry),ownership_proof:structuredClone(proof),quarantined_at_ms:Date.now()};
      delete next.browserCleanup[entry.target_id];
      // Preserve actual PENDING outcomes/retired state; never invent CLOSED/ABSENT.
      await this.save(next);return true;
    });
  }
  async recordTargetCleanup(entry,outcome) {
    return this.serial(async()=>{
      if(JSON.stringify(this.data.browserCleanup[entry.target_id])!==JSON.stringify(entry))return false;
      const next=structuredClone(this.data);
      const observed={target_id:entry.target_id,status:outcome.status,
        observed_at_ms:outcome.observed_at_ms,reason:outcome.reason,
        ...(entry.kind==='DISPLACED'?{kind:entry.kind,key:entry.key,browser_generation:entry.browser_generation,run_generation:entry.run_generation}:{}),
        ...(outcome.error?{error:String(outcome.error).slice(0,300)}:{})};
      next.browserCleanupOutcomes??={};next.browserCleanupOutcomes[entry.target_id]=observed;
      if(next.retired[entry.key])next.retired[entry.key].browser_cleanup=observed;
      if(['CLOSED','ABSENT'].includes(outcome.status))delete next.browserCleanup[entry.target_id];
      await this.save(next);return true;
    });
  }
  async recordResumeRejection(key,epoch,reason) {
    return this.serial(async()=>{
      const marker=this.data.retired[key],c=marker&&this.control(marker.task_id);
      if(!marker||c?.control_epoch!==epoch||c.state!=='RUNNING')return;
      const next=structuredClone(this.data);Object.assign(next.retired[key],{resume_rejected_epoch:epoch,resume_error:reason});
      await this.save(next);
    });
  }
  async recordStopOutcome(key,epoch,status) {
    return this.serial(async()=>{
      const marker=this.data.retired[key],c=marker&&this.control(marker.task_id);
      if(!marker||marker.control_epoch!==epoch||c?.control_epoch!==epoch||c.state!=='STOPPED')return false;
      const next=structuredClone(this.data);next.retired[key].stop_status=status;
      await this.save(next);return true;
    });
  }
  async applyControl(c) {
    return this.serial(async()=>{
      if(this.controlQuarantined(c))return false;
      const prior=this.data.controls[c.task_id];
      if(prior&&c.control_epoch<prior.control_epoch) return false;
      if(prior&&c.control_epoch===prior.control_epoch) {
        if(c.state!==prior.state||c.request_id!==prior.request_id||c.turn_id!==prior.turn_id) throw new Error('control epoch conflict');
        const old=this.data.requests[keyFor(c)];
        // A fresh read may reauthorize the same app command after a missing or
        // unreadable control suspended execution. Preserve its epoch watermark.
        if(old?.control_state!=='UNKNOWN'||c.control_epoch<old.control_epoch)return false;
        const r=transition(old,old.state,'APP_CONTROL_CONFIRMED',{
          control_epoch:c.control_epoch,control_state:c.state,run_generation:old.run_generation+1});
        const next=structuredClone(this.data);next.requests[r.key]=r;
        next.events.push(audit(r,old.state,r.reason));next.outbox[r.key]={revision:r.revision,path:r.path,body:projection(r)};
        await this.save(next);return true;
      }
      const next=structuredClone(this.data);
      // Reopening a known request is explicit RESUME, not a new-turn signal.
      const successorCreation=c.state==='RUNNING'&&!next.requests[keyFor(c)]&&!next.retired[keyFor(c)]
        &&(!prior||prior.state!=='RUNNING'||keyFor(prior)!==keyFor(c));
      next.controls[c.task_id]=structuredClone(c);
      const retired=next.retired[keyFor(c)];
      if(retired&&['STOPPED','DONE'].includes(c.state)){
        retired.control_epoch=c.control_epoch;retired.control_state=c.state;
        if(!['COMMITTED','COMPLETED'].includes(retired.state))retired.state='STOPPED';
        retired.stop_status='NOT_REQUESTED';
        const pending=next.completionOutbox[retired.key];
        if(c.state==='DONE'&&pending?.completion_reason==='APP_DONE') {
          retired.revision+=1;retired.updated_at=Date.now();
          Object.assign(pending,{control_epoch:c.control_epoch,revision:retired.revision,updated_at:retired.updated_at});
        }
      }
      const sameRunningIdentity=prior&&prior.state==='RUNNING'&&c.state==='RUNNING'
        &&prior.turn_id===c.turn_id&&prior.request_id===c.request_id;
      for(const [key,old] of Object.entries(next.requests)) {
        if(old.task_id!==c.task_id||['COMMITTED','COMPLETED'].includes(old.state)
          ||(old.state==='CANCELLED'&&!(c.state==='STOPPED'&&old.request_id===c.request_id&&old.turn_id===c.turn_id)))continue;
        const matching=old.request_id===c.request_id&&old.turn_id===c.turn_id;
        let state=old.state;
        if(c.state==='STOPPED'||c.state==='DONE') state='STOPPED';
        else if(c.state!=='RUNNING'||!matching) state='PAUSED';
        const preserveGeneration=sameRunningIdentity&&matching&&old.control_state==='RUNNING';
        const pendingEntry=old.browser_entry?.control_epoch===old.control_epoch
          &&old.browser_entry.conversation_url===old.conversation_url;
        const appUrl=canonicalUrl(c.conversation_url);
        const explicitEntry=!sameRunningIdentity&&matching&&c.state==='RUNNING'&&old.state==='PAUSED'
          &&appUrl&&appUrl===old.conversation_url;
        const preserveEntry=sameRunningIdentity&&matching&&pendingEntry&&(!c.conversation_url||appUrl===old.conversation_url);
        const previousDisplacement=old.browser_cleanup_displacement;
        const displacement=matching?null:previousDisplacement?.browser_generation===old.browser_generation?previousDisplacement:
          successorCreation?{target_id:old.target_id||null,run_generation:old.run_generation,browser_generation:old.browser_generation,
            successor_key:keyFor(c),control_epoch:c.control_epoch}:previousDisplacement||null;
        if(matching)for(const [id,entry]of Object.entries(next.browserCleanup))
          if(entry.kind==='DISPLACED'&&entry.key===key)delete next.browserCleanup[id];
        const r=transition(old,state,'CONTROL_'+c.state,{
          control_epoch:c.control_epoch,control_state:c.state,
          run_generation:preserveGeneration?old.run_generation:old.run_generation+1,
          browser_entry:explicitEntry||preserveEntry?{control_epoch:c.control_epoch,conversation_url:old.conversation_url}:null,
          browser_cleanup_displacement:displacement,
          ...(['STOPPED','DONE'].includes(c.state)?{stop_status:'NOT_REQUESTED'}:{}),
        });
        next.requests[key]=r;next.events.push(audit(r,old.state,r.reason));
        if(c.state==='RUNNING'&&!matching)queueDisplacedCleanup(next,r);
        if(retiredState(r))retireRecord(next,r);
      else next.outbox[key]={revision:r.revision,path:r.path,body:projection(r)};
      }
      await this.save(next);
      return true;
    });
  }
  async flushCurrent(transport,key) {
    if(!transport)return;
    const pending=this.data.outbox[key];
    if(!pending)return;
    return this.#publish(pending.path,async()=>{
      const item=this.data.outbox[key];
      if(!item)return;
      let remote;
      try{remote=await transport.read(item.path);}catch(error){throw projectionError(error,'read');}
      if(!this.data.requests[key]||this.data.outbox[key]?.revision!==item.revision)return;
      if(remote?.request_id!==item.body.request_id||remote?.turn_id!==item.body.turn_id||remote?.task_id!==item.body.task_id)
        throw new Error('Drive projection identity conflict');
      if(Number(remote.dispatch_attempt)!==Number(item.body.dispatch_attempt))throw new Error('Drive projection attempt conflict');
      const serverKeys=['lifecycle_record','lifecycle_state','lifecycle_revision','server_status','server_generation','browser_generation',
        'conversation_url','recovery_count','recovery_attempt_id','post_correlation','post_outcome','server_error','server_error_class',
        'server_control_state','server_control_epoch','resume_retry_count','resume_retry_at_ms','result_state','response_cursor','response_status','stop_status','updated_at_ms'];
      const body={...remote};
      for(const k of serverKeys)body[k]=item.body[k];
      delete body.server_completion_reason;
      try{await transport.write(item.path,body,{expected:remote});}catch(error){throw projectionError(error,'write');}
      await this.serial(async()=>{
        const next=structuredClone(this.data);
        if(next.outbox[key]?.revision===item.revision)delete next.outbox[key];
        await this.save(next);
      });
    });
  }
  #publish(path,operation) {
    const prior=this.publications.get(path)||Promise.resolve();
    const p=prior.then(operation,operation);this.publications.set(path,p);
    return p.finally(()=>{if(this.publications.get(path)===p)this.publications.delete(path);});
  }
  async flush(transport) {
    const publish=async()=>{
      for(const event of [...this.data.events]) {
        await this.store.recordEvent('CONVERSATION_TRANSITION',event,{generation:event.server_generation,
          signalId:event.request_id,turnId:event.turn_id,conversationUrl:event.conversation_id?'https://chatgpt.com/c/'+event.conversation_id:null,status:event.new_state});
        await this.serial(async()=>{
          const next=structuredClone(this.data);next.events=next.events.filter(e=>e.event_id!==event.event_id);await this.save(next);
        });
      }
      if(!transport)return;
      for(const [key,item] of Object.entries(this.data.completionOutbox)) {
        try {
        await this.#publish(item.path,async()=>{
        const remote=await transport.read(item.path);
        if(this.data.completionOutbox[key]?.revision!==item.revision)return;
        const marker=this.data.retired[key],appDone=item.completion_reason==='APP_DONE';
        if(item.completion_reason==='CLIENT_CANCELLED') {
          const same=remote?.request_id===item.request_id&&remote?.turn_id===item.turn_id&&remote?.task_id===item.task_id
            &&Number(remote.dispatch_attempt)===Number(item.dispatch_attempt);
          const current=marker?.state==='CANCELLED'&&marker.revision===item.revision;
          if(current&&same) {
            const cleanupConfirmed=cancelledCleanupConfirmed(this.data,marker);
            if(marker.never_submitted===true&&!cleanupConfirmed)return;
            const proof={schema:1,task_id:item.task_id,turn_id:item.turn_id,request_id:item.request_id,
              dispatch_attempt:item.dispatch_attempt,control_epoch:marker.control_epoch,
              never_submitted:marker.never_submitted===true&&marker.pre_submit_proof?.schema===1&&!marker.conversation_digest,
              cleanup_confirmed:cleanupConfirmed};
            const body={...remote,server_status:'CANCELLED',lifecycle_state:'CANCELLED',
              server_control_state:marker.control_state,server_control_epoch:marker.control_epoch,
              server_cancel_proof:proof,lifecycle_revision:marker.revision,updated_at_ms:marker.updated_at};
            delete body.lifecycle_record;
            await transport.write(item.path,body,{expected:remote});
          }
          await this.serial(async()=>{
            const next=structuredClone(this.data);
            if(next.completionOutbox[key]?.revision===item.revision){
              delete next.completionOutbox[key];
              if(next.retired[key]?.revision===item.revision)next.retired[key].cancel_projection_schema=1;
            }
            await this.save(next);
          });
          return;
        }
        const current=appDone?marker?.state==='STOPPED'&&marker.control_state==='DONE'
          &&marker.control_epoch===item.control_epoch&&marker.revision===item.revision:
          ['COMMITTED','COMPLETED'].includes(marker?.state);
        const same=remote?.request_id===item.request_id&&remote?.turn_id===item.turn_id&&remote?.task_id===item.task_id
          &&Number(remote.dispatch_attempt)===Number(item.dispatch_attempt);
        if(current&&same) {
          const body={...remote,server_status:'COMPLETED',lifecycle_state:appDone?'STOPPED':'COMMITTED',
            result_state:appDone?item.result_state:'COMMITTED',
            ...(appDone?{server_control_state:'DONE',server_control_epoch:item.control_epoch,stop_status:'NOT_REQUESTED'}:{}),
            server_completion_reason:appDone?'APP_DONE':'RESULT_COMMITTED',
            lifecycle_revision:item.revision,updated_at_ms:item.updated_at,server_error:'',server_error_class:null};
          // Do not leave an older executable lifecycle snapshot in a final projection.
          delete body.lifecycle_record;
          await transport.write(item.path,body,{expected:remote});
        }
        // A reused path/new app attempt supersedes old telemetry, never new work.

        await this.serial(async()=>{
          const next=structuredClone(this.data);
          if(next.completionOutbox[key]?.revision===item.revision)delete next.completionOutbox[key];
          await this.save(next);
        });
        });
        } catch(error) {
          console.error(JSON.stringify({event:'COMPLETION_PROJECTION_PENDING',key,error:String(error?.message||error).slice(0,300)}));
        }
      }
      for(const key of Object.keys(this.data.outbox))await this.flushCurrent(transport,key);
    };
    const p=(this.flushTail||Promise.resolve()).then(publish,publish);
    this.flushTail=p.catch(()=>{});
    return p;
  }
}
