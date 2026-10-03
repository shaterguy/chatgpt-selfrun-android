import { canonicalUrl, digest, keyFor } from './lifecycle.mjs';
// Terminal identities prevent replay; they contain no browser, prompt or retry state.
export const retiredState=r=>['STOPPED','CANCELLED','COMMITTED','COMPLETED'].includes(r?.state);
export const serverFields=new Set(['lifecycle_record','lifecycle_state','lifecycle_revision','server_status','server_generation',
  'request_generation','browser_generation','conversation_url','recovery_count','recovery_attempt_id','post_correlation','post_outcome',
  'server_error','server_error_class','server_control_state','server_control_epoch','resume_retry_count','resume_retry_at_ms',
  'result_state','response_cursor','response_status','stop_status','updated_at_ms']);
export const appInput=body=>Object.fromEntries(Object.entries(body).filter(([key])=>!serverFields.has(key)));
const stable=value=>Array.isArray(value)?value.map(stable):value&&typeof value==='object'?
  Object.fromEntries(Object.keys(value).sort().map(key=>[key,stable(value[key])])):value;
export const inputFingerprint=body=>JSON.stringify(stable(appInput(body)));
export function retireRecord(data,record) {
  const marker=Object.fromEntries(['key','path','task_id','turn_id','request_id','dispatch_attempt','state','revision',
    'run_generation','browser_generation','control_epoch','control_state','updated_at','stop_status'].map(k=>[k,record[k]]));
  if(record.conversation_url)marker.conversation_digest=digest(canonicalUrl(record.conversation_url));
  if(marker.state==='STOPPED')marker.stop_status=marker.stop_status==='CONFIRMED'?'CONFIRMED':'UNCONFIRMED';
  marker.server_ingress=record.body?.server_ingress||record.server_ingress||'DRIVE';
  marker.never_submitted=record.never_submitted===true;
  marker.retired=true;
  data.retired??={};data.retired[record.key]=marker;
  delete data.requests[record.key];delete data.outbox[record.key];
  data.events=data.events.filter(event=>event.task_id!==record.task_id||event.turn_id!==record.turn_id||event.request_id!==record.request_id);
  return marker;
}
// Offline operator operation only. The caller must quarantine the original state/events first.
// The exact epoch map is an optimistic concurrency guard against newly arrived app commands.
export function cleanupStoppedSnapshot(snapshot,{allStopped=false,expectedControlEpochs={},cutoffMs}={}) {
  if(allStopped!==true||!Number.isSafeInteger(cutoffMs))throw new Error('explicit allStopped and cutoffMs required');
  const next=structuredClone(snapshot),data=next.lifecycle||{schema:1,requests:{},controls:{},events:[],outbox:{}};
  if(JSON.stringify(Object.keys(expectedControlEpochs).sort())!==JSON.stringify(Object.keys(data.controls||{}).sort()))
    throw new Error('control changed after cleanup approval: control set differs');
  for(const [task,c] of Object.entries(data.controls||{})) {
    if(expectedControlEpochs[task]!==c.control_epoch||Number(c.updated_at_ms||0)>cutoffMs)
      throw new Error('control changed after cleanup approval: '+task);
    const record=data.requests[keyFor(c)]||data.retired?.[keyFor(c)];
    if(!['STOPPED','DONE'].includes(c.state)) {
      if(!retiredState(record))throw new Error('orphan or nonterminal app control remains active: '+task);
      if(c.control_epoch>Number(record.control_epoch||0))throw new Error('newer app resume control remains active: '+task);
    }
  }
  for(const r of Object.values(data.requests||{})) {
    for(const field of ['created_at_ms','send_requested_at_ms','cancelled_at_ms'])
      if(Number(r.body?.[field]||0)>cutoffMs)throw new Error('app input changed after cleanup approval');
    const c=data.controls[r.task_id];
    if(!retiredState(r)&&(!['STOPPED','DONE'].includes(c?.state)||c.control_epoch<r.control_epoch))throw new Error('nonterminal request lacks stopped app control');
    retireRecord(data,{...r,state:retiredState(r)?r.state:'STOPPED',control_state:c?.state||r.control_state,
      control_epoch:Math.max(r.control_epoch||0,c?.control_epoch||0),run_generation:(r.run_generation||0)+1});
  }
  for(const body of Object.values(next.httpIngress?.files||{})) {
    for(const field of ['created_at_ms','send_requested_at_ms','cancelled_at_ms'])
      if(Number(body[field]||0)>cutoffMs)throw new Error('app input changed after cleanup approval');
    const marker=data.retired?.[keyFor(body)];
    if(body.schema==='selfrun-task-control-v1') {
      if(expectedControlEpochs[body.task_id]!==body.control_epoch||Number(body.updated_at_ms||0)>cutoffMs
        ||(!retiredState(marker)&&!['STOPPED','DONE'].includes(body.state)))throw new Error('pending HTTP control changed or remains active');
    } else if(!retiredState(marker))throw new Error('untracked HTTP input cannot be cleaned');
  }
  for(const [task,c] of Object.entries(data.controls))data.controls[task]=Object.fromEntries(
    ['schema','task_id','turn_id','request_id','state','control_epoch'].map(k=>[k,c[k]]));
  data.events=[];data.outbox={};next.lifecycle=data;
  if(next.httpIngress)next.httpIngress={files:{},signals:Object.fromEntries(Object.keys(next.httpIngress.signals||{}).map(id=>[id,true]))};
  Object.assign(next,{status:'IDLE',activeCount:0,activeDispatches:[],activeSignal:null,activeTargetId:null,
    conversationUrl:null,lastActivityAt:null,lastSignalId:null,lastError:null,acceptedAt:null});
  return next;
}
