import { randomUUID } from 'node:crypto';
import { keyFor } from './lifecycle.mjs';

const conflict=error=>error?.http_status===412||/\b412\b/.test(String(error?.message||''));
const valid=claim=>claim&&typeof claim.node==='string'&&claim.node&&typeof claim.token==='string'&&claim.token
  &&Number.isSafeInteger(claim.control_epoch)&&claim.control_epoch>0;

// Only live execution authority is shared. No completed identity, retry queue or
// task history survives release. A heartbeat timeout never transfers a claim.
export class ExecutionClaims {
  constructor({transport,node}) {this.transport=transport;this.node=node;this.local=new Map();}
  localOwns(key,token=null) {const claim=this.local.get(key);return !!claim&&(!token||claim.token===token);}
  current(key) {return this.local.get(key)||null;}
  forget(key,token=null) {if(this.localOwns(key,token))this.local.delete(key);}
  async mutate(change) {
    for(let attempt=0;attempt<4;attempt++) {
      const current=await this.transport.readCluster();
      if(!current||current.schema!=='selfrun-cluster-state-v1')throw new Error('invalid cluster authority');
      const next=structuredClone(current),result=change(next,current);
      if(!result.write)return result.value;
      try {await this.transport.writeCluster(next,{expected:current});return result.value;}
      catch(error){if(!conflict(error)||attempt===3)throw error;}
    }
  }
  async acquire(identity,{createAllowed=false}={}) {
    const key=keyFor(identity),epoch=identity.control_epoch;
    if(!Number.isSafeInteger(epoch)||epoch<1)throw new Error('claim requires current control epoch');
    const token=randomUUID();
    const result=await this.mutate(next=>{
      next.execution_claims??={};const prior=next.execution_claims[key];
      const displaced=[];
      for(const [other,claim]of Object.entries(next.execution_claims)) {
        if(other===key)continue;
        let task;try {[task]=JSON.parse(other);}catch {throw new Error('invalid execution key');}
        if(task!==identity.task_id)continue;
        if(!valid(claim))throw new Error('invalid execution claim');
        if(claim.control_epoch>=epoch)return {write:false,value:null};
        displaced.push(other);
      }
      if(prior) {
        if(!valid(prior))throw new Error('invalid execution claim');
        if(prior.node!==this.node||prior.control_epoch>epoch)return {write:false,value:null};
        if(prior.control_epoch===epoch&&!displaced.length)return {write:false,value:{...prior,created:false}};
        // A new app epoch on the same node fences a delayed old release.
      } else if(!createAllowed)return {write:false,value:null};
      for(const other of displaced)delete next.execution_claims[other];
      const claim={node:this.node,token,control_epoch:epoch};next.execution_claims[key]=claim;
      return {write:true,value:{...claim,created:!prior}};
    });
    if(result)this.local.set(key,{node:result.node,token:result.token,control_epoch:result.control_epoch});
    return result;
  }
  async verify(identity) {
    const key=keyFor(identity),body=await this.transport.readCluster(),claim=body?.execution_claims?.[key];
    if(!body||body.schema!=='selfrun-cluster-state-v1')throw new Error('invalid cluster authority');
    if(!claim)return 'MISSING';
    if(!valid(claim))throw new Error('invalid execution claim');
    const local=this.local.get(key);
    if(claim.node!==this.node) {this.local.delete(key);return 'FOREIGN';}
    if(local&&local.token!==claim.token) {this.local.delete(key);return 'REPLACED';}
    this.local.set(key,structuredClone(claim));return claim.control_epoch>identity.control_epoch?'AHEAD':'OWNED';
  }
  async release(key,token) {
    this.forget(key,token);
    return this.mutate(next=>{
      const current=next.execution_claims?.[key];
      if(current?.node!==this.node||current?.token!==token)return {write:false,value:false};
      delete next.execution_claims[key];
      if(!Object.keys(next.execution_claims).length)delete next.execution_claims;
      return {write:true,value:true};
    });
  }
  async releaseControl(control) {
    if(!['STOPPED','DONE'].includes(control?.state))return false;
    const body=await this.transport.readCluster(),remove=new Map();
    if(!body||body.schema!=='selfrun-cluster-state-v1')throw new Error('invalid cluster authority');
    for(const [key,claim]of Object.entries(body.execution_claims||{})) {
      if(!valid(claim))throw new Error('invalid execution claim');
      if(JSON.parse(key)[0]===control.task_id&&claim.node===this.node&&claim.control_epoch<control.control_epoch)remove.set(key,claim.token);
    }
    if(!remove.size)return false;
    return this.mutate(next=>{
      let changed=false;
      for(const [key,token]of remove) {
        if(next.execution_claims?.[key]?.node!==this.node||next.execution_claims[key].token!==token)continue;
        delete next.execution_claims[key];this.forget(key,token);changed=true;
      }
      if(next.execution_claims&&!Object.keys(next.execution_claims).length)delete next.execution_claims;
      return {write:changed,value:changed};
    });
  }
  async publish(summary) {
    return this.mutate(next=>{Object.assign(next,summary);return {write:true,value:true};});
  }
  async reconcile({readControl}) {
    const body=await this.transport.readCluster(),remove=new Map();
    for(const [key,claim]of Object.entries(body?.execution_claims||{})) {
      if(!valid(claim))throw new Error('invalid execution claim');
      let identity;try {const [task_id,turn_id,request_id]=JSON.parse(key);identity={task_id,turn_id,request_id};}catch {throw new Error('invalid execution key');}
      let control;try {control=await readControl(identity.task_id);}catch {continue;}
      if(control?.schema==='selfrun-task-control-v1'&&control.task_id===identity.task_id&&control.turn_id&&control.request_id
        &&['RUNNING','WAITING_USER_INTERVENTION','PAUSED','STOPPED','DONE','RESUME_REQUESTED','RESUME_STOPPED_REQUESTED'].includes(control.state)
        &&Number.isSafeInteger(control.control_epoch)
        &&control.control_epoch>claim.control_epoch
        &&(['STOPPED','DONE'].includes(control.state)||keyFor(control)!==key))remove.set(key,claim);
    }
    if(remove.size)await this.mutate(next=>{
      let changed=false;
      for(const [key,claim]of remove)if(next.execution_claims?.[key]?.token===claim.token
        &&next.execution_claims[key].node===claim.node) {
        delete next.execution_claims[key];this.forget(key,claim.token);changed=true;
      }
      if(next.execution_claims&&!Object.keys(next.execution_claims).length)delete next.execution_claims;
      return {write:changed,value:changed};
    });
    return Object.entries(body?.execution_claims||{}).some(([key,claim])=>claim.node===this.node&&!remove.has(key));
  }
}

// Shared by the real server and connected fixtures. Ownership is mandatory in
// this wiring; a missing CAS transport never falls back to unfenced admission.
export function wireExecutionClaims({cluster,controller,transport}) {
  if(!cluster.executionClaims||typeof cluster.transport.readCluster!=='function'
    ||typeof cluster.transport.writeCluster!=='function')throw new Error('execution ownership requires cluster CAS');
  controller.executionClaims=cluster.executionClaims;
  // Discovery wakes the watcher without granting a browser effect or retaining
  // a task history. Admission still revalidates the exact current claim/control.
  let remoteOwned=false;
  cluster.onRefresh=async()=>{remoteOwned=await cluster.executionClaims.reconcile({
    readControl:task=>transport.read('__SELFRUN_CONTROL__'+task+'.json'),
  });};
  return ()=>cluster.canDispatch()||controller.hasCurrentExecutions()||remoteOwned;
}
