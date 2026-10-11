import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, deferred } from './helpers/lifecycle-fixture.mjs';

test('general and current-task publication never write the same Drive revision concurrently',async t=>{
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  const key=f.record().key;delete f.rows.get(f.path).conversation_url;
  await f.controller.repository.move(key,'OBSERVING','test pending projection');
  let version=1,writes=0;const entered=deferred(),release=deferred();
  const write=f.transport.write,read=f.transport.read;
  const transport={...f.transport,
    read:async path=>Object.assign(await read(path),{etag:version}),
    write:async(path,body,{expected})=>{
      writes++;if(writes===1){entered.resolve();await release.promise;}
      if(expected.etag!==version)throw Error('Drive write HTTP 412: If-Match Precondition Failed');
      version++;await write(path,body);
    },
  };
  const general=f.controller.repository.flush(transport);await entered.promise;
  const current=f.controller.repository.flushCurrent(transport,key);
  await new Promise(resolve=>setImmediate(resolve));const beforeRelease=writes;
  release.resolve();const outcomes=await Promise.allSettled([general,current]);
  assert.equal(beforeRelease,1,'only one same-task write can be in flight');
  assert.equal(outcomes.every(outcome=>outcome.status==='fulfilled'),true);
  assert.equal(writes,1,'the completed projection is not republished with a stale ETag');
});

test('a failed conditional publication leaves the pending state retryable',async t=>{
  const f=fixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
  const key=f.record().key;delete f.rows.get(f.path).conversation_url;await f.controller.repository.move(key,'OBSERVING','test pending projection');
  const write=f.transport.write;let attempts=0;
  f.transport.write=async(...args)=>{if(++attempts===1)throw Error('Drive write HTTP 412');return write(...args);};
  await assert.rejects(f.controller.repository.flushCurrent(f.transport,key),/412/);
  assert.ok(f.controller.repository.data.outbox[key]);
  await f.controller.repository.flushCurrent(f.transport,key);
  assert.equal(f.controller.repository.data.outbox[key],undefined);assert.equal(attempts,2);
});
