import { createHash, randomUUID } from 'node:crypto';

export const STATES = Object.freeze({
  DISCOVERED: ['PREPARING','ATTACHING','POST_UNCERTAIN','BLOCKED'],
  PREPARING: ['PREPARED','BLOCKED'],
  PREPARED: ['PREPARING','POST_PENDING','ATTACHING','BLOCKED'],
  ATTACHING: ['OBSERVING','POST_UNCERTAIN','BLOCKED'],
  POST_PENDING: ['ATTACHING','POST_UNCERTAIN','OBSERVING','BLOCKED'],
  POST_UNCERTAIN: ['OBSERVING','RECONCILING','POST_PENDING','RECOVERY_POST','ATTACHING','BLOCKED','STALLED'],
  RECONCILING: ['ATTACHING','POST_UNCERTAIN','POST_PENDING','RECOVERY_POST','OBSERVING','BLOCKED','STALLED'],
  OBSERVING: ['RECONCILING','STALLED','WAIT_RESULT','RECOVERY_POST','ATTACHING','BLOCKED'],
  WAIT_RESULT: ['RECONCILING','OBSERVING','STALLED','RECOVERY_POST','ATTACHING','BLOCKED'],
  STALLED: ['VERIFY_RESULT','OBSERVING','ATTACHING','BLOCKED'],
  VERIFY_RESULT: ['ATTACHING','VERIFY_CONVERSATION','BLOCKED'],
  VERIFY_CONVERSATION: ['VERIFY_CURSOR','ATTACHING','BLOCKED'],
  VERIFY_CURSOR: ['ATTACHING','OBSERVING','QUIESCING','VERIFY_INPUT','BLOCKED'],
  QUIESCING: ['VERIFY_INPUT','OBSERVING','ATTACHING','BLOCKED'],
  VERIFY_INPUT: ['ATTACHING','RECOVERY_POST','POST_UNCERTAIN','OBSERVING','BLOCKED'],
  RECOVERY_POST: ['ATTACHING','POST_UNCERTAIN','OBSERVING','BLOCKED','RECONCILING','STALLED'],
  COMMITTED: ['COMPLETED'],
  COMPLETED: [],
  STOPPED: ['ATTACHING','PREPARING','POST_UNCERTAIN','BLOCKED'],
  PAUSED: ['ATTACHING','PREPARING','POST_UNCERTAIN','BLOCKED'],
  BLOCKED: ['OBSERVING','ATTACHING','PREPARING','RECONCILING','POST_UNCERTAIN'],
  CANCELLED: ['ATTACHING','PREPARING','POST_UNCERTAIN','BLOCKED','STOPPED'],
});
export const keyFor = b => JSON.stringify([b.task_id,b.turn_id,b.request_id]);
// Dispatch/server projections are history, not app execution authorization.
export const matchingTaskControl=(c,r)=>!!c&&!!r&&c.schema==='selfrun-task-control-v1'
  &&Number.isSafeInteger(c.control_epoch)&&c.control_epoch>0&&c.control_epoch>=Number(r.control_epoch||0)
  &&['task_id','turn_id','request_id'].every(k=>!!r[k]&&c[k]===r[k]);
export const runningTaskControl=(c,r)=>matchingTaskControl(c,r)&&c.state==='RUNNING';
export const observingTaskControl=(c,r)=>matchingTaskControl(c,r)&&['RUNNING','WAITING_USER_INTERVENTION'].includes(c.state);
export const digest = value => createHash('sha256').update(String(value)).digest('hex');
// Attempt/status/timing are app bookkeeping; the accepted operation stays fixed.
export const preparationInputDigest=body=>digest(JSON.stringify([
  body?.project_url,body?.prompt,body?.profile_operations,body?.result_document_id,
]));
export const provenUnsubmitted=r=>r?.pre_submit_proof?.schema===1
  &&r.pre_submit_proof.input_digest===preparationInputDigest(r.body)
  &&r.intent===null&&r.conversation_url===''&&r.submission_confirmed===false;
export function canonicalUrl(value) {
  try {
    const u=new URL(value);
    if(u.protocol!=='https:'||u.hostname!=='chatgpt.com') return '';
    const parts=u.pathname.split('/').filter(Boolean), i=parts.indexOf('c');
    const id=i<0?'':decodeURIComponent(parts[i+1]||'');
    return /^[A-Za-z0-9_-]{1,160}$/.test(id)&&!id.startsWith('local-chatgpt')?'https://chatgpt.com/c/'+id:'';
  } catch {return '';}
}
export function cursor(p={}) {
  // Raw assistant telemetry remains global for submission/baseline compatibility.
  // New probes supply current-response metrics; old probes and durable cursors
  // keep their existing representation. An explicit scoped null must override an
  // older assistant identity, while an absent/undefined scoped field falls back.
  const progress={...p};
  for(const key of ['assistantCount','assistantTextLength','assistantMessageId']) {
    const scoped='response'+key[0].toUpperCase()+key.slice(1);
    if(p[scoped]!==undefined)progress[key]=p[scoped];
  }
  return Object.fromEntries(['assistantCount','assistantTextLength','assistantMessageId','responseTurnId','responseTextLength','responseFingerprint','streaming'].map(k=>[k,progress[k]??null]));
}
export const sameCursor=(a,b)=>JSON.stringify(cursor(a))===JSON.stringify(cursor(b));
export function classifyAttach(error) {
  if(error?.code) return error.code;
  const s=String(error?.message||error);
  if(/sign.in|auth|unauthorized/i.test(s)) return 'AUTH_REQUIRED';
  if(/websocket.*closed|websocket.*not open|browser.*closed|ECONNREFUSED/i.test(s)) return 'BROWSER_DEAD';
  if(/not found|no longer available|inaccessible/i.test(s)) return 'CONVERSATION_UNAVAILABLE';
  if(/existing conversation|load.*conversation|page error/i.test(s)) return 'CONVERSATION_LOAD_FAILED';
  return 'ATTACH_FAILED';
}
export function initialRecord(path,body,now=Date.now()) {
  for(const k of ['task_id','turn_id','request_id']) if(!body[k]) throw new Error('dispatch '+k+' required');
  if(body.conversation_url&&!canonicalUrl(body.conversation_url))throw new Error('invalid canonical conversation URL');
  if(!Number.isSafeInteger(body.dispatch_attempt)||body.dispatch_attempt<1) throw new Error('dispatch attempt invalid');
  const candidate=body.lifecycle_record;
  const stored=candidate?.schema===1&&candidate.key===keyFor(body)
    &&candidate.task_id===body.task_id&&candidate.turn_id===body.turn_id&&candidate.request_id===body.request_id
    &&candidate.conversation_url===canonicalUrl(body.conversation_url)?candidate:null;
  // Old server projections cannot complete, pause or authorize a current app
  // command. Keep only same-operation effect evidence to prevent replay.
  const saved=stored?.intent;
  const effectRisk=saved&&typeof saved.id==='string'&&saved.id&&['initial','recovery'].includes(saved.kind)
    &&typeof saved.prompt==='string'&&saved.hash===digest(saved.prompt)
    &&(saved.released===true||Number.isSafeInteger(saved.sends)&&saved.sends>0||saved.receipt?.released===true);
  const intent=effectRisk?{...structuredClone(saved),outcome:'UNKNOWN',absence_proof:null,pre_submit_stage:null}:null;
  const url=canonicalUrl(body.conversation_url);
  const state=intent?'POST_UNCERTAIN':url?'ATTACHING':'DISCOVERED';
  return {key:keyFor(body),path,body:structuredClone(body),task_id:body.task_id,turn_id:body.turn_id,request_id:body.request_id,
    dispatch_attempt:body.dispatch_attempt,state,revision:0,run_generation:0,browser_generation:0,control_epoch:0,
    control_state:'UNKNOWN',conversation_url:url,conversation_id:url.split('/c/')[1]||null,
    recovery_count:0,attach_attempts:0,failure_epoch:0,retry_at:0,
    ...(intent&&typeof stored.target_id==='string'?{target_id:stored.target_id,target_owned:false}:{}),
    intent,cursor:null,last_progress_at:now,result_state:'UNKNOWN',error:null,
    ...(candidate||body.lifecycle_state||body.server_status&&body.server_status!=='PENDING'?{pre_submit_proof:null}:{}),
    submission_confirmed:false,response_status:'UNKNOWN',
    created_at:now,updated_at:now};
}
export function transition(record,next,reason,patch={},now=Date.now()) {
  if(!STATES[next]) throw new Error('unknown lifecycle state '+next);
  const terminal=['COMPLETED','CANCELLED'].includes(record.state);
  const universal=!terminal&&['COMMITTED','STOPPED','PAUSED','CANCELLED'].includes(next);
  const recoveryRecheck=['VERIFY_RESULT','VERIFY_CONVERSATION','VERIFY_CURSOR','QUIESCING','VERIFY_INPUT'].includes(record.state)&&['VERIFY_RESULT','STALLED','OBSERVING'].includes(next);
  if(next!==record.state&&!universal&&!recoveryRecheck&&!STATES[record.state]?.includes(next)) throw new Error('invalid lifecycle transition '+record.state+' -> '+next);
  if(record.state==='COMMITTED'&&next!=='COMPLETED'&&next!=='COMMITTED') throw new Error('committed Result is final');
  for(const k of ['key','task_id','turn_id','request_id']) if(k in patch&&patch[k]!==record[k]) throw new Error('request identity is immutable');
  if(patch.control_epoch!==undefined&&patch.control_epoch<record.control_epoch) throw new Error('stale control epoch');
  if(patch.recovery_count!==undefined&&patch.recovery_count<record.recovery_count) throw new Error('recovery count is monotonic');
  if(record.conversation_url&&patch.conversation_url&&canonicalUrl(patch.conversation_url)!==record.conversation_url) throw new Error('canonical conversation ownership conflict');
  const r={...record,...structuredClone(patch),state:next,revision:record.revision+1,updated_at:now,reason};
  // The local intake establishes this; a durable intent revokes it before send.
  if(r.intent||r.conversation_url||r.submission_confirmed
    ||['POST_PENDING','RECOVERY_POST','POST_UNCERTAIN','RECONCILING','OBSERVING','COMMITTED','COMPLETED','STOPPED'].includes(next))
    r.pre_submit_proof=null;
  if(['VERIFY_RESULT','VERIFY_CONVERSATION','VERIFY_CURSOR','QUIESCING','VERIFY_INPUT','RECOVERY_POST'].includes(next)) r.recovery_stage=next;
  if(r.conversation_url) {
    r.conversation_url=canonicalUrl(r.conversation_url);
    r.conversation_id=r.conversation_url.split('/c/')[1];
    if(['DISCOVERED','PREPARING','PREPARED'].includes(next)) throw new Error('owned conversation cannot be recreated');
  }
  if(['POST_PENDING','RECOVERY_POST','POST_UNCERTAIN','RECONCILING'].includes(next)&&!r.intent) throw new Error('submission state requires durable intent');
  if(next==='COMMITTED'||next==='COMPLETED') {r.result_state='COMMITTED';r.error=null;}
  return r;
}
// App-facing output is URL-only; lifecycle and business completion stay local/app-owned.
export function projection(r) {
  return {task_id:r.task_id,turn_id:r.turn_id,request_id:r.request_id,dispatch_attempt:r.dispatch_attempt,
    conversation_url:r.conversation_url};
}
export function newIntent(record,kind,prompt,baseline) {
  return {id:randomUUID(),kind,prompt:String(prompt),hash:digest(prompt),baseline:structuredClone(baseline||{}),outcome:'PENDING',
    sends:0,created_at:Date.now(),message_id:null,transport_settled:false};
}
export function audit(record,previous,reason) {
  return {event_id:record.key+':'+record.revision,timestamp:new Date(record.updated_at).toISOString(),
    task_id:record.task_id,turn_id:record.turn_id,request_id:record.request_id,dispatch_attempt:record.dispatch_attempt,
    server_generation:record.server_generation||0,request_generation:record.run_generation,browser_generation:record.browser_generation,conversation_id:record.conversation_id,
    previous_state:previous,new_state:record.state,revision:record.revision,reason,result_state:record.result_state,
    cursor:record.cursor,post_correlation:record.intent?.id||null,recovery_attempt:record.recovery_count,
    control_epoch:record.control_epoch,error_class:record.error?.code||null,result_recovery:record.result_recovery||null};
}
