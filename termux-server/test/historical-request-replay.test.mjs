import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';
import { keyFor } from '../src/lifecycle.mjs';
import { fixture } from './helpers/lifecycle-fixture.mjs';

function missingControl(f,code='DRIVE_FILE_NOT_FOUND') {
  const read=f.transport.read;
  f.transport.read=async path=>{
    if(path.startsWith('__SELFRUN_CONTROL__'))throw Object.assign(new Error('missing control'),{code});
    return read(path);
  };
  f.transport.list=async()=>[{path:f.path,modTime:'2020-01-01T00:00:00Z',size:100}];
}
for(const status of ['CREATE_REQUESTED','SEND_REQUESTED'])test('historical '+status+' without app control cannot become active',async()=>{
  const f=fixture();f.body.client_status=status;f.body.server_control_epoch=0;
  f.rows.set(f.path,structuredClone(f.body));missingControl(f);
  for(let restart=0;restart<2;restart++) {
    f.controller=f.recreate();
    await new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}}).scanOnce();
    await f.settle();await f.controller.tick(f.transport);
    assert.equal(f.prepares,0);assert.equal(f.sends,0);assert.equal(f.resumes,0);
    assert.equal(f.controller.activeDispatches().length,0);
    assert.equal(f.store.snapshot().activeCount,0);
    assert.equal(f.controller.repository.records().length,0);
  }
});

test('direct ingress without app control cannot bypass discovery eligibility',async()=>{
  const f=fixture();missingControl(f);await f.ingest();
  assert.equal(f.record(),null);assert.equal(f.prepares,0);assert.equal(f.sends,0);
});

test('persisted default RUNNING record is inert without an explicit app control across restart',async()=>{
  const f=fixture();f.body.server_control_epoch=0;
  await f.controller.repository.ingest(f.path,f.body);missingControl(f);
  f.controller=f.recreate();await f.controller.initialize();
  assert.equal(f.store.snapshot().activeCount,0);
  assert.equal(f.controller.activeDispatches().length,0);
  await f.controller.tick(f.transport);
  assert.equal(f.prepares,0);assert.equal(f.sends,0);assert.equal(f.resumes,0);
});

test('missing remote control fences a persisted formerly authorized request',async()=>{
  const f=fixture();f.body.client_status='CREATE_REQUESTED';await f.ingest();
  await f.controller.quiesceForStandby();missingControl(f);
  f.controller=f.recreate();f.body.client_status='SEND_REQUESTED';await f.ingest();await f.controller.tick(f.transport);
  assert.equal(f.prepares,1);assert.equal(f.sends,0);assert.equal(f.resumes,0);
});

test('valid app control can activate a previously deferred historical-shaped dispatch',async()=>{
  const f=fixture();f.body.server_control_epoch=0;f.rows.set(f.path,structuredClone(f.body));
  const read=f.transport.read,list=f.transport.list;missingControl(f);
  const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
  await watcher.scanOnce();await f.settle();assert.equal(f.prepares,0);
  f.transport.read=read;f.transport.list=list;
  await watcher.scanOnce();await f.settle();
  assert.equal(f.prepares,1);assert.equal(f.sends,1);assert.equal(f.record().control_epoch,1);
});

test('control disappears during preparation: no POST is released',async()=>{
  const f=fixture(),prepare=f.browser.prepare;
  f.browser.prepare=async args=>{const result=await prepare(args);missingControl(f);return result;};
  await f.ingest();assert.equal(f.prepares,1);assert.equal(f.sends,0);
  assert.equal(f.controller.sessions.size,0);
});

test('a stale RUNNING read cannot override a durable newer control identity',async()=>{
  const f=fixture(),older=structuredClone(f.body),oldControl=structuredClone(f.control),oldPath=f.path;
  f.control={...f.control,control_epoch:2,request_id:'new-request'};
  f.body={...f.body,request_id:'new-request',server_control_epoch:2};f.path='__SELFRUN_DISPATCH__new-request__A1.json';
  await f.ingest();f.control=oldControl;f.rows.set(oldPath,older);
  await f.controller.ingest(oldPath,older,f.transport);assert.equal(f.prepares,1);assert.equal(f.sends,1);
  assert.equal(f.controller.repository.get(keyFor(older)),null);await f.controller.quiesceForStandby();
});

for(const [label,patch] of [
  ['wrong schema',{schema:'other'}],['epoch zero',{control_epoch:0}],
  ['negative epoch',{control_epoch:-1}],['fractional epoch',{control_epoch:1.5}],
  ['wrong task',{task_id:'OTHER'}],['unknown state',{state:'UNKNOWN'}],
])test(label+' cannot authorize a cold dispatch',async()=>{
  const f=fixture();Object.assign(f.control,patch);
  await assert.rejects(f.ingest(),/invalid task control/);
  assert.equal(f.prepares,0);assert.equal(f.sends,0);assert.equal(f.record(),null);
});
for(const patch of [{turn_id:'OTHER'},{request_id:'OTHER'},{state:'RESUME_REQUESTED'},{state:'RESUME_STOPPED_REQUESTED'}])
  test('non-running or different app identity is inert: '+JSON.stringify(patch),async()=>{
    const f=fixture();Object.assign(f.control,patch);await f.ingest();
    assert.equal(f.prepares,0);assert.equal(f.sends,0);assert.equal(f.controller.activeDispatches().length,0);
  });

test('an app dispatch without server epoch fields is authorized by its real positive-epoch control',async()=>{
  const f=fixture();delete f.body.server_control_epoch;delete f.body.server_control_state;await f.ingest();
  assert.equal(f.prepares,1);assert.equal(f.sends,1);assert.equal(f.record().control_epoch,1);
});

test('cached RUNNING without its remote control defers discovery until the control appears',async()=>{
  const f=fixture();await f.controller.controlDurable(null,f.control);
  const read=f.transport.read,list=f.transport.list;missingControl(f);
  const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
  await watcher.scanOnce();await f.settle();assert.equal(f.prepares,0);
  f.transport.read=read;f.transport.list=list;
  await watcher.scanOnce();await f.settle();
  assert.equal(f.prepares,1);assert.equal(f.sends,1);
});

test('current app control authorizes execution despite a higher historical server epoch',async()=>{
  const f=fixture();f.body.server_control_epoch=9;await f.ingest();
  assert.equal(f.prepares,1);assert.equal(f.sends,1);assert.equal(f.controller.activeDispatches().length,1);
  assert.equal(f.record().control_epoch,1);assert.equal(f.record().control_state,'RUNNING');
  await f.controller.tick(f.transport);assert.equal(f.sends,1);
  await f.controller.quiesceForStandby();
});

test('confirmed control absence clears active execution and same-epoch restoration remains usable',async()=>{
  const f=fixture();f.body.client_status='CREATE_REQUESTED';await f.ingest();
  const read=f.transport.read;missingControl(f);await f.controller.tick(f.transport);
  assert.equal(f.controller.sessions.size,0);assert.equal(f.controller.activeDispatches().length,0);
  assert.equal(f.store.snapshot().activeCount,0);assert.equal(f.controller.controlForTask(f.body.task_id),null);
  f.transport.read=read;f.body.client_status='SEND_REQUESTED';await f.ingest();
  assert.equal(f.sends,1);assert.equal(f.controller.activeDispatches().length,1);
});

test('invalid control preserves the existing observer and accepted epoch while blocking input',async()=>{
  const f=fixture();await f.existing();const observer=f.observations.at(-1);
  f.control={...f.control,schema:'invalid'};
  await assert.rejects(f.controller.tick(f.transport),/invalid task control/);
  assert.equal(observer.signal.aborted,false);assert.equal(f.controller.sessions.size,1);
  assert.equal(f.controller.activeDispatches().length,1);assert.equal(f.store.snapshot().activeCount,1);
  assert.equal(f.controller.sessions.get(keyFor(f.body)).inputAuthorized,false);
  assert.equal(f.controller.controlForTask(f.body.task_id).control_epoch,1);assert.equal(f.sends,0);
  await f.observe({responseTextLength:1234});assert.equal(observer.signal.aborted,false);
  assert.equal(f.observations.length,1);assert.equal(f.sends,0);
  await f.controller.quiesceForStandby();
});

test('rejected SEND update remains deferred when an existing CREATE loses its control',async()=>{
  const f=fixture();f.body.client_status='CREATE_REQUESTED';f.rows.set(f.path,structuredClone(f.body));
  const watcher=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
  await watcher.scanOnce();await f.settle();assert.equal(f.prepares,1);
  f.body.client_status='SEND_REQUESTED';f.rows.set(f.path,structuredClone(f.body));
  const read=f.transport.read,list=f.transport.list;missingControl(f);
  await watcher.scanOnce();await f.settle();assert.equal(f.sends,0);
  f.transport.read=read;f.transport.list=list;
  await watcher.scanOnce();await f.settle();
  assert.equal(f.sends,1);assert.equal(f.record().body.client_status,'SEND_REQUESTED');
});

test('repeated missing-control checks do not churn revision or generation, and restart can reauthorize',async()=>{
  const f=fixture();f.body.client_status='CREATE_REQUESTED';await f.ingest();
  const read=f.transport.read;missingControl(f);await f.controller.tick(f.transport);
  const suspended=f.record();
  await f.controller.tick(f.transport);await f.controller.tick(f.transport);
  assert.equal(suspended,null);assert.equal(f.record(),null);
  f.controller=f.recreate();await f.controller.initialize();assert.equal(f.store.snapshot().activeCount,0);
  f.transport.read=read;f.body.client_status='SEND_REQUESTED';await f.ingest();
  assert.equal(f.sends,1);assert.equal(f.record().control_epoch,1);
});

test('a late failed CONTROL read cannot overwrite an accepted STOP',async()=>{
  const f=fixture();await f.existing();const observer=f.observations.at(-1),read=f.transport.read;
  let release,entered;const gate=new Promise(r=>{release=r;});const waiting=new Promise(r=>{entered=r;});
  f.transport.read=async path=>{if(path.startsWith('__SELFRUN_CONTROL__')){entered();await gate;throw Object.assign(Error('missing control'),{code:'EACCES'});}return read(path);};
  const tick=f.controller.tick(f.transport);await waiting;await f.stop();
  assert.equal(observer.signal.aborted,true);release();await tick;
  assert.equal(f.record(),null);
  assert.equal(f.controller.repository.records().length,0);assert.equal(f.controller.sessions.size,0);assert.equal(f.sends,0);
});

test('a failed CONTROL read preserves a lifecycle advance already queued in the same generation',async()=>{
  const f=fixture();f.body.client_status='CREATE_REQUESTED';await f.ingest();const read=f.transport.read;
  const move=f.controller.repository.move.bind(f.controller.repository);
  let release,entered;const gate=new Promise(r=>{release=r;});const waiting=new Promise(r=>{entered=r;});
  f.transport.read=async path=>{if(path.startsWith('__SELFRUN_CONTROL__')){entered();await gate;throw Object.assign(Error('missing control'),{code:'EACCES'});}return read(path);};
  const tick=assert.rejects(f.controller.tick(f.transport),/missing control/);await waiting;
  await move(keyFor(f.body),'POST_PENDING','already queued submission intent',{intent:{id:'queued',kind:'initial',outcome:'PENDING',sends:0}});
  release();await tick;
  assert.equal(f.record().state,'POST_PENDING');assert.equal(f.record().intent.id,'queued');
  assert.equal(f.record().control_state,'RUNNING');assert.equal(f.store.snapshot().activeCount,1);assert.equal(f.sends,0);
  assert.equal(f.controller.sessions.get(keyFor(f.body)).inputAuthorized,false);
  await f.controller.quiesceForStandby();
});
