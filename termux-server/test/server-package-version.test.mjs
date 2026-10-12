import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { config } from '../src/config.mjs';

test('server runtime version matches distributed package version',async()=>{
  const pkg=JSON.parse(await readFile(new URL('../package.json',import.meta.url),'utf8'));
  assert.equal(config.version,pkg.version);
});
