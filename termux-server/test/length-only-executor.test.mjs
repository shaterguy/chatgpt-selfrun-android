import test from 'node:test';
import assert from 'node:assert/strict';
import {DriveDispatchController} from '../src/drive-controller.mjs';
import {keyFor} from '../src/lifecycle.mjs';

const url='https://chatgpt.com/c/test-conversation';
const tick=()=>new Promise(resolve=>setImmediate(resolve));
export function fixture(){
  let data={};const files=new Map(),owned=new Set(),events=[],callbacks=new Map();
  const store={snapshot:()=>structuredClone(data),patch:async p=>{data={...data,...structuredClone(p)};},
    update:async fn=>{data=fn(structuredClone(data));},recordEvent:async()=>{}};
  const chromium={isOwnedTarget:id=>owned.has(id),setLifecycleGuards:()=>{},
    closeOwnedTarget:async id=>{events.push('close:'+id);owned.delete(id);return {closed:true};},
    listExistingTargets:async()=>[...owned].map(id=>({id,type:'page',url}))};
  let sequence=0;
  const browser={chromium,prepare:async o=>{const target={id:'tab'+(++sequence)};owned.add(target.id);events.push('open:'+target.id);await o.onTarget(target);return {target,session:{close(){events.push('detach:'+target.id);}},baseline:{}};},
    submitIntent:async o=>{events.push('submit:'+o.kind);await o.onSubmitted?.();await o.onConversation?.({url});return {url};},
    monitor:async o=>{callbacks.set(o.session,o);await new Promise(resolve=>o.signal.addEventListener('abort',resolve,{once:true}));},
    ownedConversationUrl:async()=>'',readSubmission:async()=>({state:'UNKNOWN'}),recoveryInputSnapshot:async()=>({url,composer:true})};
  const transport={read:async p=>{if(p==='result')return JSON.stringify({committed:true});return structuredClone(files.get(p));},write:async(p,b)=>files.set(p,structuredClone(b)),purgeStopped:async()=>{},list:async()=>[],readGoogleDocText:async()=>JSON.stringify({schema:'selfrun-turn-result-v3',task_id:'task',turn_id:'task:turn:1',turn:1,document_id:'result',event_id:'task:turn:1:result',committed:false})};
  const controller=new DriveDispatchController({browser,stateStore:store,config:{recoveryPrompt:'계속 진행해.',stallAfterMs:600000,resumeRetryMs:1,browserTargetIdleMs:3600000}});
  const dispatch=async(turn=1,clientStatus='SEND_REQUESTED')=>{const body={schema:'selfrun-server-dispatch-v1',task_id:'task',turn_id:'task:turn:'+turn,request_id:'task:turn:'+turn+'-request',dispatch_attempt:1,project_url:'https://chatgpt.com/g/project',profile_operations:[],prompt:'prompt',result_document_id:'result',client_status:clientStatus};
    const control={schema:'selfrun-task-control-v1',task_id:body.task_id,turn_id:body.turn_id,request_id:body.request_id,state:'RUNNING',control_epoch:turn};const path='dispatch'+turn;files.set(path,body);files.set('__SELFRUN_CONTROL__task.json',control);await controller.controlDurable(null,control,transport);await controller.ingest(path,body,transport);return {body,control,path,key:keyFor(body)};};
  return {controller,browser,transport,files,owned,events,callbacks,dispatch,store};
}
test('unknown receipt and committed Result never gate the ten-minute continuation',async()=>{
  const f=fixture(),r=await f.dispatch();await tick();
  const old=Date.now()-600001;await f.controller.repository.move(r.key,'OBSERVING','TEST',{last_progress_at:old,last_nudge_attempt_at:0});
  await f.controller.tick(f.transport);await tick();
  assert.deepEqual(f.events.filter(x=>x.startsWith('submit:')),['submit:initial','submit:recovery']);
  assert.equal(f.controller.repository.get(r.key).error,null);
  await f.controller.controlDurable(null,{...r.control,state:'DONE',control_epoch:9},f.transport);
});
test('only character length changes reset the progress clock',async()=>{
  const f=fixture(),r=await f.dispatch();await tick();
  const observe=[...f.callbacks.values()][0].onActivity;
  await observe({pageUrl:url,responseTextLength:12,responseFingerprint:'first',streaming:true});
  const after=f.controller.repository.get(r.key).last_progress_at;
  await observe({pageUrl:url,responseTextLength:12,responseFingerprint:'changed',assistantMessageId:'new-id',streaming:false,paused:true,status:'PAGE_ERROR'});
  assert.equal(f.controller.repository.get(r.key).last_progress_at,after);
  await observe({pageUrl:url,responseTextLength:4,responseFingerprint:'less'});
  assert.equal(f.controller.repository.get(r.key).cursor.responseTextLength,4);
  assert.equal(f.controller.repository.get(r.key).error,null);
  await f.controller.controlDurable(null,{...r.control,state:'DONE',control_epoch:9},f.transport);
});
test('successor waits for physical old tab disposal before fresh creation',async()=>{
  const f=fixture();await f.dispatch();await tick();let release;
  const closed=new Promise(resolve=>release=resolve);
  f.browser.chromium.closeOwnedTarget=async id=>{f.events.push('closing:'+id);await closed;f.owned.delete(id);f.events.push('closed:'+id);return {closed:true};};
  const successor=f.dispatch(2);await tick();await tick();assert.equal(f.events.includes('open:tab2'),false);
  release();const r=await successor;await tick();assert.ok(f.events.indexOf('closed:tab1')<f.events.indexOf('open:tab2'));
  assert.equal(f.owned.size,1);
  await f.controller.controlDurable(null,{...r.control,state:'DONE',control_epoch:9},f.transport);
});


function result(status,committed=true){return JSON.stringify({schema:'selfrun-turn-result-v3',task_id:'task',turn_id:'task:turn:1',turn:1,document_id:'result',event_id:'task:turn:1:result',committed,status});}
async function due(f,key){await f.controller.repository.move(key,'OBSERVING','TEST_DEADLINE',{last_progress_at:Date.now()-600001,last_nudge_attempt_at:0,last_quiet_cycle_at:0,quiet_pending:false});await f.controller.tick(f.transport);await tick();await tick();}
test('WAITING control still checks current Result every quiet cycle and resolved status resumes input',async()=>{
  const f=fixture(),r=await f.dispatch();await tick();let reads=0,status='USER_ACTION_REQUIRED';
  f.transport.readGoogleDocText=async()=>{reads++;return result(status);};
  const waiting={...r.control,state:'WAITING_USER_INTERVENTION',control_epoch:2};f.files.set('__SELFRUN_CONTROL__task.json',waiting);await f.controller.controlDurable(null,waiting,f.transport);
  await due(f,r.key);await due(f,r.key);
  assert.equal(reads,2);assert.equal(f.events.filter(x=>x==='submit:recovery').length,0);assert.equal(f.controller.sessions.size,1);
  status='USER_ACTION_RESOLVED';await due(f,r.key);assert.equal(reads,3);assert.equal(f.events.filter(x=>x==='submit:recovery').length,1);
  await f.controller.controlDurable(null,{...waiting,state:'DONE',control_epoch:9},f.transport);
});
test('document failure retries the read without prompting, blocking observation or inventing an error',async()=>{
  const f=fixture(),r=await f.dispatch();await tick();let reads=0;
  f.transport.readGoogleDocText=async()=>{reads++;throw new Error('network unavailable');};
  await due(f,r.key);assert.equal(reads,1);assert.equal(f.events.filter(x=>x==='submit:recovery').length,0);assert.equal(f.controller.repository.get(r.key).error,null);
  const observe=[...f.callbacks.values()][0].onActivity;await observe({url,responseTextLength:8});assert.equal(f.controller.repository.get(r.key).quiet_pending,false);
  f.transport.readGoogleDocText=async()=>{reads++;return result('CONTINUE');};
  await due(f,r.key);assert.equal(reads,2);assert.equal(f.events.filter(x=>x==='submit:recovery').length,1);
  await f.controller.controlDurable(null,{...r.control,state:'DONE',control_epoch:9},f.transport);
});
test('character change during a pending Result read cancels that stale continuation',async()=>{
  const f=fixture(),r=await f.dispatch();await tick();let resolve;
  f.transport.readGoogleDocText=()=>new Promise(r=>resolve=r);
  await due(f,r.key);assert.equal(typeof resolve,'function');
  await [...f.callbacks.values()][0].onActivity({url,responseTextLength:42});resolve(result('CONTINUE'));await tick();await tick();
  assert.equal(f.events.filter(x=>x==='submit:recovery').length,0);
  await f.controller.controlDurable(null,{...r.control,state:'DONE',control_epoch:9},f.transport);
});
test('STOP while document read is pending aborts it and disposes resources without a later prompt',async()=>{
  const f=fixture(),r=await f.dispatch();await tick();let readSignal,resolve;
  f.transport.readGoogleDocText=(_id,o)=>{readSignal=o.signal;return new Promise(r=>resolve=r);};
  await due(f,r.key);await f.controller.controlDurable(null,{...r.control,state:'STOPPED',control_epoch:9},f.transport);
  assert.equal(readSignal.aborted,true);resolve(result('CONTINUE'));await tick();await tick();
  assert.equal(f.controller.sessions.size,0);assert.equal(f.owned.size,0);assert.equal(f.events.filter(x=>x==='submit:recovery').length,0);
});
test('STOP during opening disposes a late-created target and never submits',async()=>{
  const f=fixture();let release,entered,signal;const started=new Promise(r=>entered=r),gate=new Promise(r=>release=r);
  f.browser.prepare=async o=>{signal=o.signal;entered();await gate;const target={id:'late'};f.owned.add(target.id);await o.onTarget(target);return {target,session:{close(){}},baseline:{}};};
  const creating=f.dispatch();await started;const control=f.files.get('__SELFRUN_CONTROL__task.json');
  await f.controller.controlDurable(null,{...control,state:'STOPPED',control_epoch:9},f.transport);assert.equal(signal.aborted,true);
  release();await creating;await tick();await tick();assert.equal(f.owned.size,0);assert.equal(f.events.filter(x=>x.startsWith('submit:')).length,0);
});
test('repeated polling never submits the initial request again after an uncertain send',async()=>{
  const f=fixture();f.browser.submitIntent=async o=>{f.events.push('submit:'+o.kind);await o.onSubmitted();throw Object.assign(new Error('late receipt unknown'),{released:true});};
  const r=await f.dispatch();for(let i=0;i<4;i++)await f.controller.tick(f.transport);
  assert.equal(f.events.filter(x=>x==='submit:initial').length,1);assert.equal(f.controller.repository.get(r.key).error,null);
  await f.controller.controlDurable(null,{...r.control,state:'DONE',control_epoch:9},f.transport);
});
test('same-millisecond length changes invalidate an in-flight document check',async()=>{
  const original=Date.now;Date.now=()=>2000000;
  try {
    const f=fixture(),r=await f.dispatch();await tick();let resolve;
    f.transport.readGoogleDocText=()=>new Promise(r=>resolve=r);
    await f.controller.repository.move(r.key,'OBSERVING','TEST',{last_progress_at:1000000,cursor:{responseTextLength:10},last_nudge_attempt_at:0});
    await f.controller.tick(f.transport);await tick();
    await f.controller.repository.move(r.key,'OBSERVING','CLOCK_SAME',{last_progress_at:1000000,cursor:{responseTextLength:11},quiet_pending:false});
    resolve(result('CONTINUE'));await tick();await tick();assert.equal(f.events.filter(x=>x==='submit:recovery').length,0);
    await f.controller.controlDurable(null,{...r.control,state:'DONE',control_epoch:9},f.transport);
  } finally {Date.now=original;}
});
test('exact ten-minute boundary and subsequent quiet windows are repeatable',async()=>{
  const original=Date.now;let now=3000000;Date.now=()=>now;
  try {
    const f=fixture(),r=await f.dispatch();await tick();let reads=0;f.transport.readGoogleDocText=async()=>{reads++;return result('CONTINUE');};
    now+=599999;await f.controller.tick(f.transport);await tick();assert.equal(reads,0);
    now++;await f.controller.tick(f.transport);await tick();await tick();assert.equal(reads,1);
    now+=600000;await f.controller.tick(f.transport);await tick();await tick();assert.equal(reads,2);assert.equal(f.events.filter(x=>x==='submit:recovery').length,2);
    await f.controller.controlDurable(null,{...r.control,state:'DONE',control_epoch:9},f.transport);
  } finally {Date.now=original;}
});
test('a new count arriving during profile/input preparation cancels the recovery click immediately',async()=>{
  const f=fixture(),r=await f.dispatch();await tick();const observe=[...f.callbacks.values()][0].onActivity;
  f.browser.submitIntent=async o=>{
    assert.equal(o.kind,'recovery');assert.equal(o.guard(),true);
    void observe({url,responseTextLength:111});
    assert.equal(o.guard(),false);
    throw new Error('stale submission');
  };
  await due(f,r.key);await tick();await tick();
  assert.equal(f.events.filter(x=>x==='submit:recovery').length,0);assert.equal(f.controller.repository.get(r.key).error,null);
  assert.equal(f.controller.repository.get(r.key).cursor.responseTextLength,111);
  await f.controller.controlDurable(null,{...r.control,state:'DONE',control_epoch:9},f.transport);
});

test('CREATE_REQUESTED runs the same complete flow and the later SEND request never repeats the prompt',async()=>{
  const f=fixture(),r=await f.dispatch(1,'CREATE_REQUESTED');await tick();
  assert.equal(f.events.filter(x=>x==='submit:initial').length,1);
  const next={...f.files.get(r.path),client_status:'SEND_REQUESTED'};f.files.set(r.path,next);
  await f.controller.ingest(r.path,next,f.transport);await f.controller.tick(f.transport);
  assert.equal(f.events.filter(x=>x==='submit:initial').length,1);assert.equal(f.owned.size,1);
  await f.controller.controlDurable(null,{...r.control,state:'DONE',control_epoch:9},f.transport);
});
test('USER_ACTION_REQUIRED skips the cycle without a committed-value business judgment',async()=>{
  const f=fixture(),r=await f.dispatch();await tick();f.transport.readGoogleDocText=async()=>result('USER_ACTION_REQUIRED',false);
  await due(f,r.key);assert.equal(f.events.filter(x=>x==='submit:recovery').length,0);assert.equal(f.controller.sessions.size,1);
  await f.controller.controlDurable(null,{...r.control,state:'DONE',control_epoch:9},f.transport);
});
