import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { config } from '../src/config.mjs';

test('server version is independent, server-scoped, and synchronized with package metadata', async () => {
  const pkg = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));
  assert.equal(config.component, 'selfrun-termux-server');
  assert.equal(config.versionScope, 'server');
  assert.equal(config.version, pkg.version);
  assert.equal(config.versionLabel, `server/${pkg.version}`);
  assert.match(config.version, /^1\./);
});
