import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';

const taskId='SR-20261025-103800-ABC123';
const controlPath='__SELFRUN_CONTROL__'+taskId+'.json';
const dispatchPath='__SELFRUN_DISPATCH__'+taskId+':turn:1-request__A1.json';

function fixture(initialState,{notifyTerminal=false}={}) {
  const control={schema:'selfrun-task-control-v1',task_id:taskId,
    turn_id:taskId+':turn:1',request_id:taskId+':turn:1-request',
    state:initialState,control_epoch:1};
  const dispatch={schema:'selfrun-server-dispatch-v1',task_id:taskId,
    turn_id:control.turn_id,request_id:control.request_id,dispatch_attempt:1};
  const docs=new Map([[controlPath,control],[dispatchPath,dispatch]]);
  const versions=new Map([[controlPath,1],[dispatchPath,1]]);
  const reads=[],ingested=[],callbacks=[];
  let currentControl=null;
  const transport={
    list:async()=>[...docs].map(([path])=>({path,modTime:String(versions.get(path)),size:100})),
    read:async(path)=>{reads.push(path);const body=docs.get(path);
      if(!body)throw Object.assign(Error('missing'),{code:'DRIVE_FILE_NOT_FOUND'});
      return structuredClone(body);}
  };
  const controller={
    onTerminalDiscard:(fn)=>callbacks.push(fn),
    activeDispatches:()=>[],
    tick:async()=>{},
    controlForTask:()=>currentControl,
    controlDurable:async(_path,c)=>{
      currentControl=c.state==='RUNNING'?structuredClone(c):null;
      if(notifyTerminal&&!currentControl)for(const fn of callbacks)
        fn([{task_id:taskId,turn_id:c.turn_id,request_id:c.request_id,
          control_epoch:c.control_epoch,path:dispatchPath}]);
    },
    ingestDurable:async(_path,b)=>{ingested.push(structuredClone(b));}
  };
  const watcher=new DriveDispatchWatcher({transport,controller,config:{}});
  return {watcher,reads,ingested,controlPath,dispatchPath,
    setControl:(state)=>{
      docs.set(controlPath,{...docs.get(controlPath),state,control_epoch:2});
      versions.set(controlPath,2);
    }
  };
}

for(const state of ['STOPPED','DONE']) {
  test('unchanged '+state+' file is read at most once across repeated scans',async()=>{
    const f=fixture(state);
    for(let i=0;i<5;i++)await f.watcher.scanOnce();
    assert.equal(f.reads.filter(p=>p===controlPath).length,1);
    assert.equal(f.reads.filter(p=>p===dispatchPath).length,0);
    assert.equal(f.watcher.status().pendingFiles,0);
    assert.equal(f.watcher.status().pendingReads,0);
    assert.equal(f.ingested.length,0);
  });
}

test('terminal file does not requeue through onTerminalDiscard callback',async()=>{
  const f=fixture('STOPPED',{notifyTerminal:true});
  for(let i=0;i<4;i++)await f.watcher.scanOnce();
  assert.equal(f.reads.filter(p=>p===controlPath).length,1);
  assert.equal(f.watcher.status().pendingFiles,0);
});

test('new RUNNING revision is still eligible after a terminal revision',async()=>{
  const f=fixture('STOPPED');
  await f.watcher.scanOnce();
  f.setControl('RUNNING');
  await f.watcher.scanOnce();
  assert.ok(f.reads.filter(p=>p===controlPath).length>=2);
  assert.equal(f.ingested.length,1);
  assert.equal(f.ingested[0].request_id,taskId+':turn:1-request');
});
