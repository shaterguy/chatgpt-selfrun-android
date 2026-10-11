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
  const existing=f.existing;f.existing=async()=>{await existing();f.resultReads=[];};
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

test('successor CONTROL changes the monitored identity without consuming the prior Result',async t=>{
  const f=successorFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.observe({});
  const observer=f.observations.at(-1);f.result(true);await f.acceptSuccessor();await f.controller.cleanupTerminalResources();
  await observer.onActivity({...f.probe,status:'STALLED'});await f.controller.tick(f.transport);
  assert.equal(observer.signal.aborted,true);assert.equal(f.nextObserver.signal.aborted,false);assert.equal(f.sends,0);assert.equal(f.resumes,2);
  assert.equal(f.controller.repository.get(f.firstKey),null);assert.equal(f.controller.repository.data.retired[f.firstKey],undefined);assert.deepEqual(f.closed,['target-t1']);
  assert.deepEqual(f.resultReads,[]);assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
});

for(const ingress of ['applied-control','http-control','http-dispatch'])test('explicit DONE close remains independent of successor '+ingress,async t=>{
  const f=successorFixture(),entered=deferred(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
  await f.existing();f.beforeClose=async id=>{if(id==='target-t1'){entered.resolve();await gate.promise;}};
  f.control={...f.control,state:'DONE',control_epoch:2};await f.controller.control(null,f.control,f.transport);await entered.promise;
  f.nextControl={...f.nextControl,control_epoch:3};f.nextBody.server_control_epoch=3;
  if(ingress==='applied-control')await f.acceptSuccessor();
  else await f.store.patch({httpIngress:{files:{next:ingress==='http-control'?f.nextControl:f.nextBody},signals:{}}});
  gate.resolve();await f.controller.cleanupTerminalResources();
  assert.equal(f.controller.repository.get(f.firstKey),null);assert.deepEqual(f.closed,['target-t1']);assert.deepEqual(f.resultReads,[]);
  if(ingress==='applied-control'){assert.equal(f.owned.has('target-t2'),true);assert.equal(f.nextObserver.signal.aborted,false);}
});

test('pending deadline read from the displaced observer does not block or retire successor',async t=>{
  const f=successorFixture(),entered=deferred(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
  await f.existing();await f.observe({});f.result(true);
  await f.controller.repository.move(f.firstKey,'OBSERVING','test deadline',{last_progress_at:Date.now()-600001});
  const read=f.transport.readGoogleDocText;f.transport.readGoogleDocText=async id=>{if(id==='RESULT-1'){entered.resolve();await gate.promise;}return read(id);};
  const observing=f.observe({});await entered.promise;await f.acceptSuccessor();
  assert.equal(f.nextObserver.signal.aborted,false);gate.resolve();await observing;await f.controller.cleanupTerminalResources();
  assert.equal(f.controller.repository.get(f.nextKey).state,'OBSERVING');assert.equal(f.controller.repository.get(f.firstKey),null);
  assert.equal(f.sends,0);assert.equal(f.resumes,2);assert.deepEqual(f.closed,['target-t1']);
});

for(const result of ['false','true','unavailable','wrong-identity'])test('displaced '+result+' Result is not read or put on a retirement queue across restart',async t=>{
  let now=100000;t.mock.method(Date,'now',()=>now);const f=successorFixture();t.after(()=>f.controller.quiesceForStandby());
  await f.existing();if(result==='true')f.result(true);if(result==='unavailable')f.docs.delete('RESULT-1');
  if(result==='wrong-identity')f.docs.set('RESULT-1',JSON.stringify({committed:true,task_id:'WRONG'}));
  await f.acceptSuccessor();await f.controller.cleanupTerminalResources();
  assert.deepEqual(f.resultReads,[]);assert.equal(f.controller.repository.get(f.firstKey),null);
  for(const field of ['requests','retired','outbox','completionOutbox','browserCleanup','browserCleanupOutcomes','browserCleanupUnresolved'])
    assert.equal(Object.values(f.store.snapshot().lifecycle[field]||{}).some(row=>row.key===f.firstKey||row.entry?.key===f.firstKey),false,field);
  await f.controller.quiesceForStandby();f.controller=f.recreate();now+=30000;await f.controller.tick(f.transport);
  assert.deepEqual(f.resultReads,[]);assert.equal(f.resumes,3);assert.equal(f.sends,0);assert.equal(f.controller.sessions.has(f.firstKey),false);assert.equal(f.controller.repository.get(f.firstKey),null);assert.equal(f.controller.repository.cleanupEntries().length,0);
  assert.deepEqual(f.closed,['target-t1']);
});

for(const claim of ['active-target','active-conversation','http-control-conversation','http-dispatch-conversation','manual-url'])test('explicit terminal cleanup protects '+claim,async t=>{
  const f=successorFixture(),entered=deferred(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
  await f.existing();f.beforeClose=async()=>{entered.resolve();await gate.promise;};
  f.control={...f.control,state:'DONE',control_epoch:2};await f.controller.control(null,f.control,f.transport);await entered.promise;
  f.nextControl={...f.nextControl,control_epoch:3};f.nextBody.server_control_epoch=3;
  if(claim==='active-target'||claim==='active-conversation'){
    await f.acceptSuccessor();const record=f.controller.repository.get(f.nextKey);
    if(claim==='active-target')await f.controller.repository.move(f.nextKey,record.state,'test target reuse',{target_id:'target-t1'});
    else await f.controller.repository.serial(async()=>{const next=structuredClone(f.controller.repository.data);next.requests[f.nextKey].conversation_url=f.probe.url;await f.controller.repository.save(next);});
  }else if(claim==='manual-url')f.targetUrls.set('target-t1','https://example.test/manual');
  else await f.store.patch({httpIngress:{files:{next:claim==='http-control-conversation'?{...f.nextControl,conversation_url:f.probe.url}:{...f.nextBody,conversation_url:f.probe.url}},signals:{}}});
  gate.resolve();await f.controller.cleanupTerminalResources();assert.deepEqual(f.closed,[]);assert.equal(f.owned.has('target-t1'),true);
  assert.equal(f.controller.repository.get(f.firstKey),null);assert.deepEqual(f.resultReads,[]);
});

test('explicit task DONE discards current and displaced requests without reading either Result',async t=>{
  const f=successorFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.acceptSuccessor();
  f.control={...f.nextControl,state:'DONE',control_epoch:3};await f.controller.control(null,f.control,f.transport);await f.controller.cleanupTerminalResources();
  assert.deepEqual(f.controller.repository.records(),[]);assert.equal(f.controller.sessions.size,0);assert.deepEqual(f.resultReads,[]);
  assert.deepEqual(f.store.snapshot().lifecycle.requests,{});assert.deepEqual(f.store.snapshot().lifecycle.retired,{});assert.deepEqual(f.store.snapshot().lifecycle.controls,{});
});

test('never-started stale-control input causes no browser side effect or Result read',async t=>{
  const f=successorFixture();t.after(()=>f.controller.quiesceForStandby());await f.controller.initialize();await f.controller.repository.ingest(f.path,f.body);
  f.control=f.nextControl;await f.controller.controlDurable(null,f.control,f.transport);
  assert.deepEqual(f.resultReads,[]);assert.equal(f.prepares,0);assert.equal(f.resumes,0);assert.equal(f.sends,0);
});
