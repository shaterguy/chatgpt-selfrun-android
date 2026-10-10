import test from 'node:test';import assert from 'node:assert/strict';import vm from 'node:vm';
import {contentRuntime} from '../src/browser/extension-page-source.mjs';
for(const kind of ['selfrun.content.snapshot','selfrun.content.recoveryInput'])test(`expired ${kind} never inspects the DOM`,()=>{
 let receive,samples=0;const nonce='fixture-content-nonce';const chrome={runtime:{id:'test',getURL:p=>'chrome-extension://test/'+p,sendMessage:async()=>{},onMessage:{addListener:fn=>receive=fn}}};
 const sample=()=>{samples++;return{composer:true};};
 vm.runInNewContext(`(${contentRuntime.toString()})(sample,sample)`,{chrome,crypto:{randomUUID:()=>nonce},sample,MutationObserver:class{observe(){}disconnect(){}},document:{readyState:'complete'},location:{href:'https://chatgpt.com/c/owned'},addEventListener(){},Date});
 const sender={id:'test',url:'chrome-extension://test/controller.html'};receive({kind:'selfrun.content.activate',nonce},sender,()=>{});
 let response;receive({kind,nonce,deadlineAt:Date.now()-1},sender,r=>response=r);assert.equal(samples,0);assert.equal(response.ok,false);
 receive({kind,nonce,deadlineAt:Date.now()+1000},sender,r=>response=r);assert.equal(samples,1);assert.equal(response.ok,true);
});
