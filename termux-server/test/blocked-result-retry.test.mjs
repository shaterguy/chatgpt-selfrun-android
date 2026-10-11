import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture} from './helpers/lifecycle-fixture.mjs';

function setup(t){
  const clock={now:1700000000000};t.mock.method(Date,'now',()=>clock.now);
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());let reads=0;
  const read=f.transport.readGoogleDocText;f.transport.readGoogleDocText=async id=>{reads++;return read(id);};
  return {f,clock,get reads(){return reads;}};
}
for(const restored of [false,true])test('unavailable deadline Result keeps observer and retry pacing across restart='+restored,async t=>{
  const x=setup(t),{f,clock}=x;await f.existing();await f.observe({});f.docs.delete('RESULT-1');clock.now+=600000;await f.observe({});
  assert.equal(f.record().error.code,'RESULT_READ_FAILED');assert.equal(x.reads,1);assert.equal(f.controller.sessions.size,1);
  const deadline=f.record().result_retry_at;assert.equal(deadline,clock.now+30000);
  if(restored){await f.controller.quiesceForStandby();f.controller=f.recreate();await f.controller.tick(f.transport);}
  f.result(true);clock.now=deadline-1;await f.observe({});assert.equal(x.reads,1);
  clock.now=deadline+1;await f.observe({});assert.equal(x.reads,2);assert.equal(f.sends,0);
  assert.ok(f.record());assert.equal(f.observations.at(-1).signal.aborted,false);assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
});
test('a repeated Result outage retries at the deadline without retiring observation',async t=>{
  const x=setup(t),{f,clock}=x;await f.existing();await f.observe({});f.docs.delete('RESULT-1');clock.now+=600000;await f.observe({});
  clock.now=f.record().result_retry_at+1;await f.observe({});assert.equal(x.reads,2);
  const next=f.record().result_retry_at;clock.now=next-1;await f.observe({});assert.equal(x.reads,2);
  f.result(true);clock.now=next+1;await f.observe({});assert.equal(x.reads,3);assert.equal(f.sends,0);assert.equal(f.controller.sessions.size,1);
});
test('foreign Result identity suppresses a due continuation without closing the active monitor',async t=>{
  const x=setup(t),{f,clock}=x;await f.existing();await f.observe({});
  f.docs.set('RESULT-1',JSON.stringify({...JSON.parse(f.docs.get('RESULT-1')),turn_id:'SAFE-TEST:turn:99',committed:true}));
  clock.now+=600000;await f.observe({});assert.equal(f.record().result_state,'UNAVAILABLE');assert.equal(f.sends,0);
  assert.equal(f.record().error.code,'RESULT_IDENTITY_MISMATCH');assert.equal(f.controller.sessions.size,1);assert.equal(x.reads,1);
});
