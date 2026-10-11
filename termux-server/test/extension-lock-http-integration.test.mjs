import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {controllerRuntime} from '../src/browser/extension-controller-source.mjs';
import {ExtensionBridge} from '../src/browser/extension-bridge.mjs';
import {controllerRuntime as legacyControllerRuntime} from './fixtures/extension-controller-dev25.mjs';
import {EXTENSION_ID} from '../src/browser/extension-package.mjs';
const ownerId='fixture-owner',capability='fixture-capability-not-a-real-secret',session='current-session';
async function until(predicate,label){const end=Date.now()+2000;while(!predicate()){if(Date.now()>=end)assert.fail(`timeout: ${label}`);await new Promise(r=>setTimeout(r,5));}}
async function fixture(t,{saved={},extraTabs=[]}={}){
  const bridge=new ExtensionBridge({port:0,ownerId,capability,browserSessionId:session});await bridge.start();
  const tabs=new Map(extraTabs.map(x=>[x.id,x])),held=new Map(),pages=[],removed=[],records=[],errors=[],removalListeners=[];let serial=0;
  const store=structuredClone(saved);
  function start(id,{browserSessionId=session,cap=capability,runtime=controllerRuntime}={}){
    const page={id,alive:true,timers:new Set(),aborts:new Set()};pages.push(page);tabs.set(id,{id,url:`chrome-extension://${EXTENSION_ID}/controller.html`});
    const timer=(fn,ms)=>{if(!page.alive)return null;const token=setTimeout(()=>{page.timers.delete(token);if(page.alive)fn();},ms);page.timers.add(token);return token;};
    const clear=(token)=>{clearTimeout(token);page.timers.delete(token);};
    const locks={request:async(name,options,callback)=>{assert.equal(options.ifAvailable,true);if(held.has(name))return callback(null);const token={id,serial:++serial};held.set(name,token);try{return await callback({name,mode:'exclusive'});}finally{if(held.get(name)===token)held.delete(name);}}};
    const chrome={runtime:{id:EXTENSION_ID,getManifest:()=>({version:'1.0.2'}),onMessage:{addListener(){}},getURL:p=>`chrome-extension://${EXTENSION_ID}/${p}`},tabs:{onRemoved:{addListener(fn){removalListeners.push(fn);}},getCurrent:async()=>({id}),query:async()=>[...tabs.values()],get:async tabId=>{const tab=tabs.get(tabId);if(!tab)throw Error('No tab');return tab;},remove:async tabId=>{removed.push(tabId);tabs.delete(tabId);for(const fn of removalListeners)fn(tabId);}},storage:{local:{get:async key=>key===null?structuredClone(store):({[key]:store[key]}),set:async values=>Object.assign(store,values),remove:async key=>{delete store[key];}}}};
    const runtimeFetch=async(url,options)=>{if(!page.alive)throw Error('fixture page disposed');if(options.signal)page.aborts.add(options.signal);const response=await fetch(url,{...options,headers:{...options.headers,Origin:`chrome-extension://${EXTENSION_ID}`}});records.push({id,url,status:response.status,body:JSON.parse(options.body)});return response;};
    class TrackedAbortController extends AbortController{constructor(){super();page.controllers??=[];page.controllers.push(this);}}
    vm.runInNewContext(`(${runtime.toString()})(${JSON.stringify({bridgeUrl:bridge.url,ownerId,capability:cap,browserSessionId,targetOrigins:['https://fixture.invalid']})})`,{navigator:{locks},chrome,fetch:runtimeFetch,AbortController:TrackedAbortController,URL,TextDecoder,TextEncoder,console:{error:(...args)=>errors.push(args)},setTimeout:timer,clearTimeout:clear});
    return page;
  }
  t.after(async()=>{for(const page of pages){page.alive=false;for(const token of page.timers)clearTimeout(token);for(const c of page.controllers||[])c.abort();}await bridge.close();});
  // Simulated document disposal only. This is not a Chrome reload/cache test.
  function disposePage(id){const page=pages.find(p=>p.id===id&&p.alive);if(!page)throw Error('fixture page absent');page.alive=false;for(const token of page.timers)clearTimeout(token);for(const c of page.controllers||[])c.abort();tabs.delete(id);for(const [name,token] of held)if(token.id===id)held.delete(name);}
  return{bridge,start,disposePage,held,tabs,removed,records,errors,store};
}
for(const rejected of ['session','capability'])test(`rejected ${rejected} cannot own the lock or block a current successor`,async t=>{
  const f=await fixture(t);f.start(10,rejected==='session'?{browserSessionId:'stale-session'}:{cap:'wrong-capability-0123456789'});
  await until(()=>f.removed.includes(10),'rejected page closes');
  assert.equal(f.bridge.connection,null);assert.equal(f.held.size,0);
  assert.equal(f.records.filter(x=>x.id===10).length,1);
  assert.ok(f.records.find(x=>x.id===10).url.endsWith('/session'));
  f.start(20);await f.bridge.waitReady(2000);
  assert.equal([...f.held.values()][0].id,20);assert.equal(f.bridge.status().connected,true);
  assert.deepEqual(f.removed,[10]);
});
test('authenticated duplicate closes itself without displacing current ready stream',async t=>{
  const f=await fixture(t);f.start(20);await f.bridge.waitReady(2000);const connectionId=f.bridge.connection.id;
  f.start(10);await until(()=>f.removed.includes(10),'duplicate self closes');
  assert.equal(f.bridge.connection.id,connectionId);assert.equal(f.bridge.status().connected,true);
  assert.deepEqual(f.records.filter(x=>x.url.endsWith('/events')).map(x=>x.id),[20]);
  assert.equal([...f.held.values()][0].id,20);
});
test('stop refuses unrelated tabs, retains lock, then releases after guarded shutdown',async t=>{
  const f=await fixture(t,{extraTabs:[{id:7,url:'https://fixture.invalid/'}]});f.start(10);await f.bridge.waitReady(2000);
  assert.deepEqual(await f.bridge.request('controllerStop'),{stopped:false,reason:'browser_in_use'});assert.equal([...f.held.values()][0].id,10);assert.deepEqual(f.removed,[]);
  f.tabs.delete(7);assert.deepEqual(await f.bridge.request('controllerStop'),{stopped:true});await until(()=>f.removed.includes(10)&&f.held.size===0,'controller stop and release');
  f.start(20);await until(()=>f.records.some(x=>x.id===20&&x.url.endsWith('/event')&&x.status===200),'new owner hello');assert.equal([...f.held.values()][0].id,20);
});
test('owned target cleanup preserves the controller lifetime lock',async t=>{
  const key='selfrun-extension-controller-v1:'+session,id='ext:'+session+':7',url='https://fixture.invalid/';
  const f=await fixture(t,{saved:{[key]:{[id]:{id,tabId:7,url,boundUrl:url}}},extraTabs:[{id:7,url}]});f.start(10);await f.bridge.waitReady(2000);
  assert.deepEqual(await f.bridge.request('discardTarget',{targetId:id}),{discarded:true,pageCachePurged:true});assert.equal([...f.held.values()][0].id,10);assert.deepEqual(f.removed,[]);assert.equal(f.tabs.has(7),true);assert.equal(f.bridge.status().connected,true);assert.deepEqual(Object.keys(f.store[key]),[]);
});

test('loaded dev25 controller cannot connect to updated bridge without runtime replacement',async t=>{
  const f=await fixture(t,{extraTabs:[{id:777,url:'https://unrelated.invalid/'}]});f.start(10,{runtime:legacyControllerRuntime});
  await until(()=>f.records.filter(x=>x.id===10&&x.url.endsWith('/events')).length>=2,'legacy reconnect repeats');
  const connections=f.records.filter(x=>x.id===10&&x.url.endsWith('/events'));
  assert.ok(connections.every(x=>x.status===409));assert.ok(connections.every(x=>Object.keys(x.body).length===0));
  assert.equal(f.bridge.status().connected,false);assert.equal(f.bridge.status().controllerBuild,null);
  assert.equal(f.held.size,0);assert.equal(f.tabs.has(10),true);assert.equal(f.tabs.has(777),true);assert.deepEqual(f.removed,[]);
});
test('explicit replacement with current runtime establishes build while preserving unrelated tab',async t=>{
  const f=await fixture(t,{extraTabs:[{id:777,url:'https://unrelated.invalid/'}]});f.start(10,{runtime:legacyControllerRuntime});
  await until(()=>f.records.some(x=>x.id===10&&x.url.endsWith('/events')&&x.status===409),'legacy controller rejection');
  f.disposePage(10);f.start(20);await f.bridge.waitReady(2000);
  assert.equal(f.bridge.status().controllerBuild,'selfrun-monitor-only-v1');assert.equal(f.bridge.status().extensionVersion,'1.0.2');
  assert.equal(f.held.size,1);assert.equal([...f.held.values()][0].id,20);assert.equal(f.tabs.has(777),true);assert.equal(f.tabs.has(10),false);
  assert.equal([...f.tabs.values()].filter(x=>x.url===`chrome-extension://${EXTENSION_ID}/controller.html`).length,1);
});
