import test from 'node:test';
import assert from 'node:assert/strict';
import { applyBrowserClientProfile } from '../src/browser/chatgpt.mjs';

test('native browser client profile is a no-op',async()=>{
  const calls=[];
  const session={call:async(method,params)=>{calls.push({method,params});return {};}};
  const result=await applyBrowserClientProfile(session,{browserClientProfile:'native'});
  assert.equal(result.mode,'native');
  assert.equal(calls.length,0);
});

test('android-webview profile overrides UA client hints metrics and touch',async()=>{
  const calls=[];
  const session={call:async(method,params)=>{calls.push({method,params});return {};}};
  const config={
    browserClientProfile:'android-webview',
    browserAndroidModel:'SM-S931N',
    browserAndroidVersion:'17',
    browserAndroidPlatformVersion:'17.0.0',
    browserAndroidBuildId:'BP4A.251205.006',
    browserAndroidChromeVersion:'149.0.7827.155',
    browserAndroidWidth:412,
    browserAndroidHeight:915,
    browserAndroidDeviceScaleFactor:3,
  };
  const result=await applyBrowserClientProfile(session,config);
  assert.equal(result.mode,'android-webview');
  assert.deepEqual(calls.map(x=>x.method),[
    'Emulation.setUserAgentOverride',
    'Emulation.setDeviceMetricsOverride',
    'Emulation.setTouchEmulationEnabled',
  ]);
  const ua=calls[0].params;
  assert.match(ua.userAgent,/Linux; Android 17; SM-S931N/);
  assert.match(ua.userAgent,/; wv\)/);
  assert.match(ua.userAgent,/Version\/4\.0 Chrome\/149\.0\.7827\.155 Mobile Safari\/537\.36/);
  assert.equal(ua.platform,'Android');
  assert.equal(ua.userAgentMetadata.platform,'Android');
  assert.equal(ua.userAgentMetadata.mobile,true);
  assert.equal(ua.userAgentMetadata.model,'SM-S931N');
  assert.equal(calls[1].params.mobile,true);
  assert.equal(calls[1].params.width,412);
  assert.equal(calls[1].params.height,915);
  assert.equal(calls[1].params.deviceScaleFactor,3);
  assert.deepEqual(calls[2].params,{enabled:true,maxTouchPoints:5});
});
