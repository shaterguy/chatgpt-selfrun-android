import { canonicalUrl, digest, keyFor } from './lifecycle.mjs';
import { retiredState } from './retired-requests.mjs';
const consumed=r=>['COMMITTED','COMPLETED'].includes(r?.state);

// Terminal and displaced cleanup metadata never re-enters dispatch, submission
// or monitor execution; disposing a tab does not commit a business Result.
export class TerminalBrowserCleanup {
  constructor({repository,chromium,stateStore,available,busy}) {
    Object.assign(this,{repository,chromium,stateStore,available,busy});
    this.running=null;this.requested=false;this.nextAttempt=0;
    chromium?.setLifecycleGuards?.({
      canCloseTarget:(id,target)=>{
        const entry=this.repository.data.browserCleanup[id];
        return !!entry&&this.canClose(entry,target);
      },
      canRelease:()=>this.canRelease(),
    });
  }
  canClose(entry,target=null) {
    if(!this.available())return false;
    if(target&&entry.conversation_digest&&digest(canonicalUrl(target.url))!==entry.conversation_digest
      &&!(target.id===entry.target_id&&this.chromium?.isOwnedBootstrapTarget?.(target,entry.conversation_digest)===true))return false;
    const data=this.repository.data,marker=data.retired[entry.key],control=data.controls[entry.task_id];
    const source=data.requests[entry.key],d=source?.browser_cleanup_displacement;
    const displaced=entry.kind==='DISPLACED'&&source?.state==='PAUSED'&&source.target_owned===true
      &&source.target_id===entry.target_id&&source.browser_generation===entry.browser_generation
      &&d?.target_id===entry.target_id&&d.browser_generation===entry.browser_generation&&d.run_generation===entry.run_generation
      &&d.control_epoch===entry.control_epoch&&d.successor_key===entry.successor_key
      &&control?.control_epoch>=entry.control_epoch&&keyFor(control||{})!==entry.key&&entry.key!==entry.successor_key;
    if((!retiredState(marker)&&!displaced)||JSON.stringify(data.browserCleanup[entry.target_id])!==JSON.stringify(entry))return false;
    const conflicts=body=>body.target_id===entry.target_id||body.lifecycle_record?.target_id===entry.target_id
      ||(entry.conversation_digest&&body.conversation_url
        &&digest(canonicalUrl(body.conversation_url))===entry.conversation_digest);
    const independentCleanup=consumed(marker)||displaced;
    // A normal same-request app retry refresh is not authority for a new target.
    // Only a locally proven unsent cancellation may finish its owned cleanup.
    const cancelledRefresh=body=>marker?.state==='CANCELLED'&&marker.never_submitted===true
      &&marker.pre_submit_proof?.schema===1&&!marker.conversation_digest
      &&body?.state==='RUNNING'&&keyFor(body)===entry.key&&!body.conversation_url;
    if(independentCleanup) {
      // A successor owns its own resources, not every target belonging to this task.
      for(const c of Object.values(data.controls))if(!['STOPPED','DONE'].includes(c.state)
        &&!consumed(this.repository.get(keyFor(c)))&&conflicts(c))return false;
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
  canRelease() {
    if(!this.available()||this.busy()||this.repository.records().some(r=>!retiredState(r)))return false;
    for(const c of Object.values(this.repository.data.controls)) {
      const marker=this.repository.get(keyFor(c));
      if(!['STOPPED','DONE'].includes(c.state)&&(!retiredState(marker)||(!consumed(marker)&&c.control_epoch>marker.control_epoch)))return false;
    }
    for(const body of Object.values(this.stateStore.snapshot().httpIngress?.files||{})) {
      const marker=this.repository.get(keyFor(body));
      if(body.schema==='selfrun-task-control-v1') {
        if(!['STOPPED','DONE'].includes(body.state)&&(!retiredState(marker)||(!consumed(marker)&&body.control_epoch>marker.control_epoch)))return false;
      } else if(!retiredState(marker)||(!consumed(marker)&&Number(body.dispatch_attempt)>Number(marker.dispatch_attempt)))return false;
    }
    return true;
  }
  run({force=true}={}) {
    if(!this.chromium?.closeOwnedTarget||!this.available())return Promise.resolve();
    if(!force&&Date.now()<this.nextAttempt)return this.running||Promise.resolve();
    this.requested=true;
    if(this.running)return this.running;
    this.running=(async()=>{
      do {
        this.requested=false;
        for(const entry of Object.values(this.repository.data.browserCleanup)) {
          let result;
          if(!this.canClose(entry))result={reason:'guard_rejected'};
          else try {
            result=await this.chromium.closeOwnedTarget(entry.target_id,{guard:target=>this.canClose(entry,target)});
          } catch(error) {
            result={reason:'close_error',error:String(error?.message||error).slice(0,300)};
          }
          const status=result?.closed===true?'CLOSED':result?.absent===true?'ABSENT':'PENDING';
          await this.repository.recordTargetCleanup(entry,{status,observed_at_ms:Date.now(),
            reason:String(result?.reason||(status==='CLOSED'?'closed':status==='ABSENT'?'already_absent':'close_unconfirmed')).slice(0,300),
            ...(result?.error?{error:result.error}:{})});
        }
        if(this.canRelease())await this.chromium.releaseIfUnused?.({guard:()=>this.canRelease()});
      } while(this.requested);
    })().catch(error=>{
      console.error(JSON.stringify({event:'TERMINAL_BROWSER_CLEANUP_ERROR',error:String(error?.message||error).slice(0,300)}));
    }).finally(()=>{this.running=null;this.nextAttempt=Date.now()+60000;});
    return this.running;
  }
}
