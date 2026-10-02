import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';
import { fixture } from './helpers/lifecycle-fixture.mjs';
test('watcher ingests control before dispatch regardless of listing order',async()=>{
 const f=fixture();f.control.state='STOPPED';
 const list=f.transport.list;f.transport.list=async()=>(await list()).reverse();
 const w=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
 await w.scanOnce();assert.equal(f.record().state,'STOPPED');assert.equal(f.prepares,0);
});
test('watcher retries failed ingestion without marking the file seen',async()=>{
 const f=fixture();let once=true;const ingest=f.controller.ingestDurable.bind(f.controller);
 f.controller.ingestDurable=async(...args)=>{if(once){once=false;throw new Error('temporary');}return ingest(...args);};
 const w=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
 await assert.rejects(w.scanOnce(),/temporary/);await w.scanOnce();
 for(let i=0;i<8&&f.prepares===0;i++)await new Promise(resolve=>setImmediate(resolve));
 assert.equal(f.prepares,1);
});
test('watcher skips dispatches outside current control identity',async()=>{
 const f=fixture();f.control.request_id='other';
 await new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}}).scanOnce();
 assert.equal(f.prepares,0);assert.equal(f.record(),null);
});
test('watcher stops before ingestion when cluster ownership is lost during read',async()=>{
 const f=fixture();let active=true;const read=f.transport.read;
 f.transport.read=async p=>{const value=await read(p);if(p===f.path)active=false;return value;};
 await new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{},canDispatch:()=>active}).scanOnce();
 assert.equal(f.prepares,0);
});
test('unchanged folder still ticks durable pending work',async()=>{
 let ticks=0;const controller={tick:async()=>{ticks++;}};
 const w=new DriveDispatchWatcher({transport:{list:async()=>[]},controller,config:{}});
 await w.scanOnce();await w.scanOnce();assert.equal(ticks,2);
});

test('watcher ignores Drive entries older than the recovery window',async()=>{
 let reads=0,ticks=0;
 const old=new Date(Date.now()-3*60*60*1000).toISOString();
 const transport={list:async()=>[
   {path:'__SELFRUN_CONTROL__OLD.json',modTime:old,size:10},
   {path:'__SELFRUN_DISPATCH__OLD.json',modTime:old,size:20},
 ],read:async()=>{reads++;throw new Error('old entry should not be read');}};
 const controller={tick:async()=>{ticks++;},control:async()=>{},ingest:async()=>{},controlForTask:()=>null};
 const w=new DriveDispatchWatcher({transport,controller,config:{dispatchRecoveryMs:2*60*60*1000}});
 await w.scanOnce();assert.equal(reads,0);assert.equal(ticks,1);
});
test('newest dispatch can load its control directly before ingestion',async()=>{
 const f=fixture();
 const original=f.transport.list;
 f.transport.list=async()=>{
   const rows=await original();
   return rows.sort((a,b)=>b.path.startsWith('__SELFRUN_DISPATCH__')?1:-1);
 };
 const w=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{dispatchRecoveryMs:7200000}});
 await w.scanOnce();
 for(let i=0;i<8&&f.prepares===0;i++)await new Promise(resolve=>setImmediate(resolve));
 assert.equal(f.prepares,1);
 assert.equal(f.controller.controlForTask(f.body.task_id).request_id,f.body.request_id);
});

function deferredWatcherFixture() {
 const dispatchPath='__SELFRUN_DISPATCH__CACHE.json',controlPath='__SELFRUN_CONTROL__CACHE.json';
 const now=Date.now();let dispatchVersion=1,controlVersion=1,current=null;
 const reads=[],ingested=[];
 let body={schema:'selfrun-server-dispatch-v1',task_id:'CACHE',turn_id:'old-turn',request_id:'old-request',dispatch_attempt:1};
 let control={schema:'selfrun-task-control-v1',task_id:'CACHE',turn_id:'new-turn',request_id:'new-request',state:'RUNNING',control_epoch:2};
 const transport={
  list:async()=>[
   {path:dispatchPath,modTime:new Date(now+dispatchVersion).toISOString(),size:20},
   {path:controlPath,modTime:new Date(now+controlVersion).toISOString(),size:10},
  ],
  read:async path=>{reads.push(path);return structuredClone(path===dispatchPath?body:control);},
 };
 const controller={
  controlDurable:async(_path,c)=>{current=structuredClone(c);},
  controlForTask:()=>structuredClone(current),
  ingestDurable:async(_path,b)=>{ingested.push(structuredClone(b));},
  tick:async()=>{},
 };
 const watcher=new DriveDispatchWatcher({transport,controller,config:{}});
 return {watcher,reads,ingested,dispatchPath,controlPath,
  changeControl(c){control={...control,...c};controlVersion++;},
  changeDispatch(b){body={...body,...b};dispatchVersion++;},
  changeCachedControl(c){current={...current,...c};},
 };
}

test('unchanged deferred dispatch does not repeat serial Drive body and control reads',async()=>{
 const f=deferredWatcherFixture();
 await f.watcher.scanOnce();await f.watcher.scanOnce();await f.watcher.scanOnce();
 assert.deepEqual(f.reads,[f.dispatchPath,f.controlPath]);
 assert.equal(f.ingested.length,0);
});

test('changed app control reconsiders an unchanged deferred dispatch without rereading its body',async()=>{
 const f=deferredWatcherFixture();await f.watcher.scanOnce();
 f.changeControl({turn_id:'old-turn',request_id:'old-request',control_epoch:3});
 await f.watcher.scanOnce();
 assert.equal(f.ingested.length,1);
 assert.equal(f.ingested[0].request_id,'old-request');
 assert.equal(f.reads.filter(p=>p===f.dispatchPath).length,1);
});

test('changed deferred dispatch is read and evaluated against current control',async()=>{
 const f=deferredWatcherFixture();await f.watcher.scanOnce();
 f.changeDispatch({turn_id:'new-turn',request_id:'new-request',dispatch_attempt:2});
 await f.watcher.scanOnce();
 assert.equal(f.ingested.length,1);
 assert.equal(f.ingested[0].dispatch_attempt,2);
 assert.equal(f.reads.filter(p=>p===f.dispatchPath).length,2);
});

test('control reconciled outside the watcher reconsiders cached deferred identity',async()=>{
 const f=deferredWatcherFixture();await f.watcher.scanOnce();
 f.changeCachedControl({turn_id:'old-turn',request_id:'old-request',control_epoch:3});
 await f.watcher.scanOnce();
 assert.equal(f.ingested.length,1);
 assert.equal(f.reads.filter(p=>p===f.dispatchPath).length,1);
});
