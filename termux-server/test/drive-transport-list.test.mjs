import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchTransport } from '../src/drive-transport.mjs';

test('Drive dispatch listing scopes recovery window, paginates, and keeps newest same-name file', async () => {
  const originalFetch = globalThis.fetch;
  const urls = [];
  globalThis.fetch = async (url) => {
    const parsed = new URL(String(url)); urls.push(parsed);
    const pageToken = parsed.searchParams.get('pageToken');
    if (!pageToken) return new Response(JSON.stringify({
      nextPageToken: 'p2',
      files: [
        {id:'new-control',name:'__SELFRUN_CONTROL__A.json',modifiedTime:'2026-10-01T09:02:00Z',size:'11'},
        {id:'old-control',name:'__SELFRUN_CONTROL__A.json',modifiedTime:'2026-10-01T09:00:00Z',size:'10'},
      ],
    }), {status:200,headers:{'content-type':'application/json'}});
    return new Response(JSON.stringify({ files: [
      {id:'dispatch',name:'__SELFRUN_DISPATCH__A.json',modifiedTime:'2026-10-01T09:01:00Z',size:'20'},
    ]}), {status:200,headers:{'content-type':'application/json'}});
  };
  try {
    const transport = new DriveDispatchTransport({driveRunsFolderId:'folder',driveRequestTimeoutMs:1000});
    transport.auth = {accessToken:async()=> 'token',forceRefresh:async()=>{}};
    const cutoff = Date.parse('2026-10-01T08:00:00Z');
    const rows = await transport.list({modifiedAfterMs:cutoff});
    assert.equal(urls.length,2);
    assert.equal(urls[0].searchParams.get('orderBy'),'modifiedTime desc');
    assert.match(urls[0].searchParams.get('q'),/modifiedTime > '2026-10-01T08:00:00.000Z'/);
    assert.equal(urls[1].searchParams.get('pageToken'),'p2');
    assert.deepEqual(rows,[
      {path:'__SELFRUN_CONTROL__A.json',modTime:'2026-10-01T09:02:00Z',size:11},
      {path:'__SELFRUN_DISPATCH__A.json',modTime:'2026-10-01T09:01:00Z',size:20},
    ]);
  } finally { globalThis.fetch = originalFetch; }
});

test('exact Drive path resolution asks for newest duplicate first', async () => {
  const originalFetch = globalThis.fetch;
  const urls=[];
  globalThis.fetch = async (url) => {
    const value=String(url); urls.push(value);
    if (value.includes('/drive/v3/files?')) return new Response(JSON.stringify({files:[
      {id:'newest',name:'__SELFRUN_CONTROL__A.json',modifiedTime:'2026-10-01T09:02:00Z'},
      {id:'older',name:'__SELFRUN_CONTROL__A.json',modifiedTime:'2026-10-01T09:00:00Z'},
    ]}),{status:200});
    if (value.includes('/drive/v2/files/newest')) return new Response(JSON.stringify({id:'newest',etag:'v1',version:'1'}),{status:200});
    if (value.includes('/drive/v3/files/newest?alt=media')) return new Response(JSON.stringify({schema:'selfrun-task-control-v1'}),{status:200});
    throw new Error('unexpected '+value);
  };
  try {
    const transport=new DriveDispatchTransport({driveRunsFolderId:'folder',driveRequestTimeoutMs:1000});
    transport.auth={accessToken:async()=> 'token',forceRefresh:async()=>{}};
    const body=await transport.read('__SELFRUN_CONTROL__A.json');
    assert.equal(body.schema,'selfrun-task-control-v1');
    const resolve=new URL(urls.find(u=>u.includes('/drive/v3/files?')));
    assert.equal(resolve.searchParams.get('orderBy'),'modifiedTime desc');
    assert.ok(urls.some(u=>u.includes('/drive/v3/files/newest?alt=media')));
    assert.ok(!urls.some(u=>u.includes('/drive/v3/files/older?alt=media')));
  } finally { globalThis.fetch=originalFetch; }
});

test('Drive request timeout is bounded by runtime configuration', async () => {
  const originalFetch=globalThis.fetch;
  globalThis.fetch=async (_url,init={})=>new Promise((resolve,reject)=>{
    const signal=init.signal;
    signal.addEventListener('abort',()=>reject(signal.reason||new Error('aborted')),{once:true});
  });
  try {
    const transport=new DriveDispatchTransport({driveRunsFolderId:'folder',driveRequestTimeoutMs:1000});
    transport.auth={accessToken:async()=> 'token',forceRefresh:async()=>{}};
    const started=Date.now();
    await assert.rejects(transport.list());
    const elapsed=Date.now()-started;
    assert.ok(elapsed>=900 && elapsed<2500, `elapsed=${elapsed}`);
  } finally { globalThis.fetch=originalFetch; }
});
