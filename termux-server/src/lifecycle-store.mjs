import { readLifecycleHistory, migrateLegacy } from './lifecycle-migration.mjs';
import { appInput, inputFingerprint, retiredState, retireRecord } from './retired-requests.mjs';
import { audit, canonicalUrl, digest, initialRecord, keyFor, projection, transition } from './lifecycle.mjs';

// A single durable snapshot owns both current records and the publication outboxes.
// Audit/Drive writes are projections; neither can make an uncommitted transition authoritative.
export class LifecycleStore {
  constructor(stateStore) {
    this.store=stateStore;
    this.data=structuredClone(stateStore.snapshot().lifecycle||{schema:1,requests:{},controls:{},events:[],outbox:{}});
    this.data.retired??={};
    this.tail=Promise.resolve();this.history={legacy:new Map(),audits:new Map()};this.integrityFaults=new Set();
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
  control(task) {return structuredClone(this.data.controls[task]||null);}
  async save(next) {
    const records=Object.values(next.requests);
    const active=records.filter(r=>r.control_state==='RUNNING'&&!['COMMITTED','COMPLETED','CANCELLED','STOPPED','PAUSED'].includes(r.state));
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
        if(retired.state!=='CANCELLED'||!retired.never_submitted||body.dispatch_attempt<=retired.dispatch_attempt
          ||['CANCELLED','SUPERSEDED'].includes(body.client_status))return structuredClone(retired);
        const control=this.data.controls[body.task_id];
        if(control&&control.state!=='RUNNING')return structuredClone(retired);
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
        if(r.intent&&(r.body.prompt!==body.prompt||JSON.stringify(r.body.profile_operations)!==JSON.stringify(body.profile_operations)))
          throw new Error('accepted request input/profile is immutable');
        r.body={...r.body,...appInput(body)};
        if(['CANCELLED','SUPERSEDED'].includes(body.client_status)&&!['COMMITTED','COMPLETED','CANCELLED'].includes(r.state)) {
          const previous=r.state;
          r=transition(r,'CANCELLED','CLIENT_CANCELLED_INGRESS',{run_generation:r.run_generation+1,
            never_submitted:!r.intent&&!r.conversation_url&&!r.submission_confirmed&&['DISCOVERED','PREPARING','PREPARED'].includes(r.state)});
          next.requests[key]=r;next.events.push(audit(r,previous,r.reason));
        }
        if(retiredState(r))retireRecord(next,r);
        else next.outbox[key]={revision:r.revision,path,body:projection(r)};
        await this.save(next);
        return structuredClone(next.retired[key]||r);
      }
      const next=structuredClone(this.data);let r=initialRecord(path,body);
      if(retired){delete next.retired[key];r.run_generation=(retired.run_generation||0)+1;r.revision=(retired.revision||0)+1;}
      if(['CANCELLED','SUPERSEDED'].includes(body.client_status)&&!retiredState(r)){
        // Missing fields on a cold historical import are not proof that input was never sent.
        r.never_submitted=false;
        r.state='CANCELLED';
      }
      if(!retiredState(r))r=migrateLegacy(r,this.history,this.legacyPrompt||'');
      const control=next.controls[r.task_id];
      if(control&&control.control_epoch>=r.control_epoch&&!['COMMITTED','COMPLETED'].includes(r.state)
        &&(!retiredState(r)||['STOPPED','DONE'].includes(control.state))) {
        const matching=control.turn_id===r.turn_id&&control.request_id===r.request_id;
        const state=['STOPPED','DONE'].includes(control.state)?'STOPPED':control.state!=='RUNNING'||!matching?'PAUSED':r.state;
        const previous=r.state;
        r=transition(r,state,'INGEST_CONTROL_RECONCILED',{control_epoch:control.control_epoch,control_state:control.state,run_generation:r.run_generation+1});
        next.events.push(audit(r,previous,r.reason));
      }
      next.requests[key]=r;
      if(retiredState(r))retireRecord(next,r);
      else next.outbox[key]={revision:r.revision,path,body:projection(r)};
      await this.save(next);
      return structuredClone(next.retired[key]||r);
    });
  }
  async move(key,state,reason,patch={},fence=null) {
    return this.serial(async()=>{
      const old=this.data.requests[key];
      if(!old){if(this.data.retired[key])return null;throw new Error('unknown request');}
      if(fence&&(!this.matches(key,fence)||old.control_state!=='RUNNING'||['COMMITTED','COMPLETED','CANCELLED'].includes(old.state))) return null;
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
    return !!r&&!!t&&r.run_generation===t.run&&r.browser_generation===t.browser;
  }
  current(key,t) {
    const r=this.data.requests[key];
    return this.matches(key,t)&&r.control_state==='RUNNING'
      &&!['STOPPED','PAUSED','COMMITTED','COMPLETED','CANCELLED'].includes(r.state);
  }
  async restoreFromControl(c,body) {
    return this.serial(async()=>{
      const key=keyFor(c),marker=this.data.retired[key],control=this.data.controls[c.task_id];
      const url=canonicalUrl(c.conversation_url);
      if(!marker||c.state!=='RUNNING'||!url||c.control_epoch<=marker.control_epoch
        ||control?.control_epoch!==c.control_epoch||control.state!=='RUNNING'||keyFor(control)!==key)return null;
      const reject=reason=>{throw Object.assign(new Error(reason),{code:'RESUME_REJECTED',reason});};
      if(!body||body.schema!=='selfrun-server-dispatch-v1'||keyFor(body)!==key)reject('RESUME_DISPATCH_IDENTITY_CONFLICT');
      if(marker.conversation_digest&&marker.conversation_digest!==digest(url))reject('RESUME_CONVERSATION_MISMATCH');
      if(!Array.isArray(body.profile_operations)||typeof body.prompt!=='string'||!body.prompt||!body.project_url
        ||!Number.isSafeInteger(body.dispatch_attempt)||body.dispatch_attempt<1)reject('RESUME_INVALID_DISPATCH');
      if(Object.values(this.data.requests).some(r=>r.conversation_url===url))reject('RESUME_CONVERSATION_ALREADY_OWNED');
      const clean={...appInput(body),client_status:'SEND_REQUESTED',conversation_url:url,server_status:'STARTED',server_control_epoch:c.control_epoch};
      const r={...initialRecord(marker.path,clean),run_generation:(marker.run_generation||0)+1,
        browser_generation:(marker.browser_generation||0)+1,revision:(marker.revision||0)+1,control_state:'RUNNING',
        resumed_dispatch_fingerprint:digest(inputFingerprint(body))};
      const next=structuredClone(this.data);delete next.retired[key];next.requests[key]=r;
      await this.save(next);return structuredClone(r);
    });
  }
  async recordResumeRejection(key,epoch,reason) {
    return this.serial(async()=>{
      const marker=this.data.retired[key],c=marker&&this.data.controls[marker.task_id];
      if(!marker||c?.control_epoch!==epoch||c.state!=='RUNNING')return;
      const next=structuredClone(this.data);Object.assign(next.retired[key],{resume_rejected_epoch:epoch,resume_error:reason});
      await this.save(next);
    });
  }
  async recordStopOutcome(key,epoch,status) {
    return this.serial(async()=>{
      const marker=this.data.retired[key],c=marker&&this.data.controls[marker.task_id];
      if(!marker||marker.control_epoch!==epoch||c?.control_epoch!==epoch||c.state!=='STOPPED')return false;
      const next=structuredClone(this.data);next.retired[key].stop_status=status;
      await this.save(next);return true;
    });
  }
  async applyControl(c) {
    return this.serial(async()=>{
      const prior=this.data.controls[c.task_id];
      if(prior&&c.control_epoch<prior.control_epoch) return false;
      if(prior&&c.control_epoch===prior.control_epoch) {
        if(c.state!==prior.state||c.request_id!==prior.request_id||c.turn_id!==prior.turn_id) throw new Error('control epoch conflict');
        return false;
      }
      const next=structuredClone(this.data);
      next.controls[c.task_id]=structuredClone(c);
      const retired=next.retired[keyFor(c)];
      if(retired&&['STOPPED','DONE'].includes(c.state)){
        retired.control_epoch=c.control_epoch;retired.control_state=c.state;
        if(!['COMMITTED','COMPLETED'].includes(retired.state))retired.state='STOPPED';
        retired.stop_status??='UNCONFIRMED';
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
        const r=transition(old,state,'CONTROL_'+c.state,{
          control_epoch:c.control_epoch,control_state:c.state,
          run_generation:preserveGeneration?old.run_generation:old.run_generation+1,
          ...(c.state==='STOPPED'&&matching?{stop_status:'PENDING',stop_control_epoch:c.control_epoch,stop_retry_at:0}:{}),
        });
        next.requests[key]=r;next.events.push(audit(r,old.state,r.reason));
        if(retiredState(r))retireRecord(next,r);
      else next.outbox[key]={revision:r.revision,path:r.path,body:projection(r)};
      }
      await this.save(next);
      return true;
    });
  }
  async flushCurrent(transport,key) {
    if(!transport)return;
    const item=this.data.outbox[key];
    if(!item)return;
    const remote=await transport.read(item.path);
    if(!this.data.requests[key]||this.data.outbox[key]?.revision!==item.revision)return;
    if(remote?.request_id!==item.body.request_id||remote?.turn_id!==item.body.turn_id||remote?.task_id!==item.body.task_id)
      throw new Error('Drive projection identity conflict');
    if(Number(remote.dispatch_attempt)!==Number(item.body.dispatch_attempt))throw new Error('Drive projection attempt conflict');
    const serverKeys=['lifecycle_record','lifecycle_state','lifecycle_revision','server_status','server_generation','browser_generation',
      'conversation_url','recovery_count','recovery_attempt_id','post_correlation','post_outcome','server_error','server_error_class',
      'server_control_state','server_control_epoch','resume_retry_count','resume_retry_at_ms','result_state','response_cursor','response_status','stop_status','updated_at_ms'];
    const body={...remote};
    for(const k of serverKeys)body[k]=item.body[k];
    await transport.write(item.path,body,{expected:remote});
    await this.serial(async()=>{
      const next=structuredClone(this.data);
      if(next.outbox[key]?.revision===item.revision)delete next.outbox[key];
      await this.save(next);
    });
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
      for(const [key,item] of Object.entries(this.data.outbox)) {
        const remote=await transport.read(item.path);
        if(!this.data.requests[key]||this.data.outbox[key]?.revision!==item.revision)continue;
        if(remote?.request_id!==item.body.request_id||remote?.turn_id!==item.body.turn_id||remote?.task_id!==item.body.task_id)throw new Error('Drive projection identity conflict');
        if(Number(remote.dispatch_attempt)!==Number(item.body.dispatch_attempt))throw new Error('Drive projection attempt conflict');
        const serverKeys=['lifecycle_record','lifecycle_state','lifecycle_revision','server_status','server_generation','browser_generation',
          'conversation_url','recovery_count','recovery_attempt_id','post_correlation','post_outcome','server_error','server_error_class',
          'server_control_state','server_control_epoch','resume_retry_count','resume_retry_at_ms','result_state','response_cursor','response_status','stop_status','updated_at_ms'];
        const body={...remote};
        for(const k of serverKeys)body[k]=item.body[k];
        await transport.write(item.path,body,{expected:remote});
        await this.serial(async()=>{
          const next=structuredClone(this.data);
          if(next.outbox[key]?.revision===item.revision)delete next.outbox[key];
          await this.save(next);
        });
      }
    };
    const p=(this.flushTail||Promise.resolve()).then(publish,publish);
    this.flushTail=p.catch(()=>{});
    return p;
  }
}
