import test from 'node:test';
import assert from 'node:assert/strict';
import { readResult } from '../src/result-reconciler.mjs';
import { fixture } from './helpers/lifecycle-fixture.mjs';
import { keyFor, transition, initialRecord, projection } from '../src/lifecycle.mjs';
const body={schema:'selfrun-turn-result-v3',task_id:'TASK',turn_id:'TASK:turn:2',turn:2,document_id:'DOC',event_id:'TASK:turn:2:result',committed:true};
const record={task_id:'TASK',turn_id:body.turn_id,body:{result_document_id:'DOC'}};
const read=(raw, r=record)=>readResult({readGoogleDocText:async()=>raw},r);

test('known app-compatible single trailing brace recovers only a committed exact Result',async()=>{
  const raw='\uFEFF'+JSON.stringify(body)+'}\n';let reads=0;
  const result=await readResult({readGoogleDocText:async()=>{reads++;return raw;}},record);
  assert.equal(result.state,'COMMITTED');assert.equal(result.recovery,'SINGLE_TRAILING_BRACE');assert.equal(reads,2);
});
for(const [name,raw,code] of [
  ['malformed','{not JSON}','RESULT_JSON_INVALID'],
  ['two trailing braces',JSON.stringify(body)+'}}','RESULT_JSON_INVALID'],
  ['uncommitted extra brace',JSON.stringify({...body,committed:false})+'}','RESULT_JSON_INVALID'],
  ['wrong identity',JSON.stringify({...body,task_id:'OTHER'}),'RESULT_IDENTITY_MISMATCH'],
  ['wrong identity with extra brace',JSON.stringify({...body,task_id:'OTHER'})+'}','RESULT_IDENTITY_MISMATCH'],
  ['nonobject','null','RESULT_IDENTITY_MISMATCH'],
  ['NUL',JSON.stringify(body)+'\0','RESULT_TEXT_INVALID'],
  ['nonstring',null,'RESULT_TEXT_INVALID'],
])test('Result reader classifies '+name,async()=>{const result=await read(raw);assert.equal(result.state,'UNAVAILABLE');assert.equal(result.code,code);});
test('recovered Result still requires exact unchanged raw second read',async()=>{
  const raw=JSON.stringify(body)+'}';let n=0;
  assert.equal((await readResult({readGoogleDocText:async()=>++n===1?raw:raw+' '},record)).code,'RESULT_READBACK_CHANGED');
});
test('transport error classification never copies private response text',async()=>{
  const result=await readResult({readGoogleDocText:async()=>{throw Error('secret https://private/token');}},record);
  assert.equal(result.code,'RESULT_READ_FAILED');assert.doesNotMatch(result.message,/secret|private/);
});
test('strict valid sentinel and committed document keep their original behavior',async()=>{
  assert.deepEqual(await read(JSON.stringify({...body,committed:false})),{state:'NOT_COMMITTED'});
  assert.deepEqual(await read(JSON.stringify(body)),{state:'COMMITTED'});
});
test('terminal success projection clears stale current errors and preserves stored audit history',async()=>{
  const f=fixture();await f.existing();
  await f.controller.repository.move(keyFor(f.body),'OBSERVING','DIAGNOSTIC_ERROR',{error:{code:'QUERY_TIMEOUT',message:'old query timeout'}});
  await f.controller.flush(f.transport);const history=structuredClone(f.store.events);
  f.result(true);await f.observe({});await f.controller.flush(f.transport);
  assert.equal(f.rows.get(f.path).server_error,'');assert.equal(f.rows.get(f.path).server_error_class,null);
  for(const event of history)assert.ok(f.store.events.some(row=>JSON.stringify(row)===JSON.stringify(event)));
});
test('terminal transition clears current error while prior record stays immutable',()=>{
  const before={...initialRecord('p',{task_id:'TASK',turn_id:'TASK:turn:2',request_id:'REQ',dispatch_attempt:1}),error:{code:'OLD',message:'old error'}};
  const after=transition(before,'COMMITTED','RESULT_COMMITTED_TERMINAL');
  assert.equal(after.error,null);assert.equal(projection(after).server_error_class,null);assert.equal(before.error.code,'OLD');
});
test('cleanup outcome persists separately from lifecycle success with target and time',async()=>{
  const f=fixture();await f.existing();const repo=f.controller.repository;
  await repo.move(keyFor(f.body),'COMMITTED','RESULT_COMMITTED_TERMINAL',{target_owned:true,target_id:'owned'});
  const entry=structuredClone(repo.data.browserCleanup.owned);
  await repo.recordTargetCleanup(entry,{status:'PENDING',observed_at_ms:100,reason:'unavailable'});
  assert.equal(repo.get(entry.key).state,'COMMITTED');assert.equal(repo.get(entry.key).browser_cleanup.status,'PENDING');
  assert.equal(repo.data.browserCleanup.owned.target_id,'owned');
  await repo.recordTargetCleanup(entry,{status:'CLOSED',observed_at_ms:101,reason:'TARGET_ABSENT_AFTER_CLOSE'});
  assert.equal(repo.data.browserCleanup.owned,undefined);
  assert.deepEqual(repo.get(entry.key).browser_cleanup,{target_id:'owned',status:'CLOSED',observed_at_ms:101,reason:'TARGET_ABSENT_AFTER_CLOSE'});
  f.controller=f.recreate();assert.equal(f.record().browser_cleanup.observed_at_ms,101);
});
test('stale cleanup outcome cannot overwrite replaced queue ownership',async()=>{
  const f=fixture();await f.existing();const repo=f.controller.repository;
  await repo.move(keyFor(f.body),'COMMITTED','RESULT_COMMITTED_TERMINAL',{target_owned:true,target_id:'owned'});
  const entry=structuredClone(repo.data.browserCleanup.owned);repo.data.browserCleanup.owned.control_epoch++;
  assert.equal(await repo.recordTargetCleanup(entry,{status:'CLOSED',observed_at_ms:100,reason:'TARGET_ABSENT_AFTER_CLOSE'}),false);
  assert.ok(repo.data.browserCleanup.owned);assert.equal(repo.get(entry.key).browser_cleanup,undefined);
});

test('terminal success retains pending error audit events for publication',async()=>{
  const f=fixture();await f.existing();const repo=f.controller.repository,key=keyFor(f.body);
  await repo.move(key,'OBSERVING','PENDING_DIAGNOSTIC',{error:{code:'RESULT_JSON_INVALID',message:'Result JSON could not be parsed; retry pending'}});
  await repo.move(key,'COMMITTED','RESULT_COMMITTED_TERMINAL');await repo.flush(f.transport);
  assert.ok(f.store.events.some(row=>row.details.reason==='PENDING_DIAGNOSTIC'&&row.details.error_class==='RESULT_JSON_INVALID'));
});
test('bounded recovery is recorded separately from success and clears prior parse warning',async()=>{
  const f=fixture();await f.existing();f.result(true);f.docs.set('RESULT-1',f.docs.get('RESULT-1')+'}');
  await f.observe({});await f.controller.flush(f.transport);
  assert.equal(f.record().state,'COMMITTED');assert.equal(f.record().result_recovery,'SINGLE_TRAILING_BRACE');
  assert.equal(f.rows.get(f.path).server_error,'');assert.equal(f.rows.get(f.path).server_error_class,null);
  assert.ok(f.store.events.some(row=>row.details.result_recovery==='SINGLE_TRAILING_BRACE'));
});
