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
 get textContent(){return [this.text,...this.children.filter(n=>!n.removed).map(n=>n.textContent)].join('');}
 get innerText(){return [this.text,...this.children.filter(n=>!n.removed).map(n=>n.innerText)].filter(Boolean).join(' ');}
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
function fixture({prompt='p'.repeat(805),extraContent='',persisted=true,autoCommit=true,
 afterCommit={},navigationTimeoutMs=100,reloadError=null}={}) {
 const spec=structuredClone(recorded.tree);
 if(extraContent)spec.children.splice(1,0,{tag:'DIV',attrs:{},text:extraContent});
 const user=new Element(spec,prompt);
 let reloads=0,commits=0,shown=true,onEvidence=null;
 let frame={id:'main-frame',loaderId:'document-0',url:'https://chatgpt.com/c/owned'};
 let fields={composer:true,readyState:'complete',streaming:false};
 const calls=[],listeners=new Map();
 const probe=()=>({url:frame.url,...fields,userCount:shown?1:0,
  userMessageId:shown?'data-message-id:persisted-user':null});
 const emit=(name,params)=>{for(const fn of listeners.get(name)||[])fn(params);};
 const commit=(overrides={})=>{
  commits++;shown=persisted;fields={...fields,...afterCommit};
  frame={...frame,loaderId:'document-'+commits,...overrides};
  emit('Page.frameNavigated',{frame:{...frame}});
 };
 const document={querySelectorAll:()=>shown?[user]:[]};
 const session={
  on(name,fn){if(!listeners.has(name))listeners.set(name,new Set());listeners.get(name).add(fn);return()=>listeners.get(name).delete(fn);},
  async call(method,params={}){
   calls.push({method,params});
   if(method==='Page.getFrameTree')return {frameTree:{frame:{...frame}}};
   if(method==='Page.reload'){
    reloads++;
    if(reloadError)throw reloadError;
    if(autoCommit)commit();
    return {};
   }
   assert.equal(method,'Runtime.evaluate');
   if(params.expression.includes('const matched=')){
    const value=vm.runInNewContext(params.expression,{document});
    onEvidence?.();
    return {result:{value}};
   }
   return {result:{value:probe()}};
  },
 };
 const browser=new ChatGptBrowser(null,{navigationTimeoutMs});
 const read=(options={})=>browser.readSubmission({session,prompt,conversationUrl:'https://chatgpt.com/c/owned',
  intent:{kind:'initial',baseline:{userCount:0}},initialReadback:true,...options});
 return {read,user,prompt,calls,commit,emit,probe,
  show(value){shown=value;},onEvidence(fn){onEvidence=fn;},
  listenerCount:()=>[...listeners.values()].reduce((sum,set)=>sum+set.size,0),reloads:()=>reloads};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
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

test('initial receipt accepts a persisted user while composer and assistant UI are unavailable',async()=>{
 const f=fixture({afterCommit:{composer:false,readyState:'loading',assistantCount:0,assistantMessageId:null,responseTextLength:0}});
 assert.equal((await f.read()).state,'CONFIRMED');
 assert.equal(f.listenerCount(),0);
});
test('reload acknowledgement cannot confirm the old optimistic document before a new loader commits',async()=>{
 const f=fixture({autoCommit:false,persisted:false});
 let settled=false;
 const reading=f.read().finally(()=>{settled=true;});
 await flush();
 assert.equal(settled,false,'Page.reload acknowledgement is not persisted-message evidence');
 f.commit();
 assert.equal((await reading).state,'UNKNOWN');
 assert.equal(f.listenerCount(),0);
});
test('a delayed main-frame commit can confirm exact persisted content without a composer',async()=>{
 const f=fixture({autoCommit:false,afterCommit:{composer:false}});
 let settled=false;
 const reading=f.read().finally(()=>{settled=true;});
 await flush();assert.equal(settled,false);
 f.commit();
 assert.equal((await reading).state,'CONFIRMED');
});
test('iframe commits and same-loader events cannot substitute for a fresh main document',async()=>{
 const f=fixture({autoCommit:false});
 let settled=false;
 const reading=f.read().finally(()=>{settled=true;});
 await flush();
 f.emit('Page.frameNavigated',{frame:{id:'child-frame',parentId:'main-frame',loaderId:'child-new'}});
 f.emit('Page.frameNavigated',{frame:{id:'main-frame',loaderId:'document-0'}});
 await flush();assert.equal(settled,false);
 f.commit();assert.equal((await reading).state,'CONFIRMED');
});
test('persisted user content can finish rendering after the fresh document commits',async()=>{
 const f=fixture({persisted:false,afterCommit:{composer:false,readyState:'loading'},navigationTimeoutMs:600});
 const reading=f.read();
 await flush();f.show(true);
 assert.equal((await reading).state,'CONFIRMED');
});
for(const destination of ['https://chatgpt.com/c/other','https://chatgpt.com/auth/login']) {
 test('fresh readback refuses a redirected document: '+destination,async()=>{
  const f=fixture({autoCommit:false});const reading=f.read();
  await flush();f.commit({url:destination});
  assert.equal((await reading).state,'UNKNOWN');
  assert.equal(f.listenerCount(),0);
 });
}
test('navigation timeout leaves an optimistic message UNKNOWN without resending',async()=>{
 const f=fixture({autoCommit:false});
 assert.equal((await f.read()).state,'UNKNOWN');
 assert.equal(f.listenerCount(),0);
 assert.equal(f.calls.some(c=>/send\.click|Input\./.test(c.params.expression||c.method)),false);
});
test('abort before readback does not reload',async()=>{
 const f=fixture();const abort=new AbortController();const reason=new Error('stopped');abort.abort(reason);
 await assert.rejects(f.read({signal:abort.signal}),e=>e===reason);
 assert.equal(f.reloads(),0);
});
test('abort while waiting for the main document rejects and removes the listener',async()=>{
 const f=fixture({autoCommit:false});const abort=new AbortController();const reason=new Error('stopped');
 const reading=f.read({signal:abort.signal});
 await flush();abort.abort(reason);
 await assert.rejects(reading,e=>e===reason);
 assert.equal(f.listenerCount(),0);
});
test('abort during content readback cannot return a positive receipt',async()=>{
 const f=fixture();const abort=new AbortController();const reason=new Error('stopped');
 f.onEvidence(()=>abort.abort(reason));
 await assert.rejects(f.read({signal:abort.signal}),e=>e===reason);
});
test('navigation during content readback cannot reuse the previous document evidence',async()=>{
 const f=fixture();f.onEvidence(()=>f.commit({url:'https://chatgpt.com/c/other'}));
 assert.equal((await f.read()).state,'UNKNOWN');
});
test('reload failure is UNKNOWN and releases its navigation listener',async()=>{
 const f=fixture({reloadError:new Error('navigation failed')});
 assert.equal((await f.read()).state,'UNKNOWN');assert.equal(f.listenerCount(),0);
});
test('repeated initial readbacks never stage or resend the submitted input',async()=>{
 const f=fixture({afterCommit:{composer:false}});
 for(let i=0;i<3;i++)assert.equal((await f.read()).state,'CONFIRMED');
 assert.equal(f.reloads(),3);
 assert.equal(f.calls.every(c=>['Page.getFrameTree','Page.reload','Runtime.evaluate'].includes(c.method)),true);
 assert.equal(f.calls.some(c=>/send\.click|execInsert|configure\(/.test(c.params.expression||'')),false);
});
test('initial receipt preserves request-bound message ID evidence without adding a text prerequisite',async()=>{
 const f=fixture({extraContent:'different text',afterCommit:{composer:false}});
 f.user.attrs['data-message-id']='known-request-message';
 assert.equal((await f.read({intent:{kind:'initial',message_id:'known-request-message',baseline:{userCount:1}}})).state,'CONFIRMED');
});
test('recovery receipt retains its existing reload and composer-readback call sequence',async()=>{
 const f=fixture();
 assert.equal((await f.read({initialReadback:false,intent:{kind:'recovery',baseline:{userCount:0}}})).state,'CONFIRMED');
 assert.deepEqual(f.calls.map(c=>c.method),['Runtime.evaluate','Page.reload','Runtime.evaluate','Runtime.evaluate']);
 assert.equal(f.listenerCount(),0);
});
test('recovery receipt still waits for its composer instead of using the initial-only receipt path',async()=>{
 const f=fixture({afterCommit:{composer:false}});
 assert.equal((await f.read({initialReadback:false,intent:{kind:'recovery',baseline:{userCount:0}}})).state,'UNKNOWN');
 assert.equal(f.calls.some(c=>c.method==='Page.getFrameTree'),false);
});

test('STOP readback with an initial intent retains the legacy sequence unless explicitly requested',async()=>{
 const f=fixture();
 assert.equal((await f.read({initialReadback:false})).state,'CONFIRMED');
 assert.deepEqual(f.calls.map(c=>c.method),['Runtime.evaluate','Page.reload','Runtime.evaluate','Runtime.evaluate']);
});
