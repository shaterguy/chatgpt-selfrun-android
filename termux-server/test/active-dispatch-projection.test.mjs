import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './helpers/lifecycle-fixture.mjs';

test('stopped requests are not projected as active dispatches', async () => {
  const f = fixture();
  await f.ingest();
  assert.equal(f.store.snapshot().activeCount,1);
  assert.equal(f.store.snapshot().activeDispatches.length,1);
  await f.stop();
  assert.equal(f.store.snapshot().activeCount,0);
  assert.deepEqual(f.store.snapshot().activeDispatches,[]);
});
