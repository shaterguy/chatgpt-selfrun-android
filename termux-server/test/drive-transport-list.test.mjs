import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchTransport } from '../src/drive-transport.mjs';

function json(body) {
  return new Response(JSON.stringify(body), {status:200,headers:{'content-type':'application/json'}});
}

test('Drive dispatch listing requests newest modified files first', async () => {
  const originalFetch = globalThis.fetch;
  const observed = [];
  globalThis.fetch = async (url) => {
    const value=String(url); observed.push(value);
    const q=new URL(value).searchParams.get('q')||'';
    if(q.includes("mimeType='application/vnd.google-apps.folder'")) return json({files:[]});
    return json({files:[
      {id:'1',name:'__SELFRUN_CONTROL__A.json',parents:['folder'],modifiedTime:'2026-10-01T09:00:00Z',size:'10'},
      {id:'2',name:'__SELFRUN_DISPATCH__A.json',parents:['folder'],modifiedTime:'2026-10-01T09:01:00Z',size:'20'}
    ]});
  };
  try {
    const transport = new DriveDispatchTransport({driveRunsFolderId:'folder'});
    transport.auth = {accessToken:async()=> 'token',forceRefresh:async()=>{}};
    const rows = await transport.list();
    assert.equal(new URL(observed.at(-1)).searchParams.get('orderBy'),'modifiedTime desc');
    assert.deepEqual(rows.map(r=>r.path),[
      '__SELFRUN_DISPATCH__A.json',
      '__SELFRUN_CONTROL__A.json'
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('Drive dispatch listing includes task-folder documents and moves legacy root task documents', async () => {
  const originalFetch = globalThis.fetch;
  const task='SR-20261006-123456-ABC123',root='runs',folder='task-folder';
  const requests=[];
  globalThis.fetch = async (url,init={}) => {
    const value=String(url),method=init.method||'GET'; requests.push({value,method});
    if(method==='PATCH') return json({id:'dispatch',parents:[folder]});
    const q=new URL(value).searchParams.get('q')||'';
    if(q.includes("mimeType='application/vnd.google-apps.folder'")) return json({files:[{id:folder,name:task}]});
    return json({files:[
      {id:'control',name:`__SELFRUN_CONTROL__${task}.json`,parents:[folder],modifiedTime:'2026-10-06T01:00:00Z',size:'10'},
      {id:'dispatch',name:`__SELFRUN_DISPATCH__${task}:turn:1-request__A1.json`,parents:[root],modifiedTime:'2026-10-06T01:01:00Z',size:'20'},
      {id:'other',name:'__SELFRUN_CONTROL__OTHER.json',parents:['elsewhere'],modifiedTime:'2026-10-06T01:02:00Z',size:'30'}
    ]});
  };
  try {
    const transport = new DriveDispatchTransport({driveRunsFolderId:root});
    transport.auth = {accessToken:async()=> 'token',forceRefresh:async()=>{}};
    const rows=await transport.list();
    assert.deepEqual(rows.map(r=>r.path),[
      `__SELFRUN_DISPATCH__${task}:turn:1-request__A1.json`,
      `__SELFRUN_CONTROL__${task}.json`,
    ]);
    const move=requests.find(r=>r.method==='PATCH');
    assert.ok(move);
    const moveUrl=new URL(move.value);
    assert.equal(moveUrl.searchParams.get('addParents'),folder);
    assert.equal(moveUrl.searchParams.get('removeParents'),root);
  } finally {
    globalThis.fetch=originalFetch;
  }
});


test('Drive dispatch resolution accepts task-folder file without prior listing', async () => {
  const originalFetch=globalThis.fetch;
  const task='SR-20261006-123456-ABC123',root='runs',folder='task-folder';
  const name=`__SELFRUN_CONTROL__${task}.json`;
  globalThis.fetch=async (url) => {
    const parsed=new URL(String(url));
    if(parsed.pathname==='/drive/v2/files/control') return json({etag:'v1'});
    if(parsed.pathname==='/drive/v3/files/control'&&parsed.searchParams.get('alt')==='media') return json({state:'pending'});
    const q=parsed.searchParams.get('q')||'';
    if(q.includes("mimeType='application/vnd.google-apps.folder'")) return json({files:[{id:folder,name:task}]});
    if(q.includes(`name='${name}'`)) return json({files:[{id:'control',name,parents:[folder]}]});
    throw new Error('unexpected request '+parsed.pathname+'?'+parsed.searchParams);
  };
  try {
    const transport=new DriveDispatchTransport({driveRunsFolderId:root});
    transport.auth={accessToken:async()=> 'token',forceRefresh:async()=>{}};
    assert.deepEqual(await transport.read(name),{state:'pending'});
  } finally {
    globalThis.fetch=originalFetch;
  }
});
