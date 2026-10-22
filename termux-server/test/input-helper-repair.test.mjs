import test from 'node:test';
import assert from 'node:assert/strict';
import {ServerActionSession} from '../src/browser/server-action-session.mjs';
import {connectedPageRuntime} from './helpers/connected-page-runtime-fixture.mjs';
import {buildPageRuntimeSource as releasedV1Source} from './helpers/released-v1-runtime.mjs';
import {ChatGptBrowser} from '../src/browser/chatgpt.mjs';

const url='https://chatgpt.com/c/owned';
const operations=['model','thinking_effort','conversation_origin','service_tier'].map(path=>({op:'REMOVE',path}));
function fixture(t) {
 const tabs=new Map([[1,{url}]]),calls=[];
 const page=connectedPageRuntime({id:1,tabs,calls,notify:()=>{}});
 const session=new ServerActionSession({session:page.transport,initialUrl:url,installationKey:'fixture',allowedOrigins:['https://chatgpt.com']});
 t.after(()=>{session.close();page.disposeTimers();});
 return {page,session};
}
test('an input decision repairs a revoked helper in the same document',async t=>{
 const {page,session}=fixture(t);
 await session.run('installProfile');await session.run('profileConfigure',{operations});
 const helper=page.context.window.__selfrunExtensionPage,documentId=page.documentId;
 await session.run('terminalDispose');
 await session.run('prepareInput');
 assert.equal((await session.run('recoveryInput')).composer,true);
 assert.equal(page.documentId,documentId);
 assert.equal(page.context.window.__selfrunExtensionPage,helper,'the startup-captured transport facade remains usable');
 assert.equal(page.effects.some(e=>e.kind==='navigate'),false);
 await session.run('installProfile');await session.run('profileConfigure',{operations});
 assert.equal((await session.run('input',{prompt:'next'})).status,'READY');
});
test('input repair preserves the released submission fence',async t=>{
 const {session}=fixture(t);
 await session.run('installProfile');await session.run('profileConfigure',{operations});
 await session.run('receiptArm',{key:{intent_id:'already',generation:'one'}}).catch(()=>{});
 session.armed={intentId:'already',generation:'one'};session.released={...session.armed};
 await session.run('terminalDispose');await session.run('prepareInput');
 await assert.rejects(session.run('submit'),e=>e.code==='SUBMISSION_UNCERTAIN');
});
test('only an actual send decision invokes input preparation',async()=>{
 const calls=[],browser=new ChatGptBrowser(null,{});
 const session={transport:'extension',async run(op){calls.push(op);if(op==='snapshot')return{};if(op==='recoveryInput')return {url,composer:true};if(op==='input')return{status:'READY'};if(op==='submit')return{status:'SUBMITTED'};return true;}};
 await browser.livenessSnapshot({session});assert.deepEqual(calls,['snapshot']);
 await browser.submitIntent({session,prompt:'next',kind:'recovery',conversationUrl:url,intent:{id:'i',receipt_generation:'g',prompt:'next'},profileOperations:operations,guard:()=>true});
 assert.ok(calls.indexOf('prepareInput')>calls.indexOf('snapshot'));
 assert.ok(calls.indexOf('prepareInput')<calls.indexOf('input'));
 assert.equal(calls.filter(x=>x==='prepareInput').length,1);
});
test('the startup-captured fetch uses the repaired helper and the newest parent without navigation',async t=>{
 const {page,session}=fixture(t);await session.install();page.replaceDocument(url);
 await session.run('installProfile');await session.run('profileConfigure',{operations});
 const documentId=page.documentId,helper=page.context.window.__selfrunExtensionPage;
 await session.run('terminalDispose');
 await session.run('prepareInput',{prompt:'next'});
 await session.run('installProfile');await session.run('profileConfigure',{operations});await session.run('installReceipt');
 await session.run('input',{prompt:'next'});page.setServerTip('latest-from-other-device');
 await session.run('receiptArm',{key:{intent_id:'next',generation:'new',prompt:'next',conversation_id:'owned'}});
 await session.run('submit');
 for(let i=0;i<20&&!page.requests.length;i++)await new Promise(r=>setTimeout(r,2));
 assert.equal(page.requests.length,1);assert.equal(page.requests[0].parent_message_id,'latest-from-other-device');
 assert.equal(page.requests[0].messages[0].content.parts[0],'next');
 assert.equal(page.documentId,documentId);assert.equal(page.context.window.__selfrunExtensionPage,helper);
 assert.equal(page.effects.some(x=>x.kind==='navigate'),false);
});

test('preparing another input fences an older pending body parse',async t=>{
 const {page,session}=fixture(t);await session.run('prepareInput',{prompt:'old'});
 await session.run('installProfile');await session.run('profileConfigure',{operations});
 let release;const stream=new ReadableStream({start(controller){release=()=>{controller.enqueue(new TextEncoder().encode(JSON.stringify({conversation_id:'owned',messages:[{author:{role:'user'},content:{parts:['old']}}]})));controller.close();};}});
 const pending=page.context.window.fetch(new Request('https://chatgpt.com/backend-api/conversation',{method:'POST',body:stream,duplex:'half'}));
 const rejected=assert.rejects(pending,/runtime_disposed/);
 await session.run('prepareInput',{prompt:'next'});release();await rejected;
 assert.equal(page.requests.length,0);
});

test('a released immutable v1 helper is upgraded only by an input decision without document replacement',async t=>{
 const {page,session}=fixture(t);
 await page.transport.call('Page.enable',{});
 await page.transport.call('Page.addScriptToEvaluateOnNewDocument',{source:releasedV1Source({installationKey:'fixture'})});
 page.replaceDocument(url);const old=page.context.window.__selfrunExtensionPage,documentId=page.documentId;
 await session.install();assert.equal(page.context.window.__selfrunExtensionPageV2,undefined);
 await session.run('terminalDispose');
 await session.run('prepareInput',{prompt:'next',operations});
 const repaired=page.context.window.__selfrunExtensionPageV2;
 assert.equal(repaired.version,'selfrun-extension-page-v2');assert.equal(repaired.legacyTransport,true);
 assert.equal(page.context.window.__selfrunExtensionPage,old);assert.equal(page.documentId,documentId);
 await session.run('installProfile');await session.run('profileConfigure',{operations});
 assert.equal((await session.run('input',{prompt:'next'})).status,'READY');
 assert.ok(session.legacyInput);await session.legacyInput.dispose();
 assert.equal(page.effects.some(x=>x.kind==='navigate'),false);
});

test('legacy input survives the preparation command deadline and pins parent reads to its physical connection',async t=>{
 const {page,session}=fixture(t),command=new AbortController(),lifetime=new AbortController();
 await page.transport.call('Page.enable',{});await page.transport.call('Page.addScriptToEvaluateOnNewDocument',{source:releasedV1Source({installationKey:'fixture'})});page.replaceDocument(url);
 await session.run('prepareInput',{prompt:'next',operations},{signal:command.signal,lifetimeSignal:lifetime.signal});
 const input=session.legacyInput;command.abort();assert.equal(input.current(),true);
 session.transportLost=true;assert.equal(await input.evaluate('42',{}),42);assert.equal(session.session,page.transport);
 input.closeTarget=async()=>true;lifetime.abort();await input.dispose();assert.equal(input.current(),false);
});
