import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {initialRecord,newIntent} from '../src/lifecycle.mjs';
import {readLifecycleHistory,migrateLegacy} from '../src/lifecycle-migration.mjs';
import {fixture} from './helpers/lifecycle-fixture.mjs';
test('generic old Runtime.evaluate timeout cannot manufacture a send intent or release',async t=>{
 const f=fixture(),root=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-legacy-error-'));t.after(()=>fs.rm(root,{recursive:true,force:true}));
 const file=path.join(root,'events.jsonl');await fs.writeFile(file,JSON.stringify({event:'LIVENESS_RECOVERY_FAILED',details:{task_id:f.body.task_id,turn_id:f.body.turn_id,request_id:f.body.request_id,error:'CDP command timeout: Runtime.evaluate'}})+'\n');
 const record=initialRecord(f.path,f.body),result=migrateLegacy(record,await readLifecycleHistory(file),'continue');
 assert.equal(result.intent,null);assert.equal(result.state,'DISCOVERED');assert.equal(result.submission_confirmed,false);
});

for(const intent of [{},{sends:0,released:false},{id:'forged',kind:'initial',sends:'1',released:'true'}])test('external preparation-only intent cannot block current execution '+JSON.stringify(intent),async()=>{
 const f=fixture(),record=initialRecord(f.path,f.body);f.body.lifecycle_record={...record,schema:1,intent};
 await f.ingest();assert.equal(f.prepares,1);assert.equal(f.sends,1);assert.equal(f.record().state,'OBSERVING');await f.controller.quiesceForStandby();
});
for(const evidence of [{sends:1},{released:true},{receipt:{released:true}}])test('same-identity imported effect risk remains conservative '+JSON.stringify(evidence),()=>{
 const f=fixture(),record=initialRecord(f.path,f.body),intent={...newIntent(record,'initial','prepared prompt',{}),...evidence};
 const imported=initialRecord(f.path,{...f.body,lifecycle_record:{...record,schema:1,intent,target_id:'original-owned'}});
 assert.equal(imported.intent.id,intent.id);assert.equal(imported.intent.outcome,'UNKNOWN');assert.equal(imported.target_id,'original-owned');assert.equal(imported.intent.released,intent.released);
});
