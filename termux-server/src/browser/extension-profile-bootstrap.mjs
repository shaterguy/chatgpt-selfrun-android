// Explicit compatibility exception: Android identity uses only three Emulation
// methods. The session remains attached while the owned target lives: detaching
// can clear Emulation. This connection never observes or submits page content.
import {createHash} from 'node:crypto';import {CdpSession} from './cdp.mjs';import {applyBrowserClientProfile} from './chatgpt.mjs';
const methods=new Set(['Emulation.setUserAgentOverride','Emulation.setDeviceMetricsOverride','Emulation.setTouchEmulationEnabled']);
const fields=['browserClientProfile','browserAndroidUserAgent','browserAndroidModel','browserAndroidVersion','browserAndroidPlatformVersion','browserAndroidBuildId','browserAndroidChromeVersion','browserAndroidAcceptLanguage','browserAndroidWidth','browserAndroidHeight','browserAndroidDeviceScaleFactor'];
export const extensionProfileFingerprint=config=>createHash('sha256').update(JSON.stringify(fields.map(k=>[k,config[k]??null]))).digest('hex');
const defaultConnect=async(url,{signal}={})=>{const session=new CdpSession(url);let onAbort;
  try{const connecting=session.connect();connecting.catch(()=>{});if(!signal){await connecting;return session;}
    const aborted=new Promise((_,reject)=>{onAbort=()=>{session.close();reject(signal.reason||Error('Profile connection aborted'));};if(signal.aborted)onAbort();else signal.addEventListener('abort',onAbort,{once:true});});
    await Promise.race([connecting,aborted]);if(signal.aborted)throw signal.reason;return session;
  }catch(error){session.close();throw error;}finally{if(onAbort)signal?.removeEventListener('abort',onAbort);}};
export async function applyExtensionClientProfile({target,config,signal,rebind=null,onLost=()=>{},bootstrapOrigin=`http://127.0.0.1:${config.extensionBridgePort}`,guard=()=>true,fetchImpl=fetch,connect=defaultConnect}){
  if(config.browserClientProfile==='native'||!config.browserClientProfile)return{mode:'native',cdpCommands:0};
  if(config.browserClientProfile!=='android-webview'||config.extensionProfileBootstrap!=='cdp-emulation')throw Error('Android profile requires explicit cdp-emulation bootstrap selection');
  let initial;try{initial=new URL(target?.bootstrapUrl);}catch{}
  const fresh=!!initial&&initial.origin===bootstrapOrigin&&initial.protocol==='http:'&&initial.hostname==='127.0.0.1'&&/^\/bootstrap\/[0-9a-f-]{36}$/.test(initial.pathname)&&!initial.search&&!initial.hash;
  if(!fresh)throw Error('Profile bootstrap requires a uniquely owned local initial target');
  let expectedUrl=target.bootstrapUrl;
  if(rebind){let url;try{url=new URL(rebind.expectedUrl);}catch{}const allowed=[bootstrapOrigin,config.extensionFixtureOrigin||'https://chatgpt.com'];if(!url||!allowed.includes(url.origin)||typeof rebind.cdpTargetId!=='string'||!rebind.cdpTargetId)throw Error('Existing profile target proof is invalid');expectedUrl=rebind.expectedUrl;}
  const port=Number(config.browserPort);if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Invalid loopback bootstrap port');
  const check=()=>{if(signal?.aborted)throw signal.reason||Error('Profile bootstrap aborted');if(guard()!==true)throw Error('Profile bootstrap ownership changed');};
  const inventory=async()=>{check();const r=await fetchImpl(`http://127.0.0.1:${port}/json/list`,{signal:signal?AbortSignal.any([signal,AbortSignal.timeout(3000)]):AbortSignal.timeout(3000)});if(!r.ok)throw Error('Profile bootstrap target inventory unavailable');const rows=await r.json();check();if(!Array.isArray(rows))throw Error('Profile bootstrap target inventory invalid');return rows;};
  const rows=await inventory(),matches=rows.filter(row=>row.type==='page'&&row.url===expectedUrl&&(!rebind||row.id===rebind.cdpTargetId));if(matches.length!==1)throw Error('Profile target is missing or ambiguous');const matched=matches[0];
  const ws=new URL(matched.webSocketDebuggerUrl);if(ws.protocol!=='ws:'||ws.hostname!=='127.0.0.1'||Number(ws.port)!==port||ws.pathname!==`/devtools/page/${matched.id}`)throw Error('Profile endpoint outside approved loopback scope');
  let session,commands=0,retained=false,released=false;
  const isAlive=()=>!released&&!!session&&(session.socket?session.socket.readyState===1:session.isAlive?session.isAlive():true);
  try{check();session=await connect(ws.href,{signal});check();await applyBrowserClientProfile({call:async(method,params)=>{if(!methods.has(method))throw Error('Unexpected profile operation');check();const current=(await inventory()).filter(row=>row.id===matched.id&&row.url===expectedUrl&&row.webSocketDebuggerUrl===matched.webSocketDebuggerUrl);if(current.length!==1)throw Error('Profile target changed');check();await session.call(method,params,{signal,timeoutMs:5000});commands++;check();}},config);
    if(!isAlive())throw Error('Profile connection closed during initialization');retained=true;
    session.socket?.addEventListener('close',()=>{if(!released)onLost();},{once:true});
    return{mode:'android-webview',cdpCommands:commands,fingerprint:extensionProfileFingerprint(config),cdpTargetId:matched.id,isAlive,release(){if(released)return;released=true;session.close();}};
  }finally{if(!retained)session?.close();}
}
