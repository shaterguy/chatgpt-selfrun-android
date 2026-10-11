import test from 'node:test';import assert from 'node:assert/strict';import vm from 'node:vm';
const source='../src/browser/extension-controller-source.mjs';
const {controllerRuntime}=await import(new URL(source,import.meta.url));
const flush=async()=>{for(let i=0;i<6;i++)await new Promise(setImmediate);};
function fixture({supported=true,admissionStatus=200,eventStatuses=[]}={}){
  const tabs=new Map(),streams=[],removed=[],timers=new Map(),held=new Map(),requests=[],errors=[];let nextTimer=0;
  function start(id,{session='fixture-session',visible=[id],getContexts=true}={}){
    tabs.set(id,{id});
    const navigator=supported?{locks:{request:async(name,options,callback)=>{requests.push({id,name,options});if(held.has(name))return callback(null);const owner={id};held.set(name,owner);try{return await callback({name,mode:'exclusive'});}finally{if(held.get(name)===owner)held.delete(name);}}}}:{};
    const chrome={runtime:{id:'fixture',getURL:()=> 'chrome-extension://fixture/controller.html',getManifest:()=>({version:'1.0.2'}),onMessage:{addListener(){}},...(getContexts?{getContexts:async()=>visible.map(tabId=>({tabId}))}:{})},tabs:{onRemoved:{addListener(){}},getCurrent:async()=>({id}),query:async()=>[...tabs.values()],remove:async tabId=>{removed.push(tabId);tabs.delete(tabId);}},storage:{local:{get:async()=>({}),set:async()=>{}}}};
    const fetch=async url=>{if(url.endsWith('/session'))return{ok:admissionStatus===200,status:admissionStatus,json:async()=>({ok:true})};if(!url.endsWith('/events'))return{ok:true,json:async()=>({ok:true})};const status=eventStatuses.shift()||200;if(status!==200)return{ok:false,status};let controller;const body=new ReadableStream({start(c){controller=c;}});streams.push({id,controller});return{ok:true,body};};
    vm.runInNewContext(`(${controllerRuntime.toString()})(${JSON.stringify({bridgeUrl:'http://127.0.0.1:12345',ownerId:'fixture-owner',capability:'fixture-not-real-secret',browserSessionId:session,targetOrigins:[]})})`,{navigator,chrome,fetch,AbortController,URL,TextDecoder,TextEncoder,console:{error:(...x)=>errors.push(x),warn:(...x)=>errors.push(x)},setTimeout(fn,ms){const n=++nextTimer;timers.set(n,{fn,ms,id});return n;},clearTimeout(n){timers.delete(n);}});
  }
  async function disconnect(id){streams.filter(x=>x.id===id).at(-1).controller.close();await flush();}
  async function reconnect(id){const entry=[...timers].find(([,v])=>v.id===id&&v.ms===250);assert.ok(entry,'normal reconnect timer exists');timers.delete(entry[0]);entry[1].fn();await flush();}
  function terminatePage(id){tabs.delete(id);for(const [n,t]of timers)if(t.id===id)timers.delete(n);for(const [name,owner]of held)if(owner.id===id)held.delete(name);}
  return{start,streams,removed,held,requests,errors,disconnect,reconnect,terminatePage};
}
test('atomic ownership retains first higher-id holder when lower context appears late',async()=>{
  const f=fixture();f.start(20);await flush();f.start(10,{visible:[20,10]});await flush();
  assert.deepEqual(f.streams.map(x=>x.id),[20]);assert.deepEqual(f.removed,[10]);assert.equal(f.held.size,1);
});
test('simultaneous controllers with no getContexts and redacted URLs create one stream',async()=>{
  const f=fixture();f.start(10,{getContexts:false});f.start(20,{getContexts:false});await flush();
  assert.equal(f.streams.length,1);assert.equal(f.removed.length,1);assert.equal(f.held.size,1);
});
test('same lifetime lock covers reconnect backoff and duplicate closes without opening stream',async()=>{
  const f=fixture();f.start(20);await flush();
  for(let i=0;i<4;i++){await f.disconnect(20);f.start(30+i);await flush();assert.equal(f.held.size,1);await f.reconnect(20);}
  assert.deepEqual(f.streams.map(x=>x.id),[20,20,20,20,20]);assert.deepEqual(f.removed,[30,31,32,33]);assert.equal(f.requests.filter(x=>x.id===20).length,1);
});
test('lock is shared across changed session IDs in the same extension origin',async()=>{
  const f=fixture();f.start(10,{session:'old-session'});await flush();f.start(20,{session:'new-session'});await flush();
  assert.equal(f.streams.length,1);assert.deepEqual(f.removed,[20]);assert.equal(new Set(f.requests.map(x=>x.name)).size,1);
});
test('a future page can become owner after platform releases a dead page lock',async()=>{
  const f=fixture();f.start(10);await flush();assert.equal(f.held.size,1);f.terminatePage(10);f.start(20);await flush();
  assert.deepEqual(f.streams.map(x=>x.id),[10,20]);assert.equal([...f.held.values()][0].id,20);
});
test('missing platform lock capability fails closed without a second transport mechanism',async()=>{
  const f=fixture({supported:false});f.start(10);await flush();assert.equal(f.streams.length,0);assert.equal(f.errors.length,1);
});
for(const status of [403,409])test(`rejected admission ${status} closes stale page before any lock or stream`,async()=>{
  const f=fixture({admissionStatus:status});f.start(10);await flush();
  assert.deepEqual(f.removed,[10]);assert.equal(f.requests.length,0);assert.equal(f.streams.length,0);
});
for(const status of [403,409])test(`session rejection ${status} after admission releases lifetime lock without reconnect`,async()=>{
  const f=fixture({eventStatuses:[status]});f.start(10);await flush();
  assert.deepEqual(f.removed,[10]);assert.equal(f.held.size,0);assert.equal(f.streams.length,0);
});
test('a transient stream error retains lifetime lock and reconnects normally',async()=>{
  const f=fixture({eventStatuses:[503]});f.start(10);await flush();
  assert.equal(f.held.size,1);assert.equal(f.removed.length,0);
  await f.reconnect(10);assert.deepEqual(f.streams.map(x=>x.id),[10]);assert.equal(f.held.size,1);
});
