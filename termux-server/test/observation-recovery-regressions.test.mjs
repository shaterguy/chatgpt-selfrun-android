import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture} from './helpers/lifecycle-fixture.mjs';
import {keyFor} from '../src/lifecycle.mjs';

test('response progress survives eleven minutes of Result transport failure without a recovery input',async t=>{
  let now=1700000000000;t.mock.method(Date,'now',()=>now);
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());
  await f.existing();await f.observe({});
  const read=f.transport.readGoogleDocText;
  f.transport.readGoogleDocText=async()=>{throw Error('temporary Result transport failure');};
  for(let minute=1;minute<=11;minute++) {
    now+=60000;Object.assign(f.probe,{responseFingerprint:'progress-'+minute,responseTextLength:955+minute});
    await f.observe({});
    assert.equal(f.record().last_progress_at,now);
    assert.equal(f.record().cursor.responseFingerprint,f.probe.responseFingerprint);
    assert.equal(f.record().error,null);
    assert.equal(f.sends,0);assert.equal(f.quiesces,0);
  }
  f.transport.readGoogleDocText=read;now+=31000;
  await f.controller.tick(f.transport);await f.observe({});
  assert.equal(f.sends,0);assert.equal(f.quiesces,0);assert.equal(f.record().error,null);
});

test('ordinary progress does not read Result or create an availability error',async t=>{
  let now=1700000000000;t.mock.method(Date,'now',()=>now);
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.observe({});
  let reads=0;f.transport.readGoogleDocText=async()=>{reads++;throw Error('unavailable');};
  now+=1000;await f.observe({});const firstReads=reads;
  now+=1000;await f.observe({responseFingerprint:'during-retry',responseTextLength:1000});
  assert.equal(reads,firstReads);assert.equal(f.record().last_progress_at,now);
  assert.equal(f.record().error,null);assert.equal(f.sends,0);
});

test('normal identical observation clears its own outage warning without inventing progress',async t=>{
  let now=1700000000000;t.mock.method(Date,'now',()=>now);
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());
  const normal=f.browser.monitor;let attempts=0;
  f.browser.monitor=async args=>{if(++attempts===1){await args.onActivity({...f.probe});throw Error('CDP command timeout: Runtime.evaluate');}return normal(args);};
  await f.existing();await f.settle();assert.equal(f.record().error.code,'OBSERVATION_UNAVAILABLE');
  const progressAt=f.record().last_progress_at;
  now+=2000;await f.controller.tick(f.transport);await f.settle();await f.observe({});
  assert.equal(f.record().error,null);assert.equal(f.record().last_progress_at,progressAt);
  assert.equal(f.rows.get(f.path).server_error_class,undefined);assert.equal(f.sends,0);
});

test('normal identical observation does not erase a composer warning',async t=>{
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.observe({});
  await f.controller.repository.move(keyFor(f.body),'OBSERVING','fixture composer error',{error:{code:'COMPOSER_UNAVAILABLE',message:'input unavailable'}});
  await f.observe({});assert.equal(f.record().error.code,'COMPOSER_UNAVAILABLE');
});

test('a different owned-tab address remains observable without transferring input authority or reading Result early',async t=>{
  let now=1700000000000;t.mock.method(Date,'now',()=>now);
  const f=fixture();let closed=0,resultReads=0;
  f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:async(id,{guard})=>{
    if(!guard({id,url:'https://chatgpt.com/c/other'}))return {closed:false};closed++;return {closed:true};
  },releaseIfUnused:async()=>({released:false})};
  const read=f.transport.readGoogleDocText;f.transport.readGoogleDocText=async id=>{resultReads++;return read(id);};
  f.controller=f.recreate();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.observe({});
  const original=f.record().conversation_url,monitor=f.observations.at(-1);
  f.probe.url='https://chatgpt.com/c/other';f.result(true);await f.observe({});await f.controller.tick(f.transport);await f.settle();
  await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.record().error,null);assert.equal(f.rows.get(f.path).lifecycle_state,undefined);
  assert.equal(f.record().conversation_url,original);assert.equal(f.rows.get(f.path).conversation_url,f.probe.url);
  assert.equal(f.sends,0);assert.equal(f.quiesces,0);assert.equal(closed,0);assert.equal(resultReads,0);
  assert.equal(f.controller.sessions.size,1);assert.equal(monitor.signal.aborted,false);
  // At the real quiet deadline, Result is consulted once; the actual input
  // snapshot still refuses to transfer the original conversation's authority.
  f.result(false);now+=600000;await f.controller.tick(f.transport);await f.settle();
  assert.equal(resultReads,1);assert.equal(f.sends,0);assert.equal(f.quiesces,0);assert.equal(closed,0);
  assert.match(f.record().error.message,/recovery conversation mismatch/);assert.equal(monitor.signal.aborted,false);
  await f.observe({responseFingerprint:'later-other-response',responseTextLength:1001});
  assert.equal(f.record().cursor.responseFingerprint,'later-other-response');assert.equal(f.controller.sessions.size,1);
  await f.stop();await f.controller.cleanupTerminalResources();await monitor.onActivity({...f.probe});await f.controller.tick(f.transport);await f.settle();
  assert.equal(f.record(),null);assert.equal(f.controller.sessions.size,0);assert.equal(monitor.signal.aborted,true);
  assert.equal(f.sends,0);assert.equal(f.quiesces,0);assert.equal(closed,0);
});
