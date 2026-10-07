import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture} from './helpers/lifecycle-fixture.mjs';
import {keyFor, newIntent} from '../src/lifecycle.mjs';

// Removing durable preparation proof or its retired marker must fail this suite.
async function blockedPreparation() {
  const f=fixture();f.body.client_status='CREATE_REQUESTED';
  f.browser.prepare=async()=>{f.prepares++;throw Error('CDP command timeout: Page.enable');};
  await f.ingest();assert.equal(f.record().state,'BLOCKED');return f;
}

test('phone preparation-only Page.enable failure can retry after cancel and restart',async()=>{
  const f=await blockedPreparation();f.body.client_status='CANCELLED';await f.ingest();
  assert.equal(f.record().never_submitted,true);
  f.controller=f.recreate();await f.controller.initialize();
  f.body={...f.body,dispatch_attempt:2,client_status:'CREATE_REQUESTED'};
  f.browser.prepare=async()=>{f.prepares++;return {target:{id:'new'},session:{close(){}},baseline:{}};};
  await f.ingest();assert.equal(f.record().state,'PREPARED');assert.equal(f.prepares,2);assert.equal(f.sends,0);
});

for(const field of ['prompt','project_url','profile_operations','result_document_id'])
test('phone preparation cancellation cannot change accepted '+field,async()=>{
  const f=await blockedPreparation();f.body.client_status='CANCELLED';await f.ingest();
  f.body={...f.body,dispatch_attempt:2,client_status:'CREATE_REQUESTED',
    [field]:field==='profile_operations'?[{changed:true}]:'changed'};
  await assert.rejects(f.ingest(),/immutable/);assert.equal(f.prepares,1);assert.equal(f.sends,0);
});

test('phone durable submission intent prevents retry even when outcome is ABSENT',async()=>{
  const f=fixture();f.body.client_status='CREATE_REQUESTED';await f.ingest();
  const intent={...newIntent(f.record(),'initial','accepted input',{}),outcome:'ABSENT',released:false};
  await f.controller.repository.move(keyFor(f.body),'POST_PENDING','SUBMISSION_INTENT_DURABLE',{intent});
  await f.controller.repository.move(keyFor(f.body),'BLOCKED','failed submission');
  f.body.client_status='CANCELLED';await f.ingest();
  assert.equal(f.record().never_submitted,false);
  f.body={...f.body,dispatch_attempt:2,client_status:'CREATE_REQUESTED'};await f.ingest();
  assert.equal(f.prepares,1);assert.equal(f.sends,0);assert.equal(f.record().retired,true);
});
