import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture} from './helpers/lifecycle-fixture.mjs';

async function blockedDuringResultRetry(t) {
  const clock={now:1700000000000};t.mock.method(Date,'now',()=>clock.now);
  const f=fixture();let closed=0;
  f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:async(id,{guard})=>{
    if(!guard({id,url:'https://chatgpt.com/c/other'}))return {closed:false};closed++;return {closed:true};
  },releaseIfUnused:async()=>({released:false})};
  f.controller=f.recreate();t.after(()=>f.controller.quiesceForStandby());
  await f.existing();await f.observe({});
  const read=f.transport.readGoogleDocText;let reads=0,unavailable=true;
  f.transport.readGoogleDocText=async id=>{reads++;if(unavailable)throw Error('temporary Result outage');return read(id);};
  clock.now+=1000;await f.observe({});
  assert.equal(f.record().error.code,'RESULT_READ_FAILED');
  const deadline=f.record().result_retry_at;assert.equal(deadline,clock.now+30000);
  f.result(true);unavailable=false;clock.now++;
  await f.observe({url:'https://chatgpt.com/c/other'});
  assert.equal(f.record().state,'BLOCKED');assert.equal(f.record().blocked_epoch,f.record().control_epoch);
  assert.equal(f.controller.sessions.size,0);assert.equal(reads,1,'retry pacing still applies during URL mismatch');
  return {f,clock,deadline,get reads(){return reads;},get closed(){return closed;},setUnavailable(value){unavailable=value;}};
}
async function assertConsumed(x) {
  const {f}=x;await f.settle();await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
  assert.equal(f.rows.get(f.path).lifecycle_state,'COMMITTED');assert.equal(f.rows.get(f.path).server_status,'COMPLETED');
  assert.equal(f.sends,0);assert.equal(f.quiesces,0);assert.equal(f.resumes,1);
  assert.equal(x.closed,0,'the target with a different conversation stays protected');
  assert.equal(f.controller.sessions.size,0);
}
for(const restored of [false,true])test('blocked URL still consumes Result after retry deadline; restored='+restored,async t=>{
  const x=await blockedDuringResultRetry(t),{f,clock,deadline}=x;
  if(restored){await f.controller.quiesceForStandby();f.controller=f.recreate();}
  clock.now=deadline-1;await f.controller.tick(f.transport);assert.equal(x.reads,1);
  clock.now=deadline+1;await f.controller.tick(f.transport);await assertConsumed(x);
  assert.equal(x.reads,3,'exact committed result keeps the stable double-read check');
});
test('a repeated Result outage does not erase blocked completion retry eligibility',async t=>{
  const x=await blockedDuringResultRetry(t),{f,clock,deadline}=x;x.setUnavailable(true);
  clock.now=deadline+1;await f.controller.tick(f.transport);
  assert.equal(x.reads,2);assert.equal(f.record().state,'BLOCKED');assert.equal(f.record().error.code,'RESULT_READ_FAILED');
  const next=f.record().result_retry_at;clock.now=next-1;await f.controller.tick(f.transport);assert.equal(x.reads,2);
  x.setUnavailable(false);clock.now=next+1;await f.controller.tick(f.transport);await assertConsumed(x);assert.equal(x.reads,4);
});
test('blocked Result retry rejects another turn without browser entry or another input',async t=>{
  const x=await blockedDuringResultRetry(t),{f,clock,deadline}=x;
  f.docs.set('RESULT-1',JSON.stringify({...JSON.parse(f.docs.get('RESULT-1')),turn_id:'SAFE-TEST:turn:99'}));
  clock.now=deadline+1;await f.controller.tick(f.transport);await f.settle();
  assert.equal(f.record().state,'BLOCKED');assert.notEqual(f.rows.get(f.path).lifecycle_state,'COMMITTED');
  assert.equal(f.sends,0);assert.equal(f.quiesces,0);assert.equal(f.resumes,1);assert.equal(x.closed,0);
  assert.equal(f.controller.sessions.size,0);
});
