import { projection } from './helpers/legacy-projection.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';
import { keyFor } from '../src/lifecycle.mjs';
import { fixture, MemoryStore } from './helpers/lifecycle-fixture.mjs';

// Exercise actual browser entry decisions and durable controller state. Only the
// extension's transport and page responses are synthetic; no real site is used.
function entryFixture() {
  const f=fixture(),targets=new Map(),calls={create:[],resume:[],close:[]};
  let sequence=0;calls.prepare=[];
  const chromium={recoveryNavigationDisabled:true,
    listExistingTargets:async()=>[...targets.values()].map(t=>({...t})),
    isOwnedTarget:id=>targets.has(id),
    createTarget:async url=>{
      calls.create.push(url);
      if(f.createError)throw f.createError;
      const target={id:'ext:'+(++sequence),type:'page',url};targets.set(target.id,target);return {...target};
    },
    connectTarget:async target=>({transport:'extension',close(){},
      async run(operation,payload={}) {
        if(operation==='initialize'){if(f.loginPage){targets.get(target.id).url='https://chatgpt.com/auth/login';throw Error('Owned action page was repurposed');}return {};}
        if(operation==='navigate'){if(f.navigationError)throw f.navigationError;targets.get(target.id).url=payload.url;return {};}
        assert.equal(operation,'snapshot');
        if(f.snapshotError)throw f.snapshotError;
        const current=targets.get(target.id);
        if(f.loginPage)current.url='https://chatgpt.com/auth/login';
        return {...f.probe,url:current.url,loginPage:!!f.loginPage};
      },
      call(){assert.fail('entry must not issue CDP commands');},
    }),
    closeTarget:async id=>{
      calls.close.push(id);
      if(f.closeDenied)return false;
      targets.delete(id);return true;
    },
    closeOwnedTarget:async(id,{guard})=>{
      const target=targets.get(id);
      if(!target)return {absent:true};
      if(!guard(target))return {closed:false};
      calls.close.push(id);targets.delete(id);return {closed:true};
    },
    releaseIfUnused:async()=>({released:false}),
  };
  const browser=new ChatGptBrowser(chromium,{navigationTimeoutMs:10});
  f.browser.chromium=chromium;
  f.browser.resume=async args=>{f.resumes++;calls.resume.push({...args});return browser.resume(args);};
  f.browser.prepare=async args=>{f.prepares++;calls.prepare.push({...args});return browser.prepare(args);};
  f.controller=f.recreate();
  f.calls=calls;f.targets=targets;
  f.elapsed=async()=>f.controller.repository.move(keyFor(f.body),f.record().state,'test retry elapsed',{retry_at:0,result_retry_at:0});
  f.loseObservation=async()=>{
    await f.observe({});
    await f.controller.repository.move(keyFor(f.body),'STALLED','test observation loss',{last_progress_at:0});
    f.browser.recoveryInputSnapshot=async()=>{throw Object.assign(Error('synthetic page session lost'),{code:'TARGET_LOST'});};
    await f.controller.tick(f.transport);
    assert.equal(f.controller.sessions.size,0);
  };
  return f;
}

test('explicit app RESUME opens the same URL after STOP closed its owned tab',async()=>{
  const f=entryFixture();await f.existing();await f.stop();await f.controller.cleanupTerminalResources();
  assert.equal(f.targets.size,0);
  await f.resume();
  assert.equal(f.calls.resume.at(-1).automaticRecovery,false);
  assert.deepEqual(f.calls.create,[f.probe.url,f.probe.url]);
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.record().browser_generation,1);
  assert.equal(f.prepares,0);assert.equal(f.sends,0);
  await f.controller.quiesceForStandby();
});

test('fresh app URL entry keeps retrying before any target was created',async()=>{
  const f=entryFixture();f.createError=Error('synthetic creation unavailable');await f.existing();
  for(let i=0;i<5;i++){
    const count=f.calls.create.length;await f.controller.tick(f.transport);assert.equal(f.calls.create.length,count);
    await f.elapsed();await f.controller.tick(f.transport);
  }
  assert.equal(f.calls.create.length,6);assert.ok(f.calls.resume.every(args=>args.automaticRecovery===false));
  assert.equal(f.record().blocked_epoch,null);assert.ok(f.record().retry_at>Date.now());
  f.createError=null;await f.elapsed();await f.controller.tick(f.transport);
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.sends,0);
  await f.controller.quiesceForStandby();
});

test('pending explicit RESUME survives failure and server reconstruction without a retry budget',async()=>{
  const f=entryFixture();await f.existing();await f.stop();await f.controller.cleanupTerminalResources();
  f.createError=Error('synthetic creation unavailable');await f.resume();
  f.controller=f.recreate();
  for(let i=0;i<5;i++){await f.elapsed();await f.controller.tick(f.transport);}
  assert.equal(f.calls.create.length,7);assert.ok(f.calls.resume.every(args=>args.automaticRecovery===false));
  f.createError=null;await f.elapsed();await f.controller.tick(f.transport);
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.sends,0);
  await f.controller.quiesceForStandby();
});

test('unavailable initial DOM does not gate explicit entry or create a replacement tab',async()=>{
  const f=entryFixture();f.snapshotError=Error('synthetic snapshot unavailable');await f.existing();
  assert.equal(f.targets.size,1);assert.equal(f.calls.close.length,0);
  f.snapshotError=null;await f.elapsed();await f.controller.tick(f.transport);
  assert.equal(f.calls.create.length,1);assert.equal(f.calls.resume.at(-1).automaticRecovery,false);
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.sends,0);
  await f.controller.quiesceForStandby();
});

test('fresh preparation retries failed navigation on the same owned tab',async()=>{
  const f=entryFixture();f.body.client_status='CREATE_REQUESTED';f.navigationError=Error('synthetic navigation unavailable');f.closeDenied=true;
  await f.ingest();const priorId=f.record().target_id;assert.equal(f.targets.size,1);
  for(let i=0;i<3;i++){await f.elapsed();await f.controller.tick(f.transport);}
  assert.equal(f.calls.prepare.at(-1).previousTargetId,priorId);
  assert.equal(f.calls.create.length,1);assert.equal(f.targets.size,1);assert.equal(f.sends,0);
  f.closeDenied=false;f.navigationError=null;await f.elapsed();await f.controller.tick(f.transport);
  assert.equal(f.calls.create.length,1);assert.equal(f.targets.size,1);assert.equal(f.record().state,'PREPARED');
  assert.equal(f.sends,0);await f.controller.quiesceForStandby();
});

test('unconfirmed disposal of a noncanonical owned entry tab cannot accumulate new tabs',async()=>{
  const f=entryFixture();f.loginPage=true;f.closeDenied=true;await f.existing();
  assert.equal(f.targets.size,1);const priorId=f.record().target_id;
  for(let i=0;i<3;i++){await f.elapsed();await f.controller.tick(f.transport);}
  assert.equal(f.calls.resume.at(-1).previousTargetId,priorId);
  assert.equal(f.calls.create.length,1);assert.equal(f.targets.size,1);
  assert.equal(f.record().state,'BLOCKED');assert.equal(f.sends,0);
  await f.stop();await f.controller.cleanupTerminalResources();
});

test('STOP cancels pending explicit entry before another retry can create a target',async()=>{
  const f=entryFixture();f.createError=Error('synthetic creation unavailable');await f.existing();
  await f.stop();for(let i=0;i<3;i++)await f.controller.tick(f.transport);
  assert.equal(f.calls.create.length,1);assert.equal(f.record(),null);
  assert.equal(f.controller.sessions.size,0);assert.equal(f.sends,0);
});

test('client cancellation retires pending entry without replay from its old dispatch',async()=>{
  const f=entryFixture();f.createError=Error('synthetic creation unavailable');await f.existing();
  const oldBody=structuredClone(f.rows.get(f.path));f.body.client_status='CANCELLED';await f.ingest();
  await f.controller.ingest(f.path,oldBody,f.transport);
  for(let i=0;i<3;i++)await f.controller.tick(f.transport);
  assert.equal(f.calls.create.length,1);assert.equal(f.record().state,'CANCELLED');assert.equal(f.record().body,undefined);
  assert.equal(f.controller.sessions.size,0);assert.equal(f.sends,0);
});

test('successful entry consumes navigation authority before automatic observation retry',async()=>{
  const f=entryFixture();await f.existing();await f.loseObservation();f.targets.clear();
  for(let i=0;i<3;i++){await f.elapsed();await f.controller.tick(f.transport);}
  assert.equal(f.calls.resume.at(-1).automaticRecovery,true);assert.equal(f.calls.create.length,1);
  assert.equal(f.record().error.code,'PAGE_UNAVAILABLE');assert.equal(f.sends,0);
  await f.stop();
});

test('same RUNNING higher-epoch metadata cannot regrant successful entry navigation',async()=>{
  const f=entryFixture();await f.existing();await f.loseObservation();f.targets.clear();
  f.control={...f.control,control_epoch:2,conversation_url:f.probe.url};
  await f.controller.control(null,f.control,f.transport);await f.elapsed();await f.controller.tick(f.transport);
  assert.equal(f.calls.resume.at(-1).automaticRecovery,true);assert.equal(f.calls.create.length,1);
  assert.equal(f.record().error.code,'PAGE_UNAVAILABLE');await f.stop();
});

test('same RUNNING metadata refresh preserves an already pending entry retry',async()=>{
  const f=entryFixture();f.createError=Error('synthetic creation unavailable');await f.existing();
  f.control={...f.control,control_epoch:2,conversation_url:f.probe.url};
  await f.controller.control(null,f.control,f.transport);f.createError=null;
  await f.elapsed();await f.controller.tick(f.transport);
  assert.equal(f.calls.resume.at(-1).automaticRecovery,false);assert.equal(f.record().state,'OBSERVING');
  await f.controller.quiesceForStandby();
});

test('temporary unknown control preserves pending entry across a higher RUNNING metadata refresh',async()=>{
  const f=entryFixture();f.createError=Error('synthetic creation unavailable');await f.existing();
  await f.controller.repository.invalidateControl(keyFor(f.body));assert.equal(f.record().control_state,'UNKNOWN');
  f.control={...f.control,control_epoch:2,conversation_url:f.probe.url};
  await f.controller.control(null,f.control,f.transport);f.createError=null;
  await f.elapsed();await f.controller.tick(f.transport);
  assert.equal(f.calls.resume.at(-1).automaticRecovery,false);assert.equal(f.record().state,'OBSERVING');
  await f.controller.quiesceForStandby();
});

test('matching explicit PAUSED to RUNNING app URL grants a new entry',async()=>{
  const f=entryFixture();await f.existing();
  f.control={...f.control,state:'PAUSED',control_epoch:2};await f.controller.control(null,f.control,f.transport);
  f.targets.clear();await f.resume();
  assert.equal(f.calls.resume.at(-1).automaticRecovery,false);assert.equal(f.calls.create.length,2);
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.sends,0);
  await f.controller.quiesceForStandby();
});

test('cold Drive lifecycle projection cannot import pending entry authorization',async()=>{
  const f=entryFixture();await f.existing();await f.controller.quiesceForStandby();
  const body=projection(f.record());
  body.lifecycle_record.browser_entry={control_epoch:1,conversation_url:f.probe.url};f.rows.set(f.path,body);
  f.targets.clear();f.store=new MemoryStore();f.controller=f.recreate();
  await f.controller.ingest(f.path,body,f.transport);
  assert.equal(f.calls.resume.at(-1).automaticRecovery,true);assert.equal(f.calls.create.length,1);
  assert.equal(f.record().error.code,'PAGE_UNAVAILABLE');await f.stop();
});

test('successful explicit RESUME remains observation-only after a local server restart',async()=>{
  const f=entryFixture();await f.existing();await f.stop();await f.controller.cleanupTerminalResources();await f.resume();
  await f.controller.quiesceForStandby();f.controller=f.recreate();f.targets.clear();
  await f.controller.tick(f.transport);
  assert.equal(f.calls.resume.at(-1).automaticRecovery,true);assert.equal(f.calls.create.length,2);
  assert.equal(f.record().error.code,'PAGE_UNAVAILABLE');assert.equal(f.sends,0);await f.stop();
});

test('cold PAUSED projection accepts the newer explicit app URL control as entry provenance',async()=>{
  const f=entryFixture();await f.existing();
  f.control={...f.control,state:'PAUSED',control_epoch:2};await f.controller.control(null,f.control,f.transport);
  const body=projection(f.record());assert.equal(body.lifecycle_record.state,'PAUSED');
  f.targets.clear();f.store=new MemoryStore();f.controller=f.recreate();
  f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
  await f.controller.ingest(f.path,body,f.transport);
  assert.equal(f.calls.resume.at(-1).automaticRecovery,false);assert.equal(f.calls.create.length,2);
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.sends,0);await f.controller.quiesceForStandby();
});

test('current app URL grants entry without consulting an old PAUSED audit',async()=>{
  const f=entryFixture();await f.existing();
  f.control={...f.control,state:'PAUSED',control_epoch:2};await f.controller.control(null,f.control,f.transport);
  const staleBody=projection(f.record());f.targets.clear();await f.resume();
  const acceptedRevision=f.record().revision;await f.controller.quiesceForStandby();
  f.targets.clear();f.store=new MemoryStore();f.controller=f.recreate();await f.controller.initialize();
  f.controller.repository.history.audits.set(keyFor(f.body),acceptedRevision);
  await f.controller.ingest(f.path,staleBody,f.transport);
  assert.equal(f.calls.resume.at(-1).automaticRecovery,false);assert.equal(f.calls.create.length,3);
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.sends,0);await f.stop();
});

for(const state of ['STOPPED','CANCELLED'])test('stale cold '+state+' projection cannot veto a current explicit URL command',async()=>{
  const f=entryFixture();await f.existing();const staleBody=projection(f.record());
  await f.stop();await f.controller.cleanupTerminalResources();
  Object.assign(staleBody.lifecycle_record,{state,control_state:'STOPPED',control_epoch:2,revision:staleBody.lifecycle_record.revision});
  Object.assign(staleBody,{lifecycle_state:state,server_control_state:'STOPPED',server_control_epoch:2,client_status:'CANCELLED'});
  await f.resume();const acceptedRevision=f.record().revision;await f.controller.quiesceForStandby();
  f.targets.clear();f.store=new MemoryStore();f.controller=f.recreate();await f.controller.initialize();
  f.controller.repository.history.audits.set(keyFor(f.body),acceptedRevision);
  await f.controller.control(null,f.control,f.transport);await f.controller.ingest(f.path,staleBody,f.transport);
  assert.equal(f.record().state,'OBSERVING');assert.equal(f.record().resume_error,undefined);
  assert.equal(f.calls.create.length,3);assert.equal(f.calls.resume.length,3);assert.equal(f.sends,0);await f.controller.quiesceForStandby();
});
