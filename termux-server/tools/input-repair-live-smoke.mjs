// Explicit smoke probe. Reads the real graph but fulfills every probe POST
// through CDP locally; no synthetic message reaches the conversation server.
import assert from 'node:assert/strict';
import {CdpSession} from '../src/browser/cdp.mjs';
import {config} from '../src/config.mjs';
import {applyBrowserClientProfile} from '../src/browser/chatgpt.mjs';
import {ServerActionSession} from '../src/browser/server-action-session.mjs';
import {buildPageRuntimeSource as releasedV1} from '../test/helpers/released-v1-runtime.mjs';
import {LegacyInputTransport} from '../src/browser/legacy-input-transport.mjs';
import {inputParentRuntime} from '../src/browser/input-parent-sync.mjs';
const url=process.argv[2];assert.match(url||'',/^https:\/\/chatgpt\.com\/(?:.*\/)?c\/[A-Za-z0-9-]+$/);
const paused=LegacyInputTransport.prototype.paused;LegacyInputTransport.prototype.paused=async function(event){try{return await paused.call(this,event);}catch(error){console.error('INTERCEPT_ERROR',error.message);throw error;}};
const browser=new CdpSession((await(await fetch('http://127.0.0.1:'+config.browserPort+'/json/version')).json()).webSocketDebuggerUrl);
await browser.connect();let page,action,targetId,patched,legacyTip,posts=0,navigations=0;
try {
 ({targetId}=await browser.call('Target.createTarget',{url:'about:blank'}));console.log('CREATED_PROBE_TARGET',targetId);
 page=new CdpSession('ws://127.0.0.1:'+config.browserPort+'/devtools/page/'+targetId);await page.connect();
 await page.call('Page.enable');await applyBrowserClientProfile(page,config);
 await page.call('Page.navigate',{url});navigations++;
 for(let n=0;n<30;n++){
  const r=await page.call('Runtime.evaluate',{expression:"document.readyState==='complete'&&location.pathname.includes('/c/')&&!/just a moment/i.test(document.title)",returnByValue:true});
  if(r.result?.value)break;await new Promise(r=>setTimeout(r,1000));
 }
 const rawCall=page.call.bind(page),pendingPosts=new Set();
 page.on('Fetch.requestPaused',e=>{if(e.request?.method==='POST'&&['/backend-api/conversation','/backend-api/f/conversation'].includes(new URL(e.request.url).pathname)){pendingPosts.add(e.requestId);console.log('PROBE_POST_SHAPE',JSON.stringify({path:new URL(e.request.url).pathname,hasPostData:!!e.request.postData,entries:e.request.postDataEntries?.length,keys:Object.keys(e.request),networkId:!!e.networkId,bodyLength:e.request.postData?.length}));}});
 page.call=async(method,params={},opts={})=>{
  if(method==='Page.navigate'||method==='Page.reload')navigations++;
  if(method==='Fetch.continueRequest'&&pendingPosts.has(params.requestId)){
   assert.ok(params.postData,'a probe POST must be rewritten before it can be fulfilled');
   patched=JSON.parse(Buffer.from(params.postData,'base64').toString());
   const result=await rawCall('Fetch.fulfillRequest',{requestId:params.requestId,responseCode:200,responseHeaders:[{name:'content-type',value:'application/json'}],body:Buffer.from('{"probe":true}').toString('base64')},opts);posts++;pendingPosts.delete(params.requestId);return result;
  }
  if(method==='Fetch.disable')for(const id of pendingPosts){await rawCall('Fetch.failRequest',{requestId:id,errorReason:'Aborted'});pendingPosts.delete(id);}
  const value=await rawCall(method,params,opts);
  if(method==='Fetch.failRequest')pendingPosts.delete(params.requestId);
  if(method==='Runtime.evaluate'&&params.expression.includes('async function readInputParent')&&typeof value.result?.value==='string')legacyTip=value.result.value;
  return value;
 };
 const evaluate=async expression=>{const r=await page.call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true},{timeoutMs:90000});if(r.exceptionDetails)throw Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text);return r.result?.value;};
 assert.equal(await evaluate('!window.__selfrunExtensionPage'),true);
 await evaluate(releasedV1({installationKey:'live-input-repair-probe'}));
 await evaluate("window.__probeCapturedFetch=window.fetch;window.__probeDocument=crypto.randomUUID();true");
 action=new ServerActionSession({session:page,installationKey:'live-input-repair-probe',allowedOrigins:['https://chatgpt.com'],initialUrl:await evaluate('location.href')});
 await action.run('terminalDispose');
 const operations=['model','thinking_effort','conversation_origin','service_tier'].map(path=>({op:'REMOVE',path}));
 await action.run('prepareInput',{prompt:'input repair fixture',operations});
 await action.run('installProfile');await action.run('profileConfigure',{operations});
 const id=new URL(url).pathname.split('/c/')[1];
 const body={conversation_id:id,parent_message_id:'obsolete-probe-parent',model:'remove-probe',messages:[{id:crypto.randomUUID(),author:{role:'user'},content:{parts:['input repair fixture']}}],preserved:'yes'};
 await evaluate(`window.__probeCapturedFetch('https://chatgpt.com/backend-api/conversation',{method:'POST',headers:{'content-type':'application/json'},body:${JSON.stringify(JSON.stringify(body))}}).then(r=>r.json())`);
 assert.equal(posts,1);assert.equal(patched.parent_message_id,legacyTip);assert.equal(patched.preserved,'yes');assert.ok(!('model'in patched));
 const documentId=await evaluate('window.__probeDocument');await action.run('terminalDispose');await action.run('prepareInput',{prompt:'next fixture',operations});
 assert.equal(await evaluate('window.__probeDocument'),documentId);assert.equal(navigations,1);
 await action.legacyInput.dispose();await action.run('terminalDispose');
 const v2=await evaluate(`(async()=>{let tip,body,reads=0;const real=window.fetch.bind(window);const parent=(${inputParentRuntime.toString()})({href:()=>location.href,nativeFetch:async(input,init)=>{const req=new Request(input,init);if(req.method==='POST'){body=await req.json();return Response.json({probe:true});}reads++;const response=await real(input,init);if(response.ok&&req.url.includes('/backend-api/conversation/')){const graph=await response.clone().json();tip=graph.current_node;}return response;}});parent.prepare('next fixture');await parent.fetch('https://chatgpt.com/backend-api/conversation',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({...${JSON.stringify(body)},messages:[{author:{role:'user'},content:{parts:['next fixture']}}]})});parent.dispose();return{latest:!!tip&&body.parent_message_id===tip,reads};})()`);
 assert.equal(v2.latest,true);
 console.log(JSON.stringify({legacyCapturedPostPatched:true,legacyLatestParent:true,v2LatestParent:true,helperRepairedWithoutReload:true,actualConversationPosts:0,probePostsFulfilled:posts,parentReads:v2.reads}));
} catch(error){console.error('PROBE_ERROR',error.stack);throw error;} finally {
 if(action)await action.dispose().catch(()=>{});else page?.close();
 if(targetId)await browser.call('Target.closeTarget',{targetId}).catch(()=>{});
 if(targetId){let absent=false;for(let n=0;n<30;n++){const {targetInfos}=await browser.call('Target.getTargets');if(!targetInfos.some(t=>t.targetId===targetId)){absent=true;break;}await new Promise(r=>setTimeout(r,100));}console.log('PROBE_TARGET_REMOVED',absent);}
 browser.close();
}
