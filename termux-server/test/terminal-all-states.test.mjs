import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture} from './helpers/lifecycle-fixture.mjs';
import {STATES,keyFor} from '../src/lifecycle.mjs';
for(const terminal of ['DONE','STOPPED'])for(const state of Object.keys(STATES))test(`${terminal} discards ${state} regardless of its former activity`,async t=>{
 const f=fixture();t.after(()=>f.controller.quiesceForStandby());await f.existing();
 const key=keyFor(f.body);f.controller.repository.data.requests[key].state=state;
 const signal=f.observations.at(-1).signal;
 f.control={...f.control,state:terminal,control_epoch:2};await f.controller.controlDurable(null,f.control,f.transport);
 assert.equal(f.controller.repository.get(key),null);assert.equal(f.controller.sessions.has(key),false);assert.equal(signal.aborted,true);
 for(const name of ['requests','retired','outbox','completionOutbox'])assert.equal(Object.hasOwn(f.store.snapshot().lifecycle[name]||{},key),false,name);
});
