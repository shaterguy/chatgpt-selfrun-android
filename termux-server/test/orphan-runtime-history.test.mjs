import test from 'node:test';
import assert from 'node:assert/strict';
import {discardTerminalHistorySnapshot} from '../src/maintenance-quarantine.mjs';
const identity=(name)=>({key:name,task_id:name,turn_id:name+':turn',request_id:name+':request'});
const empty=()=>({status:'IDLE',activeCount:0,activeDispatches:[],lifecycle:{requests:{},controls:{},retired:{},outbox:{},completionOutbox:{},browserCleanup:{},browserCleanupUnresolved:{},browserCleanupOutcomes:{},events:[]}});
test('startup drops orphan runtime events and cleanup outcomes without retaining ended identities',()=>{
 const s=empty(),old=identity('ended');s.lifecycle.events.push({...old,reason:'OLD'});s.lifecycle.browserCleanupOutcomes.old={key:old.key,target_id:'old-target',status:'CLOSED'};
 const next=discardTerminalHistorySnapshot(s);
 assert.deepEqual(next.lifecycle.events,[]);assert.deepEqual(next.lifecycle.browserCleanupOutcomes,{});assert.equal(JSON.stringify(next).includes('ended'),false);
 assert.equal(s.lifecycle.events.length,1,'input snapshot is unchanged');
});
test('orphan pruning preserves current request and current cancellation evidence in mixed snapshot',()=>{
 const s=empty(),live={...identity('live'),state:'MONITORING'},cancel={...identity('cancel'),state:'CANCELLED',control_epoch:2};
 s.lifecycle.requests.live=live;s.lifecycle.retired.cancel=cancel;s.lifecycle.controls.cancel={...identity('cancel'),state:'RUNNING',control_epoch:2};
 s.lifecycle.events=[{...identity('ended'),reason:'OLD'},{...live,reason:'CURRENT'},{...cancel,reason:'CURRENT_CANCEL'}];
 s.lifecycle.browserCleanupOutcomes={old:{key:'ended',status:'CLOSED'},liveTarget:{key:'live',status:'CLOSED'},cancelTarget:{key:'cancel',status:'CLOSED'}};
 const next=discardTerminalHistorySnapshot(s);
 assert.deepEqual(next.lifecycle.events.map(e=>e.key),['live','cancel']);assert.deepEqual(Object.keys(next.lifecycle.browserCleanupOutcomes),['liveTarget','cancelTarget']);
 assert.deepEqual(next.lifecycle.requests.live,live);assert.deepEqual(next.lifecycle.retired.cancel,cancel);
});
test('current cancelled marker preserves its real unkeyed cleanup receipt only',()=>{
 const s=empty(),cancel={...identity('cancel'),state:'CANCELLED',control_epoch:2};
 const receipt={target_id:'cancel-target',status:'CLOSED',observed_at_ms:123,reason:'closed'};
 cancel.browser_cleanup=receipt;s.lifecycle.retired.cancel=cancel;s.lifecycle.controls.cancel={...identity('cancel'),state:'RUNNING',control_epoch:2};
 s.lifecycle.browserCleanupOutcomes={'cancel-target':structuredClone(receipt),old:{target_id:'old',status:'CLOSED',observed_at_ms:100,reason:'closed'}};
 const next=discardTerminalHistorySnapshot(s);assert.deepEqual(next.lifecycle.browserCleanupOutcomes,{'cancel-target':receipt});
 s.lifecycle.browserCleanupOutcomes['cancel-target']={...receipt,observed_at_ms:122};
 assert.deepEqual(discardTerminalHistorySnapshot(s).lifecycle.browserCleanupOutcomes,{});
});
