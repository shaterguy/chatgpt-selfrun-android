import { canonicalUrl, digest, keyFor } from './lifecycle.mjs';
import { retiredState } from './retired-requests.mjs';
const consumed=r=>['COMMITTED','COMPLETED'].includes(r?.state);

// Terminal and displaced cleanup metadata never re-enters dispatch, submission
// or monitor execution; disposing a tab does not commit a business Result.
export class TerminalBrowserCleanup {
  constructor({repository,chromium,stateStore,available,busy,idleReleaseAllowed=available}) {
    Object.assign(this,{repository,chromium,stateStore,available,busy,idleReleaseAllowed});
    this.running=null;this.requested=false;this.nextAttempt=0;this.controls=new Map();
    chromium?.setLifecycleGuards?.({
      canCloseTarget:(id,target)=>{
        const entry=this.repository.cleanupEntry(id);
        return !!entry&&this.canClose(entry,target);
      },
      canRelease:()=>this.canRelease(),
      // Preserve exact journal addressing until an in-memory final disposal ends.
      activeTargetIds:()=>[...new Set([...this.repository.records().filter(r=>!retiredState(r)).map(r=>r.target_id).filter(Boolean),...this.repository.resourceCleanups.keys()])],
    });
  }
  currentControls() {
    for(const task of this.controls.keys())if(!this.repository.cleanupEntries().some(entry=>entry.task_id===task))this.controls.delete(task);
    const current=new Map(Object.entries(this.repository.data.controls));
    for(const [task,control]of this.controls)if(!current.has(task)||control.control_epoch>=current.get(task).control_epoch)current.set(task,control);
    return current;
  }
  observeControl(control) {
    this.currentControls();
    if(!this.repository.cleanupEntries().some(entry=>entry.task_id===control.task_id))return;
    const prior=this.controls.get(control.task_id);
    if(!prior||control.control_epoch>=prior.control_epoch)this.controls.set(control.task_id,structuredClone(control));
  }
  canClose(entry,target=null,{ignoreAvailability=false}={}) {
    if(!ignoreAvailability&&!this.available())return false;
    if(target&&entry.conversation_digest&&digest(canonicalUrl(target.url))!==entry.conversation_digest
      &&!(target.id===entry.target_id&&this.chromium?.isOwnedBootstrapTarget?.(target,entry.conversation_digest)===true))return false;
    const data=this.repository.data,marker=data.retired[entry.key],candidate=this.currentControls().get(entry.task_id),control=this.repository.controlQuarantined(candidate)?null:candidate;
    const source=data.requests[entry.key],d=source?.browser_cleanup_displacement;
    const displaced=entry.kind==='DISPLACED'&&source?.state==='PAUSED'&&source.target_owned===true
      &&source.target_id===entry.target_id&&source.browser_generation===entry.browser_generation
      &&d?.target_id===entry.target_id&&d.browser_generation===entry.browser_generation&&d.run_generation===entry.run_generation
      &&d.control_epoch===entry.control_epoch&&d.successor_key===entry.successor_key
      &&control?.control_epoch>=entry.control_epoch&&keyFor(control||{})!==entry.key&&entry.key!==entry.successor_key;
    const resource=this.repository.independentResource(entry);
    if((!retiredState(marker)&&!displaced&&!resource)||JSON.stringify(this.repository.cleanupEntry(entry.target_id))!==JSON.stringify(entry))return false;
    const conflicts=body=>body.target_id===entry.target_id||body.lifecycle_record?.target_id===entry.target_id
      ||(entry.conversation_digest&&body.conversation_url
        &&digest(canonicalUrl(body.conversation_url))===entry.conversation_digest);
    // A known old conversation can be disposed independently of the new watch.
    // A preparation target without a conversation retains the newer-control fence.
    const consumedResource=consumed(marker)||resource&&['COMMITTED','COMPLETED'].includes(entry.terminal_state);
    const independentCleanup=consumedResource||displaced||resource&&!!entry.conversation_digest;
    // A normal same-request app retry refresh is not authority for a new target.
    // Only a locally proven unsent cancellation may finish its owned cleanup.
    const cancelledRefresh=body=>marker?.state==='CANCELLED'&&marker.never_submitted===true
      &&marker.pre_submit_proof?.schema===1&&!marker.conversation_digest
      &&body?.state==='RUNNING'&&keyFor(body)===entry.key&&!body.conversation_url;
    if(independentCleanup) {
      // A successor owns its own resources, not every target belonging to this task.
      for(const c of this.currentControls().values())if(!this.repository.controlQuarantined(c)&&!['STOPPED','DONE'].includes(c.state)
        &&!consumed(this.repository.get(keyFor(c)))&&!(consumedResource&&keyFor(c)===entry.key)&&conflicts(c))return false;
    } else if(control&&control.control_epoch>entry.control_epoch&&!['STOPPED','DONE'].includes(control.state)&&!cancelledRefresh(control))return false;
    // HTTP acceptance is durable before the lifecycle control catches up.
    for(const body of Object.values(this.stateStore.snapshot().httpIngress?.files||{})) {
      // Accepted HTTP history is inert after its identity was displaced. A newer
      // explicit old-request control still fences the close before application.
      if(displaced&&keyFor(body)===entry.key&&(
        body.schema==='selfrun-task-control-v1'&&body.control_epoch<=entry.control_epoch
        ||body.schema==='selfrun-server-dispatch-v1'&&body.dispatch_attempt<=source.dispatch_attempt
          &&Number(body.server_control_epoch||0)<=entry.control_epoch&&Number(body.lifecycle_record?.control_epoch||0)<=entry.control_epoch))continue;
      if(body.schema==='selfrun-task-control-v1') {
        if(this.repository.controlQuarantined(body))continue;
        if(independentCleanup) {
          if(!['STOPPED','DONE'].includes(body.state)&&!consumed(this.repository.get(keyFor(body)))&&conflicts(body))return false;
        } else if(body.task_id===entry.task_id&&body.control_epoch>entry.control_epoch&&!['STOPPED','DONE'].includes(body.state)&&!cancelledRefresh(body))return false;
      } else {
        const owner=this.repository.get(keyFor(body));
        const pending=!retiredState(owner)||(!consumed(owner)&&Number(body.dispatch_attempt)>Number(owner.dispatch_attempt));
        if(pending&&(conflicts(body)||(!independentCleanup&&body.task_id===entry.task_id)))return false;
      }
    }
    return !this.repository.records().some(r=>!retiredState(r)&&(!displaced||r.key!==entry.key)&&(r.target_id===entry.target_id
      ||(entry.conversation_digest&&r.conversation_url&&digest(canonicalUrl(r.conversation_url))===entry.conversation_digest)));
  }
  canQuarantine(entry,proof) {
    if(!this.available()||!proof||proof.schema!==1||proof.adapter!=='extension'||proof.target_id!==entry.target_id
      ||!['UNADDRESSABLE_NAMESPACE','OWNER_SESSION_CHANGED'].includes(proof.reason))return false;
    const data=this.repository.data,marker=data.retired[entry.key];
    if(!retiredState(marker)||marker.browser_open_pending||marker.key!==entry.key
      ||!['task_id','turn_id','request_id'].every(k=>marker[k]&&marker[k]===entry[k])
      ||JSON.stringify(data.browserCleanup[entry.target_id])!==JSON.stringify(entry))return false;
    const conflicts=body=>keyFor(body)===entry.key||body.target_id===entry.target_id
      ||body.lifecycle_record?.target_id===entry.target_id||(entry.conversation_digest&&body.conversation_url
        &&digest(canonicalUrl(body.conversation_url))===entry.conversation_digest);
    if(this.repository.records().some(r=>!retiredState(r)&&conflicts(r)))return false;
    for(const body of this.currentControls().values())if(!this.repository.controlQuarantined(body)&&body.state==='RUNNING'&&conflicts(body)
      &&(!consumed(marker)||keyFor(body)!==entry.key)&&Number(body.control_epoch)>Number(marker.control_epoch))return false;
    for(const body of Object.values(this.stateStore.snapshot().httpIngress?.files||{})) {
      if(!conflicts(body))continue;
      if(body.schema==='selfrun-task-control-v1') {
        if(this.repository.controlQuarantined(body))continue;
        if(body.state==='RUNNING'&&(!consumed(marker)||keyFor(body)!==entry.key)
          &&Number(body.control_epoch)>Number(marker.control_epoch))return false;
      } else if(keyFor(body)!==entry.key||!consumed(marker)&&Number(body.dispatch_attempt)>Number(marker.dispatch_attempt))return false;
    }
    return JSON.stringify(this.chromium?.cleanupDisposition?.(entry.target_id))===JSON.stringify(proof);
  }
  canRelease() {
    if(!this.idleReleaseAllowed()||this.busy()||this.repository.records().some(r=>!retiredState(r)))return false;
    for(const c of this.currentControls().values()) {
      if(this.repository.controlQuarantined(c))continue;
      const marker=this.repository.get(keyFor(c));
      if(!['STOPPED','DONE'].includes(c.state)&&(!retiredState(marker)||(!consumed(marker)&&c.control_epoch>marker.control_epoch)))return false;
    }
    for(const body of Object.values(this.stateStore.snapshot().httpIngress?.files||{})) {
      const marker=this.repository.get(keyFor(body));
      if(body.schema==='selfrun-task-control-v1') {
        if(this.repository.controlQuarantined(body))continue;
        if(!['STOPPED','DONE'].includes(body.state)&&(!retiredState(marker)||(!consumed(marker)&&body.control_epoch>marker.control_epoch)))return false;
      } else if(!retiredState(marker)||(!consumed(marker)&&Number(body.dispatch_attempt)>Number(marker.dispatch_attempt)))return false;
    }
    return true;
  }
  run({force=true}={}) {
    if(!this.chromium?.closeOwnedTarget&&!this.chromium?.discardOwnedTarget) {
      for(const [id,entry]of this.repository.resourceCleanups)if(entry.terminal_state)this.repository.resourceCleanups.delete(id);
      this.currentControls();return Promise.resolve();
    }
    if(!this.available()&&!this.idleReleaseAllowed()&&!this.repository.cleanupEntries().some(entry=>entry.terminal_state))return Promise.resolve();
    if(!force&&Date.now()<this.nextAttempt)return this.running||Promise.resolve();
    this.requested=true;
    if(this.running)return this.running;
    this.running=(async()=>{
      do {
        this.requested=false;
        let quarantined=false,resourceConfirmed=false;
        for(const entry of this.repository.cleanupEntries()) {
          if(!this.available()&&!entry.terminal_state)continue;
          let disposition;
          try{disposition=!this.available()?null:this.chromium?.inspectCleanupDisposition
            ?await this.chromium.inspectCleanupDisposition(entry.target_id)
            :this.chromium?.cleanupDisposition?.(entry.target_id);}catch{disposition=null;}
          if(this.canQuarantine(entry,disposition)) {
            quarantined=await this.repository.quarantineTargetCleanup(entry,disposition,
              ()=>this.canQuarantine(entry,disposition))||quarantined;
            continue;
          }
          let result;
          if(!this.chromium?.closeOwnedTarget)result={reason:'unavailable'};
          else if(!this.canClose(entry,null,{ignoreAvailability:!!entry.terminal_state}))result={reason:'guard_rejected'};
          else try {
            result=await this.chromium.closeOwnedTarget(entry.target_id,{caller:!!entry.terminal_state,guard:target=>this.canClose(entry,target,{ignoreAvailability:!!entry.terminal_state})});
          } catch(error) {
            result={reason:'close_error',error:String(error?.message||error).slice(0,300)};
          }
          if(entry.terminal_state&&(result?.closed||result?.absent))try{await this.chromium?.discardOwnedTarget?.(entry.target_id,{guard:()=>this.canClose(entry,null,{ignoreAvailability:true}),remote:this.available()});}
          catch{/* This one finalization is consumed even if local or remote disposal failed. */}
          const status=result?.closed===true?'CLOSED':result?.absent===true?'ABSENT':'PENDING';
          resourceConfirmed||=status==='CLOSED'||status==='ABSENT';
          await this.repository.recordTargetCleanup(entry,{status,observed_at_ms:Date.now(),
            reason:String(result?.reason||(status==='CLOSED'?'closed':status==='ABSENT'?'already_absent':'close_unconfirmed')).slice(0,300),
            ...(result?.error?{error:result.error}:{})});
        }
        if(this.canRelease())await this.chromium.releaseIfUnused?.({guard:()=>this.canRelease()});
      } while(this.requested);
    })().catch(error=>{
      console.error(JSON.stringify({event:'TERMINAL_BROWSER_CLEANUP_ERROR',error:String(error?.message||error).slice(0,300)}));
    }).finally(()=>{this.currentControls();this.running=null;this.nextAttempt=Date.now()+60000;});
    return this.running;
  }
}
