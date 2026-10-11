import test from 'node:test';
import assert from 'node:assert/strict';
import { readResult } from '../src/result-reconciler.mjs';
import { fixture } from './helpers/lifecycle-fixture.mjs';
import { keyFor, transition, initialRecord} from '../src/lifecycle.mjs';
import { projection } from './helpers/legacy-projection.mjs';
const body={schema:'selfrun-turn-result-v3',task_id:'TASK',turn_id:'TASK:turn:2',turn:2,document_id:'DOC',event_id:'TASK:turn:2:result',committed:true};
const record={task_id:'TASK',turn_id:body.turn_id,body:{result_document_id:'DOC'}};
const read=(raw, r=record)=>readResult({readGoogleDocText:async()=>raw},r);

test('strict Result reader rejects a trailing brace without repairing the app document',async()=>{
  const raw='\uFEFF'+JSON.stringify(body)+'}\n';let reads=0;
  const result=await readResult({readGoogleDocText:async()=>{reads++;return raw;}},record);
  assert.equal(result.state,'UNAVAILABLE');assert.equal(result.code,'RESULT_JSON_INVALID');assert.equal(reads,1);
});
for(const [name,raw,code] of [
  ['malformed','{not JSON}','RESULT_JSON_INVALID'],
  ['two trailing braces',JSON.stringify(body)+'}}','RESULT_JSON_INVALID'],
  ['uncommitted extra brace',JSON.stringify({...body,committed:false})+'}','RESULT_JSON_INVALID'],
  ['wrong identity',JSON.stringify({...body,task_id:'OTHER'}),'RESULT_IDENTITY_MISMATCH'],
  ['wrong identity with extra brace',JSON.stringify({...body,task_id:'OTHER'})+'}','RESULT_JSON_INVALID'],
  ['nonobject','null','RESULT_IDENTITY_MISMATCH'],
  ['NUL',JSON.stringify(body)+'\0','RESULT_TEXT_INVALID'],
  ['nonstring',null,'RESULT_TEXT_INVALID'],
])test('Result reader classifies '+name,async()=>{const result=await read(raw);assert.equal(result.state,'UNAVAILABLE');assert.equal(result.code,code);});
test('malformed Result is rejected after one read',async()=>{
  const raw=JSON.stringify(body)+'}';let n=0;
  assert.equal((await readResult({readGoogleDocText:async()=>++n===1?raw:raw+' '},record)).code,'RESULT_JSON_INVALID');assert.equal(n,1);
});
test('transport error classification never copies private response text',async()=>{
  const result=await readResult({readGoogleDocText:async()=>{throw Error('secret https://private/token');}},record);
  assert.equal(result.code,'RESULT_READ_FAILED');assert.doesNotMatch(result.message,/secret|private/);
});
test('strict valid sentinel and committed document keep their original behavior',async()=>{
  assert.deepEqual(await read(JSON.stringify({...body,committed:false})),{state:'NOT_COMMITTED'});
  assert.deepEqual(await read(JSON.stringify(body)),{state:'COMMITTED'});
});
test('ordinary observation preserves diagnostics locally without projecting business state',async()=>{
  const f=fixture();await f.existing();
  await f.controller.repository.move(keyFor(f.body),'OBSERVING','DIAGNOSTIC_ERROR',{error:{code:'QUERY_TIMEOUT',message:'old query timeout'}});
  await f.controller.flush(f.transport);const history=structuredClone(f.store.events);
  f.result(true);await f.observe({});await f.controller.flush(f.transport);
  assert.equal(f.rows.get(f.path).server_error,undefined);assert.equal(f.rows.get(f.path).server_error_class,undefined);
  for(const event of history)assert.ok(f.store.events.some(row=>JSON.stringify(row)===JSON.stringify(event)));
});
test('terminal transition clears current error while prior record stays immutable',()=>{
  const before={...initialRecord('p',{task_id:'TASK',turn_id:'TASK:turn:2',request_id:'REQ',dispatch_attempt:1}),error:{code:'OLD',message:'old error'}};
  const after=transition(before,'COMMITTED','RESULT_COMMITTED_TERMINAL');
  assert.equal(after.error,null);assert.equal(projection(after).server_error_class,null);assert.equal(before.error.code,'OLD');
});
test('one terminal cleanup outcome releases its capability without terminal history',async()=>{
  const f=fixture();await f.existing();const repo=f.controller.repository;
  await repo.move(keyFor(f.body),'COMMITTED','RESULT_COMMITTED_TERMINAL',{target_owned:true,target_id:'owned'});
  const entry=structuredClone(repo.cleanupEntry('owned'));assert.ok(entry);
  assert.equal(repo.get(entry.key),null);
  assert.equal(await repo.recordTargetCleanup(entry,{status:'PENDING',observed_at_ms:100,reason:'unavailable'}),true);
  assert.equal(repo.cleanupEntry('owned'),undefined);assert.deepEqual(repo.data.retired,{});
  assert.equal(await repo.recordTargetCleanup(entry,{status:'CLOSED',observed_at_ms:101,reason:'late'}),false);
  assert.equal(repo.data.browserCleanupOutcomes?.owned,undefined);
  f.controller=f.recreate();assert.equal(f.record(),null);
});
test('stale cleanup outcome cannot overwrite replaced resource ownership',async()=>{
  const f=fixture();await f.existing();const repo=f.controller.repository;
  await repo.move(keyFor(f.body),'COMMITTED','RESULT_COMMITTED_TERMINAL',{target_owned:true,target_id:'owned'});
  const entry=structuredClone(repo.cleanupEntry('owned'));repo.resourceCleanups.get('owned').control_epoch++;
  assert.equal(await repo.recordTargetCleanup(entry,{status:'CLOSED',observed_at_ms:100,reason:'late'}),false);
  assert.ok(repo.cleanupEntry('owned'));assert.equal(repo.get(entry.key),null);
});

test('terminal success discards pending historical error audit publication',async()=>{
  const f=fixture();await f.existing();const repo=f.controller.repository,key=keyFor(f.body);
  await repo.move(key,'OBSERVING','PENDING_DIAGNOSTIC',{error:{code:'RESULT_JSON_INVALID',message:'Result JSON could not be parsed; retry pending'}});
  await repo.move(key,'COMMITTED','RESULT_COMMITTED_TERMINAL');await repo.flush(f.transport);
  assert.equal(f.store.events.some(row=>row.details.reason==='PENDING_DIAGNOSTIC'),false);assert.deepEqual(repo.data.events,[]);
});
test('malformed committed Result remains unavailable and does not retire observation',async()=>{
  const f=fixture();await f.existing();f.result(true);f.docs.set('RESULT-1',f.docs.get('RESULT-1')+'}');
  await f.stall();await f.controller.flush(f.transport);
  assert.equal(f.record().error.code,'RESULT_JSON_INVALID');assert.equal(f.controller.sessions.size,1);
  assert.equal(f.rows.get(f.path).server_error,undefined);assert.equal(f.rows.get(f.path).server_error_class,undefined);
  assert.deepEqual(f.store.snapshot().lifecycle.events,[]);
});
