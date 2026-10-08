import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './helpers/lifecycle-fixture.mjs';

const reasons=f=>[...f.controller.repository.data.events,...f.store.events.filter(e=>e.event==='CONVERSATION_TRANSITION').map(e=>e.details)].map(e=>e.reason);
for(const kind of ['initial','recovery'])test(`${kind} submission survives a failed post-send Drive publication`,async t=>{
  const f=fixture(),logs=[];t.after(()=>f.controller.quiesceForStandby());
  t.mock.method(console,'error',line=>logs.push(JSON.parse(line)));
  if(kind==='recovery')await f.existing();
  const write=f.transport.write;let failures=0;
  f.transport.write=async(...args)=>{
    if(f.sends&&failures++===0)throw Object.assign(new TypeError('fetch failed secret-body'),{cause:{code:'ECONNRESET'}});
    return write(...args);
  };
  if(kind==='initial')await f.ingest();else await f.stall();
  await f.settle();
  assert.equal(f.sends,1);
  assert.equal(f.record().intent.outcome,'CONFIRMED','Drive publication must not replace the submission outcome');
  assert.equal(reasons(f).includes('POST_OUTCOME_UNCERTAIN'),false);
  assert.equal(reasons(f).includes('OBSERVATION_CALLBACK_FAILED'),false);
  const event=logs.find(e=>e.event==='DRIVE_PROJECTION_PENDING');
  assert.equal(event?.error?.code,'DRIVE_PROJECTION_FAILED');
  assert.equal(event?.error?.operation,'write');
  assert.equal(event?.error?.cause_code,'ECONNRESET');
  assert.doesNotMatch(JSON.stringify(logs),/secret-body/);
});

for(const operation of ['read','write'])test(`projection ${operation} failure has safe operation and transport cause`,async t=>{
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  const key=f.record().key;await f.controller.repository.move(key,'OBSERVING','fixture pending');
  f.transport[operation]=async()=>{throw Object.assign(new Error('private URL token message'),{
    code:'DRIVE_TRANSPORT_ERROR',operation:'version',stage:'request',cause_code:'ENOTFOUND',http_status:null});};
  await assert.rejects(f.controller.repository.flushCurrent(f.transport,key),error=>{
    assert.equal(error.code,'DRIVE_PROJECTION_FAILED');assert.equal(error.operation,operation);
    assert.equal(error.transport_operation,'version');assert.equal(error.stage,'request');
    assert.equal(error.cause_code,'ENOTFOUND');assert.equal(error.http_status,null);
    assert.doesNotMatch(error.message+JSON.stringify(error),/private|token|message/);return true;
  });
  assert.ok(f.controller.repository.data.outbox[key]);
});

test('a repeated projection outage keeps local confirmation and the pending outbox',async t=>{
  const f=fixture(),logs=[];t.after(()=>f.controller.quiesceForStandby());
  t.mock.method(console,'error',line=>logs.push(JSON.parse(line)));await f.existing();
  const write=f.transport.write;
  f.transport.write=async(...args)=>{if(f.sends)throw new Error('Drive write HTTP 503: private response');return write(...args);};
  await f.stall();await f.settle();
  assert.equal(f.record().intent.outcome,'CONFIRMED');assert.equal(f.record().intent.released,true);
  assert.ok(f.controller.repository.data.outbox[f.record().key]);
  assert.equal(f.record().error,null);assert.equal(f.record().retry_at,0);
  assert.ok(logs.some(e=>e.event==='DRIVE_PROJECTION_PENDING'&&e.error.http_status===503));
  f.transport.write=write;await f.controller.repository.flushCurrent(f.transport,f.record().key);
  assert.equal(f.rows.get(f.path).post_outcome,'CONFIRMED');assert.equal(f.sends,1);
});

test('HTTP error receipt produces a separate numeric ChatGPT diagnostic',async t=>{
  const f=fixture(),logs=[];t.after(()=>f.controller.quiesceForStandby());t.mock.method(console,'error',line=>logs.push(JSON.parse(line)));
  f.browser.readSubmission=async({intent,onRequest})=>{
    await onRequest({released:true,message_id:'http-user',receipt:{version:1,intent_id:intent.id,generation:intent.receipt_generation,
      request_id:intent.receipt_generation+':1',message_id:'http-user',ack:null,invalid:'http_error',http_status:429,endpoint_category:'f_conversation'}});
    return {state:'UNKNOWN',probe:f.probe};
  };
  await f.ingest();await f.settle();
  assert.equal(f.record().intent.outcome,'UNKNOWN');assert.equal(f.record().intent.receipt.http_status,429);
  assert.deepEqual(logs.find(e=>e.event==='CHATGPT_POST_HTTP_ERROR'),{event:'CHATGPT_POST_HTTP_ERROR',http_status:429,endpoint_category:'f_conversation'});
  assert.equal(logs.some(e=>e.event==='DRIVE_PROJECTION_PENDING'),false);
});

test('pre-send Drive durability failure still blocks submission',async t=>{
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());
  f.transport.write=async()=>{throw new Error('Drive write HTTP 503');};
  await assert.rejects(f.ingest(),error=>error.code==='DRIVE_PROJECTION_FAILED');
  assert.equal(f.sends,0);assert.ok(f.controller.repository.data.outbox[f.record().key]);
});

test('local durable write failure is never treated as a retryable Drive projection',async t=>{
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());
  f.onSubmit=()=>{f.store.fail=true;};
  await assert.rejects(f.ingest(),/disk failed/);
  assert.equal(f.sends,1);assert.notEqual(f.record().intent.outcome,'CONFIRMED');
  f.store.fail=false;
});
