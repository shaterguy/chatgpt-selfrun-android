import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';
import { keyFor } from '../src/lifecycle.mjs';

function committedFixture() {
  const f=fixture();f.closed=[];f.releases=0;f.resultReads=0;f.owned=new Set(['target-owned']);
  const read=f.transport.readGoogleDocText;
  f.transport.readGoogleDocText=async(...args)=>{f.resultReads++;return read(...args);};
  f.browser.chromium={isOwnedTarget:id=>f.owned.has(id),
    closeOwnedTarget:async(id,{guard})=>{
      await f.beforeClose?.();if(!guard({id,url:f.probe.url}))return {closed:false};
      if(f.closeFails)return {closed:false};
      if(!f.owned.delete(id))return {absent:true};f.closed.push(id);return {closed:true};
    },
    releaseIfUnused:async({guard})=>{if(guard()&&!f.owned.size){f.releases++;return {released:true};}return {released:false};},
  };
  const existing=f.existing;f.existing=async()=>{await existing();f.resultReads=0;};
  f.controller=f.recreate();return f;
}

for(const streaming of [false,true])test('committed Result at ten minutes suppresses input but keeps observer and owned tab; streaming='+streaming,async t=>{
  const f=committedFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.observe({});
  const observer=f.observations.at(-1),before=structuredClone(f.rows.get(f.path));f.result(true);await f.stall();
  await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
  assert.ok(f.record());assert.equal(observer.signal.aborted,false);assert.equal(f.controller.sessions.size,1);
  assert.deepEqual(f.closed,[]);assert.equal(f.releases,0);assert.equal(f.resultReads,1);assert.equal(f.sends,0);
  assert.deepEqual(f.rows.get(f.path),before);assert.equal(f.store.snapshot().activeCount,1);
  await f.observe({streaming,responseTextLength:2000});assert.equal(f.record().cursor.responseTextLength,2000);
  assert.equal(f.resultReads,1);assert.equal(observer.signal.aborted,false);
});

test('committed check survives restart as observation, not a terminal task or replay',async t=>{
  const f=committedFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();f.result(true);await f.stall();
  await f.controller.quiesceForStandby();f.controller=f.recreate();await f.controller.tick(f.transport);
  assert.ok(f.record());assert.equal(f.controller.sessions.size,1);assert.equal(f.resumes,2);assert.equal(f.sends,0);
  assert.deepEqual(f.closed,[]);assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
});

for(const invalid of ['wrong-turn','invalid-json','unavailable'])test('invalid Result cannot authorize input or terminate observation: '+invalid,async t=>{
  const f=committedFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  if(invalid==='wrong-turn')f.docs.set('RESULT-1',JSON.stringify({...JSON.parse(f.docs.get('RESULT-1')),turn_id:'OTHER:turn:1',committed:true}));
  if(invalid==='invalid-json')f.docs.set('RESULT-1','{broken');
  if(invalid==='unavailable')f.docs.delete('RESULT-1');
  await f.stall();assert.ok(f.record());assert.equal(f.record().result_state,'UNAVAILABLE');assert.equal(f.sends,0);
  assert.equal(f.observations.at(-1).signal.aborted,false);assert.deepEqual(f.closed,[]);assert.equal(f.resultReads,1);
});

test('committed Result is not read until the quiet deadline and is not polled during continued activity',async t=>{
  const f=committedFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();f.result(true);
  for(let n=0;n<4;n++){await f.observe({responseTextLength:1000+n});await f.controller.tick(f.transport);}
  assert.equal(f.resultReads,0);assert.equal(f.sends,0);assert.ok(f.record());
});

test('explicit DONE after a suppressed continuation performs the sole terminal cleanup',async t=>{
  const f=committedFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();f.result(true);await f.stall();
  f.control={...f.control,state:'DONE',control_epoch:2};await f.controller.control(null,f.control,f.transport);
  await f.controller.cleanupTerminalResources();assert.equal(f.record(),null);assert.deepEqual(f.closed,['target-owned']);
  assert.equal(f.resultReads,1);assert.equal(f.sends,0);assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
});

for(const conflicting of [true,false])test('terminal owned-resource closure respects newer target claim: '+conflicting,async t=>{
  const f=committedFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  const entered=deferred(),gate=deferred();f.beforeClose=async()=>{entered.resolve();await gate.promise;};
  f.control={...f.control,state:'DONE',control_epoch:2};await f.controller.control(null,f.control,f.transport);await entered.promise;
  f.control={...f.control,state:'RUNNING',request_id:'successor-without-dispatch',control_epoch:5,conversation_url:conflicting?f.probe.url:'https://chatgpt.com/c/independent'};
  await f.controller.control(null,f.control,f.transport);gate.resolve();await f.controller.cleanupTerminalResources();
  assert.deepEqual(f.closed,conflicting?[]:['target-owned']);assert.equal(f.resultReads,0);assert.equal(f.sends,0);
});
