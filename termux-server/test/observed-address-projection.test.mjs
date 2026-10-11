import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {initialRecord,projection} from '../src/lifecycle.mjs';
import {StateStore} from '../src/state-store.mjs';
import {fixture} from './helpers/lifecycle-fixture.mjs';
const raw='https://chatgpt.com/g/g-p-6a582c824ba08191ac7e74e9bad721fc-vibe-coding/c/test-owned';

test('local observed address is projected verbatim without changing canonical ownership or importing remote observation fields',()=>{
 const f=fixture(),body={...f.body,conversation_url:'https://chatgpt.com/c/test-owned',observed_conversation_url:raw};
 const record=initialRecord('dispatch',body);
 body.lifecycle_record={schema:1,...record,observed_conversation_url:raw};
 const imported=initialRecord('dispatch',body);
 assert.equal(imported.observed_conversation_url,undefined);
 assert.equal(projection(imported).conversation_url,'https://chatgpt.com/c/test-owned');
 const local={...imported,observed_conversation_url:raw};
 assert.equal(projection(local).conversation_url,raw);
 assert.equal(local.conversation_url,'https://chatgpt.com/c/test-owned');
 assert.deepEqual(Object.keys(projection(local)).sort(),['conversation_url','dispatch_attempt','request_id','task_id','turn_id']);
 assert.equal(projection({...local,observed_conversation_url:'https://chatgpt.com/c/other'}).conversation_url,'https://chatgpt.com/c/other');
 const successor=initialRecord('next',{...body,turn_id:'next-turn',request_id:'next-request',conversation_url:''});
 assert.equal(successor.observed_conversation_url,undefined);assert.equal(projection(successor).conversation_url,'');
});

for(const state of ['STOPPED','DONE'])test('observed address survives a fresh disk store/controller and is purged by '+state,async t=>{
 const dataDir=await fs.mkdtemp(path.join(os.tmpdir(),'selfrun-observed-address-'));
 const config={dataDir,stateFile:path.join(dataDir,'state.json'),eventsFile:path.join(dataDir,'events.jsonl')};
 t.after(()=>fs.rm(dataDir,{recursive:true,force:true}));
 const store=new StateStore(config);await store.init();const f=fixture({store});f.probe.url=raw;
 t.after(()=>f.controller.quiesceForStandby());await f.ingest();await f.controller.flush(f.transport);
 assert.equal(f.record().conversation_url,'https://chatgpt.com/c/test-owned');assert.equal(f.rows.get(f.path).conversation_url,raw);
 await f.controller.quiesceForStandby();const reloaded=new StateStore(config);await reloaded.init();f.store=reloaded;f.controller=f.recreate();
 await f.controller.tick(f.transport);await f.controller.flush(f.transport);
 assert.equal(f.record().observed_conversation_url,raw);assert.equal(f.rows.get(f.path).conversation_url,raw);assert.equal(f.sends,1);
 f.control={...f.control,state,control_epoch:2};await f.controller.control(null,f.control,f.transport);
 assert.equal(f.record(),null);assert.deepEqual(f.store.snapshot().lifecycle.requests,{});assert.deepEqual(f.store.snapshot().lifecycle.outbox,{});
 assert.equal(JSON.stringify(f.store.snapshot()).includes('observed_conversation_url'),false);
});
