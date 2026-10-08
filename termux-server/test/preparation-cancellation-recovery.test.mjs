import test from 'node:test';
import assert from 'node:assert/strict';
import { repairPreparationCancellation, recoveryIdentity as identity } from '../tools/repair-preparation-cancellation.mjs';
import { digest, initialRecord, keyFor, preparationInputDigest } from '../src/lifecycle.mjs';

function fixture(){
  const key=keyFor(identity),now=2000000000000;
  const body={schema:'selfrun-server-dispatch-v1',...identity,dispatch_attempt:1,
    prompt:'private accepted input',project_url:'https://chatgpt.com/g/test/project',profile_operations:[],result_document_id:'RESULT',
    client_status:'CANCELLED',server_status:'ERROR',conversation_url:''};
  body.lifecycle_record={...initialRecord('A1',body),schema:1,state:'BLOCKED',reason:'ATTACH_FAILED',revision:4,
    control_epoch:3,run_generation:2,browser_generation:1,target_id:'failed-owned',target_owned:true,
    error:{code:'ATTACH_FAILED',message:'CDP command timeout: Page.enable'}};
  const control={schema:'selfrun-task-control-v1',...identity,state:'RUNNING',control_epoch:3,updated_at_ms:1791353699563,conversation_url:''};
  const marker={...identity,key,retired:true,state:'CANCELLED',dispatch_attempt:1,revision:5,run_generation:3,browser_generation:1,
    control_epoch:3,control_state:'RUNNING',updated_at:1791353759426,never_submitted:false,path:'A1'};
  const snapshot={generation:551,status:'IDLE',custom:'preserved',lifecycle:{schema:1,requests:{},controls:{[identity.task_id]:control,OTHER:{state:'STOPPED'}},
    retired:{[key]:marker,OTHER:{state:'COMMITTED'}},events:[{unrelated:true}],outbox:{OTHER:{revision:9}},completionOutbox:{OTHER:{revision:9}},browserCleanup:{OTHER:{target_id:'other'}}}};
  const states=[['DISCOVERED','DISCOVERED','INGEST_CONTROL_RECONCILED'],['DISCOVERED','PREPARING','BROWSER_PREPARE'],
    ['PREPARING','PREPARING','BROWSER_TARGET_ACQUIRED'],['PREPARING','BLOCKED','ATTACH_FAILED']];
  const latest={...body,dispatch_attempt:136,client_status:'CREATE_REQUESTED',server_status:'PENDING'};
  delete latest.lifecycle_record;delete latest.conversation_url;
  const raw=JSON.stringify({schema:'selfrun-turn-result-v3',task_id:identity.task_id,turn_id:identity.turn_id,turn:2,document_id:'RESULT',event_id:identity.turn_id+':result',committed:false});
  const evidence={serverQuiesced:true,expectedSnapshotDigest:digest(JSON.stringify(snapshot)),currentControl:structuredClone(control),cancelledDispatch:body,
    dispatches:[body,latest],expectedLatestAttempt:136,history:states.map(([previous_state,new_state,reason],i)=>({...identity,event_id:key+':'+(i+1),
      revision:i+1,dispatch_attempt:1,control_epoch:3,post_correlation:null,conversation_id:null,result_state:'UNKNOWN',recovery_attempt:0,previous_state,new_state,reason})),
    resultReads:[raw,raw],evidenceAt:now,now};
  return {snapshot,evidence,key,latest,raw};
}
function withHold(f){
  const original=structuredClone(f.evidence.currentControl);
  const held={...original,state:'PAUSED',control_epoch:4,reason:'OPERATOR_PREPARATION_RETRY_HOLD',updated_at_ms:f.evidence.now};
  f.evidence.operatorHold={originalControl:original,heldControl:structuredClone(held)};
  f.evidence.currentControl=structuredClone(held);f.snapshot.lifecycle.controls[identity.task_id]=structuredClone(held);
  f.evidence.expectedSnapshotDigest=digest(JSON.stringify(f.snapshot));return f;
}
test('exact incident recovery changes only two target marker fields and leaves evidence untouched',()=>{
  const f=fixture(),original=structuredClone(f);
  const repaired=repairPreparationCancellation(f.snapshot,f.evidence);
  const expected=structuredClone(f.snapshot);
  Object.assign(expected.lifecycle.retired[f.key],{never_submitted:true,pre_submit_proof:{schema:1,input_digest:preparationInputDigest(f.evidence.cancelledDispatch)}});
  assert.deepEqual(repaired,expected);assert.deepEqual(f,original);
});
test('exact bounded operator hold authorizes repair while preserving every CONTROL field',()=>{
  const f=withHold(fixture()),before=structuredClone(f.snapshot);
  const repaired=repairPreparationCancellation(f.snapshot,f.evidence);
  assert.equal(repaired.lifecycle.retired[f.key].never_submitted,true);
  assert.deepEqual(repaired.lifecycle.controls,before.lifecycle.controls);assert.deepEqual(f.snapshot,before);
});
for(const [name,change] of [
  ['hold reason',f=>f.evidence.operatorHold.heldControl.reason='USER_PAUSE'],
  ['hold epoch',f=>f.evidence.operatorHold.heldControl.control_epoch=5],
  ['hold identity',f=>f.evidence.operatorHold.heldControl.request_id='OTHER'],
  ['hold URL',f=>f.evidence.operatorHold.heldControl.conversation_url='https://chatgpt.com/c/owned'],
  ['original hold epoch',f=>f.evidence.operatorHold.originalControl.control_epoch=4],
  ['original hold STOP',f=>f.evidence.operatorHold.originalControl.state='STOPPED'],
  ['hold changed field',f=>f.evidence.operatorHold.heldControl.unexpected='changed'],
  ['remote hold changed',f=>f.evidence.currentControl.control_epoch=5],
  ['remote STOP',f=>f.evidence.currentControl.state='STOPPED'],
  ['local hold changed',f=>f.snapshot.lifecycle.controls[identity.task_id].state='DONE'],
])test('held recovery rejects '+name,()=>{
  const f=withHold(fixture());change(f);f.evidence.expectedSnapshotDigest=digest(JSON.stringify(f.snapshot));
  assert.throws(()=>repairPreparationCancellation(f.snapshot,f.evidence),/recovery rejected/);
});
for(const [name,change] of [
  ['server running',f=>f.evidence.serverQuiesced=false],
  ['stale evidence',f=>f.evidence.evidenceAt-=30001],
  ['changed snapshot',f=>f.snapshot.generation++],
  ['different marker',f=>f.snapshot.lifecycle.retired[f.key].revision++],
  ['STOP control',f=>f.evidence.currentControl.state='STOPPED'],
  ['newer control',f=>f.evidence.currentControl.control_epoch++],
  ['control URL',f=>f.evidence.currentControl.conversation_url='https://chatgpt.com/c/owned'],
  ['missing intent field',f=>delete f.evidence.cancelledDispatch.lifecycle_record.intent],
  ['ambiguous intent',f=>f.evidence.cancelledDispatch.lifecycle_record.intent={outcome:'UNKNOWN',released:true}],
  ['existing URL',f=>f.evidence.cancelledDispatch.lifecycle_record.conversation_url='https://chatgpt.com/c/owned'],
  ['submit confirmation',f=>f.evidence.cancelledDispatch.lifecycle_record.submission_confirmed=true],
  ['A1 committed Result state',f=>f.evidence.cancelledDispatch.lifecycle_record.result_state='COMMITTED'],
  ['A1 recovery history',f=>f.evidence.cancelledDispatch.lifecycle_record.recovery_count=1],
  ['unowned target',f=>f.evidence.cancelledDispatch.lifecycle_record.target_owned=false],
  ['history gap',f=>f.evidence.history.splice(1,1)],
  ['history submission',f=>f.evidence.history[2].post_correlation='intent'],
  ['latest attempt changed',f=>f.evidence.expectedLatestAttempt++],
  ['latest CANCELLED',f=>f.latest.client_status='CANCELLED'],
  ['changed input',f=>f.latest.prompt='different accepted input'],
  ['Result unreadable',f=>f.evidence.resultReads=['bad','bad']],
  ['Result changed',f=>f.evidence.resultReads[1]+=' '],
  ['Result committed',f=>f.evidence.resultReads=f.evidence.resultReads.map(raw=>raw.replace('false','true'))],
  ['Result wrong identity',f=>f.evidence.resultReads=f.evidence.resultReads.map(raw=>raw.replace('RESULT','OTHER'))],
  ...['POST_PENDING','POST_UNCERTAIN','COMMITTED','COMPLETED','OBSERVING','PREPARING','STOPPED'].map(state=>[
    'contradictory inventory '+state,f=>f.latest.lifecycle_record={...f.evidence.cancelledDispatch.lifecycle_record,state,reason:'OTHER_HISTORY'}]),
])test('targeted recovery rejects '+name,()=>{
  const f=fixture();change(f);const before=structuredClone(f.snapshot);
  assert.throws(()=>repairPreparationCancellation(f.snapshot,f.evidence),/recovery rejected/);assert.deepEqual(f.snapshot,before);
});
for(const [name,patch] of [
  ['lifecycle COMMITTED',{lifecycle_state:'COMMITTED'}],
  ['server completed Result',{server_status:'COMPLETED',result_state:'COMMITTED'}],
  ['lifecycle POST_UNCERTAIN',{lifecycle_state:'POST_UNCERTAIN'}],
  ['server STARTED',{server_status:'STARTED'}],
  ['submission confirmed',{submission_confirmed:true}],
  ['Result committed',{result_state:'COMMITTED'}],
  ['submission intent',{intent:{outcome:'UNKNOWN'}}],
  ['completion reason',{server_completion_reason:'RESULT_COMMITTED'}],
  ['READY_TO_SUBMIT',{server_status:'READY_TO_SUBMIT'}],
  ['historical PREPARING projection',{lifecycle_state:'PREPARING'}],
  ['lifecycle revision',{lifecycle_revision:1}],
  ['projected empty URL',{conversation_url:''}],
  ['projected update timestamp',{updated_at_ms:1}],
  ['unknown extra field',{unrecognized_history:true}],
  ['changed model',{model:'changed-model'}],
])test('targeted recovery rejects intermediate top-level '+name,()=>{
  const f=fixture();f.evidence.dispatches.push({...f.latest,dispatch_attempt:2,...patch});
  assert.throws(()=>repairPreparationCancellation(f.snapshot,f.evidence),/recovery rejected/);
});
for(const [name,patch] of [
  ['response confirmation',{response_status:'CONFIRMED'}],
  ['control epoch',{server_control_epoch:4}],
  ['recovery attempt',{recovery_count:1}],
  ['missing A1 inventory',null],
  ['duplicate A1 inventory',{duplicate:true}],
])test('targeted recovery rejects A1 projection '+name,()=>{
  const f=fixture();
  if(patch?.duplicate)f.evidence.dispatches.push(structuredClone(f.evidence.cancelledDispatch));
  else if(patch)Object.assign(f.evidence.cancelledDispatch,patch);
  else f.evidence.dispatches=f.evidence.dispatches.filter(d=>d.dispatch_attempt!==1);
  assert.throws(()=>repairPreparationCancellation(f.snapshot,f.evidence),/recovery rejected/);
});
