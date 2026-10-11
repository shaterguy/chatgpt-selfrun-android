import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './helpers/lifecycle-fixture.mjs';

const WINDOW=600000;
async function setup(t,{streaming=false}={}) {
  let now=1700000000000;
  t.mock.method(Date,'now',()=>now);
  const f=fixture();f.probe.streaming=streaming;f.probe.stopButtonVisible=streaming;
  t.after(()=>f.controller.quiesceForStandby());
  await f.existing();await f.observe({});
  return {f,advance:ms=>{now+=ms;},now:()=>now};
}

test('an unchanged response without a stop button remains observation, not answer completion',async t=>{
  const {f,advance}=await setup(t),progressAt=f.record().last_progress_at;
  for(let sample=0;sample<3;sample++) {
    advance(60000);await f.observe({});
    assert.equal(f.record().response_status,'UNKNOWN');
    assert.equal(f.record().state,'OBSERVING');
    assert.equal(f.record().last_progress_at,progressAt);
    assert.equal(f.rows.get(f.path).response_status,undefined);
  }
  assert.equal(f.sends,0);assert.equal(f.quiesces,0);assert.equal(f.resumes,1);
});

test('a browser COMPLETED label cannot declare the answer or Result complete',async t=>{
  const {f,advance}=await setup(t);advance(60000);
  await f.observe({status:'COMPLETED'});
  assert.equal(f.record().response_status,'UNKNOWN');assert.equal(f.record().state,'OBSERVING');
  assert.notEqual(f.rows.get(f.path).server_status,'COMPLETED');assert.equal(f.sends,0);
});

test('a persisted completion guess is replaced without resetting the quiet deadline',async t=>{
  const {f,advance}=await setup(t),progressAt=f.record().last_progress_at;
  await f.controller.repository.move(f.record().key,'WAIT_RESULT','legacy completion guess',{response_status:'COMPLETED'});
  advance(60000);await f.observe({});
  assert.equal(f.record().response_status,'UNKNOWN');assert.equal(f.record().state,'OBSERVING');
  assert.equal(f.record().last_progress_at,progressAt);assert.equal(f.sends,0);
});

test('losing a streaming flag records uncertainty while preserving real progress and monitoring',async t=>{
  const {f,advance}=await setup(t,{streaming:true}),progressAt=f.record().last_progress_at;
  assert.equal(f.record().response_status,'RUNNING');
  f.probe.streaming=false;f.probe.stopButtonVisible=false;advance(60000);await f.observe({});
  advance(60000);await f.observe({});
  assert.equal(f.record().response_status,'UNKNOWN');assert.equal(f.record().state,'OBSERVING');
  assert.equal(f.record().last_progress_at,progressAt);assert.equal(f.sends,0);
});

test('the unchanged ten-minute deadline checks the exact false Result and sends continuation without an answer-end guess',async t=>{
  const {f,advance}=await setup(t),reads=[],read=f.transport.readGoogleDocText;
  f.transport.readGoogleDocText=async id=>{reads.push(id);return read(id);};
  f.onSubmit=async()=>{assert.ok(reads.includes('RESULT-1'));};
  advance(WINDOW-1);await f.observe({status:'COMPLETED'});assert.equal(f.sends,0);
  advance(1);await f.controller.tick(f.transport);
  assert.equal(f.sends,1);assert.equal(f.sent[0].kind,'recovery');
  assert.equal(f.sent[0].prompt,'continue test');assert.equal(f.quiesces,0);
});

test('an exact true Result suppresses continuation while retaining monitoring',async t=>{
  const {f,advance}=await setup(t);f.result(true);advance(WINDOW);
  await f.observe({status:'RUNNING',streaming:true});
  assert.equal(f.record().result_state,'COMMITTED');assert.equal(f.record().state,'OBSERVING');assert.equal(f.rows.get(f.path).lifecycle_state,undefined);assert.equal(f.observations.at(-1).signal.aborted,false);
  assert.equal(f.sends,0);assert.equal(f.quiesces,0);
});

test('a Result read outage is not false and cannot authorize the due continuation',async t=>{
  const {f,advance}=await setup(t),progressAt=f.record().last_progress_at;
  f.transport.readGoogleDocText=async()=>{throw Error('Drive unavailable');};
  advance(WINDOW);await f.observe({status:'COMPLETED'});await f.controller.tick(f.transport);
  assert.equal(f.sends,0);assert.equal(f.quiesces,0);
  assert.equal(f.record().last_progress_at,progressAt);
  assert.notEqual(f.record().response_status,'COMPLETED');
  assert.notEqual(f.record().result_state,'COMMITTED');
});
