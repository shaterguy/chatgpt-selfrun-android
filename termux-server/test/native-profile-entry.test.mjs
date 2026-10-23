import test from 'node:test';
import assert from 'node:assert/strict';
import {ChatGptBrowser,requestProfileDocumentStartSource} from '../src/browser/chatgpt.mjs';
import vm from 'node:vm';
const operations=[{op:'SET',path:'model',value:'gpt-6-thinking'},{op:'SET',path:'thinking_effort',value:'max'},{op:'REMOVE',path:'conversation_origin'},{op:'REMOVE',path:'service_tier'}];
test('fresh preparation saves native preference before project entry and never verifies it afterward',async()=>{
 const calls=[];const session={transport:'extension',close(){},async run(op,payload){calls.push([op,payload]);if(op==='recoveryInput')return {composer:true};return true;}};
 const chromium={createTarget:async()=>({id:'owned'}),connectTarget:async()=>session};
 await new ChatGptBrowser(chromium,{}).prepare({projectUrl:'https://chatgpt.com/g/project/project',profileOperations:operations,guard:()=>true});
 assert.deepEqual(calls.filter(x=>x[0]==='navigate').map(x=>x[1].url),['https://chatgpt.com/?surface=chat&disable_auto_send=1','https://chatgpt.com/g/project/project']);
 assert.equal(calls.filter(x=>x[0]==='prepareProfile').length,1);
 const save=calls.findIndex(x=>x[0]==='prepareProfile');
 assert.deepEqual(calls.slice(save+1).map(x=>x[0]),['navigate']);
 assert.deepEqual(calls[save][1].operations,operations);
});
test('request profile no longer rewrites a conversation request',async()=>{
 const requests=[];const native=async(input,init)=>{requests.push(init.body);return true;};
 const window={fetch:native};class XHR {open(){}send(){}}
 vm.runInNewContext(requestProfileDocumentStartSource(),{window,XMLHttpRequest:XHR,Set,WeakMap,URL,Request});
 window.__selfrunRequestProfileEngine.configure([['set','model','gpt-6-thinking'],['set','thinking_effort','max'],['remove','conversation_origin'],['remove','service_tier']]);
 const body=JSON.stringify({model:'native-selected',thinking_effort:'native-effort',messages:[]});
 await window.fetch('https://chatgpt.com/backend-api/conversation',{method:'POST',body});
 assert.equal(window.fetch,native);assert.deepEqual(requests,[body]);
});
