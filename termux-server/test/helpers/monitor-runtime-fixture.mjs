import {fixtureNavigator} from './web-locks-fixture.mjs';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import {createHash,createHmac} from 'node:crypto';
import {ExtensionBridge} from '../../src/browser/extension-bridge.mjs';
import {ServerActionSession} from '../../src/browser/server-action-session.mjs';
import {ExtensionManager} from '../../src/browser/extension-manager.mjs';
import {EXTENSION_ID} from '../../src/browser/extension-package.mjs';
import {controllerRuntime} from '../../src/browser/extension-controller-source.mjs';

export const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};};
export async function wait(fn,label='event') {for(let n=0;n<400;n++){const value=fn();if(value)return value;await new Promise(r=>setTimeout(r,3));}throw Error('missing '+label);}

// Real manager/session/HTTP bridge/controller runtime, with Chrome APIs simulated.
export async function monitorRuntimeFixture(t,{manifestVersion='1.0.2',runtime=controllerRuntime,readinessBudgetMs=null,pageRuntimeFactory=null}={}) {
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-runtime-'));
 const capability='fixture-only-capability-123456',ownerId='fixture-owner';
 const config={dataDir:root,extensionPairingFile:path.join(root,'pair.json'),extensionBridgePort:0,
  extensionChromiumCommand:'/fixture/chrome',browserProfile:path.join(root,'profile'),browserLogFile:path.join(root,'browser.log'),
  extensionTempDir:path.join(root,'tmp'),browserClientProfile:'native',browserPort:19227};
 await fs.writeFile(config.extensionPairingFile,JSON.stringify({schema:'selfrun-extension-pair-v1',ownerId,capability}),{mode:0o600});
 let runtimeContext,bridge,identity,receiver,removed,nextTab=0,closed=false,launches=0;const timers=new Set(),requests=[],calls=[],pageKeys=new Map(),pageRuntimes=new Map(),storage={},tabs=new Map([[99,{id:99,url:'chrome-extension://'+EXTENSION_ID+'/controller.html'}]]);
 const gates={hello:false,create:null,attest:null,removeError:null,disposals:new Map(),scripts:new Map(),snapshots:new Map()},stop=new AbortController();
 const timer=(fn,ms)=>{if(closed)return null;const id=setTimeout(()=>{timers.delete(id);fn();},ms);timers.add(id);return id;};
 const clear=id=>{timers.delete(id);clearTimeout(id);};
 const hello=(id,overrides={})=>receiver({kind:'selfrun.content.hello',nonce:'nonce-'+id},{id:'test',frameId:0,tab:{id},url:tabs.get(id)?.url,documentId:'doc-'+id,...overrides});
 function launchRuntime() {
  const runtimeConfig={bridgeUrl:bridge.url,ownerId,capability,browserSessionId:bridge.browserSessionId,targetOrigins:['https://chatgpt.com'],
   installationKey:createHash('sha256').update('selfrun-page-bootstrap:'+capability+':'+bridge.browserSessionId).digest('hex')};
  const chrome={runtime:{id:'test',getURL:p=>'chrome-extension://test/'+p,getManifest:()=>({version:manifestVersion}),onMessage:{addListener:fn=>receiver=fn}},
   storage:{local:{get:async key=>key===null?structuredClone(storage):({[key]:storage[key]}),set:async value=>Object.assign(storage,structuredClone(value)),remove:async key=>{delete storage[key];}}},
   tabs:{getCurrent:async()=>tabs.get(99),get:async id=>{if(!tabs.has(id))throw Error('target absent');return{...tabs.get(id)};},query:async()=>[...tabs.values()],
    create:async props=>{calls.push(['createRequested']);if(gates.create)await gates.create;const tab={id:++nextTab,...props};tabs.set(tab.id,tab);calls.push(['created',tab.id]);return{...tab};},
    update:async(id,props)=>{Object.assign(tabs.get(id),props);return{...tabs.get(id)};},
    remove:async id=>{calls.push(['removed',id]);if(gates.removeError)throw gates.removeError;pageRuntimes.get(id)?.closeDocument();tabs.delete(id);removed?.(id);},onRemoved:{addListener:fn=>removed=fn},
    sendMessage:async(id,message)=>{calls.push([message.kind,id,message]);if(pageRuntimes.has(id))return pageRuntimes.get(id).contentMessage(message);if(message.kind==='selfrun.content.helloRequest'&&!gates.hello)timer(()=>hello(id),0);
     if(message.kind==='selfrun.content.dispose'&&gates.disposals.has(id))await gates.disposals.get(id);
     if(message.kind==='selfrun.content.snapshot'&&gates.snapshots.has(id))await gates.snapshots.get(id);
     return{ok:true,nonce:'nonce-'+id,snapshot:{url:tabs.get(id)?.url,composer:true,responseFingerprint:'response-'+id}};}},
   scripting:{executeScript:async p=>{const operation=p.args?.[0],id=p.target.tabId;calls.push(['script',operation,id,p.args]);
    if(p.args?.length===1&&gates.attest)await gates.attest;
    if(p.args?.length===1&&pageRuntimes.has(id)){const page=pageRuntimes.get(id);return[{documentId:page.documentId,result:{url:tabs.get(id)?.url,mac:await page.attest(operation)}}];}
    if(p.args?.length===1)return[{result:{url:tabs.get(id)?.url,mac:pageKeys.has(id)?createHmac('sha256',pageKeys.get(id)).update(operation).digest('hex'):null}}];
    if(operation==='selfrun-attest')return[{documentId:p.target.documentIds[0],result:{url:tabs.get(id)?.url,mac:createHmac('sha256',runtimeConfig.installationKey).update(p.args[1]).digest('hex')}}];
    if(gates.scripts.has(id)&&operation==='submit')await gates.scripts.get(id);
    return[{documentId:p.target.documentIds[0],result:{url:tabs.get(id)?.url,value:operation==='submit'?{status:'SUBMITTED'}:true}}];}}};
  const http=async(url,options)=>{const body=JSON.parse(options.body);const row={path:new URL(url).pathname,body};requests.push(row);
   const response=await fetch(url,{...options,headers:{...options.headers,Origin:'chrome-extension://test'},signal:AbortSignal.any([stop.signal,options.signal].filter(Boolean))});row.status=response.status;return response;};
  vm.runInNewContext(`(${runtime.toString()})(${JSON.stringify(runtimeConfig)})`,runtimeContext={...(pageRuntimeFactory?{Date}:{}),navigator:fixtureNavigator(),chrome,fetch:http,setTimeout:timer,clearTimeout:clear,AbortController,TextEncoder,TextDecoder,crypto,URL,console});
 }
 const manager=new ExtensionManager(config,{bridgeFactory:options=>{bridge=new ExtensionBridge({...options,extensionId:'test'});if(readinessBudgetMs!==null){const wait=bridge.waitReady.bind(bridge);bridge.waitReady=(ms,opts)=>wait(Math.min(ms,readinessBudgetMs),opts);}return bridge;},
  processIdentity:pid=>identity?.pid===pid?identity:null,processExists:pid=>identity?.pid===pid,bootId:()=> 'fixture-boot',profileBusy:()=>false,
  launch:async(_binary,args)=>{launches++;identity={pid:42,startTime:'123',args};launchRuntime();return{pid:42,unref(){}};},
  readCdpMetadata:async(_port,resource)=>resource==='version'?{webSocketDebuggerUrl:'ws://127.0.0.1:19227/devtools/browser/fixture'}:[...tabs.values()].map(t=>({id:'CDP'+t.id,type:'page',url:t.url,webSocketDebuggerUrl:'ws://127.0.0.1:19227/devtools/page/CDP'+t.id})),
  attestController:async({challenge})=>runtimeContext.__selfrunMonitorAttest(challenge),
  browserAction:async({method,params,guard,expectedUrl})=>{if(!guard())throw Error('owner stopped');calls.push(['server',method]);if(method==='Target.createTarget'){calls.push(['createRequested']);if(gates.create)await gates.create;const tab={id:++nextTab,url:params.url};tabs.set(tab.id,tab);calls.push(['created',tab.id]);return{targetId:'CDP'+tab.id};}const id=Number(params.targetId.slice(3));calls.push(['removed',id]);if(expectedUrl!==tabs.get(id)?.url||!guard({targetId:params.targetId,type:'page',url:tabs.get(id)?.url}))throw Error('close target changed');if(gates.removeError)throw gates.removeError;pageRuntimes.get(id)?.closeDocument();tabs.delete(id);removed?.(id);return{success:true};},
  purgeInactiveAction:async args=>{calls.push(['inactivePurge',args.row.id]);return args.guard();},
  connectServerAction:async args=>{const id=Number(args.row.id.slice(3));pageKeys.set(id,args.installationKey);const runtime=pageRuntimeFactory?.({id,tabs,calls,notify:message=>receiver(message,{id:'test',frameId:0,tab:{id},url:tabs.get(id)?.url,documentId:pageRuntimes.get(id)?.documentId})});if(runtime)pageRuntimes.set(id,runtime);return new ServerActionSession({...args,initialUrl:args.row.url,session:runtime?.transport||{close(){calls.push(['actionClosed',id]);},async call(method,params,options={}){calls.push(['server',method,id]);if(method==='Page.navigate'){tabs.get(id).url=params.url;return{};}if(method==='Runtime.evaluate'){if(params.expression==='location.href')return{result:{value:tabs.get(id)?.url}};const challenge=params.expression?.match(/attest\(("[^"]+")\);/);if(challenge)return{result:{value:createHmac('sha256',args.installationKey).update(JSON.parse(challenge[1])).digest('hex')}};const match=params.expression.match(/helper\.run\(("[^"]+")/);const operation=match?JSON.parse(match[1]):null;if(operation){calls.push(['serverOperation',operation,id]);if(gates.scripts.has(id)&&operation==='submit')await Promise.race([gates.scripts.get(id),new Promise((_,reject)=>{if(options.signal?.aborted)reject(options.signal.reason);else options.signal?.addEventListener('abort',()=>reject(options.signal.reason),{once:true});})]);return{result:{value:operation==='input'?{status:'READY'}:operation==='sendReady'?{ready:true}:operation==='submit'?{status:'SUBMITTED'}:operation==='recoveryInput'?{url:tabs.get(id)?.url,composer:true}:true}};}return{result:{value:true}};}return{};}}});},
  signalProcess:()=>{identity=null;},delay:async()=>{}});
 t.after(async()=>{closed=true;stop.abort();for(const id of timers)clearTimeout(id);await manager.close();await manager.queue;await manager.saveChain;await fs.rm(root,{recursive:true,force:true});});
 return{manager,config,calls,requests,gates,tabs,storage,hello,pageRuntimes,get bridge(){return bridge;},get runtimeContext(){return runtimeContext;},get launches(){return launches;},
  target:async(name='owned')=>manager.createTarget('https://chatgpt.com/c/'+name)};
}
