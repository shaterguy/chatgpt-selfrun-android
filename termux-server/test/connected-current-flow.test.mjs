import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {ChatGptBrowser} from '../src/browser/chatgpt.mjs';
import {keyFor} from '../src/lifecycle.mjs';
import {fixture as lifecycleFixture} from './helpers/lifecycle-fixture.mjs';
import {monitorRuntimeFixture,wait} from './helpers/monitor-runtime-fixture.mjs';
import {connectedPageRuntime} from './helpers/connected-page-runtime-fixture.mjs';
const TEN_MINUTES=600000;
const profileOperations=['model','thinking_effort','conversation_origin','service_tier'].map(path=>({op:'REMOVE',path}));
async function setup(t,{afterAction=null,manualClock=true,composerKind='textarea',deferConversationUrl=false}={}){
 let now=Date.now();if(manualClock)t.mock.method(Date,'now',()=>now);
 const wire=await monitorRuntimeFixture(t,{pageRuntimeFactory:args=>{const page=connectedPageRuntime({...args,composerKind,deferConversationUrl});if(afterAction){const call=page.transport.call.bind(page.transport);page.transport.call=async(method,params)=>{const result=await call(method,params),name=params.expression?.match(/helper\.run\(("[^"]+")/);if(name)await afterAction(JSON.parse(name[1]),page,result);return result;};}return page;}}),life=lifecycleFixture();
 life.body.profile_operations=profileOperations;
 const browser=new ChatGptBrowser(wire.manager,{browserClientProfile:'native',navigationTimeoutMs:50,probeIntervalMs:20,stallAfterMs:TEN_MINUTES});
 life.browser=browser;life.controller=life.recreate();life.controller.config.stallAfterMs=TEN_MINUTES;life.controller.config.turnStartDirective='Synthetic turn start directive';
 const samples=[],late=[];const monitor=browser.monitor.bind(browser);
 browser.monitor=args=>{late.push(args);return monitor({...args,onActivity:async(...values)=>{const result=await args.onActivity(...values);samples.push(values[0]);return result;}});};
 const resultReads=[],read=life.transport.readGoogleDocText;
 life.transport.readGoogleDocText=async id=>{resultReads.push(id);return read(id);};
 const writes=[],write=life.transport.write;
 life.transport.write=async(path,body)=>{writes.push({path,body:structuredClone(body)});return write(path,body);};
 t.after(async()=>{await life.controller.quiesceForStandby();for(const page of wire.pageRuntimes.values())page.disposeTimers();});
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
 for(const field of ['model','thinking_effort','conversation_origin','service_tier'])assert.equal(Object.hasOwn(page.requests[0],field),false);
 assert.equal(f.life.rows.get(f.life.path).conversation_url,'https://chatgpt.com/c/owned-1');
 assert.ok(f.wire.calls.some(c=>c[0]==='selfrun.content.snapshot'),'actual controller requested actual generated content probe');
 assert.ok(f.samples.some(s=>s.userCount===1&&s.assistantCount===1));
 assert.equal(f.life.record().intent.sends,1);
 await f.sample();assert.equal(f.inputs(),1);assert.equal(f.clicks(),1);await assertAppliedProgress(f);
 await fs.writeFile(new URL('../evidence/connected-current-dispatch.json',import.meta.url),JSON.stringify(f.life.rows.get(f.life.path),null,2)+'\n');
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
