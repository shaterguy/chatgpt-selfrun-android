import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';

test('historical malformed JSON is never read while current STOP and lifecycle ticks continue',async t=>{
 t.mock.timers.enable({apis:['Date'],now:10000});t.mock.method(console,'error',()=>{});
 const oldPath='__SELFRUN_DISPATCH__OLD:turn:1-request__A1.json',oldControl='__SELFRUN_CONTROL__OLD.json',controlPath='__SELFRUN_CONTROL__ACTIVE.json';
 let arrived=false,oldReads=0,ticks=0,applied=null;
 const transport={list:async()=>[
  ...(arrived?[{path:controlPath,modTime:'2',size:100}]:[]),
  {path:oldPath,modTime:'1',size:0},{path:oldControl,modTime:'1',size:100},
 ],read:async path=>{
  if(path===oldPath){oldReads++;return JSON.parse('');}
  const task=path===oldControl?'OLD':'ACTIVE';
  return {schema:'selfrun-task-control-v1',task_id:task,turn_id:task+':turn:1',request_id:task+':turn:1-request',control_epoch:2,state:task==='OLD'?'DONE':'STOPPED'};
 }};
 const controller={tick:async()=>{ticks++;},controlDurable:async(_path,c)=>{if(c.task_id==='ACTIVE')applied=c;}};
 const watcher=new DriveDispatchWatcher({transport,controller,config:{}});t.after(()=>watcher.stop());
 await watcher.scanOnce();const ticksAfterFirst=ticks;arrived=true;
 await watcher.scanOnce();t.mock.timers.tick(60000);await watcher.scanOnce();
 assert.equal(applied.state,'STOPPED');assert.ok(ticks>ticksAfterFirst);assert.equal(oldReads,0);
 for(const name of ['files','seen','inputs','deferred','failures','readFailures'])assert.equal(watcher[name].has(oldPath),false,name+' retains no old dispatch');
});
