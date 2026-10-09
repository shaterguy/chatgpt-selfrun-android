import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { StateStore } from '../src/state-store.mjs';
import { SignalDispatchTransport } from '../src/signal-transport.mjs';
import { LifecycleStore } from '../src/lifecycle-store.mjs';
import { keyFor } from '../src/lifecycle.mjs';

const dispatchPath='__SELFRUN_DISPATCH__HTTP_stopped.json';
const controlPath='__SELFRUN_CONTROL__stopped.json';
const body={schema:'selfrun-server-dispatch-v1',server_ingress:'HTTP',task_id:'stopped',turn_id:'turn-1',request_id:'request-1',
  dispatch_attempt:1,server_control_epoch:1,prompt:'discard this stopped payload',profile_operations:[],created_at_ms:100};
const stop={schema:'selfrun-task-control-v1',task_id:body.task_id,turn_id:body.turn_id,request_id:body.request_id,
  state:'STOPPED',control_epoch:2,updated_at_ms:200};
const marker={key:keyFor(body),path:dispatchPath,task_id:body.task_id,turn_id:body.turn_id,request_id:body.request_id,
  dispatch_attempt:1,state:'STOPPED',control_state:'STOPPED',control_epoch:2,retired:true,updated_at:200};
const clone=value=>structuredClone(value);
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r;});return {promise,resolve};};

async function fixture(t) {
  const dataDir=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-stopped-cache-'));
  t.after(()=>fs.rm(dataDir,{recursive:true,force:true}));
  const config={dataDir,stateFile:path.join(dataDir,'state.json'),eventsFile:path.join(dataDir,'events.jsonl')};
  const store=new StateStore(config);await store.init();
  const lifecycle={schema:1,requests:{},retired:{[marker.key]:clone(marker)},controls:{[stop.task_id]:clone(stop)},events:[],outbox:{},completionOutbox:{}};
  const rows=new Map([[dispatchPath,{...body,prompt:'fresh Drive resume input'}],[controlPath,{...stop,state:'RUNNING',control_epoch:3}]]);
  const reads=[],writes=[];
  const base={read:async name=>{reads.push(name);return clone(rows.get(name));},
    write:async(name,value)=>{writes.push(name);rows.set(name,clone(value));},list:async()=>[],close(){}};
  const transport=new SignalDispatchTransport(base,store);
  await transport.register('old-start',dispatchPath,body,controlPath,{...stop,state:'RUNNING',control_epoch:1});
  await transport.setControl('old-stop',controlPath,stop);
  await store.patch({lifecycle});
  return {store,transport,base,rows,reads,writes,config};
}

test('STOP purge removes only its cached payloads and old metadata while preserving active and other requests',async t=>{
  const f=await fixture(t);
  const active={...body,task_id:'active',turn_id:'turn-active',request_id:'request-active'};
  const activeControl={...stop,...active,schema:stop.schema,state:'RUNNING',control_epoch:7};
  const otherRequest={...body,request_id:'request-other'};
  await f.transport.register('active-start','active-dispatch',active,'active-control',activeControl);
  await f.store.update(state=>({...state,httpIngress:{...state.httpIngress,files:{...state.httpIngress.files,'other-request':otherRequest}},
    lifecycle:{...state.lifecycle,requests:{[keyFor(active)]:{...active,state:'OBSERVING'}},controls:{...state.lifecycle.controls,active:activeControl}}}));
  const before=f.store.snapshot();
  assert.equal(await f.transport.purgeStopped(stop),true);
  const after=f.store.snapshot();
  assert.deepEqual(after.httpIngress.files,{'active-dispatch':active,'active-control':activeControl,'other-request':otherRequest});
  assert.deepEqual(after.httpIngress.signals,{'old-start':true,'old-stop':true,'active-start':before.httpIngress.signals['active-start']});
  assert.deepEqual(after.lifecycle,before.lifecycle);
});

test('purged HTTP paths read fresh Drive input and old signal IDs remain replay-proof',async t=>{
  const f=await fixture(t);await f.transport.purgeStopped(stop);
  assert.deepEqual(await f.transport.read(dispatchPath),f.rows.get(dispatchPath));
  assert.deepEqual(await f.transport.read(controlPath),f.rows.get(controlPath));
  await f.transport.register('old-start',dispatchPath,body,controlPath,stop);
  await f.transport.setControl('old-stop',controlPath,stop);
  assert.deepEqual(f.transport.local().files,{});
  assert.deepEqual(f.reads,[dispatchPath,controlPath]);
});

test('accepted newer HTTP RUNNING protects its dispatch while lifecycle still records STOP',async t=>{
  const f=await fixture(t);
  const resume={...stop,state:'RUNNING',control_epoch:3};
  await f.transport.setControl('new-resume',controlPath,resume);
  const before=f.store.snapshot();
  assert.equal(await f.transport.purgeStopped(stop),false);
  assert.deepEqual(f.store.snapshot(),before);
});

test('newer or pending same-identity dispatch is retained',async t=>{
  for(const patch of [{dispatch_attempt:2},{server_control_epoch:3},{created_at_ms:300},{send_requested_at_ms:300},
    {lifecycle_record:{control_epoch:3}},{lifecycle_state:'COMMITTED'}]) {
    const f=await fixture(t),pending={...body,...patch};
    await f.store.update(state=>({...state,httpIngress:{...state.httpIngress,files:{...state.httpIngress.files,[dispatchPath]:pending}}}));
    await f.transport.purgeStopped(stop);
    assert.deepEqual(f.transport.local().files[dispatchPath],pending,JSON.stringify(patch));
    assert.equal(typeof f.transport.local().signals['old-start'],'object');
  }
});

test('purge requires matching current STOP identity, epoch, and retired STOP marker',async t=>{
  const changes=[
    data=>{data.controls.stopped.state='RUNNING';},
    data=>{data.controls.stopped.control_epoch=3;},
    data=>{data.controls.stopped.request_id='different-request';},
    data=>{data.retired[marker.key].control_epoch=1;},
    data=>{data.retired[marker.key].turn_id='different-turn';},
    data=>{delete data.retired[marker.key];},
    data=>{data.requests[marker.key]={...body,state:'OBSERVING'};},
  ];
  for(const change of changes) {
    const f=await fixture(t);await f.store.update(state=>{change(state.lifecycle);return state;});
    const before=f.store.snapshot();assert.equal(await f.transport.purgeStopped(stop),false);assert.deepEqual(f.store.snapshot(),before);
  }
});

test('atomic revalidation preserves a newer accepted HTTP control queued before purge',async t=>{
  const f=await fixture(t),entered=deferred(),release=deferred();
  const persist=f.store.persist.bind(f.store);let persisted=0,hold=true;
  f.store.persist=async candidate=>{if(hold){hold=false;entered.resolve();await release.promise;}persisted++;return persist(candidate);};
  const resume={...stop,state:'RUNNING',control_epoch:3};
  const accepting=f.transport.setControl('racing-resume',controlPath,resume);await entered.promise;
  const purging=f.transport.purgeStopped(stop);release.resolve();await accepting;
  assert.equal(await purging,false);
  assert.deepEqual(f.transport.local().files[controlPath],resume);
  assert.deepEqual(f.transport.local().files[dispatchPath],body);
  assert.equal(persisted,1,'aborted purge must not persist a no-op');
});

test('restart retries persisted STOP cache retirement without repeating no-op writes',async t=>{
  const f=await fixture(t),restarted=new StateStore(f.config);await restarted.init();
  const transport=new SignalDispatchTransport(f.base,restarted);
  let persisted=0;const persist=restarted.persist.bind(restarted);restarted.persist=async candidate=>{persisted++;return persist(candidate);};
  assert.equal(await transport.purgeStopped(),true);
  for(let i=0;i<3;i++)assert.equal(await transport.purgeStopped(),false);
  assert.equal(persisted,1);assert.deepEqual(transport.local().files,{});
  const again=new StateStore(f.config);await again.init();assert.deepEqual(again.snapshot().httpIngress,{files:{},signals:{'old-start':true,'old-stop':true}});
});

test('legacy completion cache is removed after final publication without durable history',async t=>{
  for(const state of ['COMMITTED','COMPLETED']) {
    const f=await fixture(t);
    await f.store.update(snapshot=>{
      snapshot.lifecycle.retired[marker.key].state=state;
      snapshot.lifecycle.completionOutbox[marker.key]={...marker,revision:4,updated_at:250};return snapshot;
    });
    const before=f.store.snapshot();assert.equal(await f.transport.purgeStopped(stop),false);assert.equal(await f.transport.purgeStopped(),false);
    assert.deepEqual(f.store.snapshot(),before);
    const repository=new LifecycleStore(f.store);await repository.flush(f.transport);
    assert.equal(f.transport.local().files[dispatchPath],undefined);
    assert.deepEqual(repository.data.completionOutbox,{});assert.deepEqual(f.writes,[]);
  }
});

test('a renewed STOP can purge old input after a previously retired marker timestamp',async t=>{
  const f=await fixture(t),laterBody={...body,created_at_ms:300},laterStop={...stop,control_epoch:4,updated_at_ms:400};
  await f.store.update(state=>{
    state.httpIngress.files[dispatchPath]=laterBody;state.httpIngress.files[controlPath]=laterStop;
    state.lifecycle.controls.stopped=laterStop;state.lifecycle.retired[marker.key].control_epoch=4;return state;
  });
  assert.equal(await f.transport.purgeStopped(laterStop),true);
  assert.deepEqual(f.transport.local().files,{});
});
