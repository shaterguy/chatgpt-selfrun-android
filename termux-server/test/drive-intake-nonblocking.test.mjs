import test from 'node:test';
import assert from 'node:assert/strict';
import { keyFor } from '../src/lifecycle.mjs';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';

test('durable dispatch intake returns before browser preparation completes', async () => {
  const f=fixture();
  await f.controller.controlDurable(null,f.control);
  const entered=deferred(),gate=deferred();
  f.browser.prepare=async()=>{
    f.prepares+=1;
    entered.resolve();
    await gate.promise;
    return {target:{id:'target-new'},session:{close(){}},baseline:{...f.probe}};
  };
  f.body.client_status='SEND_REQUESTED';
  f.rows.set(f.path,structuredClone(f.body));
  await f.controller.ingestDurable(f.path,f.body,f.transport);
  await entered.promise;
  assert.equal(f.prepares,1);
  assert.equal(f.controller.repository.get(keyFor(f.body)).request_id,f.body.request_id);
  gate.resolve();
  for(let i=0;i<8;i++)await new Promise(resolve=>setImmediate(resolve));
});

test('watcher consumes durable control and dispatch without awaiting lifecycle tick', async () => {
  const calls=[];
  const tickGate=deferred();
  const control={schema:'selfrun-task-control-v1',task_id:'FAST',turn_id:'FAST:turn:1',request_id:'FAST:turn:1-request',state:'RUNNING',control_epoch:1};
  const body={schema:'selfrun-server-dispatch-v1',task_id:'FAST',turn_id:'FAST:turn:1',request_id:'FAST:turn:1-request',
    dispatch_attempt:1,project_url:'https://chatgpt.com/g/test/project',prompt:'x',profile_operations:[]};
  const rows=new Map([
    ['__SELFRUN_CONTROL__FAST.json',control],
    ['__SELFRUN_DISPATCH__FAST.json',body],
  ]);
  const transport={
    list:async()=>[
      {path:'__SELFRUN_CONTROL__FAST.json',modTime:new Date().toISOString(),size:10},
      {path:'__SELFRUN_DISPATCH__FAST.json',modTime:new Date().toISOString(),size:20},
    ],
    read:async path=>structuredClone(rows.get(path)),
  };
  let current=null;
  const controller={
    controlDurable:async(_path,value)=>{calls.push('control');current=structuredClone(value);},
    controlForTask:()=>structuredClone(current),
    ingestDurable:async()=>{calls.push('dispatch');},
    tick:async()=>{calls.push('tick');await tickGate.promise;},
  };
  const watcher=new DriveDispatchWatcher({transport,controller,config:{dispatchRecoveryMs:7200000}});
  await watcher.scanOnce();
  assert.deepEqual(calls.slice(0,2),['control','dispatch']);
  assert.equal(watcher.scanning,false);
  tickGate.resolve();
  await new Promise(resolve=>setImmediate(resolve));
});

test('a second scan can ingest a fresh dispatch while the prior tick is still pending', async () => {
  const tickGate=deferred();
  const rows=new Map();
  let current=null;
  const ingested=[];
  const transport={
    list:async()=>[...rows].map(([path,body])=>({path,modTime:body.modTime,size:10})),
    read:async path=>structuredClone(rows.get(path).body),
  };
  const controller={
    controlDurable:async(_path,value)=>{current=structuredClone(value);},
    controlForTask:()=>structuredClone(current),
    ingestDurable:async(_path,body)=>{ingested.push(body.request_id);},
    tick:async()=>{await tickGate.promise;},
  };
  const now=new Date().toISOString();
  rows.set('__SELFRUN_CONTROL__FAST.json',{modTime:now,body:{schema:'selfrun-task-control-v1',task_id:'FAST',turn_id:'FAST:turn:1',request_id:'FAST:turn:1-request',state:'RUNNING',control_epoch:1}});
  rows.set('__SELFRUN_DISPATCH__FAST-A1.json',{modTime:now,body:{schema:'selfrun-server-dispatch-v1',task_id:'FAST',turn_id:'FAST:turn:1',request_id:'FAST:turn:1-request',dispatch_attempt:1,project_url:'https://chatgpt.com/g/test/project',prompt:'x',profile_operations:[]}});
  const watcher=new DriveDispatchWatcher({transport,controller,config:{dispatchRecoveryMs:7200000}});
  await watcher.scanOnce();
  rows.set('__SELFRUN_DISPATCH__FAST-A2.json',{modTime:new Date(Date.now()+1000).toISOString(),body:{...rows.get('__SELFRUN_DISPATCH__FAST-A1.json').body,dispatch_attempt:2}});
  await watcher.scanOnce();
  assert.deepEqual(ingested,['FAST:turn:1-request','FAST:turn:1-request']);
  tickGate.resolve();
});
