import { canonicalUrl, digest, keyFor } from './lifecycle.mjs';
import { retiredState } from './retired-requests.mjs';
const consumed=r=>['COMMITTED','COMPLETED'].includes(r?.state);

// Cleanup-only metadata never re-enters dispatch, submission or monitor execution.
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
    if(target&&entry.conversation_digest&&digest(canonicalUrl(target.url))!==entry.conversation_digest)return false;
    const data=this.repository.data,marker=data.retired[entry.key],control=data.controls[entry.task_id];
    if(!retiredState(marker)||JSON.stringify(data.browserCleanup[entry.target_id])!==JSON.stringify(entry))return false;
    if(control&&control.control_epoch>entry.control_epoch&&!['STOPPED','DONE'].includes(control.state)
      &&!(consumed(marker)&&keyFor(control)===marker.key))return false;
    // HTTP acceptance is durable before the lifecycle control catches up.
    for(const body of Object.values(this.stateStore.snapshot().httpIngress?.files||{})) {
      if(body.schema==='selfrun-task-control-v1') {
        if(body.task_id===entry.task_id&&body.control_epoch>entry.control_epoch&&!['STOPPED','DONE'].includes(body.state)
          &&!(consumed(marker)&&keyFor(body)===marker.key))return false;
      } else {
        const owner=this.repository.get(keyFor(body));
        const pending=!retiredState(owner)||(!consumed(owner)&&Number(body.dispatch_attempt)>Number(owner.dispatch_attempt));
        if(pending&&(body.task_id===entry.task_id||(entry.conversation_digest&&body.conversation_url
          &&digest(canonicalUrl(body.conversation_url))===entry.conversation_digest)))return false;
      }
    }
    return !this.repository.records().some(r=>!retiredState(r)&&(r.target_id===entry.target_id
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
          if(!this.canClose(entry))continue;
          const result=await this.chromium.closeOwnedTarget(entry.target_id,{guard:target=>this.canClose(entry,target)});
          if(result?.closed||result?.absent)await this.repository.clearTargetCleanup(entry);
        }
        if(this.canRelease())await this.chromium.releaseIfUnused?.({guard:()=>this.canRelease()});
      } while(this.requested);
    })().catch(error=>{
      console.error(JSON.stringify({event:'TERMINAL_BROWSER_CLEANUP_ERROR',error:String(error?.message||error).slice(0,300)}));
    }).finally(()=>{this.running=null;this.nextAttempt=Date.now()+60000;});
    return this.running;
  }
}
