import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';

for(const boundary of ['result-document','STOPPED','DONE','new-generation'])test('pending deadline Result read cannot mutate replaced execution at '+boundary,async t=>{
  const f=fixture(),entered=deferred(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
  await f.existing();await f.observe({});f.result(true);
  await f.controller.repository.move(f.record().key,'OBSERVING','test deadline',{last_progress_at:Date.now()-600001});
  const raw=f.docs.get('RESULT-1');f.transport.readGoogleDocText=async()=>{entered.resolve();await gate.promise;return raw;};
  const observer=f.observations.at(-1),observing=f.observe({});await entered.promise;
  if(boundary==='result-document'){
    f.result(false,'RESULT-2');await f.controller.repository.ingest(f.path,{...f.body,result_document_id:'RESULT-2'});
  } else if(boundary==='new-generation'){
    f.control={...f.control,state:'PAUSED',control_epoch:2};await f.controller.control(null,f.control,f.transport);
    f.control={...f.control,state:'RUNNING',control_epoch:3};await f.controller.control(null,f.control,f.transport);
  } else {f.control={...f.control,state:boundary,control_epoch:2};await f.controller.control(null,f.control,f.transport);}
  gate.resolve();await observing;await f.controller.cleanupTerminalResources();
  assert.equal(f.sends,0);assert.deepEqual(f.store.snapshot().lifecycle.completionOutbox,{});
  if(['STOPPED','DONE'].includes(boundary)){assert.equal(f.record(),null);assert.equal(observer.signal.aborted,true);}
  else {assert.ok(f.record());assert.notEqual(f.record().state,'COMMITTED');if(boundary==='result-document')assert.equal(f.record().body.result_document_id,'RESULT-2');}
});
