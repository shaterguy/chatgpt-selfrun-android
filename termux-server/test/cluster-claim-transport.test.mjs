import test from 'node:test';
import assert from 'node:assert/strict';
import { SelfRunClusterTransport } from '../src/cluster-coordinator.mjs';
import { ExecutionClaims } from '../src/execution-claims.mjs';
import { keyFor } from '../src/lifecycle.mjs';

function provider(t) {
  const p={body:{schema:'selfrun-cluster-state-v1'},version:1,files:[{id:'cluster',name:'__SELFRUN_CLUSTER__.json'}],calls:[]};
  const json=(body,status=200)=>new Response(JSON.stringify(body),{status});
  t.mock.method(globalThis,'fetch',async(raw,init={})=>{
    const url=new URL(String(raw));p.calls.push({url:String(raw),method:init.method||'GET',headers:init.headers});
    if(url.pathname==='/drive/v3/files')return json({files:p.files});
    if(url.pathname==='/drive/v2/files/cluster')return json({id:'cluster',etag:'v'+p.version});
    if(url.pathname==='/drive/v3/files/cluster'&&url.searchParams.get('alt')==='media')return json(p.body);
    if(url.pathname==='/upload/drive/v2/files/cluster'&&init.method==='PUT') {
      if(p.conflictOnce){p.conflictOnce=false;p.version++;p.body.concurrent_field='preserve';return json({},412);}
      if(init.headers['if-match']!=='v'+p.version)return json({},412);
      p.body=JSON.parse(init.body);p.version++;return json({});
    }
    throw new Error('unexpected mock provider request '+url.pathname);
  });
  p.transport=new SelfRunClusterTransport({driveRunsFolderId:'folder'});
  p.transport.auth={accessToken:async()=> 'synthetic-fixture-token'};
  return p;
}

test('production cluster transport binds media to ETag and sends conditional PUT',async t=>{
  const p=provider(t),before=await p.transport.readCluster();
  await p.transport.writeCluster({...before,active_server:'Tablet'},{expected:before});
  const put=p.calls.find(c=>c.method==='PUT');assert.equal(put.headers['if-match'],'v1');
  assert.equal(p.body.active_server,'Tablet');assert.equal(p.calls.filter(c=>c.method==='PATCH').length,0);
  await assert.rejects(p.transport.writeCluster(before,{expected:structuredClone(before)}),/requires an ETag/);
});

test('real transport CAS 412 rereads authority and preserves concurrent fields',async t=>{
  const p=provider(t),claims=new ExecutionClaims({transport:p.transport,node:'Tablet'});
  const identity={task_id:'T',turn_id:'T:turn:1',request_id:'T:turn:1-request',control_epoch:1};
  p.conflictOnce=true;const claim=await claims.acquire(identity,{createAllowed:true});
  assert.equal(p.body.execution_claims[keyFor(identity)].token,claim.token);
  assert.equal(p.body.concurrent_field,'preserve');assert.equal(p.calls.filter(c=>c.method==='PUT').length,2);
  await claims.publish({schema:'selfrun-cluster-state-v1',active_server:'Phone'});
  assert.equal(p.body.execution_claims[keyFor(identity)].token,claim.token);
});

for(const count of [0,2])test('production refuses '+count+' canonical cluster files instead of acquiring unfenced authority',async t=>{
  const p=provider(t);p.files=Array.from({length:count},(_,n)=>({id:'cluster-'+n,name:'__SELFRUN_CLUSTER__.json'}));
  await assert.rejects(p.transport.readCluster(),/requires one canonical file/);
  assert.equal(p.calls.some(c=>c.method!=='GET'),false);
});
