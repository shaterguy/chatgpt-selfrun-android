import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
const turn=()=>new Promise(r=>setImmediate(r));
const controlPath='__SELFRUN_CONTROL__ACTIVE.json';
const historicalPath='__SELFRUN_DISPATCH__HISTORY.json';

test('STOP arriving during a slow historical read is applied by the next poll',async()=>{
 const blocked=deferred(),entered=deferred();let lists=0,ticks=0,current=null;
 let control={schema:'selfrun-task-control-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1',state:'RUNNING',control_epoch:1};
 const transport={list:async()=>{lists++;return [
  {path:controlPath,modTime:String(control.control_epoch),size:10},
  {path:historicalPath,modTime:'2020-01-01T00:00:00Z',size:10},
 ];},read:async path=>{
  if(path===controlPath)return structuredClone(control);
  if(path===historicalPath){entered.resolve();await blocked.promise;return {schema:'unrelated-historical-json'};}
  throw new Error('unexpected path '+path);
 }};
 const controller={controlDurable:async(_path,c)=>{current=c;},tick:async()=>{ticks++;}};
 const watcher=new DriveDispatchWatcher({transport,controller,config:{drivePollMs:500}});
 const running=watcher.start();await entered.promise;
 control={...control,state:'STOPPED',control_epoch:2};
 await new Promise(resolve=>setTimeout(resolve,650));
 const observed={lists,ticks,controlState:current?.state,controlEpoch:current?.control_epoch,scanning:watcher.scanning};
 watcher.stop();blocked.resolve();await running;
 console.log('STOP starvation snapshot',JSON.stringify(observed));
 assert.equal(observed.controlState,'STOPPED','new STOP must not wait for historical discovery to drain');
});

test('pending work receives a tick before a slow historical read finishes',async()=>{
 const blocked=deferred(),entered=deferred();let ticks=0;
 const transport={list:async()=>[{path:historicalPath,modTime:'2020-01-01T00:00:00Z',size:10}],read:async()=>{entered.resolve();await blocked.promise;return {schema:'unrelated-historical-json'};}};
 const watcher=new DriveDispatchWatcher({transport,controller:{tick:async()=>{ticks++;}},config:{}});
 const scan=watcher.scanOnce();await entered.promise;await turn();
 const observed={ticks,scanning:watcher.scanning};blocked.resolve();await scan;
 console.log('Tick starvation snapshot',JSON.stringify(observed));
 assert.ok(observed.ticks>0,'durable pending work must advance independently of historical discovery');
});

test('newest dispatch intake precedes unrelated historical controls',async()=>{
 const blocked=deferred(),entered=deferred();let ingests=0;
 const historyControl='__SELFRUN_CONTROL__HISTORY.json';
 const dispatchPath='__SELFRUN_DISPATCH__ACTIVE.json';
 const control={schema:'selfrun-task-control-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1',state:'RUNNING',control_epoch:1};
 const dispatch={schema:'selfrun-server-dispatch-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1'};
 const transport={list:async()=>[
  {path:dispatchPath,modTime:'2026-10-04T06:59:00Z',size:20},
  {path:controlPath,modTime:'2026-10-04T06:58:00Z',size:10},
  {path:historyControl,modTime:'2020-01-01T00:00:00Z',size:10},
 ],read:async path=>{
  if(path===historyControl){entered.resolve();await blocked.promise;return {...control,task_id:'HISTORY'};}
  return path===controlPath?control:dispatch;
 }};
 const controller={controlDurable:async()=>{},controlForTask:()=>control,ingestDurable:async()=>{ingests++;},tick:async()=>{}};
 const watcher=new DriveDispatchWatcher({transport,controller,config:{}});
 const scan=watcher.scanOnce();await entered.promise;await turn();
 const observed={ingests,scanning:watcher.scanning};blocked.resolve();await scan;
 console.log('Intake starvation snapshot',JSON.stringify(observed));
 assert.equal(observed.ingests,1,'fresh dispatch intake must not wait behind unrelated historical controls');
});

async function until(predicate) {
 for(let i=0;i<100&&!predicate();i++)await turn();
 assert.ok(predicate(),'expected asynchronous watcher work to settle');
}

test('new dispatch arriving during historical discovery loads its own control and ingests promptly',async()=>{
 const blocked=deferred(),entered=deferred();let arrived=false,current=null;
 const activePath='__SELFRUN_DISPATCH__ACTIVE.json',ingested=[],reads=[];
 const control={schema:'selfrun-task-control-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1',state:'RUNNING',control_epoch:1};
 const transport={list:async()=>[
  ...(arrived?[{path:activePath,modTime:'2026-10-04T07:00:00Z',size:20},{path:controlPath,modTime:'2026-10-04T06:59:59Z',size:10}]:[]),
  {path:historicalPath,modTime:'2020-01-01T00:00:00Z',size:10},
 ],read:async path=>{
  reads.push(path);
  if(path===historicalPath){entered.resolve();await blocked.promise;return {schema:'unrelated-historical-json'};}
  if(path===controlPath)return control;
  return {schema:'selfrun-server-dispatch-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1'};
 }};
 const controller={controlForTask:()=>current,controlDurable:async(_path,c)=>{current=c;},
  ingestDurable:async(_path,b)=>{assert.equal(current?.request_id,b.request_id);ingested.push(b);},tick:async()=>{}};
 const watcher=new DriveDispatchWatcher({transport,controller,config:{}});
 await watcher.scanOnce({drain:false});await entered.promise;arrived=true;
 await watcher.scanOnce({drain:false});await until(()=>ingested.length===1);
 assert.equal(reads.filter(path=>path===controlPath).length,1,'matching control read is shared between lanes');
 watcher.stop();blocked.resolve();
});

test('stale control read cannot overwrite a newer listing and each path stays single-flight',async()=>{
 const blocked=deferred(),entered=deferred();let version=1,readCount=0,concurrent=0,maxConcurrent=0;
 const applied=[],control={schema:'selfrun-task-control-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1'};
 const transport={list:async()=>[{path:controlPath,modTime:String(version),size:10}],read:async()=>{
  readCount++;concurrent++;maxConcurrent=Math.max(maxConcurrent,concurrent);
  const epoch=version;
  if(readCount===1){entered.resolve();await blocked.promise;}
  concurrent--;return {...control,control_epoch:epoch,state:epoch===1?'RUNNING':'STOPPED'};
 }};
 const controller={activeDispatches:()=>[{body:{task_id:'ACTIVE'}}],controlDurable:async(_path,c)=>applied.push(c),tick:async()=>{}};
 const watcher=new DriveDispatchWatcher({transport,controller,config:{}});
 await watcher.scanOnce({drain:false});await entered.promise;version=2;
 await watcher.scanOnce({drain:false});assert.equal(readCount,1);
 blocked.resolve();await until(()=>applied.length===1);
 assert.equal(applied[0].control_epoch,2);assert.equal(applied[0].state,'STOPPED');assert.equal(maxConcurrent,1);
 watcher.stop();
});

test('losing dispatch ownership during a control read prevents control application',async()=>{
 let owned=true,applied=0;
 const transport={list:async()=>[{path:controlPath,modTime:'1',size:10}],read:async()=>{
  owned=false;return {schema:'selfrun-task-control-v1',task_id:'ACTIVE',control_epoch:2,state:'STOPPED'};
 }};
 const watcher=new DriveDispatchWatcher({transport,controller:{controlDurable:async()=>applied++,tick:async()=>{}},config:{},canDispatch:()=>owned});
 await watcher.scanOnce();assert.equal(applied,0);
});

test('failed historical bodies back off while changed STOP controls remain responsive',async t=>{
 t.mock.timers.enable({apis:['Date'],now:10000});t.mock.method(console,'error',()=>{});
 let reads=0,stopped=false,applied=null;
 const transport={list:async()=>[
  ...(stopped?[{path:controlPath,modTime:'2',size:10}]:[]),{path:historicalPath,modTime:'1',size:10},
 ],read:async path=>{
  if(path===historicalPath){reads++;throw new Error('old Drive file unavailable');}
  return {schema:'selfrun-task-control-v1',task_id:'ACTIVE',state:'STOPPED',control_epoch:2};
 }};
 const watcher=new DriveDispatchWatcher({transport,controller:{controlDurable:async(_path,c)=>{applied=c;},tick:async()=>{}},config:{}});
 await assert.rejects(watcher.scanOnce(),/unavailable/);
 await watcher.scanOnce();assert.equal(reads,1);
 stopped=true;await watcher.scanOnce();assert.equal(applied.state,'STOPPED');assert.equal(reads,1);
 t.mock.timers.tick(1000);await assert.rejects(watcher.scanOnce(),/unavailable/);assert.equal(reads,2);
 await watcher.scanOnce();assert.equal(reads,2);
 t.mock.timers.tick(1999);await watcher.scanOnce();assert.equal(reads,2);
 t.mock.timers.tick(1);await assert.rejects(watcher.scanOnce(),/unavailable/);assert.equal(reads,3);
 watcher.stop();
});

test('STOP during queued history does not replay an old retired dispatch',async()=>{
 const f=(await import('./helpers/lifecycle-fixture.mjs')).fixture();
 f.control.state='STOPPED';
 const list=f.transport.list;
 f.transport.list=async()=>(await list()).map(file=>({...file,modTime:'2020-01-01T00:00:00Z'}));
 const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
 await watcher.scanOnce();await watcher.scanOnce();
 assert.equal(f.record().state,'STOPPED');assert.equal(f.record().retired,true);assert.equal(f.prepares,0);assert.equal(f.sends,0);
 watcher.stop();
});

test('discovery plus new intake uses at most three body reads and never duplicates a path',async()=>{
 const blocked=deferred();let arrived=false,active=0,maximum=0;
 const newPath='__SELFRUN_DISPATCH__ACTIVE.json',reads=new Map();
 const history=Array.from({length:100},(_,i)=>({path:'__SELFRUN_DISPATCH__OLD-'+i+'.json',modTime:'1',size:10}));
 const transport={list:async()=>[
  ...(arrived?[{path:controlPath,modTime:'3',size:10},{path:newPath,modTime:'2',size:10}]:[]),...history,
 ],read:async path=>{
  reads.set(path,(reads.get(path)||0)+1);active++;maximum=Math.max(maximum,active);
  await blocked.promise;active--;return {schema:'unrelated-historical-json'};
 }};
 const watcher=new DriveDispatchWatcher({transport,controller:{tick:async()=>{}},config:{}});
 await watcher.scanOnce({drain:false});await until(()=>active===1);
 arrived=true;await watcher.scanOnce({drain:false});await until(()=>active===3);
 await watcher.scanOnce({drain:false});assert.equal(active,3);assert.equal(maximum,3);
 assert.equal([...reads.values()].every(count=>count===1),true);
 watcher.stop();blocked.resolve();await until(()=>watcher.jobs.size===0);
 assert.equal(reads.size,3,'stopping does not drain queued history');
});

test('a failed new STOP keeps its priority lane for retry while discovery remains blocked',async t=>{
 t.mock.timers.enable({apis:['Date'],now:10000});t.mock.method(console,'error',()=>{});
 const blocked=deferred(),entered=deferred();let arrived=false,controlReads=0,applied=null;
 const transport={list:async()=>[
  ...(arrived?[{path:controlPath,modTime:'2',size:10}]:[]),{path:historicalPath,modTime:'1',size:10},
 ],read:async path=>{
  if(path===historicalPath){entered.resolve();await blocked.promise;return {schema:'unrelated-historical-json'};}
  controlReads++;if(controlReads===1)throw new Error('temporary STOP read failure');
  return {schema:'selfrun-task-control-v1',task_id:'ACTIVE',state:'STOPPED',control_epoch:2};
 }};
 const watcher=new DriveDispatchWatcher({transport,controller:{controlDurable:async(_path,c)=>{applied=c;},tick:async()=>{}},config:{}});
 try {
  await watcher.scanOnce({drain:false});await entered.promise;arrived=true;
  await watcher.scanOnce({drain:false});await until(()=>watcher.failures.has(controlPath));
  t.mock.timers.tick(1000);await watcher.scanOnce({drain:false});await until(()=>applied?.state==='STOPPED');
  assert.equal(controlReads,2);
 } finally {watcher.stop();blocked.resolve();}
});

test('startup tick observes the latest STOP before attempting to reattach uncertain work',async()=>{
 const f=(await import('./helpers/lifecycle-fixture.mjs')).fixture();f.mode='unknown';await f.ingest();
 assert.equal(f.record().state,'POST_UNCERTAIN');
 f.control={...f.control,state:'STOPPED',control_epoch:2};
 let attaches=0;f.browser.attachTarget=async()=>{attaches++;throw new Error('STOP must fence reattach');};
 const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
 await watcher.scanOnce();await until(()=>f.record()?.state==='STOPPED');
 assert.equal(f.record().control_epoch,2);assert.equal(attaches,0);assert.equal(f.sends,1);
 watcher.stop();
});

test('many historical dispatches share the same failing-control read backoff',async t=>{
 t.mock.timers.enable({apis:['Date'],now:10000});t.mock.method(console,'error',()=>{});
 let controlReads=0,dispatchReads=0;
 const transport={list:async()=>[
  ...Array.from({length:8},(_,i)=>({path:'__SELFRUN_DISPATCH__OLD-'+i+'.json',modTime:String(10-i),size:10})),
  {path:controlPath,modTime:'1',size:10},
 ],read:async path=>{
  if(path===controlPath){controlReads++;throw new Error('shared control unavailable');}
  dispatchReads++;return {schema:'selfrun-server-dispatch-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1'};
 }};
 const watcher=new DriveDispatchWatcher({transport,controller:{controlForTask:()=>null,tick:async()=>{}},config:{}});
 await watcher.scanOnce({drain:false});await until(()=>dispatchReads===8&&watcher.jobs.size===0);
 assert.equal(controlReads,1,'dispatch dependencies must share control backoff, not just concurrent reads');
 t.mock.timers.tick(1000);await watcher.scanOnce({drain:false});await until(()=>watcher.jobs.size===0);
 assert.equal(controlReads,2);
 watcher.stop();
});

test('the first listing prioritizes its newest dispatch over a newer unrelated control',async()=>{
 const blocked=deferred(),entered=deferred();let ingests=0,current=null;
 const unrelated='__SELFRUN_CONTROL__UNRELATED.json',dispatchPath='__SELFRUN_DISPATCH__ACTIVE.json';
 const control={schema:'selfrun-task-control-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1',state:'RUNNING',control_epoch:1};
 const transport={list:async()=>[
  {path:unrelated,modTime:'20',size:10},{path:dispatchPath,modTime:'19',size:20},{path:controlPath,modTime:'18',size:10},
 ],read:async path=>{
  if(path===unrelated){entered.resolve();await blocked.promise;return {...control,task_id:'UNRELATED'};}
  return path===controlPath?control:{schema:'selfrun-server-dispatch-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1'};
 }};
 const controller={controlForTask:()=>current,controlDurable:async(_path,c)=>{current=c;},
  ingestDurable:async()=>{assert.equal(current?.task_id,'ACTIVE');ingests++;},tick:async()=>{}};
 const watcher=new DriveDispatchWatcher({transport,controller,config:{}});
 try {await watcher.scanOnce({drain:false});await entered.promise;await turn();assert.equal(ingests,1);}
 finally {watcher.stop();blocked.resolve();}
});

test('a new dispatch retains intake priority when its matching control read is superseded',async()=>{
 const historyGate=deferred(),historyEntered=deferred(),controlGate=deferred(),controlEntered=deferred();
 let arrived=false,version=1,current=null,ingests=0;
 const dispatchPath='__SELFRUN_DISPATCH__ACTIVE.json';
 const transport={list:async()=>[
  ...(arrived?[{path:dispatchPath,modTime:'3',size:20},{path:controlPath,modTime:String(version),size:10}]:[]),
  {path:historicalPath,modTime:'0',size:10},
 ],read:async path=>{
  if(path===historicalPath){historyEntered.resolve();await historyGate.promise;return {schema:'unrelated-historical-json'};}
  if(path===controlPath){const epoch=version;if(epoch===1){controlEntered.resolve();await controlGate.promise;}
   return {schema:'selfrun-task-control-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1',state:'RUNNING',control_epoch:epoch};}
  return {schema:'selfrun-server-dispatch-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1'};
 }};
 const watcher=new DriveDispatchWatcher({transport,controller:{controlForTask:()=>current,
  controlDurable:async(_path,c)=>{current=c;},ingestDurable:async()=>{ingests++;},tick:async()=>{}},config:{}});
 try {
  await watcher.scanOnce({drain:false});await historyEntered.promise;arrived=true;
  await watcher.scanOnce({drain:false});await controlEntered.promise;version=2;
  await watcher.scanOnce({drain:false});controlGate.resolve();await until(()=>current?.control_epoch===2);
  await until(()=>ingests===1);
 } finally {watcher.stop();controlGate.resolve();historyGate.resolve();}
});

test('a deferred new dispatch keeps intake priority when a later control makes it eligible',async()=>{
 const blocked=deferred(),entered=deferred();let arrived=false,version=1,current=null,ingests=0;
 const dispatchPath='__SELFRUN_DISPATCH__ACTIVE.json';
 const transport={list:async()=>[
  ...(arrived?[{path:dispatchPath,modTime:'3',size:20},{path:controlPath,modTime:String(version),size:10}]:[]),
  {path:historicalPath,modTime:'0',size:10},
 ],read:async path=>{
  if(path===historicalPath){entered.resolve();await blocked.promise;return {schema:'unrelated-historical-json'};}
  if(path===controlPath)return {schema:'selfrun-task-control-v1',task_id:'ACTIVE',turn_id:'t1',request_id:version===1?'other':'r1',state:'RUNNING',control_epoch:version};
  return {schema:'selfrun-server-dispatch-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1'};
 }};
 const watcher=new DriveDispatchWatcher({transport,controller:{controlForTask:()=>current,
  controlDurable:async(_path,c)=>{current=c;},ingestDurable:async()=>{ingests++;},tick:async()=>{}},config:{}});
 try {
  await watcher.scanOnce({drain:false});await entered.promise;arrived=true;
  await watcher.scanOnce({drain:false});await until(()=>watcher.deferred.has(dispatchPath));assert.equal(ingests,0);
  version=2;await watcher.scanOnce({drain:false});await until(()=>ingests===1);
 } finally {watcher.stop();blocked.resolve();}
});

for(const listed of [true,false])test('malformed shared control application backs off with control listed='+listed,async t=>{
 t.mock.timers.enable({apis:['Date'],now:10000});t.mock.method(console,'error',()=>{});
 let controlReads=0,dispatchReads=0,applications=0;
 const transport={list:async()=>[
  ...Array.from({length:8},(_,i)=>({path:'__SELFRUN_DISPATCH__OLD-'+i+'.json',modTime:String(10-i),size:10})),
  ...(listed?[{path:controlPath,modTime:'1',size:10}]:[]),
 ],read:async path=>{
  if(path===controlPath){controlReads++;return {schema:'selfrun-task-control-v1',task_id:'ACTIVE',state:'RUNNING',control_epoch:0};}
  dispatchReads++;return {schema:'selfrun-server-dispatch-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1'};
 }};
 const watcher=new DriveDispatchWatcher({transport,controller:{controlForTask:()=>null,
  controlDurable:async()=>{applications++;throw new Error('invalid task control');},tick:async()=>{}},config:{}});
 await watcher.scanOnce({drain:false});await until(()=>dispatchReads===8&&watcher.jobs.size===0);
 assert.equal(controlReads,1);assert.equal(applications,1);
 t.mock.timers.tick(1000);await watcher.scanOnce({drain:false});await until(()=>watcher.jobs.size===0);
 assert.equal(controlReads,2);assert.equal(applications,2);
 watcher.stop();
});
