import {fixtureNavigator} from './helpers/web-locks-fixture.mjs';
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
  const op=p.args?.[0];calls.push(['script',op]);if(p.args?.length===1)return[{documentId:'doc'+p.target.tabId,result:{url:tabs.get(p.target.tabId).url,mac:createHmac('sha256',config.installationKey).update(op).digest('hex')}}];if(op==='selfrun-attest')return[{documentId:p.target.documentIds[0],result:{url:tabs.get(p.target.tabId).url,mac:createHmac('sha256',config.installationKey).update(p.args[1]).digest('hex')}}];
  if(op==='submit'&&gates.script)await gates.script;
  return[{documentId:p.target.documentIds[0],result:{url:tabs.get(p.target.tabId)?.url,value:op==='submit'?{status:'SUBMITTED'}:true}}];
 }}};
 const fetch=async(url,options)=>{
  if(url.endsWith('/events'))return{ok:true,body:new ReadableStream({start(c){reader=c;}})};
  const body=JSON.parse(options.body);posts.push({url,body,signal:options.signal});
  if(url.endsWith('/result')){results.push(body);const gate=gates.result;if(gate?.stage==='headers')await gate.promise;return{ok:true,json:async()=>{if(gate?.stage==='body')await gate.promise;return{ok:true};}};}
  return{ok:true,json:async()=>({ok:true})};
 };
 const context=vm.createContext({navigator:fixtureNavigator(),chrome,fetch,setTimeout:timer,clearTimeout:clear,AbortController,TextEncoder,TextDecoder,crypto,URL,console});
 vm.runInContext(`(${controllerRuntime.toString()})(${JSON.stringify(config)})`,context);
 await wait(()=>reader);const send=m=>reader.enqueue(new TextEncoder().encode(JSON.stringify(m)+'\n'));send({kind:'welcome',connectionId:'c'});
 t.after(()=>{closed=true;for(const id of timers)clearTimeout(id);reader.close();});
 const command=(operation,payload={},deadlineAt=Date.now()+30000)=>{const id='command-'+(++commandId);send({kind:'command',id,operation,payload,deadlineAt,connectionId:'c'});return{id,result:()=>wait(()=>results.find(x=>x.id===id),operation+' result')};};
 return{command,send,calls,results,posts,gates,tabs,async target(){const id=++tabId,url='https://chatgpt.com/c/owned-'+id;tabs.set(id,{id,url});const challenge='fixture-challenge-'+id,targetId='ext:s:CDP'+id;const r=await command('registerTarget',{targetId,expectedUrl:url,challenge,mac:createHmac('sha256',config.installationKey).update(challenge).digest('hex')}).result();assert.equal(r.ok,true);return targetId;}};
}

for(const stage of ['headers','body'])test('a stalled result '+stage+' cannot block another observation; reporting is bounded',async t=>{
 const f=await fixture(t),target=await f.target(),gate=deferred();f.gates.result={stage,promise:gate.promise};
 const old=f.command('snapshot',{targetId:target});await old.result();f.gates.result=null;
 const fresh=await f.command('recoveryInput',{targetId:target}).result();assert.equal(fresh.ok,true);
 const post=f.posts.find(x=>x.body.id===old.id);await wait(()=>post.signal.aborted,'HTTP reporting deadline');gate.resolve();
 assert.equal(f.results.filter(x=>x.id===old.id).length,1);
});
test('a pending progress read cannot occupy recovery read or disposal',async t=>{
 const f=await fixture(t),target=await f.target(),gate=deferred();f.gates.snapshot=gate.promise;
 const progress=f.command('progress',{targetId:target});await wait(()=>f.calls.some(x=>x[0]==='selfrun.content.snapshot'));
 assert.equal((await f.command('recoveryInput',{targetId:target}).result()).ok,true);
 assert.equal((await f.command('discardTarget',{targetId:target}).result()).result.discarded,true);
 assert.equal((await progress.result()).ok,false);gate.resolve();
});
test('cancelled observation cannot replace its cancellation response after completion',async t=>{
 const f=await fixture(t),target=await f.target(),gate=deferred();f.gates.snapshot=gate.promise;
 const old=f.command('progress',{targetId:target});await wait(()=>f.calls.some(x=>x[0]==='selfrun.content.snapshot'));
 f.send({kind:'cancel',id:old.id,connectionId:'c'});assert.equal((await old.result()).ok,false);gate.resolve();await new Promise(r=>setImmediate(r));
 assert.equal(f.results.filter(x=>x.id===old.id).length,1);assert.equal((await f.command('recoveryInput',{targetId:target}).result()).ok,true);
});
test('discarded target cannot regain authority from an old queued observation',async t=>{
 const f=await fixture(t),target=await f.target();await f.command('discardTarget',{targetId:target}).result();
 assert.equal((await f.command('progress',{targetId:target}).result()).error.code,'TARGET_NOT_OWNED');
 const fresh=await f.target();assert.equal((await f.command('progress',{targetId:fresh}).result()).ok,true);
});
