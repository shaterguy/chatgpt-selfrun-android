import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred, MemoryStore } from './helpers/lifecycle-fixture.mjs';
import { keyFor, digest } from '../src/lifecycle.mjs';
import { queueTargetCleanup } from '../src/retired-requests.mjs';

function disposalFixture() {
  const f=fixture();f.pages=new Map();f.owned=new Set();f.closed=[];f.uiStops=0;
  f.firstKey=keyFor(f.body);f.firstBody=structuredClone(f.body);f.firstControl=structuredClone(f.control);
  f.browser.chromium={
    setLifecycleGuards:guards=>{f.guards=guards;},
    isOwnedTarget:id=>f.owned.has(id),
    closeOwnedTarget:async(id,{guard})=>{
      await f.beforeClose?.(id);
      const target=f.pages.get(id);
      if(!target)return {absent:true};
      if(!guard(target))return {closed:false,reason:'guard'};
      if(!f.owned.has(id)||f.closeFails)return {closed:false,reason:'unavailable'};
      f.pages.delete(id);f.owned.delete(id);f.closed.push(id);return {closed:true};
    },
    releaseIfUnused:async()=>({released:false}),
  };
  f.attach=async(args,id,url)=>{
    const target={id,url};f.pages.set(id,target);if(id!=='old-owned'||!f.manual)f.owned.add(id);
    await args.onTarget?.(target);
    return {target,session:{close(){}},baseline:{...f.probe,url}};
  };
  f.browser.resume=async args=>{f.resumes++;return f.attach(args,'old-owned',args.conversationUrl);};
  f.browser.prepare=async args=>{f.prepares++;return f.attach(args,'new-owned',args.projectUrl);};
  f.browser.quiesce=async()=>{f.uiStops++;assert.fail('resource disposal cannot stop remote generation');};
  f.controller=f.recreate();
  f.nextBody={...f.body,turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',
    result_document_id:'RESULT-2',conversation_url:'',client_status:'CREATE_REQUESTED',server_status:'PENDING',server_control_epoch:2};
  f.nextKey=keyFor(f.nextBody);f.nextControl={...f.control,turn_id:f.nextBody.turn_id,request_id:f.nextBody.request_id,control_epoch:2};
  f.result(false,'RESULT-2',2);
  f.next=async()=>{
    f.control={...f.nextControl};await f.controller.control(null,f.control,f.transport);
    f.rows.set('next.json',structuredClone(f.nextBody));await f.controller.ingest('next.json',f.nextBody,f.transport);
  };
  f.clean=()=>f.controller.cleanupTerminalResources();
  return f;
}

for(const result of ['false','missing','read failure'])test('next-turn entry disposes prior owned observer independently of Result '+result,async t=>{
  const f=disposalFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  const observer=f.observations.at(-1),raw=f.docs.get('RESULT-1');
  if(result==='missing')f.docs.delete('RESULT-1');
  if(result==='read failure'){
    const read=f.transport.readGoogleDocText;f.transport.readGoogleDocText=async id=>{if(id==='RESULT-1')throw Error('synthetic unavailable');return read(id);};
  }
  await f.next();await f.clean();await f.settle();
  assert.equal(observer.signal.aborted,true);assert.equal(f.controller.sessions.has(f.firstKey),false);
  assert.deepEqual(f.closed,['old-owned']);assert.equal(f.pages.has('new-owned'),true);
  assert.equal(f.controller.repository.get(f.firstKey).state,'PAUSED');assert.equal(f.controller.repository.get(f.firstKey).retired,undefined);
  assert.notEqual(f.controller.repository.get(f.firstKey).result_state,'COMMITTED');
  assert.equal(f.controller.repository.get(f.nextKey).state,'PREPARED');assert.equal(f.sends,0);assert.equal(f.uiStops,0);
  if(result!=='missing')assert.equal(f.docs.get('RESULT-1'),raw);
});

test('a pending old Result read cannot delay next-turn exact owned-tab disposal',async t=>{
  const f=disposalFixture(),readGate=deferred();t.after(()=>{readGate.resolve();return f.controller.quiesceForStandby();});
  await f.existing();const read=f.transport.readGoogleDocText;let reading=false;
  f.transport.readGoogleDocText=async id=>{if(id==='RESULT-1'){reading=true;await readGate.promise;}return read(id);};
  await f.next();await f.clean();assert.equal(reading,true);
  assert.deepEqual(f.closed,['old-owned']);assert.equal(f.pages.has('new-owned'),true);
  assert.equal(f.controller.repository.get(f.firstKey).state,'PAUSED');assert.equal(f.sends,0);assert.equal(f.uiStops,0);
  readGate.resolve();await Promise.all([...f.controller.resultRetirements.values()]);
});

test('failed displaced cleanup retains exact source generations and retries only that tab after restart',async t=>{
  const f=disposalFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  const source=f.controller.repository.get(f.firstKey);f.closeFails=true;await f.next();await f.clean();
  const entry=f.controller.repository.data.browserCleanup['old-owned'];
  assert.ok(entry,'failed displaced close must retain a cleanup entry');
  assert.equal(entry.kind,'DISPLACED');assert.equal(entry.browser_generation,source.browser_generation);
  assert.equal(entry.run_generation,source.run_generation);assert.equal(entry.successor_key,f.nextKey);
  for(const field of ['prompt','intent','body','conversation_url'])assert.equal(entry[field],undefined);
  const counts={resumes:f.resumes,prepares:f.prepares};await f.controller.quiesceForStandby();
  f.closeFails=false;f.controller=f.recreate();await f.controller.initialize();await f.clean();
  assert.deepEqual(f.closed,['old-owned']);assert.equal(f.pages.has('new-owned'),true);
  assert.deepEqual({resumes:f.resumes,prepares:f.prepares},counts);assert.equal(f.sends,0);assert.equal(f.uiStops,0);
  assert.equal(f.controller.repository.get(f.firstKey).state,'PAUSED');
});

for(const state of ['RUNNING','PAUSED'])test('same-identity '+state+' control does not dispose its observer tab',async t=>{
  const f=disposalFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  f.control={...f.control,state,control_epoch:2};await f.controller.control(null,f.control,f.transport);await f.clean();
  assert.deepEqual(f.closed,[]);assert.equal(f.pages.has('old-owned'),true);assert.equal(f.uiStops,0);
});

for(const boundary of ['manual ownership','repurposed URL'])test('displaced cleanup protects '+boundary,async t=>{
  const f=disposalFixture();t.after(()=>f.controller.quiesceForStandby());f.manual=boundary==='manual ownership';await f.existing();
  if(boundary==='repurposed URL')f.pages.get('old-owned').url='https://example.test/user-page';
  await f.next();await f.clean();assert.deepEqual(f.closed,[]);
  assert.equal(f.pages.has('old-owned'),true);assert.equal(f.pages.has('new-owned'),true);assert.equal(f.uiStops,0);
});

test('historical HTTP cache cannot veto disposal but a pending explicit old-request resume can',async t=>{
  for(const pending of [false,true]){
    const f=disposalFixture(),entered=deferred(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
    await f.existing();
    await f.store.patch({httpIngress:{files:{oldDispatch:structuredClone(f.rows.get(f.path)),oldControl:f.firstControl},signals:{}}});
    f.beforeClose=async id=>{if(id==='old-owned'){entered.resolve();await gate.promise;}};
    await f.next();assert.ok(f.controller.repository.data.browserCleanup['old-owned'],'displaced close must be queued');await entered.promise;
    if(pending)await f.store.patch({httpIngress:{files:{resume:{...f.firstControl,control_epoch:3,conversation_url:f.probe.url}},signals:{}}});
    gate.resolve();await f.clean();assert.deepEqual(f.closed,pending?[]:['old-owned']);assert.equal(f.pages.has('new-owned'),true);
  }
});

for(const boundary of ['reactivation','browser generation','successor target claim','availability','standby'])test('queued displaced close is fenced by '+boundary,async t=>{
  const f=disposalFixture(),entered=deferred(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
  await f.existing();f.beforeClose=async id=>{if(id==='old-owned'){entered.resolve();await gate.promise;}};
  await f.next();assert.ok(f.controller.repository.data.browserCleanup['old-owned'],'displaced close must be queued');await entered.promise;
  if(boundary==='reactivation'){
    f.control={...f.firstControl,control_epoch:3,conversation_url:f.probe.url};await f.controller.control(null,f.control,f.transport);
  } else if(boundary==='browser generation'){
    const source=f.controller.repository.get(f.firstKey);
    await f.controller.repository.move(f.firstKey,'PAUSED','test replacement generation',{browser_generation:source.browser_generation+1});
  } else if(boundary==='successor target claim')await f.controller.repository.move(f.nextKey,'PREPARED','test claimed old target',{target_id:'old-owned'});
  else if(boundary==='availability')f.controller.canDispatch=()=>false;
  else await f.controller.quiesceForStandby();
  gate.resolve();await f.clean();await f.controller.browserCleanup.running;
  assert.deepEqual(f.closed,[]);assert.equal(f.pages.has('new-owned'),true);assert.equal(f.uiStops,0);
});

for(const refresh of [false,true])test('late prior target acquisition after successor control '+(refresh?'and metadata refresh ':'')+'disposes only its original generation',async t=>{
  const f=disposalFixture(),opening=deferred(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
  f.browser.resume=async args=>{f.resumes++;opening.resolve();await gate.promise;
    const attached=await f.attach(args,'old-owned',args.conversationUrl);
    if(args.signal.aborted)throw Error('synthetic stale entry');return attached;
  };
  const first=f.existing();await opening.promise;
  if(refresh){
    f.control=f.nextControl;await f.controller.control(null,f.control,f.transport);
    const original=f.controller.repository.get(f.firstKey).browser_cleanup_displacement;
    f.nextControl={...f.nextControl,control_epoch:3};f.control=f.nextControl;
    await f.controller.control(null,f.control,f.transport);
    const retained=f.controller.repository.get(f.firstKey).browser_cleanup_displacement;
    assert.equal(retained.run_generation,original.run_generation);assert.equal(retained.browser_generation,original.browser_generation);
  }
  await f.next();gate.resolve();await first;await f.clean();
  assert.deepEqual(f.closed,['old-owned']);assert.equal(f.pages.has('new-owned'),true);
  assert.equal(f.controller.repository.get(f.firstKey).state,'PAUSED');assert.equal(f.controller.repository.get(f.nextKey).state,'PREPARED');
  assert.equal(f.resumes,1);assert.equal(f.sends,0);assert.equal(f.uiStops,0);
});

test('next-turn disposal leaves a different task and its owned browser tab unchanged',async t=>{
  const f=disposalFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  const body={...f.firstBody,task_id:'OTHER',turn_id:'OTHER:1',request_id:'OTHER:request',
    conversation_url:'https://chatgpt.com/c/unrelated-task',server_status:'STARTED'};
  f.rows.set('other.json',structuredClone(body));
  await f.controller.repository.ingest('other.json',body);
  await f.controller.repository.move(keyFor(body),'ATTACHING','test independent owner',{target_id:'other-owned',target_owned:true});
  f.pages.set('other-owned',{id:'other-owned',url:body.conversation_url});f.owned.add('other-owned');
  const before=f.controller.repository.get(keyFor(body));await f.next();await f.clean();
  assert.deepEqual(f.closed,['old-owned']);assert.equal(f.pages.has('other-owned'),true);assert.equal(f.pages.has('new-owned'),true);
  assert.deepEqual(f.controller.repository.get(keyFor(body)),before);assert.equal(f.uiStops,0);
});

test('a later known different-request RESUME preserves the already authorized original disposal',async t=>{
  const f=disposalFixture(),entered=deferred(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
  await f.existing();
  const historical={...f.firstBody,turn_id:'SAFE-TEST:history',request_id:'SAFE-TEST:history-request',
    conversation_url:'https://chatgpt.com/c/historical-owned',server_status:'STARTED'};
  f.rows.set('historical.json',historical);await f.controller.repository.ingest('historical.json',historical);
  f.beforeClose=async id=>{if(id==='old-owned'){entered.resolve();await gate.promise;}};
  await f.next();await entered.promise;
  f.control={...f.firstControl,turn_id:historical.turn_id,request_id:historical.request_id,control_epoch:3,conversation_url:historical.conversation_url};
  await f.controller.control(null,f.control,f.transport);gate.resolve();await f.clean();
  assert.deepEqual(f.closed,['old-owned']);assert.equal(f.pages.has('new-owned'),true);assert.equal(f.uiStops,0);
});

for(const restart of [false,true])test('confirmed displaced disposal stays inert on metadata refresh'+(restart?' after restart':''),async t=>{
  const f=disposalFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.next();await f.clean();
  assert.deepEqual(f.closed,['old-owned']);assert.deepEqual(f.controller.repository.data.browserCleanup,{});
  if(restart){await f.controller.quiesceForStandby();f.controller=f.recreate();await f.controller.initialize();}
  const counts={resumes:f.resumes,prepares:f.prepares};f.control={...f.nextControl,control_epoch:3};
  await f.controller.control(null,f.control,f.transport);
  assert.deepEqual(f.controller.repository.data.browserCleanup,{},'confirmed exact generation must not be queued again');
  assert.deepEqual({resumes:f.resumes,prepares:f.prepares},counts);assert.equal(f.pages.has('new-owned'),true);
  assert.equal(f.controller.repository.get(f.firstKey).state,'PAUSED');assert.equal(f.sends,0);assert.equal(f.uiStops,0);
});

for(const status of ['CLOSED','ABSENT'])test('a real later Result commit reuses exact displaced '+status+' evidence',async t=>{
  let now=1000000;t.mock.method(Date,'now',()=>now);
  const f=disposalFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  if(status==='ABSENT')f.beforeClose=async id=>{if(id==='old-owned'){f.pages.delete(id);f.owned.delete(id);}};
  await f.next();await f.clean();
  await Promise.all([...f.controller.resultRetirements.values()]);
  const successful=structuredClone(f.controller.repository.data.browserCleanupOutcomes['old-owned']);
  assert.equal(successful.status,status);assert.equal(f.controller.repository.get(f.firstKey).state,'PAUSED');
  const close=f.browser.chromium.closeOwnedTarget;
  f.browser.chromium.closeOwnedTarget=async(id,options)=>!f.owned.has(id)?{closed:false,reason:'unowned_target'}:close(id,options);
  f.result(true);now=f.controller.repository.get(f.firstKey).result_retirement_retry_at+1;
  await f.controller.tick(f.transport);await Promise.all([...f.controller.resultRetirements.values()]);await f.clean();
  const committed=f.controller.repository.get(f.firstKey);
  assert.equal(committed.state,'COMMITTED');assert.equal(committed.retired,true);
  assert.deepEqual(f.controller.repository.data.browserCleanup,{},'real business retirement must not reopen disposed resource cleanup');
  assert.deepEqual(committed.browser_cleanup,successful);
  assert.deepEqual(f.controller.repository.data.browserCleanupOutcomes['old-owned'],successful);
  assert.deepEqual(f.closed,status==='CLOSED'?['old-owned']:[]);assert.equal(f.pages.has('new-owned'),true);assert.equal(f.sends,0);assert.equal(f.uiStops,0);
});

for(const changed of ['target','browser generation','resource run generation','source identity'])test('confirmed old disposal cannot be inherited by a different '+changed,async t=>{
  const f=disposalFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();await f.next();await f.clean();
  const data=structuredClone(f.controller.repository.data),record=f.controller.repository.get(f.firstKey);
  if(changed==='target')record.browser_cleanup_displacement.target_id=record.target_id='replacement-owned';
  else if(changed==='browser generation')record.browser_cleanup_displacement.browser_generation=++record.browser_generation;
  else if(changed==='resource run generation')record.browser_cleanup_displacement.run_generation++;
  else record.key=keyFor({...record,request_id:'replacement-request'});
  queueTargetCleanup(data,record);
  assert.ok(data.browserCleanup[record.target_id],'new exact resource still needs its own cleanup evidence');
  assert.equal(data.browserCleanup[record.target_id].key,record.key);
});

test('two fresh successor controls preserve the first original generation for a late owned target',async t=>{
  const f=disposalFixture(),opening=deferred(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
  f.browser.resume=async args=>{f.resumes++;opening.resolve();await gate.promise;
    const attached=await f.attach(args,'old-owned',args.conversationUrl);
    if(args.signal.aborted)throw Error('synthetic stale entry');return attached;
  };
  const first=f.existing();await opening.promise;
  f.control=f.nextControl;await f.controller.control(null,f.control,f.transport);
  const original=f.controller.repository.get(f.firstKey).browser_cleanup_displacement;
  const latest={...f.nextBody,turn_id:'SAFE-TEST:turn:3',request_id:'SAFE-TEST:turn:3-request',server_control_epoch:3};
  f.control={...f.nextControl,turn_id:latest.turn_id,request_id:latest.request_id,control_epoch:3};
  await f.controller.control(null,f.control,f.transport);
  const retained=f.controller.repository.get(f.firstKey).browser_cleanup_displacement;
  assert.deepEqual(retained,original,'later controls must preserve original resource generation and entry provenance');
  f.rows.set('latest.json',latest);await f.controller.ingest('latest.json',latest,f.transport);
  gate.resolve();await first;await f.clean();
  assert.deepEqual(f.closed,['old-owned']);assert.equal(f.pages.has('new-owned'),true);assert.equal(f.sends,0);assert.equal(f.uiStops,0);
});

test('cold Drive projection cannot grant displaced disposal on a same-successor metadata refresh',async t=>{
  const f=disposalFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  const body=structuredClone(f.rows.get(f.path)),source=body.lifecycle_record;
  Object.assign(source,{state:'PAUSED',control_epoch:2,control_state:'RUNNING',browser_cleanup_displacement:{
    target_id:'old-owned',browser_generation:source.browser_generation,run_generation:source.run_generation,
    control_epoch:2,successor_key:f.nextKey,
  }});
  Object.assign(body,{lifecycle_state:'PAUSED',server_control_epoch:2,server_control_state:'RUNNING'});
  await f.controller.quiesceForStandby();f.store=new MemoryStore();f.controller=f.recreate();
  f.control=f.nextControl;await f.controller.initialize();await f.controller.control(null,f.control,f.transport);
  await f.controller.repository.ingest(f.path,body);
  f.control={...f.nextControl,control_epoch:3};await f.controller.control(null,f.control,f.transport);await f.clean();
  assert.deepEqual(f.closed,[]);assert.equal(f.pages.has('old-owned'),true);assert.equal(f.uiStops,0);
});

for(const terminal of ['STOPPED','COMMITTED'])test('late '+terminal+' acquisition disposes a manager-proven pending owned bootstrap',async t=>{
  const f=disposalFixture(),opening=deferred(),gate=deferred();t.after(()=>{gate.resolve();return f.controller.quiesceForStandby();});
  const bootstrapUrl='http://127.0.0.1:19821/bootstrap/11111111-1111-1111-1111-111111111111';
  f.browser.chromium.isOwnedBootstrapTarget=(target,conversationDigest)=>f.owned.has(target.id)
    &&target.id==='old-owned'&&target.url===bootstrapUrl&&target.bootstrapUrl===bootstrapUrl
    &&target.entryPending===true&&target.entryUrl===f.probe.url&&conversationDigest===digest(f.probe.url);
  f.browser.resume=async args=>{f.resumes++;opening.resolve();await gate.promise;
    const target={id:'old-owned',url:bootstrapUrl,bootstrapUrl,entryUrl:args.conversationUrl,entryPending:true};
    f.pages.set(target.id,target);f.owned.add(target.id);await args.onTarget(target);
    if(args.signal.aborted)throw Error('synthetic retired bootstrap entry');return {target,session:{close(){}},baseline:f.probe};
  };
  const first=f.existing();await opening.promise;
  if(terminal==='STOPPED')await f.stop();
  else {f.result(true);await f.next();await Promise.all([...f.controller.resultRetirements.values()]);}
  assert.equal(f.controller.repository.get(f.firstKey).state,terminal);
  gate.resolve();await first;await f.clean();
  assert.deepEqual(f.closed,['old-owned']);assert.deepEqual(f.controller.repository.data.browserCleanup,{});
  if(terminal==='COMMITTED')assert.equal(f.pages.has('new-owned'),true);
  assert.equal(f.sends,0);assert.equal(f.uiStops,0);
});

for(const changed of ['missing manager proof','wrong entry conversation','repurposed URL','already-entered document'])test('bootstrap cleanup exemption protects '+changed,async t=>{
  const f=disposalFixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  const bootstrapUrl='http://127.0.0.1:19821/bootstrap/11111111-1111-1111-1111-111111111111';
  const target={id:'old-owned',url:bootstrapUrl,bootstrapUrl,entryUrl:f.probe.url,entryPending:true};
  if(changed==='wrong entry conversation')target.entryUrl='https://chatgpt.com/c/different-conversation';
  if(changed==='repurposed URL')target.url='https://example.test/manual-page';
  if(changed==='already-entered document')target.entryPending=false;
  if(changed!=='missing manager proof')f.browser.chromium.isOwnedBootstrapTarget=(candidate,conversationDigest)=>candidate.url===bootstrapUrl
    &&candidate.bootstrapUrl===bootstrapUrl&&candidate.entryPending===true&&digest(candidate.entryUrl)===conversationDigest;
  f.pages.set(target.id,target);await f.stop();await f.clean();
  assert.deepEqual(f.closed,[]);assert.equal(f.pages.has('old-owned'),true);assert.equal(f.uiStops,0);
});
