import test from 'node:test';import assert from 'node:assert/strict';import {LifecycleStore} from '../src/lifecycle-store.mjs';import {fixture} from './helpers/lifecycle-fixture.mjs';
const implementation=await import('../src/maintenance-quarantine.mjs').catch(()=>({}));
const old={schema:'selfrun-task-control-v1',task_id:'OLD',turn_id:'TURN',request_id:'REQ',state:'RUNNING',control_epoch:2,updated_at_ms:50};
const snapshot=()=>({generation:1,status:'IDLE',activeCount:0,activeSignal:{legacy:true},activeTargetId:'legacy',activeDispatches:[],lifecycle:{requests:{},controls:{OLD:old},retired:{},outbox:{},completionOutbox:{},browserCleanup:{},events:[]}});
const options={userConfirmedNoWork:true,cutoffMs:100,expectedGeneration:1,expectedControlEpochs:{OLD:2}};
test('explicit no-work quarantine preserves original control and projection without inventing terminal state',()=>{
 assert.equal(typeof implementation.quarantineIdleSnapshot,'function');const original=snapshot();const next=implementation.quarantineIdleSnapshot(original,options);
 assert.deepEqual(next.lifecycle.controls.OLD,old);assert.deepEqual(next.lifecycle.controlQuarantine.OLD.control,old);assert.deepEqual(next.maintenanceQuarantine.projection.activeSignal,original.activeSignal);assert.equal(next.activeSignal,null);assert.equal(next.activeTargetId,null);assert.equal(next.lifecycle.retired.OLD,undefined);assert.deepEqual(original,snapshot());
});
test('generation/epoch/new-time and current work refuse quarantine',()=>{assert.equal(typeof implementation.quarantineIdleSnapshot,'function');for(const changes of [{generation:2},{activeCount:1},{lifecycle:{...snapshot().lifecycle,requests:{live:{state:'PREPARING'}}}},{lifecycle:{...snapshot().lifecycle,controls:{OLD:{...old,control_epoch:3}}}},{lifecycle:{...snapshot().lifecycle,controls:{OLD:{...old,updated_at_ms:101}}}}])assert.throws(()=>implementation.quarantineIdleSnapshot({...snapshot(),...changes},options));assert.throws(()=>implementation.quarantineIdleSnapshot(snapshot(),{...options,userConfirmedNoWork:false}));});
test('quarantined control replay stays inert but a genuinely newer control is accepted',async()=>{
 assert.equal(typeof implementation.quarantineIdleSnapshot,'function');const value=implementation.quarantineIdleSnapshot(snapshot(),options);const store={value,snapshot(){return structuredClone(this.value)},async patch(p){this.value={...this.value,...structuredClone(p)}}};const repo=new LifecycleStore(store);
 assert.equal(repo.control('OLD'),null);assert.equal(await repo.applyControl(old),false);assert.equal(await repo.applyControl({...old,control_epoch:3,updated_at_ms:101}),true);assert.equal(repo.control('OLD').control_epoch,3);assert.equal(repo.data.controlQuarantine.OLD.control.control_epoch,2);
});
test('controller rejects historical quarantined control before restoration or cleanup effects',async()=>{
 assert.equal(typeof implementation.quarantineIdleSnapshot,'function');const f=fixture();const s=f.store.snapshot();s.lifecycle={requests:{},controls:{[f.control.task_id]:f.control},retired:{},outbox:{},completionOutbox:{},browserCleanup:{},events:[]};Object.assign(s,{activeCount:0,status:'IDLE',activeDispatches:[]});const clean=implementation.quarantineIdleSnapshot(s,{userConfirmedNoWork:true,cutoffMs:100,expectedGeneration:s.generation,expectedControlEpochs:{[f.control.task_id]:1}});await f.store.patch(clean);f.controller=f.recreate();assert.equal(await f.controller.controlDurable(null,f.control,f.transport),false);assert.equal(f.controller.repository.records().length,0);assert.equal(f.sends,0);assert.equal(f.prepares,0);assert.equal(f.resumes,0);
});
test('direct retired restoration cannot bypass the no-work fence',async()=>{
 const m={task_id:'OLD',turn_id:'TURN',request_id:'REQ',state:'STOPPED',retired:true,control_epoch:1,key:JSON.stringify(['OLD','TURN','REQ'])};const before=snapshot();before.lifecycle.retired[m.key]=m;const value=implementation.quarantineIdleSnapshot(before,options);const store={value,snapshot(){return structuredClone(this.value)},async patch(p){this.value={...this.value,...structuredClone(p)}}};const repo=new LifecycleStore(store);assert.equal(await repo.restoreFromControl({...old,conversation_url:'https://chatgpt.com/c/old'},{}),null);assert.deepEqual(repo.data.requests,{});
});
test('unsupported ingress, pending browser acquisition, and malformed timestamps cannot authorize maintenance',()=>{
 for(const changes of [{httpIngress:{files:{fresh:{schema:'selfrun-server-dispatch-v1'}}}},{lifecycle:{...snapshot().lifecycle,retired:{pending:{browser_open_pending:{generation:1}}}}},{lifecycle:{...snapshot().lifecycle,controls:{OLD:{...old,updated_at_ms:'not-a-time'}}}}])assert.throws(()=>implementation.quarantineIdleSnapshot({...snapshot(),...changes},options));
 assert.throws(()=>implementation.quarantineIdleSnapshot({...snapshot(),generation:undefined},{...options,expectedGeneration:undefined}));
});

test('maintenance refuses array or malformed existing quarantine evidence',()=>{
 for(const bad of [[], 'invalid', {OLD:{schema:1,reason:'USER_CONFIRMED_NO_WORK',control:{control_epoch:'2'}}}]){
  const before=snapshot();before.lifecycle.controlQuarantine=bad;
  assert.throws(()=>implementation.quarantineIdleSnapshot(before,options));
 }
});
test('raw epoch watermark survives fencing and is returned as a defensive copy',()=>{
 const value=implementation.quarantineIdleSnapshot(snapshot(),options);const store={snapshot:()=>value};const repo=new LifecycleStore(store);
 assert.equal(repo.control('OLD'),null);const c=repo.controlWatermark('OLD');assert.equal(c.control_epoch,2);c.control_epoch=99;assert.equal(repo.controlWatermark('OLD').control_epoch,2);
});
test('direct repository ingestion cannot grant browser proof/current authority from quarantined control',async()=>{
 const f=fixture(),before=f.store.snapshot();Object.assign(before,{status:'IDLE',activeCount:0,activeDispatches:[],lifecycle:{requests:{},controls:{[f.control.task_id]:f.control},retired:{},outbox:{},completionOutbox:{},browserCleanup:{},events:[]}});
 await f.store.patch(implementation.quarantineIdleSnapshot(before,{userConfirmedNoWork:true,cutoffMs:Date.now(),expectedGeneration:before.generation,expectedControlEpochs:{[f.control.task_id]:1}}));const repo=new LifecycleStore(f.store);
 const r=await repo.ingest(f.path,f.body);assert.equal(r.pre_submit_proof,undefined);assert.equal(repo.current(r.key,{run:r.run_generation,browser:r.browser_generation}),false);assert.equal(f.store.snapshot().activeCount,0);
});
test('rollback preserves historical evidence and creates a legacy-readable maintenance pause only',()=>{
 const original=implementation.quarantineIdleSnapshot(snapshot(),options);assert.equal(typeof implementation.rollbackIdleSnapshot,'function');
 const next=implementation.rollbackIdleSnapshot(original,options);assert.equal(next.lifecycle.controls.OLD.state,'PAUSED');assert.equal(next.lifecycle.controls.OLD.control_epoch,2);assert.deepEqual(next.lifecycle.controlQuarantine.OLD.control,old);assert.deepEqual(next.lifecycle.retired,{});assert.equal(original.lifecycle.controls.OLD.state,'RUNNING');
});
test('rollback refuses new work, advanced controls and mismatching quarantine identity',()=>{
 const value=implementation.quarantineIdleSnapshot(snapshot(),options);assert.equal(typeof implementation.rollbackIdleSnapshot,'function');
 for(const mutate of [s=>s.lifecycle.requests.fresh={},s=>s.lifecycle.controls.OLD.control_epoch=3,s=>s.lifecycle.controlQuarantine.OLD.control.request_id='OTHER']){const s=structuredClone(value);mutate(s);assert.throws(()=>implementation.rollbackIdleSnapshot(s,options));}
});

test('rollback rejects RUNNING controls when all or any existing maintenance fence is missing',()=>{
 for(const fence of [undefined,{}]){const s=snapshot();if(fence!==undefined)s.lifecycle.controlQuarantine=fence;assert.throws(()=>implementation.rollbackIdleSnapshot(s,options));}
 const s=implementation.quarantineIdleSnapshot(snapshot(),options);s.lifecycle.controls.NEW={...old,task_id:'NEW'};
 assert.throws(()=>implementation.rollbackIdleSnapshot(s,{...options,expectedControlEpochs:{OLD:2,NEW:2}}));
});
test('rollback refuses unresolved CANCELLED archives lacking a legacy-readable pending protection',()=>{
 const s=implementation.quarantineIdleSnapshot(snapshot(),options),key=JSON.stringify(['OLD','TURN','REQ']);
 s.lifecycle.retired[key]={key,task_id:'OLD',turn_id:'TURN',request_id:'REQ',state:'CANCELLED',browser_generation:0,pre_submit_proof:{schema:1}};
 s.lifecycle.browserCleanupUnresolved={archive:{schema:1,status:'UNRESOLVED',entry:{key,target_id:'legacy'}}};
 assert.throws(()=>implementation.rollbackIdleSnapshot(s,options));
 s.lifecycle.retired[key].browser_cleanup={status:'PENDING',reason:'unowned_target'};
 const next=implementation.rollbackIdleSnapshot(s,options);assert.deepEqual(next.lifecycle.browserCleanupUnresolved,s.lifecycle.browserCleanupUnresolved);assert.deepEqual(next.lifecycle.browserCleanup,{});assert.equal(next.lifecycle.retired[key].browser_cleanup.status,'PENDING');
});
