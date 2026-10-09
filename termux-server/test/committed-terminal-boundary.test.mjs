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

test('exact committed true retires monitor and owned resources without app STOP',async()=>{
  const f=committedFixture();await f.existing();const observer=f.observations.at(-1);f.result(true);
  await f.observe({streaming:true});await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
  assert.equal(f.record(),null);
  assert.equal(observer.signal.aborted,true);assert.equal(f.controller.sessions.size,0);
  assert.deepEqual(f.closed,['target-owned']);assert.ok(f.releases>0);assert.equal(f.resultReads,2);
  assert.equal(f.store.snapshot().activeCount,0);assert.notEqual(f.store.snapshot().status,'RUNNING');
  assert.equal(f.rows.get(f.path).server_status,'COMPLETED');assert.equal(f.rows.get(f.path).lifecycle_state,'COMMITTED');
  for(let n=0;n<3;n++){await observer.onActivity({...f.probe,status:'STALLED'});await f.controller.tick(f.transport);}
  assert.equal(f.resultReads,2);assert.equal(f.sends,0);assert.equal(f.resumes,1);
});
test('committed retirement survives restart and old same-request higher RUNNING cannot reopen',async()=>{
  const f=committedFixture();await f.existing();const stale=structuredClone(f.rows.get(f.path));f.result(true);await f.observe({});
  await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
  f.controller=f.recreate();f.control={...f.control,state:'RUNNING',control_epoch:9,conversation_url:f.probe.url};
  await f.controller.control(null,f.control,f.transport);await f.controller.ingest(f.path,stale,f.transport);await f.controller.tick(f.transport);
  assert.equal(f.record(),null);assert.equal(f.resumes,1);
  assert.ok(f.resultReads>=2);assert.equal(f.sends,0);assert.equal(f.controller.sessions.size,0);
});
test('new same-identity RUNNING cannot obstruct committed resource cleanup',async()=>{
  const f=committedFixture();await f.existing();const entered=deferred(),gate=deferred();
  f.beforeClose=async()=>{entered.resolve();await gate.promise;};f.result(true);await f.observe({});await entered.promise;
  f.control={...f.control,state:'RUNNING',control_epoch:8,conversation_url:f.probe.url};
  await f.controller.control(null,f.control,f.transport);gate.resolve();await f.controller.cleanupTerminalResources();
  assert.deepEqual(f.closed,['target-owned']);assert.ok(f.releases>0);assert.equal(f.resumes,1);
});
test('invalid or unstable committed Result never terminates a current observer',async()=>{
  for(const mode of ['wrong-turn','unstable']) {
    const f=committedFixture();await f.existing();const observer=f.observations.at(-1);f.result(true);
    if(mode==='wrong-turn')f.docs.set('RESULT-1',JSON.stringify({...JSON.parse(f.docs.get('RESULT-1')),turn_id:'OTHER:turn:1'}));
    else {let n=0;const read=f.transport.readGoogleDocText;f.transport.readGoogleDocText=async id=>++n===2?'{}':read(id);}
    await f.observe({});await f.controller.cleanupTerminalResources();
    assert.equal(f.record().retired,undefined);assert.equal(observer.signal.aborted,false);assert.deepEqual(f.closed,[]);
    await f.controller.quiesceForStandby();
  }
});
test('late committed readback cannot retire a newer same-request browser generation',async()=>{
  const f=committedFixture();await f.existing();f.result(true);const entered=deferred(),gate=deferred();
  const raw=f.docs.get('RESULT-1');f.transport.readGoogleDocText=async()=>{entered.resolve();await gate.promise;return raw;};
  const observing=f.observe({});await entered.promise;
  f.control={...f.control,state:'PAUSED',control_epoch:2};await f.controller.control(null,f.control,f.transport);
  f.control={...f.control,state:'RUNNING',control_epoch:3};await f.controller.control(null,f.control,f.transport);
  gate.resolve();await observing;
  assert.equal(f.record().retired,undefined);assert.deepEqual(f.closed,[]);assert.equal(f.record().control_epoch,3);
});
test('failed final publication leaves no durable terminal work after restart',async()=>{
  const f=committedFixture();await f.existing();f.writeFail=true;f.result(true);await f.observe({});
  await f.controller.flush(f.transport).catch(()=>{});assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
  f.controller=f.recreate();f.writeFail=false;await f.controller.tick(f.transport);
  assert.equal(f.record(),null);assert.equal(f.resultReads,2);assert.equal(f.resumes,1);assert.equal(f.controller.sessions.size,0);
});

test('superseded completion projection is discarded without overwriting newer app input or blocking tick',async()=>{
  const f=committedFixture();await f.existing();f.writeFail=true;f.result(true);await f.observe({});
  await f.controller.flush(f.transport).catch(()=>{});f.writeFail=false;
  const successor={...f.rows.get(f.path),request_id:'new-app-request',dispatch_attempt:2};f.rows.set(f.path,successor);
  await assert.doesNotReject(f.controller.tick(f.transport));
  assert.deepEqual(f.rows.get(f.path),successor);assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
  assert.equal(f.resultReads,2);assert.equal(f.resumes,1);
});

test('failed completion telemetry cannot block current owned-resource cleanup retry',async()=>{
  const f=committedFixture();await f.existing();f.writeFail=true;f.closeFails=true;f.result(true);await f.observe({});
  await f.controller.cleanupTerminalResources();assert.deepEqual(f.store.snapshot().lifecycle.browserCleanup,{});
  f.closeFails=false;await assert.doesNotReject(f.controller.tick(f.transport));await f.controller.cleanupTerminalResources();
  assert.deepEqual(f.closed,['target-owned']);assert.equal(f.resultReads,2);assert.equal(f.controller.sessions.size,0);
  assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
  f.controller=f.recreate();await f.controller.tick(f.transport);assert.equal(f.resumes,1);assert.equal(f.sends,0);
});

for(const conflicting of [true,false])test('current standalone successor control fences only conflicting consumed resources: '+conflicting,async()=>{
 const f=committedFixture();await f.existing();f.closeFails=true;f.result(true);await f.observe({});await f.controller.cleanupTerminalResources();
 f.control={...f.control,request_id:'successor-without-dispatch',control_epoch:5,conversation_url:conflicting?f.probe.url:'https://chatgpt.com/c/independent'};
 await f.controller.control(null,f.control,f.transport);f.closeFails=false;await f.controller.cleanupTerminalResources();
 assert.deepEqual(f.closed,conflicting?[]:['target-owned']);assert.equal(f.sends,0);assert.equal(f.resumes,1);
 assert.deepEqual(f.store.snapshot().lifecycle.controls,{});assert.deepEqual(f.store.snapshot().lifecycle.retired,{});
});
