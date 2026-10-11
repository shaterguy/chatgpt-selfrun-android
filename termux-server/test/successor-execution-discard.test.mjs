import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture,deferred,MemoryStore} from './helpers/lifecycle-fixture.mjs';
import {keyFor} from '../src/lifecycle.mjs';
import {SignalDispatchTransport} from '../src/signal-transport.mjs';
import {DriveDispatchWatcher} from '../src/drive-watcher.mjs';
const successor=f=>({...f.control,turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',state:'RUNNING',control_epoch:2});
function absent(f,old){
 const key=keyFor(old),repo=f.controller.repository,snapshot=f.store.snapshot();
 assert.equal(repo.get(key),null,'prior execution is discarded rather than PAUSED');
 for(const data of [repo.data,snapshot.lifecycle])for(const field of ['requests','retired','outbox','completionOutbox','browserCleanup','browserCleanupOutcomes','browserCleanupUnresolved'])
  assert.equal(Object.values(data[field]||{}).some(x=>x.key===key||x.entry?.key===key||keyFor(x)===key),false,field);
 assert.equal(repo.history.audits.has(key),false);assert.equal(repo.history.legacy.has(key),false);assert.equal(repo.integrityFaults.has(key),false);
 assert.equal(f.controller.sessions.has(key),false);assert.equal(f.controller.operations.has(key),false);
 for(const body of Object.values(snapshot.httpIngress?.files||{}))assert.notEqual(keyFor(body),key);
}
for(const failed of [false,true])test('successor discards payload immediately and consumes its one cleanup without restart history; failed='+failed,async t=>{
 const f=fixture(),entered=deferred(),release=deferred();let attempts=0;
 t.after(()=>{release.resolve();return f.controller.quiesceForStandby();});
 f.browser.chromium={isOwnedTarget:()=>true,closeOwnedTarget:async(id,{guard})=>{attempts++;entered.resolve();await release.promise;return{closed:!failed&&guard({id,url:f.probe.url})};},discardOwnedTarget:async()=>({localDiscarded:true}),releaseIfUnused:async()=>({released:false})};
 f.controller=f.recreate();await f.existing();const old=structuredClone(f.body),observer=f.observations.at(-1);
 const http=new SignalDispatchTransport(f.transport,f.store);await http.register('prior-signal',f.path,f.body,'control.json',f.control);
 f.control=successor(f);await f.controller.controlDurable(null,f.control,f.transport);await entered.promise;
 absent(f,old);assert.equal(observer.signal.aborted,true);assert.equal(f.controller.repository.resourceCleanups.size,1);
 const closure=JSON.stringify([...f.controller.repository.resourceCleanups.values()]);for(const name of ['"body"','"intent"','"cursor"','test input'])assert.equal(closure.includes(name),false);
 release.resolve();await f.controller.cleanupTerminalResources();assert.equal(attempts,1);assert.equal(f.controller.repository.resourceCleanups.size,0);absent(f,old);
 await observer.onActivity(f.probe);await f.controller.flush(f.transport);absent(f,old);
 f.store=new MemoryStore(f.store.snapshot());f.controller=f.recreate();await f.controller.initialize();await f.controller.tick(f.transport);await f.controller.cleanupTerminalResources();
 absent(f,old);assert.equal(attempts,1);assert.equal(f.sends,0);assert.equal(f.resumes,1);
});

test('fresh current CONTROL discards legacy displaced PAUSED state, while uncertain read and same-identity PAUSE retain it',async t=>{
 const f=fixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();const old=structuredClone(f.body),oldKey=keyFor(old);
 await f.controller.quiesceForStandby();const stored=f.store.snapshot();stored.lifecycle.requests[oldKey].state='PAUSED';stored.lifecycle.requests[oldKey].control_state='RUNNING';stored.lifecycle.requests[oldKey].control_epoch=2;
 f.control=successor(f);stored.lifecycle.controls[old.task_id]=structuredClone(f.control);f.store=new MemoryStore(stored);f.controller=f.recreate();await f.controller.initialize();
 assert.ok(f.controller.repository.get(oldKey),'startup alone does not infer termination from cached state');
 const read=f.transport.read;f.transport.read=async()=>{throw Error('control unavailable');};await assert.rejects(f.controller.tick(f.transport),/control unavailable/);assert.ok(f.controller.repository.get(oldKey));
 f.transport.read=read;await f.controller.tick(f.transport);absent(f,old);assert.equal(f.sends,0);
 await f.controller.ingest(f.path,old,f.transport);absent(f,old);assert.equal(f.sends,0);
 const paused=fixture();t.after(()=>paused.controller.quiesceForStandby());await paused.existing();paused.control={...paused.control,state:'PAUSED',control_epoch:2};await paused.controller.control(null,paused.control,paused.transport);assert.equal(paused.record().state,'PAUSED');assert.equal(paused.record().body.prompt,paused.body.prompt);
});

test('successor CONTROL remains visible to the actual watcher while only predecessor intake is discarded',async t=>{
 const f=fixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();const old=structuredClone(f.body),oldKey=keyFor(old),controlPath='__SELFRUN_CONTROL__SAFE-TEST.json';
 const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});t.after(()=>watcher.stop());
 await watcher.scanOnce();f.control=successor(f);const body={...f.body,turn_id:f.control.turn_id,request_id:f.control.request_id,client_status:'CREATE_REQUESTED',conversation_url:'',server_control_epoch:2};
 const newPath='__SELFRUN_DISPATCH__'+body.request_id+'__A1.json';f.rows.set(newPath,body);
 await watcher.scanOnce();await f.settle();
 assert.equal(f.controller.repository.get(oldKey),null);assert.equal(f.controller.repository.control(old.task_id)?.request_id,body.request_id);
 assert.equal(watcher.runningControls.has(old.task_id),true);assert.equal(watcher.files.has(controlPath),true);assert.equal(watcher.files.has(f.path),false);
 assert.equal(f.controller.repository.get(keyFor(body))?.state,'PREPARED');assert.equal(f.sends,0);
});

import {monitorRuntimeFixture} from './helpers/monitor-runtime-fixture.mjs';
import {ExtensionManager} from '../src/browser/extension-manager.mjs';
test('fresh CONTROL legacy cleanup keeps exact journal target addressable through cold manager initialization',async t=>{
 const wire=await monitorRuntimeFixture(t),f=fixture(),target=await wire.manager.createTarget(f.probe.url),session=await wire.manager.connectTarget(target);
 await session.run('initialize',{clientProfile:'native'});session.close();
 f.browser.chromium=wire.manager;f.browser.resume=async args=>{f.resumes++;await args.onTarget?.(target);return{target,session:await wire.manager.connectTarget(target),baseline:f.probe};};
 f.controller=f.recreate();await f.existing();await f.controller.quiesceForStandby();
 const old=structuredClone(f.body),key=keyFor(old),snapshot=f.store.snapshot();snapshot.lifecycle.requests[key].state='PAUSED';snapshot.lifecycle.requests[key].control_epoch=2;
 f.control=successor(f);snapshot.lifecycle.controls[old.task_id]=structuredClone(f.control);await wire.manager.close();
 const cold=new ExtensionManager(wire.config,wire.manager.deps);t.after(()=>cold.close());
 f.browser.chromium=cold;f.store=new MemoryStore(snapshot);f.controller=f.recreate();t.after(()=>f.controller.quiesceForStandby());await f.controller.initialize();
 assert.equal(cold.journal,null);await f.controller.tick(f.transport);await f.controller.cleanupTerminalResources();await cold.saveChain;
 absent(f,old);assert.equal(wire.tabs.has(1),false,'same proven journal target must remain addressable for its one close');
 assert.equal(cold.isOwnedTarget(target.id),false);assert.equal(cold.journal.targetMetadata[target.id],undefined);assert.equal(wire.tabs.has(99),true);
 assert.equal(f.sends,0);assert.equal(f.resumes,1);assert.equal(f.prepares,0);
});

import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {StateStore} from '../src/state-store.mjs';
test('legacy predecessor payload is gone after fresh CONTROL and two new disk-store/controller reconstructions; audit original stays intact',async t=>{
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-successor-discard-'));
 const config={dataDir:dir,stateFile:path.join(dir,'state.json'),eventsFile:path.join(dir,'events.jsonl')},store=new StateStore(config);await store.init();
 const f=fixture({store});t.after(async()=>{await f.controller.quiesceForStandby();await f.settle();await fs.rm(dir,{recursive:true,force:true});});
 await f.ingest();await f.observe({});await f.controller.flush(f.transport);const old=structuredClone(f.body),key=keyFor(old),rawResult=f.docs.get('RESULT-1');
 const http=new SignalDispatchTransport(f.transport,f.store);await http.register('old',f.path,f.body,'control.json',f.control);
 await f.controller.quiesceForStandby();const legacy=f.store.snapshot();legacy.lifecycle.requests[key].state='PAUSED';legacy.lifecycle.requests[key].control_state='RUNNING';legacy.lifecycle.requests[key].control_epoch=2;
 f.control=successor(f);legacy.lifecycle.controls[old.task_id]=structuredClone(f.control);await store.replace(legacy);const auditBefore=await fs.readFile(config.eventsFile,'utf8');
 f.store=new StateStore(config);await f.store.init();f.controller=f.recreate();await f.controller.initialize();assert.ok(f.controller.repository.get(key).intent);
 await f.controller.tick(f.transport);await f.controller.cleanupTerminalResources();await f.controller.flush(f.transport);absent(f,old);
 for(let n=0;n<2;n++){
  await f.controller.quiesceForStandby();f.store=new StateStore(config);await f.store.init();f.controller=f.recreate();await f.controller.initialize();
  await f.controller.ingest(f.path,old,f.transport);await f.controller.tick(f.transport);absent(f,old);
  const disk=JSON.parse(await fs.readFile(config.stateFile,'utf8'));assert.equal(disk.lifecycle.requests[key],undefined);assert.equal(disk.httpIngress.signals.old,undefined);
 }
 assert.equal(f.sends,1);assert.equal(f.docs.get('RESULT-1'),rawResult);assert.equal((await fs.readFile(config.eventsFile,'utf8')).startsWith(auditBefore),true,'original audit bytes are never deleted');
});

test('a blank save need not retain orphan successor CONTROL: fresh watcher intake still admits only the latest identity',async t=>{
 const f=fixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();const old=structuredClone(f.body),monitor=f.observations.at(-1);
 f.control=successor(f);await f.controller.controlDurable(null,f.control,f.transport);absent(f,old);
 await f.controller.repository.save(structuredClone(f.controller.repository.data));assert.equal(f.controller.repository.control(old.task_id),null);
 await monitor.onActivity(f.probe);await f.controller.ingest(f.path,old,f.transport);absent(f,old);
 const body={...f.body,turn_id:f.control.turn_id,request_id:f.control.request_id,client_status:'CREATE_REQUESTED',conversation_url:'',server_control_epoch:2},newPath='__SELFRUN_DISPATCH__'+f.control.request_id+'__A1.json';f.rows.set(newPath,body);
 const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});t.after(()=>watcher.stop());await watcher.scanOnce();await f.settle();
 assert.equal(f.controller.repository.get(keyFor(body))?.state,'PREPARED');absent(f,old);assert.equal(f.sends,0);
});
