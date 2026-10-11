import test from 'node:test';
import assert from 'node:assert/strict';
import {ChatGptBrowser} from '../src/browser/chatgpt.mjs';

const snapshot={url:'https://chatgpt.com/g/example/project',readyState:'complete',composer:true,
  streaming:false,stopButtonVisible:false,userCount:0,userTextLength:0,assistantCount:0,
  assistantTextLength:0,responseTurnId:null,responseTextLength:0,responseFingerprint:''};
function fixture(){
  const calls=[];let current={...snapshot};
  const session={transport:'extension',close(){calls.push(['close']);},
    async run(operation,payload={}){calls.push([operation,payload]);
      if(operation==='navigate'){current={...current,url:payload.url};return {};}
      if(operation==='snapshot'||operation==='progress'||operation==='recoveryInput')return {...current};
      if(operation==='input')return {status:'READY',length:payload.prompt.length};
      if(operation==='sendReady')return {ready:true,status:'SEND_FOUND'};
      if(operation==='submit'){current={...current,url:'https://chatgpt.com/c/extension-synthetic',userCount:1};return {status:'SUBMITTED'};}
      return true;
    },call(){assert.fail('extension workflow must not issue a CDP command');}};
  const chromium={createTarget:async()=>({id:'ext:7',type:'page',url:'about:blank'}),connectTarget:async()=>session,
    closeTarget:async()=>true,listExistingTargets:async()=>[],isOwnedTarget:id=>id==='ext:7'};
  return {calls,session,chromium,browser:new ChatGptBrowser(chromium,{navigationTimeoutMs:1000}),set(p){current={...current,...p};}};
}
test('extension liveness snapshot uses a named operation without CDP',async()=>{
  const f=fixture();assert.deepEqual(await f.browser.livenessSnapshot({session:f.session}),snapshot);
  assert.deepEqual(f.calls,[['snapshot',{}]]);
});
test('extension prepare initializes and navigates a new owned target without CDP',async()=>{
  const f=fixture(),seen=[];
  const result=await f.browser.prepare({projectUrl:snapshot.url,onTarget:t=>seen.push(t.id),guard:()=>true});
  assert.equal(result.target.id,'ext:7');assert.deepEqual(seen,['ext:7']);
  assert.equal(f.calls.filter(x=>x[0]==='navigate').length,1);
  assert.equal(f.calls.find(x=>x[0]==='navigate')[1].url,snapshot.url);
  assert.deepEqual(result.baseline,{});
});
test('extension submission preserves profile and immutable intent through named operations',async()=>{
  const f=fixture(),prompt='Synthetic prompt';
  const operations=[{op:'SET',path:'model',value:'test-model'},{op:'SET',path:'thinking_effort',value:'max'},
    {op:'REMOVE',path:'conversation_origin'},{op:'REMOVE',path:'service_tier'}];
  const result=await f.browser.submitIntent({session:f.session,prompt,kind:'initial',
    intent:{id:'intent1',receipt_generation:'generation1',prompt},profileOperations:operations,guard:()=>true});
  assert.deepEqual(result,{submitted:true});
  assert.equal(f.calls.filter(x=>x[0]==='submit').length,1);
  assert.deepEqual(f.calls.find(x=>x[0]==='profileConfigure')[1].operations,operations);
  assert.equal(f.calls.find(x=>x[0]==='receiptArm')[1].key.prompt,prompt);
});
test('extension automatic observation recovery cannot create a replacement conversation tab',async()=>{
  const f=fixture();f.chromium.recoveryNavigationDisabled=true;let created=0;
  f.chromium.createTarget=async()=>{created++;throw Error('must not navigate');};
  await assert.rejects(f.browser.resume({conversationUrl:'https://chatgpt.com/c/original',automaticRecovery:true,guard:()=>true}),e=>e.code==='PAGE_UNAVAILABLE');
  assert.equal(created,0);
});
