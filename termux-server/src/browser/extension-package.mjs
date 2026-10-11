import {createHash} from 'node:crypto';
import {buildContentSource} from './extension-page-source.mjs';
import {controllerRuntime} from './extension-controller-source.mjs';
export {buildPageRuntimeSource} from './extension-page-source.mjs';
export const EXTENSION_ID='pcjeefeplaheaablkijhaanjcjknnelj';
const PUBLIC_KEY='MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2nAZbJ+IPvpScKjKp6HlSNySdeTKSQNQplPTNreN6p8o2kNtSzMEU+cfqlTWaoRdg45XTGIXDNeTsqaWLxKwEv3yeTUG82gi60yOR9QVQLfp+BcQnC4lFED9NZqhtHOzjH5RRboMnfXW17N67BvFQ6p2VEyfd1tKsdsZnEjzDu7cBiqMvkeT93pvEBnduRMjzIjCHHhjgsqSTiXnsqipcNEm2FUNf2Pa/BA+TceCvql/G8AvhyrAlysa5ca3EJv6jnncLOBjEt6rxoXhHLQ8bhXjatlMgiZjsd5tJnHxbWuIIIK2HtcQ7bkLwfUJVjkdPm0dw2m6tNMXAejuG2lfpQIDAQAB';

export function buildExtensionFiles({bridgeUrl,ownerId,capability,browserSessionId=ownerId,fixtureOrigin=null}) {
  const bridge=new URL(bridgeUrl);if(bridge.protocol!=='http:'||bridge.hostname!=='127.0.0.1'||bridge.pathname!=='/'||bridge.search||bridge.hash)throw Error('Bridge must be a loopback origin');
  if(typeof ownerId!=='string'||!ownerId||typeof capability!=='string'||capability.length<16)throw Error('Local extension pairing is required');
  let targetOrigins=['https://chatgpt.com'];
  if(fixtureOrigin){const u=new URL(fixtureOrigin);if(u.protocol!=='http:'||u.hostname!=='127.0.0.1'||u.origin!==fixtureOrigin)throw Error('Fixture must be loopback only');targetOrigins=[fixtureOrigin];}
  const installationKey=createHash('sha256').update('selfrun-page-bootstrap:'+capability+':'+browserSessionId).digest('hex');
  const manifest={manifest_version:3,name:'SelfRun browser adapter',version:'1.0.2',key:PUBLIC_KEY,
    permissions:['scripting','storage'],host_permissions:fixtureOrigin?['http://127.0.0.1/*']:['https://chatgpt.com/*','http://127.0.0.1/*'],
    content_scripts:[{matches:fixtureOrigin?['http://127.0.0.1/*']:['https://chatgpt.com/*'],js:['content.js'],run_at:'document_start',all_frames:false}],
    content_security_policy:{extension_pages:"script-src 'self'; object-src 'self'"}};
  const config={bridgeUrl:bridge.origin,ownerId,capability,browserSessionId,targetOrigins,installationKey};
  return {'manifest.json':JSON.stringify(manifest,null,2)+'\n',
    'controller.html':'<!doctype html><meta charset="utf-8"><title>SelfRun controller</title><script src="controller.js"></script>\n',
    'controller.js':`(${controllerRuntime.toString()})(${JSON.stringify(config)});\n`,
    'content.js':buildContentSource()};
}
