import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';

test('a diagnostic publication outage cannot terminate the Node process',()=>{
  const helper=new URL('./helpers/lifecycle-fixture.mjs',import.meta.url).href;
  const script=`
    import {fixture,deferred} from ${JSON.stringify(helper)};
    const f=fixture();f.mode='unknown';f.publishConversationBeforeOutcome=true;
    await f.ingest();
    const entered=deferred(),release=deferred();
    f.browser.readSubmission=async()=>{entered.resolve();await release.promise;return {state:'UNKNOWN'};};
    await f.controller.tick(f.transport);await entered.promise;
    f.writeFail=true;release.resolve();
    await new Promise(resolve=>setTimeout(resolve,30));
    f.writeFail=false;await f.controller.quiesceForStandby();
    console.log('SURVIVED');
  `;
  const result=spawnSync(process.execPath,['--unhandled-rejections=strict','--input-type=module','-e',script],
    {encoding:'utf8',timeout:5000});
  assert.equal(result.status,0,result.stderr);assert.match(result.stdout,/SURVIVED/);
  assert.match(result.stderr,/DRIVE_PROJECTION_PENDING/);
  assert.doesNotMatch(result.stderr,/OBSERVATION_CALLBACK_FAILED|RECEIPT_DIAGNOSTIC_FAILED/);
});
