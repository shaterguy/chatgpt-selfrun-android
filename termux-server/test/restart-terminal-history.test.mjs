import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './helpers/lifecycle-fixture.mjs';
import { initialRecord, keyFor, projection } from '../src/lifecycle.mjs';

async function terminalFixture(state, {control=true, activeCount=0}={}) {
  const f=fixture();
  Object.assign(f.body,{conversation_url:f.probe.url,server_status:'COMPLETED',lifecycle_state:state});
  const record={...initialRecord(f.path,f.body,1000),state,result_state:'COMMITTED',
    submission_confirmed:true,revision:12,reason:'TURN_FINALIZED'};
  const body=projection(record);
  await f.store.patch({lifecycle:{schema:1,requests:{[record.key]:record},
    controls:control?{[f.body.task_id]:f.control}:{},events:[],outbox:{}},
    activeCount,activeDispatches:[],status:'COMPLETED'});
  f.rows.set(f.path,body);f.controller=f.recreate();f.result(true);
  return {f,record,body};
}

for(const state of ['COMMITTED','COMPLETED']) {
  test('restart preserves historical '+state+' despite cached RUNNING control',async()=>{
    const {f,record}=await terminalFixture(state);
    await f.controller.tick(f.transport);
    assert.equal(f.record().state,state);
    assert.equal(f.record().revision,record.revision);
    assert.equal(f.record().updated_at,record.updated_at);
    assert.equal(f.resumes,0);assert.equal(f.prepares,0);assert.equal(f.sends,0);
    assert.equal(f.store.snapshot().activeCount,0);
    assert.deepEqual(f.controller.activeDispatches(),[]);
  });
  test('Drive import preserves historical '+state+' without claiming an observer',async()=>{
    const {f,body}=await terminalFixture(state);
    const {MemoryStore}=await import('./helpers/lifecycle-fixture.mjs');
    f.store=new MemoryStore();f.controller=f.recreate();
    await f.controller.controlDurable(null,f.control);
    await f.controller.ingest(f.path,body,f.transport);
    assert.equal(f.record().state,state);
    assert.equal(f.resumes,0);assert.equal(f.prepares,0);assert.equal(f.sends,0);
    assert.equal(f.store.snapshot().activeCount,0);
    assert.deepEqual(f.controller.activeDispatches(),[]);
  });
}

test('restart does not revive terminal history when no cached control exists',async()=>{
  const {f}=await terminalFixture('COMPLETED',{control:false});
  f.control=null;await f.controller.tick(f.transport);
  assert.equal(f.record().state,'COMPLETED');
  assert.equal(f.resumes,0);assert.equal(f.sends,0);
  assert.equal(f.store.snapshot().activeCount,0);
});
test('standby initialization retains terminal history and publishes no local active records',async()=>{
  const {f}=await terminalFixture('COMPLETED');
  f.controller.canDispatch=()=>false;
  await f.controller.tick(f.transport);
  assert.equal(f.record().state,'COMPLETED');
  assert.equal(f.store.snapshot().activeCount,0);
  assert.equal(f.resumes,0);assert.equal(f.sends,0);
});
test('restart recomputes a stale active summary without changing terminal records',async()=>{
  const {f,record}=await terminalFixture('COMPLETED',{activeCount:7});
  await f.controller.tick(f.transport);
  assert.equal(f.store.snapshot().activeCount,0);
  assert.deepEqual(f.store.snapshot().activeDispatches,[]);
  assert.deepEqual(f.record(),record);
});
test('a nonterminal observer with committed Result resumes observation across restart',async()=>{
  const f=fixture();await f.existing();f.result(true);await f.observe({});
  assert.equal(f.record().result_state,'COMMITTED');
  assert.notEqual(f.record().state,'COMPLETED');
  await f.controller.quiesceForStandby();f.controller=f.recreate();
  await f.controller.tick(f.transport);
  assert.equal(f.resumes,2);assert.equal(f.sends,0);
  assert.equal(f.controller.sessions.size,1);
  assert.equal(f.store.snapshot().activeCount,1);
  await f.controller.quiesceForStandby();
});
test('a fresh app request can prepare without reviving its historical predecessor',async()=>{
  const {f,record}=await terminalFixture('COMPLETED');
  const oldKey=keyFor(f.body);
  Object.assign(f.body,{turn_id:'next-turn',request_id:'next-request',conversation_url:'',
    lifecycle_state:undefined,server_status:'PENDING',client_status:'CREATE_REQUESTED'});
  Object.assign(f.control,{turn_id:f.body.turn_id,request_id:f.body.request_id,control_epoch:2});
  f.path='__SELFRUN_DISPATCH__next.json';await f.ingest();
  assert.equal(f.controller.repository.get(oldKey).state,record.state);
  assert.equal(f.prepares,1);assert.equal(f.sends,0);
  assert.equal(f.record().state,'PREPARED');
  await f.controller.quiesceForStandby();
});

test('legacy completed status without lifecycle fields stays finalized on import',async()=>{
  const f=fixture();
  Object.assign(f.body,{conversation_url:f.probe.url,server_status:'COMPLETED'});
  await f.ingest();
  assert.equal(f.record().state,'COMPLETED');
  assert.equal(f.resumes,0);assert.equal(f.sends,0);
  assert.equal(f.store.snapshot().activeCount,0);
});
test('terminal history cannot be reopened by a stale uncertain-POST audit',async()=>{
  const {migrateLegacy}=await import('../src/lifecycle-migration.mjs');
  for(const state of ['COMMITTED','COMPLETED']) {
    const {record}=await terminalFixture(state);
    const history={legacy:new Map([[record.key,{recovery_count:0,unknown_post:true,error:'old timeout'}]])};
    const migrated=migrateLegacy(structuredClone(record),history,'old continuation');
    assert.equal(migrated.state,state);assert.equal(migrated.intent,null);
  }
});
test('STOP and cancellation of terminal history remain idempotent without browser work',async()=>{
  for(const state of ['COMMITTED','COMPLETED']) {
    const {f,record}=await terminalFixture(state);
    await f.stop();
    f.body.client_status='CANCELLED';await f.ingest();
    await f.controller.tick(f.transport);
    assert.equal(f.record().state,state);
    assert.equal(f.record().revision,record.revision);
    assert.equal(f.resumes,0);assert.equal(f.quiesces,0);assert.equal(f.sends,0);
    assert.equal(f.store.snapshot().activeCount,0);
  }
});
