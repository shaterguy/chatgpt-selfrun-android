import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './helpers/lifecycle-fixture.mjs';
import { cleanupStoppedSnapshot } from '../src/retired-requests.mjs';
const options=(f,overrides={})=>({allStopped:true,cutoffMs:1000,expectedControlEpochs:{[f.body.task_id]:2},...overrides});
async function stopped(){
 const f=fixture();await f.existing();await f.stop();const s=f.store.snapshot();
 const marker=s.lifecycle.retired[JSON.stringify([f.body.task_id,f.body.turn_id,f.body.request_id])];
 // Restore an old-format stopped record with server-owned churn after the approval cutoff.
 s.lifecycle.requests[marker.key]={...marker,body:{...f.body,created_at_ms:500},updated_at:5000,conversation_url:f.probe.url,intent:{prompt:'private input'},retry_at:9999};
 s.lifecycle.retired={};s.lifecycle.events=[{task_id:f.body.task_id,turn_id:f.body.turn_id,request_id:f.body.request_id}];
 s.lifecycle.outbox[marker.key]={path:f.path,body:f.body};s.httpIngress={files:{[f.path]:f.body},signals:{'old-signal':{path:f.path,task_id:f.body.task_id,request_id:f.body.request_id}}};
 return {f,s};
}
test('offline cleanup keeps only inert identity and signal dedup markers without mutating source',async()=>{
 const {f,s}=await stopped(),original=structuredClone(s);
 const result=cleanupStoppedSnapshot(s,options(f));
 assert.deepEqual(s,original);assert.deepEqual(result.lifecycle.requests,{});assert.deepEqual(result.lifecycle.outbox,{});
 assert.deepEqual(result.lifecycle.events,[]);assert.deepEqual(result.httpIngress.files,{});
 assert.deepEqual(result.httpIngress.signals,{'old-signal':true});assert.equal(result.activeCount,0);
 assert.equal(Object.values(result.lifecycle.retired)[0].intent,undefined);
 assert.equal(Object.values(result.lifecycle.retired)[0].conversation_url,undefined);
});
test('cleanup requires explicit approval and an unchanged epoch map',async()=>{
 const {f,s}=await stopped();assert.throws(()=>cleanupStoppedSnapshot(s),/explicit/);
 assert.throws(()=>cleanupStoppedSnapshot(s,options(f,{expectedControlEpochs:{}})),/control changed/);
});
test('cleanup rejects new app command timestamps but accepts server publication churn',async()=>{
 for(const field of ['created_at_ms','send_requested_at_ms','cancelled_at_ms']){
  const {f,s}=await stopped();Object.values(s.lifecycle.requests)[0].body[field]=1001;
  assert.throws(()=>cleanupStoppedSnapshot(s,options(f)),/app input changed/);
 }
 const {f,s}=await stopped();s.lifecycle.controls[f.body.task_id].updated_at_ms=1001;
 assert.throws(()=>cleanupStoppedSnapshot(s,options(f)),/control changed/);
});
test('completed history may have stale RUNNING control; genuine RUNNING and PAUSED work blocks cleanup',async()=>{
 const {f,s}=await stopped();const record=Object.values(s.lifecycle.requests)[0];
 record.state='COMPLETED';s.lifecycle.controls[f.body.task_id].state='RUNNING';
 assert.deepEqual(cleanupStoppedSnapshot(s,options(f)).lifecycle.requests,{});
 for(const state of ['RUNNING','PAUSED']){
  record.state=state==='RUNNING'?'OBSERVING':'PAUSED';s.lifecycle.controls[f.body.task_id].state=state;
  assert.throws(()=>cleanupStoppedSnapshot(s,options(f)),/nonterminal/);
 }
});
test('cleanup rejects newly registered or untracked HTTP work before erasing ingress',async()=>{
 const {f,s}=await stopped();
 s.httpIngress.files.newControl={schema:'selfrun-task-control-v1',task_id:'NEW',turn_id:'NEW:1',request_id:'NEW:1-request',state:'RUNNING',control_epoch:1,updated_at_ms:1001};
 assert.throws(()=>cleanupStoppedSnapshot(s,options(f)),/HTTP/);
 delete s.httpIngress.files.newControl;
 s.httpIngress.files[f.path]={...f.body,created_at_ms:1001};
 assert.throws(()=>cleanupStoppedSnapshot(s,options(f)),/app input changed/);
 s.httpIngress.files[f.path]={...f.body,task_id:'UNTRACKED',turn_id:'UNTRACKED:1',request_id:'UNTRACKED:1-request',created_at_ms:500};
 assert.throws(()=>cleanupStoppedSnapshot(s,options(f)),/untracked HTTP/);
});
test('stale STOP metadata cannot authorize cleanup of a newer nonterminal record',async()=>{
 const {f,s}=await stopped();const record=Object.values(s.lifecycle.requests)[0];
 record.state='OBSERVING';record.control_state='RUNNING';record.control_epoch=3;
 assert.throws(()=>cleanupStoppedSnapshot(s,options(f)),/nonterminal/);
});
test('orphan RUNNING or PAUSED control without terminal evidence blocks cleanup',()=>{
 for(const state of ['RUNNING','PAUSED']){
  const snapshot={lifecycle:{schema:1,requests:{},retired:{},events:[],outbox:{},controls:{T:{task_id:'T',turn_id:'U',request_id:'R',state,control_epoch:1}}}};
  assert.throws(()=>cleanupStoppedSnapshot(snapshot,{allStopped:true,cutoffMs:1000,expectedControlEpochs:{T:1}}),/orphan/);
 }
});
test('newer RUNNING control cannot be discarded using an older stopped marker even before cutoff',async()=>{
 const {f,s}=await stopped();const record=Object.values(s.lifecycle.requests)[0];record.control_epoch=2;
 s.lifecycle.controls[f.body.task_id]={...s.lifecycle.controls[f.body.task_id],state:'RUNNING',control_epoch:3,updated_at_ms:500,conversation_url:f.probe.url};
 assert.throws(()=>cleanupStoppedSnapshot(s,options(f,{expectedControlEpochs:{[f.body.task_id]:3}})),/newer/);
});
test('cleanup rejects removal of an expected control as well as additional controls',async()=>{
 const {f,s}=await stopped();delete s.lifecycle.controls[f.body.task_id];
 assert.throws(()=>cleanupStoppedSnapshot(s,options(f)),/control.*changed/);
});
