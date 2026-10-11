import test from 'node:test';
import assert from 'node:assert/strict';
import {ExtensionBridge} from '../src/browser/extension-bridge.mjs';
import {EXTENSION_ID} from '../src/browser/extension-package.mjs';
const ownerId='fixture-owner',capability='fixture-only-not-production-capability',browserSessionId='current-session';
async function fixture(t){
  const b=new ExtensionBridge({port:0,ownerId,capability,browserSessionId});await b.start();
  const abort=new AbortController();t.after(async()=>{abort.abort();await b.close();});
  const headers={Origin:`chrome-extension://${EXTENSION_ID}`,Authorization:`Bearer ${capability}`,'X-SelfRun-Owner':ownerId,'Content-Type':'application/json'};
  const post=(route,body,extra={})=>fetch(b.url+route,{method:'POST',headers:{...headers,...extra},body:JSON.stringify({controllerBuild:'selfrun-monitor-only-v2',...body}),signal:abort.signal});
  async function connect(){const response=await post('/events',{browserSessionId}),reader=response.body.getReader();let buffer='';
    async function next(){while(!buffer.includes('\n')){const {value,done}=await reader.read();if(done)throw Error('closed');buffer+=new TextDecoder().decode(value);}const n=buffer.indexOf('\n'),line=buffer.slice(0,n);buffer=buffer.slice(n+1);return JSON.parse(line);}
    const welcome=await next();await post('/event',{browserSessionId,connectionId:welcome.connectionId,event:'hello',extensionId:EXTENSION_ID,controllerBuild:'selfrun-monitor-only-v2'});
    return{next,connectionId:welcome.connectionId};}
  return{b,post,connect};
}
test('session admission is read-only, authenticated and returns no session or capability',async t=>{
  const f=await fixture(t);
  const ok=await f.post('/session',{browserSessionId});assert.equal(ok.status,200);assert.deepEqual(await ok.json(),{ok:true});
  assert.equal(f.b.connection,null);assert.equal(f.b.pendingCount,0);
  assert.equal((await f.post('/session',{browserSessionId:'old'})).status,409);
  assert.equal((await f.post('/session',{browserSessionId},{Authorization:'Bearer wrong'})).status,403);
  assert.equal((await f.post('/session',{browserSessionId},{Origin:'https://chatgpt.com'})).status,403);
});
for(const body of [{},{browserSessionId:'old'}])test('missing or stale session cannot replace a current observation connection '+JSON.stringify(body),async t=>{
  const f=await fixture(t),current=await f.connect();
  const p=f.b.request('progress',{targetId:'owned',prompt:'fixture input'});const command=await current.next();
  const bad=await f.post('/events',body);assert.equal(bad.status,409);
  assert.equal(f.b.status().connected,true);assert.equal(f.b.pendingCount,1);
  assert.equal(f.b.connection.id,current.connectionId);
  await f.post('/result',{browserSessionId,connectionId:current.connectionId,id:command.id,ok:true,result:{status:'READY',length:13}});
  assert.equal((await p).status,'READY');
});
test('health identifies the loaded monitor-only controller build',async t=>{
  const f=await fixture(t);await f.connect();assert.equal(f.b.status().controllerBuild,'selfrun-monitor-only-v2');
});
