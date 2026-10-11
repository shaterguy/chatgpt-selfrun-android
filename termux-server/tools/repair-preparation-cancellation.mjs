import { digest, keyFor, preparationInputDigest, runningTaskControl } from '../src/lifecycle.mjs';
import { isDeepStrictEqual } from 'node:util';
import { serverFields } from '../src/retired-requests.mjs';

// Offline, incident-specific operator helper. No filesystem/network/process side
// effects. Never called by normal ingress or used as a historical migration.
const task='SR-20261007-150030-4JV4X6';
export const recoveryIdentity=Object.freeze({task_id:task,turn_id:task+':turn:2',request_id:task+':turn:2-request'});
const targetKey=keyFor(recoveryIdentity);
const requireProof=(condition,reason)=>{if(!condition)throw new Error('preparation recovery rejected: '+reason);};
const sameIdentity=value=>value&&keyFor(value)===targetKey;
const noSubmission=value=>value&&value.intent===null&&value.conversation_url===''&&value.submission_confirmed===false;
const initialAttemptKeys=new Set(['schema','client_status','server_status','task_id','turn_id','request_id','dispatch_attempt',
  'project_url','prompt','task_mode','mode','model','reasoning','result_document_id','requirement_document_id','task_folder_id',
  'previous_result_document_id','profile_operations','created_at_ms','cancelled_at_ms']);
const operationFields=['project_url','prompt','task_mode','mode','model','reasoning','result_document_id',
  'requirement_document_id','task_folder_id','previous_result_document_id','profile_operations'];
const sameOperation=(a,b)=>operationFields.every(key=>isDeepStrictEqual(a[key],b[key]));
const initialAttempt=value=>['CREATE_REQUESTED','CANCELLED'].includes(value.client_status)&&value.server_status==='PENDING'
  &&Object.keys(value).every(key=>initialAttemptKeys.has(key));

export function repairPreparationCancellation(snapshot,{
  serverQuiesced,expectedSnapshotDigest,currentControl,cancelledDispatch,dispatches,
  expectedLatestAttempt,history,resultReads,evidenceAt,operatorHold,now=Date.now(),
}={}) {
  requireProof(serverQuiesced===true,'server must be quiesced before fresh evidence');
  requireProof(Number.isSafeInteger(evidenceAt)&&evidenceAt<=now&&now-evidenceAt<=30000,'fresh evidence required');
  requireProof(digest(JSON.stringify(snapshot))===expectedSnapshotDigest,'snapshot changed');
  const data=snapshot?.lifecycle,marker=data?.retired?.[targetKey],localControl=data?.controls?.[task];
  requireProof(sameIdentity(marker)&&marker.retired===true&&marker.state==='CANCELLED'
    &&marker.dispatch_attempt===1&&marker.revision===5&&marker.run_generation===3&&marker.browser_generation===1
    &&marker.control_epoch===3&&marker.control_state==='RUNNING'&&marker.updated_at===1791353759426
    &&marker.never_submitted===false&&!marker.conversation_digest&&!marker.conversation_url,'exact false retired marker required');
  requireProof(!data.requests?.[targetKey]&&!data.outbox?.[targetKey]&&!data.completionOutbox?.[targetKey],'request already live or pending publication');
  if(operatorHold) {
    const original=operatorHold.originalControl,held=operatorHold.heldControl;
    const unchanged=value=>Object.fromEntries(Object.entries(value||{}).filter(([key])=>
      !['state','control_epoch','reason','updated_at_ms'].includes(key)));
    requireProof(runningTaskControl(original,marker)&&original.control_epoch===3&&!original.conversation_url
      &&held?.schema==='selfrun-task-control-v1'&&sameIdentity(held)&&held.state==='PAUSED'&&held.control_epoch===4
      &&held.reason==='OPERATOR_PREPARATION_RETRY_HOLD'&&!held.conversation_url
      &&Number.isSafeInteger(original.updated_at_ms)&&Number.isSafeInteger(held.updated_at_ms)
      &&held.updated_at_ms>=original.updated_at_ms&&held.updated_at_ms<=evidenceAt
      &&isDeepStrictEqual(unchanged(original),unchanged(held))
      &&isDeepStrictEqual(currentControl,held)&&isDeepStrictEqual(localControl,held),'exact bounded operator hold differs');
  } else requireProof(runningTaskControl(currentControl,marker)&&currentControl.control_epoch===3&&!currentControl.conversation_url
      &&runningTaskControl(localControl,marker)&&localControl.control_epoch===3&&!localControl.conversation_url
      &&currentControl.updated_at_ms===localControl.updated_at_ms,'fresh current CONTROL differs');
  const a1=cancelledDispatch,r=a1?.lifecycle_record;
  requireProof(a1?.schema==='selfrun-server-dispatch-v1'&&sameIdentity(a1)&&a1.dispatch_attempt===1
    &&a1.client_status==='CANCELLED'&&a1.server_status==='ERROR'&&a1.conversation_url===''
    &&(!Object.hasOwn(a1,'lifecycle_state')||a1.lifecycle_state==='BLOCKED')
    &&(!Object.hasOwn(a1,'lifecycle_revision')||a1.lifecycle_revision===4)
    &&(!Object.hasOwn(a1,'result_state')||a1.result_state==='UNKNOWN')
    &&(!Object.hasOwn(a1,'submission_confirmed')||a1.submission_confirmed===false)
    &&!a1.intent&&!a1.server_completion_reason
    &&r?.schema===1&&sameIdentity(r)&&r.key===targetKey&&r.dispatch_attempt===1
    &&r.state==='BLOCKED'&&r.reason==='ATTACH_FAILED'&&r.revision===4&&r.control_epoch===3
    &&r.result_state==='UNKNOWN'&&r.recovery_count===0
    &&r.run_generation===2&&r.browser_generation===1&&r.target_owned===true&&Boolean(r.target_id)
    &&r.error?.message==='CDP command timeout: Page.enable'&&noSubmission(r),'explicit A1 pre-submit failure proof required');
  // Validate this offline incident's historical dev25 fields. Current server
  // output is URL-only and cannot be used to validate an old projection.
  const expectedA1={server_status:'ERROR',lifecycle_state:r.state,lifecycle_revision:r.revision,
    server_generation:r.server_generation||0,request_generation:r.run_generation,browser_generation:r.browser_generation,
    conversation_url:r.conversation_url,recovery_count:r.recovery_count,recovery_attempt_id:null,
    post_correlation:null,post_outcome:null,server_error:r.error?.message||'',server_error_class:r.error?.code||null,
    server_control_state:r.control_state,server_control_epoch:r.control_epoch,resume_retry_count:r.attach_attempts,
    resume_retry_at_ms:r.retry_at||0,result_state:r.result_state,response_cursor:r.cursor,
    response_status:r.response_status||'UNKNOWN',stop_status:r.stop_status||null,updated_at_ms:r.updated_at};
  requireProof(Object.keys(a1).every(key=>!serverFields.has(key)||['lifecycle_record','server_completion_reason'].includes(key)
    ||isDeepStrictEqual(a1[key],expectedA1[key])),'A1 server projection contradicts preparation record');
  const expected=[['DISCOVERED','DISCOVERED','INGEST_CONTROL_RECONCILED'],['DISCOVERED','PREPARING','BROWSER_PREPARE'],
    ['PREPARING','PREPARING','BROWSER_TARGET_ACQUIRED'],['PREPARING','BLOCKED','ATTACH_FAILED']];
  const audits=(history||[]).map(row=>row.details||row).filter(sameIdentity).toSorted((a,b)=>a.revision-b.revision);
  requireProof(audits.length===4&&audits.every((event,i)=>event.revision===i+1&&event.dispatch_attempt===1
    &&event.control_epoch===3&&event.event_id===targetKey+':'+(i+1)&&!event.post_correlation&&!event.conversation_id
    &&event.result_state==='UNKNOWN'&&event.recovery_attempt===0
    &&JSON.stringify([event.previous_state,event.new_state,event.reason])===JSON.stringify(expected[i])),
    'complete preparation-only history required');
  requireProof(Array.isArray(dispatches)&&dispatches.filter(d=>d?.dispatch_attempt===1).length===1
    &&new Set(dispatches.map(d=>d?.dispatch_attempt)).size===dispatches.length&&dispatches.every(d=>d?.schema==='selfrun-server-dispatch-v1'
    &&sameIdentity(d)&&Number.isSafeInteger(d.dispatch_attempt)&&d.dispatch_attempt>=1&&!d.conversation_url
    &&!d.post_correlation&&!d.post_outcome&&sameOperation(d,a1)
    &&(d.dispatch_attempt===1?isDeepStrictEqual(d,a1):initialAttempt(d))),
    'dispatch inventory contains submission or identity uncertainty');
  const latest=dispatches.toSorted((a,b)=>b.dispatch_attempt-a.dispatch_attempt)[0];
  requireProof(Number.isSafeInteger(expectedLatestAttempt)&&expectedLatestAttempt>1
    &&latest.dispatch_attempt===expectedLatestAttempt&&dispatches.filter(d=>d.dispatch_attempt===expectedLatestAttempt).length===1
    &&latest.client_status==='CREATE_REQUESTED'&&latest.server_status==='PENDING'
    &&Array.isArray(latest.profile_operations)&&typeof latest.prompt==='string'&&latest.prompt&&latest.project_url&&latest.result_document_id
    &&preparationInputDigest(latest)===preparationInputDigest(a1),'latest explicit attempt or accepted input changed');
  requireProof(Array.isArray(resultReads)&&resultReads.length===2&&typeof resultReads[0]==='string'
    &&resultReads[0]===resultReads[1],'exact Result readback required');
  let result;try{result=JSON.parse(resultReads[0].replace(/^\uFEFF/,''));}catch{}
  requireProof(result?.schema==='selfrun-turn-result-v3'&&result.task_id===task&&result.turn_id===recoveryIdentity.turn_id
    &&result.turn===2&&result.document_id===latest.result_document_id&&result.event_id===recoveryIdentity.turn_id+':result'
    &&result.committed===false,'current Result unavailable, mismatched or committed');
  // Change only the exact inert marker. Ordinary ingress must still obtain fresh
  // app CONTROL and a strictly higher attempt, then create the replacement.
  const next=structuredClone(snapshot);
  next.lifecycle.retired[targetKey].never_submitted=true;
  next.lifecycle.retired[targetKey].pre_submit_proof={schema:1,input_digest:preparationInputDigest(a1)};
  return next;
}
