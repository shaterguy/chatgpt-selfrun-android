import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {createHmac} from 'node:crypto';
import {controllerRuntime} from '../src/browser/extension-controller-source.mjs';

const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};};
const wait=async(fn,label='event')=>{for(let i=0;i<120;i++){const v=fn();if(v)return v;await new Promise(r=>setTimeout(r,2));}throw Error('missing '+label);};
async function fixture(t){
 const config={bridgeUrl:'http://127.0.0.1:19821',ownerId:'test-owner',capability:'fixture-only-capability',browserSessionId:'s',installationKey:'fixture-key',targetOrigins:['https://chatgpt.com']};
 let reader,receiver,removed,tabId=0,commandId=0,closed=false;const results=[],calls=[],posts=[],timers=new Set(),tabs=new Map([[99,{id:99,url:'chrome-extension://test/controller.html'}]]);
 const gates={result:null,snapshot:null,script:null};
 const timer=(fn,ms)=>{if(closed)return null;const id=setTimeout(()=>{timers.delete(id);fn();},ms===5000?15:ms);timers.add(id);return id;};
 const clear=id=>{timers.delete(id);clearTimeout(id);};
 const chrome={runtime:{id:'test',getURL:p=>'chrome-extension://test/'+p,onMessage:{addListener:fn=>receiver=fn}},storage:{local:{get:async()=>({}),set:async()=>{}}},tabs:{getCurrent:async()=>tabs.get(99),get:async id=>{if(!tabs.has(id))throw Error('gone');return{...tabs.get(id)};},query:async()=>[...tabs.values()],create:async props=>{const tab={id:++tabId,...props};tabs.set(tab.id,tab);calls.push(['create',tab.id]);return tab;},update:async(id,p)=>{Object.assign(tabs.get(id),p);return tabs.get(id);},remove:async id=>{tabs.delete(id);removed?.(id);},onRemoved:{addListener:fn=>removed=fn},sendMessage:async(id,msg)=>{
  if(msg.kind==='selfrun.content.helloRequest')receiver({kind:'selfrun.content.hello',nonce:'fixture-content-nonce'},{id:'test',frameId:0,tab:{id},url:tabs.get(id).url,documentId:'doc'+id});
  calls.push([msg.kind,id]);if(msg.kind==='selfrun.content.snapshot'&&gates.snapshot)await gates.snapshot;
  return{ok:true,nonce:'fixture-content-nonce',snapshot:{url:tabs.get(id)?.url,composer:true,responseFingerprint:'fresh-'+id}};
 }},scripting:{executeScript:async p=>{
  const op=p.args?.[0];calls.push(['script',op]);if(op==='selfrun-attest')return[{documentId:p.target.documentIds[0],result:{url:tabs.get(p.target.tabId).url,mac:createHmac('sha256',config.installationKey).update(p.args[1]).digest('hex')}}];
  if(op==='submit'&&gates.script)await gates.script;
  return[{documentId:p.target.documentIds[0],result:{url:tabs.get(p.target.tabId)?.url,value:op==='submit'?{status:'SUBMITTED'}:true}}];
 }}};
 const fetch=async(url,options)=>{
  if(url.endsWith('/events'))return{ok:true,body:new ReadableStream({start(c){reader=c;}})};
  const body=JSON.parse(options.body);posts.push({url,body,signal:options.signal});
  if(url.endsWith('/result')){results.push(body);const gate=gates.result;if(gate?.stage==='headers')await gate.promise;return{ok:true,json:async()=>{if(gate?.stage==='body')await gate.promise;return{ok:true};}};}
  return{ok:true,json:async()=>({ok:true})};
 };
 const context=vm.createContext({chrome,fetch,setTimeout:timer,clearTimeout:clear,AbortController,TextEncoder,TextDecoder,crypto,URL,console});
 vm.runInContext(`(${controllerRuntime.toString()})(${JSON.stringify(config)})`,context);
 await wait(()=>reader);const send=m=>reader.enqueue(new TextEncoder().encode(JSON.stringify(m)+'\n'));send({kind:'welcome',connectionId:'c'});
 t.after(()=>{closed=true;for(const id of timers)clearTimeout(id);reader.close();});
 const command=(operation,payload={},deadlineAt=Date.now()+30000)=>{const id='command-'+(++commandId);send({kind:'command',id,operation,payload,deadlineAt,connectionId:'c'});return{id,result:()=>wait(()=>results.find(x=>x.id===id),operation+' result')};};
 return{command,send,calls,results,posts,gates,tabs,async target(){const r=await command('createTarget',{url:'https://chatgpt.com/c/owned-'+(tabId+1)}).result();return r.result.id;}};
}

for(const stage of ['headers','body'])test('a stalled result '+stage+' cannot block the next turn createTarget; reporting is bounded',async t=>{
 const f=await fixture(t),gate=deferred();f.gates.result={stage,promise:gate.promise};
 const old=f.command('createTarget',{url:'https://chatgpt.com/c/old'});await old.result();f.gates.result=null;
 const fresh=await f.command('createTarget',{url:'https://chatgpt.com/c/new'}).result();assert.equal(fresh.ok,true);assert.equal(f.calls.filter(x=>x[0]==='create').length,2);
 const post=f.posts.find(x=>x.body.id===old.id);assert.ok(post.signal,'transport deadline must abort the actual fetch');await wait(()=>post.signal.aborted,'HTTP reporting deadline');
 gate.resolve();await new Promise(r=>setImmediate(r));assert.equal(f.results.filter(x=>x.id===fresh.id).length,1);
});
test('a pending progress read cannot occupy receipt or due recovery-input execution',async t=>{
 const f=await fixture(t),target=await f.target(),gate=deferred();f.gates.snapshot=gate.promise;
 const progress=f.command('progress',{targetId:target});await wait(()=>f.calls.some(x=>x[0]==='selfrun.content.snapshot'));
 const receipt=await f.command('receiptSnapshot',{targetId:target,key:{intent_id:'i',generation:'g'}}).result();assert.equal(receipt.ok,true);
 const input=await f.command('recoveryInput',{targetId:target}).result();assert.equal(input.ok,true);assert.equal(input.result.composer,true);
 f.send({kind:'cancel',id:progress.id,connectionId:'c'});assert.equal((await progress.result()).ok,false);gate.resolve();
});
test('turn cancellation settles its old mutation while another active target and a new turn remain usable',async t=>{
 const f=await fixture(t),oldTarget=await f.target(),otherTarget=await f.target(),gate=deferred();
 await f.command('receiptArm',{targetId:oldTarget,key:{intent_id:'old',generation:'g1'}}).result();f.gates.script=gate.promise;
 const old=f.command('submit',{targetId:oldTarget});await wait(()=>f.calls.some(x=>x[0]==='script'&&x[1]==='submit'));
 f.gates.result={stage:'headers',promise:new Promise(()=>{})};f.send({kind:'cancel',id:old.id,connectionId:'c'});
 const cancelled=await old.result();assert.equal(cancelled.error.released,true);f.gates.result=null;
 assert.equal((await f.command('recoveryInput',{targetId:otherTarget}).result()).ok,true);
 const fresh=await f.command('createTarget',{url:'https://chatgpt.com/c/new-turn'}).result();assert.equal(fresh.ok,true);
 const blocked=await f.command('receiptArm',{targetId:oldTarget,key:{intent_id:'unsafe',generation:'g2'}}).result();assert.equal(blocked.error.code,'TARGET_MUTATION_PENDING');
 gate.resolve();await new Promise(r=>setImmediate(r));assert.equal(f.results.filter(x=>x.id===old.id).length,1,'late old execution cannot replace its cancellation response');assert.equal(f.results.filter(x=>x.id===fresh.id).length,1);
});
test('cancelled queued old-turn command never executes when an earlier command settles',async t=>{
 const f=await fixture(t),target=await f.target(),gate=deferred();await f.command('receiptArm',{targetId:target,key:{intent_id:'old',generation:'g'}}).result();f.gates.script=gate.promise;
 const first=f.command('submit',{targetId:target});await wait(()=>f.calls.some(x=>x[0]==='script'&&x[1]==='submit'));
 const queued=f.command('createTarget',{url:'https://chatgpt.com/c/obsolete'});f.send({kind:'cancel',id:queued.id,connectionId:'c'});f.send({kind:'cancel',id:first.id,connectionId:'c'});
 assert.equal((await queued.result()).ok,false);assert.equal(f.calls.filter(x=>x[0]==='create').length,1);gate.resolve();
 assert.equal((await f.command('createTarget',{url:'https://chatgpt.com/c/current'}).result()).ok,true);
});
