import test from 'node:test';
import assert from 'node:assert/strict';
const {DriveDispatchWatcher}=await import(process.env.WATCHER_MODULE||'../src/drive-watcher.mjs');
const pathFor=(request,attempt=1)=>'__SELFRUN_DISPATCH__'+request+'__A'+attempt+'.json';
function fixture(state='DONE') {
 const old={schema:'selfrun-task-control-v1',task_id:'OLD',turn_id:'OLD:turn:3',request_id:'OLD:turn:3-request',state,control_epoch:9};
 const current={schema:'selfrun-task-control-v1',task_id:'NEW',turn_id:'NEW:turn:1',request_id:'NEW:turn:1-request',state:'RUNNING',control_epoch:1};
 const oldPath=pathFor(old.request_id),currentPath=pathFor(current.request_id),reads=[],ingested=[],controls=new Map();
 const body=c=>({schema:'selfrun-server-dispatch-v1',task_id:c.task_id,turn_id:c.turn_id,request_id:c.request_id,dispatch_attempt:1});
 const transport={list:async()=>[
  {path:oldPath,modTime:'1',size:0},{path:'__SELFRUN_CONTROL__OLD.json',modTime:String(old.control_epoch),size:20},
  {path:currentPath,modTime:'2',size:20},{path:'__SELFRUN_CONTROL__NEW.json',modTime:String(current.control_epoch),size:20},
 ],read:async path=>{reads.push(path);if(path===oldPath)return JSON.parse('');if(path===currentPath)return body(current);return structuredClone(path==='__SELFRUN_CONTROL__OLD.json'?old:current);}};
 const controller={tick:async()=>{},controlForTask:task=>controls.get(task),controlDurable:async(_p,c)=>{if(c.state==='RUNNING')controls.set(c.task_id,c);else controls.delete(c.task_id);},ingestDurable:async(_p,b)=>{ingested.push(b);}};
 const watcher=new DriveDispatchWatcher({transport,controller,config:{}});
 return {watcher,transport,controller,old,current,oldPath,currentPath,reads,ingested,body};
}
for(const state of ['DONE','STOPPED','PAUSED','WAITING_USER_INTERVENTION'])test('out-of-scope '+state+' body is never read or retried while the new request is ingested',async t=>{
 t.mock.method(console,'error',()=>{});const f=fixture(state);t.after(()=>f.watcher.stop());
 await f.watcher.scanOnce();await f.watcher.scanOnce();
 assert.equal(f.reads.filter(p=>p===f.oldPath).length,0);
 assert.equal(f.ingested.filter(b=>b.task_id==='NEW').length,1);
 for(const key of ['failures','readFailures','deferred','inputs','seen'])assert.equal(f.watcher[key].has(f.oldPath),false,key+' must not retain out-of-scope identity');
});
test('RUNNING different request is excluded before its malformed old body',async t=>{
 t.mock.method(console,'error',()=>{});const f=fixture('RUNNING');f.old.request_id='OLD:turn:4-request';f.old.turn_id='OLD:turn:4';t.after(()=>f.watcher.stop());
 await f.watcher.scanOnce();assert.equal(f.reads.includes(f.oldPath),false);assert.equal(f.watcher.failures.size,0);
});
test('current malformed body still reports a read failure',async t=>{
 t.mock.method(console,'error',()=>{});const f=fixture('RUNNING');t.after(()=>f.watcher.stop());
 await assert.rejects(f.watcher.scanOnce(),/Unexpected end of JSON input/);assert.equal(f.reads.includes(f.oldPath),true);assert.equal(f.watcher.failures.has(f.oldPath),true);
});
test('unlisted current control retains body-error pacing between polls',async t=>{
 t.mock.timers.enable({apis:['Date'],now:10000});t.mock.method(console,'error',()=>{});const f=fixture('RUNNING');t.after(()=>f.watcher.stop());
 const list=f.transport.list;f.transport.list=async()=>(await list()).filter(row=>row.path!=='__SELFRUN_CONTROL__OLD.json');
 await assert.rejects(f.watcher.scanOnce(),/Unexpected end/);await f.watcher.scanOnce();
 assert.equal(f.reads.filter(p=>p===f.oldPath).length,1,'unchanged current control cannot reset the body backoff');
});
test('confirmed missing control drops positive admission metadata',async t=>{
 t.mock.method(console,'error',()=>{});const f=fixture();t.after(()=>f.watcher.stop());await f.watcher.scanOnce();
 const list=f.transport.list,read=f.transport.read;f.transport.list=async()=>(await list()).filter(row=>row.path!=='__SELFRUN_CONTROL__NEW.json');
 f.transport.read=async path=>{if(path==='__SELFRUN_CONTROL__NEW.json')throw Object.assign(Error('missing'),{code:'DRIVE_FILE_NOT_FOUND'});return read(path);};
 f.watcher.seen.delete(f.currentPath);await f.watcher.scanOnce();assert.equal(f.watcher.runningControls.has('NEW'),false);
});
test('same conversation watcher refresh preserves its live observation and sends no prompt',async()=>{
 const f=(await import('./helpers/lifecycle-fixture.mjs')).fixture();await f.existing();const observer=f.observations.at(-1);
 f.control={...f.control,conversation_url:f.probe.url};const w=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
 await w.scanOnce();await f.settle();f.control={...f.control,control_epoch:2};await w.scanOnce();await f.settle();
 assert.equal(observer.signal.aborted,false);assert.equal(f.resumes,1);assert.equal(f.sends,0);w.stop();await f.controller.quiesceForStandby();
});
test('explicit same-request resume after STOP remains an observation without new submission',async()=>{
 const f=(await import('./helpers/lifecycle-fixture.mjs')).fixture();await f.existing();await f.stop();
 const w=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});await w.scanOnce();
 f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};await w.scanOnce();await f.settle();await f.controller.tick(f.transport);
 assert.equal(f.resumes,2);assert.equal(f.sends,0);assert.equal(f.record().conversation_url,f.probe.url);w.stop();await f.controller.quiesceForStandby();
});
