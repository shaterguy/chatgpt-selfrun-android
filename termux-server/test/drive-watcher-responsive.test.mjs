import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
const turn=()=>new Promise(r=>setImmediate(r));
async function until(predicate){for(let i=0;i<100&&!predicate();i++)await turn();assert.ok(predicate(),'watcher work settles');}
const cp=task=>'__SELFRUN_CONTROL__'+task+'.json';
const dp=(task,n=1,attempt=1)=>'__SELFRUN_DISPATCH__'+task+':turn:'+n+'-request__A'+attempt+'.json';
const control=(task,n=1)=>({schema:'selfrun-task-control-v1',task_id:task,turn_id:task+':turn:'+n,request_id:task+':turn:'+n+'-request',state:'RUNNING',control_epoch:n});
const dispatch=c=>({schema:'selfrun-server-dispatch-v1',task_id:c.task_id,turn_id:c.turn_id,request_id:c.request_id,dispatch_attempt:1});
function fixture(){
 const docs=new Map(),versions=new Map(),reads=[],applied=[],ingested=[],current=new Map();let ticks=0;
 const f={docs,versions,reads,applied,ingested,current,
  put(path,body,version=1){docs.set(path,body);versions.set(path,String(version));},
  task(task,n=1){const c=control(task,n);f.put(cp(task),c,n);f.put(dp(task,n),dispatch(c),n);return c;},
  get ticks(){return ticks;},
 };
 f.transport={list:async()=>[...docs].map(([path])=>({path,modTime:versions.get(path),size:10})),read:async path=>{
  reads.push(path);await f.beforeRead?.(path);
  if(!docs.has(path))throw Object.assign(Error('missing'),{code:'DRIVE_FILE_NOT_FOUND'});
  const body=structuredClone(docs.get(path));await f.afterRead?.(path,body);return body;
 }};
 f.controller={tick:async()=>{ticks++;await f.tickGate?.promise;},activeDispatches:()=>f.active||[],controlForTask:task=>current.get(task),
  controlDurable:async(path,c)=>{await f.apply?.(path,c);applied.push(c);if(c.state==='RUNNING')current.set(c.task_id,c);else current.delete(c.task_id);},
  ingestDurable:async(path,b)=>{ingested.push(b);},
 };
 f.watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{},canDispatch:()=>f.owned!==false});return f;
}

test('STOP and ticks do not wait for a slow current body',async t=>{
 const f=fixture(),c=f.task('ACTIVE'),gate=deferred(),entered=deferred();t.after(()=>{f.watcher.stop();gate.resolve();});
 f.beforeRead=async p=>{if(p===dp('ACTIVE')){entered.resolve();await gate.promise;}};
 await f.watcher.scanOnce({drain:false});await entered.promise;
 f.put(cp('ACTIVE'),{...c,state:'STOPPED',control_epoch:2},2);await f.watcher.scanOnce({drain:false});
 await until(()=>f.applied.some(x=>x.state==='STOPPED'));assert.ok(f.ticks>=2);assert.equal(f.watcher.scanning,false);
 gate.resolve();await until(()=>f.watcher.jobs.size===0);assert.equal(f.ingested.length,0);
});
test('unknown historical bodies are never opened while durable work keeps ticking',async()=>{
 const f=fixture();f.put('__SELFRUN_DISPATCH__HISTORY.json',{bad:true});await f.watcher.scanOnce();await f.watcher.scanOnce();
 assert.equal(f.reads.length,0);assert.equal(f.ticks,2);assert.equal(f.watcher.files.size,0);
});
test('newest current request is admitted while an unrelated control is slow',async t=>{
 const f=fixture(),gate=deferred(),entered=deferred();t.after(()=>{f.watcher.stop();gate.resolve();});
 f.task('ACTIVE');f.put(cp('HISTORY'),{...control('HISTORY'),state:'DONE'},0);
 f.beforeRead=async p=>{if(p===cp('HISTORY')){entered.resolve();await gate.promise;}};
 await f.watcher.scanOnce({drain:false});await entered.promise;await until(()=>f.ingested.length===1);assert.equal(f.ingested[0].task_id,'ACTIVE');
});
test('new current request gets its own lane while another current body is slow',async t=>{
 const f=fixture(),gate=deferred(),entered=deferred();f.task('FIRST');t.after(()=>{f.watcher.stop();gate.resolve();});
 f.beforeRead=async p=>{if(p===dp('FIRST')){entered.resolve();await gate.promise;}};
 await f.watcher.scanOnce({drain:false});await entered.promise;f.task('NEW');await f.watcher.scanOnce({drain:false});
 await until(()=>f.ingested.some(x=>x.task_id==='NEW'));assert.equal(f.reads.filter(p=>p===dp('FIRST')).length,1);
});
test('stale control read cannot overwrite a newer listing and stays single-flight',async t=>{
 const f=fixture(),gate=deferred(),entered=deferred();let concurrent=0,max=0,reads=0;f.put(cp('ACTIVE'),control('ACTIVE'));t.after(()=>{f.watcher.stop();gate.resolve();});
 f.afterRead=async(p,b)=>{if(p===cp('ACTIVE')){reads++;concurrent++;max=Math.max(max,concurrent);if(reads===1){entered.resolve();await gate.promise;}concurrent--;}};
 await f.watcher.scanOnce({drain:false});await entered.promise;f.put(cp('ACTIVE'),{...control('ACTIVE'),state:'STOPPED',control_epoch:2},2);
 await f.watcher.scanOnce({drain:false});gate.resolve();await until(()=>f.applied.length===1);
 assert.equal(f.applied[0].control_epoch,2);assert.equal(max,1);
});
test('ownership lost during control read prevents application and body read',async()=>{
 const f=fixture();f.task('ACTIVE');f.afterRead=async p=>{if(p===cp('ACTIVE'))f.owned=false;};await f.watcher.scanOnce();
 assert.equal(f.applied.length,0);assert.equal(f.reads.includes(dp('ACTIVE')),false);
});
test('current malformed body backs off but STOP discards its retry state',async t=>{
 t.mock.timers.enable({apis:['Date'],now:10000});t.mock.method(console,'error',()=>{});
 const f=fixture(),c=f.task('ACTIVE');f.beforeRead=async p=>{if(p===dp('ACTIVE'))throw Error('current parse failed');};
 await assert.rejects(f.watcher.scanOnce(),/parse failed/);await f.watcher.scanOnce();assert.equal(f.reads.filter(p=>p===dp('ACTIVE')).length,1);
 f.put(cp('ACTIVE'),{...c,state:'STOPPED',control_epoch:2},2);await f.watcher.scanOnce();t.mock.timers.tick(60000);await f.watcher.scanOnce();
 assert.equal(f.reads.filter(p=>p===dp('ACTIVE')).length,1);for(const map of ['failures','readFailures','seen','inputs','deferred'])assert.equal(f.watcher[map].has(dp('ACTIVE')),false);
});
test('queued historical dispatches produce zero reads and zero retry records',async()=>{
 const f=fixture();for(let i=0;i<100;i++){f.task('OLD'+i);f.put(cp('OLD'+i),{...control('OLD'+i),state:'DONE'},2);}
 await f.watcher.scanOnce();assert.equal(f.reads.some(p=>p.startsWith('__SELFRUN_DISPATCH__')),false);assert.equal(f.watcher.failures.size,0);assert.equal(f.watcher.runningControls.size,0);
});
test('body reads remain bounded and a path is never duplicated across lanes',async t=>{
 const f=fixture(),gate=deferred();let active=0,max=0;t.after(()=>{f.watcher.stop();gate.resolve();});f.task('FIRST');
 f.beforeRead=async p=>{active++;max=Math.max(max,active);if(p===dp('FIRST'))await gate.promise;active--;};
 await f.watcher.scanOnce({drain:false});await until(()=>f.reads.includes(dp('FIRST')));f.task('SECOND');await f.watcher.scanOnce({drain:false});await until(()=>f.ingested.length===1);
 assert.ok(max<=3);assert.equal(f.reads.filter(p=>p===dp('FIRST')).length,1);
});
test('failed STOP remains eligible for retry while another current body is blocked',async t=>{
 t.mock.timers.enable({apis:['Date'],now:10000});t.mock.method(console,'error',()=>{});
 const f=fixture(),gate=deferred(),entered=deferred();let stops=0;f.task('FIRST');t.after(()=>{f.watcher.stop();gate.resolve();});
 f.beforeRead=async p=>{if(p===dp('FIRST')){entered.resolve();await gate.promise;}if(p===cp('STOP')){stops++;if(stops===1)throw Error('temporary STOP read failure');}};
 await f.watcher.scanOnce({drain:false});await entered.promise;f.put(cp('STOP'),{...control('STOP'),state:'STOPPED'},2);
 await f.watcher.scanOnce({drain:false});await until(()=>f.watcher.failures.has(cp('STOP')));t.mock.timers.tick(1000);await f.watcher.scanOnce({drain:false});await until(()=>f.applied.some(c=>c.task_id==='STOP'));
 assert.equal(stops,2);
});
test('startup tick observes STOP before reattaching uncertain work',async()=>{
 const f=(await import('./helpers/lifecycle-fixture.mjs')).fixture();f.mode='unknown';await f.ingest();f.control={...f.control,state:'STOPPED',control_epoch:2};let attaches=0;
 f.browser.attachTarget=async()=>{attaches++;throw Error('must not attach');};const w=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
 await w.scanOnce();await until(()=>f.record()===null);assert.equal(attaches,0);assert.equal(f.sends,1);w.stop();
});
test('many request candidates share failing control backoff without reading bodies',async t=>{
 t.mock.timers.enable({apis:['Date'],now:10000});t.mock.method(console,'error',()=>{});const f=fixture();f.task('ACTIVE');
 for(let i=2;i<9;i++)f.put(dp('ACTIVE',1,i),dispatch(control('ACTIVE')));
 f.beforeRead=async p=>{if(p===cp('ACTIVE'))throw Error('shared control unavailable');};await f.watcher.scanOnce({drain:false});await until(()=>f.watcher.jobs.size===0);
 assert.equal(f.reads.filter(p=>p===cp('ACTIVE')).length,1);assert.equal(f.reads.some(p=>p.startsWith('__SELFRUN_DISPATCH__')),false);
});
test('newest request can bypass a newer unrelated slow control',async t=>{
 const f=fixture(),gate=deferred(),entered=deferred();f.put(cp('OTHER'),control('OTHER'),20);f.task('ACTIVE');f.versions.set(dp('ACTIVE'),'19');t.after(()=>{f.watcher.stop();gate.resolve();});
 f.beforeRead=async p=>{if(p===cp('OTHER')){entered.resolve();await gate.promise;}};
 await f.watcher.scanOnce({drain:false});await entered.promise;await until(()=>f.ingested.length===1);assert.equal(f.ingested[0].task_id,'ACTIVE');
});
test('superseded dependency read does not lose the pending current request',async t=>{
 const f=fixture(),gate=deferred(),entered=deferred();let once=true;f.task('ACTIVE');t.after(()=>{f.watcher.stop();gate.resolve();});
 f.afterRead=async(p,b)=>{if(p===cp('ACTIVE')&&once){once=false;entered.resolve();await gate.promise;}};
 await f.watcher.scanOnce({drain:false});await entered.promise;f.put(cp('ACTIVE'),{...control('ACTIVE'),control_epoch:2},2);await f.watcher.scanOnce({drain:false});gate.resolve();await until(()=>f.ingested.length===1);
});
test('a new control admits an unchanged previously excluded body',async()=>{
 const f=fixture();f.task('ACTIVE');f.put(cp('ACTIVE'),control('ACTIVE',2),2);await f.watcher.scanOnce();assert.equal(f.ingested.length,0);
 f.put(cp('ACTIVE'),{...control('ACTIVE'),control_epoch:3},3);await f.watcher.scanOnce();assert.equal(f.ingested.length,1);assert.equal(f.reads.filter(p=>p===dp('ACTIVE')).length,1);
});
for(const listed of [true,false])test('invalid control blocks every candidate before body reads, listed='+listed,async t=>{
 t.mock.timers.enable({apis:['Date'],now:10000});t.mock.method(console,'error',()=>{});const f=fixture();f.task('ACTIVE');f.put(cp('ACTIVE'),{...control('ACTIVE'),control_epoch:0});
 for(let i=2;i<9;i++)f.put(dp('ACTIVE',1,i),dispatch(control('ACTIVE')));
 if(!listed){const list=f.transport.list;f.transport.list=async()=>(await list()).filter(row=>row.path!==cp('ACTIVE'));}
 await f.watcher.scanOnce({drain:false});await until(()=>f.watcher.jobs.size===0);assert.equal(f.reads.filter(p=>p===cp('ACTIVE')).length,1);assert.equal(f.applied.length,0);assert.equal(f.ingested.length,0);assert.equal(f.reads.some(p=>p.startsWith('__SELFRUN_DISPATCH__')),false);
});
