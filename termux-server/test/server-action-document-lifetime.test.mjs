import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {ServerActionSession} from '../src/browser/server-action-session.mjs';

const initial='http://127.0.0.1:9223/bootstrap/00000000-0000-4000-8000-000000000000';
const project='https://chatgpt.com/g/test/project';

// Execute the actual registered/evaluated source in separate document globals.
// A CDP registration acknowledgement does not manufacture a delivered helper.
function fixture({deliverNewDocument=false}={}) {
  const calls=[],scripts=new Map(),requests=[];let context,registration=0,pageEnabled=false,capturedFetch;
  const replaceDocument=url=>{
    const window={fetch:async(input,init)=>{const request=input instanceof Request?input:new Request(input,init);
      requests.push(JSON.parse(await request.text()));return new Response('{}');},addEventListener(){},history:{}};
    const composer={isConnected:true,offsetParent:{}};
    const document={querySelectorAll:selector=>selector==='button'?[]:[composer]};
    context=vm.createContext({window,document,location:{href:url,origin:new URL(url).origin},URL,TextEncoder,TextDecoder,
      crypto,Request,Response,Headers,ReadableStream,AbortController,setTimeout,clearTimeout,performance});
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
  return{session,transport,calls,scripts,requests,replaceDocument,get capturedFetch(){return capturedFetch;},get window(){return context.window;}};
}

test('Page initialization delivers the registered helper before application startup',async()=>{
  const f=fixture({deliverNewDocument:'page-enabled'});await f.session.run('navigate',{url:project});
  assert.ok(f.window.__selfrunExtensionPage,'registration ACK alone cannot deliver the next-document helper');
  assert.equal(f.calls.filter(c=>c.method==='Page.enable').length,1);
  assert.ok(f.calls.findIndex(c=>c.method==='Page.enable')<f.calls.findIndex(c=>c.method==='Page.addScriptToEvaluateOnNewDocument'));
});

for(const mode of ['set-and-remove','remove-all'])test('application startup-captured fetch applies the four-field profile: '+mode,async()=>{
  const f=fixture({deliverNewDocument:'page-enabled'});await f.session.run('navigate',{url:project});
  await f.session.run('recoveryInput'); // Late repair alone must not make this test pass.
  await f.session.run('installProfile');
  const operations=[{op:'SET',path:'model',value:'test-model'},
    {op:'SET',path:'thinking_effort',value:'max'},{op:'REMOVE',path:'conversation_origin'},
    {op:'REMOVE',path:'service_tier'}];
  await f.session.run('profileConfigure',{operations:mode==='remove-all'
    ?operations.map(({path})=>({op:'REMOVE',path})):operations});
  await f.capturedFetch('https://chatgpt.com/backend-api/conversation',{method:'POST',
    headers:{'content-type':'application/json'},body:JSON.stringify({messages:[{author:{role:'user'},
      content:{parts:['synthetic prompt']}}],model:'old',thinking_effort:'old',
      conversation_origin:'old',service_tier:'old',unrelated:'keep'})});
  assert.equal(f.requests.length,1);
  if(mode==='remove-all')for(const {path} of operations)assert.equal(path in f.requests[0],false,path);
  else{assert.equal(f.requests[0].model,'test-model');assert.equal(f.requests[0].thinking_effort,'max');}
  assert.equal('conversation_origin' in f.requests[0],false);
  assert.equal('service_tier' in f.requests[0],false);assert.equal(f.requests[0].unrelated,'keep');
});

test('failed Page initialization does not register scripts, navigate, or release a submission',async()=>{
  const f=fixture({deliverNewDocument:'page-enabled'}),call=f.transport.call.bind(f.transport);
  f.transport.call=async(method,params)=>{if(method==='Page.enable')throw Error('Page initialization unavailable');return call(method,params);};
  await assert.rejects(f.session.run('navigate',{url:project}),/Page initialization unavailable/);
  assert.equal(f.scripts.size,0);assert.equal(f.calls.some(c=>c.method==='Page.navigate'),false);
  assert.equal(f.session.released,null);
  f.transport.call=call;await f.session.run('navigate',{url:project});
  assert.ok(f.window.__selfrunExtensionPage);assert.equal(f.scripts.size,1);
});

test('server action revalidates helper after navigation loses the bootstrap document',async()=>{
  const f=fixture();await f.session.install();
  const before=f.window.__selfrunExtensionPage;assert.ok(before);
  await f.session.run('navigate',{url:project});
  assert.equal(f.window.__selfrunExtensionPage,undefined,'new document has no delivered helper');
  const snapshot=await f.session.run('recoveryInput');
  assert.equal(snapshot.url,project);assert.equal(snapshot.composer,true);
  assert.ok(f.window.__selfrunExtensionPage);assert.notEqual(f.window.__selfrunExtensionPage,before);
  assert.equal(f.calls.filter(c=>c.method==='Page.navigate').length,1,'repair must not navigate again');
  assert.equal(f.scripts.size,1,'current-document repair must not duplicate future registrations');
});

test('server action revalidates an unchanged URL after its document globals are replaced',async()=>{
  const f=fixture();await f.session.run('navigate',{url:project});
  await f.session.run('recoveryInput');const before=f.window.__selfrunExtensionPage;
  f.replaceDocument(project);assert.equal(f.window.__selfrunExtensionPage,undefined);
  assert.equal((await f.session.run('recoveryInput')).composer,true);
  assert.notEqual(f.window.__selfrunExtensionPage,before);assert.equal(f.scripts.size,1);
});

test('a delivered helper is attested without replacement or duplicate registration',async()=>{
  const f=fixture({deliverNewDocument:true});await f.session.run('navigate',{url:project});
  const delivered=f.window.__selfrunExtensionPage;assert.ok(delivered);
  assert.equal((await f.session.run('recoveryInput')).composer,true);
  assert.equal((await f.session.run('recoveryInput')).composer,true);
  assert.equal(f.window.__selfrunExtensionPage,delivered);assert.equal(f.scripts.size,1);
});

test('a foreign helper after navigation is rejected rather than trusted from the prior document',async()=>{
  const f=fixture();await f.session.run('navigate',{url:project});let effects=0;
  f.window.__selfrunExtensionPage={attest:async()=> 'foreign',run:()=>{effects++;return true;}};
  await assert.rejects(f.session.run('recoveryInput'),/ownership unverified/);
  assert.equal(effects,0);assert.equal(f.scripts.size,1);
});

test('document repair preserves the already released submission fence',async()=>{
  const f=fixture();await f.session.run('navigate',{url:project});
  f.session.armed={intentId:'same',generation:'one'};f.session.released={...f.session.armed};
  await f.session.run('recoveryInput');
  await assert.rejects(f.session.run('submit'),e=>e.code==='SUBMISSION_UNCERTAIN'&&e.released===true);
  assert.equal(f.calls.some(c=>c.params.expression?.includes('helper.run("submit"')),false);
});
