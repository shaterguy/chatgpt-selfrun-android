import test from 'node:test';
import assert from 'node:assert/strict';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';
import { fixture } from './helpers/lifecycle-fixture.mjs';
import { keyFor, newIntent, initialRecord, preparationInputDigest } from '../src/lifecycle.mjs';

test('Page.enable preparation failure stays provably unsubmitted through cancellation and higher attempt', async()=>{
  const f=fixture();f.body.client_status='CREATE_REQUESTED';
  const closed=[],calls=[];
  const chromium={
    createTarget:async()=>({id:'failed-owned'}),
    isOwnedTarget:id=>id==='failed-owned',
    connectTarget:async()=>({call:async method=>{calls.push(method);throw Error('CDP command timeout: Page.enable');},close(){}}),
    closeTarget:async(id,{guard})=>{if(guard())closed.push(id);return {closed:true};},
    closeOwnedTarget:async id=>({absent:closed.includes(id)}),
  };
  f.browser=new ChatGptBrowser(chromium,{});f.controller=f.recreate();
  await f.ingest();
  assert.equal(f.record().state,'BLOCKED');assert.equal(f.record().reason,'ATTACH_FAILED');
  assert.equal(f.record().intent,null);assert.equal(f.record().conversation_url,'');
  assert.deepEqual(calls,['Page.enable']);assert.deepEqual(closed,['failed-owned']);
  f.body.client_status='CANCELLED';await f.ingest();await f.controller.cleanupTerminalResources();
  assert.equal(f.record().state,'CANCELLED');
  assert.equal(f.record().never_submitted,true,'a preparation-only failure must retain durable no-submit proof');
  f.body={...f.body,client_status:'CREATE_REQUESTED',dispatch_attempt:2};
  f.browser.prepare=async()=>({target:{id:'new'},session:{close(){}},baseline:{}});
  await f.ingest();
  assert.equal(f.record().state,'PREPARED');assert.equal(f.record().dispatch_attempt,2);
});

async function blockedPreparation(){
  const f=fixture();f.body.client_status='CREATE_REQUESTED';
  f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:async()=>({closed:true})};f.controller=f.recreate();
  f.browser.prepare=async args=>{f.prepares++;await args.onTarget({id:'failed-owned'});throw Error('CDP command timeout: Page.enable');};
  await f.ingest();return f;
}
async function cancelAndRetry(f){
  f.body.client_status='CANCELLED';await f.ingest();await f.controller.cleanupTerminalResources();
  f.body={...f.body,client_status:'CREATE_REQUESTED',dispatch_attempt:f.body.dispatch_attempt+1};
  await f.ingest();
}
test('pre-submit proof survives restart before and after cancellation, while old browser fences expire',async()=>{
  const f=await blockedPreparation(),old=f.controller.repository.token(keyFor(f.body));
  await f.controller.quiesceForStandby();f.controller=f.recreate();await f.controller.initialize();
  assert.equal(f.record().pre_submit_proof.schema,1);
  f.body.client_status='CANCELLED';await f.ingest();await f.controller.cleanupTerminalResources();
  f.controller=f.recreate();await f.controller.initialize();
  assert.equal(f.record().never_submitted,true);
  f.body={...f.body,client_status:'CREATE_REQUESTED',dispatch_attempt:2};
  f.browser.prepare=async()=>{f.prepares++;return {target:{id:'replacement'},session:{close(){}},baseline:{}};};
  await f.ingest();assert.equal(f.record().state,'PREPARED');assert.equal(f.prepares,2);
  assert.equal(f.controller.repository.current(keyFor(f.body),old),false);
  await f.controller.repository.move(keyFor(f.body),'BLOCKED','late old failure',{},old);
  assert.equal(f.record().state,'PREPARED');
  const latest=structuredClone(f.record());
  await f.controller.ingest(f.path,{...f.body,dispatch_attempt:1,client_status:'CANCELLED'},f.transport);
  assert.deepEqual(f.record(),latest);assert.equal(f.sends,0);
});
for(const field of ['prompt','project_url','profile_operations','result_document_id'])
test('preparation retry keeps accepted '+field+' immutable',async()=>{
  const f=await blockedPreparation();f.body.client_status='CANCELLED';await f.ingest();await f.controller.cleanupTerminalResources();
  f.body={...f.body,client_status:'CREATE_REQUESTED',dispatch_attempt:2,
    [field]:field==='profile_operations'?[{unexpected:'change'}]:'changed'};
  await assert.rejects(f.ingest(),/immutable/);assert.equal(f.record().retired,true);assert.equal(f.prepares,1);
});
for(const state of ['STOPPED','DONE','PAUSED','WAITING_USER_INTERVENTION'])
test(state+' control cannot reopen a preparation cancellation',async()=>{
  const f=await blockedPreparation();f.body.client_status='CANCELLED';await f.ingest();await f.controller.cleanupTerminalResources();
  f.control={...f.control,state,control_epoch:2};await f.controller.control(null,f.control,f.transport);
  f.body={...f.body,client_status:'CREATE_REQUESTED',dispatch_attempt:2};await f.ingest();
  assert.equal(f.prepares,1);assert.equal(f.sends,0);assert.equal(f.record().retired,true);
});
for(const outcome of ['PENDING','UNKNOWN','ABSENT','CONFIRMED'])
test('durable '+outcome+' intent revokes no-submit proof before cancellation',async()=>{
  const f=fixture();f.body.client_status='CREATE_REQUESTED';await f.ingest();
  const intent={...newIntent(f.record(),'initial','accepted input',{}),outcome,released:outcome!=='ABSENT'};
  await f.controller.repository.move(keyFor(f.body),'POST_PENDING','SUBMISSION_INTENT_DURABLE',{intent});
  assert.equal(f.record().pre_submit_proof,null);
  await f.controller.repository.move(keyFor(f.body),'BLOCKED','transport failed');
  await cancelAndRetry(f);assert.equal(f.prepares,1);assert.equal(f.sends,0);assert.equal(f.record().never_submitted,false);
});
test('existing URL and committed Result can never become preparation retries',async()=>{
  const f=fixture();await f.existing();
  await f.controller.repository.move(keyFor(f.body),'BLOCKED','attach failed');
  await cancelAndRetry(f);assert.equal(f.prepares,0);assert.equal(f.record().never_submitted,false);
  const g=await blockedPreparation();await g.controller.repository.move(keyFor(g.body),'COMMITTED','RESULT_COMMITTED_TERMINAL');
  await cancelAndRetry(g);assert.equal(g.prepares,1);assert.equal(g.record().state,'COMMITTED');
});
test('historical BLOCKED records with absent or even explicit empty fields do not gain fresh proof',async()=>{
  for(const missing of [true,false]){
    const f=fixture();f.body.client_status='CREATE_REQUESTED';
    const r=initialRecord(f.path,f.body);r.state='BLOCKED';r.reason='ATTACH_FAILED';
    if(missing){delete r.intent;delete r.submission_confirmed;}
    f.store.value.lifecycle={schema:1,requests:{[r.key]:r},retired:{},controls:{[f.control.task_id]:f.control},events:[],outbox:{}};
    f.controller=f.recreate();f.body.client_status='CANCELLED';await f.ingest();await f.controller.cleanupTerminalResources();
    assert.equal(f.record().never_submitted,false);
    f.body={...f.body,client_status:'CREATE_REQUESTED',dispatch_attempt:2};await f.ingest();
    assert.equal(f.prepares,0);assert.equal(f.record().retired,true);
  }
});
for(const importedState of ['BLOCKED','PREPARING','DISCOVERED','CANCELLED'])
test('cold Drive '+importedState+' cannot inject durable preparation proof',async()=>{
  const f=fixture();f.body.client_status='CREATE_REQUESTED';
  f.body.lifecycle_record={schema:1,...initialRecord(f.path,f.body),state:importedState,reason:'IMPORTED_FAILURE',blocked_epoch:1,
    pre_submit_proof:{schema:1,input_digest:preparationInputDigest(f.body)},never_submitted:true};
  await f.ingest();
  f.body.client_status='CANCELLED';await f.ingest();await f.controller.cleanupTerminalResources();
  assert.equal(f.record().never_submitted,false,'remote proof is not local submit-boundary evidence');
  const prepares=f.prepares;
  f.body={...f.body,client_status:'CREATE_REQUESTED',dispatch_attempt:2};await f.ingest();
  assert.equal(f.record().retired,true);assert.equal(f.prepares,prepares);
});
test('cold historical PREPARING without a proof cannot establish fresh local proof',async()=>{
  const f=fixture();f.body.client_status='CREATE_REQUESTED';f.body.lifecycle_state='PREPARING';
  f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:async()=>({closed:true})};f.controller=f.recreate();
  f.browser.prepare=async args=>{f.prepares++;await args.onTarget({id:'failed-owned'});throw Error('CDP command timeout: Page.enable');};
  await f.ingest();assert.equal(f.record().state,'BLOCKED');
  f.body.client_status='CANCELLED';await f.ingest();await f.controller.cleanupTerminalResources();
  assert.equal(f.record().never_submitted,false);
});
test('fresh locally authorized intake is provably unsubmitted even before browser preparation',async()=>{
  const f=fixture();f.body.client_status='CREATE_REQUESTED';
  await f.controller.repository.applyControl(f.control);
  await f.controller.repository.ingest(f.path,f.body);
  assert.equal(f.record().state,'DISCOVERED');assert.equal(f.prepares,0);
  await f.controller.repository.ingest(f.path,{...f.body,client_status:'CANCELLED'});
  assert.equal(f.record().never_submitted,true);
});
test('a local historical DISCOVERED snapshot with no proof cannot manufacture it during preparation',async()=>{
  const f=fixture();f.body.client_status='CREATE_REQUESTED';const r=initialRecord(f.path,f.body);
  f.store.value.lifecycle={schema:1,requests:{[r.key]:r},retired:{},controls:{[f.control.task_id]:f.control},events:[],outbox:{}};
  f.controller=f.recreate();await f.ingest();f.body.client_status='CANCELLED';await f.ingest();await f.controller.cleanupTerminalResources();
  assert.equal(f.record().never_submitted,false);
});
