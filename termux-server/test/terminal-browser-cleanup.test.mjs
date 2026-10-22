import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';
import { keyFor } from '../src/lifecycle.mjs';

const idle=()=>new Promise(resolve=>setImmediate(resolve));
function ownedFixture() {
  const f=fixture();f.pages=new Set();f.owned=new Set();f.closed=[];f.releases=0;
  f.browser.chromium={
    setLifecycleGuards:guards=>{f.lifecycleGuards=guards;},
    isOwnedTarget:id=>f.owned.has(id),
    closeOwnedTarget:async(id,{guard})=>{
      await f.beforeClose?.(id);if(!guard({id,url:f.targetUrl??f.probe.url}))return {closed:false,reason:'guard'};
      if(!f.owned.has(id))return {closed:false,reason:'unowned'};
      if(f.closeFails)return {closed:false,reason:'unavailable'};
      const had=f.pages.delete(id);f.owned.delete(id);if(had)f.closed.push(id);
      return {closed:had,absent:!had};
    },
    releaseIfUnused:async({guard})=>{await f.beforeRelease?.();if(guard()&&f.pages.size===0){f.releases++;return {released:true};}return {released:false};},
  };
  for(const method of ['prepare','resume','attachTarget']){
    const original=f.browser[method];f.browser[method]=async args=>{
      const a=await original(args);f.pages.add(a.target.id);if(!f.manual)f.owned.add(a.target.id);
      await args.onTarget?.(a.target);return a;
    };
  }
  f.controller=f.recreate();
  f.clean=()=>f.controller.cleanupTerminalResources();
  return f;
}
for(const state of ['STOPPED','DONE'])test(state+' closes the owned task tab without idle expiry or browser reopen',async()=>{
  const f=ownedFixture();await f.existing();f.control={...f.control,state,control_epoch:2};
  await f.controller.control(null,f.control,f.transport);await f.clean();
  assert.deepEqual(f.closed,['target-owned']);assert.equal(f.pages.size,0);
  assert.equal(f.resumes,1);assert.equal(f.sends,0);assert.equal(f.record(),null);
  assert.deepEqual(f.store.snapshot().lifecycle.browserCleanup,{});
  assert.ok(f.releases>=1);
});
test('failed terminal cleanup consumes disposal work without restarting or retrying',async()=>{
  const f=ownedFixture();await f.existing();f.closeFails=true;await f.stop();await f.clean();
  assert.equal(f.controller.repository.cleanupEntries().length,0);assert.equal(f.pages.size,1);
  f.closeFails=false;await f.clean();
  assert.deepEqual(f.closed,[]);assert.equal(f.controller.repository.cleanupEntries().length,0);
  assert.equal(f.resumes,1);assert.equal(f.sends,0);assert.equal(f.controller.sessions.size,0);
});
test('manual matching-conversation attachment is never owned or closed',async()=>{
  const f=ownedFixture();f.manual=true;await f.existing();await f.stop();await f.clean();
  assert.deepEqual(f.closed,[]);assert.equal(f.pages.size,1);assert.equal(f.releases,0);
});
test('response idle without committed Result remains app RUNNING work',async()=>{
  const f=ownedFixture();await f.existing();f.result(false);
  await f.observe({streaming:false,stopButtonVisible:false});await f.clean();
  assert.deepEqual(f.closed,[]);assert.equal(f.pages.size,1);assert.equal(f.releases,0);
  assert.equal(f.record().control_state,'RUNNING');await f.controller.quiesceForStandby();
});
test('standby and PAUSED detach preserve owned tabs and process',async()=>{
  for(const state of ['STANDBY','PAUSED']){
    const f=ownedFixture();await f.existing();
    if(state==='STANDBY')await f.controller.quiesceForStandby();
    else {f.control={...f.control,state,control_epoch:2};await f.controller.control(null,f.control,f.transport);}
    await f.clean();assert.deepEqual(f.closed,[]);assert.equal(f.releases,0);
  }
});
test('newer RUNNING request fences an already queued close before its side effect',async()=>{
  const f=ownedFixture();await f.existing();const entered=deferred(),gate=deferred();
  f.beforeClose=async()=>{entered.resolve();await gate.promise;};
  await f.stop();await entered.promise;
  f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
  await f.controller.control(null,f.control,f.transport);gate.resolve();await f.clean();
  assert.deepEqual(f.closed,[]);assert.equal(f.releases,0);assert.equal(f.record().retired,undefined);
  await f.controller.tick(f.transport);assert.equal(f.resumes,2);await f.controller.quiesceForStandby();
});
test('other active job protects its tab and prevents process release',async()=>{
  const f=ownedFixture();await f.existing();
  const body={...f.body,task_id:'OTHER',turn_id:'OTHER:1',request_id:'OTHER:req',conversation_url:'https://chatgpt.com/c/other',server_status:'STARTED'};
  f.rows.set('other.json',body);await f.controller.repository.ingest('other.json',body);
  f.pages.add('other-owned');f.owned.add('other-owned');
  await f.stop();await f.clean();assert.deepEqual(f.closed,['target-owned']);
  assert.ok(f.pages.has('other-owned'));assert.equal(f.releases,0);
});
test('late attached target after STOP is closed without resurrecting the request',async()=>{
  const f=ownedFixture(),entered=deferred(),gate=deferred();
  const original=f.browser.resume;f.browser.resume=async args=>{entered.resolve();await gate.promise;return original(args);};
  const opening=f.existing();await entered.promise;await f.stop();gate.resolve();await opening;await f.clean();
  assert.deepEqual(f.closed,['target-owned']);assert.equal(f.controller.sessions.size,0);
  assert.equal(f.record(),null);assert.equal(f.sends,0);
});
test('late attached target after DONE is closed without quiesce or reopening',async()=>{
  const f=ownedFixture(),entered=deferred(),gate=deferred();
  const original=f.browser.resume;f.browser.resume=async args=>{entered.resolve();await gate.promise;return original(args);};
  const opening=f.existing();await entered.promise;
  f.control={...f.control,state:'DONE',control_epoch:2};await f.controller.control(null,f.control,f.transport);
  gate.resolve();await opening;await f.clean();assert.deepEqual(f.closed,['target-owned']);
  assert.equal(f.quiesces,0);assert.equal(f.sends,0);
});
test('untracked pending HTTP work prevents owned process release',async()=>{
  const f=ownedFixture();await f.existing();
  await f.store.patch({httpIngress:{files:{next:{...f.body,task_id:'PENDING',turn_id:'PENDING:1',request_id:'PENDING:req',conversation_url:'https://chatgpt.com/c/pending'}},signals:{}}});
  await f.stop();await f.clean();assert.deepEqual(f.closed,['target-owned']);assert.equal(f.releases,0);
});
test('legacy terminal marker with only a conversation digest never authorizes a guessed tab close',async()=>{
  const f=ownedFixture();await f.existing();await f.controller.quiesceForStandby();
  await f.controller.repository.move(keyFor(f.body),'STOPPED','legacy fixture',{target_owned:false});
  f.controller=f.recreate();await f.controller.initialize();await f.controller.tick(f.transport);
  assert.deepEqual(f.closed,[]);assert.equal(f.pages.size,1);assert.equal(f.releases,0);
});
test('resource cleanup does not block accepting a new app control',async()=>{
  const f=ownedFixture();await f.existing();const entered=deferred(),gate=deferred();
  f.beforeClose=async()=>{entered.resolve();await gate.promise;};
  await f.stop();await entered.promise;
  f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
  await f.controller.controlDurable(null,f.control,f.transport);
  assert.equal(f.controller.controlForTask(f.body.task_id).state,'RUNNING');gate.resolve();await f.clean();
  assert.deepEqual(f.closed,[]);
});

test('new target ownership is saved before attachment and remains cleanable after STOP during attach',async()=>{
  const f=ownedFixture(),entered=deferred(),gate=deferred();
  f.browser.resume=async args=>{
    const target={id:'early-owned'};f.pages.add(target.id);f.owned.add(target.id);await args.onTarget(target);
    entered.resolve();await gate.promise;throw new Error('attachment aborted');
  };
  const opening=f.existing();await entered.promise;assert.equal(f.record().target_id,'early-owned');
  await f.stop();await f.clean();assert.deepEqual(f.closed,['early-owned']);
  gate.resolve();await opening;assert.equal(f.record(),null);
});

test('durably accepted HTTP resume fences close before lifecycle control catches up',async()=>{
  const f=ownedFixture();await f.existing();const entered=deferred(),gate=deferred();
  f.beforeClose=async()=>{entered.resolve();await gate.promise;};await f.stop();await entered.promise;
  await f.store.patch({httpIngress:{files:{resume:{...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url}},signals:{}}});
  gate.resolve();await f.clean();assert.deepEqual(f.closed,[]);assert.equal(f.releases,0);
});

test('manually repurposed owned target with another URL survives terminal cleanup',async()=>{
  const f=ownedFixture();await f.existing();f.targetUrl='https://example.test/manual-browsing';
  await f.stop();await f.clean();assert.deepEqual(f.closed,[]);assert.equal(f.releases,0);
});

test('periodic idle sweep receives lifecycle guards that preserve live and paused targets',async()=>{
  const f=ownedFixture();await f.existing();
  assert.equal(typeof f.lifecycleGuards?.canCloseTarget,'function');
  assert.equal(f.lifecycleGuards.canCloseTarget('target-owned'),false);
  assert.equal(f.lifecycleGuards.canRelease(),false);
  f.control={...f.control,state:'PAUSED',control_epoch:2};await f.controller.control(null,f.control,f.transport);
  assert.equal(f.lifecycleGuards.canCloseTarget('target-owned'),false);
  assert.equal(f.lifecycleGuards.canRelease(),false);
  await f.controller.quiesceForStandby();assert.equal(f.lifecycleGuards.canCloseTarget('target-owned'),false);
});

for (const state of ['STOPPED', 'CANCELLED', 'COMMITTED', 'COMPLETED']) {
  test('idle release ignores inert ' + state + ' metadata without erasing historical HTTP files', async () => {
    const f = ownedFixture(); await f.existing(); await f.stop(); await f.clean();
    const marker = {...f.body,key:keyFor(f.body),state,retired:true,control_epoch:2};
    f.controller.repository.data.retired[marker.key]=marker;
    const historicalControl = { ...f.control, state: 'RUNNING', control_epoch: 1 };
    f.controller.repository.data.controls[f.body.task_id] = historicalControl;
    const files = { dispatch: { ...f.body, updated_at_ms: Date.now(), server_status: 'RUNNING' }, control: historicalControl };
    await f.store.patch({ httpIngress: { files, signals: {} } });
    assert.equal(f.lifecycleGuards.canRelease(), true);
    assert.deepEqual(f.store.snapshot().httpIngress.files, files);
  });
}

for (const state of ['COMMITTED', 'COMPLETED']) {
  test('consumed ' + state + ' identity remains idle despite old-identity RUNNING refresh and dispatch retry', async () => {
    const f = ownedFixture(); await f.existing(); await f.stop(); await f.clean();
    f.controller.repository.data.retired[keyFor(f.body)]={...f.body,key:keyFor(f.body),state,retired:true,control_epoch:2};
    const historicalControl = { ...f.control, state: 'RUNNING', control_epoch: 20 };
    f.controller.repository.data.controls[f.body.task_id] = historicalControl;
    await f.store.patch({ httpIngress: { files: { control: historicalControl, dispatch: { ...f.body, dispatch_attempt: 20 } } } });
    assert.equal(f.lifecycleGuards.canRelease(), true);
  });
}

for (const kind of ['new-task', 'new-request', 'new-turn', 'new-dispatch-attempt', 'new-control-epoch', 'new-paused-control']) {
  test('idle-home release guard still preserves fresh pending HTTP ' + kind, async () => {
    const f = ownedFixture(); await f.existing(); await f.stop(); await f.clean();
    let pending = { ...f.body };
    if (kind === 'new-task') Object.assign(pending, { task_id: 'PENDING', turn_id: 'PENDING:1', request_id: 'PENDING:req' });
    if (kind === 'new-request') pending.request_id += '-next';
    if (kind === 'new-turn') pending.turn_id += '-next';
    if (kind === 'new-dispatch-attempt') pending.dispatch_attempt += 1;
    if (kind === 'new-control-epoch' || kind === 'new-paused-control') {
      pending = { ...f.control, control_epoch: f.control.control_epoch + 1, state: kind === 'new-paused-control' ? 'PAUSED' : 'RUNNING' };
    }
    await f.store.patch({ httpIngress: { files: { pending }, signals: {} } });
    assert.equal(f.lifecycleGuards.canRelease(), false);
    assert.deepEqual(f.store.snapshot().httpIngress.files.pending, pending);
  });
}

test('idle-home release guard preserves a newer app generation before any request is ingested', async () => {
  const f = ownedFixture(); await f.existing(); await f.stop(); await f.clean();
  f.controller.repository.data.controls[f.body.task_id] = { ...f.control, turn_id: 'NEXT-TURN', request_id: 'NEXT-REQUEST', state: 'RUNNING', control_epoch: 3 };
  assert.equal(f.lifecycleGuards.canRelease(), false);
});

test('successful explicit DONE cleanup releases owned target and discards its terminal evidence', async () => {
  const f=ownedFixture();await f.existing();f.control={...f.control,state:'DONE',control_epoch:2};await f.controller.control(null,f.control,f.transport);await f.clean();
  assert.deepEqual(f.closed,['target-owned']);assert.equal(f.pages.size,0);assert.equal(f.record(),null);
  assert.deepEqual(f.store.snapshot().lifecycle.browserCleanup,{});
  assert.equal(f.store.snapshot().lifecycle.browserCleanupOutcomes?.['target-owned'],undefined);
  f.controller=f.recreate();await f.controller.initialize();assert.equal(f.record(),null);
});

test('explicit DONE discard does not claim tab closure when one disposal fails', async () => {
  const f=ownedFixture();await f.existing();f.closeFails=true;
  f.control={...f.control,state:'DONE',control_epoch:2};await f.controller.control(null,f.control,f.transport);await f.clean();
  assert.equal(f.record(),null);assert.equal(f.pages.has('target-owned'),true);
  assert.equal(f.controller.repository.cleanupEntries().length,0);assert.deepEqual(f.store.snapshot().lifecycle.browserCleanup,{});
  f.closeFails=false;await f.clean();
  assert.deepEqual(f.closed,[]);assert.equal(f.pages.size,1);
});

test('already absent owned target clears cleanup without reporting an observed close', async () => {
  const f=ownedFixture();await f.existing();f.pages.delete('target-owned');await f.stop();await f.clean();
  assert.equal(f.record(),null);assert.deepEqual(f.closed,[]);assert.equal(f.owned.size,0);
  assert.deepEqual(f.store.snapshot().lifecycle.browserCleanup,{});
});

test('terminal cleanup exception leaves the tab untouched and no future execution', async () => {
  const f=ownedFixture();await f.existing();let attempts=0;
  f.beforeClose=async()=>{attempts++;throw new Error('test CDP outage');};
  await f.stop();await f.clean();await f.clean();
  assert.equal(f.record(),null);assert.equal(attempts,1);assert.equal(f.pages.has('target-owned'),true);
  assert.equal(f.controller.repository.cleanupEntries().length,0);assert.deepEqual(f.store.snapshot().lifecycle.browserCleanup,{});
});

test('repurposed physical page is preserved without retaining ended-task cleanup', async () => {
  const f=ownedFixture();await f.existing();f.targetUrl='https://example.test/manual-browsing';
  await f.stop();await f.clean();
  assert.equal(f.record(),null);assert.equal(f.controller.repository.cleanupEntries().length,0);
  assert.deepEqual(f.closed,[]);
  assert.equal(f.pages.has('target-owned'),true);
});

test('idle standby node performs orphan retirement without admitting new tasks',async()=>{
  const f=ownedFixture();await f.controller.initialize();f.controller.canDispatch=()=>false;
  await f.controller.tick(f.transport);assert.equal(f.controller.standby,true);assert.equal(f.releases,1);
});

for(const state of ['STOPPED','DONE'])test(state+' closes its exact owned tab even while new dispatch is unavailable',async()=>{
 const f=ownedFixture();await f.existing();f.controller.canDispatch=()=>false;
 f.control={...f.control,state,control_epoch:2};await f.controller.control(null,f.control,f.transport);await f.clean();
 assert.equal(f.owned.size,0);assert.deepEqual(f.closed,['target-owned']);assert.equal(f.controller.repository.resourceCleanups.size,0);
 assert.equal(f.sends,0);assert.equal(f.controller.sessions.size,0);
});

test('failed physical close attempts owner disposal once and cannot retain future cleanup',async()=>{
 const f=ownedFixture();await f.existing();f.closeFails=true;let disposals=0;
 f.browser.chromium.discardOwnedTarget=async()=>{disposals++;throw Error('synthetic disposal storage failure');};
 await f.stop();await f.clean();await f.clean();
 assert.equal(disposals,1);assert.equal(f.controller.repository.resourceCleanups.size,0);assert.equal(f.pages.size,1);
 f.closeFails=false;await f.clean();assert.equal(f.pages.size,1);assert.equal(f.controller.repository.cleanupEntries().length,0);assert.equal(disposals,1);
});
