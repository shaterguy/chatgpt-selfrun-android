import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {ServerActionSession} from '../src/browser/server-action-session.mjs';
import {ChatGptBrowser,preSubmitFailureStage} from '../src/browser/chatgpt.mjs';
import {isActionNotSubmitted} from '../src/browser/server-action-errors.mjs';
import {fixture as lifecycleFixture} from './helpers/lifecycle-fixture.mjs';

const initial='http://127.0.0.1:9223/bootstrap/00000000-0000-4000-8000-000000000000';
const project='https://chatgpt.com/g/test/project';

// Execute the actual registered/evaluated source in separate document globals.
// A CDP registration acknowledgement does not manufacture a delivered helper.
function fixture({deliverNewDocument=false}={}) {
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
  return{session,transport,calls,scripts,requests,replaceDocument,get clicks(){return clicks;},get capturedFetch(){return capturedFetch;},get window(){return context.window;}};
}

test('prepared receipt cannot submit on a replacement document with no profile',async()=>{
 const f=fixture({deliverNewDocument:'page-enabled'});
 await f.session.run('navigate',{url:project});
 await f.session.run('installProfile');
 await f.session.run('profileConfigure',{operations:['model','thinking_effort','conversation_origin','service_tier'].map(path=>({op:'REMOVE',path}))});
 await f.session.run('installReceipt');
 await f.session.run('receiptArm',{key:{intent_id:'same',generation:'one',prompt:'prompt'}});
 f.replaceDocument(project);
 await assert.rejects(f.session.run('submit'),isActionNotSubmitted);
 await new Promise(resolve=>setTimeout(resolve,5));
 assert.equal(f.session.released,null);
 assert.equal(f.requests.length,0,'new document must not inherit prepared submission authority');
});
test('replacement after submit attestation cannot cross the final effect boundary',async()=>{
 const f=fixture({deliverNewDocument:'page-enabled'});
 await f.session.run('navigate',{url:project});
 await f.session.run('installProfile');
 await f.session.run('profileConfigure',{operations:['model','thinking_effort','conversation_origin','service_tier'].map(path=>({op:'REMOVE',path}))});
 await f.session.run('installReceipt');
 await f.session.run('receiptArm',{key:{intent_id:'same',generation:'one',prompt:'prompt'}});
 const call=f.transport.call.bind(f.transport);let switched=false;
 f.transport.call=async(method,params)=>{const result=await call(method,params);if(!switched&&params.expression?.includes('.attest(')){switched=true;f.replaceDocument(project);}return result;};
 await assert.rejects(f.session.run('submit'),isActionNotSubmitted);
 await new Promise(resolve=>setTimeout(resolve,5));
 assert.equal(f.session.released,null);
 assert.equal(switched,true);assert.equal(f.requests.length,0,'final action must verify its prepared document');
});

const profileOperations=['model','thinking_effort','conversation_origin','service_tier'].map(path=>({op:'REMOVE',path}));
const submission=f=>({session:{transport:'extension',run:(...args)=>f.session.run(...args)},prompt:'prompt',kind:'initial',
  intent:{id:'same',receipt_generation:'one',prompt:'prompt',baseline:{}},profileOperations,guard:()=>true});
for(const boundary of ['after-arm','after-submit-attestation'])test('controller retries preparation after document replacement at '+boundary,async t=>{
 const f=fixture({deliverNewDocument:'page-enabled'}),life=lifecycleFixture();
 await f.session.run('navigate',{url:project});
 let now=Date.now();t.mock.method(Date,'now',()=>now);
 const browser=new ChatGptBrowser(null,{navigationTimeoutMs:10});
 const pageSession={transport:'extension',close(){},run:(...args)=>f.session.run(...args)};
 const call=f.transport.call.bind(f.transport);let switched=false,armed=false;
 f.transport.call=async(method,params)=>{const result=await call(method,params);
   if(params.expression?.includes('helper.run("receiptArm"')){armed=true;if(boundary==='after-arm'&&!switched){switched=true;f.replaceDocument(project);}}
   else if(boundary==='after-submit-attestation'&&armed&&!switched&&params.expression?.includes('.attest(')){
     switched=true;f.replaceDocument(project);
   }return result;};
 life.body.profile_operations=profileOperations;life.body.prompt='prompt';
 life.probe={url:project,composer:true,readyState:'complete',userCount:0,assistantCount:0,streaming:false};
 life.browser.prepare=async()=>{life.prepares++;return{target:{id:'same-target'},session:pageSession,baseline:life.probe};};
 life.browser.submitIntent=args=>browser.submitIntent(args);
 life.browser.readSubmission=async()=>({state:'UNKNOWN',probe:life.probe});
 t.after(()=>life.controller.quiesceForStandby());
 await life.ingest();
 assert.equal(switched,true);assert.equal(f.clicks,0);assert.equal(life.record().reason,'PRE_SUBMIT_RETRY');
 assert.equal(life.record().intent.pre_submit_stage,'input');assert.equal(life.record().intent.sends,0);
 assert.equal(life.record().intent.absence_proof,'SUBMIT_NOT_CALLED');assert.equal(life.record().intent.released,false);
 now+=1001;await life.controller.tick(life.transport);await life.settle();await new Promise(r=>setTimeout(r,5));
 assert.equal(f.clicks,1);assert.equal(f.requests.length,1);assert.equal(life.prepares,1);
 for(const {path} of profileOperations)assert.equal(path in f.requests[0],false,path);
});

for(const failure of ['lost-click-response','forged-no-click-message','lost-no-click-response','mismatched-no-click-result'])test('submit evidence remains uncertain for '+failure,async()=>{
 const f=fixture({deliverNewDocument:'page-enabled'});await f.session.run('navigate',{url:project});
 const browser=new ChatGptBrowser(null,{navigationTimeoutMs:10}),call=f.transport.call.bind(f.transport);
 f.transport.call=async(method,params)=>{
   if(params.expression?.includes('helper.run("submit"')){
     if(failure==='forged-no-click-message')return{exceptionDetails:{exception:{description:'Submission preparation changed before click'}}};
     if(failure==='mismatched-no-click-result')return{result:{value:{status:'NOT_SUBMITTED',reason:'PREPARATION_LOST',intent_id:'other',generation:'one'}}};
     if(failure==='lost-no-click-response')f.replaceDocument(project);
     await call(method,params);throw Error('submit response lost');
   }return call(method,params);
 };
 let error;try{await browser.submitIntent(submission(f));}catch(e){error=e;}
 assert.ok(error);assert.equal(isActionNotSubmitted(error),false);assert.equal(preSubmitFailureStage(error),null);
 assert.equal(error.released,true);
 assert.deepEqual(f.session.released,{intentId:'same',generation:'one'});
 await assert.rejects(f.session.run('submit'),e=>e.code==='SUBMISSION_UNCERTAIN');
 assert.equal(f.clicks,failure==='lost-click-response'?1:0);
});
