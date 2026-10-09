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
    assert.equal(f.record().error.code,'RESULT_READ_FAILED');
    assert.equal(f.sends,0);assert.equal(f.quiesces,0);
  }
  f.transport.readGoogleDocText=read;now+=31000;
  await f.controller.tick(f.transport);await f.observe({});
  assert.equal(f.sends,0);assert.equal(f.quiesces,0);assert.equal(f.record().error,null);
});

test('progress within the Result retry window is retained without rereading or clearing its error',async t=>{
  let now=1700000000000;t.mock.method(Date,'now',()=>now);
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.observe({});
  let reads=0;f.transport.readGoogleDocText=async()=>{reads++;throw Error('unavailable');};
  now+=1000;await f.observe({});const firstReads=reads;
  now+=1000;await f.observe({responseFingerprint:'during-retry',responseTextLength:1000});
  assert.equal(reads,firstReads);assert.equal(f.record().last_progress_at,now);
  assert.equal(f.record().error.code,'RESULT_READ_FAILED');assert.equal(f.sends,0);
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
  assert.equal(f.rows.get(f.path).server_error_class,null);assert.equal(f.sends,0);
});

test('normal identical observation does not erase a composer warning',async t=>{
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.observe({});
  await f.controller.repository.move(keyFor(f.body),'OBSERVING','fixture composer error',{error:{code:'COMPOSER_UNAVAILABLE',message:'input unavailable'}});
  await f.observe({});assert.equal(f.record().error.code,'COMPOSER_UNAVAILABLE');
});

test('exact committed Result is consumed even when the observed page has left the owned conversation',async t=>{
  const f=fixture();let closed=0;
  f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:async(id,{guard})=>{
    if(!guard({id,url:'https://chatgpt.com/c/other'}))return {closed:false};closed++;return {closed:true};
  },releaseIfUnused:async()=>({released:false})};
  f.controller=f.recreate();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.observe({});
  f.result(true);await f.observe({url:'https://chatgpt.com/c/other'});await f.controller.tick(f.transport);await f.settle();
  await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);
  assert.equal(f.rows.get(f.path).lifecycle_state,'COMMITTED');assert.equal(f.rows.get(f.path).server_status,'COMPLETED');
  assert.equal(f.sends,0);assert.equal(f.quiesces,0);assert.equal(closed,0);
  assert.equal(f.controller.sessions.size,0);
});
