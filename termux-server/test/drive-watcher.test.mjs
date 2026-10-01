import test from 'node:test';
import assert from 'node:assert/strict';
import { DriveDispatchWatcher } from '../src/drive-watcher.mjs';
import { fixture } from './helpers/lifecycle-fixture.mjs';
test('watcher ingests control before dispatch regardless of listing order',async()=>{
 const f=fixture();f.control.state='STOPPED';
 const list=f.transport.list;f.transport.list=async()=>(await list()).reverse();
 const w=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
 await w.scanOnce();assert.equal(f.record().state,'STOPPED');assert.equal(f.prepares,0);
});
test('watcher retries failed ingestion without marking the file seen',async()=>{
 const f=fixture();let once=true;const ingest=f.controller.ingest.bind(f.controller);
 f.controller.ingest=async(...args)=>{if(once){once=false;throw new Error('temporary');}return ingest(...args);};
 const w=new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}});
 await assert.rejects(w.scanOnce(),/temporary/);await w.scanOnce();assert.equal(f.prepares,1);
});
test('watcher skips dispatches outside current control identity',async()=>{
 const f=fixture();f.control.request_id='other';
 await new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{}}).scanOnce();
 assert.equal(f.prepares,0);assert.equal(f.record(),null);
});
test('watcher stops before ingestion when cluster ownership is lost during read',async()=>{
 const f=fixture();let active=true;const read=f.transport.read;
 f.transport.read=async p=>{const value=await read(p);if(p===f.path)active=false;return value;};
 await new DriveDispatchWatcher({transport:f.transport,controller:f.controller,config:{},canDispatch:()=>active}).scanOnce();
 assert.equal(f.prepares,0);
});
test('unchanged folder still ticks durable pending work',async()=>{
 let ticks=0;const controller={tick:async()=>{ticks++;}};
 const w=new DriveDispatchWatcher({transport:{list:async()=>[]},controller,config:{}});
 await w.scanOnce();await w.scanOnce();assert.equal(ticks,2);
});
