import { controlQuarantined, currentCancellationProof, discardTerminalHistorySnapshot } from './maintenance-quarantine.mjs';
import { readLifecycleHistory, migrateLegacy } from './lifecycle-migration.mjs';
import { appInput, inputFingerprint, retiredState, retireRecord, queueTargetCleanup, cancelledCleanupConfirmed } from './retired-requests.mjs';
import { audit, canonicalUrl, digest, initialRecord, keyFor, preparationInputDigest, projection, provenUnsubmitted, observingTaskControl, runningTaskControl, transition } from './lifecycle.mjs';

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
  // A new app entry disposes only its displaced browser resource; no business
  // Result read is started for the preceding request.
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


let nextRequestGeneration=0;

// A single durable snapshot owns both current records and the publication outboxes.
// Audit/Drive writes are projections; neither can make an uncommitted transition authoritative.
export class LifecycleStore {
  constructor(stateStore,{onTerminal=()=>{},canPublish=()=>true}={}) {
    this.onTerminal=onTerminal;
    this.canPublish=canPublish;
    this.store=stateStore;
    this.data=structuredClone(stateStore.snapshot().lifecycle||{schema:1,requests:{},controls:{},events:[],outbox:{}});
    this.data.retired??={};this.data.browserCleanup??={};this.data.completionOutbox??={};
    this.tail=Promise.resolve();this.history={legacy:new Map(),audits:new Map()};this.integrityFaults=new Set();
    this.publications=new Map();
    // Unfinished owned resources may outlive a replacement watch for the same
    // request. They are process-local cleanup authority, never restart history.
    this.resourceCleanups=new Map();
  }
  async initialize(prompt) {
    if(this.initPromise)return this.initPromise;
    this.initPromise=(async()=>{
      const original={...this.store.snapshot(),lifecycle:this.data};
      const clean=discardTerminalHistorySnapshot(original);this.data=clean.lifecycle;
      await this.store.patch(clean);
      this.history=await readLifecycleHistory(this.store.config?.eventsFile);
      const currentKeys=new Set(this.records().map(r=>r.key));
      for(const map of [this.history.audits,this.history.legacy])for(const key of map.keys())if(!currentKeys.has(key))map.delete(key);
      this.legacyPrompt=prompt;
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
  cleanupEntry(id) {return this.data.browserCleanup[id]||this.resourceCleanups.get(id);}
  cleanupEntries() {return [...new Map([...this.resourceCleanups,...Object.entries(this.data.browserCleanup)]).values()];}
  independentResource(entry) {
    return !this.data.browserCleanup[entry.target_id]
      &&JSON.stringify(this.resourceCleanups.get(entry.target_id))===JSON.stringify(entry);
  }
  records() {return Object.values(this.data.requests).map(r=>structuredClone(r));}
  ownedByOtherIngress(task,ingress) {
    return Object.values(this.data.requests)
      .some(r=>r.task_id===task&&(r.body?.server_ingress||r.server_ingress||'DRIVE')!==ingress);
  }
  controlQuarantined(control) {return controlQuarantined(this.data,control);}
  controlWatermark(task) {return structuredClone(this.data.controls[task]||null);}
  control(task) {const control=this.data.controls[task];return this.controlQuarantined(control)?null:structuredClone(control||null);}
  async save(next,discardedRecords=[]) {
    const ended=[...Object.values(next.retired||{}).filter(r=>['STOPPED','COMMITTED','COMPLETED'].includes(r.state)),...discardedRecords];
    const endedKeys=new Set(ended.map(r=>r.key||keyFor(r)));
    const records=Object.values(next.requests).filter(r=>!endedKeys.has(r.key));
    const active=records.filter(r=>!controlQuarantined(next,next.controls[r.task_id])&&observingTaskControl(next.controls[r.task_id],r)&&['RUNNING','WAITING_USER_INTERVENTION'].includes(r.control_state)&&!['COMMITTED','COMPLETED','CANCELLED','STOPPED','PAUSED'].includes(r.state));
    const latest=records.toSorted((a,b)=>b.updated_at-a.updated_at)[0];
    const status=active.some(r=>r.state==='BLOCKED')?'ERROR':active.some(r=>['STALLED','POST_UNCERTAIN','RECONCILING'].includes(r.state))?'STALLED':
      active.length?'RUNNING':latest?.state==='COMPLETED'?'COMPLETED':latest?.state==='STOPPED'?'STOPPED':'IDLE';
    // Terminal execution state is discarded in memory and on disk together.
    // Cancellation keeps its existing bounded pre-submit proof policy.
    const resources=Object.values(next.browserCleanup||{}).filter(entry=>ended.some(r=>r.key===entry.key));
    const clean=discardTerminalHistorySnapshot({lifecycle:next},ended,true).lifecycle;
    // Keep cancellation bookkeeping only; no ended execution is moved to retired.
    next=clean;
    await this.store.update(current=>discardTerminalHistorySnapshot({...current,lifecycle:next,status,activeCount:active.length,
      activeDispatches:active.map(r=>({
        path:r.path,task_id:r.task_id,turn_id:r.turn_id,request_id:r.request_id,dispatch_attempt:r.dispatch_attempt,
        conversation_url:r.conversation_url})),
      conversationUrl:latest?.conversation_url||null,lastError:latest?.error?.message||null,
      lastActivityAt:latest?new Date(latest.last_progress_at).toISOString():null},ended));
    this.data=next;
    for(const entry of resources)this.resourceCleanups.set(entry.target_id,{...structuredClone(entry),
      terminal_state:ended.find(r=>r.key===entry.key).state});
    for(const r of ended){this.history.audits.delete(r.key);this.history.legacy.delete(r.key);this.integrityFaults.delete(r.key);}
    this.onTerminal(ended);
  }
  async removeCurrentTerminalControl(control) {
    return this.serial(async()=>{
      if(this.data.requests[keyFor(control)]||JSON.stringify(this.data.controls[control.task_id])!==JSON.stringify(control))return false;
      const clean=discardTerminalHistorySnapshot({...this.store.snapshot(),lifecycle:this.data},[{...control,state:'CANCELLED'}],true);
      await this.save(clean.lifecycle);return true;
    });
  }
  async removeMissingRequest(identity) {
    return this.serial(async()=>{
      const key=keyFor(identity),current=this.data.requests[key];
      if(!current||current.run_generation!==identity.run_generation||current.browser_generation!==identity.browser_generation||current.revision!==identity.revision)return false;
      const clean=discardTerminalHistorySnapshot({...this.store.snapshot(),lifecycle:this.data},[{...current,state:'STOPPED'}],true);
      this.history.audits.delete(key);this.history.legacy.delete(key);this.integrityFaults.delete(key);
      await this.save(clean.lifecycle);return true;
    });
  }
  async forgetTerminalHistory({discardPendingPublications=false}={}) {
    return this.serial(async()=>{
      const ongoingTasks=new Set(Object.values(this.data.requests).map(r=>r.task_id));
      const cleaningKeys=new Set(Object.values(this.data.browserCleanup).map(entry=>entry.key));
      const records=[...Object.values(this.data.retired).filter(r=>!currentCancellationProof(this.data,r)&&!r.browser_open_pending&&!cleaningKeys.has(r.key)&&(discardPendingPublications||!this.data.completionOutbox[r.key])),...Object.values(this.data.controls).filter(c=>!ongoingTasks.has(c.task_id)&&['STOPPED','DONE'].includes(c.state)&&!Object.values(this.data.retired).some(r=>r.task_id===c.task_id)).map(c=>({...c,state:'STOPPED'}))];
      if(!records.length)return;
      const clean=discardTerminalHistorySnapshot({...this.store.snapshot(),lifecycle:this.data},records,true);
      // Keep in-flight resource cleanup in memory only; save() still discards
      // all terminal history from disk.
      for(const [key,r]of Object.entries(this.data.retired))if(!records.includes(r))clean.lifecycle.retired[key]=structuredClone(r);
      await this.save(clean.lifecycle,records);
      for(const record of records){const key=keyFor(record);if(!this.data.requests[key]&&!this.data.retired[key]){this.history.audits.delete(key);this.history.legacy.delete(key);this.integrityFaults.delete(key);}}
    });
  }
  async ingest(path,body,currentControl=null,{resumeFrom=null,admissionGuard=null}={}) {
    return this.serial(async()=>{
      if(admissionGuard&&!admissionGuard())return null;
      const key=keyFor(body), old=this.data.requests[key];
      const retired=this.data.retired[key];
      const currentWatch=resumeFrom&&retired&&['STOPPED','CANCELLED'].includes(retired.state)
        &&runningTaskControl(currentControl,retired)&&currentControl.control_epoch>retired.control_epoch
        &&canonicalUrl(body.conversation_url)===canonicalUrl(currentControl.conversation_url)&&!!canonicalUrl(body.conversation_url)
        &&!retired.browser_open_pending;
      const appRetry=retired?.state==='CANCELLED'&&['CREATE_REQUESTED','SEND_REQUESTED'].includes(body.client_status)
        &&body.dispatch_attempt>retired.dispatch_attempt&&runningTaskControl(currentControl,retired);
      if(retired&&!currentWatch&&!appRetry) return structuredClone(retired);
      if(appRetry)body={...appInput(body),server_control_epoch:currentControl.control_epoch};
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
      const next=structuredClone(this.data),resources=[];let r=initialRecord(path,body);
      if(resumeFrom)r.resumed_dispatch_fingerprint=digest(inputFingerprint(resumeFrom));
      // Commit new request and its current authority together. Discovery must
      // never persist an orphan control while waiting for a dispatch to arrive.
      if(currentControl&&keyFor(currentControl)===key) {
        const prior=next.controls[r.task_id];
        if(!prior||currentControl.control_epoch>prior.control_epoch)next.controls[r.task_id]=structuredClone(currentControl);
        else if(currentControl.control_epoch===prior.control_epoch
          &&(currentControl.state!==prior.state||keyFor(currentControl)!==keyFor(prior)))throw new Error('control epoch conflict');
      }
      nextRequestGeneration=Math.max(nextRequestGeneration+1,Date.now());r.run_generation=nextRequestGeneration;
      // Remote lifecycle projections cannot grant a new browser entry. Only
      // locally accepted app input/control may establish this pending authority.
      r.browser_entry=null;
      r.browser_cleanup_displacement=null;
      if(retired){delete next.retired[key];r.run_generation=(retired.run_generation||0)+1;r.revision=(retired.revision||0)+1;
        if(currentWatch)r.pre_submit_proof=null;
        else if(!appRetry)r.pre_submit_proof=structuredClone(retired.pre_submit_proof);
        if(currentWatch){
          delete next.completionOutbox[key];
          for(const entry of Object.values(next.browserCleanup))if(entry.key===key)resources.push(structuredClone(entry));
          for(const kind of ['browserCleanup','browserCleanupOutcomes','browserCleanupUnresolved'])
            for(const [id,entry]of Object.entries(next[kind]||{}))if(entry.key===key||entry.entry?.key===key)delete next[kind][id];
        }}
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
      const currentUrl=canonicalUrl(control?.conversation_url);
      if(currentUrl&&runningTaskControl(control,r)&&!retiredState(r)) {
        r.conversation_url=currentUrl;r.state='ATTACHING';r.intent=null;
        r.browser_entry={control_epoch:r.control_epoch,conversation_url:currentUrl};
      } else if(explicitColdResume||r.conversation_url&&runningTaskControl(control,r)&&r.control_state==='RUNNING'
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
      for(const entry of resources)this.resourceCleanups.set(entry.target_id,entry);
      return structuredClone(next.retired[key]||r);
    });
  }
  async invalidateControl(key) {
    return this.serial(async()=>{
      const old=this.data.requests[key];
      if(!old||retiredState(old)||!['RUNNING','WAITING_USER_INTERVENTION'].includes(old.control_state))return null;
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
      if(!old){if(fence||this.data.retired[key])return null;throw new Error('unknown request');}
      if(fence&&(!this.matches(key,fence)||!['RUNNING','WAITING_USER_INTERVENTION'].includes(old.control_state)||['COMMITTED','COMPLETED','CANCELLED'].includes(old.state))) return null;
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
    return this.matches(key,t)&&observingTaskControl(this.control(r.task_id),r)&&['RUNNING','WAITING_USER_INTERVENTION'].includes(r.control_state)
      &&!['STOPPED','PAUSED','COMMITTED','COMPLETED','CANCELLED'].includes(r.state);
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
      if(this.independentResource(entry)) {
        // A terminal disposal has one current attempt only; it cannot become future work.
        if(['CLOSED','ABSENT'].includes(outcome.status))this.resourceCleanups.delete(entry.target_id);
        return true;
      }
      if(JSON.stringify(this.data.browserCleanup[entry.target_id])!==JSON.stringify(entry))return false;
      const next=structuredClone(this.data);
      const observed={target_id:entry.target_id,status:outcome.status,
        observed_at_ms:outcome.observed_at_ms,reason:outcome.reason,
        ...(entry.kind==='DISPLACED'?{kind:entry.kind,key:entry.key,browser_generation:entry.browser_generation,run_generation:entry.run_generation}:{}),
        ...(outcome.error?{error:String(outcome.error).slice(0,300)}:{})};
      next.browserCleanupOutcomes??={};next.browserCleanupOutcomes[entry.target_id]=observed;
      if(next.retired[entry.key])next.retired[entry.key].browser_cleanup=observed;
      if(['CLOSED','ABSENT'].includes(outcome.status))delete next.browserCleanup[entry.target_id];
      await this.save(next);
      if(['CLOSED','ABSENT'].includes(outcome.status))this.resourceCleanups.delete(entry.target_id);
      return true;
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
        // Fresh current authority also discards legacy PAUSED predecessors.
        // Startup alone cannot infer this from a cached control snapshot.
        const displaced=c.state==='RUNNING'?Object.values(this.data.requests).filter(r=>r.task_id===c.task_id&&r.state==='PAUSED'&&keyFor(r)!==keyFor(c)):[];
        if(displaced.length){
          const next=structuredClone(this.data);
          for(const r of displaced)queueTargetCleanup(next,r);
          await this.save(next,displaced);return true;
        }
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
      let next=structuredClone(this.data);const discarded=[];
      if(c.state==='RUNNING'&&canonicalUrl(c.conversation_url)&&next.retired[keyFor(c)]) {
        next=discardTerminalHistorySnapshot({lifecycle:next},[next.retired[keyFor(c)]],true).lifecycle;
        // The incoming current command sets fresh state; old cleanup/projection
        // bookkeeping does not decide whether this explicit entry may run.
      }
      // Only current nonterminal requests participate in concurrency decisions.
      const successorCreation=!!prior&&c.state==='RUNNING'&&!next.requests[keyFor(c)]&&!next.retired[keyFor(c)]
        &&(prior.state!=='RUNNING'||keyFor(prior)!==keyFor(c));
      next.controls[c.task_id]=structuredClone(c);
      if(['STOPPED','DONE'].includes(c.state))for(const retired of Object.values(next.retired))if(retired.task_id===c.task_id) {
        retired.control_epoch=c.control_epoch;retired.control_state=c.state;retired.state='STOPPED';retired.stop_status='NOT_REQUESTED';
      }
      const sameRunningIdentity=prior&&['RUNNING','WAITING_USER_INTERVENTION'].includes(prior.state)&&['RUNNING','WAITING_USER_INTERVENTION'].includes(c.state)
        &&prior.turn_id===c.turn_id&&prior.request_id===c.request_id;
      for(const [key,old] of Object.entries(next.requests)) {
        if(old.task_id!==c.task_id||(['COMMITTED','COMPLETED'].includes(old.state)&&!['STOPPED','DONE'].includes(c.state))
          ||(old.state==='CANCELLED'&&!(c.state==='STOPPED'&&old.request_id===c.request_id&&old.turn_id===c.turn_id)))continue;
        const matching=old.request_id===c.request_id&&old.turn_id===c.turn_id;
        let state=old.state;
        if(c.state==='STOPPED'||c.state==='DONE') state=['COMMITTED','COMPLETED'].includes(old.state)?'COMPLETED':'STOPPED';
        else if(!['RUNNING','WAITING_USER_INTERVENTION'].includes(c.state)||!matching) state='PAUSED';
        const preserveGeneration=sameRunningIdentity&&matching&&['RUNNING','WAITING_USER_INTERVENTION'].includes(old.control_state);
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
        if(c.state==='RUNNING'&&!matching){queueTargetCleanup(next,r);discarded.push(r);}
        if(retiredState(r))retireRecord(next,r);
      else next.outbox[key]={revision:r.revision,path:r.path,body:projection(r)};
      }
      await this.save(next,discarded);
      return true;
    });
  }
  async flushCurrent(transport,key) {
    if(!transport||!this.canPublish(key))return;
    const pending=this.data.outbox[key];
    if(!pending)return;
    return this.#publish(pending.path,async()=>{
      const item=this.data.outbox[key];
      if(!item||!this.canPublish(key))return;
      if(!canonicalUrl(item.body.conversation_url)){
        await this.serial(async()=>{const next=structuredClone(this.data);if(next.outbox[key]?.revision===item.revision)delete next.outbox[key];await this.save(next);});
        return;
      }
      let remote;
      try{remote=await transport.read(item.path);}catch(error){throw projectionError(error,'read');}
      if(!this.data.requests[key]||this.data.outbox[key]?.revision!==item.revision||!this.canPublish(key))return;
      if(remote?.request_id!==item.body.request_id||remote?.turn_id!==item.body.turn_id||remote?.task_id!==item.body.task_id)
        throw new Error('Drive projection identity conflict');
      if(Number(remote.dispatch_attempt)!==Number(item.body.dispatch_attempt))throw new Error('Drive projection attempt conflict');
      // Only newly observed URLs cross the app boundary. No READY/COMPLETED/error business projection.
      if(canonicalUrl(item.body.conversation_url)&&remote.conversation_url!==item.body.conversation_url) {
        const body={...remote,conversation_url:item.body.conversation_url};
        try{await transport.write(item.path,body,{expected:remote});}catch(error){throw projectionError(error,'write');}
      }
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
  async flush(transport,{awaitTerminal=false}={}) {
    const publish=async()=>{
      for(const event of [...this.data.events]) {
        if(!this.data.retired[event.key]&&!Object.values(this.data.retired).some(r=>keyFor(r)===keyFor(event)))await this.store.recordEvent('CONVERSATION_TRANSITION',event,{generation:event.server_generation,
          signalId:event.request_id,turnId:event.turn_id,conversationUrl:event.conversation_id?'https://chatgpt.com/c/'+event.conversation_id:null,status:event.new_state});
        await this.serial(async()=>{
          const next=structuredClone(this.data);next.events=next.events.filter(e=>e.event_id!==event.event_id);await this.save(next);
        });
      }
      if(!transport)return;
      for(const key of Object.keys(this.data.outbox))await this.flushCurrent(transport,key);
    };
    const p=(this.flushTail||Promise.resolve()).then(publish,publish);
    this.flushTail=p.catch(()=>{});
    await p;await this.forgetTerminalHistory({discardPendingPublications:true});
    return;
  }
}
