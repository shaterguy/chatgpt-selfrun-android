import {ChromiumManager} from './cdp.mjs';
import {ExtensionManager} from './extension-manager.mjs';
export function createBrowserManager(config){
  const name=config.browserAdapter||'cdp';
  if(name==='cdp')return new ChromiumManager(config);
  if(name==='extension')return new ExtensionManager(config);
  throw new Error('Unsupported browser adapter; choose cdp or extension explicitly');
}
