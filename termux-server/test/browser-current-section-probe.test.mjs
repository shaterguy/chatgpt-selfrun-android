import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {ChatGptBrowser} from '../src/browser/chatgpt.mjs';
import {sameCursor,cursor} from '../src/lifecycle.mjs';
import {fixture as controllerFixture} from './helpers/lifecycle-fixture.mjs';

// Synthetic text only. The current layout's SECTION/tag/attribute names come from
// read-only WS877V evidence 05. Literal data-turn values are compatibility cases,
// not values captured by that diagnostic. This DOM implements actual ancestry,
// selector matching, document order and root-scoped text walking used by the probe.
class Element {
  constructor(tag,attrs={},...children){this.tagName=tag.toUpperCase();this.attrs={...attrs};this.children=[];this.parentElement=null;this.style={display:'block',visibility:'visible',opacity:'1'};this.isConnected=true;this.offsetParent={};for(const child of children)this.append(child);}
  append(child){if(typeof child==='string')child={nodeType:3,nodeValue:child,parentElement:null};child.parentElement=this;this.children.push(child);return child;}
  getAttribute(name){return this.attrs[name]??null;}
  get id(){return this.attrs.id||'';}
  get dataset(){return {testid:this.attrs['data-testid']};}
  get innerText(){return this.children.map(child=>child.nodeType===3?child.nodeValue:child.innerText).join(' ');}
  get textContent(){return this.innerText;}
  get title(){return this.attrs.title||'';}
  contains(node){for(let at=node;at;at=at.parentElement)if(at===this)return true;return false;}
  closest(selector){for(let at=this;at;at=at.parentElement)if(matches(at,selector))return at;return null;}
  querySelectorAll(selector){return descendants(this).filter(node=>node.nodeType!==3&&matches(node,selector));}
  querySelector(selector){return this.querySelectorAll(selector)[0]||null;}
  compareDocumentPosition(other){if(!other)throw new TypeError('compareDocumentPosition requires a Node');let root=this;while(root.parentElement)root=root.parentElement;const all=[root,...descendants(root)];return all.indexOf(this)<all.indexOf(other)?4:2;}
}
const descendants=root=>root.children.flatMap(child=>[child,...(child.children?descendants(child):[])]);
function simpleMatch(element,selector){
  const tag=selector.match(/^[a-z][a-z0-9-]*/i)?.[0];if(tag&&element.tagName!==tag.toUpperCase())return false;
  const id=selector.match(/#([\w-]+)/)?.[1];if(id&&element.id!==id)return false;
  const cls=selector.match(/\.([\w-]+)/)?.[1];if(cls&&!String(element.attrs.class||'').split(/\s+/).includes(cls))return false;
  for(const match of selector.matchAll(/\[([^\]\s~|^$*!=]+)(?:([\^$*]?=)["']?([^\]"']*)["']?)?\]/g)){
    const value=element.getAttribute(match[1]);if(value===null)return false;if(!match[2])continue;
    if(match[2]==='='&&value!==match[3]||match[2]==='^='&&!value.startsWith(match[3])||match[2]==='$='&&!value.endsWith(match[3])||match[2]==='*='&&!value.includes(match[3]))return false;
  }
  return true;
}
function matches(element,selectors){return selectors.split(',').some(selector=>{
  const parts=selector.trim().split(/\s+/);if(!simpleMatch(element,parts.pop()))return false;
  let ancestor=element.parentElement;while(parts.length){const part=parts.pop();while(ancestor&&!simpleMatch(ancestor,part))ancestor=ancestor.parentElement;if(!ancestor)return false;ancestor=ancestor.parentElement;}return true;
});}
const el=(...args)=>new Element(...args);
function fixture({legacy=false,turnAttribute='assistant',bubble=true,userOnly=false,fallback=false}={}){
  const main=el('main'),body=el('body',{},main),composer=el('textarea',{id:'prompt-textarea'});main.append(el('form',{},composer));
  const user=el('div',{'data-message-author-role':'user','data-message-id':'user-1'},'Synthetic user input');
  const userSection=el('section',{'data-turn-id':'user-turn-1','data-turn-id-container':'user-container','data-testid':'conversation-turn-1','data-turn':'user'},user);main.append(userSection);
  const attrs=legacy?{'data-turn-key':'response-1'}:{'data-turn-id':'response-1','data-turn-id-container':'assistant-container','data-testid':'conversation-turn-2',...(turnAttribute===null?{}:{'data-turn':turnAttribute})};
  const response=el(legacy?'article':'section',attrs);
  const assistant=el('div',{'data-message-author-role':'assistant','data-message-id':'assistant-1'},'Synthetic assistant reply');
  const thinking=el('div',{},'Inspecting synthetic input');
  if(!userOnly){if(fallback)main.append(assistant);else{main.append(response);response.append(bubble?assistant:thinking);}}
  const document={title:'Synthetic probe fixture',readyState:'complete',body,querySelectorAll:selector=>body.querySelectorAll(selector),
    createTreeWalker(root){const nodes=descendants(root).filter(node=>node.nodeType===3);let index=0;return {currentNode:null,nextNode(){this.currentNode=nodes[index++];return !!this.currentNode;}};}};
  const calls=[];const session={call:async(method,params)=>{calls.push(method);assert.equal(method,'Runtime.evaluate','probe must remain read-only');return {result:{value:vm.runInNewContext(params.expression,{document,location:{href:'https://chatgpt.com/c/synthetic',pathname:'/c/synthetic'},NodeFilter:{SHOW_TEXT:4},Node:{DOCUMENT_POSITION_FOLLOWING:4,DOCUMENT_POSITION_PRECEDING:2},getComputedStyle:e=>e.style})}};}};
  const browser=new ChatGptBrowser(null,{});
  return {main,response,assistant,thinking,user,userSection,calls,session,browser,probe:()=>browser.livenessSnapshot({session}),setText(node,text){node.children=[];node.append(text);}};
}

test('current production SECTION shape yields response identity and content from the exact probe',async()=>{
  const f=fixture();f.setText(f.assistant,'A'.repeat(221));const p=await f.probe();
  assert.equal(p.assistantCount,1);assert.equal(p.assistantTextLength,221);assert.equal(p.assistantMessageId,'data-message-id:assistant-1');
  assert.equal(p.responseTurnId,'response-1');assert.equal(p.responseTextLength,221);assert.ok(p.responseFingerprint);assert.deepEqual(f.calls,['Runtime.evaluate']);
});
test('current SECTION response detects same-length assistant content changes',async()=>{
  const f=fixture();f.setText(f.assistant,'first response');const before=await f.probe();f.setText(f.assistant,'other response');const after=await f.probe();
  assert.equal(before.responseTextLength,after.responseTextLength);assert.notEqual(before.responseFingerprint,after.responseFingerprint);assert.equal(sameCursor(before,after),false);
});
for(const turnAttribute of ['assistant',null])test('current SECTION thinking/tool progress before a bubble exists: '+String(turnAttribute),async()=>{
  const f=fixture({bubble:false,turnAttribute});const tool=el('button',{'aria-label':'Read synthetic source'},'Reading synthetic source');f.response.append(tool);
  const before=await f.probe();f.setText(f.thinking,'Inspecting different input');const after=await f.probe();
  assert.equal(before.assistantCount,0);assert.equal(before.responseTurnId,'response-1');assert.ok(before.responseTextLength>0);assert.equal(before.responseTextLength,after.responseTextLength);assert.notEqual(before.responseFingerprint,after.responseFingerprint);
});
test('latest user-only section must not reuse an earlier assistant section',async()=>{
  const f=fixture();f.main.append(el('section',{'data-turn-id':'user-turn-2','data-testid':'conversation-turn-3','data-turn':'user'},el('div',{'data-message-author-role':'user','data-message-id':'user-2'},'New pending input')));
  const p=await f.probe();assert.equal(p.responseTurnId,null);assert.equal(p.responseTextLength,0);assert.equal(p.responseFingerprint,'');
});
test('an explicit user SECTION without a bubble must not become response progress',async()=>{
  const f=fixture({userOnly:true});f.main.append(el('section',{'data-turn-id':'pending-user','data-testid':'conversation-turn-2','data-turn':'user'},'Unrendered user text'));
  const p=await f.probe();assert.equal(p.responseTurnId,null);assert.equal(p.responseTextLength,0);assert.equal(p.responseFingerprint,'');
});
test('a descendant user bubble excludes an otherwise unlabelled current SECTION',async()=>{
  const f=fixture({userOnly:true});f.main.append(el('section',{'data-turn-id':'pending-user','data-testid':'conversation-turn-2'},el('div',{'data-message-author-role':'user','data-message-id':'user-2'},'Pending user input')));
  const p=await f.probe();assert.equal(p.responseTurnId,null);assert.equal(p.responseTextLength,0);
});
test('legacy data-turn-key response remains supported',async()=>{
  const f=fixture({legacy:true});const p=await f.probe();assert.equal(p.responseTurnId,'response-1');assert.equal(p.responseTextLength,'Synthetic assistant reply'.length);assert.ok(p.responseFingerprint);
});
test('assistant-node fallback without any supported wrapper detects content changes',async()=>{
  const f=fixture({fallback:true});f.setText(f.assistant,'first response');const before=await f.probe();f.setText(f.assistant,'other response');const after=await f.probe();
  assert.equal(before.responseTurnId,null);assert.equal(before.responseTextLength,14);assert.notEqual(before.responseFingerprint,after.responseFingerprint);
});
test('arbitrary main SECTION is not a response wrapper',async()=>{
  const f=fixture({userOnly:true});f.main.append(el('section',{'data-turn-id':'unrelated'},'Unrelated dashboard text'));const p=await f.probe();assert.equal(p.responseTurnId,null);assert.equal(p.responseTextLength,0);
});
test('elapsed-only assistant timer changes do not change the effective progress cursor',async()=>{
  const f=fixture();f.setText(f.assistant,'Thinking for 9 seconds');const before=await f.probe();f.setText(f.assistant,'Thinking for 10 seconds');const after=await f.probe();
  assert.equal(sameCursor(before,after),true);assert.equal(after.responseTextLength,0);
});
test('current SECTION retains hidden/user/control exclusion while keeping real tool text',async()=>{
  const f=fixture({bubble:false});f.response.append(el('span',{'aria-hidden':'true'},'Hidden secret synthetic text'));const hidden=el('div',{},'Display-none synthetic text');hidden.style.display='none';f.response.append(hidden);
  f.response.append(el('button',{'aria-label':'Copy'},'Copy'));f.response.append(el('form',{},'Composer data'));f.response.append(el('button',{'aria-label':'Read synthetic source'},'Tool progress'));
  const p=await f.probe();assert.equal(p.responseTextLength,'Inspecting synthetic input Tool progress'.length);
});


test('legacy combined user-response wrapper keeps thinking progress before assistant bubble',async()=>{
  const f=fixture({legacy:true,bubble:false});f.main.children=f.main.children.filter(node=>node!==f.userSection);f.response.children.unshift(f.userSection);f.userSection.parentElement=f.response;
  const p=await f.probe();assert.equal(p.assistantCount,0);assert.equal(p.responseTurnId,'response-1');assert.equal(p.responseTextLength,'Inspecting synthetic input'.length);
});


test('unchanged pre-answer thinking and minute clock labels recover once at ten minutes',async()=>{
  const originalNow=Date.now;let now=1000000;Date.now=()=>now;
  const dom=fixture({bubble:false,turnAttribute:null});dom.main.append(el('button',{'data-testid':'stop-button'}));
  const clock=el('span',{},'Thinking for 0 minutes');dom.response.append(clock);
  const f=controllerFixture();f.controller.config.stallAfterMs=600000;f.controller.config.recoveryPrompt='configured thinking continuation';
  try{
    f.probe=await dom.probe();await f.existing();await f.observe({});const initialProgress=f.record().last_progress_at;
    assert.equal(f.probe.assistantCount,0);assert.equal(f.probe.streaming,true);assert.ok(f.probe.responseTextLength>0);
    for(let minute=1;minute<=9;minute++){
      now+=60000;dom.setText(clock,'Thinking for '+minute+' minutes');f.probe=await dom.probe();await f.observe({status:'RUNNING'});
      assert.equal(f.sends,0,'a one-minute observation is not a ten-minute stall');assert.equal(f.record().last_progress_at,initialProgress,'clock-only changes and probes are not progress');
    }
    now+=60000;dom.setText(clock,'Thinking for 10 minutes');f.probe=await dom.probe();await f.observe({status:'RUNNING'});
    assert.equal(f.sends,1);assert.equal(f.quiesces,1);assert.equal(f.sent[0].prompt,'configured thinking continuation');assert.equal(f.sent[0].kind,'recovery');assert.equal(f.prepares,0);
    for(let minute=1;minute<=10;minute++){now+=60000;await f.observe({status:'RUNNING'});}
    assert.equal(f.sends,1,'unchanged confirmed recovery must not send again');
  }finally{await f.controller.quiesceForStandby();Date.now=originalNow;}
});

test('same-length pre-answer tool progress resets the ten-minute controller deadline',async()=>{
  const originalNow=Date.now;let now=1000000;Date.now=()=>now;
  const dom=fixture({bubble:false,turnAttribute:null});dom.main.append(el('button',{'data-testid':'stop-button'}));
  const tool=el('button',{'aria-label':'Read synthetic source'},'Reading file A');dom.response.append(tool);
  const f=controllerFixture();f.controller.config.stallAfterMs=600000;
  try{
    f.probe=await dom.probe();await f.existing();await f.observe({});const before=f.probe;
    now+=540000;dom.setText(tool,'Reading file B');f.probe=await dom.probe();await f.observe({status:'RUNNING'});
    assert.equal(f.probe.assistantCount,0);assert.equal(f.probe.responseTextLength,before.responseTextLength);assert.notEqual(f.probe.responseFingerprint,before.responseFingerprint);assert.equal(f.record().last_progress_at,now);
    now+=540000;await f.observe({status:'RUNNING'});assert.equal(f.sends,0,'eighteen minutes since start is only nine minutes since real tool progress');
    now+=60000;await f.observe({status:'RUNNING'});assert.equal(f.sends,1);assert.equal(f.quiesces,1);
  }finally{await f.controller.quiesceForStandby();Date.now=originalNow;}
});


test('legacy wrapper excludes explicit pending user SECTION text before its bubble',async()=>{
  const f=fixture({userOnly:true});f.main.append(el('article',{'data-turn-key':'legacy-pending'},el('section',{'data-turn-id':'pending-user','data-testid':'conversation-turn-2','data-turn':'user'},'New pending user input')));
  const p=await f.probe();assert.equal(p.responseTextLength,0);assert.equal(p.responseFingerprint,'');
});
function appendPendingUser(f){return f.main.append(el('section',{'data-turn-id':'user-turn-2','data-testid':'conversation-turn-3','data-turn':'user'},el('div',{'data-message-author-role':'user','data-message-id':'user-2'},'New pending input')));}
function alterOldAssistant(f){f.setText(f.assistant,'An older response expanded, without any change to current thinking');f.assistant.attrs['data-message-id']='old-remounted';f.response.append(el('div',{'data-message-author-role':'assistant','data-message-id':'old-extra'},'Another old response fragment'));}
for(const thinking of [false,true])test('prior-assistant text, identity and count do not change current cursor: thinking='+thinking,async()=>{
  const f=fixture();appendPendingUser(f);
  if(thinking)f.main.append(el('section',{'data-turn-id':'response-2','data-testid':'conversation-turn-4'},'Inspecting current input'));
  const before=await f.probe();alterOldAssistant(f);const after=await f.probe();
  assert.equal(sameCursor(before,after),true);assert.equal(after.assistantCount,2);assert.equal(after.responseAssistantCount,0);assert.equal(after.responseAssistantTextLength,0);assert.equal(after.responseAssistantMessageId,null);
  assert.equal(after.responseTurnId,thinking?'response-2':null);
});
test('multi-turn semantic receipt confirms with old assistant present and current bubble absent',async()=>{
  const f=fixture();appendPendingUser(f);f.main.append(el('section',{'data-turn-id':'response-2','data-testid':'conversation-turn-4'},'Inspecting current input'));
  const uuid='12345678-1234-4123-8123-123456789abc';
  const intent={id:'intent-2',receipt_generation:'generation-2',prompt:'Continue current thinking'};
  const receipt={version:1,intent_id:intent.id,generation:intent.receipt_generation,request_id:'generation-2:1',message_id:uuid,conversation_id:'synthetic',ack:'cot_token',invalid:null,released:true};
  const original=f.session.call;f.session.call=async(method,params)=>params.expression.includes('window.__selfrunReceiptObserver?.snapshot(')?{result:{value:receipt}}:original(method,params);
  const result=await f.browser.readSubmission({session:f.session,intent,prompt:intent.prompt,conversationUrl:'https://chatgpt.com/c/synthetic',guard:()=>true,onRequest:async evidence=>Object.assign(intent,evidence)});
  assert.equal(result.state,'CONFIRMED');assert.equal(result.messageId,uuid);assert.equal(result.probe.assistantCount,1);assert.equal(result.probe.assistantMessageId,'data-message-id:assistant-1');assert.equal(result.probe.responseAssistantCount,0);assert.equal(result.probe.responseAssistantMessageId,null);assert.equal(result.probe.responseTurnId,'response-2');assert.ok(result.probe.responseTextLength>0);
});


test('durable cursor prefers scoped assistant metrics and preserves legacy probe fallback',()=>{
  const legacy={assistantCount:7,assistantTextLength:100,assistantMessageId:'old-id',responseTurnId:'r',responseTextLength:20,responseFingerprint:'x',streaming:true};
  assert.equal(cursor(legacy).assistantCount,7);assert.equal(cursor(legacy).assistantMessageId,'old-id');
  const effective=cursor({...legacy,responseAssistantCount:0,responseAssistantTextLength:0,responseAssistantMessageId:null});
  assert.equal(effective.assistantCount,0);assert.equal(effective.assistantTextLength,0);assert.equal(effective.assistantMessageId,null);
  assert.deepEqual(cursor({...legacy,responseAssistantCount:undefined,responseAssistantMessageId:undefined}),cursor(legacy));
});
test('browser monitor ignores prior assistant changes using the same scoped cursor as controller',async()=>{
  const f=fixture();appendPendingUser(f);f.main.append(el('section',{'data-turn-id':'response-2','data-testid':'conversation-turn-4'},'Inspecting current input'));
  const baseline=await f.probe(),original=f.session.call,abort=new AbortController(),activities=[];let calls=0;
  f.session.call=async(...args)=>{if(++calls===2)alterOldAssistant(f);return original(...args);};
  const browser=new ChatGptBrowser(null,{probeIntervalMs:0,stallAfterMs:600000,conversationStateGraceMs:5000});
  await assert.rejects(browser.monitor({session:f.session,baseline,signal:abort.signal,livenessGate:()=>({state:'RUNNING',epoch:1}),onActivity:async p=>{activities.push(p);if(activities.length===2)abort.abort(new Error('fixture complete'));}}),/fixture complete/);
  assert.equal(activities[1].assistantCount,2);assert.equal(activities[1].responseAssistantCount,0);assert.equal(activities[1].progressDetected,false);assert.equal(sameCursor(activities[0],activities[1]),true);
});
test('prior assistant changes cannot postpone the current thinking recovery deadline',async()=>{
  const originalNow=Date.now;let now=1000000;Date.now=()=>now;
  const dom=fixture();appendPendingUser(dom);dom.main.append(el('section',{'data-turn-id':'response-2','data-testid':'conversation-turn-4'},'Inspecting current input'));dom.main.append(el('button',{'data-testid':'stop-button'}));
  const f=controllerFixture();f.controller.config.stallAfterMs=600000;
  try{
    f.probe=await dom.probe();await f.existing();await f.observe({});const initial=f.record().last_progress_at;
    now+=540000;alterOldAssistant(dom);f.probe=await dom.probe();await f.observe({status:'RUNNING'});assert.equal(f.record().last_progress_at,initial);assert.equal(f.sends,0);
    now+=60000;await f.observe({status:'RUNNING'});assert.equal(f.sends,1);assert.equal(f.quiesces,1);
  }finally{await f.controller.quiesceForStandby();Date.now=originalNow;}
});
