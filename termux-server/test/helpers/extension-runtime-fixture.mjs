import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import {createHash,createHmac} from 'node:crypto';
import {ExtensionBridge} from '../../src/browser/extension-bridge.mjs';
import {ExtensionManager} from '../../src/browser/extension-manager.mjs';
import {controllerRuntime} from '../../src/browser/extension-controller-source.mjs';

export const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};};
export async function wait(fn,label='event') {for(let n=0;n<400;n++){const value=fn();if(value)return value;await new Promise(r=>setTimeout(r,3));}throw Error('missing '+label);}

// Real manager/session/HTTP bridge/controller runtime, with Chrome APIs simulated.
export async function extensionRuntimeFixture(t,{manifestVersion='1.0.2'}={}) {
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-runtime-'));
 const capability='fixture-only-capability-123456',ownerId='fixture-owner';
 const config={dataDir:root,extensionPairingFile:path.join(root,'pair.json'),extensionBridgePort:0,
  extensionChromiumCommand:'/fixture/chrome',browserProfile:path.join(root,'profile'),browserLogFile:path.join(root,'browser.log'),
  extensionTempDir:path.join(root,'tmp'),browserClientProfile:'native',browserPort:19227};
 await fs.writeFile(config.extensionPairingFile,JSON.stringify({schema:'selfrun-extension-pair-v1',ownerId,capability}),{mode:0o600});
 let bridge,identity,receiver,removed,nextTab=0,closed=false,launches=0;const timers=new Set(),requests=[],calls=[],storage={},tabs=new Map([[99,{id:99,url:'chrome-extension://test/controller.html'}]]);
 const gates={hello:false,create:null,scripts:new Map(),snapshots:new Map()},stop=new AbortController();
 const timer=(fn,ms)=>{if(closed)return null;const id=setTimeout(()=>{timers.delete(id);fn();},ms);timers.add(id);return id;};
 const clear=id=>{timers.delete(id);clearTimeout(id);};
 const hello=(id,overrides={})=>receiver({kind:'selfrun.content.hello',nonce:'nonce-'+id},{id:'test',frameId:0,tab:{id},url:tabs.get(id)?.url,documentId:'doc-'+id,...overrides});
 function launchRuntime() {
  const runtimeConfig={bridgeUrl:bridge.url,ownerId,capability,browserSessionId:bridge.browserSessionId,targetOrigins:['https://chatgpt.com'],
   installationKey:createHash('sha256').update('selfrun-page-bootstrap:'+capability+':'+bridge.browserSessionId).digest('hex')};
  const chrome={runtime:{id:'test',getURL:p=>'chrome-extension://test/'+p,getManifest:()=>({version:manifestVersion}),onMessage:{addListener:fn=>receiver=fn}},
   storage:{local:{get:async key=>({[key]:storage[key]}),set:async value=>Object.assign(storage,structuredClone(value))}},
   tabs:{getCurrent:async()=>tabs.get(99),get:async id=>{if(!tabs.has(id))throw Error('target absent');return{...tabs.get(id)};},query:async()=>[...tabs.values()],
    create:async props=>{calls.push(['createRequested']);if(gates.create)await gates.create;const tab={id:++nextTab,...props};tabs.set(tab.id,tab);calls.push(['created',tab.id]);return{...tab};},
    update:async(id,props)=>{Object.assign(tabs.get(id),props);return{...tabs.get(id)};},
    remove:async id=>{calls.push(['removed',id]);tabs.delete(id);removed?.(id);},onRemoved:{addListener:fn=>removed=fn},
    sendMessage:async(id,message)=>{calls.push([message.kind,id,message]);if(message.kind==='selfrun.content.helloRequest'&&!gates.hello)timer(()=>hello(id),0);
     if(message.kind==='selfrun.content.snapshot'&&gates.snapshots.has(id))await gates.snapshots.get(id);
     return{ok:true,nonce:'nonce-'+id,snapshot:{url:tabs.get(id)?.url,composer:true,responseFingerprint:'response-'+id}};}},
   scripting:{executeScript:async p=>{const operation=p.args?.[0],id=p.target.tabId;calls.push(['script',operation,id,p.args]);
    if(operation==='selfrun-attest')return[{documentId:p.target.documentIds[0],result:{url:tabs.get(id)?.url,mac:createHmac('sha256',runtimeConfig.installationKey).update(p.args[1]).digest('hex')}}];
    if(gates.scripts.has(id)&&operation==='submit')await gates.scripts.get(id);
    return[{documentId:p.target.documentIds[0],result:{url:tabs.get(id)?.url,value:operation==='submit'?{status:'SUBMITTED'}:true}}];}}};
  const http=async(url,options)=>{const body=JSON.parse(options.body);const row={path:new URL(url).pathname,body};requests.push(row);
   const response=await fetch(url,{...options,headers:{...options.headers,Origin:'chrome-extension://test'},signal:AbortSignal.any([stop.signal,options.signal].filter(Boolean))});row.status=response.status;return response;};
  vm.runInNewContext(`(${controllerRuntime.toString()})(${JSON.stringify(runtimeConfig)})`,{chrome,fetch:http,setTimeout:timer,clearTimeout:clear,AbortController,TextEncoder,TextDecoder,crypto,URL,console});
 }
 const manager=new ExtensionManager(config,{bridgeFactory:options=>{bridge=new ExtensionBridge({...options,extensionId:'test'});return bridge;},
  processIdentity:pid=>identity?.pid===pid?identity:null,processExists:pid=>identity?.pid===pid,bootId:()=> 'fixture-boot',profileBusy:()=>false,
  launch:async(_binary,args)=>{launches++;identity={pid:42,startTime:'123',args};launchRuntime();return{pid:42,unref(){}};},
  signalProcess:()=>{identity=null;},delay:async()=>{}});
 t.after(async()=>{closed=true;stop.abort();for(const id of timers)clearTimeout(id);await manager.close();await fs.rm(root,{recursive:true,force:true});});
 return{manager,config,calls,requests,gates,tabs,storage,hello,get bridge(){return bridge;},get launches(){return launches;},
  target:async(name='owned')=>manager.createTarget('https://chatgpt.com/c/'+name)};
}
