import { createHash, randomUUID } from 'node:crypto';

export const STATES = Object.freeze({
  DISCOVERED: ['PREPARING','ATTACHING','POST_UNCERTAIN','BLOCKED'],
  PREPARING: ['PREPARED','BLOCKED'],
  PREPARED: ['PREPARING','POST_PENDING','ATTACHING','BLOCKED'],
  ATTACHING: ['OBSERVING','POST_UNCERTAIN','BLOCKED'],
  POST_PENDING: ['ATTACHING','POST_UNCERTAIN','OBSERVING','BLOCKED'],
  POST_UNCERTAIN: ['RECONCILING','POST_PENDING','RECOVERY_POST','ATTACHING','BLOCKED'],
  RECONCILING: ['ATTACHING','POST_UNCERTAIN','POST_PENDING','RECOVERY_POST','OBSERVING','BLOCKED'],
  OBSERVING: ['STALLED','WAIT_RESULT','RECOVERY_POST','ATTACHING','BLOCKED'],
  WAIT_RESULT: ['OBSERVING','STALLED','RECOVERY_POST','ATTACHING','BLOCKED'],
  STALLED: ['VERIFY_RESULT','OBSERVING','ATTACHING','BLOCKED'],
  VERIFY_RESULT: ['ATTACHING','VERIFY_CONVERSATION','BLOCKED'],
  VERIFY_CONVERSATION: ['VERIFY_CURSOR','ATTACHING','BLOCKED'],
  VERIFY_CURSOR: ['ATTACHING','OBSERVING','QUIESCING','VERIFY_INPUT','BLOCKED'],
  QUIESCING: ['VERIFY_INPUT','OBSERVING','ATTACHING','BLOCKED'],
  VERIFY_INPUT: ['ATTACHING','RECOVERY_POST','POST_UNCERTAIN','OBSERVING','BLOCKED'],
  RECOVERY_POST: ['ATTACHING','POST_UNCERTAIN','OBSERVING','BLOCKED'],
  COMMITTED: ['COMPLETED'],
  COMPLETED: [],
  STOPPED: ['ATTACHING','PREPARING','POST_UNCERTAIN','BLOCKED'],
  PAUSED: ['ATTACHING','PREPARING','POST_UNCERTAIN','BLOCKED'],
  BLOCKED: ['OBSERVING','ATTACHING','PREPARING','RECONCILING','POST_UNCERTAIN'],
  CANCELLED: ['ATTACHING','PREPARING','POST_UNCERTAIN','BLOCKED'],
});
export const keyFor = b => JSON.stringify([b.task_id,b.turn_id,b.request_id]);
export const digest = value => createHash('sha256').update(String(value)).digest('hex');
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
  return Object.fromEntries(['assistantCount','assistantTextLength','assistantMessageId','responseTurnId','responseTextLength','responseFingerprint','streaming'].map(k=>[k,p[k]??null]));
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
  const turn=Number(String(body.turn_id).slice(String(body.task_id).length+6));
  if(!String(body.turn_id).startsWith(body.task_id+':turn:')||!Number.isSafeInteger(turn)||turn<1)throw new Error('invalid turn identity');
  if(body.conversation_url&&!canonicalUrl(body.conversation_url))throw new Error('invalid canonical conversation URL');
  if(!Number.isSafeInteger(body.dispatch_attempt)||body.dispatch_attempt<1) throw new Error('dispatch attempt invalid');
  if(body.lifecycle_record?.schema===1) {
    const stored=structuredClone(body.lifecycle_record);
    if(stored.key!==keyFor(body)||stored.task_id!==body.task_id||stored.turn_id!==body.turn_id||stored.request_id!==body.request_id
      ||stored.conversation_url!==canonicalUrl(body.conversation_url)||!STATES[stored.state])throw new Error('durable Drive identity mismatch');
    delete stored.schema;
    return {...stored,path,body:structuredClone(body),dispatch_attempt:body.dispatch_attempt};
  }
  const url=canonicalUrl(body.conversation_url);
  const imported=body.lifecycle_state;
  const state=STATES[imported]?imported:url?'ATTACHING':body.server_status==='READY_TO_SUBMIT'?'PREPARED':'DISCOVERED';
  return {key:keyFor(body),path,body:structuredClone(body),task_id:body.task_id,turn_id:body.turn_id,request_id:body.request_id,
    dispatch_attempt:body.dispatch_attempt,state,revision:0,run_generation:0,browser_generation:0,control_epoch:Number(body.server_control_epoch||0),
    control_state:body.server_control_state||'UNKNOWN',conversation_url:url,conversation_id:url.split('/c/')[1]||null,
    recovery_count:Number(body.recovery_count||0),attach_attempts:Number(body.resume_retry_count||0),failure_epoch:Number(body.server_control_epoch||0),retry_at:0,
    intent:null,cursor:null,last_progress_at:now,result_state:'UNKNOWN',error:null,
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
  if(['VERIFY_RESULT','VERIFY_CONVERSATION','VERIFY_CURSOR','QUIESCING','VERIFY_INPUT','RECOVERY_POST'].includes(next)) r.recovery_stage=next;
  if(r.conversation_url) {
    r.conversation_url=canonicalUrl(r.conversation_url);
    r.conversation_id=r.conversation_url.split('/c/')[1];
    if(['DISCOVERED','PREPARING','PREPARED'].includes(next)) throw new Error('owned conversation cannot be recreated');
  }
  if(['POST_PENDING','RECOVERY_POST','POST_UNCERTAIN','RECONCILING'].includes(next)&&!r.intent) throw new Error('submission state requires durable intent');
  if(next==='COMMITTED'||next==='COMPLETED') r.result_state='COMMITTED';
  return r;
}
export function projection(r) {
  const statuses={PREPARED:'READY_TO_SUBMIT',COMMITTED:'COMPLETED',COMPLETED:'COMPLETED',CANCELLED:'CANCELLED',
    BLOCKED:'ERROR',RECOVERY_POST:'RECOVERY_SENDING',POST_UNCERTAIN:'RECOVERY_SENDING',RECONCILING:'RECOVERY_SENDING'};
  const {body,...durable}=r;
  return {...r.body,lifecycle_record:{schema:1,...durable},server_status:statuses[r.state]||(r.conversation_url?'STARTED':'PENDING'),lifecycle_state:r.state,
    lifecycle_revision:r.revision,server_generation:r.server_generation||0,request_generation:r.run_generation,browser_generation:r.browser_generation,
    conversation_url:r.conversation_url,recovery_count:r.recovery_count,recovery_attempt_id:r.intent?.kind==='recovery'?r.intent.id:null,
    post_correlation:r.intent?.id||null,post_outcome:r.intent?.outcome||null,server_error:r.error?.message||'',
    server_error_class:r.error?.code||null,server_control_state:r.control_state,server_control_epoch:r.control_epoch,
    resume_retry_count:r.attach_attempts,resume_retry_at_ms:r.retry_at||0,result_state:r.result_state,
    response_cursor:r.cursor,updated_at_ms:r.updated_at};
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
    control_epoch:record.control_epoch,error_class:record.error?.code||null};
}
