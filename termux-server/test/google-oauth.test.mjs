import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { GoogleOAuthTokenProvider } from '../src/google-oauth.mjs';

test('OAuth refresh is shared across provider instances and atomically replaces the token file', async () => {
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-oauth-'));
  const client=path.join(dir,'client.json'),token=path.join(dir,'token.json');
  await fs.writeFile(client,JSON.stringify({client_id:'client'}));
  await fs.writeFile(token,JSON.stringify({refresh_token:'refresh',access_token:'expired',expires_at_ms:0}));
  const originalFetch=globalThis.fetch;let calls=0;
  globalThis.fetch=async()=>{calls+=1;await new Promise(r=>setTimeout(r,20));return new Response(JSON.stringify({access_token:'fresh',expires_in:3600}),{status:200,headers:{'content-type':'application/json'}});};
  try {
    const config={driveAuthClientFile:client,driveAuthTokenFile:token,driveRequestTimeoutMs:1000};
    const [a,b]=await Promise.all([new GoogleOAuthTokenProvider(config).accessToken(),new GoogleOAuthTokenProvider(config).accessToken()]);
    assert.equal(a,'fresh');assert.equal(b,'fresh');assert.equal(calls,1);
    const stored=JSON.parse(await fs.readFile(token,'utf8'));assert.equal(stored.access_token,'fresh');
    const leftovers=(await fs.readdir(dir)).filter(n=>n.includes('.tmp-'));assert.deepEqual(leftovers,[]);
  } finally { globalThis.fetch=originalFetch;await fs.rm(dir,{recursive:true,force:true}); }
});
