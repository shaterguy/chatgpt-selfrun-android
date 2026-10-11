import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {ServerActionSession} from '../src/browser/server-action-session.mjs';
import {ChatGptBrowser,observedConversationUrl,actionConversationUrl} from '../src/browser/chatgpt.mjs';
import {fixture} from './helpers/lifecycle-fixture.mjs';
import {monitorRuntimeFixture,wait} from './helpers/monitor-runtime-fixture.mjs';
import {connectedPageRuntime} from './helpers/connected-page-runtime-fixture.mjs';
const project='https://chatgpt.com/g/test/project';
const conversation='https://chatgpt.com/g/test/c/owned-conversation';
const profileOperations=['model','thinking_effort','conversation_origin','service_tier'].map(path=>({op:'REMOVE',path}));

test('same owned action target accepts its project conversation before release',()=>{
 const actor=new ServerActionSession({session:{},installationKey:'test',initialUrl:project});
 assert.equal(actor.released,null);
 assert.equal(actor.verifyUrl(conversation),conversation);
 assert.throws(()=>actor.verifyUrl('https://chatgpt.com/g/other/c/foreign'),/repurposed/);
});

test('actual submitIntent publishes owned metadata URL and enters controller monitor despite dead DOM',async t=>{
 const life=fixture(),operations=[];
 life.probe={url:project,composer:true};
 const page={transport:'extension',close(){},async run(op){operations.push(op);throw Error('CDP command timeout: Runtime.evaluate');}};
 const browser=new ChatGptBrowser({listExistingTargets:async()=>[{id:'target-owned',type:'page',url:conversation}]},{navigationTimeoutMs:10});
 life.browser.prepare=async()=>({target:{id:'target-owned'},session:page,baseline:life.probe});
 life.browser.submitIntent=args=>browser.submitIntent(args);
 t.after(()=>life.controller.quiesceForStandby());
 await life.ingest();
 assert.equal(life.record().conversation_url,'https://chatgpt.com/c/owned-conversation');
 assert.equal(life.rows.get(life.path).conversation_url,'https://chatgpt.com/c/owned-conversation');
 assert.equal(life.rows.get(life.path).server_status,'PENDING','URL publication does not add a lifecycle status contract');
 assert.ok(life.observations.length>0);
 assert.equal(operations.length,0,'metadata URL handoff precedes all DOM/input calls');
 assert.notEqual(life.record().intent.outcome,'CONFIRMED');
 assert.notEqual(life.record().intent.released,true);
});

test('monitor accepts an attested new conversation document without any release or project flag',async t=>{
 const f=await monitorRuntimeFixture(t,{pageRuntimeFactory:connectedPageRuntime}),target=await f.manager.createTarget(project),session=await f.manager.connectTarget(target);
 await session.run('navigate',{url:project});await session.run('progress');
 const page=f.pageRuntimes.get(1);t.after(()=>page.disposeTimers());page.replaceDocument('https://chatgpt.com/g/other/c/owned-conversation');
 assert.equal((await session.run('progress')).url,'https://chatgpt.com/g/other/c/owned-conversation');
 assert.ok(Object.values(f.storage).some(rows=>Object.values(rows).some(row=>row.documentId===page.documentId)));
 assert.equal(page.effects.filter(e=>['input','click'].includes(e.kind)).length,0);
});

for(const failure of ['MISMATCH','NO_COMPOSER','TIMEOUT'])test('lost first input '+failure+' hands its new URL to the real controller without another input',async t=>{
 const life=fixture(),operations=[];let url=project;
 life.probe={url:project,composer:true};
 const page={transport:'extension',close(){},async run(op){operations.push(op);
   if(op==='recoveryInput')return{url,composer:true};
   if(op==='input'){url=conversation;if(failure==='TIMEOUT')throw Error('CDP command timeout: Runtime.evaluate');return{status:failure};}
   return true;
 }};
 const browser=new ChatGptBrowser({listExistingTargets:async()=>[{id:'target-owned',type:'page',url}]},{});
 life.browser.prepare=async()=>({target:{id:'target-owned'},session:page,baseline:life.probe});
 life.browser.submitIntent=args=>browser.submitIntent(args);
 t.after(()=>life.controller.quiesceForStandby());
 await life.ingest();
 assert.equal(life.rows.get(life.path).conversation_url,'https://chatgpt.com/c/owned-conversation');
 assert.ok(life.observations.length>0);
 assert.equal(operations.filter(x=>x==='input').length,1);
 assert.equal(operations.filter(x=>x==='submit').length,0);
 assert.equal(life.record().intent.sends,0);
 assert.notEqual(life.record().intent.released,true);
 assert.notEqual(life.record().intent.outcome,'CONFIRMED');
});

test('owned metadata URL wins before a pre-submit retry timer and dead DOM',async t=>{
 const life=fixture();let url=project,domCalls=0;
 const page={transport:'extension',close(){},async run(){domCalls++;throw Error('CDP command timeout: Runtime.evaluate');}};
 const browser=new ChatGptBrowser({listExistingTargets:async()=>[{id:'target-owned',type:'page',url}]},{});
 life.browser.prepare=async()=>({target:{id:'target-owned'},session:page,baseline:{url:project}});
 life.browser.submitIntent=args=>browser.submitIntent(args);
 life.browser.ownedConversationUrl=args=>browser.ownedConversationUrl(args);
 t.after(()=>life.controller.quiesceForStandby());
 await life.ingest();
 assert.equal(life.record().reason,'PRE_SUBMIT_RETRY');
 assert.ok(life.record().retry_at>Date.now());
 const before=domCalls;url=conversation;
 await Promise.all([life.controller.tick(life.transport),life.controller.tick(life.transport)]);await life.settle();
 assert.equal(life.rows.get(life.path).conversation_url,'https://chatgpt.com/c/owned-conversation');
 assert.ok(life.observations.length>0);
 assert.equal(domCalls,before);
 assert.equal(life.record().intent.sends,0);
 assert.notEqual(life.record().intent.released,true);
});

for(const status of ['MISMATCH','NO_COMPOSER'])test('initial input preserves '+status+' without publishing prompt',async()=>{
 const browser=new ChatGptBrowser(null,{}),prompt='private prepared input';
 await assert.rejects(browser.submitIntent({session:{transport:'extension',run:async op=>op==='recoveryInput'?{url:project}:op==='input'?{status}:true},
   kind:'initial',projectUrl:project,prompt,intent:{prompt},guard:()=>true}),error=>error.message.endsWith(status)&&!error.message.includes(prompt));
});

for(const row of [{id:'other-target',url:conversation},{id:'target-owned',url:'https://example.com/g/test/c/foreign'}])test('metadata rejects foreign target or origin '+row.url+':'+row.id,async()=>{
 const browser=new ChatGptBrowser({listExistingTargets:async()=>[{...row,type:'page'}]},{});
 assert.equal(await browser.ownedConversationUrl({targetId:'target-owned',projectUrl:project}),'');
});
for(const url of ['https://chatgpt.com/g/other/c/foreign','https://chatgpt.com/c/foreign'])test('metadata hands off the exact owned target address independently of requested project '+url,async()=>{
 const browser=new ChatGptBrowser({listExistingTargets:async()=>[{id:'target-owned',url,type:'page'}]},{});
 assert.equal(await browser.ownedConversationUrl({targetId:'target-owned',projectUrl:project}),url);
});

const initial='http://127.0.0.1:9223/bootstrap/00000000-0000-4000-8000-000000000000';
function actionFixture({deliverNewDocument=false}={}) {
  const calls=[],scripts=new Map(),requests=[];let context,registration=0,pageEnabled=false,capturedFetch,clicks=0;
  const replaceDocument=url=>{
    const window={fetch:async(input,init)=>{const request=input instanceof Request?input:new Request(input,init);
      requests.push(JSON.parse(await request.text()));return new Response('{}');},addEventListener(){},history:{}};
    const send={isConnected:true,offsetParent:{},dataset:{testid:'send-button'},disabled:false,getAttribute(){return null;},click(){ clicks++;void capturedFetch('https://chatgpt.com/backend-api/conversation',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({messages:[{id:'92dd19de-1242-4d48-8f34-413e8ed42aa4',author:{role:'user'},content:{parts:['prompt']}}],model:'old',thinking_effort:'old',conversation_origin:'old',service_tier:'old'})});}};
    const composer={isConnected:true,offsetParent:{},value:'prompt',focus(){},dispatchEvent(){return true;},closest(){return null;}};
    const document={querySelectorAll:selector=>selector==='button'?[send]:[composer]};
    context=vm.createContext({window,document,location:{href:url,origin:new URL(url).origin},URL,TextEncoder,TextDecoder,
      Event,crypto,Request,Response,Headers,ReadableStream,AbortController,setTimeout,clearTimeout,performance});
    if(deliverNewDocument===true||deliverNewDocument==='page-enabled'&&pageEnabled)
      for(const source of scripts.values())vm.runInContext(source,context);
    capturedFetch=window.fetch; // First application script, before any later server evaluation.
  };
  replaceDocument(initial);
  const transport={socket:{readyState:1},close(){this.socket.readyState=3;},async call(method,params){
    calls.push({method,params});
    if(method==='Page.enable'){pageEnabled=true;return{};}
    if(method==='Page.addScriptToEvaluateOnNewDocument'){
      const identifier='script-'+(++registration);scripts.set(identifier,params.source);return{identifier};
    }
    if(method==='Page.removeScriptToEvaluateOnNewDocument'){scripts.delete(params.identifier);return{};}
    if(method==='Page.navigate'){replaceDocument(params.url);return{};}
    if(method==='Runtime.evaluate'){
      try{return{result:{value:await vm.runInContext(params.expression,context)}};}
      catch(error){return{exceptionDetails:{exception:{description:error.message}}};}
    }
    throw Error('Unexpected test transport method: '+method);
  }};
  const session=new ServerActionSession({session:transport,installationKey:'fixture',initialUrl:initial,
    allowedOrigins:['https://chatgpt.com']});
  return{session,transport,calls,scripts,requests,replaceDocument,changeUrl:url=>{context.location.href=url;},get clicks(){return clicks;},get capturedFetch(){return capturedFetch;},get window(){return context.window;}};
}

for(const boundary of ['input','submit'])test('actual helper skips '+boundary+' when the owned conversation appears at the final action boundary',async()=>{
 const f=actionFixture({deliverNewDocument:'page-enabled'}),events=[];
 await f.session.run('navigate',{url:project});
 const priorReleased={intentId:'older',generation:'older'};f.session.released=priorReleased;
 const call=f.transport.call.bind(f.transport);let switched=false;
 f.transport.call=async(method,params)=>{
   if(!switched&&params.expression?.includes('helper.run("'+boundary+'"')){switched=true;f.changeUrl(conversation);}
   return call(method,params);
 };
 const browser=new ChatGptBrowser({listExistingTargets:async()=>[{id:'target-owned',type:'page',url:project}]},{});
 const result=await browser.submitIntent({session:{transport:'extension',run:(...args)=>f.session.run(...args)},
   kind:'initial',projectUrl:project,targetId:'target-owned',prompt:'prompt',intent:{id:'intent',receipt_generation:'generation',prompt:'prompt'},profileOperations,guard:()=>true,
   onConversation:async event=>events.push(event),onSubmitted:async()=>assert.fail('no click'),onRequest:async()=>assert.fail('no released request')});
 assert.equal(result.url,conversation);assert.equal(result.observationOnly,true);
 assert.equal(events.length,1);assert.equal(events[0].url,conversation);
 assert.equal(f.clicks,0);assert.equal(f.requests.length,0);
 assert.equal(f.session.released,priorReleased,'an older released fence is never erased');
});

test('page profile stays active across the same project conversation even without submit',async()=>{
 const f=actionFixture({deliverNewDocument:'page-enabled'});
 await f.session.run('navigate',{url:project});
 await f.session.run('installProfile');
 await f.session.run('profileConfigure',{operations:profileOperations});
 f.changeUrl(conversation);
 await f.capturedFetch('https://chatgpt.com/backend-api/conversation',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({messages:[{author:{role:'user'},content:{parts:['prompt']}}],model:'old'})});
 assert.equal(f.requests.length,1);assert.equal(Object.hasOwn(f.requests[0],'model'),false);
});

test('an actual click with a lost response keeps its fence while URL handoff prevents replay',async()=>{
 const f=actionFixture({deliverNewDocument:'page-enabled'}),events=[];let url=project;
 await f.session.run('navigate',{url:project});
 const call=f.transport.call.bind(f.transport);let lost=false;
 f.transport.call=async(method,params)=>{const value=await call(method,params);
   if(!lost&&params.expression?.includes('helper.run("submit"')){lost=true;url=conversation;f.changeUrl(url);throw Error('websocket closed after click');}
   return value;
 };
 const browser=new ChatGptBrowser({listExistingTargets:async()=>[{id:'target-owned',type:'page',url}]},{});
 const args={session:{transport:'extension',run:(...a)=>f.session.run(...a)},kind:'initial',projectUrl:project,targetId:'target-owned',prompt:'prompt',
   intent:{id:'intent',receipt_generation:'generation',prompt:'prompt'},profileOperations,guard:()=>true,onConversation:async e=>events.push(e)};
 const result=await browser.submitIntent(args);
 assert.equal(result.observationOnly,true);assert.equal(result.noSubmitEffect,false);
 assert.equal(f.clicks,1);const fence=f.session.released;assert.ok(fence);
 await browser.submitIntent(args);
 assert.equal(f.clicks,1);assert.equal(f.session.released,fence);
 f.session.reconnect=async()=>({socket:{readyState:1},call:(...a)=>f.transport.call(...a),close(){}});
 await assert.rejects(f.session.run('submit',{initialProjectUrl:project}),/already have been released/);
 assert.equal(f.clicks,1);
});

test('STOP during metadata lookup cannot publish a stale URL or reach input',async()=>{
 let active=true,publish=0,dom=0;
 const browser=new ChatGptBrowser({listExistingTargets:async()=>{active=false;return[{id:'owned',type:'page',url:conversation}];}},{});
 await assert.rejects(browser.submitIntent({session:{transport:'extension',run:async()=>{dom++;}},kind:'initial',projectUrl:project,targetId:'owned',
   prompt:'prompt',intent:{prompt:'prompt'},guard:()=>active,onConversation:async()=>publish++}),/stale conversation lookup/);
 assert.equal(publish,0);assert.equal(dom,0);
});

test('monitor follows the registered tab while refusing an unattested different document',async t=>{
 const f=await monitorRuntimeFixture(t),target=await f.manager.createTarget(project),session=await f.manager.connectTarget(target);
 await session.run('navigate',{url:project});await session.run('progress');
 f.tabs.get(1).url=conversation;
 assert.equal((await f.bridge.request('progress',{targetId:target.id})).url,conversation);
 f.tabs.get(1).url='https://chatgpt.com/g/other/c/foreign';
 assert.equal((await f.bridge.request('progress',{targetId:target.id})).url,'https://chatgpt.com/g/other/c/foreign');
 f.hello(1,{documentId:'foreign-document'});
 assert.equal(Object.values(f.storage).some(rows=>Object.values(rows).some(row=>row.documentId==='foreign-document')),false);
});


test('metadata rejects an unapproved ChatGPT port even when both project and conversation match',async()=>{
 const browser=new ChatGptBrowser({listExistingTargets:async()=>[{id:'owned',type:'page',url:'https://chatgpt.com:444/g/test/c/foreign'}]},{});
 assert.equal(await browser.ownedConversationUrl({targetId:'owned',projectUrl:'https://chatgpt.com:444/g/test/project'}),'');
});

test('restart publishes the owned URL before unavailable attachment DOM and never repeats initial input',async t=>{
 const life=fixture();let url=project,dom=0,attaches=0;
 const browser=new ChatGptBrowser({listExistingTargets:async()=>[{id:'target-owned',type:'page',url}]},{});
 life.browser.prepare=async()=>({target:{id:'target-owned'},session:{transport:'extension',close(){},async run(){dom++;throw Error('CDP command timeout: Runtime.evaluate');}},baseline:{url:project}});
 life.browser.submitIntent=args=>browser.submitIntent(args);
 life.browser.ownedConversationUrl=args=>browser.ownedConversationUrl(args);
 life.browser.attachTarget=async options=>{attaches++;assert.equal(options.targetId,'target-owned');assert.equal(options.observationOnly,true);assert.equal(life.rows.get(life.path).conversation_url,'https://chatgpt.com/c/owned-conversation');throw Error('CDP command timeout: Runtime.evaluate');};
 t.after(()=>life.controller.quiesceForStandby());
 await life.ingest();assert.equal(life.record().reason,'PRE_SUBMIT_RETRY');const before=dom;
 await life.controller.quiesceForStandby();life.controller=life.recreate();url=conversation;
 await life.controller.tick(life.transport);await life.settle();
 assert.equal(life.rows.get(life.path).conversation_url,'https://chatgpt.com/c/owned-conversation');
 assert.equal(attaches,1,'publish URL before the readonly attachment attempt');
 await life.controller.tick(life.transport);await life.settle();
 assert.equal(attaches,1);assert.equal(dom,before);assert.equal(life.record().intent.sends,0);
 assert.equal(life.record().conversation_url,'https://chatgpt.com/c/owned-conversation');
 assert.notEqual(life.record().intent.released,true);assert.notEqual(life.record().intent.outcome,'CONFIRMED');
});

for(const state of ['STOPPED','DONE'])test(state+' during a restart metadata lookup cannot restore or publish the task',async t=>{
 const life=fixture();let pending=false,entered,release;
 const started=new Promise(r=>entered=r),gate=new Promise(r=>release=r);
 const browser=new ChatGptBrowser({listExistingTargets:async()=>{if(pending){entered();await gate;}return[{id:'target-owned',type:'page',url:pending?conversation:project}];}},{});
 life.browser.prepare=async()=>({target:{id:'target-owned'},session:{transport:'extension',close(){},async run(){throw Error('CDP command timeout: Runtime.evaluate');}},baseline:{url:project}});
 life.browser.submitIntent=args=>browser.submitIntent(args);life.browser.ownedConversationUrl=args=>browser.ownedConversationUrl(args);
 t.after(()=>life.controller.quiesceForStandby());
 await life.ingest();await life.controller.quiesceForStandby();life.controller=life.recreate();pending=true;
 const advancing=life.controller.tick(life.transport).catch(error=>error);await started;
 life.control={...life.control,state,control_epoch:2};await life.controller.control(null,life.control,life.transport);
 release();await advancing;await life.settle();
 assert.equal(life.record(),null);assert.equal(life.controller.sessions.size,0);
 assert.equal(life.rows.get(life.path)?.conversation_url,undefined);
});

test('URL-only observation does not borrow the input authorization guard',async()=>{
 let input=0,published='';
 const browser=new ChatGptBrowser({listExistingTargets:async()=>[{id:'owned',type:'page',url:'https://chatgpt.com/g/different/c/observed'}]},{});
 const result=await browser.submitIntent({session:{transport:'extension',run:async()=>input++},targetId:'owned',projectUrl:project,kind:'initial',intent:{prompt:'prompt'},prompt:'prompt',
  guard:()=>false,observationGuard:()=>true,onConversation:async({url})=>published=url});
 assert.equal(published,'https://chatgpt.com/g/different/c/observed');assert.equal(result.observationOnly,true);assert.equal(input,0);
});

for(const url of ['https://chatgpt.com/g/renamed/c/a','https://chatgpt.com/g/g-p-other-id/c/a','https://chatgpt.com/g/%ED%95%9C%EA%B8%80/c/a','https://chatgpt.com/c/a'])
 test('conversation observation ignores the project name or ID: '+url,()=>{assert.equal(observedConversationUrl(url),url);assert.equal(actionConversationUrl(project,url),url);});
for(const url of ['http://chatgpt.com/c/a','https://chatgpt.com:444/c/a','https://chatgpt.com.evil.invalid/c/a','https://user@chatgpt.com/c/a','https://chatgpt.com/c/a%2Fb','https://chatgpt.com/c/%2e%2e','https://chatgpt.com/c/a/extra','https://chatgpt.com/g/test/project'])
 test('conversation observation retains URL syntax and approved-origin boundary: '+url,()=>assert.equal(observedConversationUrl(url),''));

test('action ownership follows route aliases of its conversation but refuses another conversation',()=>{
 const actor=new ServerActionSession({session:{},installationKey:'test',initialUrl:project});
 actor.verifyUrl('https://chatgpt.com/g/renamed/c/owned');
 assert.equal(actor.verifyUrl('https://chatgpt.com/c/owned'),'https://chatgpt.com/c/owned');
 assert.throws(()=>actor.verifyUrl('https://chatgpt.com/c/another'),/repurposed/);
});
