import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';

test('empty historical JSON backs off without blocking current STOP control or lifecycle ticks',async t=>{
  t.mock.timers.enable({apis:['Date'],now:10000});
  t.mock.method(console,'error',()=>{});
  const oldPath='__SELFRUN_DISPATCH__OLD-EMPTY.json',controlPath='__SELFRUN_CONTROL__ACTIVE.json';
  let arrived=false,oldReads=0,ticks=0,applied=null,parseFailure=null;
  const transport={list:async()=>[
    ...(arrived?[{path:controlPath,modTime:'2',size:100}]:[]),
    {path:oldPath,modTime:'1',size:0},
  ],read:async path=>{
    if(path===oldPath) {
      oldReads++;
      try {return JSON.parse('');}catch(error){parseFailure=error;throw error;}
    }
    return {schema:'selfrun-task-control-v1',task_id:'ACTIVE',turn_id:'t1',request_id:'r1',control_epoch:2,state:'STOPPED'};
  }};
  const controller={tick:async()=>{ticks++;},controlDurable:async(_path,control)=>{applied=control;}};
  const watcher=new DriveDispatchWatcher({transport,controller,config:{}});
  t.after(()=>watcher.stop());
  await assert.rejects(watcher.scanOnce(),/Unexpected end of JSON input/);
  assert.ok(parseFailure instanceof SyntaxError,'the historical body fails JSON parsing');
  assert.equal(oldReads,1);assert.equal(watcher.seen.has(oldPath),false);
  const ticksAfterFailure=ticks;
  arrived=true;
  await watcher.scanOnce();await watcher.scanOnce();
  assert.equal(applied.state,'STOPPED');assert.equal(applied.control_epoch,2);
  assert.ok(ticks>ticksAfterFailure,'lifecycle ticks continue while historical parsing is backed off');
  assert.equal(oldReads,1,'unchanged empty JSON is not retried on every poll');
  t.mock.timers.tick(1000);
  await assert.rejects(watcher.scanOnce(),/Unexpected end of JSON input/);
  assert.equal(oldReads,2);
  await watcher.scanOnce();assert.equal(oldReads,2,'the next exponential retry delay is retained');
});
