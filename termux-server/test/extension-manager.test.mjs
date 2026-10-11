import {digest} from '../src/lifecycle.mjs';
import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs/promises';import path from 'node:path';import os from 'node:os';import {EventEmitter} from 'node:events';
import {ExtensionManager,extensionProcessMatches,extensionProcessExited} from '../src/browser/extension-manager.mjs';
import {ExtensionBridge} from '../src/browser/extension-bridge.mjs';
import {createHash,createHmac} from 'node:crypto';
import {ServerActionSession} from '../src/browser/server-action-session.mjs';
import {EXTENSION_ID} from '../src/browser/extension-package.mjs';
async function fixture(t){
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'srex-test-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));
 const capability='fixture-local-capability-123456',pairing=path.join(root,'pair.json');
 await fs.writeFile(pairing,JSON.stringify({schema:'selfrun-extension-pair-v1',ownerId:'owner',capability}),{mode:0o600});
 const config={dataDir:root,extensionPairingFile:pairing,extensionBridgePort:0,extensionChromiumCommand:'/test/chrome',browserProfile:path.join(root,'profile'),browserLogFile:path.join(root,'browser.log'),extensionTempDir:path.join(root,'tmp'),browserClientProfile:'native',browserPort:19227};
 let process=null,controllerReady=true,extraTargets=[],targets=[],signals=0,launchCount=0,bridge;const calls=[];
 class Bridge extends EventEmitter{
  url='http://127.0.0.1:17777';pendingCount=0;
  get targets(){return targets;}set targets(value){targets=value;}
  async start(){}async close(){}status(){return{connected:!!process&&controllerReady};}
  async waitReady(){if(!process||!controllerReady)throw Object.assign(Error('not running'),{code:'EXTENSION_UNAVAILABLE'});}
  async request(op,p,options){
   calls.push([op,p,options]);
   if(op==='idleStatus')return{stopping:false,ownedCount:targets.length,unrelatedCount:0};
   if(op==='registerTarget')return{registered:true,tabId:1};
   if(op==='listTargets')return targets;
   if(op==='discardTarget')return{discarded:true,pageCachePurged:true};
   if(op==='snapshot')return{url:'https://chatgpt.com/'};
   throw Error('Unexpected extension actor command: '+op);
  }
 }
 const deps={
  bridgeFactory:options=>{bridge=new Bridge();bridge.session=options.browserSessionId;return bridge;},
  processIdentity:pid=>process?.pid===pid?process:null,processExists:pid=>process?.pid===pid,bootId:()=> 'boot',profileBusy:()=>false,findOwnedBrowserRoots:()=>[],
  readCdpMetadata:async(_port,resource)=>resource==='version'?{webSocketDebuggerUrl:'ws://127.0.0.1:19227/devtools/browser/browser1'}:[
   ...(controllerReady?[{id:'controller',type:'page',url:'chrome-extension://'+EXTENSION_ID+'/controller.html'}]:[]),
   ...targets.map(target=>({...target,id:target.id.split(':').at(-1),webSocketDebuggerUrl:'ws://127.0.0.1:19227/devtools/page/'+target.id.split(':').at(-1)})),...extraTargets],
  attestController:async({challenge})=>createHmac('sha256',createHash('sha256').update('selfrun-page-bootstrap:'+capability+':'+bridge.session).digest('hex')).update(challenge).digest('hex'),
  browserAction:async({method,params,guard,expectedUrl})=>{
   if(!guard())throw Error('owner changed');calls.push([method,params]);
   if(method==='Target.createTarget'){targets=[{id:'ext:'+bridge.session+':cdp1',type:'page',url:params.url}];return{targetId:'cdp1'};}
   const target=targets.find(t=>t.id.split(':').at(-1)===params.targetId);
   if(!target||target.url!==expectedUrl||!guard({targetId:params.targetId,type:'page',url:target.url}))throw Error('close target changed');
   targets=targets.filter(t=>t!==target);return{success:true};
  },
  connectServerAction:async args=>new ServerActionSession({...args,initialUrl:args.row.url,session:{close(){calls.push(['actionClosed',args.row.id]);},async call(method,params){
   calls.push([method,params]);
   if(method==='Page.navigate'){targets=targets.map(t=>t.id.split(':').at(-1)===args.row.id?{...t,url:params.url}:t);return{};}
   if(method==='Runtime.evaluate'){
    if(params.expression==='location.href')return{result:{value:targets.find(t=>t.id.split(':').at(-1)===args.row.id)?.url}};
    const challenge=params.expression.match(/attest\(("[^"]+")\);/);
    if(challenge)return{result:{value:createHmac('sha256',args.installationKey).update(JSON.parse(challenge[1])).digest('hex')}};
    return{result:{value:true}};
   }
   return{};
  }}}),
  signalProcess:()=>{signals++;process=null;},delay:async()=>{},
  launch:async(_binary,args)=>{launchCount++;controllerReady=true;targets=[];process={pid:42,startTime:'123',args};return{pid:42,unref(){}};}
 };
 const manager=new ExtensionManager(config,deps);t.after(()=>manager.close());
 return{manager,config,calls,get bridge(){return bridge;},setProcess:p=>process=p,setReady:r=>controllerReady=r,setExtra:r=>extraTargets=r,get signals(){return signals;},get launches(){return launchCount;},fresh:()=>new ExtensionManager(config,deps)};
}
test('extension manager creates via server actions and observes via named extension commands',async t=>{const f=await fixture(t);const target=await f.manager.createTarget('https://chatgpt.com/');assert.ok(f.manager.isOwnedTarget(target.id));const s=await f.manager.connectTarget(target);assert.equal(s.transport,'extension');await s.run('initialize',{clientProfile:'native'});assert.equal((await s.run('snapshot')).url,'https://chatgpt.com/');s.close();await assert.rejects(s.run('submit'),e=>e.name==='AbortError');assert.ok(f.calls.some(x=>x[0]==='Target.createTarget'));assert.ok(!f.calls.some(x=>['createTarget','navigate','input','submit','closeTarget'].includes(x[0])));});
test('manager refuses missing private pairing',async t=>{const f=await fixture(t);await fs.unlink(f.config.extensionPairingFile);await assert.rejects(f.manager.createTarget(),/pair/i);});
test('Chromium launch is not blocked by a stale profile-busy heuristic',async t=>{const f=await fixture(t);f.manager.deps.profileBusy=()=>{throw Error('stale heuristic must not run');};const target=await f.manager.createTarget();assert.ok(f.manager.isOwnedTarget(target.id));assert.equal(f.launches,1);});
test('active Android observation re-registers exact target before each read without replaying actions',async t=>{
 const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});
 f.manager.deps.applyProfile=async()=>({cdpCommands:3,cdpTargetId:'cdp1',isAlive:()=>true,release(){}});
 const target=await f.manager.createTarget('https://chatgpt.com/c/original'),session=await f.manager.connectTarget(target);
 await session.run('initialize',{clientProfile:'android-webview'});
 const request=f.bridge.request.bind(f.bridge);let registered=false,registrations=0;
 f.bridge.request=async(operation,payload,options)=>{
  if(operation==='registerTarget'){registered=true;registrations++;assert.equal(payload.targetId,target.id);assert.equal(payload.expectedUrl,'https://chatgpt.com/c/original');}
  if(operation==='snapshot')assert.equal(registered,true);
  return request(operation,payload,options);
 };
 await session.run('snapshot');registered=false;await session.run('snapshot');
 assert.equal(registrations,2);assert.equal(f.launches,1);assert.equal(f.calls.filter(x=>x[0]==='Target.createTarget').length,1);assert.equal(f.calls.filter(x=>x[0]==='Page.navigate').length,1);
});
test('valid browser activity renews the idle retention clock',async t=>{
 const f=await fixture(t);f.manager.lastActivityAt=1;
 const target=await f.manager.createTarget();assert.ok(f.manager.lastActivityAt>1);
 const session=await f.manager.connectTarget(target);f.manager.lastActivityAt=1;
 await session.run('snapshot');assert.ok(f.manager.lastActivityAt>1);
 f.manager.lastActivityAt=1;await f.manager.ensureStarted();assert.ok(f.manager.lastActivityAt>1);
});
test('process identity requires boot, start time, exact profile, extension and owner marker',()=>{const proof={pid:7,startTime:'1',bootId:'b',profile:'/p',extensionPath:'/e',ownerId:'o'};const row={pid:7,startTime:'1',args:['chrome','--user-data-dir=/p','--load-extension=/e','--selfrun-extension-owner=o']};assert.equal(extensionProcessMatches(proof,row,'b'),true);for(const bad of [{...row,startTime:'2'},{...row,args:row.args.filter(a=>!a.startsWith('--load'))}])assert.equal(extensionProcessMatches(proof,bad,'b'),false);assert.equal(extensionProcessMatches(proof,row,'other'),false);});
test('target close rechecks caller guard and never touches an unowned target',async t=>{const f=await fixture(t),target=await f.manager.createTarget();f.bridge.targets=[{...target,url:'https://chatgpt.com/c/a'}];assert.equal((await f.manager.closeOwnedTarget('foreign')).reason,'unowned_target');assert.equal((await f.manager.closeOwnedTarget(target.id,{guard:()=>false})).reason,'guard_rejected');assert.equal(f.calls.filter(x=>x[0]==='Target.closeTarget').length,0);assert.equal((await f.manager.closeOwnedTarget(target.id,{guard:()=>true})).closed,true);assert.equal(f.manager.isOwnedTarget(target.id),false);});
async function closeWithoutAcknowledgement(t){
 const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});
 let leaseReleases=0;f.manager.deps.applyProfile=async()=>({cdpCommands:3,cdpTargetId:'cdp1',isAlive:()=>true,release(){leaseReleases++;}});
 const target=await f.manager.createTarget(),session=await f.manager.connectTarget(target);await session.run('initialize',{clientProfile:'android-webview'});session.close();
 const action=f.manager.deps.browserAction;f.manager.deps.browserAction=async args=>{const result=await action(args);if(args.method==='Target.closeTarget')throw Error('synthetic close acknowledgement timeout');return result;};
 const first=await f.manager.closeOwnedTarget(target.id,{guard:()=>true});
 return{f,target,first,get leaseReleases(){return leaseReleases;}};
}
test('an absent target after a lost close acknowledgement is removed by idle cleanup',async t=>{
  const close=await closeWithoutAcknowledgement(t),{f,target,first}=close;
  assert.equal(first.reason,'close_unconfirmed');assert.deepEqual(f.bridge.targets,[]);
  assert.equal(f.manager.isOwnedTarget(target.id),true);assert.equal(f.manager.targetClosing(target.id),true);assert.equal(close.leaseReleases,0);
  const released=await f.manager.releaseIfUnused({guard:()=>true});assert.equal(released.released,true);assert.equal(f.signals,1);
  assert.equal(f.manager.isOwnedTarget(target.id),false);assert.equal(f.manager.targetClosing(target.id),false);assert.equal(close.leaseReleases,1);
  const saved=JSON.parse(await fs.readFile(path.join(f.config.dataDir,'extension-ownership.json'),'utf8'));assert.deepEqual(saved.targets,[]);assert.equal(saved.process,null);
  assert.equal((await f.manager.closeOwnedTarget(target.id,{guard:()=>true})).reason,'unowned_target');assert.equal(f.calls.filter(c=>c[0]==='Target.closeTarget').length,1);
});
for(const phase of ['before inventory','after inventory'])test('absent-target cleanup preserves ownership when the guard rejects '+phase,async t=>{
 const close=await closeWithoutAcknowledgement(t),{f,target}=close;let allowed=phase!=='before inventory';
 const read=f.manager.deps.readCdpMetadata;f.manager.deps.readCdpMetadata=async(...args)=>{const result=await read(...args);if(phase==='after inventory'&&args[1]==='list')allowed=false;return result;};
 const result=await f.manager.closeOwnedTarget(target.id,{guard:()=>allowed});assert.equal(result.reason,'guard_rejected');
 assert.equal(f.manager.isOwnedTarget(target.id),true);assert.equal(f.manager.targetClosing(target.id),true);assert.equal(f.manager.profileLeases.has(target.id),true);assert.equal(close.leaseReleases,0);
 assert.ok(f.manager.journal.targetMetadata[target.id]);assert.equal((await f.manager.releaseIfUnused({guard:()=>false})).reason,'guard_rejected');assert.equal(f.signals,0);
 assert.equal(f.calls.filter(c=>c[0]==='Target.closeTarget').length,1);assert.equal(f.calls.filter(c=>c[0]==='controllerStop').length,0);
});
test('idle release closes the dedicated browser even with an unrecorded live tab',async t=>{const f=await fixture(t);const target=await f.manager.createTarget();f.bridge.targets=[target,{id:'ext:lost-session:2',type:'page',url:'https://chatgpt.com/c/old'}];assert.equal((await f.manager.releaseIfUnused({guard:()=>true})).released,true);assert.equal(f.signals,1);assert.equal(f.manager.journal.process,null);assert.deepEqual(f.manager.journal.targets,[]);});

test('proven browser exit rotates target identity before a new browser starts',async t=>{const f=await fixture(t);const a=await f.manager.createTarget();f.setProcess(null);const b=await f.manager.createTarget();assert.notEqual(a.id,b.id);assert.equal(f.manager.isOwnedTarget(a.id),false);assert.equal(f.manager.isOwnedTarget(b.id),true);});

test('Android profile is initialized once on fresh target and is not repeated for observation reconnect',async t=>{const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});let profiles=0;f.manager.deps.applyProfile=async options=>{profiles++;assert.equal(options.guard(),true);assert.equal(options.bootstrapOrigin,f.bridge.url);return{cdpCommands:3,cdpTargetId:'cdp1',isAlive:()=>true,release(){}};};const target=await f.manager.createTarget('https://chatgpt.com/c/explicit');assert.equal(f.calls.filter(x=>x[0]==='Page.navigate').length,0);const s=await f.manager.connectTarget(target);await s.run('initialize',{clientProfile:'android-webview'});assert.equal(profiles,1);assert.equal(f.calls.filter(x=>x[0]==='Page.navigate').length,1);s.close();const resumed=await f.manager.connectTarget(target);await resumed.run('initialize',{clientProfile:'android-webview'});assert.equal(profiles,1);assert.equal(f.calls.filter(x=>x[0]==='Page.navigate').length,1);assert.equal(f.manager.status().bootstrapCdpCommands,3);});

test('Android profile selection is explicit and a failed bootstrap cannot navigate to the account',async t=>{const f=await fixture(t);f.config.browserClientProfile='android-webview';await assert.rejects(f.manager.createTarget(),/explicit/);f.config.extensionProfileBootstrap='cdp-emulation';f.manager.deps.applyProfile=async()=>{throw Error('synthetic emulation failure');};const target=await f.manager.createTarget('https://chatgpt.com/c/explicit');const session=await f.manager.connectTarget(target);await assert.rejects(session.run('initialize',{clientProfile:'android-webview'}),/emulation failure/);assert.equal(f.calls.filter(x=>x[0]==='Page.navigate').length,0);});

test('missing profile proof cannot bootstrap an already repurposed conversation target',async t=>{const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});let profiles=0;f.manager.deps.applyProfile=async()=>{profiles++;return{cdpCommands:3,cdpTargetId:'cdp1',isAlive:()=>true,release(){}};};const target=await f.manager.createTarget();f.bridge.targets=[{...target,url:'https://chatgpt.com/c/other'}];const session=await f.manager.connectTarget(target);await assert.rejects(session.run('initialize',{clientProfile:'android-webview'}),/initial target/);assert.equal(profiles,0);});

test('lost Android profile connection restores only the same existing target and reports continuity gap',async t=>{const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});const sessions=[];f.manager.deps.applyProfile=async options=>{const held={alive:true,options,cdpCommands:3,cdpTargetId:'cdp1',isAlive(){return this.alive;},release(){this.alive=false;}};sessions.push(held);return held;};const target=await f.manager.createTarget('https://chatgpt.com/c/explicit');const session=await f.manager.connectTarget(target);await session.run('initialize',{clientProfile:'android-webview'});sessions[0].alive=false;sessions[0].options.onLost();await session.run('snapshot');assert.equal(sessions.length,1,'observation does not require effect-profile repair');await session.run('initialize',{clientProfile:'android-webview'});assert.equal(sessions.length,2);assert.equal(sessions[1].options.rebind.cdpTargetId,'cdp1');assert.equal(sessions[1].options.rebind.expectedUrl,'https://chatgpt.com/c/explicit');assert.equal(f.calls.filter(x=>x[0]==='Target.createTarget').length,1);assert.equal(f.calls.filter(x=>x[0]==='Page.navigate').length,1);assert.equal(f.manager.status().profileContinuityUnverified,true);assert.equal(f.manager.status().bootstrapCdpCommands,6);await f.manager.close();assert.equal(sessions[1].alive,false);});

test('profile restoration respects caller deadline and does not borrow another session generation',async t=>{const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});let complete;f.manager.deps.applyProfile=async({signal})=>new Promise((resolve,reject)=>{complete=()=>resolve({cdpCommands:3,cdpTargetId:'cdp1',isAlive:()=>true,release(){}});signal.addEventListener('abort',()=>reject(signal.reason),{once:true});});const target=await f.manager.createTarget(),first=await f.manager.connectTarget(target),second=await f.manager.connectTarget(target);const before=Date.now();const work=first.run('initialize',{clientProfile:'android-webview'},{timeoutMs:30});await new Promise(r=>setTimeout(r,5));await assert.rejects(second.run('initialize',{clientProfile:'android-webview'}),e=>e.code==='PROFILE_INITIALIZATION_BUSY');await assert.rejects(work);assert.ok(Date.now()-before<500);assert.equal(f.calls.filter(x=>x[0]==='Page.navigate').length,0);});

test('late cleanup bootstrap proof is exact, owned, pending and conversation-bound',async t=>{const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});const url='https://chatgpt.com/c/pending';const target=await f.manager.createTarget(url);assert.equal(f.manager.isOwnedBootstrapTarget(target,digest(url)),true);for(const changed of [{...target,entryPending:false},{...target,url:'https://chatgpt.com/c/manual'},{...target,entryUrl:'https://chatgpt.com/c/other'},{...target,bootstrapUrl:target.bootstrapUrl+'x'},{...target,id:'foreign'}])assert.equal(f.manager.isOwnedBootstrapTarget(changed,digest(url)),false);assert.equal(f.manager.isOwnedBootstrapTarget(target,digest('https://chatgpt.com/c/other')),false);f.setProcess(null);assert.equal(f.manager.isOwnedBootstrapTarget(target,digest(url)),false);});
test('a proven stale same-host profile lock is left for Chromium normal recovery',async t=>{const {profileBusy}=await import('../src/browser/extension-manager.mjs');assert.equal(typeof profileBusy,'function');const root=await fs.mkdtemp(path.join(os.tmpdir(),'stale-profile-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));await fs.symlink(os.hostname()+'-2147483000',path.join(root,'SingletonLock'));assert.equal(profileBusy(root),false);assert.equal((await fs.lstat(path.join(root,'SingletonLock'))).isSymbolicLink(),true,'guard does not delete or change the lock');});
test('a live or foreign-host profile lock is never adopted',async t=>{const {profileBusy}=await import('../src/browser/extension-manager.mjs');assert.equal(typeof profileBusy,'function');const root=await fs.mkdtemp(path.join(os.tmpdir(),'live-profile-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));await fs.symlink(os.hostname()+'-'+process.pid,path.join(root,'SingletonLock'));assert.equal(profileBusy(root),true);await fs.rename(path.join(root,'SingletonLock'),path.join(root,'saved-lock'));await fs.symlink('different-host-2147483000',path.join(root,'SingletonLock'));assert.equal(profileBusy(root),true);});
test('an unrecognized regular profile lock fails closed',async t=>{const {profileBusy}=await import('../src/browser/extension-manager.mjs');assert.equal(typeof profileBusy,'function');const root=await fs.mkdtemp(path.join(os.tmpdir(),'opaque-profile-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));await fs.writeFile(path.join(root,'SingletonLock'),'opaque');assert.equal(profileBusy(root),true);});

async function androidEmpty(t){const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});f.manager.deps.applyProfile=async()=>({cdpCommands:3,cdpTargetId:'cdp1',isAlive:()=>true,release(){}});const target=await f.manager.createTarget();const session=await f.manager.connectTarget(target);await session.run('initialize',{clientProfile:'android-webview'});session.close();await f.manager.closeOwnedTarget(target.id,{guard:()=>true});return f;}
test('Android idle cleanup terminates only the proven empty owned root before immediate next acquisition',async t=>{const f=await androidEmpty(t),first=f.manager.journal.browserSessionId;const released=await f.manager.releaseIfUnused({guard:()=>true});assert.equal(released.released,true);assert.equal(f.signals,1);const target=await f.manager.createTarget();assert.ok(f.manager.isOwnedTarget(target.id));assert.equal(f.launches,2);assert.notEqual(f.manager.journal.browserSessionId,first);assert.equal(f.manager.journal.retiringProcess,null);});
test('a fresh manager retires a bound empty orphan instead of singleton CLI forwarding',async t=>{const f=await androidEmpty(t);f.setReady(false);await f.manager.close();const next=f.fresh();t.after(()=>next.close());await next.ensureStarted();const target=await next.createTarget();assert.ok(next.isOwnedTarget(target.id));assert.equal(f.signals,1);assert.equal(f.launches,2);});
test('any remaining page or worker blocks orphan termination',async t=>{for(const type of ['page','service_worker','background_page']){const f=await androidEmpty(t);f.setReady(false);f.setExtra([{id:'unrelated',type,url:'about:blank'}]);await assert.rejects(f.manager.ensureStarted(),e=>e.code==='EXTENSION_UNAVAILABLE');assert.equal(f.signals,0);assert.equal(f.launches,1);}});
test('missing or changed browser endpoint proof blocks empty-root termination',async t=>{for(const mode of ['missing','changed','malformed']){const f=await androidEmpty(t);f.setReady(false);if(mode==='missing')delete f.manager.journal.process.browserKey;if(mode==='changed')f.manager.journal.process.browserKey='ws://127.0.0.1:19227/devtools/browser/other';if(mode==='malformed')f.manager.deps.readCdpMetadata=async()=>({});await assert.rejects(f.manager.ensureStarted(),e=>e.code==='EXTENSION_UNAVAILABLE');assert.equal(f.signals,0);}});
test('ownership lost during endpoint inventory never signals the process',async t=>{const f=await androidEmpty(t);f.setReady(false);const read=f.manager.deps.readCdpMetadata;f.manager.deps.readCdpMetadata=async(...args)=>{const r=await read(...args);f.setProcess({pid:42,startTime:'reused',args:[]});return r;};await assert.rejects(f.manager.ensureStarted());assert.equal(f.signals,0);});
test('idle guard change during process inventory prevents any signal',async t=>{const f=await androidEmpty(t);let allowed=true;const scan=f.manager.deps.findOwnedBrowserRoots;f.manager.deps.findOwnedBrowserRoots=options=>{allowed=false;return scan(options);};const result=await f.manager.releaseIfUnused({guard:()=>allowed});assert.equal(result.released,false);assert.equal(f.signals,0);});
test('signal failure keeps durable retirement quarantine and never cold-launches',async t=>{const f=await androidEmpty(t);f.setReady(false);f.manager.deps.signalProcess=()=>{throw Object.assign(Error('denied'),{code:'EPERM'});};await assert.rejects(f.manager.ensureStarted());assert.equal(f.launches,1);assert.ok(f.manager.journal.retiringProcess);const saved=JSON.parse(await fs.readFile(path.join(f.config.dataDir,'extension-ownership.json'),'utf8'));assert.equal(saved.retiringProcess.pid,42);});
test('active profile initialization or lease blocks empty-orphan retirement',async t=>{for(const slot of ['profileLeases','profileInitializations']){const f=await androidEmpty(t);f.setReady(false);f.manager[slot].set('pending',{release(){}});await assert.rejects(f.manager.ensureStarted());assert.equal(f.signals,0);f.manager[slot].clear();}});

test('an unsent pending controller stop is reconciled without blocking acquisition',async t=>{const f=await androidEmpty(t);f.manager.journal.controllerStopPending=true;const target=await f.manager.createTarget();assert.ok(f.manager.isOwnedTarget(target.id));assert.equal(f.manager.journal.controllerStopPending,false);assert.equal(f.signals,0);assert.equal(f.launches,1);});
test('Android target stopped before initialization retains endpoint proof and can restart',async t=>{const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});const target=await f.manager.createTarget();assert.ok(f.manager.journal.process.browserKey);await f.manager.closeOwnedTarget(target.id,{guard:()=>true});assert.equal((await f.manager.releaseIfUnused({guard:()=>true})).released,true);assert.ok(await f.manager.createTarget());assert.equal(f.signals,1);});
test('failed profile initialization can close and retire its empty owned browser',async t=>{const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});f.manager.deps.applyProfile=async()=>{throw Error('profile failed');};const target=await f.manager.createTarget(),session=await f.manager.connectTarget(target);await assert.rejects(session.run('initialize',{clientProfile:'android-webview'}));session.close();await f.manager.closeOwnedTarget(target.id,{guard:()=>true});assert.equal((await f.manager.releaseIfUnused({guard:()=>true})).released,true);assert.equal(f.signals,1);});
test('idle cleanup does not require CDP endpoint proof to dispose the exact SelfRun root',async t=>{const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});const target=await f.manager.createTarget();await f.manager.closeOwnedTarget(target.id,{guard:()=>true});f.manager.deps.readCdpMetadata=async()=>{throw Error('unavailable');};const result=await f.manager.releaseIfUnused({guard:()=>true});assert.equal(result.released,true);assert.equal(f.calls.filter(c=>c[0]==='controllerStop').length,0);assert.equal(f.signals,1);});

test('probe-only SelfRun browser is retired once global work is idle',async t=>{const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});await f.manager.ensureStarted();const r=await f.manager.releaseIfUnused({guard:()=>true});assert.equal(r.released,true);assert.equal(f.signals,1);assert.ok(await f.manager.createTarget());assert.equal(f.launches,2);});
test('queued acquisition waits through delayed proven root exit',async t=>{
 const f=await androidEmpty(t),signal=f.manager.deps.signalProcess;let stopping=false,delays=0;
 f.manager.deps.signalProcess=()=>{stopping=true;};f.manager.deps.delay=async()=>{if(stopping&&++delays===3)signal();};
 const release=f.manager.releaseIfUnused({guard:()=>true}),acquire=f.manager.createTarget();
 assert.equal((await release).released,true);assert.ok(await acquire);assert.equal(delays,3);assert.equal(f.signals,1);assert.equal(f.launches,2);
});
test('accepted SIGTERM with surviving root retains quarantine across manager restoration',async t=>{const f=await androidEmpty(t);f.setReady(false);f.manager.deps.signalProcess=()=>{};await assert.rejects(f.manager.ensureStarted());assert.equal(f.launches,1);assert.ok(f.manager.journal.retiringProcess);await f.manager.close();const next=f.fresh();next.deps.signalProcess=()=>{};t.after(()=>next.close());await assert.rejects(next.ensureStarted());assert.equal(f.launches,1);assert.ok(next.journal.retiringProcess);});
test('close during final endpoint binding never resurrects a profile lease',async t=>{const f=await fixture(t);Object.assign(f.config,{browserClientProfile:'android-webview',extensionProfileBootstrap:'cdp-emulation'});let released=false;f.manager.deps.applyProfile=async()=>({cdpCommands:3,cdpTargetId:'cdp1',isAlive:()=>true,release(){released=true;}});const target=await f.manager.createTarget(),session=await f.manager.connectTarget(target),read=f.manager.deps.readCdpMetadata;let lists=0;f.manager.deps.readCdpMetadata=async(...args)=>{const result=await read(...args);if(args[1]==='list'&&++lists===2)f.manager.owned.delete(target.id);return result;};await assert.rejects(session.run('initialize',{clientProfile:'android-webview'}));assert.equal(released,true);assert.equal(f.manager.profileLeases.size,0);});

test('cold cleanup inspection loads durable journal without launching or signalling browser',async t=>{
 const f=await fixture(t);await f.manager.createTarget();await f.manager.close();const fresh=f.fresh();t.after(()=>fresh.close());
 assert.equal(fresh.journal,null);const proof=await fresh.inspectCleanupDisposition('legacy-cdp-target');
 assert.equal(proof.reason,'UNADDRESSABLE_NAMESPACE');assert.equal(f.launches,1);assert.equal(f.signals,0);
 assert.equal(await fresh.inspectCleanupDisposition(`ext:${fresh.journal.browserSessionId}:unknown`),null);
});

import {LifecycleStore} from '../src/lifecycle-store.mjs';
import {TerminalBrowserCleanup} from '../src/terminal-browser-cleanup.mjs';
test('cold terminal startup discards old cleanup without browser actions',async t=>{
 const f=await fixture(t);await f.manager.createTarget();await f.manager.close();const fresh=f.fresh();t.after(()=>fresh.close());
 const key=JSON.stringify(['TASK','TURN','REQ']),marker={key,task_id:'TASK',turn_id:'TURN',request_id:'REQ',state:'STOPPED',retired:true,control_epoch:1};
 const entry={key,target_id:'legacy-cdp-id',task_id:'TASK',turn_id:'TURN',request_id:'REQ',control_epoch:1};
 const store={value:{generation:1,lifecycle:{requests:{},controls:{TASK:{task_id:'TASK',turn_id:'TURN',request_id:'REQ',state:'STOPPED',control_epoch:1}},retired:{[key]:marker},browserCleanup:{[entry.target_id]:entry},outbox:{},completionOutbox:{},events:[]}},snapshot(){return structuredClone(this.value)},async patch(p){this.value={...this.value,...structuredClone(p)}},async update(fn){return this.patch(fn(this.snapshot()))}};
 const repository=new LifecycleStore(store),cleanup=new TerminalBrowserCleanup({repository,chromium:fresh,stateStore:store,available:()=>true,busy:()=>false});
 await repository.initialize('');await cleanup.run();await cleanup.run();assert.deepEqual(repository.data.browserCleanup,{});assert.equal(Object.keys(repository.data.browserCleanupUnresolved||{}).length,0);assert.equal(f.signals,1);assert.equal(f.launches,1);assert.equal(f.calls.filter(c=>c[0]==='Target.closeTarget'||c[0]==='controllerStop').length,0);
});

test('actor acquisition and input setup proceed while the observer reconnects',async t=>{
 const f=await androidEmpty(t);f.setReady(false);
 f.bridge.waitReady=async()=>{throw Error('observer hello must not block actor setup');};
 const target=await f.manager.createTarget('https://chatgpt.com/c/next'),session=await f.manager.connectTarget(target);
 await session.run('initialize',{clientProfile:'android-webview'});await session.run('input',{prompt:'next prompt'});
 assert.ok(f.manager.isOwnedTarget(target.id));assert.equal(f.signals,0);assert.equal(f.launches,1);
 assert.equal(f.calls.filter(c=>c[0]==='registerTarget').length,0);
});

test('observer readiness remains pending until hello without delaying actor acquisition',async t=>{
 const f=await androidEmpty(t);f.setReady(false);
 const target=await f.manager.createTarget('https://chatgpt.com/c/next'),session=await f.manager.connectTarget(target);
 await session.run('initialize',{clientProfile:'android-webview'});
 f.bridge.waiters=new Set();f.bridge.connection={ready:false};f.bridge.waitReady=ExtensionBridge.prototype.waitReady;
 t.mock.timers.enable({apis:['Date','setTimeout','setInterval']});
 let finished=false;const observe=session.run('snapshot').then(x=>{finished=true;return x;});
 for(let i=0;i<30&&!f.bridge.waiters.size;i++)await new Promise(resolve=>setImmediate(resolve));
 assert.equal(f.bridge.waiters.size,1);t.mock.timers.tick(4000);await Promise.resolve();assert.equal(finished,false);
 f.setReady(true);f.bridge.connection.ready=true;for(const ready of [...f.bridge.waiters])ready();
 assert.equal((await observe).url,'https://chatgpt.com/');assert.equal(f.signals,0);assert.equal(f.launches,1);
});

for(const reason of ['abort','closing','owner'])test('observer readiness stops on '+reason+' without registration or page commands',async t=>{
 const f=await androidEmpty(t),target=await f.manager.createTarget('https://chatgpt.com/c/next'),session=await f.manager.connectTarget(target);
 await session.run('initialize',{clientProfile:'android-webview'});f.setReady(false);
 f.bridge.waiters=new Set();f.bridge.connection={ready:false};f.bridge.waitReady=ExtensionBridge.prototype.waitReady;
 const abort=new AbortController(),before=f.calls.length;
 const work=session.run('snapshot',{}, {signal:abort.signal});const rejected=assert.rejects(work);
 for(let i=0;i<30&&!f.bridge.waiters.size;i++)await new Promise(resolve=>setImmediate(resolve));
 assert.equal(f.bridge.waiters.size,1);
 const stoppedAt=Date.now();if(reason==='abort')abort.abort();else if(reason==='closing')f.manager.closing.add(target.id);else f.setProcess(null);
 await rejected;assert.ok(Date.now()-stoppedAt<500,'owner loss must cancel promptly instead of exhausting readiness timeout');assert.equal(f.bridge.waiters.size,0);assert.equal(f.calls.length,before);assert.equal(f.signals,0);assert.equal(f.launches,1);
});

test('dev14 persists cleared stop flags after proven Android root exit',async t=>{
 const f=await androidEmpty(t),originalProcess=structuredClone(f.manager.journal.process);
 const result=await f.manager.releaseIfUnused({guard:()=>true});assert.equal(result.released,true);
 const saved=JSON.parse(await fs.readFile(path.join(f.config.dataDir,'extension-ownership.json'),'utf8'));
 assert.equal(saved.controllerStopPending,false);assert.equal(saved.retiringProcess,null);assert.deepEqual(saved.targets,[]);assert.deepEqual(saved.targetMetadata,{});
 assert.equal(saved.process,null,'no old Chromium PID may remain in an idle server');assert.equal(f.signals,1);assert.ok(originalProcess.pid);
});
test('dev14 reconciles a disconnected already-exited owner without browser commands',async t=>{
 const f=await androidEmpty(t);f.manager.journal.controllerStopPending=true;f.manager.journal.retiringProcess={pid:42,startTime:'123',bootId:'boot',browserKey:f.manager.journal.process.browserKey};
 const before=structuredClone(f.manager.journal);await fs.writeFile(path.join(f.config.dataDir,'extension-ownership.json'),JSON.stringify(before));f.setProcess(null);f.setReady(false);const calls=f.calls.length;
 assert.equal((await f.manager.releaseIfUnused({guard:()=>true})).released,true);
 const saved=JSON.parse(await fs.readFile(path.join(f.config.dataDir,'extension-ownership.json'),'utf8'));
 assert.equal(saved.process,null);assert.deepEqual(saved.targets,[]);assert.deepEqual(saved.targetMetadata,{});assert.equal(saved.controllerStopPending,false);assert.equal(saved.retiringProcess,null);assert.notEqual(saved.browserSessionId,before.browserSessionId);assert.equal(f.calls.length,calls);assert.equal(f.signals,0);
});
test('idle release persists exited-root state without controller mutation',async t=>{
 const f=await androidEmpty(t);
 assert.equal((await f.manager.releaseIfUnused({guard:()=>true})).released,true);
 const saved=JSON.parse(await fs.readFile(path.join(f.config.dataDir,'extension-ownership.json'),'utf8'));
 assert.equal(saved.controllerStopPending,false);assert.equal(saved.retiringProcess,null);assert.equal(saved.process,null);assert.equal(f.signals,1);
 assert.equal(f.calls.some(call=>call[0]==='controllerStop'),false);
});
test('dev14 rechecks new work after fsync and never commits stale stop finalization',async t=>{
 const f=await androidEmpty(t);let allowed=true,finalizing=false;f.manager.deps.signalProcess=()=>{f.setProcess(null);finalizing=true;};
 const open=fs.open;t.mock.method(fs,'open',async(...args)=>{const handle=await open(...args);if(!finalizing||!String(args[0]).includes('extension-ownership.json.tmp-'))return handle;return{async sync(){await handle.sync();allowed=false;},async close(){await handle.close();}};});
 const result=await f.manager.releaseIfUnused({guard:()=>allowed});assert.equal(result.released,false);
 const saved=JSON.parse(await fs.readFile(path.join(f.config.dataDir,'extension-ownership.json'),'utf8'));assert.equal(saved.controllerStopPending,false);assert.equal(saved.retiringProcess,null);assert.ok(saved.process);assert.equal(f.manager.journal.controllerStopPending,false);assert.ok(!(await fs.readdir(f.config.dataDir)).some(x=>x.includes('.tmp-')));
});

test('a reused PID is an exited old owner and does not block the first new task',async t=>{
  const f=await fixture(t);const first=await f.manager.createTarget();const old=structuredClone(f.manager.journal.process);
  f.setProcess({...old,startTime:'other-process',args:['unrelated']});await f.manager.close();
  const next=f.fresh();t.after(()=>next.close());const target=await next.createTarget();
  assert.notEqual(target.id,first.id);assert.equal(f.signals,0);assert.equal(f.launches,2);
});
test('reboot or PID reuse proves exit but unreadable same PID does not',()=>{
  const proof={pid:42,startTime:'123',bootId:'boot',profile:'/private',extensionPath:'/extension',ownerId:'owner'};
  const deps={processIdentity:()=>({pid:42,startTime:'456',state:'S'}),processExists:()=>true,bootId:()=> 'boot'};
  assert.equal(extensionProcessExited(proof,deps),true);
  deps.processIdentity=()=>null;assert.equal(extensionProcessExited(proof,deps),false);
  deps.bootId=()=> 'next-boot';assert.equal(extensionProcessExited(proof,deps),true);
});
test('idle cleanup also retires a SelfRun-marked orphan root absent from the journal',async t=>{
  const f=await fixture(t);await f.manager.ensureStarted();f.setProcess(null);f.manager.journal.process=null;
  const proof={pid:77,startTime:'777',bootId:'boot',profile:f.config.browserProfile,extensionPath:f.manager.extensionPath,ownerId:'owner'};
  let alive=true,signals=0;f.manager.deps.findOwnedBrowserRoots=()=>alive?[proof]:[];
  f.manager.deps.processIdentity=pid=>pid===77&&alive?{pid:77,startTime:'777',state:'S',args:['chrome',`--user-data-dir=${proof.profile}`,`--load-extension=${proof.extensionPath}`,'--selfrun-extension-owner=owner']}:null;
  f.manager.deps.processExists=pid=>pid===77&&alive;f.manager.deps.signalProcess=pid=>{assert.equal(pid,77);signals++;alive=false;};
  const result=await f.manager.releaseIfUnused({guard:()=>true});assert.equal(result.released,true);assert.equal(signals,1);assert.equal(f.manager.journal.process,null);
});
test('unproven root identity and current work block idle signals',async t=>{
  const f=await fixture(t);await f.manager.createTarget();assert.equal((await f.manager.releaseIfUnused({guard:()=>false})).reason,'guard_rejected');assert.equal(f.signals,0);
  f.setProcess({pid:42,startTime:'123',args:['unrelated']});const result=await f.manager.releaseIfUnused({guard:()=>true});assert.equal(result.reason,'ownership_unproven');assert.equal(f.signals,0);
});
test('failed idle SIGTERM does not erase ownership or report cleanup success',async t=>{
  const f=await fixture(t);await f.manager.createTarget();f.manager.deps.signalProcess=()=>{};
  const result=await f.manager.releaseIfUnused({guard:()=>true});assert.equal(result.reason,'browser_exit_unconfirmed');assert.equal(result.released,false);assert.ok(f.manager.journal.process);
});


test('ownership journal waits for an older delayed save before committing idle retirement',async t=>{
  const f=await fixture(t),target=await f.manager.createTarget();
  const file=path.join(f.config.dataDir,'extension-ownership.json');
  let reached,unblock;const entering=new Promise(resolve=>reached=resolve),gate=new Promise(resolve=>unblock=resolve);
  t.after(()=>unblock());
  const original=fs.rename.bind(fs);let held=false;
  t.mock.method(fs,'rename',async(...args)=>{
    if(!held&&args[1]===file){held=true;reached();await gate;}
    return original(...args);
  });
  f.bridge.emit('targetRemoved',{targetId:target.id});
  await Promise.race([entering,new Promise((_,reject)=>setTimeout(()=>reject(Error('old save did not start')),1500))]);
  f.setProcess(null);
  let finished=false;
  const cleanup=f.manager.releaseIfUnused({guard:()=>true}).then(result=>{finished=true;return result;});
  await new Promise(resolve=>setTimeout(resolve,20));
  assert.equal(finished,false,'idle clear must not bypass the earlier ownership file write');
  unblock();
  assert.equal((await cleanup).released,true);
  await f.manager.saveChain;
  const disk=JSON.parse(await fs.readFile(file,'utf8'));
  assert.equal(disk.process,null);assert.deepEqual(disk.targets,[]);assert.deepEqual(disk.targetMetadata,{});
  assert.equal(f.manager.journal.process,null);
});

test('a callback queued after idle retirement cannot restore a stale process owner',async t=>{
  const f=await fixture(t),target=await f.manager.createTarget();
  const file=path.join(f.config.dataDir,'extension-ownership.json');
  f.setProcess(null);
  let reached,unblock;const entering=new Promise(resolve=>reached=resolve),gate=new Promise(resolve=>unblock=resolve);
  t.after(()=>unblock());
  const original=fs.rename.bind(fs);let held=false,writes=0;
  t.mock.method(fs,'rename',async(...args)=>{
    if(args[1]===file){writes++;if(!held){held=true;reached();await gate;}}
    return original(...args);
  });
  const cleanup=f.manager.releaseIfUnused({guard:()=>true});
  await Promise.race([entering,new Promise((_,reject)=>setTimeout(()=>reject(Error('idle clear did not start')),1500))]);
  f.bridge.emit('targetRemoved',{targetId:target.id});
  unblock();
  assert.equal((await cleanup).released,true);
  await f.manager.saveChain;
  const disk=JSON.parse(await fs.readFile(file,'utf8'));
  assert.equal(disk.process,null);assert.deepEqual(disk.targets,[]);assert.deepEqual(disk.targetMetadata,{});
  assert.equal(writes,1,'late old-generation snapshot must be fenced before rename');
});

test('startup prunes every stale target metadata entry against current nonterminal lifecycle ownership',async t=>{
 const f=await fixture(t),target=await f.manager.createTarget('https://chatgpt.com/c/ended');
 const journal=JSON.parse(await fs.readFile(f.manager.journalFile,'utf8'));journal.targetMetadata['ext:orphan:99']={entryUrl:'https://chatgpt.com/c/old'};
 await fs.writeFile(f.manager.journalFile,JSON.stringify(journal));await f.manager.close();
 const next=f.fresh();t.after(()=>next.close());next.setLifecycleGuards({activeTargetIds:()=>[]});
 await next.inspectCleanupDisposition('legacy-cdp');
 assert.deepEqual([...next.owned],[]);assert.deepEqual(next.journal.targets,[]);assert.deepEqual(next.journal.targetMetadata,{});
 const saved=JSON.parse(await fs.readFile(next.journalFile,'utf8'));assert.deepEqual(saved.targets,[]);assert.deepEqual(saved.targetMetadata,{});
 assert.equal(f.launches,1);assert.equal(f.signals,0);
});

test('startup keeps the exact active-owned target while dropping stale sibling registry metadata',async t=>{
 const f=await fixture(t),active=await f.manager.createTarget('https://chatgpt.com/c/active');
 const journal=JSON.parse(await fs.readFile(f.manager.journalFile,'utf8')),stale=`ext:${journal.browserSessionId}:88`;
 journal.targets.push(stale);journal.targetMetadata[stale]={entryUrl:'https://chatgpt.com/c/ended'};await fs.writeFile(f.manager.journalFile,JSON.stringify(journal));await f.manager.close();
 const next=f.fresh();t.after(()=>next.close());next.setLifecycleGuards({activeTargetIds:()=>[active.id]});await next.inspectCleanupDisposition('legacy');
 assert.deepEqual([...next.owned],[active.id]);assert.deepEqual(next.journal.targets,[active.id]);assert.deepEqual(Object.keys(next.journal.targetMetadata),[active.id]);
 assert.deepEqual(next.journal.targetMetadata[active.id],journal.targetMetadata[active.id]);assert.equal(f.signals,0);assert.equal(f.launches,1);
});
