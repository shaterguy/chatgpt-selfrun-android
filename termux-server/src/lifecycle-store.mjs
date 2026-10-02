import { readLifecycleHistory, migrateLegacy } from './lifecycle-migration.mjs';
import { audit, canonicalUrl, initialRecord, keyFor, projection, transition, restoreObserver } from './lifecycle.mjs';

// A single durable snapshot owns both current records and the publication outboxes.
// Audit/Drive writes are projections; neither can make an uncommitted transition authoritative.
export class LifecycleStore {
  constructor(stateStore) {
    this.store=stateStore;
    this.data=structuredClone(stateStore.snapshot().lifecycle||{schema:1,requests:{},controls:{},events:[],outbox:{}});
    this.tail=Promise.resolve();this.history={legacy:new Map(),audits:new Map()};this.integrityFaults=new Set();
  }
  async initialize(prompt) {
    if(this.initPromise)return this.initPromise;
    this.initPromise=(async()=>{
      this.history=await readLifecycleHistory(this.store.config?.eventsFile);
      this.legacyPrompt=prompt;
      const next=structuredClone(this.data);let restored=false;
      for(const [key,record] of Object.entries(next.requests)) {
        const value=restoreObserver(record,next.controls[record.task_id]);
        if(value!==record){next.requests[key]=value;restored=true;}
      }
      if(restored)await this.save(next);
      for(const r of this.records())if((this.history.audits.get(r.key)||0)>r.revision)this.integrityFaults.add(r.key);
    })();
    return this.initPromise;
  }
  serial(fn) {
    const p=this.tail.then(fn,fn);
    this.tail=p.catch(()=>{});
    return p;
  }
  get(key) {return structuredClone(this.data.requests[key]||null);}
  records() {return Object.values(this.data.requests).map(r=>structuredClone(r));}
  control(task) {return structuredClone(this.data.controls[task]||null);}
  async save(next) {
    const records=Object.values(next.requests);
    const active=records.filter(r=>r.control_state==='RUNNING'&&!['COMPLETED','CANCELLED','STOPPED','PAUSED'].includes(r.state));
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
      const incoming=canonicalUrl(body.conversation_url);
      if(incoming&&this.records().some(r=>r.key!==key&&r.conversation_url===incoming&&!['COMPLETED','CANCELLED'].includes(r.state)))
        throw new Error('canonical conversation is already owned by another request');
      if(old) {
        if(body.dispatch_attempt<old.dispatch_attempt) return structuredClone(old);
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
        r.body={...r.body,...body};
        if(['CANCELLED','SUPERSEDED'].includes(body.client_status)&&r.state!=='CANCELLED') {
          const previous=r.state;
          r=transition(r,'CANCELLED','CLIENT_CANCELLED_INGRESS',{run_generation:r.run_generation+1});
          next.requests[key]=r;next.events.push(audit(r,previous,r.reason));
        }
        next.outbox[key]={revision:r.revision,path,body:projection(r)};
        await this.save(next);
        return structuredClone(r);
      }
      const next=structuredClone(this.data);let r=restoreObserver(migrateLegacy(initialRecord(path,body),this.history,this.legacyPrompt||''),next.controls[body.task_id]);
      const control=next.controls[r.task_id];
      if(control&&control.control_epoch>=r.control_epoch&&!['COMMITTED','COMPLETED','CANCELLED'].includes(r.state)) {
        const matching=control.turn_id===r.turn_id&&control.request_id===r.request_id;
        const state=['STOPPED','DONE'].includes(control.state)?'STOPPED':control.state!=='RUNNING'||!matching?'PAUSED':r.state;
        const previous=r.state;
        r=transition(r,state,'INGEST_CONTROL_RECONCILED',{control_epoch:control.control_epoch,control_state:control.state,run_generation:r.run_generation+1});
        next.events.push(audit(r,previous,r.reason));
      }
      next.requests[key]=r;
      next.outbox[key]={revision:r.revision,path,body:projection(r)};
      await this.save(next);
      return structuredClone(r);
    });
  }
  async move(key,state,reason,patch={},fence=null) {
    return this.serial(async()=>{
      const old=this.data.requests[key];
      if(!old) throw new Error('unknown request');
      if(fence&&(!this.matches(key,fence)||old.control_state!=='RUNNING'||['COMMITTED','COMPLETED','CANCELLED'].includes(old.state))) return null;
      const url=canonicalUrl(patch.conversation_url||old.conversation_url);
      if(url&&Object.values(this.data.requests).some(r=>r.key!==key&&r.conversation_url===url&&!['COMPLETED','CANCELLED'].includes(r.state)))
        throw new Error('canonical conversation is already owned by another request');
      const r=transition(old,state,reason,patch);
      const next=structuredClone(this.data);
      next.requests[key]=r;
      next.events.push(audit(r,old.state,reason));
      next.outbox[key]={revision:r.revision,path:r.path,body:projection(r)};
      await this.save(next);
      return structuredClone(r);
    });
  }
  token(key) {
    const r=this.data.requests[key];
    return {run:r.run_generation,epoch:r.control_epoch,browser:r.browser_generation};
  }
  matches(key,t) {
    const r=this.data.requests[key];
    return !!r&&r.run_generation===t.run&&r.browser_generation===t.browser;
  }
  current(key,t) {
    const r=this.data.requests[key];
    return this.matches(key,t)&&r.control_state==='RUNNING'
      &&!['STOPPED','PAUSED','COMMITTED','COMPLETED','CANCELLED'].includes(r.state);
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
        next.outbox[key]={revision:r.revision,path:r.path,body:projection(r)};
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
