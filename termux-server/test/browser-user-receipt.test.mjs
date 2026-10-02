import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { ChatGptBrowser } from '../src/browser/chatgpt.mjs';

const recorded=JSON.parse(readFileSync(new URL('./fixtures/chatgpt-user-message-controls.json',import.meta.url),'utf8'));
const norm=value=>String(value||'').replace(/\s+/g,' ').trim();

// Minimal DOM reconstruction of the recorded user-content/control subtree.
// The production readSubmission expression runs unmodified in the VM.
class Element {
 constructor(spec,prompt,parent=null) {
  this.tagName=spec.tag.toUpperCase();this.attrs=spec.attrs||{};this.parentElement=parent;
  this.text=spec.text==='$PROMPT'?prompt:(spec.text||'');this.removed=false;
  this.children=(spec.children||[]).map(child=>new Element(child,prompt,this));
 }
 get textContent(){return [this.text,...this.children.filter(n=>!n.removed).map(n=>n.textContent)].filter(Boolean).join(' ');}
 get innerText(){return this.textContent;}
 getAttribute(name){return this.attrs[name]??null;}
 get id(){return this.attrs.id||'';}
 cloneNode(){return new Element(this.toSpec(),'');}
 toSpec(){return {tag:this.tagName,attrs:{...this.attrs},text:this.text,children:this.children.filter(n=>!n.removed).map(n=>n.toSpec())};}
 remove(){this.removed=true;}
 matches(selector){
  const attr=selector.match(/^\[([^=\]]+)="([^"]+)"\]$/);
  if(attr)return this.getAttribute(attr[1])===attr[2];
  return this.tagName===selector.toUpperCase();
 }
 querySelectorAll(selector){
  const selectors=selector.split(',');const found=[];
  const visit=node=>{for(const child of node.children){if(child.removed)continue;if(selectors.some(s=>child.matches(s)))found.push(child);visit(child);}};
  visit(this);return found;
 }
}
function fixture({prompt='p'.repeat(805),extraContent='',persisted=true}={}) {
 const spec=structuredClone(recorded.tree);
 if(extraContent)spec.children.splice(1,0,{tag:'DIV',attrs:{},text:extraContent});
 const user=new Element(spec,prompt);
 let reloads=0;
 const probe=()=>({url:'https://chatgpt.com/c/owned',composer:true,readyState:'complete',streaming:false,
  userCount:persisted||!reloads?1:0,userMessageId:persisted||!reloads?'data-message-id:persisted-user':null});
 const document={querySelectorAll:()=>persisted||!reloads?[user]:[]};
 const session={async call(method,params={}){
  if(method==='Page.reload'){reloads++;return {};}
  assert.equal(method,'Runtime.evaluate');
  if(params.expression.includes('const matched='))return {result:{value:vm.runInNewContext(params.expression,{document})}};
  return {result:{value:probe()}};
 }};
 const browser=new ChatGptBrowser(null,{navigationTimeoutMs:100});
 const read=()=>browser.readSubmission({session,prompt,conversationUrl:'https://chatgpt.com/c/owned',intent:{baseline:{userCount:0}}});
 return {read,user,prompt,reloads:()=>reloads};
}
test('persisted exact user content is confirmed despite the recorded Show more wrapper',async()=>{
 const f=fixture();
 assert.equal(norm(f.user.innerText).length,recorded.observed_comparison.actual_length);
 assert.equal(norm(f.prompt).length,recorded.observed_comparison.expected_length);
 const receipt=await f.read();
 assert.equal(f.reloads(),1);
 assert.equal(receipt.state,'CONFIRMED');
});
test('literal UI label words in user content are preserved during receipt comparison',async()=>{
 const f=fixture({prompt:'Keep the literal phrases Show more and 더 보기 in this user instruction.'});
 assert.equal((await f.read()).state,'CONFIRMED');
});
test('additional substantive message text cannot be accepted as a matching prompt prefix',async()=>{
 const f=fixture({extraContent:'different instruction'});
 assert.equal((await f.read()).state,'UNKNOWN');
});
test('removing controls cannot turn a disappeared optimistic bubble into accepted submission',async()=>{
 const f=fixture({persisted:false});
 assert.equal((await f.read()).state,'UNKNOWN');
 assert.equal(f.reloads(),1);
});
