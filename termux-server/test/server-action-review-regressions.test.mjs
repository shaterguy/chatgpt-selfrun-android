import test from 'node:test';
import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {ServerActionSession} from '../src/browser/server-action-session.mjs';

const initial='http://127.0.0.1:9223/bootstrap/00000000-0000-4000-8000-000000000000';
const destination='https://chatgpt.com/g/test/project';
function transport(state) {
  return {socket:{readyState:1},close(){this.socket.readyState=3;},async call(method,params){
    if(method==='Page.addScriptToEvaluateOnNewDocument'){state.installs++;return{identifier:'script-'+state.installs};}
    if(method==='Page.navigate'){state.navigates++;state.url=params.url;if(state.loseNavigationReply)throw Error('navigation response lost');return{};}
    if(params.expression==='location.href')return{result:{value:state.url}};
    const challenge=params.expression?.match(/attest\(("[^"]+")\);/);
    return {result:{value:challenge?createHmac('sha256','fixture').update(JSON.parse(challenge[1])).digest('hex'):true}};
  }};
}

test('review: approved navigation with lost response does not become an unrelated-page fence',async()=>{
  const state={url:initial,installs:0,navigates:0,loseNavigationReply:true};
  const session=new ServerActionSession({session:transport(state),installationKey:'fixture',initialUrl:initial,allowedOrigins:['https://chatgpt.com']});
  await assert.rejects(session.run('navigate',{url:destination}),/navigation response lost/);
  assert.equal(state.url,destination);
  assert.equal(await session.run('recoveryInput'),true);
  assert.equal(session.boundUrl,destination);
  assert.equal(state.navigates,1,'recovery must not re-create or replay already observed navigation');
});

test('review: direct install after transport disconnect restores helper installation',async()=>{
  const state={url:destination,installs:0,navigates:0},first=transport(state);let reconnects=0;
  const session=new ServerActionSession({session:first,installationKey:'fixture',initialUrl:destination,
    allowedOrigins:['https://chatgpt.com'],reconnect:async()=>{reconnects++;return transport(state);}});
  await session.install();first.close();
  session.armed={intentId:'existing',generation:'one'};session.released={...session.armed};
  assert.equal(await session.install(),true);
  assert.equal(reconnects,1);
  assert.equal(session.isAlive(),true);
  assert.equal(state.installs,2);
  assert.deepEqual(session.released,{intentId:'existing',generation:'one'});
  session.close();
});

import {purgeInactiveAction} from '../src/browser/server-action-session.mjs';
for(const scenario of ['mine','foreign','became-active'])test('inactive helper purge preserves scope: '+scenario,async()=>{
 const calls=[];let active=false;
 const result=await purgeInactiveAction({row:{id:'owned',type:'page',url:destination,webSocketDebuggerUrl:'ws://127.0.0.1:9222/devtools/page/owned'},port:9222,installationKey:'fixture',guard:()=>!active,connect:async()=>({close(){},async call(method,params){calls.push(params.expression);const challenge=params.expression.match(/attest\(("[^"]+")\)/);if(challenge){if(scenario==='became-active')active=true;return{result:{value:createHmac('sha256',scenario==='foreign'?'other-key':'fixture').update(JSON.parse(challenge[1])).digest('hex')}};}return{result:{value:true}};}})});
 assert.equal(result,scenario==='mine');assert.equal(calls.filter(s=>s.includes('terminalDispose')).length,scenario==='mine'?1:0);assert.equal(calls.some(s=>/install|input|navigate/.test(s)),false);
});
