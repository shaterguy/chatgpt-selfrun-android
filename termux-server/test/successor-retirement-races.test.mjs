import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';
import { keyFor } from '../src/lifecycle.mjs';

const idle=()=>new Promise(resolve=>setImmediate(resolve));
function successorFixture() {
  const f=fixture();
  f.closed=[];f.closeCalls=[];f.resultReads=[];f.resumeUrls=[];f.sessionCloses=[];f.owned=new Set();f.targetUrls=new Map();
  const read=f.transport.readGoogleDocText;
  f.transport.readGoogleDocText=async id=>{f.resultReads.push(id);return read(id);};
  f.browser.chromium={
    isOwnedTarget:id=>f.owned.has(id),
    closeOwnedTarget:async(id,{guard})=>{
      f.closeCalls.push(id);await f.beforeClose?.(id);
      if(!guard({id,url:f.targetUrls.get(id)}))return {closed:false,reason:'guard'};
      if(!f.owned.delete(id))return {absent:true};f.closed.push(id);return {closed:true};
    },
    releaseIfUnused:async()=>({released:false}),
  };
  f.browser.resume=async args=>{
    f.resumes++;f.resumeUrls.push(args.conversationUrl);
    const id=args.conversationUrl===f.probe.url?'target-t1':'target-t2';
    f.owned.add(id);f.targetUrls.set(id,args.conversationUrl);
    await args.onTarget?.({id});
    return {target:{id},session:{id,close(){f.sessionCloses.push(id);}},baseline:{...f.probe,url:args.conversationUrl}};
  };
  f.controller=f.recreate();f.controller.config.stallAfterMs=600000;
  f.firstKey=keyFor(f.body);
  f.nextBody={...f.body,turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',result_document_id:'RESULT-2',
    conversation_url:'https://chatgpt.com/c/successor-owned',server_status:'STARTED',server_control_epoch:2};
  f.nextPath='__SELFRUN_DISPATCH__successor.json';f.nextKey=keyFor(f.nextBody);f.result(false,'RESULT-2',2);
  f.nextControl={...f.control,turn_id:f.nextBody.turn_id,request_id:f.nextBody.request_id,control_epoch:2};
  f.acceptSuccessor=async()=>{
    f.control=f.nextControl;
    await f.controller.control(null,f.control,f.transport);
    f.rows.set(f.nextPath,structuredClone(f.nextBody));
    await f.controller.ingest(f.nextPath,f.nextBody,f.transport);
    f.nextObserver=f.observations.at(-1);
    await f.nextObserver.onActivity({...f.probe,url:f.nextBody.conversation_url,responseTextLength:276});
    assert.equal(f.controller.repository.get(f.nextKey).state,'OBSERVING','T2 must keep observing independently');
    assert.equal(f.nextObserver.signal.aborted,false,'T2 observer must remain alive');
    assert.equal(f.controller.repository.get(f.nextKey).cursor.responseTextLength,276,'T2 progress must be saved');
  };
  return f;
}

test('T1 Result commits before T2 control: old retirement still happens after its observer is displaced',async t=>{
  const f=successorFixture();t.after(()=>f.controller.quiesceForStandby());
  await f.existing();await f.observe({});const oldObserver=f.observations.at(-1);
  assert.deepEqual(f.resultReads,['RESULT-1']);
  f.result(true); // The app consumes this and starts T2 before the next T1 poll.
  await f.acceptSuccessor();
  await oldObserver.onActivity({...f.probe});
  await f.controller.tick(f.transport);await f.controller.cleanupTerminalResources();await idle();
  const old=f.controller.repository.get(f.firstKey),snapshot=f.store.snapshot().lifecycle;
  t.diagnostic(JSON.stringify({oldState:old.state,oldResultState:old.result_state,oldObserverAborted:oldObserver.signal.aborted,
    oldProjectedState:f.rows.get(f.path).lifecycle_state,oldProjectedStatus:f.rows.get(f.path).server_status,
    resultReads:f.resultReads,cleanupQueued:Object.keys(snapshot.browserCleanup),closeCalls:f.closeCalls,closed:f.closed,
    successorState:f.controller.repository.get(f.nextKey).state,successorObserverAborted:f.nextObserver.signal.aborted}));
  assert.equal(oldObserver.signal.aborted,true);
  assert.equal(f.sends,0,'No prompt or recovery may be sent by retired T1');
  assert.equal(f.resumes,2,'T1 must not be reopened');
  assert.equal(old.state,'COMMITTED','exact T1 committed Result must retire independently of T2 control');
  assert.equal(old.retired,true);
  assert.deepEqual(f.closed,['target-t1']);
  assert.equal(f.nextObserver.signal.aborted,false);
});

for(const ingress of ['applied-control','http-control','http-dispatch'])test(
  'already committed T1 owned close survives independent T2 '+ingress,
  async t=>{
    const f=successorFixture();t.after(()=>f.controller.quiesceForStandby());
    await f.existing();const entered=deferred(),gate=deferred();
    f.beforeClose=async id=>{if(id==='target-t1'){entered.resolve();await gate.promise;}};
    f.result(true);await f.observe({});await entered.promise;
    assert.equal(f.controller.repository.get(f.firstKey).state,'COMMITTED');
    if(ingress==='applied-control')await f.acceptSuccessor();
    else await f.store.patch({httpIngress:{files:{next:ingress==='http-control'?f.nextControl:f.nextBody},signals:{}}});
    gate.resolve();await f.controller.cleanupTerminalResources();await idle();
    t.diagnostic(JSON.stringify({ingress,oldState:f.controller.repository.get(f.firstKey).state,
      queued:Object.keys(f.controller.repository.data.browserCleanup),closeCalls:f.closeCalls,closed:f.closed,
      canClose:f.controller.repository.data.browserCleanup['target-t1']?f.controller.browserCleanup.canClose(f.controller.repository.data.browserCleanup['target-t1']):null}));
    assert.deepEqual(f.closed,['target-t1'],'T2 on another owned target cannot fence consumed T1 cleanup');
    assert.equal(f.owned.has('target-t1'),false);
    if(ingress==='applied-control'){
      assert.equal(f.owned.has('target-t2'),true);assert.equal(f.nextObserver.signal.aborted,false);
      assert.equal(f.controller.repository.get(f.nextKey).state,'OBSERVING');
    }
  },
);

const settleRetirement=async f=>{
  for(let i=0;i<5;i++){
    await Promise.all([...(f.controller.resultRetirements?.values()||[])]);
    await idle();
  }
};

test('displaced exact-Result read cannot block accepting or observing T2',async t=>{
  const f=successorFixture(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
  await f.existing();await f.observe({});f.result(true);
  const read=f.transport.readGoogleDocText;let reading=false;
  f.transport.readGoogleDocText=async id=>{if(id==='RESULT-1'){reading=true;await gate.promise;}return read(id);};
  await f.acceptSuccessor();await idle();
  assert.equal(f.nextObserver.signal.aborted,false);assert.equal(f.controller.repository.get(f.nextKey).state,'OBSERVING');
  assert.equal(reading,true,'displaced Result reconciliation must start without awaiting T2');
  assert.equal(f.controller.repository.get(f.firstKey).state,'PAUSED');
  gate.resolve();await settleRetirement(f);await f.controller.cleanupTerminalResources();
  assert.equal(f.controller.repository.get(f.firstKey).state,'COMMITTED');assert.deepEqual(f.closed,['target-t1']);
  assert.equal(f.resumeUrls.filter(url=>url===f.probe.url).length,1);assert.equal(f.sends,0);
});

for(const outcome of ['false','unavailable','wrong-identity','unstable'])test(
  'displaced '+outcome+' Result retries at a durable deadline across restart',async t=>{
    let now=100000;t.mock.method(Date,'now',()=>now);
    const f=successorFixture();t.after(()=>f.controller.quiesceForStandby());
    await f.existing();await f.observe({});const read=f.transport.readGoogleDocText;let calls=0;
    f.transport.readGoogleDocText=async id=>{
      if(id!=='RESULT-1')return read(id);calls++;
      if(outcome==='unavailable')throw Error('offline');
      if(outcome==='wrong-identity')return JSON.stringify({...JSON.parse(f.docs.get(id)),committed:true,task_id:'WRONG'});
      if(outcome==='unstable')return f.docs.get(id)+(calls%2?'':' ');
      return read(id);
    };
    if(outcome==='unstable')f.result(true);
    await f.acceptSuccessor();await settleRetirement(f);
    const pending=f.controller.repository.get(f.firstKey),before=calls;
    assert.equal(pending.state,'PAUSED');assert.ok(pending.result_retirement_retry_at>now,'retry deadline is durable');
    assert.deepEqual(f.closed,['target-t1'],'new app entry disposes T1 resources independently of Result commitment');
    await f.controller.tick(f.transport);await settleRetirement(f);assert.equal(calls,before);
    await f.controller.quiesceForStandby();f.controller=f.recreate();f.controller.config.stallAfterMs=600000;
    await f.controller.tick(f.transport);await settleRetirement(f);assert.equal(calls,before,'restart preserves pacing');
    assert.equal(f.resumes,3,'only T2 may reattach after restart');
    f.transport.readGoogleDocText=read;f.result(true);now=pending.result_retirement_retry_at+1;
    await f.controller.tick(f.transport);await settleRetirement(f);await f.controller.cleanupTerminalResources();
    assert.equal(f.controller.repository.get(f.firstKey).state,'COMMITTED');assert.deepEqual(f.closed,['target-t1']);
    assert.equal(f.sends,0);assert.equal(f.resumes,3);
  },
);

for(const change of ['result-document','new-generation','dispatch-attempt','browser-generation'])test('displaced committed proof is fenced by '+change,async t=>{
  const f=successorFixture(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
  await f.existing();f.result(true);const raw=f.docs.get('RESULT-1'),read=f.transport.readGoogleDocText;let reading=false;
  f.transport.readGoogleDocText=async id=>{if(id==='RESULT-1'){reading=true;await gate.promise;return raw;}return read(id);};
  await f.acceptSuccessor();await idle();assert.equal(reading,true);
  if(change==='result-document'){
    f.result(false,'RESULT-REPLACED',1);await f.controller.repository.ingest(f.path,{...f.body,result_document_id:'RESULT-REPLACED'});
  } else if(change==='dispatch-attempt') {
    await f.controller.repository.ingest(f.path,{...f.body,dispatch_attempt:2});
  } else if(change==='browser-generation') {
    const old=f.controller.repository.get(f.firstKey);
    await f.controller.repository.move(f.firstKey,'PAUSED','test new browser generation',{browser_generation:old.browser_generation+1});
  } else {
    f.control={...f.control,turn_id:f.body.turn_id,request_id:f.body.request_id,control_epoch:3};
    await f.controller.control(null,f.control,f.transport);
  }
  gate.resolve();await settleRetirement(f);await f.controller.cleanupTerminalResources();
  assert.notEqual(f.controller.repository.get(f.firstKey).state,'COMMITTED');assert.deepEqual(f.closed,['target-t1']);
  assert.equal(f.resumeUrls.filter(url=>url===f.probe.url).length,1);assert.equal(f.sends,0);
});

for(const claim of ['active-target','active-conversation','http-control-conversation','http-dispatch-conversation','manual-url'])test(
  'consumed T1 cleanup still protects '+claim,async t=>{
    const f=successorFixture(),entered=deferred(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
    await f.existing();f.beforeClose=async()=>{entered.resolve();await gate.promise;};f.result(true);await f.observe({});await entered.promise;
    if(claim==='active-target'||claim==='active-conversation'){
      await f.acceptSuccessor();
      const record=f.controller.repository.get(f.nextKey);
      if(claim==='active-target')await f.controller.repository.move(f.nextKey,record.state,'test target reuse',{target_id:'target-t1'});
      else {
        // Construct an already-durable conflicting owner directly; normal ingress rejects this collision.
        await f.controller.repository.serial(async()=>{
          const next=structuredClone(f.controller.repository.data);next.requests[f.nextKey].conversation_url=f.probe.url;
          await f.controller.repository.save(next);
        });
      }
    } else if(claim==='manual-url')f.targetUrls.set('target-t1','https://example.test/manual');
    else await f.store.patch({httpIngress:{files:{next:claim==='http-control-conversation'?{...f.nextControl,conversation_url:f.probe.url}:{...f.nextBody,conversation_url:f.probe.url}},signals:{}}});
    gate.resolve();await f.controller.cleanupTerminalResources();assert.deepEqual(f.closed,[]);assert.equal(f.owned.has('target-t1'),true);
  },
);

test('snapshot/audit integrity quarantine also fences displaced retirement',async t=>{
  const f=successorFixture();t.after(()=>f.controller.quiesceForStandby());
  await f.existing();f.result(true);f.controller.repository.integrityFaults.add(f.firstKey);
  await f.acceptSuccessor();await settleRetirement(f);
  assert.equal(f.controller.repository.get(f.firstKey).state,'PAUSED');assert.deepEqual(f.closed,['target-t1']);
  assert.equal(f.resultReads.filter(id=>id==='RESULT-1').length,0);
});

test('failed telemetry cannot starve displaced Result retirement after restart',async t=>{
  let now=100000;t.mock.method(Date,'now',()=>now);
  const f=successorFixture();t.after(()=>f.controller.quiesceForStandby());
  await f.existing();await f.acceptSuccessor();await settleRetirement(f);
  const old=f.controller.repository.get(f.firstKey);assert.ok(old.result_retirement_retry_at>now);
  await f.controller.repository.move(f.firstKey,'PAUSED','test pending telemetry');
  await f.controller.quiesceForStandby();f.controller=f.recreate();f.controller.config.stallAfterMs=600000;
  now=old.result_retirement_retry_at+1;f.result(true);f.writeFail=true;
  await f.controller.tick(f.transport).catch(()=>{});await settleRetirement(f);await f.controller.cleanupTerminalResources();
  assert.equal(f.controller.repository.get(f.firstKey).state,'COMMITTED');assert.deepEqual(f.closed,['target-t1']);
  assert.equal(f.resumeUrls.filter(url=>url===f.probe.url).length,1);assert.equal(f.sends,0);
});

test('never-started stale-control input is not mistaken for displaced browser work',async t=>{
  const f=successorFixture();t.after(()=>f.controller.quiesceForStandby());
  await f.controller.initialize();await f.controller.repository.ingest(f.path,f.body);
  f.control=f.nextControl;await f.controller.controlDurable(null,f.control,f.transport);await settleRetirement(f);
  assert.equal(f.resultReads.length,0);assert.equal(f.prepares,0);assert.equal(f.resumes,0);assert.equal(f.sends,0);
});

for(const boundary of ['availability','standby','STOP'])test('pending displaced proof respects '+boundary,async t=>{
  const f=successorFixture(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
  await f.existing();f.result(true);const read=f.transport.readGoogleDocText;let reading=false;
  f.transport.readGoogleDocText=async id=>{if(id==='RESULT-1'){reading=true;await gate.promise;}return read(id);};
  await f.acceptSuccessor();await idle();assert.equal(reading,true);
  if(boundary==='availability')f.controller.canDispatch=()=>false;
  else if(boundary==='standby')await f.controller.quiesceForStandby();
  else {f.control={...f.nextControl,state:'STOPPED',control_epoch:3};await f.controller.control(null,f.control,f.transport);}
  gate.resolve();await settleRetirement(f);await f.controller.cleanupTerminalResources();
  assert.notEqual(f.controller.repository.get(f.firstKey).state,'COMMITTED');assert.equal(f.sends,0);
  if(boundary!=='STOP')assert.deepEqual(f.closed,['target-t1'],'already completed resource disposal is independent of a later proof boundary');
});
