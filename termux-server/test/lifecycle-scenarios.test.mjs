import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { StateStore } from '../src/state-store.mjs';
import { keyFor, transition } from '../src/lifecycle.mjs';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';
import { fixture, MemoryStore, deferred } from './helpers/lifecycle-fixture.mjs';

test('01 new conversation request -> server-owned POST -> progress -> exact Result commit',async()=>{
  const f=fixture();await f.ingest();assert.equal(f.sends,1);assert.equal(f.record().state,'OBSERVING');
  await f.observe({responseTextLength:1000});f.result(true);await f.observe({});
  assert.equal(f.record().state,'COMPLETED');assert.equal(f.rows.get(f.path).server_status,'COMPLETED');
});
test('02 initial POST accepted but timed out is read back without duplicate',async()=>{
  const f=fixture();f.mode='accepted-timeout';await f.ingest();
  assert.equal(f.sends,1);assert.equal(f.record().intent.outcome,'CONFIRMED');
});
test('03 proven absent POST gets exactly one retry after readback',async()=>{
  const f=fixture();f.mode='absent';await f.ingest();
  assert.equal(f.sends,2);assert.equal(f.record().intent.sends,2);assert.ok(f.readbacks>=2);
});
test('04 existing conversation attach does not send continuation',async()=>{
  const f=fixture();await f.existing();assert.equal(f.resumes,1);assert.equal(f.sends,0);assert.equal(f.record().conversation_url,f.probe.url);
});
test('05 attach failure retains conversation and classified bounded retry',async()=>{
  const f=fixture();f.attachError=Object.assign(new Error('attach pending'),{code:'ATTACH_FAILED'});await f.existing();
  assert.equal(f.record().error.code,'ATTACH_FAILED');assert.equal(f.record().conversation_url,f.probe.url);assert.equal(f.prepares,0);
});
test('06 browser death reconnects to the same canonical conversation',async()=>{
  const f=fixture();await f.existing();const a=f.observations.at(-1);
  await f.controller.quiesceForStandby();f.controller.standby=false;await f.controller.tick(f.transport);
  assert.equal(f.resumes,2);assert.equal(f.prepares,0);assert.equal(a.signal.aborted,true);
});
test('07 server restart restores durable running request without input',async()=>{
  const f=fixture();await f.existing();await f.controller.quiesceForStandby();
  f.controller=f.recreate();await f.controller.tick(f.transport);
  assert.equal(f.resumes,2);assert.equal(f.sends,0);assert.equal(f.record().state,'OBSERVING');
});
test('08 unchanged streaming cursor stalls and quiesces before recovery',async()=>{
  const f=fixture();await f.existing();await f.stall();assert.equal(f.quiesces,1);assert.equal(f.sends,1);
});
test('09 recovery success is durably confirmed',async()=>{
  const f=fixture();await f.existing();await f.stall();assert.equal(f.record().recovery_count,1);
  assert.equal(f.record().intent.outcome,'CONFIRMED');assert.equal(f.rows.get(f.path).recovery_count,1);
});
test('10 recovery POST timeout accepted readback prevents duplicate',async()=>{
  const f=fixture();f.mode='accepted-timeout';await f.existing();await f.stall();await f.controller.tick(f.transport);
  assert.equal(f.sends,1);assert.equal(f.record().intent.outcome,'CONFIRMED');
});
test('11 Result commits during recovery before SEND',async()=>{
  const f=fixture();f.onQuiesce=()=>f.result(true);await f.existing();await f.stall();
  assert.equal(f.sends,0);assert.equal(f.record().state,'COMPLETED');
});
test('12 STOP during POST fences late completion',async()=>{
  const f=fixture();const gate=deferred(),entered=deferred();f.onSubmit=async()=>{entered.resolve();await gate.promise;};
  const sending=f.ingest();await entered.promise;
  await f.stop();gate.resolve();await sending;
  assert.equal(f.record().state,'STOPPED');assert.equal(f.sends,1);
});
test('13 STOP then explicit higher epoch resumes existing request without recreation',async()=>{
  const f=fixture();await f.existing();await f.stop();await f.resume();
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.resumes,2);assert.equal(f.sends,0);
});
test('14 stale STOP epoch cannot affect resumed request',async()=>{
  const f=fixture();await f.existing();await f.stop();const stale={...f.control};await f.resume();
  await f.controller.control(null,stale,f.transport);assert.equal(f.record().control_state,'RUNNING');
});
test('15 predecessor exact commit gates successor preparation',async()=>{
  const f=fixture();f.body.turn_id='SAFE-TEST:turn:2';f.body.request_id='SAFE-TEST:turn:2-request';f.control.turn_id=f.body.turn_id;f.control.request_id=f.body.request_id;f.result(false,'RESULT-1',2);f.body.previous_result_document_id='PREVIOUS';f.result(false,'PREVIOUS',1);await f.ingest();
  assert.equal(f.prepares,0);f.result(true,'PREVIOUS',1);
  await f.controller.repository.move(keyFor(f.body),'BLOCKED','test retry ready',{retry_at:0});
  await f.controller.tick(f.transport);assert.equal(f.prepares,1);
});
test('16 old browser callback cannot overwrite a new generation',async()=>{
  const f=fixture();await f.existing();const old=f.observations.at(-1);await f.stop();await f.resume();
  const rev=f.record().revision;await old.onActivity({...f.probe,responseTextLength:99999});
  assert.equal(f.record().revision,rev);
});
test('17 duplicate concurrent scans do not duplicate prepare or submission',async()=>{
  const f=fixture();const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
  await Promise.all([watcher.scanOnce(),watcher.scanOnce()]);
  for(let i=0;i<16&&(f.prepares===0||f.sends===0);i++)await new Promise(resolve=>setImmediate(resolve));
  assert.equal(f.prepares,1);assert.equal(f.sends,1);
});
test('18 pending event and Drive projection replay from canonical state after restart',async()=>{
  const f=fixture();await f.existing();f.writeFail=true;
  await assert.rejects(f.controller.repository.flush({...f.transport,read:async p=>f.rows.get(p),write:async()=>{throw new Error('fail');}}).then(async()=>{
    await f.controller.repository.move(keyFor(f.body),'STALLED','test durable');
    await f.controller.repository.flush(f.transport);
  }));
  f.controller=f.recreate();f.writeFail=false;await f.controller.repository.flush(f.transport);
  assert.equal(f.rows.get(f.path).lifecycle_state,'STALLED');
  assert.equal(f.rows.get(f.path).lifecycle_revision,f.record().revision);
});
test('19 existing conversation timeout cannot reach retry count 45',async()=>{
  const f=fixture();f.attachError=new Error('Timed out waiting for existing conversation');await f.existing();
  for(let i=0;i<6;i++){
    await f.controller.repository.move(keyFor(f.body),'BLOCKED','test elapsed backoff',{retry_at:0});
    await f.controller.tick(f.transport);
  }
  assert.equal(f.resumes,3);assert.equal(f.record().state,'BLOCKED');assert.equal(f.record().error.code,'CONVERSATION_LOAD_FAILED');
});
test('20 stalled canonical POST timeout stays uncertain with monotonic recovery count',async()=>{
  const f=fixture();f.mode='unknown';await f.existing();await f.stall();
  assert.equal(f.record().state,'POST_UNCERTAIN');assert.equal(f.record().recovery_count,1);
  await f.controller.tick(f.transport);assert.equal(f.sends,1);
  assert.match(f.rows.get(f.path).server_error,/POST timeout/);
});
test('disk failure cannot expose an unpersisted snapshot',async()=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-state-'));
  try {
    const config={dataDir:dir,stateFile:path.join(dir,'state.json'),eventsFile:path.join(dir,'events.jsonl')};
    const store=new StateStore(config);await store.init();await store.patch({status:'RUNNING'});
    store.config={...config,stateFile:path.join(dir,'missing','state.json')};
    await assert.rejects(store.patch({status:'COMPLETED'}));assert.equal(store.snapshot().status,'RUNNING');
  }finally{await fs.rm(dir,{recursive:true,force:true});}
});
test('Result regex fragments and wrong identity never finalize',async()=>{
  const f=fixture();await f.existing();f.docs.set('RESULT-1','broken {"committed":true}');
  await f.observe({});assert.notEqual(f.record().state,'COMPLETED');assert.equal(f.sends,0);
});
test('identity cannot change during lifecycle transition',async()=>{
  const f=fixture();await f.existing();assert.throws(()=>transition(f.record(),'OBSERVING','bad',{request_id:'other'}),/immutable/);
});

test('recovery read failure resumes its durable verification stage',async()=>{
  const f=fixture();await f.existing();await f.observe({});
  await f.controller.repository.move(keyFor(f.body),'STALLED','test stalled',{last_progress_at:0});
  await f.controller.repository.move(keyFor(f.body),'VERIFY_RESULT','test result read failed',{result_state:'UNAVAILABLE'});
  f.docs.set('RESULT-1','invalid');
  await f.controller.tick(f.transport);assert.equal(f.sends,0);
  f.result(false);await f.controller.tick(f.transport);assert.equal(f.sends,1);
});
test('directive changes cannot change an already durable uncertain input',async()=>{
  const f=fixture();f.mode='unknown';await f.existing();await f.stall();
  const prompt=f.record().intent.prompt;
  f.controller.config.recoveryPrompt='different directive';
  let readPrompt;
  const original=f.browser.readSubmission;
  f.browser.readSubmission=async args=>{readPrompt=args.prompt;return original(args);};
  await f.controller.tick(f.transport);assert.equal(readPrompt,prompt);assert.equal(f.sends,1);
});
test('a committed non-predecessor Result cannot unlock a successor',async()=>{
  const f=fixture();f.body.turn_id='SAFE-TEST:turn:4';f.body.request_id='SAFE-TEST:turn:4-request';
  f.control.turn_id=f.body.turn_id;f.control.request_id=f.body.request_id;
  f.result(false,'RESULT-1',4);f.result(true,'WRONG-PREVIOUS',1);f.body.previous_result_document_id='WRONG-PREVIOUS';
  await f.ingest();assert.equal(f.prepares,0);assert.equal(f.record().error.code,'PREDECESSOR_NOT_COMMITTED');
});
test('Drive publishing preserves current client claim and prompt fields',async()=>{
  const f=fixture();await f.existing();
  await f.controller.repository.move(keyFor(f.body),'STALLED','test publication');
  f.rows.get(f.path).client_claim_nonce='new-claim';f.rows.get(f.path).prompt='new client value';
  await f.controller.repository.flush(f.transport);
  assert.equal(f.rows.get(f.path).client_claim_nonce,'new-claim');assert.equal(f.rows.get(f.path).prompt,'new client value');
});
test('slow Drive publication cannot block local STOP persistence',async()=>{
  const f=fixture();await f.existing();
  await f.controller.repository.move(keyFor(f.body),'STALLED','test publication');
  const gate=deferred(),entered=deferred();
  const publishing=f.controller.repository.flush({...f.transport,write:async()=>{entered.resolve();await gate.promise;}});
  await entered.promise;
  const c={...f.control,state:'STOPPED',control_epoch:2};
  await f.controller.repository.applyControl(c);
  assert.equal(f.record().state,'STOPPED');gate.resolve();await publishing;
});
test('real disk restart restores request and exact intent',async()=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-restart-'));
  const config={dataDir:dir,stateFile:path.join(dir,'state.json'),eventsFile:path.join(dir,'events.jsonl')};
  try {
    const store=new StateStore(config);await store.init();const f=fixture({store});f.mode='unknown';
    await f.existing();await f.stall();const before=f.record();
    await f.controller.quiesceForStandby();
    const second=new StateStore(config);await second.init();f.store=second;f.controller=f.recreate();
    await f.controller.tick(f.transport);
    assert.equal(f.record().intent.id,before.intent.id);assert.equal(f.record().recovery_count,1);assert.equal(f.sends,1);
  }finally{await fs.rm(dir,{recursive:true,force:true});}
});
test('Drive durable record can restore without previous process memory',async()=>{
  const f=fixture();await f.existing();f.mode='unknown';await f.stall();
  const projected=structuredClone(f.rows.get(f.path)),id=f.record().intent.id;
  await f.controller.quiesceForStandby();f.store=new MemoryStore();f.controller=f.recreate();
  await f.controller.ingest(f.path,projected,f.transport);
  assert.equal(f.record().intent.id,id);assert.equal(f.sends,1);
});

test('legacy event recovery count and uncertain POST override stale dispatch without releasing STOP',async()=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-migrate-'));
  try {
    const config={dataDir:dir,stateFile:path.join(dir,'state.json'),eventsFile:path.join(dir,'events.jsonl')};
    const store=new StateStore(config);await store.init();
    await fs.writeFile(config.eventsFile,JSON.stringify({event:'LIVENESS_RECOVERY_DECISION',details:{task_id:'SAFE-TEST',turn_id:'SAFE-TEST:turn:1',
      request_id:'SAFE-TEST:turn:1-request',recovery_count:1,error:'Canonical conversation POST timeout'}})+'\n');
    const f=fixture({store});f.control.state='STOPPED';f.control.control_epoch=13;await f.existing();
    assert.equal(f.record().state,'STOPPED');assert.equal(f.record().recovery_count,1);assert.equal(f.record().intent.outcome,'UNKNOWN');
    assert.equal(f.sends,0);assert.equal(f.resumes,0);
  }finally{await fs.rm(dir,{recursive:true,force:true});}
});
test('audit newer than restored canonical state is quarantined before browser work',async()=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-audit-'));
  try {
    const config={dataDir:dir,stateFile:path.join(dir,'state.json'),eventsFile:path.join(dir,'events.jsonl')};
    const store=new StateStore(config);await store.init();const f=fixture({store});await f.existing();await f.controller.quiesceForStandby();
    await fs.appendFile(config.eventsFile,JSON.stringify({event:'CONVERSATION_TRANSITION',details:{task_id:'SAFE-TEST',turn_id:'SAFE-TEST:turn:1',
      request_id:'SAFE-TEST:turn:1-request',revision:999}})+'\n');
    f.controller=f.recreate();await assert.rejects(f.controller.tick(f.transport),/behind committed audit/);
    assert.equal(f.resumes,1);assert.equal(f.sends,0);
  }finally{await fs.rm(dir,{recursive:true,force:true});}
});

test('control-first stopped ingestion publishes the stopped canonical record',async()=>{
 const f=fixture();f.control.state='STOPPED';f.control.control_epoch=13;
 await f.controller.control(null,f.control,f.transport);
 await f.existing();await f.controller.flush(f.transport);
 assert.equal(f.record().state,'STOPPED');assert.equal(f.record().control_epoch,13);
 assert.equal(f.rows.get(f.path).server_control_state,'STOPPED');assert.equal(f.sends,0);assert.equal(f.resumes,0);
});
test('successor without predecessor document cannot prepare',async()=>{
 const f=fixture();f.body.turn_id='SAFE-TEST:turn:2';f.body.request_id='SAFE-TEST:turn:2-request';
 f.control.turn_id=f.body.turn_id;f.control.request_id=f.body.request_id;f.result(false,'RESULT-1',2);
 await f.ingest();assert.equal(f.prepares,0);assert.equal(f.record().error.code,'PREDECESSOR_DOCUMENT_REQUIRED');
});
for(const stage of ['STALLED','VERIFY_RESULT','VERIFY_CONVERSATION','VERIFY_CURSOR','QUIESCING','VERIFY_INPUT','RECOVERY_POST','POST_PENDING','POST_UNCERTAIN','RECONCILING']) {
 test('cold restart restores intermediate '+stage,async()=>{
  const f=fixture();f.mode='unknown';await f.existing();await f.stall();await f.controller.quiesceForStandby();
  const snapshot=f.store.snapshot(),record=snapshot.lifecycle.requests[keyFor(f.body)];
  record.state=stage;
  if(!['RECOVERY_POST','POST_PENDING','POST_UNCERTAIN','RECONCILING'].includes(stage))record.intent=null;
  await f.store.patch(snapshot);f.controller=f.recreate();
  await f.controller.tick(f.transport);
  assert.equal(f.record().conversation_url,f.probe.url);assert.equal(f.prepares,0);
  assert.ok(['POST_UNCERTAIN','OBSERVING','BLOCKED'].includes(f.record().state));
  await f.controller.quiesceForStandby();
 });
}
test('legacy unknown input confirmed by readback fences subsequent same-cursor recovery across restart',async()=>{
 const f=fixture();f.mode='unknown';await f.existing();await f.stall();
 const snapshot=f.store.snapshot(),record=snapshot.lifecycle.requests[keyFor(f.body)];
 delete record.last_recovered_response;delete record.last_recovery_epoch;
 await f.controller.quiesceForStandby();await f.store.patch(snapshot);
 f.sentId=record.intent.message_id;f.controller=f.recreate();await f.controller.tick(f.transport);
 assert.equal(f.record().intent.outcome,'CONFIRMED');
 await f.controller.quiesceForStandby();f.controller=f.recreate();await f.controller.tick(f.transport);
 if(f.record().state!=='BLOCKED')await f.observe({status:'STALLED'});
 assert.equal(f.sends,1);assert.equal(f.record().error.code,'RECOVERY_NO_PROGRESS');
});

for(const kind of ['initial','recovery'])test('cold restart after proven absence retries same '+kind+' intent once',async()=>{
 const f=fixture();f.mode='unknown';
 if(kind==='initial'){await f.ingest();f.body.client_status='SEND_REQUESTED';await f.ingest();}
 else{await f.existing();await f.stall();}
 await f.controller.quiesceForStandby();
 const snapshot=f.store.snapshot(),record=snapshot.lifecycle.requests[keyFor(f.body)],id=record.intent.id;
 record.intent={...record.intent,outcome:'ABSENT',absence_proof:'INTERCEPTED_REQUEST_ABORTED',released:false};
 if(kind==='initial'){record.conversation_url='';record.conversation_id=null;}
 await f.store.patch(snapshot);f.mode='success';f.controller=f.recreate();await f.controller.tick(f.transport);
 assert.equal(f.record().intent.id,id);assert.equal(f.sends,2);assert.equal(f.record().intent.outcome,'CONFIRMED');
});
