import test from 'node:test';
import assert from 'node:assert/strict';
import {ExtensionBridge} from '../src/browser/extension-bridge.mjs';
import {EXTENSION_ID} from '../src/browser/extension-package.mjs';
const ownerId='owner-test',capability='fixture-capability-not-production-123456';
async function setup(t){const b=new ExtensionBridge({port:0,ownerId,capability,browserSessionId:'session-test'});await b.start();t.after(()=>b.close());return b;}
function headers(b,extra={}){return {Origin:`chrome-extension://${EXTENSION_ID}`,Authorization:`Bearer ${capability}`,'X-SelfRun-Owner':ownerId,...extra};}
async function connect(b){const abort=new AbortController();const r=await fetch(b.url+'/events',{method:'POST',body:'{}',headers:headers(b),signal:abort.signal});assert.equal(r.status,200);const reader=r.body.getReader();let buffer='';async function next(){while(!buffer.includes('\n')){const {value,done}=await reader.read();if(done)throw Error('stream closed');buffer+=new TextDecoder().decode(value);}const i=buffer.indexOf('\n'),line=buffer.slice(0,i);buffer=buffer.slice(i+1);return JSON.parse(line);}
const welcome=await next();const post=(path,data)=>fetch(b.url+path,{method:'POST',headers:{...headers(b),'Content-Type':'application/json'},body:JSON.stringify({browserSessionId:'session-test',connectionId:welcome.connectionId,...data})});await post('/event',{event:'hello',extensionId:EXTENSION_ID});return{next,post,abort,welcome};}
test('bridge rejects unauthenticated, foreign extension and owner requests',async t=>{const b=await setup(t);for(const extra of [{Authorization:'Bearer wrong'},{Origin:'https://chatgpt.com'},{'X-SelfRun-Owner':'other'}]){const r=await fetch(b.url+'/events',{headers:headers(b,extra)});assert.equal(r.status,403);}const missing=await fetch(b.url+'/events');assert.equal(missing.status,403);});
test('bridge accepts only named operations and correlates one current response',async t=>{const b=await setup(t),c=await connect(b);t.after(()=>c.abort.abort());await b.waitReady(100);await assert.rejects(b.request('evaluate',{expression:'1'}),/Unsupported/);const p=b.request('snapshot',{targetId:'ext:session-test:7'});const command=await c.next();assert.equal(command.operation,'snapshot');assert.equal(command.connectionId,c.welcome.connectionId);assert.equal((await c.post('/result',{id:command.id,ok:true,result:{url:'https://chatgpt.com/'}})).status,200);assert.equal((await p).url,'https://chatgpt.com/');assert.equal((await c.post('/result',{id:command.id,ok:true,result:{wrong:true}})).status,409);});
test('disconnect rejects sent mutation as uncertain and never replays it on reconnect',async t=>{const b=await setup(t),c=await connect(b);const p=b.request('submit',{targetId:'ext:session-test:7'});await c.next();const rejected=assert.rejects(p,e=>e.code==='EXTENSION_DISCONNECTED'&&e.released===true);c.abort.abort();await rejected;const d=await connect(b);t.after(()=>d.abort.abort());assert.equal(b.pendingCount,0);assert.equal(b.commandCount,1);});
test('abort sends cancellation and cleans pending command without claiming a click did not happen',async t=>{const b=await setup(t),c=await connect(b);t.after(()=>c.abort.abort());const abort=new AbortController();const p=b.request('submit',{targetId:'ext:session-test:7'},{signal:abort.signal});const command=await c.next();const rejected=assert.rejects(p,e=>e.name==='AbortError'&&e.released===true);abort.abort();await rejected;const cancel=await c.next();assert.equal(cancel.kind,'cancel');assert.equal(cancel.id,command.id);assert.equal(b.pendingCount,0);});
test('stale connection and browser session events cannot establish current identity',async t=>{const b=await setup(t),c=await connect(b);t.after(()=>c.abort.abort());assert.equal((await c.post('/event',{connectionId:'stale',event:'hello',extensionId:EXTENSION_ID})).status,409);assert.equal((await c.post('/event',{browserSessionId:'other',event:'hello',extensionId:EXTENSION_ID})).status,409);});
test('connection replacement fences pending actions and timeout sends cancel',async t=>{const b=await setup(t),c=await connect(b);t.after(()=>c.abort.abort());const p=b.request('input',{targetId:'ext:session-test:7'},{timeoutMs:20});await c.next();const rejection=assert.rejects(p,e=>e.code==='EXTENSION_TIMEOUT');await rejection;assert.equal((await c.next()).kind,'cancel');const q=b.request('snapshot',{});await c.next();const rejected=assert.rejects(q,/replaced/);const d=await connect(b);t.after(()=>d.abort.abort());await rejected;});
test('bridge is loopback only, validates frame limit and does not expose pairing secret in status',async t=>{const b=await setup(t);assert.equal(b.address.address,'127.0.0.1');assert.ok(!JSON.stringify(b.status()).includes(capability));const r=await fetch(b.url+'/event',{method:'POST',headers:headers(b),body:'x'.repeat(2*1024*1024+1)});assert.equal(r.status,413);});

test('readiness wakes on authenticated hello and releases all waiting resources',async t=>{
 const b=await setup(t);const waiting=b.waitReady(20000);assert.equal(b.waiters.size,1);
 const c=await connect(b);t.after(()=>c.abort.abort());await waiting;assert.equal(b.waiters.size,0);
});
test('request cancellation and owner loss interrupt readiness without a command',async t=>{
 const b=await setup(t),abort=new AbortController();
 const waiting=b.waitReady(20000,{signal:abort.signal});const rejection=assert.rejects(waiting,e=>e.code==='ABORT_ERR');
 abort.abort();await rejection;assert.equal(b.waiters.size,0);
 let owned=true;const ownerWait=b.waitReady(20000,{guard:()=>owned});owned=false;
 await assert.rejects(ownerWait,e=>e.code==='ABORT_ERR');assert.equal(b.waiters.size,0);assert.equal(b.commandCount,0);
});
test('bridge shutdown settles readiness waiters immediately',async t=>{
 const b=await setup(t),waiting=b.waitReady(20000);const rejection=assert.rejects(waiting,e=>e.code==='EXTENSION_DISCONNECTED');
 await b.close();await rejection;assert.equal(b.waiters.size,0);
});

for(const phase of ['before','during'])for(const kind of ['generation','timeout','stop'])test(`bridge preserves ${kind} cancellation ${phase} sending and fences late results`,async t=>{
 const b=await setup(t),c=await connect(b);t.after(()=>c.abort.abort());const a=new AbortController();
 const reason=kind==='generation'?Object.assign(new Error('observation generation replaced'),{code:'OBSERVATION_SUPERSEDED'}):kind==='timeout'?new DOMException('operation timeout','TimeoutError'):new Error('app STOP');
 if(phase==='before')a.abort(reason);
 const p=b.request('submit',{}, {signal:a.signal});let command;
 const rejected=assert.rejects(p,e=>e.cause===reason&&e.code===(kind==='generation'?'OBSERVATION_SUPERSEDED':kind==='timeout'?'EXTENSION_TIMEOUT':'ABORT_ERR')&&e.released===(phase==='during'));
 if(phase==='during'){command=await c.next();a.abort(reason);}await rejected;
 assert.equal(b.pendingCount,0);
 if(command){assert.equal((await c.next()).kind,'cancel');assert.equal((await c.post('/result',{id:command.id,ok:true,result:{old:true}})).status,409);}
 const current=b.request('snapshot',{}),next=await c.next();await c.post('/result',{id:next.id,ok:true,result:{current:true}});assert.deepEqual(await current,{current:true});
});
