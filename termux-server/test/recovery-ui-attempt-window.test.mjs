import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, MemoryStore } from './helpers/lifecycle-fixture.mjs';

const WINDOW=600000;
for(const restart of ['none','local','Drive'])test('failed recovery UI attempt keeps a durable ten-minute window across '+restart,async t=>{
  let now=1000000;t.mock.method(Date,'now',()=>now);
  const f=fixture();f.controller.config.stallAfterMs=WINDOW;t.after(()=>f.controller.quiesceForStandby());
  let attempts=0,expectedAt=0;const observed=[];
  f.browser.quiesce=async()=>{
    attempts++;
    observed.push({local:f.record().last_nudge_attempt_at,remote:f.rows.get(f.path).lifecycle_record.last_nudge_attempt_at});
    throw Error('temporary quiesce failure');
  };
  await f.existing();await f.observe({});now+=WINDOW;expectedAt=now;
  await f.observe({status:'STALLED'});
  assert.equal(f.record().error.code,'OBSERVATION_CALLBACK_FAILED');
  assert.equal(attempts,1);assert.equal(f.sends,0);
  assert.deepEqual(observed[0],{local:expectedAt,remote:expectedAt});
  if(restart!=='none'){
    const projected=structuredClone(f.rows.get(f.path));await f.controller.quiesceForStandby();
    if(restart==='Drive')f.store=new MemoryStore();f.controller=f.recreate();
    if(restart==='Drive')await f.controller.ingest(f.path,projected,f.transport);else await f.controller.tick(f.transport);
  }
  for(let minute=1;minute<=9;minute++){now+=60000;await f.observe({status:'STALLED'});await f.controller.tick(f.transport);assert.equal(attempts,1);}
  now+=59999;await f.observe({status:'STALLED'});assert.equal(attempts,1);
  now+=1;expectedAt=now;await f.observe({status:'STALLED'});
  assert.equal(f.record().error.code,'OBSERVATION_CALLBACK_FAILED');
  assert.equal(attempts,2);assert.equal(f.sends,0);
  assert.deepEqual(observed[1],{local:expectedAt,remote:expectedAt});
});
