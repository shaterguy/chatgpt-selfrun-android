import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchTransport } from '../src/drive-transport.mjs';

test('Drive dispatch listing requests newest modified files first', async () => {
  const originalFetch = globalThis.fetch;
  let observedUrl = null;
  globalThis.fetch = async (url) => {
    observedUrl = String(url);
    return new Response(JSON.stringify({
      files: [
        {id:'1',name:'__SELFRUN_CONTROL__A.json',modifiedTime:'2026-10-01T09:00:00Z',size:'10'},
        {id:'2',name:'__SELFRUN_DISPATCH__A.json',modifiedTime:'2026-10-01T09:01:00Z',size:'20'}
      ]
    }), {status:200,headers:{'content-type':'application/json'}});
  };
  try {
    const transport = new DriveDispatchTransport({driveRunsFolderId:'folder'});
    transport.auth = {accessToken:async()=> 'token',forceRefresh:async()=>{}};
    const rows = await transport.list();
    assert.equal(new URL(observedUrl).searchParams.get('orderBy'),'modifiedTime desc');
    assert.deepEqual(rows.map(r=>r.path),[
      '__SELFRUN_DISPATCH__A.json',
      '__SELFRUN_CONTROL__A.json'
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
