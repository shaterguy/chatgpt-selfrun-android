import test from 'node:test';import assert from 'node:assert/strict';
import {LifecycleStore} from '../src/lifecycle-store.mjs';import {TerminalBrowserCleanup} from '../src/terminal-browser-cleanup.mjs';import {ExtensionManager} from '../src/browser/extension-manager.mjs';import {cancelledCleanupConfirmed} from '../src/retired-requests.mjs';import {keyFor} from '../src/lifecycle.mjs';
function setup({state='CANCELLED',id='legacy-cdp-id',pending=false}={}){
 const marker={task_id:'TASK',turn_id:'TURN',request_id:'REQUEST',state,retired:true,control_epoch:1,browser_generation:0,never_submitted:true,pre_submit_proof:{schema:1},browser_open_pending:pending};marker.key=keyFor(marker);
 const entry={target_id:id,key:marker.key,task_id:marker.task_id,turn_id:marker.turn_id,request_id:marker.request_id,control_epoch:1};
 const data={generation:1,lifecycle:{schema:1,requests:{},controls:{TASK:{schema:'selfrun-task-control-v1',task_id:'TASK',turn_id:'TURN',request_id:'REQUEST',state:'STOPPED',control_epoch:1}},retired:{[marker.key]:marker},browserCleanup:{[id]:entry},outbox:{},completionOutbox:{},events:[]}};
 const store={value:data,snapshot(){return structuredClone(this.value)},async patch(p){this.value={...this.value,...structuredClone(p)}},async update(fn){return this.patch(fn(this.snapshot()))}};
 const manager=new ExtensionManager({});manager.journal={schema:'selfrun-extension-ownership-v1',browserSessionId:'current-session',targets:[],controllerStopPending:false};
 manager.inspectCleanupDisposition=async id=>manager.cleanupDisposition(id);
 let closes=0,releases=0;manager.closeOwnedTarget=async()=>{closes++;return{reason:'unowned_target'}};manager.releaseIfUnused=async()=>{releases++;};
 const repository=new LifecycleStore(store);const cleanup=new TerminalBrowserCleanup({repository,chromium:manager,stateStore:store,available:()=>true,busy:()=>false});
 return {marker,entry,store,manager,repository,cleanup,get closes(){return closes},get releases(){return releases}};
}
test('cancelled legacy namespace is quarantined in memory without persistent history or browser actions',async()=>{
 const f=setup();await f.cleanup.run();const life=f.repository.data;assert.deepEqual(f.store.snapshot().lifecycle.browserCleanupUnresolved,{});assert.deepEqual(life.browserCleanup,{});assert.equal(Object.keys(life.browserCleanupUnresolved).length,1);const record=Object.values(life.browserCleanupUnresolved)[0];assert.equal(record.status,'UNRESOLVED');assert.equal(record.reason,'UNADDRESSABLE_NAMESPACE');assert.deepEqual(record.entry,f.entry);assert.equal(f.closes,0);assert.equal(f.releases,1);assert.equal(life.retired[f.marker.key].state,'CANCELLED');assert.equal(life.browserCleanupOutcomes?.[f.entry.target_id],undefined);
});
test('previous extension session converges without declaring target absence',async()=>{const f=setup({id:'ext:previous-session:1'});await f.cleanup.run();assert.deepEqual(f.repository.data.browserCleanup,{});assert.equal(Object.values(f.repository.data.browserCleanupUnresolved)[0].reason,'OWNER_SESSION_CHANGED');assert.equal(f.closes,0);assert.equal(f.releases,1);});
test('current-session unowned target stays pending',async()=>{const f=setup({id:'ext:current-session:1'});await f.cleanup.run();assert.equal(Object.keys(f.repository.data.browserCleanup).length,1);assert.equal(f.repository.data.browserCleanupUnresolved,undefined);});
test('owned target stays pending',async()=>{const f=setup();f.manager.owned.add(f.entry.target_id);await f.cleanup.run();assert.equal(Object.keys(f.repository.data.browserCleanup).length,1);assert.equal(f.repository.data.browserCleanupUnresolved,undefined);});
test('pending acquisition fences quarantine',async()=>{const f=setup({pending:{generation:1}});await f.cleanup.run();assert.equal(Object.keys(f.repository.data.browserCleanup).length,1);assert.equal(f.repository.data.browserCleanupUnresolved,undefined);});
test('new same-request RUNNING control fences quarantine',async()=>{const f=setup();f.repository.data.controls.TASK.state='RUNNING';f.repository.data.controls.TASK.control_epoch=2;await f.cleanup.run();assert.equal(Object.keys(f.repository.data.browserCleanup).length,1);assert.equal(f.repository.data.browserCleanupUnresolved,undefined);});
test('new HTTP resume fences quarantine before control projection',async()=>{const f=setup();f.store.value.httpIngress={files:{resume:{...f.repository.data.controls.TASK,state:'RUNNING',control_epoch:2}}};await f.cleanup.run();assert.equal(Object.keys(f.repository.data.browserCleanup).length,1);assert.equal(f.repository.data.browserCleanupUnresolved,undefined);});
test('unresolved cancellation evidence still rejects unsafe resend',async()=>{const f=setup({state:'CANCELLED'});await f.cleanup.run();assert.deepEqual(f.repository.data.browserCleanup,{});assert.equal(cancelledCleanupConfirmed(f.repository.data,f.marker),false);});
test('old cleanup archive does not block dedicated idle-browser retirement',async()=>{
 const f=setup();await f.cleanup.run();await f.cleanup.run();assert.equal(f.releases,2);
 const m={...f.marker,task_id:'NEW',turn_id:'NEW-TURN',request_id:'NEW-REQUEST',key:keyFor({task_id:'NEW',turn_id:'NEW-TURN',request_id:'NEW-REQUEST'})};
 const e={...f.entry,target_id:'ext:current-session:2',task_id:m.task_id,turn_id:m.turn_id,request_id:m.request_id,key:m.key};
 f.repository.data.retired[m.key]=m;f.repository.data.browserCleanup[e.target_id]=e;f.manager.owned.add(e.target_id);f.manager.closeOwnedTarget=async()=>({closed:true});await f.cleanup.run();assert.ok(f.releases>0);assert.equal(Object.keys(f.repository.data.browserCleanupUnresolved).length,1);
});
