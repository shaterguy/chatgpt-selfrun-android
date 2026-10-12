import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture} from './helpers/lifecycle-fixture.mjs';
import {keyFor,newIntent} from '../src/lifecycle.mjs';

for(const reason of ['UNKNOWN_PRIOR_POST','PRE_SUBMIT_RETRY'])test('fresh app retry after cancelled '+reason+' is admitted even without retained proof',async()=>{
 const f=fixture(); f.body.client_status='CREATE_REQUESTED';
 await f.ingest();
 if(reason==='UNKNOWN_PRIOR_POST'){
   const intent={...newIntent(f.record(),'initial','test input',{}),outcome:'PENDING',released:false};
   await f.controller.repository.move(keyFor(f.body),'POST_PENDING','SUBMISSION_INTENT_DURABLE',{intent});
 } else {
   const intent={...newIntent(f.record(),'initial','test input',{}),outcome:'ABSENT',released:false,absence_proof:'SUBMIT_NOT_CALLED'};
   await f.controller.repository.move(keyFor(f.body),'POST_PENDING','PRE_SUBMIT_RETRY',{intent});
 }
 f.body.client_status='CANCELLED'; await f.ingest();
 assert.equal(f.record().state,'CANCELLED');
 assert.equal(f.record().never_submitted,false);
 f.body={...f.body,client_status:'CREATE_REQUESTED',dispatch_attempt:2};
 f.path='__SELFRUN_DISPATCH__'+f.body.request_id+'__A2.json';
 await f.ingest();
 assert.equal(f.record().retired,undefined);
 assert.equal(f.record().dispatch_attempt,2);
 assert.equal(f.record().state,'PREPARED');
 assert.equal(f.prepares,2);
 assert.equal(f.sends,0);
});

test('old cancelled attempt cannot replay without an explicit higher app attempt',async()=>{
 const f=fixture();f.body.client_status='CREATE_REQUESTED';await f.ingest();
 f.body.client_status='CANCELLED';await f.ingest();
 const prior=f.record();
 f.body.client_status='CREATE_REQUESTED';
 await f.ingest();
 assert.equal(f.record().state,'CANCELLED');
 assert.equal(f.prepares,1);
 assert.equal(f.record().revision,prior.revision);
});

test('app STOPPED is authoritative and prevents stale cancelled retry',async()=>{
 const f=fixture();f.body.client_status='CREATE_REQUESTED';await f.ingest();
 f.body.client_status='CANCELLED';await f.ingest();
 f.control={...f.control,state:'STOPPED',control_epoch:2};await f.controller.control(null,f.control,f.transport);
 f.body={...f.body,client_status:'CREATE_REQUESTED',dispatch_attempt:2};
 f.path='__SELFRUN_DISPATCH__'+f.body.request_id+'__A2.json';
 await f.ingest();
 assert.equal(f.prepares,1);
 assert.equal(f.sends,0);
});

test('app-claimed new attempt after cancellation executes SEND_REQUESTED without stale proof gate',async()=>{
 const f=fixture();f.body.client_status='CREATE_REQUESTED';await f.ingest();
 f.body.client_status='CANCELLED';await f.ingest();
 f.body={...f.body,client_status:'SEND_REQUESTED',dispatch_attempt:2};
 f.path='__SELFRUN_DISPATCH__'+f.body.request_id+'__A2.json';
 await f.ingest();
 assert.equal(f.sends,1);assert.ok(f.record().conversation_url.includes('/c/'));
});
