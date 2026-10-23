import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {SignalDispatchTransport} from '../src/signal-transport.mjs';
import {LifecycleStore} from '../src/lifecycle-store.mjs';
import {ChatGptBrowser} from '../src/browser/chatgpt.mjs';
import {keyFor} from '../src/lifecycle.mjs';
import {fixture as lifecycleFixture,MemoryStore} from './helpers/lifecycle-fixture.mjs';
import {monitorRuntimeFixture,wait} from './helpers/monitor-runtime-fixture.mjs';
import {connectedPageRuntime} from './helpers/connected-page-runtime-fixture.mjs';
const TEN_MINUTES=600000;
const appUrl=url=>'https://chatgpt.com/c/'+new URL(url).pathname.split('/c/')[1];
const profileOperations=['model','thinking_effort','conversation_origin','service_tier'].map(path=>({op:'REMOVE',path}));
async function setup(t,{afterAction=null,manualClock=true,composerKind='textarea',deferConversationUrl=false,conversationUrlOnSend=''}={}){
 let now=Date.now();if(manualClock)t.mock.method(Date,'now',()=>now);
 let wire,life;
 // Node runs after hooks in registration order: stop the producer before the
 // browser fixture closes its bridge and removes its journal directory.
 t.after(async()=>{await life?.controller.quiesceForStandby();await Promise.allSettled([...(life?.controller.operations.values()||[])]);await life?.controller.browserCleanup.running;for(const page of wire?.pageRuntimes.values()||[])page.disposeTimers();});
 wire=await monitorRuntimeFixture(t,{pageRuntimeFactory:args=>{const page=connectedPageRuntime({...args,composerKind,deferConversationUrl,conversationUrlOnSend});if(afterAction){const call=page.transport.call.bind(page.transport);page.transport.call=async(method,params)=>{const result=await call(method,params),name=params.expression?.match(/helper\.run\(("[^"]+")/);if(name)await afterAction(JSON.parse(name[1]),page,result);return result;};}return page;}});life=lifecycleFixture();
 life.body.profile_operations=profileOperations;
 const browser=new ChatGptBrowser(wire.manager,{browserClientProfile:'native',navigationTimeoutMs:50,probeIntervalMs:20,stallAfterMs:TEN_MINUTES});
 life.browser=browser;life.controller=life.recreate();life.controller.config.stallAfterMs=TEN_MINUTES;life.controller.config.turnStartDirective='Synthetic turn start directive';
 const samples=[],late=[];const monitor=browser.monitor.bind(browser);
 browser.monitor=args=>{late.push(args);return monitor({...args,onActivity:async(...values)=>{const result=await args.onActivity(...values);samples.push(values[0]);return result;}});};
 const resultReads=[],read=life.transport.readGoogleDocText;
 life.transport.readGoogleDocText=async id=>{resultReads.push(id);return read(id);};
 const writes=[],write=life.transport.write;
 life.transport.write=async(path,body)=>{writes.push({path,body:structuredClone(body)});return write(path,body);};
 const sample=async()=>{const count=samples.length;await wait(()=>samples.length>count,'actual monitor callback').catch(error=>{throw new Error(error.message+': '+JSON.stringify({state:life.record()?.state,error:life.record()?.error,requests:wire.requests.slice(-3).map(r=>({path:r.path,event:r.body.event,error:r.body.error}))}));});await life.settle();};
 const elapse=async ms=>{now+=ms;await sample();};
 const tickAfter=async ms=>{now+=ms;await life.controller.tick(life.transport);await life.settle();};
 const clicks=()=>[...wire.pageRuntimes.values()].reduce((n,p)=>n+p.effects.filter(e=>e.kind==='click').length,0);
 const inputs=()=>[...wire.pageRuntimes.values()].reduce((n,p)=>n+p.effects.filter(e=>e.kind==='input').length,0);
 return{life,wire,browser,samples,late,writes,resultReads,sample,elapse,tickAfter,clicks,inputs};
}
async function start(f){await f.life.ingest();await wait(()=>f.life.record()?.conversation_url&&f.samples.length,'URL delivery and actual monitor');await f.life.settle();}

async function assertAppliedProgress(f){
 await wait(()=>f.life.record()?.cursor&&f.life.record()?.state==='OBSERVING','applied observer cursor');
 assert.doesNotMatch(f.life.record().error?.code||'',/^OBSERVATION_|CONVERSATION_LOAD_FAILED/);
 const before=JSON.stringify(f.life.record().cursor),page=[...f.wire.pageRuntimes.values()].at(-1);
 page.appendResponse('later synthetic response progression');
 await wait(()=>f.life.record()?.state==='OBSERVING'&&JSON.stringify(f.life.record()?.cursor)!==before,'later actual response applied');
 assert.doesNotMatch(f.life.record().error?.code||'',/^OBSERVATION_|CONVERSATION_LOAD_FAILED/);
}

test('current create, navigate, real input, one click, URL publication and real extension monitor are connected',async t=>{
 const f=await setup(t);await start(f);
 const page=f.wire.pageRuntimes.get(1),effects=page.effects;
 assert.equal(f.wire.calls.filter(c=>c[0]==='server'&&c[1]==='Target.createTarget').length,1);
 assert.equal(effects.filter(e=>e.kind==='navigate').length,1);
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);assert.equal(page.requests.length,1);
 assert.ok(effects.findIndex(e=>e.kind==='navigate')<effects.findIndex(e=>e.kind==='input'));
 assert.ok(effects.findIndex(e=>e.kind==='input')<effects.findIndex(e=>e.kind==='click'));
 assert.equal(page.requests[0].messages[0].content.parts[0],f.life.record().intent.prompt);
 assert.notEqual(f.life.record().intent.prompt,f.life.body.prompt,'the actual prepared prompt, including directive, is sent');
 for(const field of ['model','thinking_effort','conversation_origin','service_tier'])assert.equal(page.requests[0][field],'old');
 assert.equal(f.life.rows.get(f.life.path).conversation_url,'https://chatgpt.com/c/owned-1');
 assert.ok(f.wire.calls.some(c=>c[0]==='selfrun.content.snapshot'),'actual controller requested actual generated content probe');
 assert.ok(f.samples.some(s=>s.userCount===1&&s.assistantCount===1));
 assert.equal(f.life.record().intent.sends,1);
 await f.sample();assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);await assertAppliedProgress(f);
 await fs.writeFile(new URL('../evidence/dev31-connected-normal-dispatch.json',import.meta.url),JSON.stringify(f.life.rows.get(f.life.path),null,2)+'\n');
});

test('the connected monitor enforces ten-minute Result and intervention branches, then creates a fresh successor',async t=>{
 const f=await setup(t);await start(f);
 const firstTarget=f.life.record().target_id,firstMonitor=f.late[0],firstPage=f.wire.pageRuntimes.get(1);
 f.life.result(true);
 await f.elapse(TEN_MINUTES-1);assert.equal(f.resultReads.length,0);assert.equal(f.clicks(),1);
 await f.elapse(1);assert.deepEqual(f.resultReads,['RESULT-1']);assert.equal(f.clicks(),1);assert.equal(f.inputs(),1);
 const afterCommit=f.samples.length;await f.sample();assert.ok(f.samples.length>afterCommit);assert.equal(firstMonitor.signal.aborted,false);
 // The next quiet interval re-reads the same Result and permits real continuation.
 f.life.result(false);await f.elapse(TEN_MINUTES);
 await wait(()=>f.clicks()===2&&firstPage.requests.length===2,'real continuation click and request');await f.sample();
 assert.ok(f.life.record().cursor);assert.equal(f.life.record().state,'OBSERVING');
 assert.equal(f.inputs(),2);assert.equal(firstPage.requests[1].messages[0].content.parts[0],f.life.controller.config.recoveryPrompt);
 assert.equal(f.life.record().target_id,firstTarget);
 f.life.control={...f.life.control,state:'WAITING_USER_INTERVENTION',control_epoch:2};
 await f.life.controller.control(null,f.life.control,f.life.transport);
 const beforeWaiting=f.samples.length;await f.elapse(TEN_MINUTES);
 assert.equal(f.clicks(),2);assert.equal(f.inputs(),2);assert.ok(f.samples.length>beforeWaiting);assert.equal(firstMonitor.signal.aborted,false);
 // App selects the next turn. No predecessor Result is a creation/input gate.
 const oldKey=keyFor(f.life.body),oldResult=f.life.docs.get('RESULT-1');
 const next={...f.life.body,turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',result_document_id:'RESULT-2',
  previous_result_document_id:'RESULT-1',conversation_url:'',client_status:'CREATE_REQUESTED',server_status:'PENDING',server_control_epoch:3};
 f.life.control={...f.life.control,state:'RUNNING',turn_id:next.turn_id,request_id:next.request_id,control_epoch:3};
 await f.life.controller.control(null,f.life.control,f.life.transport);
 f.life.body=next;f.life.path='__SELFRUN_DISPATCH__successor.json';f.life.result(false,'RESULT-2',2);f.resultReads.length=0;
 await f.life.ingest();assert.equal(f.life.record().state,'PREPARED');assert.equal(f.clicks(),2);assert.equal(f.inputs(),2);
 assert.notEqual(f.life.record().target_id,firstTarget);
 f.life.body.client_status='SEND_REQUESTED';await f.life.ingest();
 await wait(()=>f.life.record()?.conversation_url==='https://chatgpt.com/c/owned-2','successor URL publication');await f.sample();
 assert.equal(f.clicks(),3);assert.equal(f.inputs(),3);assert.deepEqual(f.resultReads,[]);
 assert.equal(f.life.docs.get('RESULT-1'),oldResult);assert.equal(firstMonitor.signal.aborted,true);assert.equal(f.life.controller.sessions.has(oldKey),false);
 assert.equal(f.wire.pageRuntimes.get(2).requests.length,1);await assertAppliedProgress(f);
 f.life.control={...f.life.control,state:'STOPPED',control_epoch:4};await f.life.controller.control(null,f.life.control,f.life.transport);await f.life.controller.cleanupTerminalResources();
 await firstMonitor.onActivity(f.samples[0]);await f.life.controller.tick(f.life.transport);await f.life.settle();
 assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);assert.equal(f.clicks(),3);assert.equal(f.inputs(),3);
 assert.equal(f.wire.manager.owned.size,0);
});

for(const terminal of ['STOPPED','DONE'])test(terminal+' clears the connected action and observer runtimes and rejects late work',async t=>{
 const f=await setup(t);await start(f);
 const target=f.life.record().target_id,actor=f.wire.manager.actionSessions.get(target),page=f.wire.pageRuntimes.get(1),late=f.late[0];
 const key=actor.installationKey,helper=page.context.window.__selfrunExtensionPage,oldUrl=page.context.location.href;
 const staleBody=structuredClone(f.life.rows.get(f.life.path)),sample=f.samples.at(-1),clicks=f.clicks(),inputs=f.inputs();
 f.life.control={...f.life.control,state:terminal,control_epoch:2};
 await f.life.controller.control(null,f.life.control,f.life.transport);await f.life.controller.cleanupTerminalResources();
 await wait(()=>actor.closed&&!f.wire.manager.isOwnedTarget(target),'terminal action disposal');
 assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);assert.equal(late.signal.aborted,true);
 assert.equal(actor.armed,null);assert.equal(actor.released,null);assert.equal(actor.installationKey,null);
 assert.ok(page.effects.some(e=>e.kind==='observer-disconnected'));
 assert.throws(()=>helper.run('receiptSnapshot',{key:{intent_id:'late',generation:'late'}},'server-action-owner',oldUrl,Date.now()+1000,key),/ownership/);
 await late.onActivity(sample);await assert.rejects(actor.run('input',{prompt:'late'}));
 await f.life.controller.ingest(f.life.path,staleBody,f.life.transport);await f.life.controller.tick(f.life.transport);await f.life.settle();
 assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);assert.equal(f.life.controller.operations.size,0);
 assert.equal(f.clicks(),clicks);assert.equal(f.inputs(),inputs);assert.equal(f.wire.manager.actionSessions.size,0);
});


test('URL appearing just after actual input publishes and monitors without a click or repeated input',async t=>{
 let changed=false;
 const f=await setup(t,{afterAction:async(operation,page)=>{if(operation==='input'&&!changed){changed=true;page.context.window.history.pushState({},'', '/g/test/c/input-transition');}}});
 await start(f);
 assert.equal(changed,true);assert.equal(f.inputs(),1);assert.equal(f.clicks(),0);assert.equal(f.wire.pageRuntimes.get(1).requests.length,0);
 assert.equal(f.life.rows.get(f.life.path).conversation_url,'https://chatgpt.com/c/input-transition');
 assert.ok(f.samples.length>0);assert.notEqual(f.life.record().intent.released,true);assert.equal(f.life.record().intent.sends,0);await assertAppliedProgress(f);
 await f.life.controller.tick(f.life.transport);await f.sample();assert.equal(f.inputs(),1);assert.equal(f.clicks(),0);
});

test('lost response after one actual click publishes and monitors, and CDP reconnection retains the duplicate fence',async t=>{
 let lost=false;
 const f=await setup(t,{afterAction:async(operation,page)=>{if(operation==='submit'&&!lost){lost=true;await wait(()=>page.requests.length===1,'site request completion');throw Error('websocket closed after click');}}});
 await start(f);
 const actor=f.wire.manager.actionSessions.get(f.life.record().target_id),page=f.wire.pageRuntimes.get(1),fence=actor.released;
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);assert.equal(page.requests.length,1);
 assert.equal(f.life.rows.get(f.life.path).conversation_url,'https://chatgpt.com/c/owned-1');assert.ok(f.samples.length>0);assert.ok(fence);
 actor.reconnect=async()=>({...page.transport,socket:{readyState:1}});
 await actor.run('recoveryInput');assert.equal(actor.released,fence);
 await assert.rejects(actor.run('submit',{initialProjectUrl:f.life.body.project_url}),/already have been released/);
 await f.life.controller.tick(f.life.transport);await f.sample();
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);assert.equal(page.requests.length,1);await assertAppliedProgress(f);
});

test('URL arriving after a lost input response and controller restart reaches the same real observer without reinput',async t=>{
 let lost=false;
 const f=await setup(t,{afterAction:async(operation)=>{if(operation==='input'&&!lost){lost=true;throw Error('lost input response');}}});
 await f.life.ingest();assert.equal(f.life.record().reason,'PRE_SUBMIT_RETRY');assert.equal(f.inputs(),1);assert.equal(f.clicks(),0);
 const target=f.life.record().target_id,page=f.wire.pageRuntimes.get(1);
 await f.life.controller.quiesceForStandby();f.life.controller=f.life.recreate();
 page.context.window.history.pushState({},'', '/g/test/c/reconnected-transition');
 await f.life.controller.tick(f.life.transport);
 assert.equal(f.life.rows.get(f.life.path).conversation_url,'https://chatgpt.com/c/reconnected-transition');
 await f.life.controller.tick(f.life.transport);await wait(()=>f.samples.length>0,'same page observer after restart');await f.life.settle();
 assert.equal(f.life.record().target_id,target);assert.equal(f.inputs(),1);assert.equal(f.clicks(),0);
 assert.equal(f.wire.calls.filter(c=>c[0]==='server'&&c[1]==='Target.createTarget').length,1);
 assert.ok(f.wire.calls.some(c=>c[0]==='selfrun.content.snapshot'));await assertAppliedProgress(f);
});


test('reconstruction enters the actual monitor even while initial content snapshots are unavailable',async t=>{
 const f=await setup(t,{manualClock:false});await start(f);
 const page=f.wire.pageRuntimes.get(1),target=f.life.record().target_id,before=f.late.length;
 await f.life.controller.quiesceForStandby();f.life.controller=f.life.recreate();
 const message=page.contentMessage.bind(page);page.contentMessage=async request=>request.kind==='selfrun.content.snapshot'?{ok:false,error:'temporary DOM unavailable'}:message(request);
 await f.life.controller.tick(f.life.transport);
 assert.equal(f.late.length,before+1,'DOM availability belongs to the monitor, not attachment admission');
 assert.equal(f.life.record().target_id,target);assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);
 page.contentMessage=message;
 await f.life.controller.tick(f.life.transport);
 await wait(()=>f.samples.length>1,'observation recovery');
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);
});

test('exact-target attachment returns its session before any DOM snapshot',async t=>{
 const f=await setup(t,{manualClock:false});await start(f);
 const target=f.life.record().target_id,page=f.wire.pageRuntimes.get(1),message=page.contentMessage.bind(page);let snapshots=0;
 page.contentMessage=async request=>{if(request.kind==='selfrun.content.snapshot'){snapshots++;return{ok:false,error:'temporary DOM unavailable'};}return message(request);};
 const attached=await f.browser.attachTarget({targetId:target});
 assert.ok(attached.session);assert.equal(snapshots,0);attached.session.close();
});

for(const failure of ['read-error','unconfirmed'])test('CONTROL '+failure+' preserves actual monitoring while blocking all input',async t=>{
 const f=await setup(t,{manualClock:false});await start(f);
 const read=f.life.transport.read,observer=f.late[0],before=f.samples.length;
 f.life.transport.read=async path=>{if(path.startsWith('__SELFRUN_CONTROL__')){if(failure==='read-error')throw Error('temporary control read failure');return null;}return read(path);};
 await f.life.controller.tick(f.life.transport).catch(()=>{});
 assert.equal(observer.signal.aborted,false,'read-only monitoring must survive an uncertain CONTROL read');
 await wait(()=>f.samples.length>before,'observation after failed CONTROL read');
 const record=f.life.record();await f.life.controller.repository.move(keyFor(f.life.body),record.state,'test quiet interval elapsed',{last_progress_at:Date.now()-TEN_MINUTES});
 await f.life.controller.tick(f.life.transport).catch(()=>{});await f.sample();
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);assert.equal(f.late.length,1);
 f.life.transport.read=read;await f.life.controller.tick(f.life.transport);await f.sample();
 assert.equal(observer.signal.aborted,false);
});


test('observed hidden textarea and visible contenteditable execute selection insertion, one click, URL and monitoring',async t=>{
 const f=await setup(t,{composerKind:'contenteditable'});await start(f);
 const page=f.wire.pageRuntimes.get(1);
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);assert.equal(page.requests.length,1);
 assert.equal(page.effects.find(e=>e.kind==='input').method,'execCommand');
 assert.equal(page.requests[0].messages[0].content.parts[0],f.life.record().intent.prompt);
 assert.equal(f.life.rows.get(f.life.path).conversation_url,'https://chatgpt.com/c/owned-1');assert.ok(f.samples.length>0);
});

for(const legacy of [{server_status:'COMPLETED'},{lifecycle_state:'COMMITTED'},{server_control_epoch:99,server_control_state:'STOPPED'}])test('current CONTROL owns admission despite stale server projection '+JSON.stringify(legacy),async t=>{
 const f=await setup(t);Object.assign(f.life.body,legacy);await f.life.ingest();
 assert.ok(f.life.record(),'current app RUNNING must not be replaced by old server status');
 await wait(()=>f.life.record()?.conversation_url&&f.samples.length,'legacy projection ignored');
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);assert.equal(f.life.record().control_epoch,1);
});


test('an advisory sendReady failure cannot block the actual submit action',async t=>{
 const f=await setup(t,{afterAction:async operation=>{if(operation==='sendReady')throw Error('advisory readiness unavailable');}});
 await f.life.ingest();assert.equal(f.clicks(),1,'the actual send action, not an advisory poll, owns the outcome');
 await wait(()=>f.life.record()?.conversation_url&&f.samples.length,'actual submitted URL and monitor');assert.equal(f.inputs(),1);
});

test('actual missing send button retries only the send action without reinput',async t=>{
 let inputCalls=0;
 const f=await setup(t,{afterAction:async(operation,page)=>{if(operation==='input'){inputCalls++;if(inputCalls>1)throw Error('unexpected repeated input');page.context.document.querySelectorAll('button')[0].offsetParent=null;}}});
 await f.life.ingest();
 assert.equal(f.life.record().reason,'PRE_SUBMIT_RETRY');assert.equal(f.life.record().intent.pre_submit_stage,'submit');
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),0);
 const page=f.wire.pageRuntimes.get(1),actor=f.wire.manager.actionSessions.get(f.life.record().target_id);
 assert.equal(actor.released,null);
 page.context.document.querySelectorAll('button')[0].offsetParent={};
 await f.tickAfter(1000);await wait(()=>f.life.record()?.conversation_url&&f.samples.length,'retried send URL and monitor');
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);assert.equal(page.requests.length,1);
});

// Status words and readiness observations never grant or end read-only monitoring.
test('PAGE_ERROR telemetry keeps the same actual observer sampling and recovers without reattachment',async t=>{
 const f=await setup(t,{manualClock:false});await start(f);
 const page=f.wire.pageRuntimes.get(1),document=page.context.document,query=document.querySelectorAll;
 document.querySelectorAll=()=>[];document.body.innerText='Something went wrong';
 await wait(()=>f.samples.some(s=>s.status==='PAGE_ERROR'),'page error telemetry');
 const count=f.samples.length;await wait(()=>f.samples.length>=count+2,'continued page error sampling');
 assert.equal(f.late.length,1);assert.equal(f.late[0].signal.aborted,false);
 document.querySelectorAll=query;document.body.innerText='';await f.sample();
 assert.equal(f.samples.at(-1).status,'RUNNING');assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);
});

test('a due continuation invokes the real input and send despite stale stop-button telemetry',async t=>{
 const f=await setup(t);await start(f);const page=f.wire.pageRuntimes.get(1),document=page.context.document,query=document.querySelectorAll;
 let stops=0;const stop={isConnected:true,offsetParent:{},dataset:{testid:'stop-button'},getAttribute:()=>null,click(){stops++;}};
 document.querySelectorAll=selector=>selector==='button'?[...query(selector),stop]:query(selector);
 await f.sample();f.life.result(false);await f.elapse(TEN_MINUTES);
 assert.equal(f.clicks(),2);assert.equal(f.inputs(),2);assert.equal(stops,0,'no pre-input stream cleanup effect');assert.equal(f.late[0].signal.aborted,false);
});

test('audit revision disagreement permits owned URL adoption and monitoring after reconstruction without another input',async t=>{
 let lost=false;const f=await setup(t,{afterAction:async operation=>{if(operation==='input'&&!lost){lost=true;throw Error('lost input response');}}});
 await f.life.ingest();const page=f.wire.pageRuntimes.get(1);await f.life.controller.quiesceForStandby();f.life.controller=f.life.recreate();
 page.context.window.history.pushState({},'', '/g/test/c/audit-transition');await f.life.controller.initialize();
 f.life.controller.repository.integrityFaults.add(keyFor(f.life.body));
 await f.life.controller.tick(f.life.transport);await f.life.controller.tick(f.life.transport);
 await wait(()=>f.samples.length>0,'owned URL monitor under audit disagreement');
 assert.equal(f.life.rows.get(f.life.path).conversation_url,'https://chatgpt.com/c/audit-transition');
 f.life.result(false);await f.elapse(TEN_MINUTES);assert.equal(f.inputs(),1);assert.equal(f.clicks(),0);
});

test('pending URL publication cannot hold actual monitor samples, cursor updates or STOP disposal',async t=>{
 let changed=false;const f=await setup(t,{afterAction:async(operation,page)=>{if(operation==='input'&&!changed){changed=true;page.context.window.history.pushState({},'', '/g/test/c/pending-publication');}}});
 let release,entered;const gate=new Promise(r=>release=r),started=new Promise(r=>entered=r),write=f.life.transport.write;
 f.life.transport.write=async(path,body)=>{if(body.conversation_url){entered();await gate;}return write(path,body);};
 const ingest=f.life.ingest();t.after(()=>release());await started;
 await wait(()=>f.samples.length>=2,'two actual samples while Drive write is pending');
 assert.ok(f.life.record().cursor);assert.equal(f.clicks(),0);assert.equal(f.inputs(),1);
 const page=f.wire.pageRuntimes.get(1),actor=f.wire.manager.actionSessions.get(f.life.record().target_id),late=f.late[0];
 f.life.control={...f.life.control,state:'STOPPED',control_epoch:2};await f.life.controller.control(null,f.life.control,f.life.transport);
 assert.equal(late.signal.aborted,true);assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);
 release();await ingest;await f.life.settle();await late.onActivity(f.samples[0]);await f.life.controller.cleanupTerminalResources();
 assert.equal(f.life.rows.get(f.life.path).conversation_url,'https://chatgpt.com/c/pending-publication');
 assert.equal(f.life.record(),null);assert.equal(actor.closed,true);assert.equal(f.inputs(),1);assert.equal(f.clicks(),0);
 assert.ok(page.effects.some(e=>e.kind==='observer-disconnected'));
});

for(const restart of [false,true])test('a continuation with no send button retries that same send action without repeated input, reconstruction='+restart,async t=>{
 let inputs=0;const f=await setup(t,{afterAction:async(operation,page)=>{if(operation==='input'&&++inputs===2)page.context.document.querySelectorAll('button')[0].offsetParent=null;}});
 await start(f);await f.elapse(TEN_MINUTES);
 assert.equal(f.inputs(),2);assert.equal(f.clicks(),1);assert.equal(f.life.record().intent.pre_submit_stage,'submit');
 const id=f.life.record().intent.id;f.wire.pageRuntimes.get(1).context.document.querySelectorAll('button')[0].offsetParent={};
 if(restart){await f.life.controller.quiesceForStandby();f.life.controller=f.life.recreate();}
 await f.tickAfter(1000);await f.sample();assert.equal(f.inputs(),2);assert.equal(f.clicks(),2);assert.equal(f.life.record().intent.id,id);
});

test('pending URL write and pending content probe cannot delay the independent ten-minute tick',async t=>{
 const f=await setup(t);await start(f);
 const page=f.wire.pageRuntimes.get(1),write=f.life.transport.write,message=page.contentMessage.bind(page);
 let releaseWrite,releaseProbe,writeEntered,probeEntered;
 const writeGate=new Promise(r=>releaseWrite=r),probeGate=new Promise(r=>releaseProbe=r),startedWrite=new Promise(r=>writeEntered=r),startedProbe=new Promise(r=>probeEntered=r);
 f.life.transport.write=async(...args)=>{writeEntered();await writeGate;return write(...args);};
 page.contentMessage=async request=>{if(request.kind==='selfrun.content.snapshot'){probeEntered();await probeGate;}return message(request);};
 delete f.life.rows.get(f.life.path).conversation_url;
 await f.life.controller.repository.move(keyFor(f.life.body),'OBSERVING','test pending URL projection',{last_progress_at:Date.now()-TEN_MINUTES});
 const publishing=f.life.controller.repository.flushCurrent(f.life.transport,keyFor(f.life.body));
 await startedWrite;await startedProbe;let ended=false;
 const ticking=f.life.controller.tick(f.life.transport).finally(()=>ended=true);
 try {
  await wait(()=>ended,'independent tick completion with both reads pending');
  assert.equal(f.inputs(),2);assert.equal(f.clicks(),2);assert.equal(page.requests.length,2);
  assert.deepEqual(f.resultReads,['RESULT-1']);
  f.life.control={...f.life.control,state:'STOPPED',control_epoch:2};await f.life.controller.control(null,f.life.control,f.life.transport);
  assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);
 } finally {releaseWrite();releaseProbe();await Promise.all([publishing,ticking]);}
 await f.life.settle();assert.equal(f.life.record(),null);assert.equal(f.inputs(),2);assert.equal(f.clicks(),2);
});

for(const arrival of ['monitor','metadata'])test('late canonical URL after real click and UNKNOWN readback enters observation through '+arrival,async t=>{
 const f=await setup(t,{manualClock:false,deferConversationUrl:true});await f.life.ingest();await f.sample();
 await wait(()=>f.life.record()?.state==='POST_UNCERTAIN'&&f.life.record()?.reason==='POST_READBACK_UNKNOWN','completed UNKNOWN readback');
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);assert.equal(f.life.record().conversation_url,'');
 assert.equal(f.life.record().state,'POST_UNCERTAIN');assert.equal(f.life.record().intent.outcome,'UNKNOWN');
 const page=f.wire.pageRuntimes.get(1),message=page.contentMessage.bind(page);let release,entered;
 const gate=new Promise(r=>release=r),started=new Promise(r=>entered=r);
 try {
  if(arrival==='metadata'){page.contentMessage=async request=>{if(request.kind==='selfrun.content.snapshot'){entered();await gate;}return message(request);};await started;}
  page.context.window.history.pushState({},'', '/g/test/c/owned-1');
  if(arrival==='metadata')await f.life.controller.tick(f.life.transport);else await f.sample();
  assert.equal(f.life.record().conversation_url,'https://chatgpt.com/c/owned-1');
  assert.doesNotMatch(f.life.record().error?.message||'',/invalid lifecycle transition/);
 } finally {release();page.contentMessage=message;}
 await assertAppliedProgress(f);await f.life.controller.flush(f.life.transport);
 assert.equal(f.life.rows.get(f.life.path).conversation_url,'https://chatgpt.com/c/owned-1');
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);
 await f.life.controller.quiesceForStandby();f.life.controller=f.life.recreate();await f.life.controller.tick(f.life.transport);await f.sample();
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);
 f.life.control={...f.life.control,state:'STOPPED',control_epoch:2};await f.life.controller.control(null,f.life.control,f.life.transport);
 assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);
});

for(const boundary of ['STOPPED','next-identity'])test('late URL cannot revive an uncertain old identity after '+boundary,async t=>{
 const f=await setup(t,{manualClock:false,deferConversationUrl:true});await f.life.ingest();await f.sample();
 await wait(()=>f.life.record()?.state==='POST_UNCERTAIN'&&f.life.record()?.reason==='POST_READBACK_UNKNOWN','old UNKNOWN readback');
 const old=f.late[0],oldKey=keyFor(f.life.body),delayed={...f.samples.at(-1),pageUrl:'https://chatgpt.com/g/test/c/late-old'};
 if(boundary==='STOPPED'){
  f.life.control={...f.life.control,state:'STOPPED',control_epoch:2};await f.life.controller.control(null,f.life.control,f.life.transport);
 }else{
  const next={...f.life.body,turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',result_document_id:'RESULT-2',conversation_url:'',client_status:'CREATE_REQUESTED'};
  f.life.control={...f.life.control,state:'RUNNING',turn_id:next.turn_id,request_id:next.request_id,control_epoch:2};await f.life.controller.control(null,f.life.control,f.life.transport);
  f.life.body=next;f.life.path='__SELFRUN_DISPATCH__late-successor.json';await f.life.ingest();assert.equal(f.life.record().state,'PREPARED');
 }
 assert.equal(old.signal.aborted,true);await old.onActivity(delayed);await f.life.controller.tick(f.life.transport);await f.life.settle();
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);
 if(boundary==='STOPPED'){assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);}
 else {assert.equal(f.life.record().conversation_url,'');assert.equal(f.life.record().state,'PREPARED');assert.equal(f.life.controller.sessions.has(oldKey),false);await f.life.stop();assert.equal(f.life.record(),null);}
});

const actualProjectEntry='https://chatgpt.com/g/g-p-6a582c824ba08191ac7e74e9bad721fc/project';
const actualConversation='https://chatgpt.com/g/g-p-6a582c824ba08191ac7e74e9bad721fc-vibe-coding/c/6acb731a-b770-83ec-81e8-f5f4ac3fb1fc';
for(const [label,conversation,newDocument] of [
 ['EXAYJP exact observed pair, SPA',actualConversation,false],
 ['EXAYJP exact observed pair, new document',actualConversation,true],
 ['XZ historical observed URL with synthetic entry','https://chatgpt.com/g/g-p-6a582c824ba08191ac7e74e9bad721fc-vibe-coding/c/6acb5ad5-bc60-83ee-8541-30f96d36e675',false],
 ['different project on the exact owned tab','https://chatgpt.com/g/other-project/c/owned-conversation',false],
 ['root conversation on the exact owned tab','https://chatgpt.com/c/owned-conversation',false],
])test('owned address handoff through monitor and ten-minute continuation: '+label,async t=>{
 let hideSend=true;
 const f=await setup(t,{composerKind:'contenteditable',afterAction:async(operation,page)=>{if(operation==='input'&&hideSend)page.context.document.querySelectorAll('button')[0].offsetParent=null;}});
 f.life.body.project_url=actualProjectEntry;await f.life.ingest();
 assert.equal(f.life.record().state,'POST_PENDING');assert.equal(f.life.record().reason,'PRE_SUBMIT_RETRY');
 assert.equal(f.life.record().intent.pre_submit_stage,'submit');assert.equal(f.life.record().intent.sends,0);assert.equal(f.life.record().intent.released,false);
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),0);const page=f.wire.pageRuntimes.get(1);
 const firstHelper=page.context.window.__selfrunExtensionPage;
 if(newDocument)page.replaceDocument(conversation);else page.context.window.history.pushState({},'',conversation);
 // Independent site observation reproduces userCount=1, empty editor, no
 // assistant, streaming; it does not manufacture a server click or receipt.
 page.observeSubmitted(f.life.record().intent.prompt);
 await f.life.controller.tick(f.life.transport);await f.life.settle();
 const canonical='https://chatgpt.com/c/'+conversation.split('/c/')[1];
 assert.equal(f.life.record().conversation_url,canonical);
 await wait(()=>f.samples.some(sample=>sample.userCount===1&&sample.assistantCount===0&&sample.stopButtonVisible),'actual registered content sample');
 await assertAppliedProgress(f);
 await f.life.controller.flush(f.life.transport);assert.equal(f.life.rows.get(f.life.path).conversation_url,appUrl(conversation));
 assert.equal(f.life.record().intent.sends,0);assert.notEqual(f.life.record().intent.released,true);assert.notEqual(f.life.record().intent.outcome,'CONFIRMED');
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),0);assert.equal(f.wire.calls.filter(c=>c[0]==='server'&&c[1]==='Target.createTarget').length,1);
 if(newDocument)assert.notEqual(page.context.window.__selfrunExtensionPage,firstHelper);
 hideSend=false;page.context.document.querySelectorAll('button')[0].offsetParent={};page.context.document.querySelectorAll('button')[1].offsetParent=null;
 f.life.result(false);await f.tickAfter(TEN_MINUTES);
 await wait(()=>f.clicks()===1&&page.requests.length===1,'continuation on adopted conversation');await f.sample();
 assert.equal(f.inputs(),2);assert.deepEqual(f.resultReads,['RESULT-1']);
 assert.equal(page.requests[0].messages[0].content.parts[0],f.life.controller.config.recoveryPrompt);
 for(const field of ['model','thinking_effort','conversation_origin','service_tier'])assert.equal(page.requests[0][field],'old');
 assert.equal(f.life.record().conversation_url,canonical);assert.equal(f.life.record().state,'OBSERVING');assert.ok(f.life.record().cursor);
 const late=f.late[0],last=f.samples.at(-1),helper=page.context.window.__selfrunExtensionPage,actor=f.wire.manager.actionSessions.get(f.life.record().target_id),key=actor.installationKey;
 await f.life.stop();await f.life.controller.cleanupTerminalResources();await late.onActivity(last);
 assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);assert.equal(f.inputs(),2);assert.equal(f.clicks(),1);
 assert.throws(()=>helper.run('input',{prompt:'stale'},'server-action-owner',conversation,Date.now()+1000,key),/ownership/);
});

test('owned named address is published and sampled while CONTROL input authorization is unavailable',async t=>{
 const f=await setup(t,{afterAction:async(op,page)=>{if(op==='input')page.context.document.querySelectorAll('button')[0].offsetParent=null;}});
 f.life.body.project_url=actualProjectEntry;await f.life.ingest();const page=f.wire.pageRuntimes.get(1);
 page.context.window.history.pushState({},'',actualConversation);page.observeSubmitted(f.life.record().intent.prompt);
 const read=f.life.transport.read;f.life.transport.read=async name=>{if(name.startsWith('__SELFRUN_CONTROL__'))throw Error('control temporarily unavailable');return read(name);};
 await assert.rejects(f.life.controller.tick(f.life.transport),/control temporarily unavailable/);
 await wait(()=>f.life.rows.get(f.life.path).conversation_url===appUrl(actualConversation),'URL despite uncertain input authority');
 await assertAppliedProgress(f);await f.sample();
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),0);assert.equal(f.life.record().intent.sends,0);
 assert.equal([...f.life.controller.sessions.values()][0].inputAuthorized,false);
 f.life.transport.read=read;await f.life.stop();assert.equal(f.life.record(),null);
});

for(const state of ['STOPPED','DONE'])test(state+' during owned address lookup prevents publication, observer registration and input',async t=>{
 const f=await setup(t,{afterAction:async(op,page)=>{if(op==='input')page.context.document.querySelectorAll('button')[0].offsetParent=null;}});
 f.life.body.project_url=actualProjectEntry;await f.life.ingest();const page=f.wire.pageRuntimes.get(1);
 page.context.window.history.pushState({},'',actualConversation);
 const lookup=f.wire.manager.listExistingTargets.bind(f.wire.manager);let stopped=false;
 f.wire.manager.listExistingTargets=async(...args)=>{const rows=await lookup(...args);if(!stopped){stopped=true;f.life.control={...f.life.control,state,control_epoch:2};await f.life.controller.controlDurable(null,f.life.control,f.life.transport);}return rows;};
 await f.life.controller.tick(f.life.transport).catch(error=>assert.match(error.message,/app |stale/));await f.life.settle();await f.life.controller.cleanupTerminalResources();
 assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);
 assert.equal(f.writes.some(w=>!!w.body.conversation_url),false);assert.equal(f.samples.length,0);
 assert.equal(f.wire.calls.filter(c=>c[0]==='selfrun.content.snapshot').length,0);
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),0);
});

test('STOP first received after address observation still purges ownership and cannot turn a late URL write into restart',async t=>{
 const f=await setup(t,{afterAction:async(op,page)=>{if(op==='input')page.context.document.querySelectorAll('button')[0].offsetParent=null;}});
 f.life.body.project_url=actualProjectEntry;await f.life.ingest();const page=f.wire.pageRuntimes.get(1);
 page.context.window.history.pushState({},'',actualConversation);
 // The repository has not seen this newer app command at metadata-read time.
 f.life.control={...f.life.control,state:'STOPPED',control_epoch:2};
 await f.life.controller.tick(f.life.transport);await f.life.settle();await f.life.controller.cleanupTerminalResources();
 assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);assert.equal(f.wire.manager.owned.size,0);
 const oldWrites=f.writes.filter(w=>!!w.body.conversation_url);
 for(const row of oldWrites)await f.life.controller.ingest(row.path,row.body,f.life.transport);
 await f.life.controller.tick(f.life.transport);await f.life.settle();
 assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);assert.equal(f.inputs(),1);assert.equal(f.clicks(),0);
 assert.equal(f.wire.calls.filter(c=>c[0]==='server'&&c[1]==='Target.createTarget').length,1);
});

for(const loseReply of [false,true])test('actual first click creates exact named address, publishes its normalized app link and continues once; lost reply='+loseReply,async t=>{
 let lost=false;
 const f=await setup(t,{conversationUrlOnSend:actualConversation,afterAction:async operation=>{if(loseReply&&operation==='submit'&&!lost){lost=true;throw Error('synthetic lost submit response');}}});
 f.life.body.project_url=actualProjectEntry;await start(f);await f.life.controller.flush(f.life.transport);
 const page=f.wire.pageRuntimes.get(1),target=f.life.record().target_id,actor=f.wire.manager.actionSessions.get(target),firstFence=actor.released;
 assert.equal(page.context.location.href,actualConversation);assert.equal(f.life.record().observed_conversation_url,actualConversation);assert.equal(f.life.rows.get(f.life.path).conversation_url,appUrl(actualConversation));
 if(!loseReply)await fs.writeFile(new URL('../evidence/dev31-connected-dispatch.json',import.meta.url),JSON.stringify(f.life.rows.get(f.life.path),null,2)+'\n');
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);assert.equal(page.requests.length,1);assert.ok(firstFence);
 assert.equal(page.requests[0].messages[0].content.parts[0],f.life.record().intent.prompt);await assertAppliedProgress(f);
 await assert.rejects(actor.run('submit'),/already have been released/);assert.equal(f.clicks(),1);assert.equal(actor.released,firstFence);
 f.life.result(false);await f.tickAfter(TEN_MINUTES);await wait(()=>f.clicks()===2&&page.requests.length===2,'one real continuation');await f.sample();
 assert.equal(f.inputs(),2);assert.equal(f.life.record().target_id,target);assert.equal(f.life.record().state,'OBSERVING');assert.ok(f.life.record().cursor);
 assert.equal(page.requests[1].messages[0].content.parts[0],f.life.controller.config.recoveryPrompt);
 for(const request of page.requests)for(const field of ['model','thinking_effort','conversation_origin','service_tier'])assert.equal(request[field],'old');
 await f.life.stop();await f.life.controller.cleanupTerminalResources();assert.equal(f.life.record(),null);assert.equal(f.inputs(),2);assert.equal(f.clicks(),2);
});

test('a later conversation on the same owned tab is published and monitored without transferring input authority',async t=>{
 const f=await setup(t,{manualClock:false,conversationUrlOnSend:actualConversation});f.life.body.project_url=actualProjectEntry;await start(f);
 const page=f.wire.pageRuntimes.get(1),original=f.life.record().conversation_url,actor=f.wire.manager.actionSessions.get(f.life.record().target_id),late=f.late[0];
 const other='https://chatgpt.com/g/different-project/c/later-owned-tab-address';page.context.window.history.pushState({},'',other);
 await wait(()=>f.life.rows.get(f.life.path).conversation_url===appUrl(other),'updated owned-tab URL publication');
 await assertAppliedProgress(f);assert.equal(late.signal.aborted,false);assert.equal(f.life.record().conversation_url,original);
 assert.equal(f.life.record().observed_conversation_url,other);await f.life.controller.flush(f.life.transport);
 await fs.writeFile(new URL('../evidence/dev31-connected-later-dispatch.json',import.meta.url),JSON.stringify(f.life.rows.get(f.life.path),null,2)+'\n');
 await assert.rejects(actor.run('input',{prompt:'do not transfer'}),/repurposed/);
 // Age only the lifecycle liveness evidence. Advancing global Date also expires
 // an in-flight extension command and does not model elapsed idle time.
 const elapsed=Date.now()-TEN_MINUTES-1;
 assert.ok(Number(f.life.record().last_nudge_attempt_at||0)<=elapsed);
 assert.ok(Number(f.life.record().last_recovery_check_at||0)<=elapsed);
 const resultReadsBefore=f.resultReads.length,attemptStarted=Date.now();
 await f.life.controller.repository.move(keyFor(f.life.body),f.life.record().state,'TEST_LIVENESS_ELAPSED',
  {last_progress_at:elapsed});
 f.life.result(false);await f.life.controller.tick(f.life.transport);await f.life.settle();await f.sample();
 assert.ok(f.resultReads.length>resultReadsBefore);
 assert.ok(f.life.record().last_nudge_attempt_at>=attemptStarted);
 assert.equal(late.signal.aborted,false);assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);assert.equal(f.life.rows.get(f.life.path).conversation_url,appUrl(other));
 const before=JSON.stringify(f.life.record().cursor);page.appendResponse('later address still observed after refused effect');
 await wait(()=>JSON.stringify(f.life.record()?.cursor)!==before,'cursor remains live after refused cross-conversation input');
 await f.life.stop();assert.equal(f.life.record(),null);assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);
});

async function preparedWithoutIntent(f,clientStatus='SEND_REQUESTED') {
 const prepare=f.browser.prepare.bind(f.browser),read=f.life.transport.read;let unavailable=false,onTargetState=null;
 f.life.body.project_url=actualProjectEntry;f.life.body.client_status=clientStatus;
 f.browser.prepare=async args=>{const prepared=await prepare({...args,onTarget:async target=>{await args.onTarget?.(target);onTargetState=f.life.store.snapshot();}});unavailable=true;return prepared;};
 f.life.transport.read=async name=>{if(unavailable&&name.startsWith('__SELFRUN_CONTROL__'))throw Error('CONTROL failed after prepare');return read(name);};
 if(clientStatus==='SEND_REQUESTED')await assert.rejects(f.life.ingest(),/CONTROL failed after prepare/);else await f.life.ingest();
 assert.equal(f.life.record().state,'PREPARED');assert.equal(f.life.record().intent,null);assert.ok(f.life.record().target_id);
 assert.equal(f.inputs(),0);assert.equal(f.clicks(),0);
 return{get checkpoint(){return onTargetState;},restore(){unavailable=false;f.browser.prepare=prepare;f.life.transport.read=read;}};
}

for(const clientStatus of ['SEND_REQUESTED','CREATE_REQUESTED'])test('intent-null owned address is published and monitored under uncertain CONTROL, then respects '+clientStatus,async t=>{
 const f=await setup(t,{composerKind:'contenteditable'}),control=await preparedWithoutIntent(f,clientStatus),page=f.wire.pageRuntimes.get(1);
 page.context.window.history.pushState({},'',actualConversation);page.observeSubmitted('synthetic independently observed site input');
 await assert.rejects(f.life.controller.tick(f.life.transport),/CONTROL failed after prepare/);
 await wait(()=>f.life.rows.get(f.life.path).conversation_url===appUrl(actualConversation),'intent-null exact address publication');
 await assertAppliedProgress(f);await f.sample();
 assert.equal(f.life.record().state,'OBSERVING');assert.equal(f.life.record().intent,null);
 assert.equal(f.late[0].livenessGate().observationGeneration,null);assert.equal(f.inputs(),0);assert.equal(f.clicks(),0);
 control.restore();page.context.document.querySelectorAll('button')[1].offsetParent=null;f.life.result(false);
 await f.tickAfter(TEN_MINUTES);await f.sample();
 if(clientStatus==='CREATE_REQUESTED'){
  assert.equal(f.inputs(),0);assert.equal(f.clicks(),0);assert.equal(f.life.record().intent,null);
  assert.equal(f.life.record().state,'OBSERVING');assert.equal(f.resultReads.length,0,'CREATE alone cannot start recovery work');
  assert.equal(f.life.record().last_nudge_attempt_at??null,null);
  assert.ok(Date.now()-Number(f.life.record().last_progress_at)>=TEN_MINUTES,'the quiet interval already elapsed during read-only CREATE');
  const authorizedAt=Date.now();f.life.body.client_status='SEND_REQUESTED';await f.life.ingest();
  assert.equal(Date.now(),authorizedAt);assert.equal(f.clicks(),1,'SEND opens the already-due continuation immediately');
  assert.equal(f.life.record().intent.kind,'recovery');assert.equal(f.life.record().intent.created_at,authorizedAt);
  assert.equal(f.life.record().last_nudge_attempt_at,authorizedAt);await f.sample();
 }
 await wait(()=>f.clicks()===1&&page.requests.length===1,'one explicitly authorized continuation');
 assert.equal(f.inputs(),1);assert.equal(f.life.record().intent.kind,'recovery');assert.equal(f.life.record().intent.sends,1);
 assert.equal(page.requests[0].messages[0].content.parts[0],f.life.controller.config.recoveryPrompt);
 assert.equal(f.life.record().state,'OBSERVING');assert.ok(f.life.record().cursor);
 const oldMonitor=f.late[0],oldSample=f.samples.at(-1),oldTarget=f.life.record().target_id;
 if(clientStatus==='SEND_REQUESTED'){
  const next={...f.life.body,turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',result_document_id:'RESULT-2',conversation_url:'',client_status:'CREATE_REQUESTED'};
  f.life.control={...f.life.control,turn_id:next.turn_id,request_id:next.request_id,control_epoch:2};await f.life.controller.control(null,f.life.control,f.life.transport);
  f.life.body=next;f.life.path='__SELFRUN_DISPATCH__intent-null-successor.json';f.life.result(false,'RESULT-2',2);await f.life.ingest();
  assert.equal(f.life.record().state,'PREPARED');assert.equal(f.life.record().intent,null);assert.equal(f.life.record().observed_conversation_url,undefined);assert.notEqual(f.life.record().target_id,oldTarget);
  await oldMonitor.onActivity(oldSample);assert.equal(f.life.record().conversation_url,'');assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);
  f.life.body.client_status='SEND_REQUESTED';await f.life.ingest();await wait(()=>f.clicks()===2,'successor first click');await f.sample();assert.equal(f.inputs(),2);
 }
 const inputs=f.inputs(),clicks=f.clicks();await f.life.stop();await f.life.controller.cleanupTerminalResources();await oldMonitor.onActivity(oldSample);await f.life.controller.tick(f.life.transport);
 assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);assert.equal(f.inputs(),inputs);assert.equal(f.clicks(),clicks);
});

for(const state of ['STOPPED','DONE'])test('intent-null address lookup loses authority on '+state+' without any input or late publication',async t=>{
 const f=await setup(t),control=await preparedWithoutIntent(f);f.wire.pageRuntimes.get(1).context.window.history.pushState({},'',actualConversation);
 const lookup=f.wire.manager.listExistingTargets.bind(f.wire.manager);let stopped=false;
 f.wire.manager.listExistingTargets=async(...args)=>{const rows=await lookup(...args);if(!stopped){stopped=true;control.restore();f.life.control={...f.life.control,state,control_epoch:2};await f.life.controller.controlDurable(null,f.life.control,f.life.transport);}return rows;};
 await f.life.controller.tick(f.life.transport).catch(error=>assert.match(error.message,/app |stale/));await f.life.settle();await f.life.controller.cleanupTerminalResources();
 assert.equal(stopped,true);assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);
 assert.equal(f.writes.some(w=>!!w.body.conversation_url),false);assert.equal(f.samples.length,0);assert.equal(f.inputs(),0);assert.equal(f.clicks(),0);
});

for(const phase of ['PREPARED','PREPARING'])test('intent-null '+phase+' restoration reconnects only observation while CONTROL is unavailable',async t=>{
 const f=await setup(t),control=await preparedWithoutIntent(f),page=f.wire.pageRuntimes.get(1),target=f.life.record().target_id;
 const snapshot=phase==='PREPARING'?control.checkpoint:f.life.store.snapshot();
 assert.equal(snapshot.lifecycle.requests[keyFor(f.life.body)].state,phase);
 await f.life.controller.quiesceForStandby();f.life.store=new MemoryStore(snapshot);f.life.controller=f.life.recreate();
 page.context.window.history.pushState({},'',actualConversation);page.observeSubmitted('synthetic site observation after restoration');
 const effects=page.effects.length,calls=f.wire.calls.length;
 await assert.rejects(f.life.controller.tick(f.life.transport),/CONTROL failed after prepare/);
 await wait(()=>f.life.rows.get(f.life.path).conversation_url===appUrl(actualConversation),'restored exact address publication');
 await assertAppliedProgress(f);await f.sample();
 assert.equal(f.life.record().target_id,target);assert.equal(f.life.record().intent,null);assert.equal(f.life.record().state,'OBSERVING');
 assert.equal(f.inputs(),0);assert.equal(f.clicks(),0);
 assert.equal(page.effects.slice(effects).filter(e=>['action','navigate','input','click'].includes(e.kind)).length,0,'observation reconnection does not initialize helper/profile/receipt');
 assert.equal(f.wire.calls.slice(calls).filter(c=>c[0]==='server').length,0,'no server CDP action is needed to restore the content observer');
 assert.ok(f.wire.calls.slice(calls).some(c=>c[0]==='selfrun.content.snapshot'));
 control.restore();const late=f.late.at(-1),sample=f.samples.at(-1);await f.life.stop();await f.life.controller.cleanupTerminalResources();await late.onActivity(sample);
 assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);assert.equal(f.inputs(),0);assert.equal(f.clicks(),0);
});

for(const state of ['STOPPED','DONE'])test('intent-null observation-only reattachment cannot finish after '+state,async t=>{
 const f=await setup(t),control=await preparedWithoutIntent(f),page=f.wire.pageRuntimes.get(1);
 await f.life.controller.quiesceForStandby();f.life.controller=f.life.recreate();page.context.window.history.pushState({},'',actualConversation);
 const connect=f.wire.manager.connectTarget.bind(f.wire.manager);let interrupted=false;
 f.wire.manager.connectTarget=async target=>{const session=await connect(target);if(!interrupted){interrupted=true;control.restore();f.life.control={...f.life.control,state,control_epoch:2};await f.life.controller.controlDurable(null,f.life.control,f.life.transport);}return session;};
 await f.life.controller.tick(f.life.transport);await f.life.settle();await f.life.controller.cleanupTerminalResources();
 assert.equal(interrupted,true);assert.equal(f.life.record(),null);assert.equal(f.life.controller.sessions.size,0);assert.equal(f.samples.length,0);
 assert.equal(f.inputs(),0);assert.equal(f.clicks(),0);assert.equal(f.wire.calls.filter(c=>c[0]==='server'&&c[1]==='Target.createTarget').length,1);
 for(const row of f.writes.filter(w=>w.body.conversation_url))await f.life.controller.ingest(row.path,row.body,f.life.transport);
 await f.life.controller.tick(f.life.transport);assert.equal(f.life.record(),null);assert.equal(f.inputs(),0);assert.equal(f.clicks(),0);
});

test('CONTROL failure after standby cannot reconnect observation or discard the formerly owned target',async t=>{
 const f=await setup(t);await start(f);const target=f.life.record().target_id,generation=f.life.record().run_generation;
 let entered,release;const waiting=new Promise(r=>entered=r),gate=new Promise(r=>release=r),read=f.life.transport.read;
 f.life.transport.read=async name=>{if(name.startsWith('__SELFRUN_CONTROL__')){entered();await gate;throw Error('delayed CONTROL failure');}return read(name);};
 let connects=0;const connect=f.wire.manager.connectTarget.bind(f.wire.manager);f.wire.manager.connectTarget=async target=>{connects++;return connect(target);};
 const calls=f.wire.calls.length,advancing=f.life.controller.tick(f.life.transport);advancing.catch(()=>{});await waiting;
 await f.life.controller.quiesceForStandby();release();await assert.rejects(advancing,/delayed CONTROL failure/);await f.life.settle();
 assert.equal(connects,0);assert.equal(f.life.controller.sessions.size,0);assert.equal(f.life.record().run_generation,generation);
 assert.equal(f.wire.manager.isOwnedTarget(target),true);assert.equal(f.wire.tabs.has(1),true);
 assert.equal(f.wire.calls.slice(calls).filter(c=>c[0]==='server'&&['Target.createTarget','Target.closeTarget','Page.navigate'].includes(c[1])).length,0);
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);
});

test('readonly restart with an uncertain initial intent and lost Android lease observes without receipt/profile effects',async t=>{
 const f=await setup(t,{manualClock:false,deferConversationUrl:true,conversationUrlOnSend:actualConversation});
 Object.assign(f.wire.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});f.browser.config.browserClientProfile='android-webview';
 const leases=[];f.wire.manager.deps.applyProfile=async()=>{const lease={alive:true,cdpCommands:3,cdpTargetId:'CDP1',isAlive(){return this.alive;},release(){this.alive=false;}};leases.push(lease);return lease;};
 f.life.body.project_url=actualProjectEntry;await f.life.ingest();await f.sample();
 await wait(()=>f.life.record()?.reason==='POST_READBACK_UNKNOWN','initial readback settled UNKNOWN');
 assert.equal(f.life.record().intent.outcome,'UNKNOWN');assert.equal(f.life.record().intent.sends,1);assert.equal(f.clicks(),1);assert.equal(leases.length,1);
 await f.life.controller.quiesceForStandby();f.life.controller=f.life.recreate();leases[0].alive=false;
 const page=f.wire.pageRuntimes.get(1);page.context.window.history.pushState({},'',actualConversation);
 const read=f.life.transport.read;f.life.transport.read=async name=>{if(name.startsWith('__SELFRUN_CONTROL__'))throw Error('CONTROL unavailable during readonly reconnect');return read(name);};
 const calls=f.wire.calls.length,effects=page.effects.length;
 await assert.rejects(f.life.controller.tick(f.life.transport),/CONTROL unavailable during readonly reconnect/);
 await assertAppliedProgress(f);await f.sample();
 assert.equal(f.life.rows.get(f.life.path).conversation_url,appUrl(actualConversation));assert.equal(leases.length,1,'readonly observation must not reapply Android profile');assert.equal(f.life.record().intent.outcome,'UNKNOWN');
 assert.equal(page.effects.slice(effects).filter(e=>e.kind==='action').length,0);
 assert.equal(f.wire.calls.slice(calls).filter(c=>c[0]==='server').length,0);assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);
 f.life.transport.read=read;await f.life.controller.tick(f.life.transport);await f.sample();
 await wait(()=>leases.length===2,'profile restoration resumes only after CONTROL authorization');
 assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);assert.ok(f.life.record().cursor);
 await f.life.stop();await f.life.controller.cleanupTerminalResources();assert.equal(f.life.record(),null);
});


test('successor removes prior execution payload, HTTP cache and extension ownership while the new turn remains live',async t=>{
 const f=await setup(t);await start(f);const prior=structuredClone(f.life.record()),oldKey=prior.key,oldTarget=prior.target_id,oldMonitor=f.late[0],sample=f.samples.at(-1);
 const http=new SignalDispatchTransport(f.life.transport,f.life.store);
 await http.register('synthetic-old-signal',f.life.path,f.life.body,'__SELFRUN_CONTROL__SAFE-TEST.json',f.life.control);
 const next={...f.life.body,turn_id:'SAFE-TEST:turn:2',request_id:'SAFE-TEST:turn:2-request',result_document_id:'RESULT-2',conversation_url:'',client_status:'CREATE_REQUESTED',server_control_epoch:2};
 f.life.control={...f.life.control,turn_id:next.turn_id,request_id:next.request_id,control_epoch:2};await f.life.controller.control(null,f.life.control,f.life.transport);
 f.life.body=next;f.life.path='__SELFRUN_DISPATCH__successor-discard.json';f.life.result(false,'RESULT-2',2);await f.life.ingest();
 await f.life.controller.cleanupTerminalResources();await f.life.settle();await f.wire.manager.saveChain;
 const data=f.life.store.snapshot();
 for(const field of ['requests','retired','outbox','completionOutbox','browserCleanup','browserCleanupOutcomes','browserCleanupUnresolved']){
  assert.equal(Object.hasOwn(data.lifecycle[field]||{},oldKey),false,field);
  assert.equal(Object.hasOwn(data.lifecycle[field]||{},oldTarget),false,field);
 }
 assert.equal(f.life.controller.repository.get(oldKey),null);assert.equal(oldMonitor.signal.aborted,true);
 assert.equal(f.life.controller.sessions.has(oldKey),false);assert.equal(f.life.controller.operations.has(oldKey),false);assert.equal(f.life.controller.repository.resourceCleanups.size,0);
 assert.equal(f.wire.tabs.has(1),false);assert.equal(f.wire.manager.isOwnedTarget(oldTarget),false);assert.equal(f.wire.manager.actionSessions.has(oldTarget),false);assert.equal(f.wire.manager.sessions.has(oldTarget),false);
 assert.equal(Object.hasOwn(f.wire.manager.journal.targetMetadata,oldTarget),false);assert.equal(JSON.stringify(f.wire.storage).includes(oldTarget),false);
 assert.equal(Object.values(data.httpIngress.files).some(b=>b.request_id===prior.request_id),false);assert.equal(data.httpIngress.signals['synthetic-old-signal'],undefined);
 const restoredStore=new MemoryStore(data),restored=new LifecycleStore(restoredStore);await restored.initialize('synthetic');assert.equal(restored.get(oldKey),null);
 assert.equal(f.life.record().state,'PREPARED');assert.equal(f.wire.tabs.has(2),true);assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);
 const nextBefore=JSON.stringify(f.life.record());await oldMonitor.onActivity(sample);await f.life.settle();assert.equal(JSON.stringify(f.life.record()),nextBefore);assert.equal(f.life.controller.repository.get(oldKey),null);
 f.life.body.client_status='SEND_REQUESTED';await f.life.ingest();await wait(()=>f.clicks()===2,'one successor click');await f.sample();assert.equal(f.inputs(),2);assert.ok(f.life.record().cursor);
 await f.life.stop();await f.life.controller.cleanupTerminalResources();assert.equal(f.life.record(),null);assert.deepEqual(f.life.store.snapshot().httpIngress,{files:{},signals:{}});
});
