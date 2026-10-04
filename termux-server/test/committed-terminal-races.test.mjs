import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';

for(const boundary of ['pending-read','pending-terminal-move'])test(
  'replacing the current Result document fences prior committed proof at '+boundary,
  async()=>{
    const f=fixture(),entered=deferred(),gate=deferred();
    await f.existing();f.result(true);
    const observer=f.observations.at(-1),oldResult=f.docs.get('RESULT-1');
    if(boundary==='pending-read') {
      f.transport.readGoogleDocText=async id=>{
        if(id==='RESULT-1'){entered.resolve();await gate.promise;return oldResult;}
        return f.docs.get(id);
      };
    } else {
      const move=f.controller.repository.move.bind(f.controller.repository);
      f.controller.repository.move=async(...args)=>{
        if(args[2]==='RESULT_COMMITTED_TERMINAL'){entered.resolve();await gate.promise;}
        return move(...args);
      };
    }
    const observing=f.observe({});await entered.promise;
    f.result(false,'RESULT-2');
    await f.controller.repository.ingest(f.path,{...f.body,result_document_id:'RESULT-2'});
    gate.resolve();await observing;
    assert.equal(f.record().retired,undefined);
    assert.equal(f.record().body.result_document_id,'RESULT-2');
    assert.equal(observer.signal.aborted,false);
    assert.equal(f.sends,0);
    await f.controller.quiesceForStandby();
  },
);
