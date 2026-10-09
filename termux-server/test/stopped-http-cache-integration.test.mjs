import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './helpers/lifecycle-fixture.mjs';
import { digest } from '../src/lifecycle.mjs';
import { SignalDispatchTransport } from '../src/signal-transport.mjs';

test('controller STOP purges cached HTTP input and explicit resume reads fresh app metadata',async()=>{
  const f=fixture();f.body.server_ingress='HTTP';f.rows.delete(f.path);f.path='__SELFRUN_DISPATCH__HTTP_'+digest(f.body.request_id)+'.json';await f.existing();
  const base=f.transport,transport=new SignalDispatchTransport(base,f.store);f.transport=transport;
  await transport.register('old-start',f.path,f.body,'__SELFRUN_CONTROL__'+f.body.task_id+'.json',f.control);
  await f.stop();assert.deepEqual(transport.local().files,{});assert.equal(transport.local().signals['old-start'],undefined);
  const fresh={...f.body,prompt:'fresh app resume metadata'};f.rows.set(f.path,fresh);
  f.control={...f.control,state:'RUNNING',control_epoch:3,conversation_url:f.probe.url};
  await f.controller.control(null,f.control,transport);await f.controller.tick(transport);
  assert.equal(f.record().body.prompt,fresh.prompt);assert.equal(f.resumes,2);assert.equal(f.sends,0);
  await f.controller.quiesceForStandby();
});
