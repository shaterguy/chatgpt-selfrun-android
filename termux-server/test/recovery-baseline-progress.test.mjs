import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './helpers/lifecycle-fixture.mjs';

test('detected response progress renews the deadline without requiring another baseline read',async t=>{
  const WINDOW=600000;let now=1000000;
  t.mock.method(Date,'now',()=>now);
  const f=fixture();f.controller.config.stallAfterMs=WINDOW;
  f.probe.streaming=false;f.probe.stopButtonVisible=false;
  t.after(()=>f.controller.quiesceForStandby());
  await f.existing();await f.observe({});
  f.browser.livenessSnapshot=async()=>{throw Error('response observation unavailable');};
  now+=540000;
  f.probe.responseFingerprint='actual-observed-progress';
  await f.observe({status:'STALLED'});
  assert.equal(f.sends,0,'detected response progress renews the quiet window');
  assert.equal(f.record().last_progress_at,now);
  assert.equal(f.record().last_nudge_attempt_at,undefined);
  assert.equal(f.record().cursor.responseFingerprint,f.probe.responseFingerprint);
  now+=WINDOW-1;await f.observe({status:'STALLED'});
  assert.equal(f.sends,0);
  now+=1;await f.observe({status:'STALLED'});
  assert.equal(f.sends,1);
});

for(const boundary of ['setup','submit-success','submit-unknown'])test(
  'slow unchanged recovery '+boundary+' cannot permit an immediate following send',async t=>{
    const WINDOW=600000;let now=1000000;
    t.mock.method(Date,'now',()=>now);
    const f=fixture();f.controller.config.stallAfterMs=WINDOW;
    f.probe.streaming=false;f.probe.stopButtonVisible=false;
    t.after(()=>f.controller.quiesceForStandby());
    await f.existing();await f.observe({});
    if(boundary==='setup') {
      let snapshots=0;
      f.browser.recoveryInputSnapshot=async()=>{
        if(++snapshots===1)now+=WINDOW+60000;
        return {...f.probe};
      };
    } else {
      if(boundary==='submit-unknown')f.mode='unknown';
      const submit=f.browser.submitIntent;let delayed=false;
      f.browser.submitIntent=async args=>{
        try{return await submit(args);}
        finally{if(!delayed){delayed=true;now+=WINDOW+60000;}}
      };
    }
    now+=WINDOW;await f.observe({status:'STALLED'});
    assert.equal(f.sends,1);
    const finishedAt=now;
    await f.observe({status:'STALLED'});
    assert.equal(f.sends,1,'an expired pre-UI reservation must not allow a back-to-back send');
    assert.equal(f.record().last_nudge_attempt_at,finishedAt);
    now+=WINDOW-1;await f.observe({status:'STALLED'});
    assert.equal(f.sends,1);
    now+=1;await f.observe({status:'STALLED'});
    assert.equal(f.sends,2);
  },
);

for(const outcome of ['success','quiesce-failure','snapshot-failure'])test(
  'slow recovery quiesce '+outcome+' renews the quiet window at UI completion',async t=>{
    const WINDOW=600000;let now=1000000;
    t.mock.method(Date,'now',()=>now);
    const f=fixture();f.controller.config.stallAfterMs=WINDOW;
    t.after(()=>f.controller.quiesceForStandby());
    await f.existing();await f.observe({});
    let quiesces=0,snapshots=0;
    f.browser.quiesce=async()=>{
      quiesces++;
      if(quiesces===1)now+=WINDOW+60000;
      if(outcome==='quiesce-failure')throw Error('slow quiesce failed');
      f.probe.streaming=false;f.probe.stopButtonVisible=false;
    };
    f.browser.livenessSnapshot=async()=>{
      if(++snapshots===2&&outcome==='snapshot-failure')throw Error('post-quiesce snapshot failed');
      return {...f.probe};
    };
    now+=WINDOW;await f.observe({status:'STALLED'}).catch(()=>{});
    const finishedAt=now,initialSends=outcome==='quiesce-failure'?0:1;
    assert.equal(quiesces,1);assert.equal(f.sends,initialSends);
    assert.equal(f.record().last_nudge_attempt_at,finishedAt);
    now+=30000;await f.observe({status:'STALLED'}).catch(()=>{});
    assert.equal(quiesces,1);assert.equal(f.sends,initialSends);
    now+=WINDOW-30001;await f.observe({status:'STALLED'}).catch(()=>{});
    assert.equal(quiesces,1);assert.equal(f.sends,initialSends);
    now+=1;await f.observe({status:'STALLED'}).catch(()=>{});
    if(outcome==='quiesce-failure')assert.equal(quiesces,2);
    else assert.equal(f.sends,initialSends+1);
  },
);

test('streaming-only recovery verification uses fresh UI state without resetting response progress',async t=>{
  const WINDOW=600000;let now=1000000;
  t.mock.method(Date,'now',()=>now);
  const f=fixture();f.controller.config.stallAfterMs=WINDOW;
  f.probe.streaming=false;f.probe.stopButtonVisible=false;
  t.after(()=>f.controller.quiesceForStandby());
  await f.existing();await f.observe({});
  const progressAt=f.record().last_progress_at;
  f.browser.recoveryInputSnapshot=async()=>({...f.probe,streaming:f.quiesces===0});
  now+=WINDOW;await f.observe({status:'STALLED'});
  assert.equal(f.record().last_progress_at,progressAt,'streaming telemetry is not response text progress');
  assert.equal(f.quiesces,1,'fresh streaming state still selects recovery quiesce');
  assert.equal(f.sends,1,'a telemetry-only change cannot postpone an otherwise due nudge');
  now+=WINDOW-1;await f.observe({status:'STALLED'});assert.equal(f.sends,1);
  now+=1;await f.observe({status:'STALLED'});assert.equal(f.sends,2);
});
